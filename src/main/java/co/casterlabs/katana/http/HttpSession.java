package co.casterlabs.katana.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.Katana;
import co.casterlabs.miki.Miki;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public abstract class HttpSession {

    // Request headers
    public abstract @NonNull Map<String, String> getHeaders();

    public @Nullable String getHeader(@NonNull String header) {
        return this.getHeaders().get(header.toLowerCase());
    }

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

    public abstract @NonNull Map<String, String> parseFormBody() throws IOException;

    // Server info
    public abstract @NonNull String getHost();

    public abstract int getPort();

    public abstract @NonNull FastLogger getLogger();

    // Misc
    public abstract @NonNull HttpMethod getMethod();

    public abstract @NonNull String getRemoteIpAddress();

    public @NonNull Map<String, String> getMikiGlobals() {
        Map<String, String> globals = new HashMap<String, String>() {
            private static final long serialVersionUID = -902644615560162682L;

            @Override
            public String get(Object key) {
                return super.get(((String) key).toLowerCase());
            }
        };

        globals.put("server", String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic")));
        globals.put("miki", String.format("Miki/%s (Katana/%s)", Miki.VERSION, Katana.VERSION));

        globals.put("ip_address", this.getRemoteIpAddress());

        globals.put("host", this.getHost());

        return globals;
    }

}
