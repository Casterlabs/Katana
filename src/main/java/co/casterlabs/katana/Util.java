package co.casterlabs.katana;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map.Entry;

import org.apache.commons.collections4.MultiValuedMap;
import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;

public class Util {

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

    public static HttpResponse errorResponse(HttpSession session, HttpStatus status, String description, @Nullable ServerConfiguration config) {
        if (config != null) {
            String potential = config.getErrorResponses().get(String.valueOf(status.getStatusCode()));

            if (potential != null) {
                File file = new File(potential);

                if (file.exists()) {
                    if (FileUtil.isMiki(file)) {
                        return FileUtil.serveMiki(session, file, status);
                    } else {
                        return FileUtil.serveFile(file, session);
                    }
                }
            }
        }

        // @formatter:off
        return HttpResponse.newFixedLengthResponse(status, Katana.ERROR_HTML
                .replace("$RESPONSECODE", String.valueOf(status.getStatusCode()))
                .replace("$DESCRIPTION", description)
                .replace("$ADDRESS", String.format("%s:%d", session.getHost(), session.getPort()))
        ).setMimeType("text/html");
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
