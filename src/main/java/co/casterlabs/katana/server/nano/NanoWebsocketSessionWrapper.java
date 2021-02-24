package co.casterlabs.katana.server.nano;

import java.util.List;
import java.util.Map;

import co.casterlabs.katana.http.HttpMethod;
import co.casterlabs.katana.http.websocket.WebsocketSession;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@AllArgsConstructor
public class NanoWebsocketSessionWrapper extends WebsocketSession {
    private @Getter IHTTPSession nanoSession;
    private FastLogger logger;
    private int port;

    // Headers
    @Override
    public @NonNull Map<String, String> getHeaders() {
        return this.nanoSession.getHeaders();
    }

    // URI
    @Override
    public String getUri() {
        return this.nanoSession.getUri();
    }

    @Override
    public @NonNull Map<String, List<String>> getAllQueryParameters() {
        return this.nanoSession.getParameters();
    }

    @SuppressWarnings("deprecation")
    @Override
    public @NonNull Map<String, String> getQueryParameters() {
        return this.nanoSession.getParms();
    }

    @Override
    public @NonNull String getQueryString() {
        if (this.nanoSession.getQueryParameterString() == null) {
            return "";
        } else {
            return "?" + this.nanoSession.getQueryParameterString();
        }
    }

    // Server info
    @Override
    public @NonNull String getHost() {
        return this.nanoSession.getHeaders().getOrDefault("host", "UNKNOWN");
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
        return HttpMethod.valueOf(this.nanoSession.getMethod().name());
    }

    @Override
    public @NonNull String getRemoteIpAddress() {
        return this.nanoSession.getRemoteIpAddress();
    }

}
