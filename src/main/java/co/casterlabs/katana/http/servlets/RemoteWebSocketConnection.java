package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import co.casterlabs.katana.http.nano.websocket.ClientWebSocketConnection;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

public class RemoteWebSocketConnection extends WebSocketClient {
    private ClientWebSocketConnection client;

    public RemoteWebSocketConnection(URI serverUri, ClientWebSocketConnection client) {
        super(serverUri);

        this.setTcpNoDelay(true);

        this.client = client;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        new Thread(() -> {
            while (this.isOpen()) {
                try {
                    this.sendPing();

                    TimeUnit.SECONDS.sleep(2);
                } catch (Exception e) {}
            }
        }).start();
    }

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
