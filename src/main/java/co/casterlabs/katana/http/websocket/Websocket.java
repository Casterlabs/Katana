package co.casterlabs.katana.http.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import lombok.NonNull;

public abstract class Websocket {
    private Object attachment;

    public void setAttachment(Object attachment) {
        this.attachment = attachment;
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttachment() {
        return (T) this.attachment;
    }

    /**
     * Sends a text payload to the receiving end.
     *
     * @param message the message
     */
    public abstract void send(@NonNull String message) throws IOException;

    /**
     * Sends a byte payload to the receiving end.
     *
     * @param bytes the bytes
     */
    public abstract void send(@NonNull byte[] bytes) throws IOException;

    /**
     * Closes the connection.
     */
    public abstract void close(@NonNull WebsocketCloseCode code) throws IOException;

    // Request headers
    public abstract @NonNull Map<String, String> getHeaders();

    public @Nullable String getHeader(@NonNull String header) {
        return this.getHeaders().get(header);
    }

    // URI
    public abstract String getUri();

    public abstract @NonNull Map<String, List<String>> getAllQueryParameters();

    public abstract @NonNull Map<String, String> getQueryParameters();

    public abstract @NonNull String getQueryString();

    // Misc
    public abstract @NonNull String getRemoteIpAddress();

}
