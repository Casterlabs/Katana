package co.casterlabs.katana.http.servlets;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import co.casterlabs.miki.json.MikiFileAdapter;
import co.casterlabs.miki.templating.WebResponse;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import lombok.SneakyThrows;

public class StaticServlet extends Servlet {
    private static final List<String> defaultFiles = Arrays.asList("index.html", "index.miki", "index2.html", "default.html", "home.html", "placeholder.html");

    private HostConfiguration config;

    public StaticServlet() {
        super(ServletType.HTTP, "STATIC");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        public boolean require_file_extensions;
        @SerializedName("use_miki")
        public boolean useMiki = true;
        public String directory;

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
                    int index = file.getName().lastIndexOf('.');

                    if (this.config.useMiki && (index != 0)) {
                        String extension = file.getName().substring(index + 1);

                        if (extension.equalsIgnoreCase("miki")) {
                            StaticServlet.serveMiki(session, file);
                            return true;
                        }
                    }

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

    public static void serveMiki(HttpSession session, File file) {
        try {
            MikiFileAdapter miki = MikiFileAdapter.readFile(file);
            WebResponse response = miki.formatAsWeb(session.getMikiGlobals());

            if (response.getMime() == null) {
                if (miki.getTemplateFile() != null) {
                    session.setMime(Util.getMimeForFile(new File(miki.getTemplateFile())));
                }
            } else {
                session.setMime(response.getMime());
            }

            session.getResponseHeaders().putAll(response.getHeaders());
            session.setStatus(Status.lookup(response.getStatus()));
            session.setResponse(response.getResult());
        } catch (Exception e) {
            e.printStackTrace();
            Util.errorResponse(session, Status.INTERNAL_ERROR, String.format("The following Miki template is invalid due to the following reason:<br /><br />%s: %s", e.getClass().getCanonicalName(), e.getMessage()));
        }
    }

}
