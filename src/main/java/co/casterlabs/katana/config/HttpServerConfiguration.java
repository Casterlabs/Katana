package co.casterlabs.katana.config;

import java.util.ArrayList;
import java.util.List;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.io.http.TLSVersion;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonDeserializationMethod;
import co.casterlabs.rakurai.json.annotating.JsonExclude;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.annotating.JsonSerializationMethod;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.element.JsonString;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;

@Getter
@JsonClass(exposeAll = true)
public class HttpServerConfiguration {
    public static final String TYPE = "http";

    private String name = "www";

    private int port = 80;

    @JsonField("is_behind_proxy")
    private boolean isBehindProxy;

    @JsonField("debug_mode")
    private boolean debugMode;

    @JsonField("ssl")
    private SSLConfiguration SSL = new SSLConfiguration();

    private @JsonExclude List<HttpServlet> servlets = new ArrayList<>();

    @JsonSerializationMethod("type")
    private JsonElement $serialize_type() {
        return new JsonString(TYPE);
    }

    @JsonDeserializationMethod("hosts")
    private void $deserialize_hosts(JsonElement e) throws JsonValidationException, JsonParseException {
        this.$deserialize_servlets(e); // TODO Deprecated.
    }

    @JsonDeserializationMethod("servlets")
    private void $deserialize_servlets(JsonElement servlets) throws JsonValidationException, JsonParseException {
        for (JsonElement e : servlets.getAsArray()) {
            JsonObject config = e.getAsObject();
            String type = config.getString("type");

            HttpServlet servlet = Katana.getInstance().getHttpServlet(type);

            // Deprecated stuff.
            if (servlet.getClass().isAnnotationPresent(Deprecated.class)) {
                Katana.getInstance().getLogger().warn("The servlet %s is deprecated and will be removed in the future:\n%s", type, config.toString(true));
            }

            if (config.containsKey("hostname")) {
                String hostname = config.getString("hostname");

                servlet.getHostnames().add(hostname);
                servlet.getCorsAllowedHosts().add(
                    hostname
                        .replace(".", "\\.")
                        .replace("*", ".*")
                );
            }

            if (config.containsKey("allowed_hosts")) {
                // Remap the allowed_hosts key to allowed_cors_hosts.
                config.put("cors_allowed_hosts", config.get("allowed_hosts"));
            }

            // Init the servlet.
            servlet.init(config);

            // Read the rest of the config.
            if (config.containsKey("priority")) {
                servlet.setPriority(config.getNumber("priority").intValue());
            }

            if (config.containsKey("hostnames")) {
                for (JsonElement h_e : config.getArray("hostnames")) {
                    String hostname = h_e.getAsString();

                    servlet.getHostnames().add(hostname);
                    servlet.getCorsAllowedHosts().add(
                        hostname
                            .replace(".", "\\.")
                            .replace("*", ".*")
                    );
                }
            }

            if (config.containsKey("cors_allowed_hosts")) {
                for (JsonElement ach : config.getArray("cors_allowed_hosts")) {
                    String hostname = ach.getAsString();
                    servlet.getCorsAllowedHosts().add(
                        hostname
                            .replace(".", "\\.")
                            .replace("*", ".*")
                    );
                }
            }

            this.servlets.add(servlet);
        }
    }

    @JsonSerializationMethod("servlets")
    private JsonElement $serialize_servlets() {
        JsonArray arr = new JsonArray();

        for (HttpServlet servlet : this.servlets) {
            // We need to convert from the internal format BACK to the user format.
            JsonArray hostnames = new JsonArray();
            for (String hostname : servlet.getHostnames()) {
                hostnames.add(
                    hostname
                        .replace(".*", "*")
                        .replace("\\.", ".")
                );
            }

            // We need to convert from the internal format BACK to the user format.
            JsonArray corsAllowedHosts = new JsonArray();
            for (String hostname : servlet.getCorsAllowedHosts()) {
                corsAllowedHosts.add(
                    hostname
                        .replace(".*", "*")
                        .replace("\\.", ".")
                );
            }

            JsonObject asObject = new JsonObject()
                .put("type", servlet.getType().toLowerCase())
                .put("priority", servlet.getPriority())
                .put("hostnames", hostnames)
                .put("cors_allowed_hosts", corsAllowedHosts);

            // Copy the config in.
            JsonObject config = (JsonObject) Rson.DEFAULT.toJson(servlet.getConfig());
            config.entrySet().forEach((e) -> asObject.put(e.getKey(), e.getValue()));

            arr.add(asObject);
        }

        return arr;
    }

    @JsonClass(exposeAll = true)
    public static class SSLConfiguration {
        public boolean enabled = false;
        public int port = 443;

        public TLSVersion[] tls = TLSVersion.values();
        @JsonField("enabled_cipher_suites")
        public String[] enabledCipherSuites = {
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"
        }; // Null = All Available
        @JsonField("dh_size")
        public int dhSize = 2048;

        @JsonField("allow_insecure")
        public boolean allowInsecure = true;
        public boolean force = false;

        @JsonField("keystore_password")
        public String keystorePassword = "";
        public String keystore = "";

    }

}
