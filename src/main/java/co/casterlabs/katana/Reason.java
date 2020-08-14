package co.casterlabs.katana;

import org.jetbrains.annotations.Nullable;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

@AllArgsConstructor
public class Reason {
    private @NonNull String reason;
    private @Nullable Exception ex;

    public void print(FastLogger logger) {
        logger.severe(this.reason);

        this.ex.printStackTrace();
    }

}
