package libs.scheduling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TaskScheduler {
    private static final Logger LOG = Logger.getLogger(TaskScheduler.class.getName());

    private static final long STOP_TIMEOUT_SECONDS = 5;

    private final Duration interval;
    private final Runnable task;
    private ScheduledExecutorService executor;

    public TaskScheduler(Duration interval, Runnable task) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(task, "task");
        if (interval.compareTo(Duration.ofMillis(1)) < 0
                || interval.compareTo(Duration.ofMillis(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Интервал выполнения задачи должен быть не меньше 1 миллисекунды и помещаться в long");
        }
        this.interval = interval;
        this.task = task;
    }

    public synchronized void start() {
        if (executor != null) {
            throw new IllegalStateException("Планировщик уже запущен");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "task-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::runSafely, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    private void runSafely() {
        try {
            task.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Ошибка фоновой задачи; повтор при следующем проходе", e);
        }
    }
}
