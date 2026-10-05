package emu.grasscutter.server.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
public final class ServerTaskSchedulerTest {
    private static final long TIMEOUT_MS = 3000;

    @Test
    public void asyncOnlyTasksStartOnceAndAreReleased() throws InterruptedException {
        var scheduler = new ServerTaskScheduler();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        int id =
                scheduler.scheduleAsyncTask(
                        () -> {
                            calls.incrementAndGet();
                            started.countDown();
                            await(release);
                        });
        var task = scheduler.getAsyncTask(id);
        try {
            scheduler.runTasks();
            assertTrue(
                    started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "async-only task did not start");
            for (int i = 0; i < 20; i++) scheduler.runTasks();
            assertEquals(1, calls.get(), "scheduler started a task more than once");
        } finally {
            release.countDown();
        }
        awaitFinished(task);
        scheduler.runTasks();
        assertNull(scheduler.getAsyncTask(id), "finished task was not released");
        assertEquals(1, calls.get(), "async task executed more than once");
    }

    @Test
    public void runtimeExceptionStillFinishesAndReleasesTask() throws InterruptedException {
        assertFailedTaskIsReleased(new IllegalStateException("expected test exception"));
    }

    @Test
    public void errorStillFinishesAndReleasesTask() throws InterruptedException {
        assertFailedTaskIsReleased(new AssertionError("expected test error"));
    }

    @Test
    public void consecutiveRunsExecuteOnce() {
        var calls = new AtomicInteger();
        var task = new AsyncServerTask(calls::incrementAndGet, 0);

        task.run();
        task.run();

        assertEquals(1, calls.get(), "consecutive task runs executed more than once");
        assertTrue(task.hasStarted() && task.isFinished(), "successful task state is incomplete");
    }

    @Test
    public void synchronousTaskRunsOnNextTickAndIsReleased() {
        var scheduler = new ServerTaskScheduler();
        var calls = new AtomicInteger();
        int id = scheduler.scheduleTask(calls::incrementAndGet);

        assertEquals(0, calls.get());
        scheduler.runTasks();
        assertEquals(1, calls.get());
        assertNull(scheduler.getTask(id));
        scheduler.runTasks();
        assertEquals(1, calls.get());
    }

    @Test
    public void delayedSynchronousTaskWaitsForItsTick() {
        var scheduler = new ServerTaskScheduler();
        var calls = new AtomicInteger();
        int id = scheduler.scheduleDelayedTask(calls::incrementAndGet, 3);

        scheduler.runTasks();
        scheduler.runTasks();
        assertEquals(0, calls.get());
        assertNotNull(scheduler.getTask(id));
        scheduler.runTasks();
        assertEquals(1, calls.get());
        assertNull(scheduler.getTask(id));
        scheduler.runTasks();
        assertEquals(1, calls.get());
    }

    @Test
    public void repeatingSynchronousTaskKeepsItsPeriodUntilCancelled() {
        var scheduler = new ServerTaskScheduler();
        var calls = new AtomicInteger();
        int id = scheduler.scheduleRepeatingTask(calls::incrementAndGet, 2);

        scheduler.runTasks();
        assertEquals(1, calls.get(), "the zero-delay task runs on its first tick");
        scheduler.runTasks();
        assertEquals(2, calls.get());
        scheduler.runTasks();
        assertEquals(2, calls.get());
        scheduler.runTasks();
        assertEquals(3, calls.get());
        assertNotNull(scheduler.getTask(id));
        scheduler.cancelTask(id);
        scheduler.runTasks();
        assertNull(scheduler.getTask(id));
        assertEquals(3, calls.get());
    }

    @Test
    public void asyncCallbackRunsOnlyWhenCompletionIsRequested() {
        var calls = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var task =
                new AsyncServerTask(
                        calls::incrementAndGet,
                        () -> {
                            assertEquals(1, calls.get(), "callback observes the completed task");
                            callbacks.incrementAndGet();
                        },
                        0);

        task.run();
        assertTrue(task.isFinished());
        assertEquals(0, callbacks.get(), "the worker does not run the completion callback");
        task.complete();
        assertEquals(1, callbacks.get());
    }

