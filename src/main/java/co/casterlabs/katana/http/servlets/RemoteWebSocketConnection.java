package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import co.casterlabs.katana.http.websocket.Websocket;
import co.casterlabs.katana.http.websocket.WebsocketCloseCode;

public class RemoteWebSocketConnection extends WebSocketClient {
    private Websocket client;

    public RemoteWebSocketConnection(URI serverUri, Websocket client) {
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
        if (remote) {
            try {
                this.client.close(WebsocketCloseCode.NORMAL);
            } catch (IOException e) {}
        }
    }

    @Override
    public void onError(Exception ignored) {}

}
