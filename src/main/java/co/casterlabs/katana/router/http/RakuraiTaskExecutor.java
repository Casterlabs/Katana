package co.casterlabs.katana.router.http;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import co.casterlabs.rhs.util.TaskExecutor;

public class RakuraiTaskExecutor implements TaskExecutor {
    public static final RakuraiTaskExecutor INSTANCE = new RakuraiTaskExecutor();

    public static final ExecutorService PLATFORM_POOL = Executors.newCachedThreadPool((run) -> {
        return Thread.ofPlatform()
            .name("RHS - Platform Pool", 0)
            .unstarted(run);
    });

//    public static final ExecutorService VIRTUAL_POOL = Executors.newCachedThreadPool((run) -> {
//        return Thread.ofVirtual()
//            .name("RHS - Virtual Pool", 0)
//            .unstarted(run);
//    });

    @Override
    public Task execute(Runnable toRun, TaskType type) {
        ExecutorService exec = null;
        switch (type) {
            case HEAVY_IO:
                exec = PLATFORM_POOL;
                break;

            case MEDIUM_IO:
            case LIGHT_IO:
            default:
//                exec = VIRTUAL_POOL;
                exec = PLATFORM_POOL;
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
