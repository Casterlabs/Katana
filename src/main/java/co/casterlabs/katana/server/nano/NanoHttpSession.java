package co.casterlabs.katana.server.nano;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.HttpMethod;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.miki.Miki;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.ResponseException;
import fi.iki.elonen.NanoWSD.WebSocket;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class NanoHttpSession extends HttpSession {
    private @Getter IHTTPSession nanoSession;
    private FastLogger logger;
    private int port;

    private byte[] body;

    private @Getter @Setter WebSocket websocketResponse;

    public NanoHttpSession(IHTTPSession nanoSession, FastLogger logger, int port) {
        this.port = port;
        this.logger = logger;
        this.nanoSession = nanoSession;
    }

    // Request headers
    @Override
    public @NonNull Map<String, String> getHeaders() {
        return this.nanoSession.getHeaders();
    }

    @Override
    public @Nullable String getHeader(@NonNull String header) {
        return this.nanoSession.getHeaders().get(header.toLowerCase());
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

    // Request body
    @Override
    public boolean hasBody() {
        return this.getHeader("content-length") != null;
    }

    @Override
    public @Nullable byte[] getRequestBodyBytes() throws IOException {
        if (this.body == null) {
            if (this.hasBody()) {
                int contentLength = Integer.parseInt(this.getHeader("content-length"));
                this.body = new byte[contentLength];

                this.nanoSession.getInputStream().read(this.body, 0, contentLength);

                return this.body;
            } else {
                return this.body = new byte[0];
            }
        } else {
            return this.body;
        }
    }

    @Override
    public @NonNull Map<String, String> parseFormBody() throws IOException, ResponseException {
        Map<String, String> files = new HashMap<>();

        this.nanoSession.parseBody(files);

        return files;
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

    @Override
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
