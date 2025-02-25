package co.casterlabs.katana.router.http.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import co.casterlabs.commons.async.AsyncTask;
import co.casterlabs.commons.async.promise.Promise;
import co.casterlabs.commons.async.promise.PromiseResolver;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.HttpMethod;
import co.casterlabs.rhs.HttpStatus;
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

public class WebhookToWSServlet extends HttpServlet {
    private static final Map<String, List<Websocket>> wsListeners = new HashMap<>();
    private static final Map<String, PromiseResolver<ResultData>> resultPromises = new HashMap<>();

    private @Getter HostConfiguration config;

    public WebhookToWSServlet() {
        super("WEBHOOK_TO_WS");
    }

    @SneakyThrows
    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
        public String path = "/";

        @JsonField("websocket_secret")
        public String websocketSecret = "abc123";

        @JsonField("allowed_methods")
        public List<HttpMethod> allowedMethods = Arrays.asList(
            HttpMethod.POST
        );

        @JsonValidate
        private void $validate() {
            assert this.path != null : "The `path` option must be set.";
            assert this.allowedMethods != null && !this.allowedMethods.isEmpty() : "The `allowed_methods` option must be set and cannot be empty.";
        }
    }

    @Override
    public boolean matchHttp(HttpSession session, HttpRouter router) {
        return session.uri().path.startsWith(this.config.path);
    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (!this.config.allowedMethods.contains(session.method())) {
            return HttpResponse.newFixedLengthResponse(StandardHttpStatus.METHOD_NOT_ALLOWED, "Method not allowed.");
        }

        List<Websocket> targets = new ArrayList<>(wsListeners.getOrDefault(this.config.path, Collections.emptyList())); // Copy.

        if (targets.isEmpty()) {
            return HttpResponse.newFixedLengthResponse(StandardHttpStatus.OK, "No listeners available.");
        }

        String requestId = UUID.randomUUID().toString();

        String payload;
        {
            JsonObject payloadJson = new JsonObject()
                .put("request_id", requestId)
                .put("method", session.rawMethod())
                .put("body_b64", Base64.getEncoder().encodeToString(session.body().bytes()));
            JsonObject headers = new JsonObject();
            for (Entry<String, List<HeaderValue>> entry : session.headers().entrySet()) {
                headers.put(entry.getKey(), entry.getValue().get(0).raw());
            }
            payloadJson.put("headers", headers);
            payload = payloadJson.toString();
        }

        PromiseResolver<ResultData> resultPromise = Promise.withResolvers();
        resultPromises.put(requestId, resultPromise);

        for (Websocket ws : targets) {
            try {
                ws.send(payload);
            } catch (IOException ignored) {
                wsListeners.get(this.config.path).remove(ws);
            }
        }

        AsyncTask.create(() -> {
            try {
                TimeUnit.SECONDS.sleep(15);
                resultPromise.resolve(null); // May throw ISE, doesn't matter.
            } catch (InterruptedException | IllegalStateException ignored) {}
            resultPromises.remove(requestId);
        });

        ResultData result = resultPromise.promise.await();

        if (result == null) {
            return HttpResponse.newFixedLengthResponse(StandardHttpStatus.NO_CONTENT);
        }

        byte[] body = Base64.getDecoder().decode(result.bodyB64);

        return HttpResponse
            .newFixedLengthResponse(HttpStatus.adapt(result.code, ""), body)
            .putAllHeaders(result.headers);
    }

    @Override
    public boolean matchWebsocket(WebsocketSession session, HttpRouter router) {
        if (!session.uri().path.startsWith(this.config.path)) return false;

        // If the secret doesn't match don't handle.
        if (!session.uri().path.startsWith(this.config.path + '/' + this.config.websocketSecret)) {
            return false;
        }

        return true;
    }

    @Override
    public WebsocketResponse serveWebsocket(WebsocketSession session, HttpRouter router) {
        return WebsocketResponse.accept(new WebsocketListener() {
            @Override
            public void onOpen(Websocket websocket) {
                List<Websocket> list = wsListeners.get(config.path);
                if (list == null) {
                    list = new LinkedList<>();
                    wsListeners.put(config.path, list);
                }

                list.add(websocket);
            }

            @Override
            public void onText(Websocket websocket, String message) {
                try {
                    ResultData result = Rson.DEFAULT.fromJson(message, ResultData.class);

                    PromiseResolver<ResultData> resolver = resultPromises.remove(result.requestId);
                    if (resolver == null) return; // We missed it, oh well.

                    resolver.resolve(result);
                } catch (IOException e) {
                    session.logger().exception(e);
                } catch (IllegalStateException ignored) {}
            }

            @Override
            public void onClose(Websocket websocket) {
                List<Websocket> list = wsListeners.get(config.path);
                list.remove(websocket);
            }
        }, session.firstProtocol());
    }

    @JsonClass(exposeAll = true)
    private static class ResultData {
        @JsonField("request_id")
        public String requestId;

        public int code;

        @JsonField("body_b64")
        public String bodyB64;

        public Map<String, String> headers;
    }

}
