package co.casterlabs.katana.http.websocket;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public abstract class WebsocketSession {

    // Request headers
    public abstract @NonNull Map<String, String> getHeaders();

    public @Nullable String getHeader(@NonNull String header) {
        return this.getHeaders().get(header);
    }

    // URI
    public abstract String getUri();

    public abstract @NonNull Map<String, List<String>> getAllQueryParameters();

    public abstract @NonNull Map<String, String> getQueryParameters();

    public abstract @NonNull String getQueryString();

    // Server info
    public abstract @NonNull String getHost();

    public abstract int getPort();

    public abstract @NonNull FastLogger getLogger();

    // Misc
    public abstract @NonNull String getRemoteIpAddress();

}
