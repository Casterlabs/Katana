package co.casterlabs.katana.http.servlets;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.JsonObject;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import kotlin.Pair;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProxyServlet extends Servlet {
    private HostConfiguration config;

    public ProxyServlet() {
        super(ServletType.HTTP, "PROXY");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);

        if (this.config.proxy_path != null) {
            this.config.proxy_path = this.config.proxy_path.replace("*", ".*");
        }
    }

    private static class HostConfiguration {
        public String proxy_url;
        public String proxy_path;
        public boolean include_path;

    }

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (session.isWebsocketRequest()) {
            return false;
        } else if (this.config.proxy_url != null) {
            if ((this.config.proxy_path == null) || session.getUri().matches(this.config.proxy_path)) {
                String url = this.config.proxy_url;

                if (this.config.proxy_path == null) {
                    url += session.getUri();
                } else if (this.config.include_path) {
                    url += session.getUri().replace(this.config.proxy_path.replace(".*", ""), "");
                    url += session.getQueryString();
                }

                OkHttpClient client = new OkHttpClient().newBuilder().build();
                Request.Builder builder = new Request.Builder().url(url);

                if ((session.getMethod() == Method.POST) || (session.getMethod() == Method.PUT)) {
                    builder.post(RequestBody.create(session.getRequestBody().getBytes(StandardCharsets.UTF_8)));
                }

                for (Map.Entry<String, String> header : session.getHeaders().entrySet()) {
                    String key = header.getKey();
                    // Prevent Nano headers from being injected
                    if (!key.equalsIgnoreCase("remote-addr") && !key.equalsIgnoreCase("http-client-ip") && !key.equalsIgnoreCase("host")) {
                        builder.addHeader(key, header.getValue());
                    }
                }

                Request request = builder.build();
                Response response = client.newCall(request).execute();

                for (Pair<? extends String, ? extends String> header : response.headers()) {
                    session.setResponseHeader(header.getFirst(), header.getSecond());
                }

                session.setStatus(Status.lookup(response.code()));
                session.setResponse(response.body().bytes());
            } else {
                return false;
            }
        } else {
            Util.errorResponse(session, Status.INTERNAL_ERROR, "Proxy url not set.");
        }

        return true;
    }

}