package co.casterlabs.katana.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;
import lombok.SneakyThrows;

public class ClientWebSocketConnection extends WebSocket {
    private RemoteWebSocketConnection remote;
    private boolean connected = false;

    @SneakyThrows
    public ClientWebSocketConnection(IHTTPSession nanoSession, String uri) {
        super(nanoSession);

        this.remote = new RemoteWebSocketConnection(new URI(uri), this);

        for (Map.Entry<String, String> header : this.getHandshakeRequest().getHeaders().entrySet()) {
            String key = header.getKey();
            // Prevent Nano headers from being injected
            if (!key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                this.remote.addHeader(key, header.getValue());
            }
        }

        try {
            this.connected = this.remote.connectBlocking();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onOpen() {
        if (!this.connected) {
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

}