    @Test
    public void concurrentSyncAndAsyncSchedulingAssignsUniqueIds() throws InterruptedException {
        var scheduler = new ServerTaskScheduler();
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        var duplicateIds = new AtomicInteger();
        var failures = new AtomicReference<Throwable>();
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        Thread[] workers = new Thread[12];
        for (int i = 0; i < workers.length; i++) {
            workers[i] =
                    new Thread(
                            () -> {
                                ready.countDown();
                                try {
                                    await(start);
                                    for (int taskIndex = 0; taskIndex < 100; taskIndex++) {
                                        if (!ids.add(scheduler.scheduleTask(() -> {}))) {
                                            duplicateIds.incrementAndGet();
                                        }
                                        if (!ids.add(scheduler.scheduleAsyncTask(() -> {}))) {
                                            duplicateIds.incrementAndGet();
                                        }
                                    }
                                } catch (Throwable failure) {
                                    failures.compareAndSet(null, failure);
                                }
                            },
                            "scheduler-id-test-" + i);
            workers[i].start();
        }
        try {
            assertTrue(
                    ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "scheduling workers did not start");
        } finally {
            start.countDown();
            for (Thread worker : workers) join(worker);
        }
        assertNull(failures.get(), "scheduling worker failed");
        assertEquals(0, duplicateIds.get(), "sync and async tasks share one unique ID sequence");
        assertEquals(workers.length * 100 * 2, ids.size());
        for (int id : ids) {
            assertTrue(
                    (scheduler.getTask(id) != null) ^ (scheduler.getAsyncTask(id) != null),
                    "every ID must identify exactly one scheduled task");
        }
    }

    @Test
    public void concurrentRunsExecuteOnce() throws InterruptedException {
        var calls = new AtomicInteger();
        var failures = new AtomicReference<Throwable>();
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        var task = new AsyncServerTask(calls::incrementAndGet, 0);
        Thread[] workers = new Thread[12];
        for (int i = 0; i < workers.length; i++) {
            workers[i] =
                    new Thread(
                            () -> {
                                ready.countDown();
                                try {
                                    await(start);
                                    task.run();
                                } catch (Throwable failure) {
                                    failures.compareAndSet(null, failure);
                                }
                            },
                            "scheduler-concurrent-test-" + i);
            workers[i].start();
        }
        try {
            assertTrue(
                    ready.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "concurrent workers did not start");
        } finally {
            start.countDown();
            for (Thread worker : workers) join(worker);
        }
        assertNull(failures.get(), "concurrent worker failed");
        assertEquals(1, calls.get(), "concurrent task runs executed more than once");
        assertTrue(task.isFinished(), "concurrent task did not finish");
    }

    private static void assertFailedTaskIsReleased(Throwable expected) throws InterruptedException {
        var scheduler = new ServerTaskScheduler();
        var caught = new AtomicReference<Throwable>();
        int id =
                scheduler.scheduleAsyncTask(
                        () -> {
                            if (expected instanceof Error error) throw error;
                            throw (RuntimeException) expected;
                        });
        var task = scheduler.getAsyncTask(id);
        // Capture the actual mapped task's failure without changing a global thread handler.
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                task.run();
                            } catch (Throwable failure) {
                                caught.set(failure);
                            }
                        },
                        "scheduler-failure-test");
        worker.start();
        join(worker);
        assertSame(expected, caught.get(), "task failure was swallowed or replaced");
        assertTrue(task.hasStarted() && task.isFinished(), "failed task did not finish in finally");
        scheduler.runTasks();
        assertNull(scheduler.getAsyncTask(id), "failed task was not released");
    }

    private static void awaitFinished(AsyncServerTask task) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (!task.isFinished() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(task.isFinished(), "async task did not finish");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "worker wait timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("worker interrupted", interrupted);
        }
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(TIMEOUT_MS);
        if (worker.isAlive()) worker.interrupt();
        assertTrue(!worker.isAlive(), "worker did not terminate: " + worker.getName());
    }
}
