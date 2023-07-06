package co.casterlabs.katana.router;

import co.casterlabs.katana.config.RouterConfiguration;

public interface KatanaRouter<T extends RouterConfiguration> {

    public void loadConfig(T config);

    public boolean isRunning();

    public void start();

    public void stop();

}
