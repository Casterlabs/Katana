package co.casterlabs.katana.http;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Reason;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.DataSize;
import co.casterlabs.rakurai.io.IOUtil;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpListener;
import co.casterlabs.rakurai.io.http.server.HttpServer;
import co.casterlabs.rakurai.io.http.server.HttpServerBuilder;
import co.casterlabs.rakurai.io.http.server.SSLConfiguration;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import fi.iki.elonen.NanoHTTPD;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class HttpRouter implements HttpListener {
    private MultiValuedMap<String, HttpServlet> hostnames = new ArrayListValuedHashMap<>();
    private List<Reason> failReasons = new ArrayList<>();
    private boolean keepErrorStatus = true;
    private boolean allowInsecure = true;
    private ServerConfiguration config;
    private boolean forceHttps = false;
    private FastLogger logger;
    private Katana katana;

    private HttpServer serverSecure;
    private HttpServer server;

    static {
        // Buffers need to be small, since this server will be "outward" facing.
        // Unless the response is chunked, this value will effectively be
        // the maximum buffer size.
        IOUtil.DEFAULT_BUFFER_SIZE = (int) DataSize.MEGABYTE.toBytes(10);

        try {
            Field field = NanoHTTPD.class.getDeclaredField("LOG");

            field.setAccessible(true);

            Logger log = (Logger) field.get(null);

            log.setLevel(Level.OFF); // Shush
        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
            e.printStackTrace();
        }
    }

    public HttpRouter(ServerConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));

        this.loadConfig(config);

        this.katana = katana;
        this.config = config;

        HttpServerBuilder builder = HttpServerBuilder.get(this.katana.getLauncher().getImplementation());

        builder.setPort(config.getPort());
        builder.setHttp2Enabled(true);

        this.server = builder.build(this);

        ServerConfiguration.SSLConfiguration ssl = config.getSSL();
        if ((ssl != null) && ssl.enabled) {
            try {
                File certificate = new File(ssl.keystore);

                if (!certificate.exists()) {
                    this.logger.severe("Unable to find SSL certificate file.");
                } else {
                    SSLConfiguration rakurai = new SSLConfiguration(certificate, ssl.keystore_password.toCharArray());

                    rakurai.setDHSize(ssl.dh_size);
                    rakurai.setEnabledCipherSuites(ssl.enabled_cipher_suites);
                    rakurai.setEnabledTlsVersions(ssl.tls);
                    rakurai.setPort(ssl.port);

                    this.forceHttps = ssl.force;

                    this.serverSecure = builder.buildSecure(this);
                }
            } catch (Exception e) {
                this.failReasons.add(new Reason("Server cannot start due to an exception.", e));
            }
        }
    }

    public void loadConfig(ServerConfiguration config) {
        this.hostnames.clear();

        for (HttpServlet servlet : config.getServlets()) {
            for (String host : servlet.getHosts()) {
                String regex = host.toLowerCase().replace(".", "\\.").replace("*", ".*");

                this.hostnames.put(regex, servlet);
            }
        }
    }

    public void start() {
        if (this.failReasons.size() != 0) return;

        try {
            if ((this.serverSecure != null) && !this.serverSecure.isAlive()) {
                if (this.forceHttps) this.logger.info("Forcing secure connections.");

                this.serverSecure.start();
                this.logger.info("Started secure server on port %d.", this.serverSecure.getPort());
            }
            if ((this.server != null) && !this.server.isAlive()) {
                this.server.start();
                this.logger.info("Started server on port %d.", this.server.getPort());
            }
        } catch (IOException e) {
            this.failReasons.add(new Reason(String.format("Unable to bind on port."), e));
        }
    }

    @SneakyThrows
    public void stop() {
        if (this.serverSecure != null) this.serverSecure.stop();
        if (this.server != null) this.server.stop();
    }

    // Interacts with servlets
    @Override
    public @Nullable HttpResponse serveSession(@NonNull String host, @NonNull HttpSession session, boolean secure) {
        if (!secure && (!this.allowInsecure || this.forceHttps)) {
            if (this.forceHttps) {
                HttpResponse response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.TEMPORARY_REDIRECT, new byte[0]);

                response.putHeader("Location", "https://" + host + session.getUri() + session.getQueryString());

                return response;
            } else {
                return Util.errorResponse(session, StandardHttpStatus.FORBIDDEN, "Insecure connections are not allowed.", this.config);
            }
        } else {
            Collection<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());
            HttpResponse response = this.iterateConfigs(session, servlets);

            // Allow CORS
            String refererHeader = session.getHeader("Referer");
            if (response != null) {
                response.putHeader("server", Katana.SERVER_DECLARATION);

                if (refererHeader != null) {
                    String[] split = refererHeader.split("://");
                    String protocol = split[0];
                    String referer = split[1].split("/")[0]; // Strip protocol and uri

                    for (HttpServlet servlet : servlets) {
                        if (Util.regexContains(servlet.getAllowedHosts(), referer)) {
                            response.putHeader("Access-Control-Allow-Origin", protocol + "://" + referer);
                            this.logger.debug("Set CORS header for %s", referer);
                            break;
                        }
                    }
                }

                return response;
            } else {
                return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "No servlet available.", this.config);
            }
        }
    }

    // Also interacts with servlets
    @Override
    public @Nullable WebsocketListener serveWebsocketSession(@NonNull String host, @NonNull WebsocketSession session, boolean secure) {
        Collection<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());

        return this.iterateWebsocketConfigs(session, servlets);
    }

    private HttpResponse iterateConfigs(HttpSession session, Collection<HttpServlet> servlets) {
        HttpResponse response = null;

        for (HttpServlet servlet : servlets) {
            response = servlet.serveHttp(session, this);

            if (response != null) {
                break;
            }
        }

        return response;
    }

    private WebsocketListener iterateWebsocketConfigs(WebsocketSession session, Collection<HttpServlet> servlets) {
        WebsocketListener response = null;

        for (HttpServlet servlet : servlets) {
            response = servlet.serveWebsocket(session, this);

            if (response != null) {
                break;
            }
        }

        return response;
    }

    public boolean isRunning() {
        boolean nanoAlive = (this.server != null) ? this.server.isAlive() : false;
        boolean nanoSecureAlive = (this.serverSecure != null) ? this.serverSecure.isAlive() : false;

        return nanoAlive || nanoSecureAlive;
    }

    public int[] getPorts() {
        if (this.serverSecure != null) {
            return new int[] {
                    this.server.getPort(),
                    this.serverSecure.getPort()
            };
        } else {
            return new int[] {
                    this.server.getPort()
            };
        }
    }

}
