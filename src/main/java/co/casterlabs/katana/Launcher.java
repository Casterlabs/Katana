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
            "-c",
            "--config"
    }, description = "The config file to use.")
    private File file = new File("config.json");

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
    }

    @SneakyThrows
    public void loadConfig(Katana katana) {
        JsonArray json;

        if (this.file.exists()) {
            json = Util.readFileAsJson(this.file, JsonArray.class);
        } else {
            // Auto populate a default.
            json = JsonArray.of(
                new JsonObject()
                    .put("type", "http")
                    .put("logs_dir", "./logs")
                    .put(
                        "servlets",
                        JsonArray.of(
                            new JsonObject()
                                .put("type", "static")
                                .put("hostnames", JsonArray.of("*"))
                                .put("directory", "./www")
                        )
                    )
            );
        }

        String newConfigJson = katana.init(json);
        Files.write(this.file.toPath(), newConfigJson.getBytes(StandardCharsets.UTF_8));

        Katana.getInstance().getLogger().info("Updated config.");
    }

}
