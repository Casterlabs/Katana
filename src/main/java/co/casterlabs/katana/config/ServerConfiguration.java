package co.casterlabs.katana.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.io.http.TLSVersion;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
@Setter(AccessLevel.NONE)
public class ServerConfiguration {
    private SSLConfiguration SSL = new SSLConfiguration();
    private List<HttpServlet> servlets = new ArrayList<>();
    private String name = "www";
    private int port = 80;
    private Map<String, Map<String, String>> errorResponses = new HashMap<>();
    private boolean debugMode;

    public ServerConfiguration(JsonObject json, Katana katana) throws IllegalArgumentException {
        this.SSL = Katana.GSON.fromJson(json.get("ssl"), SSLConfiguration.class);
        this.name = ConfigUtil.getStringValue("name", json);
        this.port = ConfigUtil.getIntValue("port", json);
        this.errorResponses = Katana.GSON.fromJson(json.get("error_responses"), new TypeToken<Map<String, Map<String, String>>>() {
        }.getType());

        if (json.has("debug_mode")) {
            this.debugMode = json.get("debug_mode").getAsBoolean();
        }

        JsonElement array = json.get("hosts");
        if ((array != null) && array.isJsonArray()) {
            for (JsonElement e : array.getAsJsonArray()) {
                JsonObject config = e.getAsJsonObject();
                String type = config.get("type").getAsString();

                HttpServlet servlet = katana.getServlet(type);

                servlet.init(config);

                if (config.has("priority")) {
                    servlet.setPriority(config.get("priority").getAsInt());
                }

                if (config.has("hostname")) {
                    String hostname = config.get("hostname").getAsString();
                    servlet.getHosts().add(hostname);
                    servlet.getAllowedHosts().add(hostname.replace(".", "\\.").replace("*", ".*"));
                }

                if (config.has("hostnames")) {
                    for (JsonElement hostname : config.getAsJsonArray("hostnames")) {
                        servlet.getHosts().add(hostname.getAsString());
                        servlet.getAllowedHosts().add(hostname.getAsString().replace(".", "\\.").replace("*", ".*"));
                    }
                }

                if (config.has("allowed_hosts")) {
                    for (JsonElement hostname : config.getAsJsonArray("allowed_hosts")) {
                        servlet.getAllowedHosts().add(hostname.getAsString().replace(".", "\\.").replace("*", ".*"));
                    }
                }

                this.servlets.add(servlet);
            }
        }

        Collections.sort(this.servlets, (HttpServlet s1, HttpServlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });
    }

    public static class SSLConfiguration {
        public boolean enabled = false;

        public TLSVersion[] tls = TLSVersion.values();
        public String[] enabled_cipher_suites = null; // Null = All Available
        public boolean allow_insecure = true;
        public boolean force = false;
        public int dh_size = 2048;
        public int port = 443;

        public String keystore_password = "";
        public String keystore = "";

    }

}
