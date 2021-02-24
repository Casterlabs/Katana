package co.casterlabs.katana.http.servlets;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.HttpStatus;
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
    public HttpResponse serveHttp(HttpSession session) {
        if (this.config.redirectUrl != null) {
            HttpResponse response = HttpResponse.newFixedLengthResponse(HttpStatus.TEMPORARY_REDIRECT, new byte[0]);

            if (this.config.includePath) {
                response.putHeader("location", this.config.redirectUrl + session.getUri());
            } else {
                response.putHeader("location", this.config.redirectUrl);
            }

            return response;
        } else {
            return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, "Redirect url not set.");
        }
    }

}
