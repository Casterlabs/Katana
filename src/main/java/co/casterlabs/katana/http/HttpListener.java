package co.casterlabs.katana.http;

import java.io.IOException;

public interface HttpListener {

    public void start() throws IOException;

    public void stop() throws IOException;

    public int getPort();

    public boolean isAlive();

}
