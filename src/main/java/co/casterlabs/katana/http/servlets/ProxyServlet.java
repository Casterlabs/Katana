package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map.Entry;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.DropConnectionException;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.websocket.Websocket;
import co.casterlabs.rakurai.io.http.websocket.WebsocketCloseCode;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import kotlin.Pair;
import lombok.AllArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProxyServlet extends HttpServlet {
    private static final OkHttpClient client = new OkHttpClient();

    private HostConfiguration config;

    public ProxyServlet() {
        super("PROXY");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);

        if (this.config.proxyPath != null) {
            this.config.proxyPath = this.config.proxyPath.replace("*", ".*");
        }
    }

    @JsonClass(exposeAll = true)
    private static class HostConfiguration {
        @JsonField("proxy_url")
        public String proxyUrl;

        @JsonField("proxy_path")
        public String proxyPath;

        @JsonField("include_path")
        public boolean includePath;

        @JsonField("forward_ip")
        public boolean forwardIp;

        @JsonField("allow_http")
        public boolean allowHttp;

        @JsonField("allow_websockets")
        public boolean allowWebsockets;

    }

    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath == null) || session.getUri().matches(this.config.proxyPath)) {
                String url = this.config.proxyUrl;

                if (this.config.proxyPath == null) {
                    url += session.getUri();
                } else if (this.config.includePath) {
                    if (this.config.proxyPath != null) {
                        url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    }

                    url += session.getQueryString();
                }

                Request.Builder builder = new Request.Builder().url(url);

                try {
                    // If it throws then we have no body.
                    if (session.getRequestBodyBytes() != null) {
                        try {
                            builder.method(session.getMethod().name().toUpperCase(), RequestBody.create(session.getRequestBodyBytes()));
                        } catch (IOException e) {
                            throw new DropConnectionException();
                        }
                    }
                } catch (IOException e) {}

                for (Entry<String, List<String>> header : session.getHeaders().entrySet()) {
                    String key = header.getKey();

                    if (!key.equals("remote-addr") && !key.equals("http-client-ip") && !key.equals("host")) {
                        builder.addHeader(key, header.getValue().get(0));
                    }
                }

                if (this.config.forwardIp) {
                    builder.addHeader("X-Remote-IP", session.getRemoteIpAddress()); // Deprecated
                    builder.addHeader("X-Katana-IP", session.getRemoteIpAddress()); // Deprecated
                    builder.addHeader("X-Forwarded-For", String.join(", ", session.getRequestHops()));
                }

                Request request = builder.build();

                try (Response response = client.newCall(request).execute()) {

                    HttpStatus status = new HttpStatusAdapter(response.code());
                    long responseLen = response.body().contentLength();

                    //@formatter:off
                    HttpResponse result = (responseLen == -1) ?
                            HttpResponse.newChunkedResponse(status, response.body().byteStream()) : 
                            HttpResponse.newFixedLengthResponse(status, response.body().bytes()); // Ugh.
                    //@formatter:on

                    for (Pair<? extends String, ? extends String> header : response.headers()) {
                        String key = header.getFirst();

                        if (!key.equalsIgnoreCase("Transfer-Encoding") && !key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Content-Type")) {
                            result.putHeader(key, header.getSecond());
                        }
                    }

                    result.setMimeType(response.header("Content-Type", "application/octet-stream"));

                    return result;
                } catch (Exception e) {
                    e.printStackTrace();

                    // Rakurai will automatically close the stream if a write error or
                    // end of stream is reached.
                    throw new DropConnectionException();
                }
            } else {
                return null;
            }
        } else {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Proxy url not set.", router.getConfig());
        }
    }

    @Override
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        if (!this.config.allowWebsockets) {
            return null;
        }

        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath != null) && !session.getUri().matches(this.config.proxyPath)) {
                return null;
            } else {
                String url = this.config.proxyUrl;

                if (this.config.includePath) {
                    url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                try {
                    URI uri = new URI(url);

                    return new WebsocketListener() {
                        private RemoteWebSocketConnection remote;

                        @Override
                        public void onOpen(Websocket websocket) {
                            this.remote = new RemoteWebSocketConnection(uri, websocket);

                            for (Entry<String, List<String>> entry : session.getHeaders().entrySet()) {
                                this.remote.addHeader(entry.getKey(), entry.getValue().get(0));
                            }

                            try {
                                if (!this.remote.connectBlocking()) {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                }
                            } catch (IOException | InterruptedException e) {
                                try {
                                    websocket.close(WebsocketCloseCode.NORMAL);
                                } catch (IOException ignored) {}
                            }
                        }

                        @Override
                        public void onText(Websocket websocket, String message) {
                            this.remote.send(message);
                        }

                        @Override
                        public void onBinary(Websocket websocket, byte[] bytes) {
                            this.remote.send(bytes);
                        }

                        @Override
                        public void onClose(Websocket websocket) {
                            if (!this.remote.isClosing()) {
                                this.remote.close();
                            }
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

    private static class RemoteWebSocketConnection extends WebSocketClient {
        private Websocket client;

        public RemoteWebSocketConnection(URI serverUri, Websocket client) {
            super(serverUri);

            this.setTcpNoDelay(true);

            this.client = client;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {}

        @Override
        public void onMessage(String message) {
            try {
                this.client.send(message);
            } catch (IOException e) {}
        }

        @Override
        public void onMessage(ByteBuffer message) {
            try {
                this.client.send(message.array());
            } catch (IOException e) {}
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            try {
                this.client.close(WebsocketCloseCode.NORMAL);
            } catch (IOException e) {}
        }

        @Override
        public void onError(Exception e) {}

    }

    @AllArgsConstructor
    private static class HttpStatusAdapter implements HttpStatus {
        private int code;

        @Override
        public String getStatusString() {
            return String.valueOf(this.code);
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public int getStatusCode() {
            return this.code;
        }
    }

}
