package co.casterlabs.katana.http.servlets;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonObject;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import lombok.SneakyThrows;

public class StaticServlet extends Servlet {
    private static final List<String> defaultFiles = Arrays.asList("index.html", "index2.html", "default.html", "home.html", "placeholder.html");

    private HostConfiguration config;

    public StaticServlet() {
        super(ServletType.HTTP, "STATIC");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        public String directory;
        public boolean require_file_extensions;

    }

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (session.isWebsocketRequest()) {
            return false;
        } else if (this.config.directory != null) {
            File directory = new File(this.config.directory);
            File file = FileUtil.getFile(directory, session.getUri().replace('\\', '/'), this.config.require_file_extensions, defaultFiles);

            try {
                if (file.getCanonicalPath().startsWith(directory.getCanonicalPath()) && file.exists() && file.isFile()) {
                    FileUtil.sendFile(file, session);
                } else {
                    Util.errorResponse(session, Status.NOT_FOUND, "File not found.");
                }
            } catch (Exception e) {
                session.getLogger().severe("An error occured whilst reading a file.");
                session.getLogger().exception(e);
                Util.errorResponse(session, Status.INTERNAL_ERROR, "Unable to read file.");
            }
        } else {
            Util.errorResponse(session, Status.INTERNAL_ERROR, "Serve directory not set.");
        }

        return true;
    }

}
