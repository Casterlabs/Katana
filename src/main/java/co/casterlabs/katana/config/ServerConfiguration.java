package co.casterlabs.katana.config;

import java.util.ArrayList;
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
    private List<Servlet> servlets = new ArrayList<>();
    private SSLConfiguration SSL = new SSLConfiguration();
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

                if (config.has("hostname")) {
                    servlet.getHosts().add(config.get("hostname").getAsString());
                }

                if (config.has("hostnames")) {
                    for (JsonElement hostname : config.getAsJsonArray("hostnames")) {
                        servlet.getHosts().add(hostname.getAsString());
                    }
                }

                this.servlets.add(servlet);
            }
        }
    }

}
