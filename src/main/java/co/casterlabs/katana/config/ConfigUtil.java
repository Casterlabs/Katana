package co.casterlabs.katana.config;

import com.google.gson.JsonObject;

public class ConfigUtil {

    public static boolean getBooleanValue(String key, JsonObject json) {
        if (json.has(key)) {
            return json.get(key).getAsBoolean();
        } else {
            throw new IllegalArgumentException("Configuration is missing key: " + key);
        }
    }

    public static int getIntValue(String key, JsonObject json) {
        if (json.has(key)) {
            return json.get(key).getAsInt();
        } else {
            throw new IllegalArgumentException("Configuration is missing key: " + key);
        }
    }

    public static String getStringValue(String key, JsonObject json) {
        if (json.has(key)) {
            return json.get(key).getAsString();
        } else {
            throw new IllegalArgumentException("Configuration is missing key: " + key);
        }
    }

}
