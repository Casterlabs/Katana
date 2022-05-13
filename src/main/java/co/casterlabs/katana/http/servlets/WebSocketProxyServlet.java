package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map.Entry;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.websocket.Websocket;
import co.casterlabs.rakurai.io.http.websocket.WebsocketCloseCode;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;

@Deprecated
public class WebSocketProxyServlet extends HttpServlet {
    private HostConfiguration config;

    public WebSocketProxyServlet() {
        super("WEBSOCKETPROXY");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
        @JsonField("proxy_url")
        public String proxyUrl;

        @JsonField("proxy_path")
        public String proxyPath;

        @JsonField("include_path")
        public boolean includePath;

    }

    @Override
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath != null) && !session.getUri().matches(this.config.proxyPath)) {
                return null;
            } else {
                String url = this.config.proxyUrl;

                if (this.config.includePath) {
                    url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                try {
                    URI uri = new URI(url);

                    return new WebsocketListener() {
                        private RemoteWebSocketConnection remote;

                        @Override
                        public void onOpen(Websocket websocket) {
                            this.remote = new RemoteWebSocketConnection(uri, websocket);

                            for (Entry<String, List<String>> entry : session.getHeaders().entrySet()) {
                                this.remote.addHeader(entry.getKey(), entry.getValue().get(0));
                            }

                            try {
                                if (!this.remote.connectBlocking()) {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                }
                            } catch (IOException | InterruptedException e) {
                                try {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                } catch (IOException ignored) {}
                            }
                        }

                        @Override
                        public void onText(Websocket websocket, String message) {
                            this.remote.send(message);
                        }

                        @Override
                        public void onBinary(Websocket websocket, byte[] bytes) {
                            this.remote.send(bytes);
                        }

                        @Override
                        public void onClose(Websocket websocket) {
                            if (!this.remote.isClosing()) {
                                this.remote.close();
                            }
                        }

                    };
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        }

        return null;
    }

    private static class RemoteWebSocketConnection extends WebSocketClient {
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
            try {
                this.client.close(WebsocketCloseCode.NORMAL);
            } catch (IOException e) {}
        }

        @Override
        public void onError(Exception e) {}

    }

}
