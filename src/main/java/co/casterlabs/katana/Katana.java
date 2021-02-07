package co.casterlabs.katana;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import co.casterlabs.katana.config.ServerConfiguration;
import co.casterlabs.katana.http.HttpServer;
import co.casterlabs.katana.server.Server;
import co.casterlabs.katana.server.Servlet;
import lombok.Getter;
import xyz.e3ndr.consolidate.CommandRegistry;
import xyz.e3ndr.consolidate.exception.ArgumentsLengthException;
import xyz.e3ndr.consolidate.exception.CommandExecutionException;
import xyz.e3ndr.consolidate.exception.CommandNameException;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class Katana {
    public static final String ERROR_HTML = "<!DOCTYPE html><html><head><title>$RESPONSECODE</title></head><body><h1>$RESPONSECODE</h1><p>$DESCRIPTION</p><br/><p><i>Running Casterlabs Katana, $ADDRESS</i></p></body></html>";
    public static final String VERSION = "1.13.6";
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Map<String, Class<? extends Servlet>> servlets = new HashMap<>();
    private CommandRegistry<Void> commandRegistry = new CommandRegistry<>();
    private Map<String, Server> servers = new HashMap<>();
    private FastLogger logger = new FastLogger();
    private Launcher launcher;

    public Katana(Launcher launcher) {
        this.launcher = launcher;
        this.commandRegistry.addCommand(new KatanaCommands(this.commandRegistry, this));

        (new Thread() {
            @SuppressWarnings("resource")
            @Override
            public void run() {
                Scanner in = new Scanner(System.in);

                while (true) {
                    try {
                        commandRegistry.execute(in.nextLine());
                    } catch (CommandNameException | CommandExecutionException | ArgumentsLengthException e) {
                        e.printStackTrace();
                        logger.exception(e);
                    }
                }
            }
        }).start();
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
        if (this.servers.containsKey(config.getName())) {
            this.servers.get(config.getName()).loadConfig(config);
        } else {
            this.servers.put(config.getName(), new HttpServer(config, this));
        }
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
        for (Server server : this.servers.values()) {
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
        for (Server server : this.servers.values()) {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    public boolean isRunning() {
        for (Server server : this.servers.values()) {
            if (server.isRunning()) {
                return true;
            }
        }

        return false;
    }

}
