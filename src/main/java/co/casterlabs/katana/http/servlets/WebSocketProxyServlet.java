package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.websocket.Websocket;
import co.casterlabs.katana.http.websocket.WebsocketCloseCode;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import co.casterlabs.katana.http.websocket.WebsocketSession;

public class WebSocketProxyServlet extends HttpServlet {
    private HostConfiguration config;

    public WebSocketProxyServlet() {
        super("WEBSOCKETPROXY");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        @SerializedName("proxy_url")
        public String proxyUrl;

        @SerializedName("proxy_path")
        public String proxyPath;

        public boolean include_path;

    }

    @Override
    public WebsocketListener serveWebsocket(WebsocketSession session) {
        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath != null) && !session.getUri().equalsIgnoreCase(this.config.proxyPath)) {
                return null;
            } else {
                String url = this.config.proxyUrl;

                if (this.config.include_path) {
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

                            for (Map.Entry<String, String> entry : session.getHeaders().entrySet()) {
                                this.remote.addHeader(entry.getKey(), entry.getValue());
                            }

                            try {
                                if (!this.remote.connectBlocking()) {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                }
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace();
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

}
