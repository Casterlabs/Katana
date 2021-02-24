package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

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
                        private RemoteWebSocketConnection client;

                        @Override
                        public void onOpen(Websocket websocket) {
                            this.client = new RemoteWebSocketConnection(uri, websocket);

                            try {
                                if (!this.client.connectBlocking()) {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                }
                            } catch (IOException | InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onText(Websocket websocket, String message) {
                            this.client.send(message);
                        }

                        @Override
                        public void onBinary(Websocket websocket, byte[] bytes) {
                            this.client.send(bytes);
                        }

                        @Override
                        public void onClose(Websocket websocket) {
                            this.client.close();
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
