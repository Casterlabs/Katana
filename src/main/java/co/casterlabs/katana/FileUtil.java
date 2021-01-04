package co.casterlabs.katana;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import co.casterlabs.katana.http.HttpSession;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class FileUtil {

    public static void sendFile(File file, HttpSession session) {
        session.setResponseHeader("Content-Disposition", "filename=\"" + file.getName() + "\"");
        session.setResponseHeader("Accept-Ranges", "bytes");

        try {
            String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());
            String mime = Files.probeContentType(file.toPath());
            String range = session.getHeaders().get("range");
            long startFrom = 0;
            long endAt = -1;

            if (mime == null) {
                mime = "application/octet-stream";
            }

            if (range != null) {
                if (range.startsWith("bytes=")) {
                    range = range.substring("bytes=".length());
                    int minus = range.indexOf('-');
                    try {
                        if (minus > 0) {
                            startFrom = Long.parseLong(range.substring(0, minus));
                            endAt = Long.parseLong(range.substring(minus + 1));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            long fileLen = file.length();

            if ((range != null) && (startFrom >= 0)) {
                if (startFrom >= fileLen) {
                    session.setStatus(Response.Status.RANGE_NOT_SATISFIABLE);
                    session.setResponseHeader("Content-Range", "bytes 0-0/" + fileLen);
                    session.setResponseHeader("ETag", etag);
                    session.setMime(NanoHTTPD.MIME_PLAINTEXT);
                    session.setResponse("");
                } else {
                    if (endAt < 0) endAt = fileLen - 1;
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) newLen = 0;
                    long dataLen = newLen;
                    FileInputStream fis = new FileInputStream(file) {
                        @Override
                        public int available() throws IOException {
                            return (int) dataLen;
                        }
                    };

                    fis.skip(startFrom);

                    session.setStatus(Response.Status.PARTIAL_CONTENT);
                    session.setResponseHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                    session.setResponseHeader("ETag", etag);
                    session.setResponseHeader("Content-Length", "" + dataLen);
                    session.setResponseStream(fis, dataLen);
                    session.setMime(mime);
                }
            } else {
                if ((range != null) && etag.equals(session.getHeaders().get("if-none-match"))) {
                    session.setStatus(Response.Status.RANGE_NOT_SATISFIABLE);
                    session.setResponseHeader("Content-Range", "bytes 0-0/" + fileLen);
                    session.setResponseHeader("ETag", etag);
                    session.setMime(NanoHTTPD.MIME_PLAINTEXT);
                    session.setResponse("");
                } else {
                    session.setStatus(Response.Status.OK);
                    session.setResponseHeader("Content-Length", String.valueOf(fileLen));
                    session.setResponseHeader("ETag", etag);
                    session.setMime(mime);
                    session.setResponseStream(new FileInputStream(file), fileLen);
                }
            }
        } catch (IOException e) {
            Util.errorResponse(session, Status.INTERNAL_ERROR, "Error while reading file (exists)");
        }
    }

    public static File getFile(File directory, String rawUri, boolean requireFileExtensions, List<String> defaultFiles) {
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
