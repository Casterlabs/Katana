package co.casterlabs.katana.router.http.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.commons.async.PromiseWithHandles;
import co.casterlabs.commons.io.streams.StreamUtil;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.protocol.HttpStatus;
import co.casterlabs.rhs.server.HttpResponse;
import co.casterlabs.rhs.server.HttpResponse.ResponseContent;
import co.casterlabs.rhs.session.HttpSession;
import co.casterlabs.rhs.session.Websocket;
import co.casterlabs.rhs.session.WebsocketListener;
import co.casterlabs.rhs.session.WebsocketSession;
import co.casterlabs.rhs.util.DropConnectionException;
import co.casterlabs.rhs.util.HeaderMap;
import kotlin.Pair;
import lombok.AllArgsConstructor;
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

public class ProxyServlet extends HttpServlet {
    private static final List<String> DISALLOWED_HEADERS = Arrays.asList(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailers",
        "host",
        "transfer-encoding",
        "upgrade",
        "sec-websocket-key",
        "sec-websocket-extensions",
        "sec-websocket-protocol",
        "sec-websocket-version",
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

        @JsonValidate
        private void $validate() {
            assert this.proxyUrl != null : "The `proxy_url` option must be set.";
            assert !this.proxyUrl.isEmpty() : "The `proxy_url` option must not be empty.";
        }

    }

    private String transformUrl(HttpSession session, boolean isWebSocket) {
        String url = this.config.proxyUrl;

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
            url = url.replaceFirst(this.proxyUrlHost, session.getHost());
            session.getLogger().debug("Rewrote %s to %s, keep this in mind for the following messages.", this.proxyUrlHost, session.getHost());
        }

        if (this.config.includePath) {
            String append;

            if (this.config.proxyPath == null) {
                append = session.getUri();
            } else {
                append = session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
            }

            session.getLogger().debug("%s -> %s%s%s", url, url, append, session.getQueryString());
            url += append;
            url += session.getQueryString();
        }

        return url;
    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (!this.config.allowHttp) {
            return null;
        }

        // If the path doesn't match don't serve.
        // A NULL path is wildcard.
        if ((this.config.proxyPath != null) && !session.getUri().matches(this.config.proxyPath)) {
            return null;
        }

        final String url = this.transformUrl(session, false);

        session.getLogger().debug("Final proxy url: %s", url);
        Request.Builder builder = new Request.Builder().url(url);

        RequestBody body = null;

        if (session.hasBody()) {
            body = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return null;
                }

