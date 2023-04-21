package co.casterlabs.katana.http.servlets;

import java.io.File;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;

public class StaticServlet extends HttpServlet {
    private static final List<String> defaultFiles = Arrays.asList("index.html", /*"index.miki",*/ "index2.html", "default.html", "home.html");

    private @Getter HostConfiguration config;

    public StaticServlet() {
        super("STATIC");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
        @JsonField("require_file_extensions")
        public boolean requireFileExtensions;

//        @JsonField("use_miki")
//        public boolean useMiki = false;

        public String directory;

        @JsonValidate
        private void $validate() {
            assert this.directory != null : "The `directory` option must be set.";
            assert !this.directory.isEmpty() : "The `directory` option must not be empty.";
        }

    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        String uri = decodeURIComponent(session.getUri());
        File directory = new File(this.config.directory);

        File file = FileUtil.getFile(directory, uri, this.config.requireFileExtensions, defaultFiles);

        try {
            if (file.getCanonicalPath().startsWith(directory.getCanonicalPath()) && file.exists() && file.isFile()) {
//                if (this.config.useMiki && FileUtil.isMiki(file)) {
//                    return FileUtil.serveMiki(session, file, StandardHttpStatus.OK);
//                } else {
                return FileUtil.serveFile(file, session);
//                }
            }

            return Util.errorResponse(session, StandardHttpStatus.NOT_FOUND, "File not found.", router.getConfig());
        } catch (Exception e) {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Unable to read file.", router.getConfig());
        }
    }

    @SneakyThrows
    public static String decodeURIComponent(@NonNull String s) {
        return URLDecoder.decode(s, "UTF-8");
    }

}
