package co.casterlabs.katana.http.nano.websocket;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.java_websocket.enums.Opcode;

import co.casterlabs.katana.http.servlets.RemoteWebSocketConnection;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

public class ClientWebSocketConnection extends WebSocket implements AutoCloseable {
    private static final Map<WebSocketFrame.OpCode, Opcode> MAPPING = new HashMap<>();

    private RemoteWebSocketConnection remote;

    static {
        MAPPING.put(WebSocketFrame.OpCode.Binary, Opcode.BINARY);
        MAPPING.put(WebSocketFrame.OpCode.Continuation, Opcode.CONTINUOUS);
        MAPPING.put(WebSocketFrame.OpCode.Text, Opcode.TEXT);
    }

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
        if (this.isOpen()) {
            new Thread(() -> {
                while (this.isOpen()) {
                    try {
                        this.ping(":X-Katana-Ping".getBytes());

                        TimeUnit.SECONDS.sleep(2);
                    } catch (Exception e) {}
                }
            }).start();
        } else {
            try {
                this.close(CloseCode.NormalClosure, "Remote connection encountered an error", true);
            } catch (IOException ignored) {}
        }
    }

    @Override
    protected void onPong(WebSocketFrame frame) {
        // this.remote.sendPing();
    }

    @Override
    protected void onMessage(WebSocketFrame frame) {
        Opcode opCode = MAPPING.get(frame.getOpCode());

        if (opCode != null) {
            this.remote.sendFragmentedFrame(opCode, ByteBuffer.wrap(frame.getBinaryPayload()), frame.isFin());
        }
    }

    @Override
    protected void onClose(CloseCode code, String reason, boolean remote) {
        try {
            this.remote.close(CloseCode.NormalClosure.getValue(), reason);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onException(IOException ignored) {}

    @Override
    public void close() throws Exception {
        this.remote.closeBlocking();
    }

}
