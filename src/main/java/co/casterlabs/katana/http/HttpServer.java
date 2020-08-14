package co.casterlabs.katana.http;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLServerSocketFactory;

import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Reason;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.config.SSLConfiguration;
import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.katana.server.Server;
import co.casterlabs.katana.server.Servlet;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoWSD;
import lombok.Getter;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class HttpServer implements Server {
    private static final int SOCKET_TIMEOUT = (int) TimeUnit.MINUTES.toMillis(15);

    private MultiValuedMap<String, Servlet> hostnames = new ArrayListValuedHashMap<>();
    private List<Reason> failReasons = new ArrayList<>();
    private boolean keepErrorStatus = true;
    private boolean allowInsecure = true;
    private ServerConfiguration config;
    private boolean forceHttps = false;
    private String hostnameRegex;
    private FastLogger logger;
    private Katana katana;
    private int port;

    private NanoWSD nanoSecure;
    private NanoWSD nano;

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

        for (Servlet servlet : config.getServlets()) {
            for (String host : servlet.getHosts()) {
                String regex = host.toLowerCase().replace(".", "\\.").replace("*", ".*");

                this.hostnames.put(regex, servlet);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String hostname : this.hostnames.keySet()) {
            sb.append("|(");
            sb.append(hostname);
            sb.append(")");
        }

        this.nano = new NanoWrapper(config.getPort(), false);
        this.hostnameRegex = sb.substring(1);
        this.port = config.getPort();
        this.katana = katana;
        this.config = config;

        SSLConfiguration ssl = config.getSSL();
        if ((ssl != null) && ssl.enabled) {
            try {
                File certificate = new File(ssl.certificate);
                File privateKey = new File(ssl.private_key);
                File chain = new File(ssl.chain);

                if (!certificate.exists()) {
                    this.logger.severe("Unable to find SSL certificate file.");
                } else if (!privateKey.exists()) {
                    this.logger.severe("Unable to find SSL private key file.");
                } else if (!chain.exists()) {
                    this.logger.severe("Unable to find SSL chain/ca-bundle file.");
                } else {
                    SSLServerSocketFactory factory = SSLUtil.getSocketFactoryPEM(certificate, privateKey, chain);

                    this.nanoSecure = new NanoWrapper(443, true);
                    this.nanoSecure.makeSecure(factory, null);

                    this.forceHttps = ssl.force;
                }
            } catch (Exception e) {
                this.failReasons.add(new Reason("Server cannot start due to an exception.", e));
            }
        }
    }

    @Override
    public void start() {
        if (this.failReasons.size() != 0) return;

        try {
            if ((this.nanoSecure != null) && !this.nanoSecure.isAlive()) {
                if (this.forceHttps) this.logger.info("Forcing secure connections.");

                this.nanoSecure.start(SOCKET_TIMEOUT, false);
                this.logger.info("Started secure server on port 443.");
            }
            if ((this.nano != null) && !this.nano.isAlive()) {
                this.nano.start(SOCKET_TIMEOUT, false);
                this.logger.info("Started server on port %d.", this.port);
            }
        } catch (IOException e) {
            this.failReasons.add(new Reason(String.format("Unable to bind on port."), e));
        }
    }

    @Override
    public void stop() {
        if (this.nanoSecure != null) this.nanoSecure.stop();
        if (this.nano != null) this.nano.stop();
    }

    private class NanoWrapper extends NanoWSD {
        private boolean secure;

        public NanoWrapper(int port, boolean secure) {
            super(port);

            this.secure = secure;
        }

        // Serves http sessions or calls super to serve websockets
        @Override
        public Response serve(IHTTPSession nanoSession) {
            try {
                if (this.isWebsocketRequested(nanoSession)) {
                    return super.serve(nanoSession);
                } else {
                    long start = System.currentTimeMillis();
                    String host = nanoSession.getHeaders().get("host");
                    HttpSession session = new HttpSession(nanoSession, logger, this.getListeningPort());

                    serveSession(host, session, this.secure);

                    Response response = readResponse(session);

                    double time = (System.currentTimeMillis() - start) / 1000d;
                    logger.debug("Served http %s %s (%.2fs)", session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

                    return response;
                }
            } catch (NullPointerException e) {
                String host = nanoSession.getHeaders().get("host");

                if (host == null) {
                    host = "unknown";
                }

                return Util.errorResponse(Status.BAD_REQUEST, "Unable to upgrade request", host, port);
            }
        }

        @Override
        protected WebSocket openWebSocket(IHTTPSession nanoSession) {
            long start = System.currentTimeMillis();
            String host = nanoSession.getHeaders().get("host");
            HttpSession session = new HttpSession(nanoSession, logger, this.getListeningPort());

            session.getUnsafe().setWebsocketRequest(true);
            serveSession(host, session, this.secure);

            double time = (System.currentTimeMillis() - start) / 1000d;
            logger.debug("Served websocket %s %s (%.2fs)", session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

            return session.getWebsocketResponse();
        }

    }

    private Response readResponse(HttpSession session) {
        Response response = NanoHTTPD.newChunkedResponse(session.getStatus(), null, session.getResponseStream());

        // TODO error pages

        for (Map.Entry<String, String> header : session.getResponseHeaders().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Type")) {
                session.setMime(header.getValue());
            } else if (!header.getKey().equalsIgnoreCase("Server")) {
                response.addHeader(header.getKey(), header.getValue()); // Check prevents duplicate headers
            }
        }

        response.addHeader("Server", String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic")));
        response.setMimeType(session.getMime());

        return response;
    }

    // Interacts with servlets
    private void serveSession(String host, HttpSession session, boolean secure) {
        if (host == null) {
            session.getUnsafe().setHost("unknown");
            Util.errorResponse(session, Status.BAD_REQUEST, "Request is missing \"host\" header.");
        } else {
            host = host.split(":")[0].toLowerCase();
            session.getUnsafe().setHost(host);

            if (!host.matches(hostnameRegex)) {
                Util.errorResponse(session, Status.FORBIDDEN, "Host not allowed.");
            } else if (!secure && (!this.allowInsecure || this.forceHttps)) {
                if (this.forceHttps && !session.isWebsocketRequest()) {
                    session.setStatus(Status.TEMPORARY_REDIRECT);
                    session.setResponseHeader("Location", "https://" + host + session.getUri());
                } else {
                    Util.errorResponse(session, Status.FORBIDDEN, "Insecure connections are not allowed.");
                }
            } else {
                Collection<Servlet> servlets = Util.regexGet(this.hostnames, host.toLowerCase());

                // Iterate priority configs first, if they can't serve then attempt lower priority configs
                boolean served = this.iterateConfigs(session, servlets);

                if (!served) {
                    if (!session.isWebsocketRequest()) {
                        this.logger.warn("No servlet for host %s.", host);
                    }

                    Util.errorResponse(session, Status.INTERNAL_ERROR, "No servlet available.");
                }
            }
        }
    }

    private boolean iterateConfigs(HttpSession session, Collection<Servlet> servlets) {
        for (Servlet servlet : servlets) {
            if (servlet.serve(session)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isRunning() {
        boolean nanoAlive = (this.nano != null) ? this.nano.isAlive() : false;
        boolean nanoSecureAlive = (this.nanoSecure != null) ? this.nanoSecure.isAlive() : false;

        return nanoAlive || nanoSecureAlive;
    }

    @Override
    public int[] getPorts() { // TODO make "dynamic"
        if (this.nanoSecure != null) {
            return new int[] {
                    this.port,
                    this.nanoSecure.getListeningPort()
            };
        } else {
            return new int[] {
                    this.port
            };
        }
    }

}
