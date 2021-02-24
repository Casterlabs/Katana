package co.casterlabs.katana.http.servlets;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import co.casterlabs.katana.http.websocket.WebsocketSession;
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

    public HttpResponse serveHttp(HttpSession session) {
        return null;
    }

    public WebsocketListener serveWebsocket(WebsocketSession session) {
        return null;
    }

}
