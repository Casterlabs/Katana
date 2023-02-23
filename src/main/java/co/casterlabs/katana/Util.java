package co.casterlabs.katana;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.commons.collections4.MultiValuedMap;
import org.jetbrains.annotations.Nullable;

import co.casterlabs.katana.config.HttpServerConfiguration;
import co.casterlabs.rakurai.io.http.HttpResponse;
import co.casterlabs.rakurai.io.http.HttpSession;
import co.casterlabs.rakurai.io.http.HttpStatus;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import lombok.NonNull;

public class Util {

    public static <T extends Collection<String>> T fillFromJson(JsonArray array, T collection) {
        if (array.isJsonArray()) {
            for (JsonElement element : array.getAsArray()) {
                if (!element.isJsonNull()) {
                    collection.add(element.getAsString());
                }
            }
        }
        return collection;
    }

    public static <T> List<T> regexGet(MultiValuedMap<String, T> map, String in) {
        List<T> ret = new LinkedList<>();

        for (Entry<String, T> entry : map.entries()) {
            if (in.matches(entry.getKey())) {
                ret.add(entry.getValue());
            }
        }

        return ret;
    }

    public static <T> List<T> regexGet(Map<String, T> map, String in) {
        List<T> ret = new LinkedList<>();

        for (Entry<String, T> entry : map.entrySet()) {
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

    public static HttpResponse errorResponse(HttpSession session, HttpStatus status, String description, @Nullable HttpServerConfiguration config) {
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

        return Rson.DEFAULT.fromJson(new String(bytes, StandardCharsets.UTF_8), clazz);
    }

    public static String escapeHtml(@NonNull String str) {
        return str
            .codePoints()
            .mapToObj(c -> c > 127 || "\"'<>&".indexOf(c) != -1 ? "&#" + c + ";" : new String(Character.toChars(c)))
            .collect(Collectors.joining());
    }

}
