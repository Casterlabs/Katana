package co.casterlabs.katana.http.nano;

import java.util.Map;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpListener;
import co.casterlabs.katana.http.HttpServer;
import co.casterlabs.katana.http.HttpSession;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoWSD;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class NanoWrapper extends NanoWSD implements HttpListener {
    private FastLogger logger = new FastLogger();
    private HttpServer server;
    private boolean secure;

    public NanoWrapper(HttpServer server, int port) {
        super(port);

        this.setAsyncRunner(new NanoRunner());

        this.secure = false;
        this.server = server;
    }

    public NanoWrapper(HttpServer server, int port, WrappedSSLSocketFactory factory, String[] tls) {
        super(port);

        this.makeSecure(factory, tls);
        this.setAsyncRunner(new NanoRunner());

        this.secure = true;
        this.server = server;
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

                this.server.serveSession(host, session, this.secure);

                Response response = readResponse(session);

                double time = (System.currentTimeMillis() - start) / 1000d;
                logger.debug("Served HTTP %s %s %s (%.2fs)", session.getMethod().name(), session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

                return response;
            }
        } catch (NullPointerException e) {
            String host = nanoSession.getHeaders().get("host");

            if (host == null) {
                host = "unknown";
            }

            return Util.errorResponse(Status.BAD_REQUEST, "Unable to upgrade request", host, this.getListeningPort());
        }
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession nanoSession) {
        long start = System.currentTimeMillis();
        String host = nanoSession.getHeaders().get("host");
        HttpSession session = new HttpSession(nanoSession, logger, this.getListeningPort());

        session.getUnsafe().setWebsocketRequest(true);
        this.server.serveSession(host, session, this.secure);

        double time = (System.currentTimeMillis() - start) / 1000d;
        logger.debug("Served websocket %s %s (%.2fs)", session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

        return session.getWebsocketResponse();
    }

    private Response readResponse(HttpSession session) {
        Response response = NanoHTTPD.newChunkedResponse(session.getStatus(), null, session.getResponseStream());

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

    @Override
    public int getPort() {
        return this.getListeningPort();
    }

}
