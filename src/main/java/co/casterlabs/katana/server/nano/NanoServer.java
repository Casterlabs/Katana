package co.casterlabs.katana.server.nano;

import java.util.Map;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.HttpListener;
import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpResponse.TransferEncoding;
import co.casterlabs.katana.http.HttpServer;
import co.casterlabs.katana.http.HttpStatus;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import co.casterlabs.katana.server.WrappedSSLSocketFactory;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;
import fi.iki.elonen.NanoWSD;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class NanoServer extends NanoWSD implements HttpListener {
    private FastLogger logger = new FastLogger();
    private HttpServer server;
    private boolean secure;

    public NanoServer(HttpServer server, int port) {
        super(port);

        this.setAsyncRunner(new NanoRunner());

        this.secure = false;
        this.server = server;
    }

    public NanoServer(HttpServer server, int port, WrappedSSLSocketFactory factory, String[] tls) {
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
                throw new RuntimeException(); // Drop connection.
            }
        } else {
            long start = System.currentTimeMillis();
            NanoHttpSession session = new NanoHttpSession(nanoSession, logger, this.getListeningPort());

            HttpResponse response = this.server.serveSession(session.getHost(), session, this.secure);

            if (response.getStatus() == HttpStatus.NO_RESPONSE) {
                logger.debug("Dropped HTTP %s %s %s", session.getMethod().name(), session.getRemoteIpAddress(), session.getHost() + session.getUri());
                throw new RuntimeException(); // Drop connection.
            }

            String mime = response.getAllHeaders().getOrDefault("content-type", "text/plaintext");

            //@formatter:off
            IStatus status = convertStatus(response.getStatus());
            Response nanoResponse = (response.getMode() == TransferEncoding.CHUNKED) ?
                    NanoHTTPD.newChunkedResponse(status, mime, response.getResponseStream()) :
                    NanoHTTPD.newFixedLengthResponse(status, mime, response.getResponseStream(), response.getLength());
            //@formatter:off

            for (Map.Entry<String, String> header : response.getAllHeaders().entrySet()) {
                if (!header.getKey().equalsIgnoreCase("server") && !header.getKey().equalsIgnoreCase("content-type") && !header.getKey().equalsIgnoreCase("content-length")) {
                    nanoResponse.addHeader(header.getKey(), header.getValue()); // Check prevents duplicate headers
                }
            }

            nanoResponse.addHeader("Server", String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic")));

            double time = (System.currentTimeMillis() - start) / 1000d;
            logger.debug("Served HTTP %s %s %s (%.2fs)", session.getMethod().name(), session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

            return nanoResponse;
        }
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession nanoSession) {
        long start = System.currentTimeMillis();
        NanoWebsocketSessionWrapper session = new NanoWebsocketSessionWrapper(nanoSession, logger, this.getListeningPort());

        WebsocketListener listener = this.server.serveWebsocketSession(session.getHost(), session, this.secure);

        if (listener != null) {
            double time = (System.currentTimeMillis() - start) / 1000d;
            logger.debug("Served websocket %s %s (%.2fs)", session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

            return new NanoWebsocketWrapper(nanoSession, listener);
        } else {
            logger.debug("Dropped websocket %s %s", session.getRemoteIpAddress(), session.getHost() + session.getUri());
            return null;
        }
    }

    @Override
    public int getPort() {
        return this.getListeningPort();
    }

    private static IStatus convertStatus(HttpStatus status) {
        return new IStatus() {
            @Override
            public String getDescription() {
                return status.getStatusString(); // What the hell Nano
            }

            @Override
            public int getRequestStatus() {
                return status.getStatusCode();
            }
        };
    }

}
