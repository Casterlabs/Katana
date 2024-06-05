package co.casterlabs.katana;

import java.io.Closeable;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import co.casterlabs.commons.async.AsyncTask;
import lombok.RequiredArgsConstructor;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

//Modified from: https://stackoverflow.com/questions/16251273/can-i-watch-for-single-file-change-with-watchservice-not-the-whole-directory
@RequiredArgsConstructor
public abstract class FileWatcher implements Closeable {
    private final File file;
    private boolean shouldWatch;

    public abstract void onChange();

    @SuppressWarnings("unchecked")
    private final void run() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Path path = this.file.getCanonicalFile().getParentFile().toPath();
            path.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);

            while (this.shouldWatch) {
                WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);

                if (key == null) {
                    Thread.yield();
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    WatchEvent.Kind<?> kind = event.kind();
                    Path filename = ev.context();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        Thread.yield();
                        continue;
                    } else if (kind == java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
                        && filename.toString().equals(this.file.getName())) {
                            this.onChange();
                        }
                    boolean valid = key.reset();
                    if (!valid) {
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            FastLogger.logException(e);
        }
    }

    @Override
    public final int hashCode() {
        return this.file.hashCode();
    }

    public final void start() {
        if (this.shouldWatch) return;
        this.shouldWatch = true;
        AsyncTask.create(this::run);
    }

    @Override
    public final void close() {
        this.shouldWatch = false;
    }

    public static abstract class MultiFileWatcher implements Closeable {
        private final Map<FileWatcher, Boolean> sub = new HashMap<>();

        public MultiFileWatcher(File... files) {
            for (File file : files) {
                this.sub.put(
                    new FileWatcher(file) {
                        @Override
                        public void onChange() {
                            sub.put(this, true);
                            checkAllForChange();
                        }
                    },
                    false
                );
            }
        }

        private final void checkAllForChange() {
            for (boolean b : this.sub.values()) {
                if (!b) return; // Hasn't changed yet.
            }

            this.sub.keySet().forEach((w) -> this.sub.put(w, false)); // Update all to false.
            this.onChange();
        }

        public abstract void onChange();

        public final void start() {
            this.sub.keySet().forEach((w) -> w.start());
        }

        @Override
        public final void close() {
            this.sub.keySet().forEach((w) -> w.close());
            this.sub.keySet().forEach((w) -> this.sub.put(w, false)); // Update all to false.
        }

    }

}
