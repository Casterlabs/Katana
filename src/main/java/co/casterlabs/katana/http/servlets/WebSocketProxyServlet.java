package co.casterlabs.katana.http.servlets;

import com.google.gson.JsonObject;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import co.casterlabs.katana.websocket.ClientWebSocketConnection;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import lombok.SneakyThrows;

public class WebSocketProxyServlet extends Servlet {
    private HostConfiguration config;

    public WebSocketProxyServlet() {
        super(ServletType.WEBSOCKET, "WEBSOCKETPROXY");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        public String proxy_url;
        public String proxy_path;
        public boolean include_path;

    }

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (this.config.proxy_url != null) {
            if ((this.config.proxy_path != null) && !session.getUri().equalsIgnoreCase(this.config.proxy_path)) {
                return false;
            } else if (!session.isWebsocketRequest()) {
                Util.errorResponse(session, Status.BAD_REQUEST, "Unable to upgrade.");
            } else {
                String url = this.config.proxy_url;

                if (this.config.include_path) {
                    url += session.getUri().replace(this.config.proxy_path.replace(".*", ""), "");
                }

                ClientWebSocketConnection client = new ClientWebSocketConnection(session.getSession(), url);

                session.setWebsocketResponse(client);
                return true;
            }
        }

        return false;
    }

}