                @Override
                public long contentLength() throws IOException {
                    try {
                        return Long.parseLong(session.getHeader("Content-Length"));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }

                @Override
                public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeAll(Okio.source(session.getRequestBodyStream()));
                }
            };
        }

        builder.method(session.getRawMethod(), body);

        for (Entry<String, List<String>> header : session.getHeaders().entrySet()) {
            String key = header.getKey().toLowerCase();

            if (!DISALLOWED_HEADERS.contains(key)) {
                for (String value : header.getValue()) {
                    builder.addHeader(key, value);
                }
            }
        }

        if (this.config.forwardIp) {
            builder.addHeader("X-Forwarded-For", String.join(", ", session.getRequestHops()));
        }

        Request request = builder.build();
        Response response = null;

        try {
            Response $response_pointer = this.client.newCall(request).execute();
            response = $response_pointer;

            HttpStatus status = new HttpStatusAdapter(response.code(), response.message());
            long responseLen = Long.parseLong(response.header("Content-Length", "-1"));
            InputStream responseStream = response.body().byteStream();

            HttpResponse result = new HttpResponse(
                new ResponseContent() {
                    @Override
                    public void write(OutputStream out) throws IOException {
                        // Automatically uses the content length or 16MB for the IO buffer, whichever is
                        // smallest.
                        StreamUtil.streamTransfer(
                            responseStream,
                            out,
                            2048,
                            responseLen
                        );
                    }

                    @Override
                    public long getLength() {
                        return responseLen;
                    }

                    @Override
                    public void close() throws IOException {
                        $response_pointer.close();
                    }
                },
                status
            )
                .setMimeType(response.header("Content-Type", "application/octet-stream"));

            for (Pair<? extends String, ? extends String> header : response.headers()) {
                String key = header.getFirst();

                if (key.equalsIgnoreCase("Transfer-Encoding") ||
                    key.equalsIgnoreCase("Content-Length") ||
                    key.equalsIgnoreCase("Content-Type")) {
                    continue;
                }

                result.putHeader(key, header.getSecond());
            }

            return result;
        } catch (Throwable t) {
            session.getLogger().severe("An error occurred whilst proxying (serving %s %s): \n%s", builder.getMethod$okhttp(), builder.getUrl$okhttp(), t);
            response.close();
            return null;
        }
    }

    @Override
    public WebsocketListener serveWebsocket(WebsocketSession session, HttpRouter router) {
        if (!this.config.allowWebsockets) {
            return null;
        }

        // If the path doesn't match don't serve.
        // A NULL path is wildcard.
        if ((this.config.proxyPath != null) && !session.getUri().matches(this.config.proxyPath)) {
            return null;
        }

        final String url = this.transformUrl(session, true);

        session.getLogger().debug("Final proxy url: %s", url);
        URI uri = URI.create(url);

        return new WebsocketListener() {
            private RemoteWebSocketConnection remote;
            private PromiseWithHandles<Void> connectPromise = new PromiseWithHandles<>();

            @Override
            public void onOpen(Websocket websocket) {
                this.connectPromise.except((t) -> {
                }); // SILENCE.

                try {
                    this.remote = new RemoteWebSocketConnection(uri, websocket, session.getHeaders());

                    for (Entry<String, List<String>> entry : session.getHeaders().entrySet()) {
                        this.remote.addHeader(entry.getKey(), entry.getValue().get(0));
                    }

                    session.getLogger().debug("Connecting to proxy target...");
                    if (this.remote.connectBlocking()) {
                        session.getLogger().debug("Connected to proxy target.");
                    } else {
                        throw new IOException("Couldn't connect to proxy target.");
                    }
                    this.connectPromise.resolve(null);
                } catch (Throwable t) {
                    this.connectPromise.reject(new DropConnectionException());
                    websocket.getSession().getLogger().severe("An error occurred whilst connecting to target (serving %s): \n%s", uri, t);
                    try {
                        websocket.close();
                    } catch (IOException ignored) {}
                }
            }

            @SneakyThrows
            @Override
            public void onText(Websocket websocket, String message) {
                try {
                    this.connectPromise.await();
                    this.remote.send(message);
                } catch (DropConnectionException e) {
                    // NOOP
                } catch (Throwable t) {
                    websocket.getSession().getLogger().debug("An error occurred whilst sending message to target:\n%s", t);
                    throw t;
                }
            }

            @SneakyThrows
            @Override
            public void onBinary(Websocket websocket, byte[] bytes) {
                try {
                    this.connectPromise.await();
                    this.remote.send(bytes);
                } catch (DropConnectionException e) {
                    // NOOP
                } catch (Throwable t) {
                    websocket.getSession().getLogger().debug("An error occurred whilst sending message to target:\n%s", t);
                    throw t;
                }
            }

            @Override
            public void onClose(Websocket websocket) {
                session.getLogger().debug("Closed websocket.");
                if (!this.remote.isClosing() || !this.remote.isClosed()) {
                    try {
                        this.connectPromise.await();
                        this.remote.close();
                    } catch (Throwable ignored) {}
                }
            }
        };
    }

    private class RemoteWebSocketConnection extends WebSocketClient {
        private Websocket client;

        public RemoteWebSocketConnection(URI serverUri, Websocket client, HeaderMap headers) {
            super(serverUri);
            this.client = client;

            if (sslSocketFactory != null) {
                this.setSocketFactory(sslSocketFactory);
            }

            for (Entry<String, List<String>> header : headers.entrySet()) {
                String key = header.getKey().toLowerCase();

                if (!DISALLOWED_HEADERS.contains(key)) {
                    this.addHeader(key, header.getValue().get(0));
                }
            }

            if (config.forwardHost) {
                this.addHeader("Host", this.client.getSession().getHost());
            }

            if (config.forwardIp) {
                this.addHeader("X-Forwarded-For", String.join(", ", this.client.getSession().getRequestHops()));
            }

            this.setTcpNoDelay(true);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            Map<String, String> headers = new HashMap<>();
            handshakedata
                .iterateHttpFields()
                .forEachRemaining((field) -> headers.put(field, handshakedata.getFieldValue(field)));
            this.client.getSession().getLogger().debug("Handshake headers: %s", headers);
        }

        @Override
        public void onMessage(String message) {
            try {
                this.client.getSession().getLogger().trace("Received message from proxy: %s", message);
                this.client.send(message);
            } catch (Throwable t) {
                this.client.getSession().getLogger().debug("An error occurred whilst sending message to client: %s", t);
            }
        }

        @Override
        public void onMessage(ByteBuffer message) {
            try {
                byte[] array = message.array();
                this.client.getSession().getLogger().trace("Received bytes from proxy: %s", Util.bytesToHex(array));
                this.client.send(array);
            } catch (Throwable t) {
                this.client.getSession().getLogger().debug("An error occurred whilst sending message to client: %s", t);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            this.client.getSession().getLogger().debug("Proxy's close reason: %d %s", code, reason);
            try {
                this.client.close();
            } catch (IOException e) {}
        }

        @Override
        public void onError(Exception e) {
            this.client.getSession().getLogger().fatal("Uncaught: %s", e);
        }

    }

    @AllArgsConstructor
    private static class HttpStatusAdapter implements HttpStatus {
        private int code;
        private String description;

        @Override
        public String getStatusString() {
            return String.format("%d %s", this.code, this.description);
        }

        @Override
        public String getDescription() {
            return this.description;
        }

        @Override
        public int getStatusCode() {
            return this.code;
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
