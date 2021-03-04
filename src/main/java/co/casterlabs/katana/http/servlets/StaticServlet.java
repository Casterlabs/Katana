package co.casterlabs.katana.http.servlets;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import lombok.SneakyThrows;

public class StaticServlet extends HttpServlet {
    private static final List<String> defaultFiles = Arrays.asList("index.html", "index.miki", "index2.html", "default.html", "home.html", "placeholder.html");

    private HostConfiguration config;

    public StaticServlet() {
        super("STATIC");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        @SerializedName("require_file_extensions")
        public boolean requireFileExtensions;

        @SerializedName("use_miki")
        public boolean useMiki = true;

        public String directory;

    }

    @SneakyThrows
    @Override
    public HttpResponse serveHttp(HttpSession session) {
        if (this.config.directory != null) {
            File directory = new File(this.config.directory);
            File file = FileUtil.getFile(directory, session.getUri().replace('\\', '/'), this.config.requireFileExtensions, defaultFiles);

            try {
                if (file.getCanonicalPath().startsWith(directory.getCanonicalPath()) && file.exists() && file.isFile()) {
                    int index = file.getName().lastIndexOf('.');

                    if (this.config.useMiki && (index != 0)) {
                        String extension = file.getName().substring(index + 1);

                        if (extension.equalsIgnoreCase("miki")) {
                            return FileUtil.serveMiki(session, file);
                        }
                    }

                    return FileUtil.sendFile(file, session);
                } else {
                    return Util.errorResponse(session, StandardHttpStatus.NOT_FOUND, "File not found.");
                }
            } catch (Exception e) {
                return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Unable to read file.");
            }
        } else {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Serve directory not set.");
        }
    }

}
