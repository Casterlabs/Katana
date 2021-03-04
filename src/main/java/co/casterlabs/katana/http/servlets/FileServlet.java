package co.casterlabs.katana.http.servlets;

import java.io.File;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import lombok.SneakyThrows;

public class FileServlet extends HttpServlet {
    private HostConfiguration config;

    public FileServlet() {
        super("FILE");
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
    public HttpResponse serveHttp(HttpSession session) {
        if (session.getUri().equals(this.config.path)) {
            if (this.config.file != null) {
                File file = new File(this.config.file);

                try {
                    if (file.exists() && file.isFile()) {
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
                    e.printStackTrace();
                    return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Unable to read file.");
                }
            } else {
                return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Serve directory not set.");
            }
        } else {
            return null;
        }
    }

}
