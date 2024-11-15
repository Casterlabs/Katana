package co.casterlabs.katana.router;

public interface KatanaRouter<T extends KatanaRouterConfiguration> {

    public void loadConfig(T config);

    public boolean isRunning();

    public void start();

    public void stop(boolean disconnectClients);

}
