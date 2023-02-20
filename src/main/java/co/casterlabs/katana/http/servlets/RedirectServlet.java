package co.casterlabs.katana.http.servlets;

import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import lombok.SneakyThrows;

public class RedirectServlet extends HttpServlet {
    private @Getter HostConfiguration config;

    public RedirectServlet() {
        super("REDIRECT");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
        @JsonField("redirect_url")
        public String redirectUrl;

        @JsonField("include_path")
        public boolean includePath;

        @JsonValidate
        private void $validate() {
            assert this.redirectUrl != null : "The `redirect_url` option must be set.";
            assert !this.redirectUrl.isEmpty() : "The `redirect_url` option must not be empty.";
        }

    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        String redirectUrl = this.config.redirectUrl;
        if (this.config.includePath) redirectUrl += session.getUri();

        String escaped_redirectUrl = Util.escapeHtml(redirectUrl);

        return HttpResponse.newFixedLengthResponse(
            StandardHttpStatus.TEMPORARY_REDIRECT,
            "<!DOCTYPE html><html><a href=\"" + escaped_redirectUrl + "\">" + escaped_redirectUrl + "</a></html>"
        )
            .putHeader("Location", redirectUrl)
            .setMimeType("text/html");
    }

}
