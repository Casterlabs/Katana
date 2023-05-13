package co.casterlabs.katana.http;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Reason;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.config.HttpServerConfiguration;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.DataSize;
import co.casterlabs.rakurai.io.IOUtil;
import co.casterlabs.rakurai.io.http.HttpMethod;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpListener;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpServer;
import co.casterlabs.rakurai.io.http.server.HttpSession;
import co.casterlabs.rakurai.io.http.server.config.HttpServerBuilder;
import co.casterlabs.rakurai.io.http.server.config.HttpServerImplementation;
import co.casterlabs.rakurai.io.http.server.config.SSLConfiguration;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketSession;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

@Getter
public class HttpRouter implements HttpListener {
    private static final HttpServerImplementation SERVER_IMPLEMENTATION = HttpServerImplementation.valueOf(System.getProperty("katana.impl", "RAKURAI"));
    private static final String ALLOWED_METHODS;

    private MultiValuedMap<String, HttpServlet> hostnames = new ArrayListValuedHashMap<>();
    private List<Reason> failReasons = new ArrayList<>();
    private boolean keepErrorStatus = true;
    private boolean allowInsecure = true;
    private HttpServerConfiguration config;
    private boolean forceHttps = false;
    private FastLogger logger;
    private Katana katana;

    private HttpServer serverSecure;
    private HttpServer server;

    private List<FastLogger> serverLoggers = new ArrayList<>();

    static {
        // Buffers need to be small, since this server will be "outward" facing.
        // Unless the response is chunked, this value will effectively be
        // the maximum buffer size.
        IOUtil.DEFAULT_BUFFER_SIZE = (int) DataSize.MEGABYTE.toBytes(10);

        List<String> methods = new ArrayList<>();
        for (HttpMethod method : HttpMethod.values()) {
            methods.add(method.name());
        }

        ALLOWED_METHODS = String.join(", ", methods);
    }

