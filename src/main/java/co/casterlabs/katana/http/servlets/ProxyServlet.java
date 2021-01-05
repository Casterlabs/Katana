package co.casterlabs.katana.http.servlets;

import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import kotlin.Pair;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProxyServlet extends Servlet {
    private static final OkHttpClient client = new OkHttpClient();

    private HostConfiguration config;

    public ProxyServlet() {
        super(ServletType.HTTP, "PROXY");
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

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (session.isWebsocketRequest()) {
            return false;
        } else if (this.config.proxyUrl != null) {
            if ((this.config.proxyPath == null) || session.getUri().matches(this.config.proxyPath)) {
                String url = this.config.proxyUrl;

                if (this.config.proxyPath == null) {
                    url += session.getUri();
                } else if (this.config.includePath) {
                    url += session.getUri().replace(this.config.proxyPath.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                Request.Builder builder = new Request.Builder().url(url);

                if (session.hasBody()) {
                    builder.method(session.getMethod().name(), RequestBody.create(session.getRequestBodyBytes()));
                }

                for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
                    String key = header.getKey();
                    // Prevent Nano headers from being injected
                    if (!key.equalsIgnoreCase("x-katana-ip") && !key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                        builder.addHeader(key, header.getValue());
                    }
                }

                if (this.config.forwardIp) {
                    builder.addHeader("x-katana-ip", session.getRemoteIpAddress());
                }

                Request request = builder.build();
                Response response = client.newCall(request).execute();

                for (Pair<? extends String, ? extends String> header : response.headers()) {
                    String key = header.getFirst();

                    if (!key.equalsIgnoreCase("transfer-encoding")) {
                        session.setResponseHeader(key, header.getSecond());
                    }
                }

                session.setStatus(response.code());
                session.setResponse(response.body().bytes());

                response.close();
            } else {
                return false;
            }
        } else {
            Util.errorResponse(session, Status.INTERNAL_ERROR, "Proxy url not set.");
        }

        return true;
    }

}
