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

import co.casterlabs.commons.async.AsyncTask;
import co.casterlabs.katana.CertificateAutoIssuer;
import co.casterlabs.katana.CertificateAutoIssuer.IssuanceException;
import co.casterlabs.katana.FileWatcher.MultiFileWatcher;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.http.HttpRouterConfiguration.HttpSSLConfiguration;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
import co.casterlabs.rhs.HttpMethod;
import co.casterlabs.rhs.HttpServer;
import co.casterlabs.rhs.HttpServerBuilder;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.TLSVersion;
import co.casterlabs.rhs.protocol.DropConnectionException;
import co.casterlabs.rhs.protocol.HttpException;
import co.casterlabs.rhs.protocol.http.HttpProtocol;
import co.casterlabs.rhs.protocol.http.HttpProtocol.HttpProtoHandler;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import co.casterlabs.rhs.protocol.websocket.WebsocketProtocol;
import co.casterlabs.rhs.protocol.websocket.WebsocketProtocol.WebsocketHandler;
import co.casterlabs.rhs.protocol.websocket.WebsocketResponse;
import co.casterlabs.rhs.protocol.websocket.WebsocketSession;
import co.casterlabs.rhs.util.SSLUtil;
import lombok.Getter;
import lombok.SneakyThrows;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.pem.util.PemUtils;
import nl.altindag.ssl.util.KeyManagerUtils;
import nl.altindag.ssl.util.SSLSessionUtils;
import nl.altindag.ssl.util.TrustManagerUtils;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

@Getter
public class HttpRouter implements HttpProtoHandler, WebsocketHandler, KatanaRouter<HttpRouterConfiguration> {
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

    @SneakyThrows
    public HttpRouter(HttpRouterConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));
        this.katana = katana;
        this.config = config;

        HttpServerBuilder builder = new HttpServerBuilder()
            .withPort(this.config.getPort())
            .withBehindProxy(this.config.isBehindProxy())
            .withServerHeader(Katana.SERVER_DECLARATION)
            .withTaskExecutor(RakuraiTaskExecutor.INSTANCE)
            .with(new HttpProtocol(), this)
            .with(new WebsocketProtocol(), this);

        this.server = builder.build();

        this.serverLoggers.add(this.server.logger());

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

                    this.logger.warn("Temporarily starting server on port %d to issue certificates.", this.server.port());
                    this.server.start();
                    try {
                        this.autoIssueCertificates();
                        this.logger.info("The day has been saved! Shutting down the server until we're ready to actually serve requests :^)");
                    } finally {
                        this.stop(true);
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
                .build();

            this.serverLoggers.add(this.serverSecure.logger());

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
            this.logger.info("Started secure server on port %d.", this.serverSecure.port());
        }
        if ((this.server != null) && !this.server.isAlive()) {
            this.server.start();
            this.logger.info("Started server on port %d.", this.server.port());
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
    public void stop(boolean disconnectClients) {
        if (this.certificateChecker != null) {
            this.certificateChecker.cancel();
            this.certificateChecker = null;
        }
        if (this.certificateWatcher != null) {
            this.certificateWatcher.close();
        }
        if (this.serverSecure != null) {
            this.serverSecure.stop(disconnectClients);
            this.logger.info("Stopped secure server on port %d.", this.serverSecure.port());
        }
        if (this.server != null) {
            this.server.stop(disconnectClients);
            this.logger.info("Stopped server on port %d.", this.server.port());
        }
    }

    // Interacts with servlets
    @Override
    public HttpResponse handle(HttpSession session) throws HttpException, DropConnectionException {
        // This one's special! It needs to always bypass SSL redirects.
        if (session.uri().path.startsWith("/.well-known/acme-challenge/")) {
            String token = session.uri().path.substring("/.well-known/acme-challenge/".length());
            String challenge = CertificateAutoIssuer.activeChallenges.get(token);

            if (challenge == null) {
                return HttpResponse
                    .newFixedLengthResponse(StandardHttpStatus.NOT_FOUND, "acme challenge not found")
                    .mime("text/plain");
            } else {
                return HttpResponse
                    .newFixedLengthResponse(StandardHttpStatus.OK, challenge)
                    .mime("text/plain");
            }
        }

        if (session.tlsVersion() == null) {
            if (this.forceHttps) {
                session.logger().info("Redirected from http -> https.");
                return HttpResponse.newFixedLengthResponse(StandardHttpStatus.TEMPORARY_REDIRECT)
                    .header("Location", "https://" + session.uri().toString());
            } else if (!this.allowInsecure) {
                session.logger().info("Request is over http, this is forbidden.");
                return HttpUtil.errorResponse(session, StandardHttpStatus.FORBIDDEN, "Insecure connections are not allowed.");
            }
        }

        List<HttpServlet> servlets = Util.regexGet(this.hostnames, session.uri().host.toLowerCase());
        Collections.sort(servlets, (HttpServlet s1, HttpServlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });
        session.logger().debug("Canidate servlets: %s", servlets);

        // Browser is doing a CORS probe, let them.
        if (session.method() == HttpMethod.OPTIONS) {
            HttpResponse response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.NO_CONTENT);
            HttpUtil.handleCors(servlets, session, response);
            return response;
        }

        HttpResponse response = this.iterateConfigs(session, servlets);

        if (response == null) {
            return HttpUtil.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "No servlet available.");
        }

        HttpUtil.handleCors(servlets, session, response);

        return response;
    }

    // Also interacts with servlets
    @Override
    public WebsocketResponse handle(WebsocketSession session) {
        List<HttpServlet> servlets = Util.regexGet(this.hostnames, session.uri().host.toLowerCase());
        Collections.sort(servlets, (HttpServlet s1, HttpServlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });

        return this.iterateWebsocketConfigs(session, servlets);
    }

    @SneakyThrows
    private HttpResponse iterateConfigs(HttpSession session, Collection<HttpServlet> servlets) {
        for (HttpServlet servlet : servlets) {
            if (servlet.matchHttp(session, this)) {
                return servlet.serveHttp(session, this);
            }
        }

        return null;
    }

    @SneakyThrows
    private WebsocketResponse iterateWebsocketConfigs(WebsocketSession session, Collection<HttpServlet> servlets) {
        for (HttpServlet servlet : servlets) {
            if (servlet.matchWebsocket(session, this)) {
                return servlet.serveWebsocket(session, this);
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
                    this.server.port(),
                    this.serverSecure.port()
            };
        } else {
            return new int[] {
                    this.server.port()
            };
        }
    }

}
