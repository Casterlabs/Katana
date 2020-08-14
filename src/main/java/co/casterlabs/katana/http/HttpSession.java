package co.casterlabs.katana.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.ResponseException;
import fi.iki.elonen.NanoWSD.WebSocket;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Data
@RequiredArgsConstructor
public class HttpSession {
    private @Setter(AccessLevel.NONE) @NonNull IHTTPSession session;
    private @Setter(AccessLevel.NONE) @NonNull FastLogger logger;
    private final @Setter(AccessLevel.NONE) int port;

    private @Setter(AccessLevel.NONE) Map<String, String> responseHeaders = new HashMap<>();
    private @Setter(AccessLevel.NONE) boolean websocketRequest = false;
    private @Setter(AccessLevel.NONE) Unsafe unsafe = new Unsafe();
    private @Setter(AccessLevel.NONE) String host;
    private String mime = NanoHTTPD.MIME_HTML;
    private WebSocket websocketResponse;
    private Status status = Status.OK;

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

    public Map<String, List<String>> getParameters() {
        return this.session.getParameters();
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

    public void setResponseHeader(String key, String value) {
        this.responseHeaders.put(key, value);
    }

    public String getRequestBody() throws Exception {
        this.parseBody(new HashMap<>());

        return this.session.getQueryParameterString();
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
