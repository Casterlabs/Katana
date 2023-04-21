package co.casterlabs.katana.http.servlets;

import java.io.File;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpSession;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import lombok.SneakyThrows;

public class FileServlet extends HttpServlet {
    private @Getter HostConfiguration config;

    public FileServlet() {
        super("FILE");
    }

    @Override
    public void init(JsonObject config) throws JsonValidationException, JsonParseException {
        this.config = Rson.DEFAULT.fromJson(config, HostConfiguration.class);
    }

    @JsonClass(exposeAll = true)
    public static class HostConfiguration {
//        @JsonClass("use_miki")
//        public boolean useMiki = true;

        public String file;
        public String path;

        @JsonValidate
        private void $validate() {
            assert this.file != null : "The `file` option must be set.";
            assert this.path != null : "The `path` option must be set.";
            assert !this.file.isEmpty() : "The `file` option must not be empty.";
            assert !this.path.isEmpty() : "The `path` option must not be empty.";
        }

    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        if (!session.getUri().matches(this.config.path)) {
            return null;
        }

        File file = new File(this.config.file);

        try {
            if (file.exists() && file.isFile()) {
//                if (this.config.useMiki && FileUtil.isMiki(file)) {
//                    return FileUtil.serveMiki(session, file, StandardHttpStatus.OK);
//                } else {
                return FileUtil.serveFile(file, session);
//                }
            }

            return Util.errorResponse(session, StandardHttpStatus.NOT_FOUND, "File not found.", router.getConfig());
        } catch (Exception e) {
            e.printStackTrace();
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Unable to read file.", router.getConfig());
        }
    }

}
