package co.casterlabs.katana.websocket;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

public class RemoteWebSocketConnection extends WebSocketClient {
    private ClientWebSocketConnection client;

    public RemoteWebSocketConnection(URI serverUri, ClientWebSocketConnection client) {
        super(serverUri);

        this.setTcpNoDelay(true);

        this.client = client;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {}

    @Override
    public void onMessage(String message) {
        try {
            this.client.send(message);
        } catch (IOException e) {}
    }

    @Override
    public void onMessage(ByteBuffer message) {
        try {
            this.client.send(message.array());
        } catch (IOException e) {}
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        try {
            this.client.close(CloseCode.NormalClosure, reason, false);
        } catch (Exception ignored) {}
    }

    @Override
    public void onError(Exception ignored) {}

}
