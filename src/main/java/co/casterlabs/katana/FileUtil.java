package co.casterlabs.katana;

import java.io.File;
import java.io.IOException;
import java.util.List;

import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.HttpStatus;
import co.casterlabs.katana.http.MimeTypes;

public class FileUtil {

    public static HttpResponse sendFile(File file, HttpSession session) {
        try {
            String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());
            String mime = MimeTypes.getMimeForFile(file);
            String range = session.getHeaders().get("range");
            long startFrom = 0;
            long endAt = -1;

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

            HttpResponse response = null;

            if ((range != null) && (startFrom >= 0)) {
                if (startFrom >= fileLen) {
                    response = HttpResponse.newFixedLengthResponse(HttpStatus.RANGE_NOT_SATISFIABLE, new byte[0]);

                    response.putHeader("Content-Range", "bytes 0-0/" + fileLen);

                    return response;
                } else {
                    if (endAt < 0) endAt = fileLen - 1;
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) newLen = 0;
                    long dataLen = newLen;

                    response = HttpResponse.newFixedLengthFileResponse(HttpStatus.PARTIAL_CONTENT, file, startFrom, dataLen);

                    response.putHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                }
            } else {
                if ((range != null) && etag.equals(session.getHeaders().get("if-none-match"))) {
                    response = HttpResponse.newFixedLengthResponse(HttpStatus.RANGE_NOT_SATISFIABLE, new byte[0]);

                    response.putHeader("Content-Range", "bytes 0-0/" + fileLen);
                } else {
                    response = HttpResponse.newFixedLengthFileResponse(HttpStatus.OK, file);
                }
            }

            response.setMimeType(mime);

            response.putHeader("ETag", etag);
            response.putHeader("Content-Length", String.valueOf(fileLen));
            response.putHeader("Content-Disposition", "filename=\"" + file.getName() + "\"");
            response.putHeader("Accept-Ranges", "bytes");

            return response;
        } catch (IOException e) {
            return Util.errorResponse(session, HttpStatus.INTERNAL_ERROR, "Error while reading file (exists)");
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
