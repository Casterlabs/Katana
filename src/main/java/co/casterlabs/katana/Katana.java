package co.casterlabs.katana;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import co.casterlabs.commons.async.AsyncTask;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.katana.router.KatanaRouterConfiguration.RouterType;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.HttpRouterConfiguration;
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
    public static final String VERSION = "1.24.1";
    public static final String SERVER_DECLARATION = String.format("Katana/%s (%s)", Katana.VERSION, System.getProperty("os.name", "Generic"));

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
                } catch (Exception ignored) {}
            }
        });
    }

    public String init(JsonArray configurations) {
        JsonArray updatedResult = new JsonArray(); // We want to populate defaults by reserializing the classes.

        for (JsonElement element : configurations) {
            JsonObject configElement = element.getAsObject();

            RouterType type = RouterType.valueOf(
                configElement
                    .getString("type")
                    .toUpperCase()
            );

            switch (type) {
                case HTTP: {
                    try {
                        HttpRouterConfiguration config = Rson.DEFAULT.fromJson(configElement, HttpRouterConfiguration.class);
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

    public void addHttpConfiguration(KatanaRouterConfiguration config) throws Exception {
        switch (config.getType()) {
            case HTTP:
                if (this.routers.containsKey(config.getName())) {
                    ((HttpRouter) this.routers.get(config.getName()))
                        .loadConfig((HttpRouterConfiguration) config);
                } else {
                    this.routers.put(config.getName(), new HttpRouter((HttpRouterConfiguration) config, this));
                }
                break;

            // TODO others ;)
        }
    }

    public void start() {
        for (KatanaRouter<?> server : this.routers.values()) {
            if (server.isRunning()) continue;

            server.start();
        }
    }

    public void stop() {
        for (KatanaRouter<?> server : this.routers.values()) {
            if (!server.isRunning()) continue;

            server.stop();
        }
    }

}