    public HttpRouter(HttpServerConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));
        this.katana = katana;
        this.config = config;

        HttpServerBuilder builder = HttpServerBuilder.get(SERVER_IMPLEMENTATION);

        builder.setPort(this.config.getPort());
        builder.setHttp2Enabled(true);
        builder.setBehindProxy(this.config.isBehindProxy());

        this.server = builder.build(this);

        this.serverLoggers.add(this.server.getLogger());

        HttpServerConfiguration.SSLConfiguration ssl = this.config.getSSL();
        if ((ssl != null) && ssl.enabled) {
            try {
                File keystore = new File(ssl.keystore);

                if (!keystore.exists()) {
                    this.logger.severe("Unable to find SSL certificate file.");
                } else {
                    SSLConfiguration rakuraiConfig = new SSLConfiguration(keystore, ssl.keystorePassword.toCharArray());

                    rakuraiConfig.setDHSize(ssl.dhSize);
                    rakuraiConfig.setEnabledCipherSuites(ssl.enabledCipherSuites);
                    rakuraiConfig.setEnabledTlsVersions(ssl.tls);

                    builder.setSsl(rakuraiConfig);

                    this.forceHttps = ssl.force;
                    this.serverSecure = builder
                        .setPort(ssl.port)
                        .buildSecure(this);

                    this.serverLoggers.add(this.serverSecure.getLogger());
                }
            } catch (Exception e) {
                this.failReasons.add(new Reason("Server cannot start due to an exception.", e));
            }
        }

        this.loadConfig(this.config);
    }

    public void loadConfig(HttpServerConfiguration config) {
        this.config = config;

        this.hostnames.clear();

        for (HttpServlet servlet : this.config.getServlets()) {
            for (String host : servlet.getHostnames()) {
                String regex = host.toLowerCase().replace(".", "\\.").replace("*", ".*");

                this.hostnames.put(regex, servlet);
            }
        }

        for (FastLogger serverLogger : this.serverLoggers) {
            if (this.config.isDebugMode() || katana.getLauncher().isTrace()) {
                serverLogger.setCurrentLevel(LogLevel.ALL);
            } else {
                serverLogger.setCurrentLevel(LogLevel.WARNING); // Allows FATAL, SEVERE, and WARNING
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
        if (this.serverSecure != null) {
            this.serverSecure.stop();
            this.logger.info("Stopped secure server on port %d.", this.serverSecure.getPort());
        }
        if (this.server != null) {
            this.server.stop();
            this.logger.info("Stopped server on port %d.", this.server.getPort());
        }
    }

    // Interacts with servlets
    @Override
    public @Nullable HttpResponse serveSession(@NonNull String host, @NonNull HttpSession session, boolean secure) {
        host = host.split(":")[0];

        try {
            if (!secure && (!this.allowInsecure || this.forceHttps)) {
                if (this.forceHttps) {
                    session.getLogger().info("Redirected from http -> https.");

                    return HttpResponse.newFixedLengthResponse(StandardHttpStatus.TEMPORARY_REDIRECT)
                        .putHeader("Location", "https://" + host + session.getUri() + session.getQueryString());
                } else {
                    session.getLogger().info("Request is over http, this is forbidden.");
                    return Util.errorResponse(session, StandardHttpStatus.FORBIDDEN, "Insecure connections are not allowed.", this.config);
                }
            }

            List<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());
            Collections.sort(servlets, (HttpServlet s1, HttpServlet s2) -> {
                return s1.getPriority() > s2.getPriority() ? -1 : 1;
            });
            session.getLogger().debug("Canidate servlets: %s", servlets);

            // Browser is doing a CORS probe, let them.
            if (session.getMethod() == HttpMethod.OPTIONS) {
                HttpResponse response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.NO_CONTENT)
                    .putHeader("server", Katana.SERVER_DECLARATION);

                this.handleCors(servlets, session, response);

                return response;
            }

            HttpResponse response = this.iterateConfigs(session, servlets);

            if (response == null) {
                return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "No servlet available.", this.config);
            }

            this.handleCors(servlets, session, response);

            return response;
        } catch (Throwable t) {
            session.getLogger().exception(t);
            return HttpResponse.NO_RESPONSE;
        }
    }

    private void handleCors(Collection<HttpServlet> servlets, HttpSession session, HttpResponse response) {
        String originHeader = session.getHeader("Origin");
        response.putHeader("Server", Katana.SERVER_DECLARATION);

        if (originHeader != null) {
            String[] split = originHeader.split("://");

            if (split.length == 2) {
                String protocol = split[0];
                String referer = split[1].split("/")[0]; // Strip protocol and uri

                for (HttpServlet servlet : servlets) {
                    if (Util.regexContains(servlet.getCorsAllowedHosts(), referer)) {
                        response.putHeader("Access-Control-Allow-Origin", protocol + "://" + referer);
                        response.putHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
                        response.putHeader("Access-Control-Allow-Headers", "Authorization, *");
                        session.getLogger().debug("Added CORS declaration.");
                        break;
                    }
                }
            }
        }
    }

    // Also interacts with servlets
    @Override
    public @Nullable WebsocketListener serveWebsocketSession(@NonNull String host, @NonNull WebsocketSession session, boolean secure) {
        List<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());
        Collections.sort(servlets, (HttpServlet s1, HttpServlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });

        return this.iterateWebsocketConfigs(session, servlets);
    }

    private HttpResponse iterateConfigs(HttpSession session, Collection<HttpServlet> servlets) {
        for (HttpServlet servlet : servlets) {
            HttpResponse response = servlet.serveHttp(session, this);

            if (response != null) {
                return response;
            }
        }

        return null;
    }

    private WebsocketListener iterateWebsocketConfigs(WebsocketSession session, Collection<HttpServlet> servlets) {
        for (HttpServlet servlet : servlets) {
            WebsocketListener response = servlet.serveWebsocket(session, this);

            if (response != null) {
                return response;
            }
        }

        return null;
    }

    public boolean isRunning() {
        boolean serverAlive = (this.server != null) ? this.server.isAlive() : false;
        boolean serverSecureAlive = (this.serverSecure != null) ? this.serverSecure.isAlive() : false;

        return serverAlive || serverSecureAlive;
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
