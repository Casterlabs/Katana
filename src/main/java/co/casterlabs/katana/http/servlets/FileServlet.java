package co.casterlabs.katana.http.servlets;

import java.io.File;
import java.nio.file.Files;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import co.casterlabs.katana.FileUtil;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.HttpStatus;
import co.casterlabs.miki.json.MikiFileAdapter;
import co.casterlabs.miki.templating.WebRequest;
import co.casterlabs.miki.templating.WebResponse;
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
                                return serveMiki(session, file);
                            }
                        }

                        return FileUtil.sendFile(file, session);
                    } else {
                        return Util.errorResponse(session, HttpStatus.NOT_FOUND, "File not found.");
                    }
                } catch (Exception e) {
                    session.getLogger().severe("An error occured whilst reading a file.");
                    session.getLogger().exception(e);
                    return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, "Unable to read file.");
                }
            } else {
                return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, "Serve directory not set.");
            }
        } else {
            return null;
        }
    }

    public static HttpResponse serveMiki(HttpSession session, File file) {
        try {
            MikiFileAdapter miki = MikiFileAdapter.readFile(file);
            WebRequest request = new WebRequest(session.getQueryParameters(), session.getHeaders(), session.getHost(), session.getMethod().name(), session.getUri(), session.getRequestBody(), session.getPort());

            WebResponse response = miki.formatAsWeb(session.getMikiGlobals(), request);

            HttpResponse result = HttpResponse.newFixedLengthResponse(HttpStatus.lookup(response.getStatus()), response.getResult());

            if (response.getMime() == null) {
                if (miki.getTemplateFile() != null) {
                    result.setMimeType(Files.probeContentType(new File(miki.getTemplateFile()).toPath()));
                }
            } else {
                result.setMimeType(response.getMime());
            }

            result.putAllHeaders(response.getHeaders());

            return result;
        } catch (Exception e) {
            if (e.getCause() != null) {
                return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, e.getMessage() + "<br />" + e.getCause().getMessage());
            } else {
                return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, e.getMessage());
            }
        }
    }

}
