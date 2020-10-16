package co.casterlabs.katana.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

public class ClientWebSocketConnection extends WebSocket implements AutoCloseable {
    private RemoteWebSocketConnection remote;

    public ClientWebSocketConnection(IHTTPSession nanoSession, String uri) throws InterruptedException, URISyntaxException {
        super(nanoSession);

        this.remote = new RemoteWebSocketConnection(new URI(uri), this);

        for (Map.Entry<String, String> header : this.getHandshakeRequest().getHeaders().entrySet()) {
            String key = header.getKey();
            // Prevent Nano headers from being injected
            if (!key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                this.remote.addHeader(key, header.getValue());
            }
        }
    }

    public boolean connect() throws InterruptedException {
        return this.remote.connectBlocking();
    }

    @Override
    protected void onOpen() {
        if (!this.isOpen()) {
            try {
                this.close(CloseCode.AbnormalClosure, "Remote connection encountered an error", true);
            } catch (IOException ignored) {}
        }
    }

    @Override
    protected void onPong(WebSocketFrame frame) {
        this.remote.sendPing();
    }

    @Override
    protected void onMessage(WebSocketFrame frame) {
        this.remote.send(frame.getTextPayload());
    }

    @Override
    protected void onClose(CloseCode code, String reason, boolean remote) {
        try {
            this.remote.close(code.getValue(), reason);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onException(IOException ignored) {}

    @Override
    public void close() throws Exception {
        this.remote.closeBlocking();
    }

}
