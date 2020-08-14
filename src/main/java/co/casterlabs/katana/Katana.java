package co.casterlabs.katana;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.katana.http.HttpServer;
import co.casterlabs.katana.server.Server;
import co.casterlabs.katana.server.Servlet;
import lombok.Getter;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class Katana {
    public static final String ERROR_HTML = "<!DOCTYPE html><html><head><title>$RESPONSECODE</title></head><body><h1>$RESPONSECODE</h1><p>$DESCRIPTION</p><br/><p><i>Running Casterlabs Katana, $ADDRESS</i></p></body></html>";
    public static final String VERSION = "1.1.1";
    public static final Gson GSON = new Gson();

    private Map<String, Class<? extends Servlet>> servlets = new HashMap<>();
    private List<Server> servers = new ArrayList<>();
    private FastLogger logger = new FastLogger();

    public void init(ServerConfiguration... configurations) {
        for (ServerConfiguration config : configurations) {
            try {
                this.addConfiguration(config);
            } catch (Exception e) {
                this.logger.severe("Config generated an exception:");
                e.printStackTrace();
            }
        }
    }

    public void init(JsonArray configurations) {
        for (JsonElement element : configurations) {
            try {
                ServerConfiguration config = new ServerConfiguration(element.getAsJsonObject(), this);

                this.addConfiguration(config);
            } catch (Exception e) {
                this.logger.severe("Config generated an exception:");
                e.printStackTrace();
            }
        }
    }

    public void addConfiguration(ServerConfiguration config) throws Exception {
        this.servers.add(new HttpServer(config, this));
    }

    public void addServlet(String type, Class<? extends Servlet> servlet) {
        this.servlets.put(type.toUpperCase(), servlet);
    }

    public Servlet getServlet(String type) {
        Class<? extends Servlet> servlet = this.servlets.get(type.toUpperCase());

        if (servlet == null) {
            throw new IllegalArgumentException("Servlet does not exist");
        }

        try {
            return servlet.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot instantiate servlet", e);
        }
    }

    public void start() {
        for (Server server : this.servers) {
            if (!server.isRunning()) {
                server.start();

                List<Reason> reasons = server.getFailReasons();

                if (reasons.size() != 0) {
                    this.logger.severe("Server %s failed to start for the following reason(s)", server.getConfig().getName());

                    for (Reason reason : reasons) {
                        reason.print(this.logger);
                    }

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {}
                }
            }
        }
    }

    public void stop() {
        for (Server server : this.servers) {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    public boolean isRunning() {
        for (Server server : this.servers) {
            if (server.isRunning()) {
                return true;
            }
        }

        return false;
    }

}
