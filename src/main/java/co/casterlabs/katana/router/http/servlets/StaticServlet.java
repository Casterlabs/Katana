package co.casterlabs.katana.router.http.servlets;

import java.io.File;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;

import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.HttpUtil;
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
        public boolean requireFileExtensions = false;

//        @JsonField("use_miki")
//        public boolean useMiki = false;

        public String directory = "www";

        @JsonValidate
        private void $validate() {
            assert this.directory != null : "The `directory` option must be set.";
            assert !this.directory.isEmpty() : "The `directory` option must not be empty.";
        }

    }

    @Override
    public boolean matchHttp(HttpSession session, HttpRouter router) {
        return true;
    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session, HttpRouter router) {
        String uri = decodeURIComponent(session.uri().path);
        File directory = new File(this.config.directory);

        try {
            File file = getFile(directory, uri, this.config.requireFileExtensions, defaultFiles);

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

    @SneakyThrows
    public static String decodeURIComponent(@NonNull String s) {
        return URLDecoder.decode(s, "UTF-8");
    }

    private static File getFile(File directory, String rawUri, boolean requireFileExtensions, List<String> defaultFiles) {
        String uri = rawUri.endsWith("/") ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
        File file = new File(directory, uri);

        if (file.isDirectory() && (defaultFiles != null)) {
            for (String def : defaultFiles) {
                File defFile = new File(file, def);

                if (defFile.isFile()) return defFile;
            }
        } else if (!file.exists() && !requireFileExtensions && !file.getName().contains(".")) {
            String name = file.getName().split("\\.")[0];
            String[] split = uri.split("/");
            File parent = new File(directory, uri.replace("/" + split[split.length - 1], ""));

            if (parent.isDirectory()) {
                for (File possible : parent.listFiles()) {
                    if (possible.isFile() && possible.getName().split("\\.")[0].equalsIgnoreCase(name)) {
                        return possible;
                    }
                }
            }
        }

        return file;
    }

}
