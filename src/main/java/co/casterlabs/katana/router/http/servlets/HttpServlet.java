package co.casterlabs.katana.router.http.servlets;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.server.HttpResponse;
import co.casterlabs.rhs.session.HttpSession;
import co.casterlabs.rhs.session.WebsocketListener;
import co.casterlabs.rhs.session.WebsocketSession;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@Getter
public abstract class HttpServlet {
    private static Map<String, Class<? extends HttpServlet>> SERVLETS = Map.of(
        "STATIC", StaticServlet.class,
        "PROXY", ProxyServlet.class,
        "REDIRECT", RedirectServlet.class,
        "FILE", FileServlet.class,
        "ECHO", EchoServlet.class
    );

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

    @SuppressWarnings("deprecation")
    public static HttpServlet create(String type) {
        Class<? extends HttpServlet> servlet = SERVLETS.get(type.toUpperCase());

        if (servlet == null) {
            throw new IllegalArgumentException("Servlet does not exist");
        }

        try {
            return servlet.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot instantiate servlet", e);
        }
    }

}
