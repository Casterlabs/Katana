package co.casterlabs.katana;

import java.io.File;
import java.io.IOException;
import java.util.List;

import co.casterlabs.rakurai.io.http.MimeTypes;
import co.casterlabs.rakurai.io.http.StandardHttpStatus;
import co.casterlabs.rakurai.io.http.server.HttpResponse;
import co.casterlabs.rakurai.io.http.server.HttpSession;

public class FileUtil {

    public static HttpResponse serveFile(File file, HttpSession session) {
        try {
            String etag = Integer.toHexString((file.getAbsolutePath() + file.lastModified() + "" + file.length()).hashCode());
            String mime = MimeTypes.getMimeForFile(file);
            String range = session.getHeader("range");
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
                    response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.RANGE_NOT_SATISFIABLE)
                        .putHeader("Content-Range", "bytes 0-0/" + fileLen);
                } else {
                    if (endAt < 0) endAt = fileLen - 1;
                    long newLen = endAt - startFrom + 1;
                    if (newLen < 0) newLen = 0;
                    long dataLen = newLen;

                    response = HttpResponse.newFixedLengthFileResponse(StandardHttpStatus.PARTIAL_CONTENT, file, startFrom, dataLen)
                        .putHeader("Content-Range", "bytes " + startFrom + "-" + endAt + "/" + fileLen);
                }
            } else {
                if ((range != null) && etag.equals(session.getHeader("if-none-match"))) {
                    response = HttpResponse.newFixedLengthResponse(StandardHttpStatus.RANGE_NOT_SATISFIABLE)
                        .putHeader("Content-Range", "bytes 0-0/" + fileLen);
                } else {
                    response = HttpResponse.newFixedLengthFileResponse(StandardHttpStatus.OK, file);
                }
            }

            response.setMimeType(mime);

            response.putHeader("ETag", etag);
            response.putHeader("Content-Length", String.valueOf(fileLen));
            response.putHeader("Content-Disposition", "filename=\"" + file.getName() + "\"");
            response.putHeader("Accept-Ranges", "bytes");

            return response;
        } catch (IOException e) {
            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, "Error while reading file (exists)", null);
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

//  public static boolean isMiki(File file) {
//      int index = file.getName().lastIndexOf('.');
//
//      if (index > 0) {
//          String extension = file.getName().substring(index + 1);
//
//          if (extension.equalsIgnoreCase("miki")) {
//              return true;
//          }
//      }
//
//      return false;
//  }
//
//    public static HttpResponse serveMiki(HttpSession session, File file, HttpStatus status) {
//        try {
//            Map<String, String> headers = new HashMap<>();
//
//            for (Map.Entry<String, List<String>> entry : session.getHeaders().entrySet()) {
//                headers.put(entry.getKey(), entry.getValue().get(0));
//            }
//
//            MikiFileAdapter miki = MikiFileAdapter.readFile(file);
//            WebRequest request = new WebRequest(session.getQueryParameters(), headers, session.getHost(), session.getMethod().name(), session.getUri(), session.getRequestBody(), session.getPort());
//
//            WebResponse response = miki.formatAsWeb(getMikiGlobals(session, status), request);
//
//            HttpResponse result = HttpResponse.newFixedLengthResponse(StandardHttpStatus.lookup(response.getStatus()), response.getResult());
//
//            if (response.getMime() == null) {
//                if (miki.getTemplateFile() != null) {
//                    result.setMimeType(Files.probeContentType(new File(miki.getTemplateFile()).toPath()));
//                }
//            } else {
//                result.setMimeType(response.getMime());
//            }
//
//            result.putAllHeaders(response.getHeaders());
//
//            return result;
//        } catch (Exception e) {
//            return Util.errorResponse(session, StandardHttpStatus.INTERNAL_ERROR, StringUtil.getExceptionStack(e), null);
//        }
//    }
//
//    public static Map<String, String> getMikiGlobals(HttpSession session, HttpStatus status) {
//        Map<String, String> globals = new HashMap<String, String>() {
//            private static final long serialVersionUID = -902644615560162682L;
//
//            @Override
//            public String get(Object key) {
//                return super.get(((String) key).toLowerCase());
//            }
//        };
//
//        globals.put("server", Katana.SERVER_DECLARATION);
//        globals.put("miki", String.format("Miki/%s (Katana/%s)", Miki.VERSION, Katana.VERSION));
//
//        globals.put("remote_ip_address", session.getRemoteIpAddress());
//        globals.put("host", session.getHost());
//
//        globals.put("status_code", String.valueOf(status.getStatusCode()));
//        globals.put("status_message", status.getDescription());
//        globals.put("status", status.getDescription().toUpperCase().replace(' ', '_'));
//
//        return globals;
//    }

}
