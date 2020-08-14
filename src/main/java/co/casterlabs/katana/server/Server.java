package co.casterlabs.katana.server;

import java.util.List;

import co.casterlabs.katana.Reason;
import co.casterlabs.katana.config.ServerConfiguration;

public interface Server {

    public ServerConfiguration getConfig();

    public List<Reason> getFailReasons();

    public boolean isRunning();

    public int[] getPorts();

    public void start();

    public void stop();

}
