package co.casterlabs.katana;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import co.casterlabs.katana.config.HttpServerConfiguration;
import co.casterlabs.katana.http.HttpRouter;
import co.casterlabs.katana.http.servlets.HttpServlet;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import lombok.Getter;
import xyz.e3ndr.consolidate.CommandRegistry;
import xyz.e3ndr.consolidate.exception.ArgumentsLengthException;
import xyz.e3ndr.consolidate.exception.CommandExecutionException;
import xyz.e3ndr.consolidate.exception.CommandNameException;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class Katana {
    public static final String ERROR_HTML = "<!DOCTYPE html><html><head><title>$RESPONSECODE</title></head><body><h1>$RESPONSECODE</h1><p>$DESCRIPTION</p><br/><p><i>Running Casterlabs Katana, $ADDRESS</i></p></body></html>";
    public static final String VERSION = "1.20.1";
    public static final String SERVER_DECLARATION = String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic"));

    private Map<String, Class<? extends HttpServlet>> servlets = new HashMap<>();
    private CommandRegistry<Void> commandRegistry = new CommandRegistry<>();
    private FastLogger logger = new FastLogger();

    private Map<String, HttpRouter> routers = new HashMap<>();
    private Launcher launcher;

    private static @Getter Katana instance;

    public Katana(Launcher launcher) {
        instance = this;
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
                        logger.exception(e);
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    public String init(JsonArray configurations) {
        JsonArray updatedResult = new JsonArray(); // We want to populate defaults by reserializing the classes.

        for (JsonElement element : configurations) {
            JsonObject configElement = element.getAsObject();

            String type = "http"; // default.
            if (configElement.containsKey("type")) {
                type = configElement.getString("type").toLowerCase();
            }

            switch (type) {
                case HttpServerConfiguration.TYPE: {
                    try {
                        HttpServerConfiguration config = Rson.DEFAULT.fromJson(configElement, HttpServerConfiguration.class);
                        updatedResult.add(Rson.DEFAULT.toJson(config));

                        this.addHttpConfiguration(config);
                    } catch (Exception e) {
                        updatedResult.add(element); // Add back the raw json, let the user fix it.
                        this.logger.severe("An exception occurred whilst loading config:\n%s", e);
                    }
                    break;
                }

                // TODO others ;)
            }
        }

        return updatedResult.toString(true);
    }

    public void addHttpConfiguration(HttpServerConfiguration config) throws Exception {
        if (this.routers.containsKey(config.getName())) {
            this.routers.get(config.getName()).loadConfig(config);
        } else {
            this.routers.put(config.getName(), new HttpRouter(config, this));
        }
    }

    public void addHttpServlet(String type, Class<? extends HttpServlet> servlet) {
        this.servlets.put(type.toUpperCase(), servlet);
    }

    @SuppressWarnings("deprecation")
    public HttpServlet getHttpServlet(String type) {
        Class<? extends HttpServlet> servlet = this.servlets.get(type.toUpperCase());

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
        for (HttpRouter server : this.routers.values()) {
            if (server.isRunning()) continue;

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

    public void stop() {
        for (HttpRouter server : this.routers.values()) {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    public boolean isRunning() {
        for (HttpRouter server : this.routers.values()) {
            if (server.isRunning()) {
                return true;
            }
        }

        return false;
    }

}
