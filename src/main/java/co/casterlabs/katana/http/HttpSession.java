package co.casterlabs.katana.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import fi.iki.elonen.NanoHTTPD.ResponseException;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public abstract class HttpSession {

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
    public abstract @NonNull HttpMethod getMethod();

    public abstract @NonNull String getRemoteIpAddress();

    public abstract @NonNull Map<String, String> getMikiGlobals();

}
