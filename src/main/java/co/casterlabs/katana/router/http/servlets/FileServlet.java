package co.casterlabs.katana.router.http.servlets;

import java.io.File;

import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.HttpUtil;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidate;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
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
        if (!session.uri().path.matches(this.config.path)) {
            return null;
        }

        try {
            File file = new File(this.config.file);

            if (file.exists() && file.isFile()) {
//                if (this.config.useMiki && FileUtil.isMiki(file)) {
//                    return FileUtil.serveMiki(session, file, StandardHttpStatus.OK);
//                } else {
                return HttpResponse.newRangedFileResponse(session, StandardHttpStatus.OK, file);
//                }
            }

            return HttpUtil.errorResponse(session, StandardHttpStatus.NOT_FOUND, "File not found.");
        } catch (Exception e) {
            e.printStackTrace();
            return HttpUtil.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Unable to read file.");
        }
    }

}
