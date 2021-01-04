package co.casterlabs.katana.http.servlets;

import java.net.URISyntaxException;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.nano.NanoHttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import co.casterlabs.katana.websocket.ClientWebSocketConnection;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import xyz.e3ndr.fastloggingframework.logging.LoggingUtil;

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
        @SerializedName("proxy_url")
        public String proxyUrl;

        @SerializedName("proxy_path")
        public String proxyPath;

        public boolean include_path;

    }

    @Override
    public boolean serve(HttpSession session) {
        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath != null) && !session.getUri().equalsIgnoreCase(this.config.proxyPath)) {
                return false;
            } else if (!session.isWebsocketRequest()) {
                Util.errorResponse(session, Status.BAD_REQUEST, "Unable to upgrade.");
            } else {
                String url = this.config.proxyUrl;

                if (this.config.include_path) {
                    url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                NanoHttpSession nano = (NanoHttpSession) session;

                try {
                    ClientWebSocketConnection client = new ClientWebSocketConnection(nano.getNanoSession(), url);

                    if (client.connect()) {
                        nano.setWebsocketResponse(client);
                    } else {
                        Util.errorResponse(session, Status.INTERNAL_ERROR, "Could not connect to remote url.");
                    }
                } catch (InterruptedException | URISyntaxException e) {
                    Util.errorResponse(session, Status.INTERNAL_ERROR, "An error occured whilst proxying:\n" + LoggingUtil.getExceptionStack(e));
                }

                return true;
            }
        }

        return false;
    }

}
