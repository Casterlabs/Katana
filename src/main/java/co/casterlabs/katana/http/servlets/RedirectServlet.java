package co.casterlabs.katana.http.servlets;

import com.google.gson.JsonObject;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import lombok.SneakyThrows;

public class RedirectServlet extends Servlet {
    private HostConfiguration config;

    public RedirectServlet() {
        super(ServletType.HTTP, "REDIRECT");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        public String redirect_url;
        public boolean include_path;

    }

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (this.config.redirect_url != null) {
            if (!session.isWebsocketRequest()) {
                if (this.config.include_path) {
                    session.setResponseHeader("location", this.config.redirect_url + session.getUri());
                } else {
                    session.setResponseHeader("location", this.config.redirect_url);
                }
                session.setStatus(Status.TEMPORARY_REDIRECT);
                return true;
            }
        } else {
            Util.errorResponse(session, Status.INTERNAL_ERROR, "Redirect url not set.");
            return true;
        }

        return false;
    }

}
