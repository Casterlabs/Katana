package co.casterlabs.katana.router.http.servlets;

import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import lombok.Getter;

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
        public String redirectUrl = "https://example.com";

        @JsonField("include_path")
        public boolean includePath = false;

        @JsonValidate
        private void $validate() {
            assert this.redirectUrl != null : "The `redirect_url` option must be set.";
            assert !this.redirectUrl.isEmpty() : "The `redirect_url` option must not be empty.";
        }

    }

    @Override
    public boolean matchHttp(HttpSession session, HttpRouter router) {
        return true;
    }

    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        String redirectUrl = this.config.redirectUrl;
        if (this.config.includePath) redirectUrl += session.uri().rawPath;

        session.logger().debug("Redirecting to: %s", redirectUrl);

        String escaped_redirectUrl = Util.escapeHtml(redirectUrl);
        return HttpResponse.newFixedLengthResponse(
            StandardHttpStatus.TEMPORARY_REDIRECT,
            "<!DOCTYPE html><html><a href=\"" + escaped_redirectUrl + "\">" + escaped_redirectUrl + "</a></html>"
        )
            .mime("text/html")
            .header("Location", redirectUrl);
    }

}
