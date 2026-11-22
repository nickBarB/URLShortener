package libs.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskSchedulerTest {

    private static final Duration FAST = Duration.ofMillis(20);

    @Test
    void runsTaskRepeatedly() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(3);
        TaskScheduler scheduler = new TaskScheduler(FAST, latch::countDown);

        scheduler.start();
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void keepsRunningAfterTaskFailure() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        TaskScheduler scheduler = new TaskScheduler(FAST, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("тестовая ошибка");
            }
            latch.countDown();
        });

        scheduler.start();
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void stopPreventsFurtherRuns() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        TaskScheduler scheduler = new TaskScheduler(FAST, () -> {
            calls.incrementAndGet();
            started.countDown();
        });

        scheduler.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        scheduler.stop();
        int afterStop = calls.get();
        Thread.sleep(100);

        assertEquals(afterStop, calls.get());
    }

}
