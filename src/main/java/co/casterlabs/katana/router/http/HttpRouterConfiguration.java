package co.casterlabs.katana.router.http;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import co.casterlabs.katana.CertificateAutoIssuer.IssueMethod;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
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
public class HttpRouterConfiguration implements KatanaRouterConfiguration {
    private String name = "www";

    private int port = 80;

    @JsonField("is_behind_proxy")
    private boolean isBehindProxy;

    @JsonField("debug_mode")
    private boolean debugMode;

    @JsonField("ssl")
    private HttpSSLConfiguration SSL = new HttpSSLConfiguration();

    private @JsonExclude List<HttpServlet> servlets = new ArrayList<>();

    @Override
    public RouterType getType() {
        return RouterType.HTTP;
    }

    @JsonSerializationMethod("type")
    private JsonElement $serialize_type() {
        return new JsonString(this.getType().name());
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

            HttpServlet servlet = HttpServlet.create(type);

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

    public Set<String> getAllFrontFacingDomains() {
        Set<String> domains = new HashSet<>();
        for (HttpServlet s : this.servlets) {
            domains.addAll(s.getHostnames());
        }
        return domains;
    }

    @JsonClass(exposeAll = true)
    public static class HttpSSLConfiguration extends SSLConfiguration {
        public int port = 443;

        @JsonField("allow_insecure")
        public boolean allowInsecure = true;

        public boolean force = false;

        @JsonField("certificate_auto_issuer")
        public HttpSSLAutoIssueConfiguration certAutoIssuer = new HttpSSLAutoIssueConfiguration();

    }

    @JsonClass(exposeAll = true)
    public static class HttpSSLAutoIssueConfiguration {
        public boolean enabled = false;

        @JsonField("account_email")
        public String accountEmail = "letsencrypt@example.com";

        public IssueMethod method = IssueMethod.HTTP;

    }

}
