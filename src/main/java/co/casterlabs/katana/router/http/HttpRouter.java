package co.casterlabs.katana.router.http;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.commons.async.AsyncTask;
import co.casterlabs.katana.CertificateAutoIssuer;
import co.casterlabs.katana.CertificateAutoIssuer.IssuanceException;
import co.casterlabs.katana.FileWatcher.MultiFileWatcher;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.http.HttpRouterConfiguration.HttpSSLConfiguration;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
import co.casterlabs.rhs.protocol.HttpMethod;
import co.casterlabs.rhs.protocol.HttpStatus;
import co.casterlabs.rhs.protocol.StandardHttpStatus;
import co.casterlabs.rhs.server.HttpListener;
import co.casterlabs.rhs.server.HttpResponse;
import co.casterlabs.rhs.server.HttpServer;
import co.casterlabs.rhs.server.HttpServerBuilder;
import co.casterlabs.rhs.server.SSLUtil;
import co.casterlabs.rhs.session.HttpSession;
import co.casterlabs.rhs.session.TLSVersion;
import co.casterlabs.rhs.session.WebsocketListener;
import co.casterlabs.rhs.session.WebsocketSession;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.pem.util.PemUtils;
import nl.altindag.ssl.util.KeyManagerUtils;
import nl.altindag.ssl.util.SSLSessionUtils;
import nl.altindag.ssl.util.TrustManagerUtils;
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

    private SSLFactory factory;
    private MultiFileWatcher certificateWatcher;
    private CertificateAutoIssuer autoIssuer;
    private AsyncTask certificateChecker;

    static {
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

        HttpServerBuilder builder = new HttpServerBuilder()
            .withPort(this.config.getPort())
            .withBehindProxy(this.config.isBehindProxy());

        this.server = builder.build(this);

        this.serverLoggers.add(this.server.getLogger());

        HttpSSLConfiguration ssl = this.config.getSSL();
        if ((ssl != null) && ssl.enabled) {
            if (ssl.certAutoIssuer != null && ssl.certAutoIssuer.enabled) {
                if (config.getPort() != 80) {
                    this.logger.warn("ACME will only validate certificate requests on port 80. I hope you know what you are doing...");
                }

                this.autoIssuer = new CertificateAutoIssuer(ssl);

                if (!new File(ssl.privateKeyFile).exists() || !new File(ssl.certificateFile).exists() || !new File(ssl.trustChainFile).exists()) {
                    // Uh oh, we don't have a cert _at all_. We need to spin up the server to
                    // perform validation and clean up.

                    this.logger.warn("Temporarily starting server on port %d to issue certificates.", this.server.getPort());
                    this.server.start();
                    try {
                        this.autoIssueCertificates();
                        this.logger.info("The day has been saved! Shutting down the server until we're ready to actually serve requests :^)");
                    } finally {
                        this.stop();
                    }
                }
            }

            X509ExtendedKeyManager keyManager = KeyManagerUtils.createSwappableKeyManager(
                PemUtils.loadIdentityMaterial(
                    Paths.get(ssl.trustChainFile),
                    Paths.get(ssl.privateKeyFile)
                )
            );
            X509ExtendedTrustManager trustManager = TrustManagerUtils.createSwappableTrustManager(
                PemUtils.loadTrustMaterial(
                    Paths.get(ssl.certificateFile)
                )
            );

            this.factory = SSLFactory.builder()
                .withIdentityMaterial(keyManager)
                .withTrustMaterial(trustManager)
                .withCiphers(ssl.enabledCipherSuites) // Unsupported ciphers are automatically excluded.
                .withProtocols(TLSVersion.toRuntimeNames(ssl.tls))
                .build();
            SSLUtil.applyDHSize(ssl.dhSize);

            this.forceHttps = ssl.force;
            this.serverSecure = builder
                .withPort(ssl.port)
                .withSsl(this.factory)
                .buildSecure(this);

            this.serverLoggers.add(this.serverSecure.getLogger());

            this.certificateWatcher = new MultiFileWatcher(new File(ssl.trustChainFile), new File(ssl.privateKeyFile), new File(ssl.certificateFile)) {
                @Override
                public void onChange() {
                    try {
                        TimeUnit.SECONDS.sleep(5);
                        logger.info("Detected change in certificates. Reloading SSL...");
                        X509ExtendedKeyManager keyManager = PemUtils.loadIdentityMaterial(
                            Paths.get(ssl.trustChainFile),
                            Paths.get(ssl.privateKeyFile)
                        );
                        X509ExtendedTrustManager trustManager = PemUtils.loadTrustMaterial(
                            Paths.get(ssl.certificateFile)
                        );

                        KeyManagerUtils.swapKeyManager(factory.getKeyManager().get(), keyManager);
                        TrustManagerUtils.swapTrustManager(factory.getTrustManager().get(), trustManager);
                        SSLSessionUtils.invalidateCaches(factory.getSslContext());
                        logger.info("SSL reloaded successfully!");
                    } catch (Throwable t) {
                        logger.fatal("Could not reload certificates:\n%s", t);
                    }
                }
            };
        }

        this.loadConfig(this.config);
    }

    private void autoIssueCertificates() throws IssuanceException {
        if (this.autoIssuer == null) return;
        Set<String> frontFacing = this.config.getAllFrontFacingDomains();
        frontFacing.remove("*"); // This won't work :P
        this.autoIssuer.reissue(frontFacing);
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
        if (this.certificateWatcher != null) {
            this.certificateWatcher.start();
        }
        if (this.autoIssuer == null) {
            this.certificateChecker = AsyncTask.create(() -> {
                final Date MONTH_FROM_NOW = Date.from(Instant.now().plus(28, ChronoUnit.DAYS));

                while (true) {
                    try {
                        TimeUnit.HOURS.sleep(8);
                    } catch (InterruptedException ignored) {
                        return;
                    }

                    X509ExtendedKeyManager keyManager = this.factory.getKeyManager().get();
                    String[] aliases = keyManager.getClientAliases("RSA", null);

                    boolean certsGoingToExpire = false;
                    for (String alias : aliases) {
                        X509Certificate[] chain = keyManager.getCertificateChain(alias);
                        for (X509Certificate cert : chain) {
                            try {
                                cert.checkValidity(MONTH_FROM_NOW);
                            } catch (CertificateExpiredException e) {
                                certsGoingToExpire = true;
                            } catch (CertificateNotYetValidException ignored) {}
                        }
                    }

                    if (!certsGoingToExpire) {
                        continue; // We'll check again in a little bit.
                    }

                    try {
                        logger.info("Certificates are going to expire! Renewing automagically...");
                        this.autoIssueCertificates();
                        logger.info("Certificates renewed successfully! Auto swapping them in a few seconds.");
                    } catch (IssuanceException e) {
                        logger.fatal("Couldn't renew certificate. This is bad!\n%s", e);
                    }
                }
            });
        }
    }

    @SneakyThrows
    @Override
    public void stop() {
        if (this.certificateChecker != null) {
            this.certificateChecker.cancel();
            this.certificateChecker = null;
        }
        if (this.certificateWatcher != null) {
            this.certificateWatcher.close();
        }
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
    public @Nullable HttpResponse serveHttpSession(@NonNull HttpSession session) {
        try {
            // This one's special! It needs to always bypass SSL redirects.
            if (session.getUri().startsWith("/.well-known/acme-challenge/")) {
                String token = session.getUri().substring("/.well-known/acme-challenge/".length());
                String challenge = CertificateAutoIssuer.activeChallenges.get(token);

                if (challenge == null) {
                    return HttpResponse
                        .newFixedLengthResponse(StandardHttpStatus.NOT_FOUND, "acme challenge not found")
                        .setMimeType("text/plain");
                } else {
                    return HttpResponse
                        .newFixedLengthResponse(StandardHttpStatus.OK, challenge)
                        .setMimeType("text/plain");
                }
            }

            if ((session.getTLSVersion() == null) && (!this.allowInsecure || this.forceHttps)) {
                if (this.forceHttps) {
                    session.getLogger().info("Redirected from http -> https.");

                    return HttpResponse.newFixedLengthResponse(StandardHttpStatus.TEMPORARY_REDIRECT)
                        .putHeader("Location", "https://" + session.getHost() + session.getUri() + session.getQueryString());
                } else {
                    session.getLogger().info("Request is over http, this is forbidden.");
                    return HttpRouter.errorResponse(session, StandardHttpStatus.FORBIDDEN, "Insecure connections are not allowed.", this.config);
                }
            }

            List<HttpServlet> servlets = Util.regexGet(this.hostnames, session.getHost().toLowerCase());
            Collections.sort(servlets, (HttpServlet s1, HttpServlet s2) -> {
                return s1.getPriority() > s2.getPriority() ? -1 : 1;
            });
            session.getLogger().debug("Canidate servlets: %s", servlets);

            // Browser is doing a CORS probe, let them.
            if (session.getMethod() == HttpMethod.OPTIONS) {
                HttpResponse response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.NO_CONTENT);
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
    public @Nullable WebsocketListener serveWebsocketSession(@NonNull WebsocketSession session) {
        List<HttpServlet> servlets = Util.regexGet(this.hostnames, session.getHost().toLowerCase());
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
