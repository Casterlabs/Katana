package co.casterlabs.katana.websocket;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;
import lombok.SneakyThrows;

public class RemoteWebSocketConnection extends WebSocketClient {
    private List<String> messageBuffer = new ArrayList<>();
    private ClientWebSocketConnection client;
    private boolean ready = false;

    public RemoteWebSocketConnection(URI serverUri, ClientWebSocketConnection client) {
        super(serverUri);

        this.client = client;
    }

    public void sendMessage(String message) {
        if (this.ready) {
            this.send(message);
        } else {
            this.messageBuffer.add(message);
        }
    }

    @SneakyThrows
    public void open() {
        try {
            this.connectBlocking();
            this.ready = true;

            for (String message : this.messageBuffer) {
                this.send(message);
            }

            this.messageBuffer.clear();
        } catch (Exception e) {
            e.printStackTrace();
            this.client.close(CloseCode.AbnormalClosure, "Remote connection encountered an error", true);
        }
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {}

    @Override
    public void onMessage(String message) {
        try {
            this.client.send(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (this.client.isOpen()) {
            try {
                this.client.close(CloseCode.find(code), reason, true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onError(Exception e) {
        if (!(e instanceof SocketTimeoutException)) { // Ignore
            e.printStackTrace();
        }
    }

}
