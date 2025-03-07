package co.casterlabs.katana;

import java.util.Collection;

import lombok.AllArgsConstructor;
import xyz.e3ndr.consolidate.CommandEvent;
import xyz.e3ndr.consolidate.CommandRegistry;
import xyz.e3ndr.consolidate.command.Command;
import xyz.e3ndr.consolidate.command.CommandListener;

@AllArgsConstructor
public class KatanaCommands implements CommandListener<Void> {
    private CommandRegistry<Void> registry;
    private Katana katana;

    @Command(name = "help", description = "Shows this page.")
    public void help(CommandEvent<Void> event) {
        Collection<Command> commands = registry.getCommands();
        StringBuilder sb = new StringBuilder();

        sb.append("All available commands:");

        for (Command c : commands) {
            sb.append("\n\t").append(c.name()).append(": ").append(c.description());
        }

        this.katana.getLogger().info(sb);
    }

    @Command(name = "reload", description = "Reloads the config, optionally terminating all connections with the \"no-preserve\" option.")
    public void reload(CommandEvent<Void> event) {
        boolean noPreserve = (event.getArgs().length > 0) && event.getArgs()[0].equalsIgnoreCase("no-preserve");
        if (noPreserve) {
            this.katana.stop(true);
        }

        this.katana.getLauncher().loadConfig(this.katana);
        this.katana.start();
        this.katana.getLogger().info("Reloaded config!");
    }

    @Command(name = "stop", description = "Stops all listening servers, optionally terminating all connections with the \"no-preserve\" option.")
    public void stop(CommandEvent<Void> event) {
        boolean noPreserve = (event.getArgs().length > 0) && event.getArgs()[0].equalsIgnoreCase("no-preserve");

        this.katana.stop(noPreserve);
    }

    @Command(name = "start", description = "Starts all registered servers.")
    public void start(CommandEvent<Void> event) {
        this.katana.start();
    }

    @Command(name = "gc", description = "Tells Java to garbage collect.")
    public void gc(CommandEvent<Void> event) {
        System.gc();
    }

}
