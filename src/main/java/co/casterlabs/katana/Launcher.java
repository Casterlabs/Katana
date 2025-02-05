package co.casterlabs.katana;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import xyz.e3ndr.fastloggingframework.FastLoggingFramework;
import xyz.e3ndr.fastloggingframework.loggerimpl.FileLogHandler;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

@Getter
@Command(name = "start", mixinStandardHelpOptions = true, version = "Katana v" + Katana.VERSION, description = "Starts the Katana server")
public class Launcher implements Runnable {

    @Option(names = {
            "-t",
            "--trace"
    }, description = "Forcefully enables trace logging across all of the servers.")
    private boolean trace = false;

    public static void main(String[] args) {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true); // Enable assertions
        new CommandLine(new Launcher()).execute(args);
    }

    @SneakyThrows
    @Override
    public void run() {
        new FileLogHandler(new File("latest.log"));

        System.setProperty("fastloggingframework.wrapsystem", "true");
        FastLoggingFramework.setColorEnabled(true);

        if (this.trace) {
            FastLoggingFramework.setDefaultLevel(LogLevel.ALL);
            new FastLogger().debug("Trace mode enabled.");
        }

        Katana katana = new Katana(this);

        this.loadConfig(katana);

        katana.start();

        Thread.ofPlatform()
            .name("Katana JVM Keep-Alive")
            .start(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {}
            });
    }

    @SneakyThrows
    public void loadConfig(Katana katana) {
        JsonArray json;

        if (Katana.CONFIG_FILE.exists()) {
            json = Util.readFileAsJson(Katana.CONFIG_FILE, JsonArray.class);
        } else {
            // Auto populate a default.
            json = JsonArray.of(
                new JsonObject()
                    .put("type", "http")
                    .put(
                        "servlets",
                        JsonArray.of(
                            new JsonObject()
                                .put("type", "static")
                                .put("hostnames", JsonArray.of("*"))
                                .put("directory", "./www")
                        )
                    ),
                JsonObject.singleton("type", "ui")
            );
        }

        String newConfigJson = katana.init(json);
        Files.write(Katana.CONFIG_FILE.toPath(), newConfigJson.getBytes(StandardCharsets.UTF_8));

        Katana.getInstance().getLogger().info("Updated config.");
    }

}
