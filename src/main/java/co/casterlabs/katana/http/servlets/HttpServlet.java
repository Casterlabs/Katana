package co.casterlabs.katana.http.servlets;

import java.util.HashSet;
import java.util.Set;

import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpSession;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketSession;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
public abstract class HttpServlet {
    private Set<String> corsAllowedHosts = new HashSet<>();
    private Set<String> hostnames = new HashSet<>();
    private @Setter int priority = 1;
    private String type;

    public HttpServlet(@NonNull String type) {
        this.type = type;
    }

    public abstract Object getConfig();

    public abstract void init(JsonObject config) throws JsonValidationException, JsonParseException;

    /* Override */
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        return null;
    }

    /* Override */
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        return null;
    }

}
