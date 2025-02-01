package co.casterlabs.katana.router.http.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.commons.async.promise.Promise;
import co.casterlabs.commons.async.promise.PromiseResolver;
import co.casterlabs.commons.io.streams.StreamUtil;
import co.casterlabs.commons.websocket.WebSocketClient;
import co.casterlabs.commons.websocket.WebSocketListener;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.HttpUtil;
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
import co.casterlabs.rhs.protocol.http.HttpResponse.ResponseContent;
import co.casterlabs.rhs.protocol.http.HttpSession;
import co.casterlabs.rhs.protocol.uri.Query;
import co.casterlabs.rhs.protocol.uri.SimpleUri;
import co.casterlabs.rhs.protocol.websocket.Websocket;
import co.casterlabs.rhs.protocol.websocket.WebsocketListener;
import co.casterlabs.rhs.protocol.websocket.WebsocketResponse;
import co.casterlabs.rhs.protocol.websocket.WebsocketSession;
import kotlin.Pair;
import lombok.Getter;
import lombok.SneakyThrows;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class ProxyServlet extends HttpServlet {
    private static final List<String> DISALLOWED_HEADERS = Arrays.asList(
        "accept-encoding",
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "content-length",
        "te",
        "trailers",
        "host",
        "transfer-encoding",
        "content-encoding",
        "upgrade",
        "sec-websocket-key",
        "sec-websocket-extensions",
        "sec-websocket-version",
        "sec-websocket-protocol",
        "remote-addr",
        "http-client-ip",
        "host",
        "x-forwarded-for",
        "x-remote-ip",
        "x-katana-ip"
    );

    private @Getter HostConfiguration config;

    private OkHttpClient client;
    private @Nullable String proxyUrlHost;
    private SSLSocketFactory sslSocketFactory;

    public ProxyServlet() {
        super("PROXY");
    }

    @SneakyThrows
    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);

        OkHttpClient.Builder okhttpBuilder = new OkHttpClient.Builder();

        if (this.config.ignoreBadSsl) {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(
                null,
                new TrustManager[] {
                        UnsafeTrustManager.INSTANCE
                },
                new SecureRandom()
            );
            this.sslSocketFactory = sslContext.getSocketFactory();

            okhttpBuilder.sslSocketFactory(this.sslSocketFactory, UnsafeTrustManager.INSTANCE);
            okhttpBuilder.hostnameVerifier((hostname, session) -> true);
        }

        if (this.config.forwardHost) {
            this.proxyUrlHost = HttpUrl
                .parse(this.config.proxyUrl)
                .host();

            // We intercept hostname lookups and give the result for the proxyUrl's host.
            okhttpBuilder.dns((hostname) -> {
                return Dns.SYSTEM.lookup(this.proxyUrlHost);
            });
        }

        okhttpBuilder.followRedirects(this.config.followRedirects);

        this.client = okhttpBuilder.build();
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
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

        @JsonField("ignore_bad_ssl")
        public boolean ignoreBadSsl = false;

        @JsonField("forward_host")
        public boolean forwardHost = false;

        @JsonField("follow_redirects")
        public boolean followRedirects = false;

        @JsonField("solve_for_ip")
        public boolean solveForIp = false;

        @JsonValidate
        private void $validate() {
            assert this.proxyUrl != null : "The `proxy_url` option must be set.";
            assert !this.proxyUrl.isEmpty() : "The `proxy_url` option must not be empty.";
        }

    }

    private String transformUrl(FastLogger logger, SimpleUri uri, boolean isWebSocket) {
        String url = this.config.proxyUrl;

        if (this.config.solveForIp) {
            String[] requested = uri.host.substring(0, uri.host.indexOf('.')).split("-");

            String targetIp;
            if (requested.length == 4) {
                // IPv4
                targetIp = String.join(".", requested);
            } else {
                // IPv6
                targetIp = "[" + String.join(":", requested) + "]";
            }

            url = url.replace("{ip}", targetIp);
        }

        // Remap http urls to websocket urls and vice versa.
        if (isWebSocket) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url = "ws" + url.substring("http".length());
            }
        } else {
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                url = "http" + url.substring("ws".length());
            }
        }

        if (this.config.forwardHost) {
            // Replace the proxyUrl's host with the session's host. Look at the above DNS
            // logic to see what this does.
            url = url.replaceFirst(this.proxyUrlHost, uri.host);
            logger.debug("Rewrote %s to %s, keep this in mind for the following messages.", this.proxyUrlHost, uri.host);
        }

        if (this.config.includePath) {
            String append;

            if (this.config.proxyPath == null) {
                append = uri.path;
            } else {
                append = uri.path.replace(this.config.proxyPath.replace(".*", ""), "");
            }

            if (uri.query != Query.EMPTY) {
                append += '?' + uri.query.raw;
            }

            logger.debug("%s -> %s%s", url, url, append);

            url += append;
        }

        return url;
    }

    @Override
    public boolean matchHttp(HttpSession session, HttpRouter router) {
        if (!this.config.allowHttp) {
            return false;
        }

        // If the path doesn't match don't serve.
        // A NULL path is wildcard.
        if ((this.config.proxyPath != null) && !session.uri().path.matches(this.config.proxyPath)) {
            return false;
        }

        return true;
    }

    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        final String url = this.transformUrl(session.logger(), session.uri(), false);

        session.logger().debug("Final proxy url: %s", url);
        Request.Builder builder = new Request.Builder().url(url);

        RequestBody body = null;

        if (session.body().present() && session.method() != HttpMethod.GET) {
            body = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return null; // Already handled.
                }

                @Override
                public long contentLength() throws IOException {
                    return session.body().length();
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeAll(Okio.source(session.body().stream()));
                }
            };
        }

        builder.method(session.rawMethod(), body);

        for (Entry<String, List<HeaderValue>> header : session.headers().entrySet()) {
            String key = header.getKey().toLowerCase();
            if (DISALLOWED_HEADERS.contains(key)) continue;

            for (HeaderValue value : header.getValue()) {
                builder.addHeader(key, value.raw());
            }
        }

        if (this.config.forwardIp) {
            builder.addHeader("X-Forwarded-For", String.join(", ", session.hops()));
        }

        Request request = builder.build();
        Response response = null;

        try {
            Response $response_pointer = this.client.newCall(request).execute();
            response = $response_pointer;

            HttpStatus status = HttpStatus.adapt(response.code(), response.message());
            long responseLen = Long.parseLong(response.header("Content-Length", "-1"));
            InputStream responseStream = response.body().byteStream();

            HttpResponse result = new HttpResponse(
                new ResponseContent() {
                    @Override
                    public void write(int recommendedBufferSize, OutputStream out) throws IOException {
                        StreamUtil.streamTransfer(
                            responseStream,
                            out,
                            recommendedBufferSize,
                            responseLen
                        );
                    }

                    @Override
                    public long length() {
                        return responseLen;
                    }

                    @Override
                    public void close() throws IOException {
                        $response_pointer.close();
                    }

                },
                status
            )
                .mime(response.header("Content-Type"));

            for (Pair<? extends String, ? extends String> header : response.headers()) {
                String key = header.getFirst();

                if (key.equalsIgnoreCase("Transfer-Encoding") ||
                    key.equalsIgnoreCase("Content-Length") ||
                    key.equalsIgnoreCase("Content-Encoding") ||
                    key.equalsIgnoreCase("Content-Type")) {
                    continue;
                }

                result.header(key, header.getSecond());
            }

            return result;
        } catch (Throwable t) {
            session.logger().severe("An error occurred whilst proxying (serving %s %s): \n%s", builder.getMethod$okhttp(), builder.getUrl$okhttp(), t);
            if (response != null) {
                response.close();
            }
            return HttpUtil.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "An error occurred whilst proxying.");
        }
    }

    @Override
    public boolean matchWebsocket(WebsocketSession session, HttpRouter router) {
        if (!this.config.allowWebsockets) {
            return false;
        }

        // If the path doesn't match don't serve.
        // A NULL path is wildcard.
        if ((this.config.proxyPath != null) && !session.uri().path.matches(this.config.proxyPath)) {
            return false;
        }

        return true;
    }

    @SneakyThrows
    @Override
    public WebsocketResponse serveWebsocket(WebsocketSession session, HttpRouter router) {
        final String url = this.transformUrl(session.logger(), session.uri(), true);

        session.logger().debug("Final proxy url: %s", url);
        URI uri = URI.create(url);

        PromiseResolver<Websocket> websocketPromise = Promise.withResolvers();
        RemoteWebSocketConnection remote = new RemoteWebSocketConnection(uri, session, websocketPromise.promise);

        try {
            session.logger().debug("Connecting to proxy target...");
            remote.socket.connect(TimeUnit.SECONDS.toMillis(15), TimeUnit.SECONDS.toMillis(5));
        } catch (Throwable t) {
            session.logger().severe("An error occurred whilst connecting to target (serving %s): \n%s", uri, t);
            try {
                remote.socket.close();
            } catch (Throwable ignored) {}
            return WebsocketResponse.reject(StandardHttpStatus.INTERNAL_ERROR);
        }

        String acceptedProtocol = remote.selectedProtocol;
        session.logger().debug("Connected! Using protocol: %s", acceptedProtocol);

        return WebsocketResponse.accept(
            new WebsocketListener() {
                @Override
                public void onOpen(Websocket websocket) {
                    websocketPromise.resolve(websocket);
                }

                @SneakyThrows
                @Override
                public void onText(Websocket websocket, String message) {
                    try {
                        session.logger().trace("Received message from client: %s", message);
                        remote.socket.send(message);
                    } catch (Throwable t) {
                        websocket.session().logger().debug("An error occurred whilst sending message to target:\n%s", t);
                        throw t;
                    }
                }

                @SneakyThrows
                @Override
                public void onBinary(Websocket websocket, byte[] bytes) {
                    try {
                        session.logger().trace("Received bytes from client: len=%d", bytes.length);
                        remote.socket.send(bytes);
                    } catch (Throwable t) {
                        websocket.session().logger().debug("An error occurred whilst sending message to target:\n%s", t);
                        throw t;
                    }
                }

                @Override
                public void onClose(Websocket websocket) {
                    session.logger().debug("Closed websocket.");

                    if (websocketPromise.promise.isPending()) {
                        websocketPromise.reject(new IOException("Closed"));
                    }

                    try {
                        remote.socket.close();
                    } catch (Throwable ignored) {}
                }
            },
            acceptedProtocol
        );
    }

    private class RemoteWebSocketConnection implements WebSocketListener {
        private FastLogger sessionLogger;
        private Promise<Websocket> client;

        public String selectedProtocol = null;

        private WebSocketClient socket;

        public RemoteWebSocketConnection(URI serverUri, WebsocketSession session, Promise<Websocket> client) {
            this.sessionLogger = session.logger();
            this.client = client;

            Map<String, String> headers = new HashMap<>();
            for (Entry<String, List<HeaderValue>> header : session.headers().entrySet()) {
                String key = header.getKey().toLowerCase();

                if (!DISALLOWED_HEADERS.contains(key)) {
                    headers.put(key, header.getValue().get(0).raw());
                }
            }

            if (config.forwardIp) {
                headers.put("X-Forwarded-For", String.join(", ", session.hops()));
            }

            if (config.forwardHost) {
                headers.put("Host", session.uri().host);
            }

            this.socket = new WebSocketClient(serverUri, headers, session.acceptedProtocols());
            this.socket.setListener(this);
            this.socket.setThreadFactory(Thread.ofVirtual().factory());

            if (sslSocketFactory != null) {
                this.socket.setSocketFactory(sslSocketFactory);
            }
        }

        @Override
        public void onOpen(WebSocketClient client, Map<String, String> headers, @Nullable String acceptedProtocol) {
            this.selectedProtocol = acceptedProtocol;
        }

        @Override
        public void onText(WebSocketClient client, String string) {
            try {
                this.sessionLogger.trace("Received message from proxy: %s", string);
                this.client.await().send(string);
            } catch (Throwable t) {
                this.sessionLogger.debug("An error occurred whilst sending message to client: %s", t);
            }
        }

        @Override
        public void onBinary(WebSocketClient client, byte[] bytes) {
            try {
                this.sessionLogger.trace("Received bytes from proxy: len=%d", bytes.length);
                this.client.await().send(bytes);
            } catch (Throwable t) {
                this.sessionLogger.debug("An error occurred whilst sending message to client: %s", t);
            }
        }

        @Override
        public void onClosed(WebSocketClient client) {
//            this.sessionLogger.debug("Remote's close reason: %d %s", code, reason);
            if (this.client.isSettled()) {
                try {
                    this.client.await().close();
                } catch (Throwable ignored) {}
            }
        }

        @Override
        public void onException(Throwable t) {
            this.sessionLogger.fatal("Uncaught: %s", t);
        }
    }
}

class UnsafeTrustManager extends X509ExtendedTrustManager {
    public static final UnsafeTrustManager INSTANCE = new UnsafeTrustManager();

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[] {};
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
}
