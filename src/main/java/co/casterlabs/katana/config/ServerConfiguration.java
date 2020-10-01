package co.casterlabs.katana.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.server.Servlet;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
@Setter(AccessLevel.NONE)
public class ServerConfiguration {
    private SSLConfiguration SSL = new SSLConfiguration();
    private List<Servlet> servlets = new ArrayList<>();
    private boolean panel;
    private String name;
    private int port;

    public ServerConfiguration(JsonObject json, Katana katana) throws IllegalArgumentException {
        this.SSL = Katana.GSON.fromJson(json.get("ssl"), SSLConfiguration.class);
        this.name = ConfigUtil.getStringValue("name", json);
        this.port = ConfigUtil.getIntValue("port", json);

        JsonElement array = json.get("hosts");
        if ((array != null) && array.isJsonArray()) {
            for (JsonElement e : array.getAsJsonArray()) {
                JsonObject config = e.getAsJsonObject();
                String type = config.get("type").getAsString();

                Servlet servlet = katana.getServlet(type);

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

        Collections.sort(this.servlets, (Servlet s1, Servlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });
    }

    public void addServlet(Servlet servlet) {
        this.servlets.add(servlet);

        Collections.sort(this.servlets, (Servlet s1, Servlet s2) -> {
            return s1.getPriority() > s2.getPriority() ? -1 : 1;
        });
    }

}
