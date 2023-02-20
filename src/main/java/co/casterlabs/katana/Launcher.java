package co.casterlabs.katana;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import co.casterlabs.katana.http.servlets.FileServlet;
import co.casterlabs.katana.http.servlets.ProxyServlet;
import co.casterlabs.katana.http.servlets.RedirectServlet;
import co.casterlabs.katana.http.servlets.StaticServlet;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonObject;
import lombok.Getter;
import lombok.SneakyThrows;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import xyz.e3ndr.fastloggingframework.FastLoggingFramework;
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
            "-trace",
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
        if (this.trace) {
            FastLoggingFramework.setDefaultLevel(LogLevel.ALL);
            new FastLogger().debug("Trace mode enabled.");
        }

        Katana katana = new Katana(this);

        katana.addHttpServlet("STATIC", StaticServlet.class);
        katana.addHttpServlet("PROXY", ProxyServlet.class);
        katana.addHttpServlet("REDIRECT", RedirectServlet.class);
        katana.addHttpServlet("FILE", FileServlet.class);

        this.loadConfig(katana);

        katana.start();
    }

    @SneakyThrows
    public void loadConfig(Katana katana) {
        JsonArray json;

        if (this.file.exists()) {
            json = Util.readFileAsJson(this.file, JsonArray.class);
        } else {
            json = JsonArray.of(JsonObject.singleton("type", "http")); // Populate a default.
        }

        String newConfigJson = katana.init(json);
        Files.write(this.file.toPath(), newConfigJson.getBytes(StandardCharsets.UTF_8));
    }

}
