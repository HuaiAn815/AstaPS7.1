package emu.grasscutter.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DatabaseExecutorSupportTest {
    private static final Runnable NO_OP = () -> {};

    @Test
    void configuredLargeCapacityUsesItsOwnSeventyPercentBoundary() {
        var executor = idleExecutor(new LinkedBlockingQueue<>(10000));
        try {
            fill(executor, 7000);
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
            executor.getQueue().add(NO_OP);
            assertTrue(DatabaseExecutorSupport.isOverloaded(executor));
        } finally {
            executor.getQueue().clear();
            executor.shutdown();
        }
    }

    @Test
    void configuredSmallCapacityUsesItsOwnSeventyPercentBoundary() {
        var executor = idleExecutor(new LinkedBlockingQueue<>(10));
        try {
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
            fill(executor, 7);
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
            executor.getQueue().add(NO_OP);
            assertTrue(DatabaseExecutorSupport.isOverloaded(executor));
            fill(executor, 2);
            assertTrue(DatabaseExecutorSupport.isOverloaded(executor));
        } finally {
            executor.getQueue().clear();
            executor.shutdown();
        }
    }

    @Test
    void unboundedQueueDoesNotUseTheDatabaseDefaultCapacity() {
        var executor = idleExecutor(new LinkedBlockingQueue<>());
        try {
            fill(executor, 20000);
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
        } finally {
            executor.getQueue().clear();
            executor.shutdown();
        }
    }

    @Test
    void directHandoffQueueHasNoQueuedBacklog() {
        var executor = idleExecutor(new SynchronousQueue<>());
        try {
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void capacityArithmeticDoesNotOverflowAnInteger() {
        var queue =
                new LinkedBlockingQueue<Runnable>() {
                    @Override
                    public int size() {
                        return Integer.MAX_VALUE;
                    }

                    @Override
                    public int remainingCapacity() {
                        return Integer.MAX_VALUE;
                    }
                };
        var executor = idleExecutor(queue);
        try {
            assertFalse(DatabaseExecutorSupport.isOverloaded(executor));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void saturatedExecutorStillRunsTheTaskOnItsCaller() throws Exception {
        var executor = workerExecutor(new LinkedBlockingQueue<>(1));
        var workerStarted = new CountDownLatch(1);
        var releaseWorker = new CountDownLatch(1);
        try {
            executor.execute(
                    () -> {
                        workerStarted.countDown();
                        await(releaseWorker);
                    });
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            executor.execute(NO_OP);
            var ranOn = new AtomicReference<Thread>();

            var future = executor.submit(() -> ranOn.set(Thread.currentThread()));

            assertTrue(future.isDone());
            assertSame(Thread.currentThread(), ranOn.get());
        } finally {
            releaseWorker.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void submissionsAfterShutdownFailInsteadOfReturningAnIncompleteFuture() {
        var executor = workerExecutor(new LinkedBlockingQueue<>(1));
        executor.shutdown();

        assertThrows(RejectedExecutionException.class, () -> executor.execute(NO_OP));
        assertThrows(RejectedExecutionException.class, () -> executor.submit(NO_OP));
    }

    @Test
    void acceptedTasksDrainFromAllFourRealPools() throws Exception {
        var pools = new ArrayList<DatabaseExecutorSupport.Pool>();
        var completed = new AtomicInteger();
        for (int index = 0; index < 4; index++) {
            var executor = workerExecutor(new LinkedBlockingQueue<>(8));
            pools.add(new DatabaseExecutorSupport.Pool("POOL_" + index, executor));
            for (int task = 0; task < 8; task++) executor.submit(completed::incrementAndGet);
        }
        try {
            var result = DatabaseExecutorSupport.shutdownAndAwait(pools, 2, TimeUnit.SECONDS);

            assertTrue(result.unfinishedPools().isEmpty());
            assertEquals(32, completed.get());
            for (var pool : pools) assertTrue(pool.executor().isTerminated());
        } finally {
            for (var pool : pools) {
                pool.executor().shutdown();
                assertTrue(pool.executor().awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void crossPoolSubmissionsDuringDrainAreExplicitlyRejected() throws Exception {
        var source = workerExecutor(new LinkedBlockingQueue<>(1));
        var target = workerExecutor(new LinkedBlockingQueue<>(1));
        var started = new CountDownLatch(1);
        var rejected = new AtomicInteger();
        source.submit(
                () -> {
                    started.countDown();
                    try {
                        target.awaitTermination(2, TimeUnit.SECONDS);
                        target.submit(NO_OP);
                    } catch (RejectedExecutionException exception) {
                        rejected.incrementAndGet();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var result =
                    DatabaseExecutorSupport.shutdownAndAwait(
                            List.of(
                                    new DatabaseExecutorSupport.Pool("SOURCE", source),
                                    new DatabaseExecutorSupport.Pool("TARGET", target)),
                            2,
                            TimeUnit.SECONDS);

            assertTrue(result.unfinishedPools().isEmpty());
            assertEquals(1, rejected.get());
        } finally {
            target.shutdown();
            source.shutdown();
            assertTrue(target.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(source.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void allFourPoolsStopAcceptingBeforeAnyPoolIsAwaited() throws Exception {
        var pools = new ArrayList<DatabaseExecutorSupport.Pool>();
        var executors = new ArrayList<RecordingExecutor>();
        for (int index = 0; index < 4; index++) {
            var executor = new RecordingExecutor();
            executor.terminateOnAwait = true;
            executors.add(executor);
            pools.add(new DatabaseExecutorSupport.Pool("POOL_" + index, executor));
        }
        for (var executor : executors) executor.peers = executors;

        var result = DatabaseExecutorSupport.shutdownAndAwait(pools, 1, TimeUnit.SECONDS);

        assertFalse(result.interrupted());
        assertTrue(result.unfinishedPools().isEmpty());
        for (var executor : executors) {
            assertTrue(executor.allShutdownBeforeAwait);
            assertEquals(1, executor.awaitCalls);
            assertEquals(0, executor.shutdownNowCalls);
        }
    }

    @Test
    void fourPoolsShareOneTimeoutInsteadOfFourTimeouts() {
        var executors = new ArrayList<RecordingExecutor>();
        var pools = new ArrayList<DatabaseExecutorSupport.Pool>();
        for (int index = 0; index < 4; index++) {
            var executor = new RecordingExecutor();
            executor.consumeWaitBudget = true;
            executors.add(executor);
            pools.add(new DatabaseExecutorSupport.Pool("POOL_" + index, executor));
        }
        for (var executor : executors) executor.peers = executors;

        var result = DatabaseExecutorSupport.shutdownAndAwait(pools, 50, TimeUnit.MILLISECONDS);

        assertEquals(4, result.unfinishedPools().size());
        assertEquals(1, executors.stream().mapToInt(executor -> executor.awaitCalls).sum());
        for (var executor : executors) assertEquals(0, executor.shutdownNowCalls);
    }

    @Test
    void timeoutReportsUnfinishedTasksWithoutDiscardingTheQueue() {
        var executor = new RecordingExecutor();
        fill(executor, 2);
        executor.activeTasks = 1;
        var result =
                DatabaseExecutorSupport.shutdownAndAwait(
                        List.of(new DatabaseExecutorSupport.Pool("DATABASE_ITEM", executor)),
                        0,
                        TimeUnit.SECONDS);

        assertEquals(
                List.of(new DatabaseExecutorSupport.UnfinishedPool("DATABASE_ITEM", 1, 2, 3)),
                result.unfinishedPools());
        assertEquals(2, executor.getQueue().size());
        assertEquals(0, executor.shutdownNowCalls);
        executor.getQueue().clear();
    }

    @Test
    void interruptionStillShutsDownEveryPoolAndPreservesTheInterruptFlag() {
        var executors = List.of(new RecordingExecutor(), new RecordingExecutor());
        var pools =
                List.of(
                        new DatabaseExecutorSupport.Pool("DEFAULT", executors.get(0)),
                        new DatabaseExecutorSupport.Pool("ACCOUNT", executors.get(1)));
        Thread.currentThread().interrupt();
        try {
            var result = DatabaseExecutorSupport.shutdownAndAwait(pools, 1, TimeUnit.SECONDS);

            assertTrue(result.interrupted());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, result.unfinishedPools().size());
            for (var executor : executors) {
                assertTrue(executor.isShutdown());
                assertEquals(0, executor.shutdownNowCalls);
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void negativeTimeoutCannotPartiallyShutDownPools() {
        var executor = new RecordingExecutor();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DatabaseExecutorSupport.shutdownAndAwait(
                                List.of(new DatabaseExecutorSupport.Pool("DEFAULT", executor)),
                                -1,
                                TimeUnit.SECONDS));
        assertFalse(executor.isShutdown());
        executor.shutdown();
    }

    private static ThreadPoolExecutor idleExecutor(BlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, queue);
    }

    private static ThreadPoolExecutor workerExecutor(BlockingQueue<Runnable> queue) {
        return new ThreadPoolExecutor(
                1,
                1,
                1,
                TimeUnit.SECONDS,
                queue,
                runnable -> {
                    var thread = new Thread(runnable);
                    thread.setDaemon(true);
                    return thread;
                },
                DatabaseExecutorSupport.callerRunsUnlessShutdown());
    }

    private static void fill(ThreadPoolExecutor executor, int count) {
        for (int index = 0; index < count; index++) executor.getQueue().add(NO_OP);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingExecutor extends ThreadPoolExecutor {
        private List<RecordingExecutor> peers = List.of();
        private boolean terminateOnAwait;
        private boolean consumeWaitBudget;
        private boolean terminated;
        private boolean allShutdownBeforeAwait;
        private int awaitCalls;
        private int shutdownNowCalls;
        private int activeTasks;

        private RecordingExecutor() {
            super(0, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            awaitCalls++;
            allShutdownBeforeAwait = peers.stream().allMatch(ThreadPoolExecutor::isShutdown);
            if (Thread.interrupted()) throw new InterruptedException();
            if (consumeWaitBudget) unit.sleep(timeout);
            terminated = terminateOnAwait;
            return terminated;
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalls++;
            return super.shutdownNow();
        }

        @Override
        public int getActiveCount() {
            return activeTasks;
        }

        @Override
        public long getTaskCount() {
            return activeTasks + getQueue().size();
        }
    }
}
