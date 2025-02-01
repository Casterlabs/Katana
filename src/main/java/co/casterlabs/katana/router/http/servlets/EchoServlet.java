package co.casterlabs.katana.router.http.servlets;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.protocol.HeaderValue;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import co.casterlabs.rhs.protocol.websocket.Websocket;
import co.casterlabs.rhs.protocol.websocket.WebsocketListener;
import co.casterlabs.rhs.protocol.websocket.WebsocketResponse;
import co.casterlabs.rhs.protocol.websocket.WebsocketSession;
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

    @Override
    public boolean matchHttp(HttpSession session, HttpRouter router) {
        return true;
    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        StringBuilder request = new StringBuilder();

        request.append(
            String.format(
                "%s %s\n\n",
                session.method(), session.uri().rawPath
            )
        );

        for (Entry<String, List<HeaderValue>> header : session.headers().entrySet()) {
            String key = header.getKey().toLowerCase();
            for (HeaderValue value : header.getValue()) {
                request.append(String.format("%s: %s\n", key, value.raw()));
            }
        }

        if (session.body().present()) {
            request.append('\n');
            request.append("<body content>");
        }

        return HttpResponse
            .newFixedLengthResponse(StandardHttpStatus.OK, request.toString())
            .mime("text/plain");
    }

    @Override
    public boolean matchWebsocket(WebsocketSession session, HttpRouter router) {
        return true;
    }

    @Override
    public WebsocketResponse serveWebsocket(WebsocketSession session, HttpRouter router) {
        return WebsocketResponse.accept(
            new WebsocketListener() {
                @Override
                public void onOpen(Websocket websocket) throws IOException {
                    StringBuilder request = new StringBuilder();

                    request.append(
                        String.format(
                            "GET %s\n\n",
                            session, session.uri().rawPath
                        )
                    );

                    for (Entry<String, List<HeaderValue>> header : session.headers().entrySet()) {
                        String key = header.getKey().toLowerCase();
                        for (HeaderValue value : header.getValue()) {
                            request.append(String.format("%s: %s\n", key, value.raw()));
                        }
                    }

                    websocket.send(request.toString());
                }

                @Override
                public void onText(Websocket websocket, String message) throws IOException {
                    websocket.send(message);
                }

                @Override
                public void onBinary(Websocket websocket, byte[] bytes) throws IOException {
                    websocket.send(bytes);
                }
            },
            session.firstProtocol()
        );
    }

}
