package co.casterlabs.katana.http;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
import co.casterlabs.rakurai.io.http.HttpMethod;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpListener;
import co.casterlabs.rakurai.io.http.server.HttpServer;
import co.casterlabs.rakurai.io.http.server.HttpServerBuilder;
import co.casterlabs.rakurai.io.http.server.HttpServerImplementation;
import co.casterlabs.rakurai.io.http.server.SSLConfiguration;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;
import xyz.e3ndr.reflectionlib.ReflectionLib;

@Getter
public class HttpRouter implements HttpListener {
    private static final HttpServerImplementation SERVER_IMPLEMENTATION = HttpServerImplementation.UNDERTOW;
    private static final String ALLOWED_METHODS;

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

    @SneakyThrows
    private static FastLogger getLoggerFromServer(HttpServer server) {
        return ReflectionLib.getValue(server, "logger");
    }

    public HttpRouter(ServerConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));
        this.katana = katana;
        this.config = config;

        HttpServerBuilder builder = HttpServerBuilder.get(SERVER_IMPLEMENTATION);

        builder.setPort(this.config.getPort());
        builder.setHttp2Enabled(true);
        builder.setBehindProxy(this.config.isBehindProxy());
        builder.setLogsDir(this.config.getLogsDir());

        this.server = builder.build(this);

        this.serverLoggers.add(getLoggerFromServer(this.server));

        ServerConfiguration.SSLConfiguration ssl = this.config.getSSL();
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
                    rakuraiConfig.setPort(ssl.port);

                    builder.setSsl(rakuraiConfig);

                    this.forceHttps = ssl.force;
                    this.serverSecure = builder.buildSecure(this);

                    this.serverLoggers.add(getLoggerFromServer(this.serverSecure));
                }
            } catch (Exception e) {
                this.failReasons.add(new Reason("Server cannot start due to an exception.", e));
            }
        }

        this.loadConfig(this.config);
    }

    public void loadConfig(ServerConfiguration config) {
        this.config = config;

        this.hostnames.clear();

        for (HttpServlet servlet : this.config.getServlets()) {
            for (String host : servlet.getHosts()) {
                String regex = host.toLowerCase().replace(".", "\\.").replace("*", ".*");

                this.hostnames.put(regex, servlet);
            }
        }

        for (FastLogger serverLogger : this.serverLoggers) {
            if (this.config.isDebugMode() || katana.getLauncher().isDebug()) {
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
        if (this.serverSecure != null) this.serverSecure.stop();
        if (this.server != null) this.server.stop();
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

            Collection<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());

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
        response.putHeader("server", Katana.SERVER_DECLARATION);

        if (originHeader != null) {
            String[] split = originHeader.split("://");

            if (split.length == 2) {
                String protocol = split[0];
                String referer = split[1].split("/")[0]; // Strip protocol and uri

                for (HttpServlet servlet : servlets) {
                    if (Util.regexContains(servlet.getAllowedHosts(), referer)) {
                        response.putHeader("Access-Control-Allow-Origin", protocol + "://" + referer);
                        response.putHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
                        response.putHeader("Access-Control-Allow-Headers", "Authorization, *");
                        session.getLogger().info("Added CORS declaration.");
                        break;
                    }
                }
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
