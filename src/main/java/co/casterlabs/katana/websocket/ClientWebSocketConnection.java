package co.casterlabs.katana.websocket;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;
import lombok.SneakyThrows;

public class ClientWebSocketConnection extends WebSocket {
    private RemoteWebSocketConnection remote;

    @SneakyThrows
    public ClientWebSocketConnection(IHTTPSession nanoSession, String uri) {
        super(nanoSession);

        this.remote = new RemoteWebSocketConnection(new URI(uri), this);
    }

    @Override
    protected void onOpen() {
        for (Map.Entry<String, String> header : this.getHandshakeRequest().getHeaders().entrySet()) {
            String key = header.getKey();
            // Prevent Nano headers from being injected
            if (!key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                this.remote.addHeader(key, header.getValue());
            }
        }

        this.remote.open();
    }

    @Override
    protected void onPong(WebSocketFrame frame) {
        this.remote.sendMessage(frame.getTextPayload());
    }

    @Override
    protected void onMessage(WebSocketFrame frame) {
        this.remote.sendMessage(frame.getTextPayload());
    }

    @Override
    protected void onClose(CloseCode code, String reason, boolean remote) {
        if (this.remote.isOpen()) {
            this.remote.close(code.getValue(), reason);
        }
    }

    @Override
    protected void onException(IOException e) {
        if (!(e instanceof SocketTimeoutException)) { // Ignore
            e.printStackTrace();
        }
    }

}
