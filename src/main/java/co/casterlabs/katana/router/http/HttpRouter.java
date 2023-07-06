package co.casterlabs.katana.router.http;

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
import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.http.HttpRouterConfiguration.HttpSSLConfiguration;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
import co.casterlabs.rakurai.DataSize;
import co.casterlabs.rakurai.io.IOUtil;
import co.casterlabs.rakurai.io.http.HttpMethod;
import co.casterlabs.rakurai.io.http.HttpStatus;
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
public class HttpRouter implements HttpListener, KatanaRouter<HttpRouterConfiguration> {
    public static final String ERROR_HTML = "<!DOCTYPE html><html><head><title>$RESPONSECODE</title></head><body><h1>$RESPONSECODE</h1><p>$DESCRIPTION</p><br/><p><i>Running Casterlabs Katana, $ADDRESS</i></p></body></html>";

    private static final String ALLOWED_METHODS;

    private MultiValuedMap<String, HttpServlet> hostnames = new ArrayListValuedHashMap<>();
    private boolean keepErrorStatus = true;
    private boolean allowInsecure = true;
    private HttpRouterConfiguration config;
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
    public HttpRouter(HttpRouterConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));
        this.katana = katana;
        this.config = config;

        HttpServerBuilder builder = HttpServerBuilder.get(HttpServerImplementation.RAKURAI);

        builder.setPort(this.config.getPort());
        builder.setHttp2Enabled(true);
        builder.setBehindProxy(this.config.isBehindProxy());

        this.server = builder.build(this);

        this.serverLoggers.add(this.server.getLogger());

        HttpSSLConfiguration ssl = this.config.getSSL();
        if ((ssl != null) && ssl.enabled) {
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
        }

        this.loadConfig(this.config);
    }

    @Override
    public void loadConfig(HttpRouterConfiguration config) {
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

    @SneakyThrows
    @Override
    public void start() {
        if ((this.serverSecure != null) && !this.serverSecure.isAlive()) {
            if (this.forceHttps) this.logger.info("Forcing secure connections.");

            this.serverSecure.start();
            this.logger.info("Started secure server on port %d.", this.serverSecure.getPort());
        }
        if ((this.server != null) && !this.server.isAlive()) {
            this.server.start();
            this.logger.info("Started server on port %d.", this.server.getPort());
        }
    }

    @SneakyThrows
    @Override
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
                    return HttpRouter.errorResponse(session, StandardHttpStatus.FORBIDDEN, "Insecure connections are not allowed.", this.config);
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
                return HttpRouter.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "No servlet available.", this.config);
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

    @Override
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

    public static HttpResponse errorResponse(HttpSession session, HttpStatus status, String description, @Nullable HttpRouterConfiguration config) {
        // @formatter:off
        return HttpResponse.newFixedLengthResponse(status, ERROR_HTML
                .replace("$RESPONSECODE", String.valueOf(status.getStatusCode()))
                .replace("$DESCRIPTION", description)
                .replace("$ADDRESS", String.format("%s:%d", session.getHost(), session.getPort()))
        ).setMimeType("text/html");
        // @formatter:on
    }

}
