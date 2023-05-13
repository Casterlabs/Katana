package co.casterlabs.katana.http.servlets;

import java.util.List;
import java.util.Map.Entry;

import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpSession;
import co.casterlabs.rakurai.io.http.server.websocket.Websocket;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.server.websocket.WebsocketSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import lombok.SneakyThrows;

public class EchoServlet extends HttpServlet {
    private @Getter HostConfiguration config;

    public EchoServlet() {
        super("ECHO");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        StringBuilder request = new StringBuilder();

        request.append(
            String.format(
                "%s %s%s\n\n",
                session.getMethod(), session.getUri(), session.getQueryString()
            )
        );

        for (Entry<String, List<String>> header : session.getHeaders().entrySet()) {
            String key = header.getKey().toLowerCase();
            for (String value : header.getValue()) {
                request.append(String.format("%s: %s\n", key, value));
            }
        }

        if (session.hasBody()) {
            request.append('\n');
            request.append(session.getRequestBody());
        }

        return HttpResponse
            .newFixedLengthResponse(StandardHttpStatus.OK, request.toString())
            .setMimeType("text/plain");
    }

    @Override
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        return new WebsocketListener() {

            @Override
            public void onOpen(Websocket websocket) {
                try {
                    StringBuilder request = new StringBuilder();

                    request.append(
                        String.format(
                            "%s %s%s\n\n",
                            session.getMethod(), session.getUri(), session.getQueryString()
                        )
                    );

                    for (Entry<String, List<String>> header : session.getHeaders().entrySet()) {
                        String key = header.getKey().toLowerCase();
                        for (String value : header.getValue()) {
                            request.append(String.format("%s: %s\n", key, value));
                        }
                    }

                    websocket.send(request.toString());
                } catch (Throwable t) {
                    session.getLogger().fatal(t);
                }
            }

            @SneakyThrows
            @Override
            public void onText(Websocket websocket, String message) {
                try {
                    websocket.send(message);
                } catch (Throwable t) {
                    websocket.getSession().getLogger().fatal("An error occurred whilst sending message to target: %s", t);
                    throw t;
                }
            }

            @SneakyThrows
            @Override
            public void onBinary(Websocket websocket, byte[] bytes) {
                try {
                    websocket.send(bytes);
                } catch (Throwable t) {
                    websocket.getSession().getLogger().fatal("An error occurred whilst sending message to target: %s", t);
                    throw t;
                }
            }

            @Override
            public void onClose(Websocket websocket) {}

        };
    }

}
