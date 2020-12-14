package co.casterlabs.katana.http.nano;

import java.util.Map;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpListener;
import co.casterlabs.katana.http.HttpServer;
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
        if (this.isWebsocketRequested(nanoSession)) {
            try {
                return super.serve(nanoSession);
            } catch (NullPointerException e) { // Happens when no servlet set the websocket response.
                return Util.errorResponse(Status.BAD_REQUEST, "Unable to upgrade request.", nanoSession.getHeaders().getOrDefault("host", "UNKNOWN"), this.getListeningPort());
            }
        } else {
            long start = System.currentTimeMillis();
            NanoHttpSession session = new NanoHttpSession(nanoSession, logger, this.getListeningPort(), false);

            this.server.serveSession(session.getHost(), session, this.secure);

            Response response = session.getNanoResponse();

            for (Map.Entry<String, String> header : session.getResponseHeaders().entrySet()) {
                if (!header.getKey().equalsIgnoreCase("Server")) {
                    response.addHeader(header.getKey(), header.getValue()); // Check prevents duplicate headers
                }
            }

            response.addHeader("Server", String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic")));

            double time = (System.currentTimeMillis() - start) / 1000d;
            logger.debug("Served HTTP %s %s %s (%.2fs)", session.getMethod().name(), session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

            return response;
        }
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession nanoSession) {
        long start = System.currentTimeMillis();
        NanoHttpSession session = new NanoHttpSession(nanoSession, logger, this.getListeningPort(), true);

        this.server.serveSession(session.getHost(), session, this.secure);

        double time = (System.currentTimeMillis() - start) / 1000d;
        logger.debug("Served websocket %s %s (%.2fs)", session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

        return session.getWebsocketResponse();
    }

    @Override
    public int getPort() {
        return this.getListeningPort();
    }

}
