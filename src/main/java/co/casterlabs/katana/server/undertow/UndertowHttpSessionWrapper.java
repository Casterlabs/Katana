package co.casterlabs.katana.server.undertow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.http.HttpMethod;
import co.casterlabs.katana.http.HttpSession;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class UndertowHttpSessionWrapper extends HttpSession {
    private HttpServerExchange exchange;
    private FastLogger logger;
    private int port;

    private Map<String, List<String>> allQueryParameters = new HashMap<>();
    private Map<String, String> queryParameters = new HashMap<>();
    private Map<String, String> headers = new HashMap<>();

    private byte[] body;

    public UndertowHttpSessionWrapper(HttpServerExchange exchange, FastLogger logger, int port) {
        this.exchange = exchange;
        this.logger = logger;
        this.port = port;

        HeaderMap headerMap = exchange.getRequestHeaders();

        long headersIndex = headerMap.fastIterate();
        while (headersIndex != -1) {
            HeaderValues header = headerMap.fiCurrent(headersIndex);
            HttpString headerName = header.getHeaderName();

            byte[] bytes = new byte[headerName.length()];

            headerName.copyTo(bytes, 0);

            this.headers.put(new String(bytes, StandardCharsets.UTF_8).toLowerCase(), header.getFirst());

            headersIndex = headerMap.fiNext(headersIndex);
        }

        for (Map.Entry<String, Deque<String>> entry : exchange.getQueryParameters().entrySet()) {
            this.allQueryParameters.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            this.queryParameters.put(entry.getKey(), entry.getValue().getFirst());
        }

        this.headers = Collections.unmodifiableMap(this.headers);
    }

    // Request headers
    @Override
    public @NonNull Map<String, String> getHeaders() {
        return this.headers;
    }

    // URI
    @Override
    public String getUri() {
        return this.exchange.getRequestPath();
    }

    @Override
    public @NonNull Map<String, List<String>> getAllQueryParameters() {
        return this.allQueryParameters;
    }

    @Override
    public @NonNull Map<String, String> getQueryParameters() {
        return this.queryParameters;
    }

    @Override
    public @NonNull String getQueryString() {
        if (this.exchange.getQueryString() == null) {
            return "";
        } else {
            return "?" + this.exchange.getQueryString();
        }
    }

    // Request body
    @Override
    public boolean hasBody() {
        return this.exchange.getRequestContentLength() != -1;
    }

    @Override
    public @Nullable byte[] getRequestBodyBytes() throws IOException {
        if (this.body == null) {
            long length = this.exchange.getRequestContentLength();

            if (length != -1) {
                this.body = new byte[(int) length];
                this.exchange.getInputStream().read(this.body, 0, (int) length);

                return this.body;
            } else {
                throw new IOException("No body was sent");
            }
        } else {
            return this.body;
        }
    }

    @Override
    public @NonNull Map<String, String> parseFormBody() throws IOException {
        throw new UnsupportedOperationException(); // TODO
    }

    // Server Info
    @Override
    public @NonNull String getHost() {
        return this.getHeader("host");
    }

    @Override
    public int getPort() {
        return this.port;
    }

    @Override
    public @NonNull FastLogger getLogger() {
        return this.logger;
    }

    // Misc
    @Override
    public @NonNull HttpMethod getMethod() {
        return HttpMethod.valueOf(this.exchange.getRequestMethod().toString());
    }

    @Override
    public @NonNull String getRemoteIpAddress() {
        return this.exchange.getSourceAddress().getHostString();
    }

}
