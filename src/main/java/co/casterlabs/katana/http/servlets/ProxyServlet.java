package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.DataSize;
import co.casterlabs.rakurai.io.IOUtil;
import co.casterlabs.rakurai.io.http.DropConnectionException;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpResponse.ResponseContent;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;
import co.casterlabs.rakurai.io.http.websocket.Websocket;
import co.casterlabs.rakurai.io.http.websocket.WebsocketCloseCode;
import co.casterlabs.rakurai.io.http.websocket.WebsocketListener;
import co.casterlabs.rakurai.io.http.websocket.WebsocketSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import kotlin.Pair;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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

    public ProxyServlet() {
        super("PROXY");
    }

    @SneakyThrows
    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);

        OkHttpClient.Builder okhttpBuilder = new OkHttpClient.Builder();

        if (this.config.ignoreBadSsl) {
            // https://www.baeldung.com/okhttp-client-trust-all-certificates
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[] {};
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            okhttpBuilder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
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

        String url = this.config.proxyUrl
            .replace("wss://", "https://") // Remap websocket urls to http.
            .replace("ws://", "http://");

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

        session.getLogger().debug("Final proxy url: %s", url);
        Request.Builder builder = new Request.Builder().url(url);

        try {
            // If it throws then we have no body.
            if (session.getRequestBodyBytes() != null) {
                try {
                    builder.method(session.getMethod().name().toUpperCase(), RequestBody.create(session.getRequestBodyBytes()));
                } catch (IOException e) {
                    session.getLogger().fatal("A fatal error occurred whilst trying to read the body:\n%s", e);
                    throw new DropConnectionException();
                }
            }
        } catch (IOException e) {}

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

        Response response = client.newCall(request).execute();

        try {
            HttpStatus status = new HttpStatusAdapter(response.code(), response.message());
            long responseLen = Long.parseLong(response.header("Content-Length", "-1"));
            InputStream responseStream = response.body().byteStream();

            HttpResponse result = new HttpResponse(
                new ResponseContent() {
                    @Override
                    public void write(OutputStream out) throws IOException {
                        // Automatically uses the content length or 16MB for the IO buffer, whichever is
                        // smallest.
                        IOUtil.writeInputStreamToOutputStream(
                            responseStream,
                            out,
                            responseLen,
                            (int) DataSize.MEGABYTE.toBytes(16)
                        );
                    }

                    @Override
                    public long getLength() {
                        return responseLen;
                    }

                    @Override
                    public void close() throws IOException {
                        response.close();
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
        } catch (Exception e) {
            e.printStackTrace();
            IOUtil.safeClose(response);
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

        String url = this.config.proxyUrl
            .replace("https://", "wss://") // Remap http urls to websocket.
            .replace("http://", "ws://");

//        if (this.config.forwardHost) {
//            // Replace the proxyUrl's host with the session's host. Look at the above DNS
//            // logic to see what this does.
//            url = url.replaceFirst(this.proxyUrlHost, session.getHost());
//            session.getLogger().debug("Rewrote %s to %s, keep this in mind for the following messages.", this.proxyUrlHost, session.getHost());
//        }

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

        try {
            URI uri = new URI(url);
        session.getLogger().debug("Final proxy url: %s", url);

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
            session.getLogger().fatal("A fatal error occurred whilst building the proxy url:\n%s", e);
            throw new DropConnectionException();
        }
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
