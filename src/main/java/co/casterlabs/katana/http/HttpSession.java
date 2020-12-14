package co.casterlabs.katana.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.ResponseException;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public abstract class HttpSession {

    // Response
    public final void setResponse(@NonNull String str) {
        this.setResponse(str.getBytes(StandardCharsets.UTF_8));
    }

    public void setResponse(@NonNull byte[] bytes) {
        this.setResponseStream(new ByteArrayInputStream(bytes), bytes.length);
    }

    public abstract void setResponseStream(@NonNull InputStream is, long length);

    public abstract void setChunkedResponseStream(@NonNull InputStream is);

    public final void setStatus(Status status) {
        this.setStatus(status.getRequestStatus());
    }

    public abstract void setStatus(int code);

    public abstract void setMime(@NonNull String mime);

    public abstract void putAllHeaders(@NonNull Map<String, String> headers);

    public abstract void setResponseHeader(@NonNull String key, @NonNull String value);

    // Request headers
    public abstract @NonNull Map<String, String> getHeaders();

    public abstract @Nullable String getHeader(@NonNull String header);

    // URI
    public abstract String getUri();

    public abstract @NonNull Map<String, List<String>> getAllQueryParameters();

    public abstract @NonNull Map<String, String> getQueryParameters();

    public abstract @NonNull String getQueryString();

    // Request body
    public abstract boolean hasBody();

    public @Nullable String getRequestBody() throws IOException {
        if (this.hasBody()) {
            return new String(this.getRequestBodyBytes(), StandardCharsets.UTF_8);
        } else {
            return null;
        }
    }

    public abstract @Nullable byte[] getRequestBodyBytes() throws IOException;

    public abstract @NonNull Map<String, String> parseFormBody() throws IOException, ResponseException;

    // Server info
    public abstract @NonNull String getHost();

    public abstract int getPort();

    public abstract @NonNull FastLogger getLogger();

    // Misc
    public abstract @NonNull Method getMethod();

    public abstract @NonNull String getRemoteIpAddress();

    public abstract @NonNull Map<String, String> getMikiGlobals();

    public abstract boolean isWebsocketRequest();

    public enum Method {
        GET,
        PUT,
        POST,
        DELETE,
        HEAD,
        OPTIONS,
        TRACE,
        CONNECT,
        PATCH,
        PROPFIND,
        PROPPATCH,
        MKCOL,
        MOVE,
        COPY,
        LOCK,
        UNLOCK;
    }

}
