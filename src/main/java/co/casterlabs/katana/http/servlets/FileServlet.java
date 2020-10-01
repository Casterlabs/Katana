package co.casterlabs.katana.http.servlets;

import java.io.File;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.server.Servlet;
import co.casterlabs.katana.server.ServletType;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import lombok.SneakyThrows;

public class FileServlet extends Servlet {
    private HostConfiguration config;

    public FileServlet() {
        super(ServletType.HTTP, "FILE");
    }

    @Override
    public void init(JsonObject config) {
        this.config = Katana.GSON.fromJson(config, HostConfiguration.class);
    }

    private static class HostConfiguration {
        @SerializedName("use_miki")
        public boolean useMiki = true;
        public String file;
        public String path;
    }

    @SneakyThrows
    @Override
    public boolean serve(HttpSession session) {
        if (session.isWebsocketRequest()) {
            return false;
        } else if (session.getUri().equals(this.config.path)) {
            if (this.config.file != null) {
                File file = new File(this.config.file);

                try {
                    if (file.exists() && file.isFile()) {
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

        return false;

    }

}
