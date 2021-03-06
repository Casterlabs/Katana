package co.casterlabs.katana.http.servlets;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
public abstract class HttpServlet {
    private List<String> allowedHosts = new ArrayList<>();
    private List<String> hosts = new ArrayList<>();
    private @Setter int priority = 1;
    private String id;

    public HttpServlet(@NonNull String id) {
        this.id = id;
    }

    public abstract void init(JsonObject config);

    /* Override */
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        return null;
    }

    /* Override */
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        return null;
    }

}
