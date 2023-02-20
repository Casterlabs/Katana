package co.casterlabs.katana.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.io.http.TLSVersion;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonDeserializationMethod;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;

@Getter
@JsonClass(exposeAll = true)
public class ServerConfiguration {
    @JsonField("ssl")
    private SSLConfiguration SSL = new SSLConfiguration();

    private List<HttpServlet> servlets = new ArrayList<>();

    private String name = "www";

    private int port = 80;

    @JsonField("is_behind_proxy")
    private boolean isBehindProxy;

    @JsonField("debug_mode")
    private boolean debugMode;

    private File logsDir;

    @JsonDeserializationMethod("logs_dir")
    private void $deserialize_logs_dir(JsonElement dir) {
        if (dir.isJsonString()) {
            this.logsDir = new File(dir.getAsString());
        }
    }

    @JsonDeserializationMethod("hosts")
    private void $deserialize_hosts(JsonElement hosts) throws JsonValidationException, JsonParseException {
        for (JsonElement e : hosts.getAsArray()) {
            JsonObject config = e.getAsObject();
            String type = config.getString("type");

            HttpServlet servlet = Katana.getInstance().getServlet(type);

            if (servlet.getClass().isAnnotationPresent(Deprecated.class)) {
                Katana.getInstance().getLogger().warn("The servlet %s is deprecated and will be removed in the future:\n%s", type, config.toString(true));
            }

            servlet.init(config);

            if (config.containsKey("priority")) {
                servlet.setPriority(config.getNumber("priority").intValue());
            }

            if (config.containsKey("hostname")) {
                String hostname = config.getString("hostname");

                servlet.getHosts().add(hostname);
                servlet.getAllowedHosts().add(
                    hostname
                        .replace(".", "\\.")
                        .replace("*", ".*")
                );
            }

            if (config.containsKey("hostnames")) {
                for (JsonElement h_e : config.getArray("hostnames")) {
                    String hostname = h_e.getAsString();

                    servlet.getHosts().add(hostname);
                    servlet.getAllowedHosts().add(
                        hostname
                            .replace(".", "\\.")
                            .replace("*", ".*")
                    );
                }
            }

            if (config.containsKey("allowed_hosts")) {
                for (JsonElement ah_e : config.getArray("allowed_hosts")) {
                    String hostname = ah_e.getAsString();

                    servlet.getAllowedHosts().add(
                        hostname
                            .replace(".", "\\.")
                            .replace("*", ".*")
                    );
                }
            }

            this.servlets.add(servlet);
        }

        Collections.sort(this.servlets, (HttpServlet s1, HttpServlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });
    }

    @JsonClass(exposeAll = true)
    public static class SSLConfiguration {
        public boolean enabled = false;

        public TLSVersion[] tls = TLSVersion.values();
        public String[] enabledCipherSuites = null; // Null = All Available
        public boolean allowInsecure = true;
        public boolean force = false;
        public int dhSize = 2048;
        public int port = 443;

        public String keystorePassword = "";
        public String keystore = "";

    }

}
