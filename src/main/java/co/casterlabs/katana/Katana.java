package co.casterlabs.katana;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

import co.casterlabs.commons.async.AsyncTask;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.katana.router.KatanaRouterConfiguration.RouterType;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.HttpRouterConfiguration;
import co.casterlabs.katana.router.ui.UIRouter;
import co.casterlabs.katana.router.ui.UIRouterConfiguration;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rakurai.json.validation.JsonValidationException;
import lombok.Getter;
import xyz.e3ndr.consolidate.CommandRegistry;
import xyz.e3ndr.consolidate.exception.ArgumentsLengthException;
import xyz.e3ndr.consolidate.exception.CommandExecutionException;
import xyz.e3ndr.consolidate.exception.CommandNameException;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@Getter
public class Katana {
    public static final String VERSION = "1.29.0";
    public static final String SERVER_DECLARATION = String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic"));

    public static final File CONFIG_FILE = new File("config.json");

    private CommandRegistry<Void> commandRegistry = new CommandRegistry<>();

    private Map<String, KatanaRouter<?>> routers = new HashMap<>();
    private FastLogger logger = new FastLogger();
    private Launcher launcher;

    private static @Getter Katana instance;

    public Katana(Launcher launcher) {
        instance = this;
        this.launcher = launcher;

        this.commandRegistry.addCommand(new KatanaCommands(this.commandRegistry, this));

        AsyncTask.create(() -> {
            @SuppressWarnings("resource")
            Scanner in = new Scanner(System.in);

            while (true) {
                try {
                    commandRegistry.execute(in.nextLine());
                } catch (CommandNameException | CommandExecutionException | ArgumentsLengthException e) {
                    logger.exception(e);
                }
            }
        });
    }

    public String init(JsonArray configurations) {
        JsonArray updatedResult = new JsonArray(); // We want to populate defaults by reserializing the classes.
        List<String> validNames = new LinkedList<>();

        for (JsonElement element : configurations) {
            JsonObject configElement = element.getAsObject();

            try {
                KatanaRouterConfiguration config = deserialize(configElement);
                validNames.add(config.getName());
                this.addConfiguration(config);
                updatedResult.add(Rson.DEFAULT.toJson(config));
            } catch (Exception e) {
                updatedResult.add(element); // Add back the raw json, let the user fix it.
                this.logger.severe("An exception occurred whilst loading config:\n%s", e);
            }
        }

        // We need to shut down removed servers
        List<String> removedRouterNames = this.routers.keySet().stream().filter((name) -> !validNames.contains(name)).collect(Collectors.toList());
        for (String name : removedRouterNames) {
            KatanaRouter<?> router = this.routers.remove(name);
            if (router.isRunning()) {
                try {
                    router.stop(true);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        return updatedResult.toString(true);
    }

    public void addConfiguration(KatanaRouterConfiguration config) throws Exception {
        switch (config.getType()) {
            case HTTP:
                if (this.routers.containsKey(config.getName())) {
                    ((HttpRouter) this.routers.get(config.getName()))
                        .loadConfig((HttpRouterConfiguration) config);
                } else {
                    this.routers.put(config.getName(), new HttpRouter((HttpRouterConfiguration) config, this));
                }
                break;

            case UI:
                if (this.routers.containsKey(config.getName())) {
                    ((UIRouter) this.routers.get(config.getName()))
                        .loadConfig((UIRouterConfiguration) config);
                } else {
                    this.routers.put(config.getName(), new UIRouter((UIRouterConfiguration) config, this));
                }
                break;
        }
    }

    public void start() {
        for (KatanaRouter<?> server : this.routers.values()) {
            if (server.isRunning()) continue;
            server.start();
        }
    }

    public void stop(boolean disconnectClients) {
        for (KatanaRouter<?> server : this.routers.values()) {
            if (!server.isRunning()) continue;
            server.stop(disconnectClients);
        }
    }

    public static KatanaRouterConfiguration deserialize(JsonObject routerConfig) throws JsonValidationException, JsonParseException {
        RouterType type = RouterType.valueOf(
            routerConfig
                .getString("type")
                .toUpperCase()
        );

        switch (type) {
            case HTTP:
                return Rson.DEFAULT.fromJson(routerConfig, HttpRouterConfiguration.class);

            case UI:
                return Rson.DEFAULT.fromJson(routerConfig, UIRouterConfiguration.class);

            default:
                throw new IllegalArgumentException("Unknown router type: " + type);
        }
    }

}
