package co.casterlabs.katana;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map.Entry;

import org.apache.commons.collections4.MultiValuedMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.HttpStatus;
import co.casterlabs.katana.http.TLSVersion;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Util {

    public static String[] convertTLS(TLSVersion[] tls) {
        String[] versions = new String[tls.length];

        for (int i = 0; i != tls.length; i++) {
            versions[i] = tls[i].getRuntimeName();
        }

        return versions;
    }

    public static <T extends Collection<String>> T fillFromJson(JsonArray array, T collection) {
        if (array.isJsonArray()) {
            for (JsonElement element : array.getAsJsonArray()) {
                if (!element.isJsonNull()) {
                    collection.add(element.getAsString());
                }
            }
        }

        return collection;
    }

    public static <T> Collection<T> regexGet(MultiValuedMap<String, T> map, String in) {
        Collection<T> ret = new ArrayList<>();

        for (Entry<String, T> entry : map.entries()) {
            if (in.matches(entry.getKey())) {
                ret.add(entry.getValue());
            }
        }

        return ret;
    }

    public static boolean regexContains(Collection<String> list, String in) {
        for (String item : list) {
            if (in.matches(item)) {
                return true;
            }
        }

        return false;
    }

    public static Response errorResponse(Status status, String description, String host, int port) {
        // @formatter:off
        return NanoHTTPD.newFixedLengthResponse(status, "text/html", 
                Katana.ERROR_HTML
                .replace("$RESPONSECODE", String.valueOf(status.getRequestStatus()))
                .replace("$DESCRIPTION", description)
                .replace("$ADDRESS", String.format("%s:%d", host, port))
        );
        // @formatter:on
    }

    public static HttpResponse errorResponse(HttpSession session, HttpStatus status, String description) {
        // @formatter:off
        return HttpResponse.newFixedLengthResponse(status, Katana.ERROR_HTML
                .replace("$RESPONSECODE", String.valueOf(status.getStatusCode()))
                .replace("$DESCRIPTION", description)
                .replace("$ADDRESS", String.format("%s:%d", session.getHost(), session.getPort()))
        );
        // @formatter:on
    }

    public static String getFileOrNull(String file) {
        try {
            return new String(Files.readAllBytes(new File(file).toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    public static <T> T readFileAsJson(File file, Class<T> clazz) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());

        return Katana.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), clazz);
    }

}
