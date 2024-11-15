package co.casterlabs.katana.router.http;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import co.casterlabs.rhs.util.TaskExecutor;

public class RakuraiTaskExecutor implements TaskExecutor {
    public static final RakuraiTaskExecutor INSTANCE = new RakuraiTaskExecutor();

    private static final ExecutorService LIGHT_IO_EXEC = Executors.newFixedThreadPool(10000, (run) -> {
        return Thread.ofVirtual()
            .name("RHS - Light IO (V)", 0)
            .unstarted(run);
    });

    private static final ExecutorService MEDIUM_IO_EXEC = Executors.newCachedThreadPool((run) -> {
        return Thread.ofVirtual()
            .name("RHS - Medium IO (V)", 0)
            .unstarted(run);
    });

    private static final ExecutorService HEAVY_IO_EXEC = Executors.newCachedThreadPool((run) -> {
        return Thread.ofPlatform()
            .name("RHS - Heavy IO (P)", 0)
            .unstarted(run);
    });

    @Override
    public Task execute(Runnable toRun, TaskType type) {
        ExecutorService exec = null;
        switch (type) {
            case LIGHT_IO:
                exec = LIGHT_IO_EXEC;
                break;
            case MEDIUM_IO:
                exec = MEDIUM_IO_EXEC;
                break;
            case HEAVY_IO:
                exec = HEAVY_IO_EXEC;
                break;
        }

        Future<?> future = exec.submit(() -> {
            try {
                toRun.run();
            } finally {
                Thread.interrupted(); // Clear the interrupt flag.
            }
        });

        return new Task() {
            @Override
            public void interrupt() {
                future.cancel(true);
            }

            @Override
            public void waitFor() throws InterruptedException {
                try {
                    future.get();
                } catch (ExecutionException ignored) {}
            }

            @Override
            public boolean isAlive() {
                return !future.isCancelled() && !future.isDone();
            }
        };
    }

}
