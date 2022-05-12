package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.DropConnectionException;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import kotlin.Pair;
import lombok.AllArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProxyServlet extends HttpServlet {
    private static final OkHttpClient client = new OkHttpClient();

    private HostConfiguration config;

    public ProxyServlet() {
        super("PROXY");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);

        if (this.config.proxyPath != null) {
            this.config.proxyPath = this.config.proxyPath.replace("*", ".*");
        }
    }

    private static class HostConfiguration {
        @SerializedName("proxy_url")
        public String proxyUrl;

        @SerializedName("proxy_path")
        public String proxyPath;

        @SerializedName("include_path")
        public boolean includePath;

        @SerializedName("forward_ip")
        public boolean forwardIp;

    }

    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath == null) || session.getUri().matches(this.config.proxyPath)) {
                String url = this.config.proxyUrl;

                if (this.config.proxyPath == null) {
                    url += session.getUri();
                } else if (this.config.includePath) {
                    if (this.config.proxyPath != null) {
                        url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    }

                    url += session.getQueryString();
                }

                Request.Builder builder = new Request.Builder().url(url);

                try {
                    // If it throws then we have no body.
                    if (session.getRequestBodyBytes() != null) {
                        try {
                            builder.method(session.getMethod().name().toUpperCase(), RequestBody.create(session.getRequestBodyBytes()));
                        } catch (IOException e) {
                            throw new DropConnectionException();
                        }
                    }
                } catch (IOException e) {}

                for (Entry<String, List<String>> header : session.getHeaders().entrySet()) {
                    String key = header.getKey();

                    if (!key.equals("remote-addr") && !key.equals("http-client-ip") && !key.equals("host")) {
                        builder.addHeader(key, header.getValue().get(0));
                    }
                }

                if (this.config.forwardIp) {
                    builder.addHeader("X-Remote-IP", session.getRemoteIpAddress()); // Deprecated
                    builder.addHeader("X-Katana-IP", session.getRemoteIpAddress()); // Deprecated
                    builder.addHeader("X-Forwarded-For", String.join(", ", session.getRequestHops()));
                }

                Request request = builder.build();

                try (Response response = client.newCall(request).execute()) {

                    HttpStatus status = new HttpStatusAdapter(response.code());
                    long responseLen = response.body().contentLength();

                    //@formatter:off
                    HttpResponse result = (responseLen == -1) ?
                            HttpResponse.newChunkedResponse(status, response.body().byteStream()) : 
                            HttpResponse.newFixedLengthResponse(status, response.body().bytes()); // Ugh.
                    //@formatter:on

                    for (Pair<? extends String, ? extends String> header : response.headers()) {
                        String key = header.getFirst();

                        if (!key.equalsIgnoreCase("Transfer-Encoding") && !key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Content-Type")) {
                            result.putHeader(key, header.getSecond());
                        }
                    }

                    result.setMimeType(response.header("Content-Type", "application/octet-stream"));

                    return result;
                } catch (Exception e) {
                    e.printStackTrace();

                    // Rakurai will automatically close the stream if a write error or
                    // end of stream is reached.
                    throw new DropConnectionException();
                }
            } else {
                return null;
            }
        } else {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Proxy url not set.", router.getConfig());
        }
    }

    @AllArgsConstructor
    private static class HttpStatusAdapter implements HttpStatus {
        private int code;

        @Override
        public String getStatusString() {
            return String.valueOf(this.code);
        }

        @Override
        public String getDescription() {
            return "";
        }

        @Override
        public int getStatusCode() {
            return this.code;
        }
    }

}
