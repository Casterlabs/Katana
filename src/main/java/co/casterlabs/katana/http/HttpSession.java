package co.casterlabs.katana.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.casterlabs.katana.Katana;
import co.casterlabs.miki.Miki;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.ResponseException;
import fi.iki.elonen.NanoWSD.WebSocket;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Data
@RequiredArgsConstructor
@Setter(AccessLevel.NONE)
public class HttpSession {
    private @NonNull IHTTPSession session;
    private @NonNull FastLogger logger;
    private final int port;

    private @Getter(AccessLevel.NONE) byte[] body;

    private Map<String, String> responseHeaders = new HashMap<>();
    private @Setter String mime = NanoHTTPD.MIME_HTML;
    private @Setter WebSocket websocketResponse;
    private @Setter Status status = Status.OK;
    private boolean websocketRequest = false;
    private Unsafe unsafe = new Unsafe();
    private String host;

    private @Setter(AccessLevel.NONE) InputStream responseStream = new InputStream() {
        @Override
        public int read() {
            return -1;
        }
    };

    public void setResponse(String str) {
        this.setResponse(str.getBytes(StandardCharsets.UTF_8));
    }

    @SneakyThrows
    public void setResponse(byte[] bytes) {
        this.responseStream = new ByteArrayInputStream(bytes);
    }

    public void setResponseStream(InputStream is) {
        this.responseStream = is;
    }

    public Map<String, String> getHeaders() {
        return this.session.getHeaders();
    }

    public void parseBody(Map<String, String> files) throws IOException, ResponseException {
        this.session.parseBody(files);
    }

    public Method getMethod() {
        return this.session.getMethod();
    }

    public String getHeader(String header) {
        return this.session.getHeaders().getOrDefault(header.toLowerCase(), "");
    }

    public Map<String, List<String>> getParameters() {
        return this.session.getParameters();
    }

    public boolean hasBody() {
        return this.hasHeader("content-length");
    }

    public String getUri() {
        String uri = this.session.getUri();

        if (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }

        return uri;
    }

    public String getRemoteIpAddress() {
        return this.session.getRemoteIpAddress();
    }

    @SuppressWarnings("deprecation")
    public Map<String, String> getQueryParameters() {
        return this.session.getParms();
    }

    public boolean hasHeader(String header) {
        return this.session.getHeaders().containsKey(header.toLowerCase());
    }

    public String getQueryString() {
        return "?" + this.session.getQueryParameterString();
    }

    public void setResponseHeader(String key, String value) {
        this.responseHeaders.put(key, value);
    }

    public String getRequestBody() throws Exception {
        return new String(this.getRequestBodyBytes());
    }

    public byte[] getRequestBodyBytes() throws Exception {
        if (this.body == null) {
            if (this.hasBody()) {
                int contentLength = Integer.parseInt(this.session.getHeaders().get("content-length"));
                this.body = new byte[contentLength];

                this.session.getInputStream().read(this.body, 0, contentLength);

                return this.body;
            } else {
                return this.body = new byte[0];
            }
        } else {
            return this.body;
        }
    }

    public Map<String, String> getMikiGlobals() {
        Map<String, String> globals = new HashMap<String, String>() {
            private static final long serialVersionUID = -902644615560162682L;

            @Override
            public String get(Object key) {
                return super.get(((String) key).toLowerCase());
            }
        };

        globals.put("server", String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic")));
        globals.put("miki", String.format("Miki/%s (Katana/%s)", Miki.VERSION, Katana.VERSION));
        globals.put("status_code", String.valueOf(this.status.getRequestStatus()));
        globals.put("status_message", this.status.getDescription());
        globals.put("status", this.status.name());
        globals.put("host", this.host);

        return globals;
    }

    public class Unsafe {
        public void setWebsocketRequest(boolean socketRequest) {
            websocketRequest = socketRequest;
        }

        public void setHost(String newHost) {
            host = newHost;
        }
    }

}
