package co.casterlabs.katana.http.servlets;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import lombok.SneakyThrows;

public class RedirectServlet extends HttpServlet {
    private HostConfiguration config;

    public RedirectServlet() {
        super("REDIRECT");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        @SerializedName("redirectUrl")
        public String redirectUrl;

        @SerializedName("includePath")
        public boolean includePath;

    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (this.config.redirectUrl != null) {
            HttpResponse response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.TEMPORARY_REDIRECT, new byte[0]);

            if (this.config.includePath) {
                response.putHeader("location", this.config.redirectUrl + session.getUri());
            } else {
                response.putHeader("location", this.config.redirectUrl);
            }

            return response;
        } else {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Redirect url not set.", router.getConfig());
        }
    }

}
