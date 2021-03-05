package co.casterlabs.katana.http.servlets;

import java.io.IOException;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import kotlin.Pair;
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
        public String proxyPath = "*";

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
                    url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                try {
                    Request.Builder builder = new Request.Builder().url(url);

                    if (session.hasBody()) {
                        builder.method(session.getMethod().name(), RequestBody.create(session.getRequestBodyBytes()));
                    }

                    for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
                        String key = header.getKey();

                        if (!key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                            builder.addHeader(key, header.getValue());
                        }
                    }

                    if (this.config.forwardIp) {
                        builder.addHeader("x-remote-ip", session.getRemoteIpAddress());
                        builder.addHeader("x-katana-ip", session.getRemoteIpAddress());
                    }

                    Request request = builder.build();
                    Response response = client.newCall(request).execute();

                    HttpStatus status = StandardHttpStatus.lookup(response.code());
                    long responseLen = response.body().contentLength();

                    //@formatter:off
                    HttpResponse result = (responseLen == -1) ?
                            HttpResponse.newChunkedResponse(status, response.body().byteStream()) : 
                            HttpResponse.newFixedLengthResponse(status, response.body().byteStream(), responseLen);
                    //@formatter:on

                    for (Pair<? extends String, ? extends String> header : response.headers()) {
                        String key = header.getFirst();

                        if (!key.equalsIgnoreCase("Transfer-Encoding") && !key.equalsIgnoreCase("Content-Length") && !key.equalsIgnoreCase("Content-Type")) {
                            result.putHeader(key, header.getSecond());
                        }
                    }

                    result.setMimeType(response.header("Content-Type"));

                    return result;
                } catch (IOException e) {
                    throw new RuntimeException(); // Kill the connection.
                }
            } else {
                return null;
            }
        } else {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Proxy url not set.", router.getConfig());
        }
    }

}
