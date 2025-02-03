package co.casterlabs.katana.router;

public interface KatanaRouter<T extends KatanaRouterConfiguration> {

    public void loadConfig(T config);

    public T getConfig();

    public boolean isRunning();

    public void start();

    public void stop(boolean disconnectClients);

}
