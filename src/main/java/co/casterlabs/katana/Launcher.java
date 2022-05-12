package co.casterlabs.katana;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.JsonArray;

import co.casterlabs.katana.http.servlets.FileServlet;
import co.casterlabs.katana.http.servlets.ProxyServlet;
import co.casterlabs.katana.http.servlets.RedirectServlet;
import co.casterlabs.katana.http.servlets.StaticServlet;
import co.casterlabs.katana.http.servlets.WebSocketProxyServlet;
import co.casterlabs.rakurai.io.http.server.HttpServerImplementation;
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
    }, description = "The config file to use")
    private File file = new File("config.json");

    @Option(names = {
            "-d",
            "--debug"
    }, description = "Enables debug logging")
    private boolean debug = false;

    @Getter
    @Option(names = {
            "-s",
            "--server-implementation"
    }, description = "Sets the desired server implementation")
    private HttpServerImplementation implementation = HttpServerImplementation.UNDERTOW;

    public static void main(String[] args) {
        new CommandLine(new Launcher()).execute(args);
    }

    @SneakyThrows
    @Override
    public void run() {
        if (this.debug) {
            FastLoggingFramework.setDefaultLevel(LogLevel.ALL);
            new FastLogger().debug("Debug mode enabled.");
        }

        Katana katana = new Katana(this);

        katana.addServlet("STATIC", StaticServlet.class);
        katana.addServlet("PROXY", ProxyServlet.class);
        katana.addServlet("WEBSOCKETPROXY", WebSocketProxyServlet.class);
        katana.addServlet("REDIRECT", RedirectServlet.class);
        katana.addServlet("FILE", FileServlet.class);

        this.loadConfig(katana);

        katana.start();
    }

    @SneakyThrows
    public void loadConfig(Katana katana) {
        JsonArray json;

        if (!this.file.exists()) {
            InputStream in = this.getClass().getResourceAsStream("/resources/config.json");
            byte[] bytes = new byte[in.available()];

            in.read(bytes);

            Files.write(this.file.toPath(), bytes);

            json = Katana.GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonArray.class);
        } else {
            json = Util.readFileAsJson(this.file, JsonArray.class);
        }

        katana.init(json);
    }

}
