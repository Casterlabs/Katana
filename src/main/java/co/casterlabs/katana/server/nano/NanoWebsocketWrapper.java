package co.casterlabs.katana.server.nano;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import co.casterlabs.katana.http.websocket.Websocket;
import co.casterlabs.katana.http.websocket.WebsocketCloseCode;
import co.casterlabs.katana.http.websocket.WebsocketFrame;
import co.casterlabs.katana.http.websocket.WebsocketFrameType;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;
import fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode;
import lombok.NonNull;

public class NanoWebsocketWrapper extends WebSocket {
    private WebsocketListener listener;

    private WebSocket instance = this;
    private KatanaWebsocket katanaWebsocket = new KatanaWebsocket();

    public NanoWebsocketWrapper(IHTTPSession nanoSession, WebsocketListener listener) {
        super(nanoSession);

        this.listener = listener;
    }

    // Nano WebSocket Impl
    @Override
    protected void onOpen() {
        new Thread(() -> {
            while (this.isOpen()) {
                try {
                    this.ping(":x-katana-ping".getBytes());

                    TimeUnit.SECONDS.sleep(1);
                } catch (Exception ignored) {}
            }
        }).start();

        this.listener.onOpen(this.katanaWebsocket);
    }

    @Override
    protected void onClose(CloseCode code, String reason, boolean remote) {
        if (remote) {
            this.listener.onClose(this.katanaWebsocket);
        }
    }

    @Override
    protected void onMessage(WebSocketFrame frame) {
        if (frame.getOpCode() == OpCode.Binary) {
            this.listener.onFrame(this.katanaWebsocket, new WebsocketFrame() {
                @Override
                public WebsocketFrameType getFrameType() {
                    return WebsocketFrameType.BINARY;
                }

                @Override
                public String getAsText() {
                    return new String(this.getBytes(), StandardCharsets.UTF_8);
                }

                @Override
                public byte[] getBytes() {
                    return frame.getBinaryPayload();
                }

                @Override
                public int getSize() {
                    return this.getBytes().length;
                }
            });
        } else if (frame.getOpCode() == OpCode.Text) {
            this.listener.onFrame(this.katanaWebsocket, new WebsocketFrame() {

                @Override
                public WebsocketFrameType getFrameType() {
                    return WebsocketFrameType.TEXT;
                }

                @Override
                public String getAsText() {
                    return frame.getTextPayload();
                }

                @Override
                public byte[] getBytes() {
                    return this.getAsText().getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public int getSize() {
                    return this.getBytes().length;
                }

            });
        }
    }

    @Override
    protected void onPong(WebSocketFrame pong) {}

    @Override
    protected void onException(IOException ignored) {}

    public class KatanaWebsocket extends Websocket {

        @Override
        public void send(@NonNull String message) throws IOException {
            instance.send(message);
        }

        @Override
        public void send(@NonNull byte[] bytes) throws IOException {
            instance.send(bytes);
        }

        @Override
        public void close(@NonNull WebsocketCloseCode code) throws IOException {
            try {
                instance.close(CloseCode.find(code.getCode()), "", false);
            } catch (Exception ignored) {}
        }

        // Request headers
        @Override
        public @NonNull Map<String, String> getHeaders() {
            return instance.getHandshakeRequest().getHeaders();
        }

        // URI
        @Override
        public String getUri() {
            return instance.getHandshakeRequest().getUri();
        }

        @Override
        public @NonNull Map<String, List<String>> getAllQueryParameters() {
            return instance.getHandshakeRequest().getParameters();
        }

        @SuppressWarnings("deprecation")
        @Override
        public @NonNull Map<String, String> getQueryParameters() {
            return instance.getHandshakeRequest().getParms();
        }

        @Override
        public @NonNull String getQueryString() {
            if (instance.getHandshakeRequest().getQueryParameterString() == null) {
                return "";
            } else {
                return "?" + instance.getHandshakeRequest().getQueryParameterString();
            }
        }

        // Misc
        @Override
        public @NonNull String getRemoteIpAddress() {
            return instance.getHandshakeRequest().getRemoteIpAddress();
        }

    }

}
