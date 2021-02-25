package co.casterlabs.katana.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Reason;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.config.SSLConfiguration;
import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import co.casterlabs.katana.http.websocket.WebsocketSession;
import co.casterlabs.katana.server.Server;
import co.casterlabs.katana.server.WrappedSSLSocketFactory;
import co.casterlabs.katana.server.nano.NanoServer;
import co.casterlabs.katana.server.undertow.UndertowServer;
import fi.iki.elonen.NanoHTTPD;
import lombok.Getter;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class HttpServer implements Server {
    private MultiValuedMap<String, HttpServlet> hostnames = new ArrayListValuedHashMap<>();
    private List<Reason> failReasons = new ArrayList<>();
    private boolean keepErrorStatus = true;
    private boolean allowInsecure = true;
    private ServerConfiguration config;
    private boolean forceHttps = false;
    private FastLogger logger;
    private Katana katana;

    private HttpListener listenerSecure;
    private HttpListener listener;

    static {
        try {
            Field field = NanoHTTPD.class.getDeclaredField("LOG");

            field.setAccessible(true);

            Logger log = (Logger) field.get(null);

            log.setLevel(Level.OFF); // Shush
        } catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException e) {
            e.printStackTrace();
        }
    }

    public HttpServer(ServerConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("HttpServer (%s)", config.getName()));

        this.loadConfig(config);

        this.katana = katana;
        this.config = config;

        switch (this.katana.getLauncher().getImplementation()) {
            case NANO:
                this.logger.info("Using Nano as the server implementation.");
                this.listener = new NanoServer(this, config.getPort());
                break;

            case UNDERTOW:
                this.logger.info("Using Undertow as the server implementation.");
                this.listener = new UndertowServer(this, config.getPort());
                break;

        }

        SSLConfiguration ssl = config.getSSL();
        if ((ssl != null) && ssl.enabled) {
            try {
                File certificate = new File(ssl.keystore);

                if (!certificate.exists()) {
                    this.logger.severe("Unable to find SSL certificate file.");
                } else {
                    // https://www.java.com/en/configure_crypto.html
                    // https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#customizing_dh_keys
                    System.setProperty("jdk.tls.ephemeralDHKeySize", String.valueOf(ssl.dh_size));
                    String disabledAlgorithmsProperty = System.getProperty("jdk.tls.disabledAlgorithms", "DH keySize");
                    String[] disabledAlgorithms = disabledAlgorithmsProperty.split(",");
                    boolean replacedParameter = false;

                    for (int i = 0; i != disabledAlgorithms.length; i++) {
                        if (disabledAlgorithms[i].startsWith("DH keySize")) {
                            replacedParameter = true;

                            disabledAlgorithms[i] = "DH keySize < " + ssl.dh_size;

                            break;
                        }
                    }

                    if (replacedParameter) {
                        System.setProperty("jdk.tls.disabledAlgorithms", String.join(", ", disabledAlgorithms));
                    } else {
                        System.setProperty("jdk.tls.disabledAlgorithms", disabledAlgorithmsProperty + ", DH keySize < " + ssl.dh_size);
                    }

                    this.logger.debug("Ephemeral DH Key Size: %s", ssl.dh_size);

                    KeyStore keystore = KeyStore.getInstance("jks");
                    keystore.load(new FileInputStream(certificate), ssl.keystore_password.toCharArray());

                    KeyManagerFactory managerFactory = KeyManagerFactory.getInstance("SunX509");
                    managerFactory.init(keystore, ssl.key_password.toCharArray());

                    this.forceHttps = ssl.force;

                    String[] tls = Util.convertTLS(ssl.tls);

                    switch (this.katana.getLauncher().getImplementation()) {
                        case NANO:
                            SSLServerSocketFactory factory = NanoHTTPD.makeSSLSocketFactory(keystore, managerFactory);
                            this.listenerSecure = new NanoServer(this, 443, new WrappedSSLSocketFactory(factory, ssl), tls);
                            break;

                        case UNDERTOW:
                            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                            trustManagerFactory.init(keystore);

                            this.listener = new UndertowServer(this, 443, managerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), tls, ssl.enabled_cipher_suites);
                            break;

                    }
                }
            } catch (Exception e) {
                this.failReasons.add(new Reason("Server cannot start due to an exception.", e));
            }
        }
    }

    @Override
    public void loadConfig(ServerConfiguration config) {
        this.hostnames.clear();

        for (HttpServlet servlet : config.getServlets()) {
            for (String host : servlet.getHosts()) {
                String regex = host.toLowerCase().replace(".", "\\.").replace("*", ".*");

                this.hostnames.put(regex, servlet);
            }
        }
    }

    @Override
    public void start() {
        if (this.failReasons.size() != 0) return;

        try {
            if ((this.listenerSecure != null) && !this.listenerSecure.isAlive()) {
                if (this.forceHttps) this.logger.info("Forcing secure connections.");

                this.listenerSecure.start();
                this.logger.info("Started secure server on port %d.", this.listenerSecure.getPort());
            }
            if ((this.listener != null) && !this.listener.isAlive()) {
                this.listener.start();
                this.logger.info("Started server on port %d.", this.listener.getPort());
            }
        } catch (IOException e) {
            this.failReasons.add(new Reason(String.format("Unable to bind on port."), e));
        }
    }

    @SneakyThrows
    @Override
    public void stop() {
        if (this.listenerSecure != null) this.listenerSecure.stop();
        if (this.listener != null) this.listener.stop();
    }

    // Interacts with servlets
    public HttpResponse serveSession(String host, HttpSession session, boolean secure) {
        if (host == null) {
            return Util.errorResponse(session, HttpStatus.BAD_REQUEST, "Request is missing \"host\" header.");
        } else {
            if (!secure && (!this.allowInsecure || this.forceHttps)) {
                if (this.forceHttps) {
                    HttpResponse response = HttpResponse.newFixedLengthResponse(HttpStatus.TEMPORARY_REDIRECT, new byte[0]);

                    response.putHeader("Location", "https://" + host + session.getUri() + session.getQueryString());

                    return response;
                } else {
                    return Util.errorResponse(session, HttpStatus.FORBIDDEN, "Insecure connections are not allowed.");
                }
            } else {
                Collection<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());
                HttpResponse response = this.iterateConfigs(session, servlets);

                // Allow CORS
                String refererHeader = session.getHeader("Referer");
                if (response != null) {
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
                    return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, "No servlet available.");
                }
            }
        }
    }

    // Also interacts with servlets
    public WebsocketListener serveWebsocketSession(String host, WebsocketSession session, boolean secure) {
        if (host == null) {
            return null;
        } else {
            Collection<HttpServlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());

            return this.iterateWebsocketConfigs(session, servlets);
        }
    }

    private HttpResponse iterateConfigs(HttpSession session, Collection<HttpServlet> servlets) {
        HttpResponse response = null;

        for (HttpServlet servlet : servlets) {
            response = servlet.serveHttp(session);

            if (response != null) {
                break;
            }
        }

        return response;
    }

    private WebsocketListener iterateWebsocketConfigs(WebsocketSession session, Collection<HttpServlet> servlets) {
        WebsocketListener response = null;

        for (HttpServlet servlet : servlets) {
            response = servlet.serveWebsocket(session);

            if (response != null) {
                break;
            }
        }

        return response;
    }

    @Override
    public boolean isRunning() {
        boolean nanoAlive = (this.listener != null) ? this.listener.isAlive() : false;
        boolean nanoSecureAlive = (this.listenerSecure != null) ? this.listenerSecure.isAlive() : false;

        return nanoAlive || nanoSecureAlive;
    }

    @Override
    public int[] getPorts() {
        if (this.listenerSecure != null) {
            return new int[] {
                    this.listener.getPort(),
                    this.listenerSecure.getPort()
            };
        } else {
            return new int[] {
                    this.listener.getPort()
            };
        }
    }

}
