package emu.grasscutter.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

public final class CompactIntSetTest {
    private static final Integer[] BOUNDARIES = {
        null,
        Integer.MIN_VALUE,
        -65536,
        -1,
        0,
        1,
        127,
        128,
        4095,
        4096,
        32767,
        32768,
        65534,
        65535,
        65536,
        65537,
        Integer.MAX_VALUE
    };

    @Test
    @DisplayName("dense, sparse and null IDs obey the mutable Set contract")
    public void boundariesAndDenseIds() {
        var actual = new CompactIntSet();
        var expected = new HashSet<Integer>();
        assertSameSet(expected, actual, "empty");
        for (Integer value : BOUNDARIES) {
            assertEquals(expected.add(value), actual.add(value), "boundary add: " + value);
            assertTrue(actual.contains(value), "boundary missing: " + value);
            assertFalse(actual.add(value), "duplicate boundary: " + value);
        }
        for (int id = 0; id < 10000; id++) {
            assertEquals(expected.add(id), actual.add(id), "dense add: " + id);
        }
        assertSameSet(expected, actual, "dense and sparse boundaries");
        assertFalse(actual.contains("1"));
        assertFalse(actual.contains(1L));
        assertFalse(actual.remove("1"));
        assertFalse(actual.remove(1L));
        for (Integer value : BOUNDARIES) {
            assertEquals(expected.remove(value), actual.remove(value), "boundary remove: " + value);
            assertFalse(actual.contains(value), "boundary not removed: " + value);
        }
        assertSameSet(expected, actual, "removed boundaries");
        actual.clear();
        expected.clear();
        assertSameSet(expected, actual, "cleared");
        assertTrue(actual.add(65535));
        assertTrue(actual.remove(65535));
        assertTrue(actual.isEmpty());
    }

    @Test
    @DisplayName("constructing a set copies IDs without sharing mutable storage")
    public void independentCopies() {
        var source = new ArrayList<>(Arrays.asList(BOUNDARIES));
        var expected = new HashSet<>(source);
        var first = new CompactIntSet(source);
        var second = new CompactIntSet(first);
        var third = new CompactIntSet();
        third.add(2);
        assertSameSet(expected, first, "collection constructor");
        source.clear();
        assertSameSet(expected, first, "source collection isolation");
        assertTrue(first.remove(65535));
        assertTrue(first.remove(Integer.MAX_VALUE));
        assertTrue(first.add(2));
        assertSameSet(expected, second, "copy isolation");
        assertEquals(Set.of(2), third);
        second.clear();
        assertTrue(first.contains(2));
        assertTrue(first.contains(65536));
    }

    @Test
    @DisplayName("iterator removal updates the live set and enforces iterator state")
    public void iteratorRemoval() {
        var actual = new CompactIntSet(Arrays.asList(BOUNDARIES));
        var expected = new HashSet<>(Arrays.asList(BOUNDARIES));
        Iterator<Integer> iterator = actual.iterator();
        assertThrows(IllegalStateException.class, iterator::remove);
        while (iterator.hasNext()) {
            Integer value = iterator.next();
            assertTrue(expected.remove(value), "iterator duplicate or foreign value: " + value);
            iterator.remove();
            assertFalse(actual.contains(value), "iterator remove: " + value);
            assertThrows(IllegalStateException.class, iterator::remove);
            assertSameSet(expected, actual, "iterator removal");
        }
        assertTrue(actual.isEmpty());
        assertTrue(expected.isEmpty());
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    @DisplayName("iteration is a stable snapshot even after the live set changes")
    public void snapshotIteration() {
        var original = new HashSet<>(Arrays.asList(BOUNDARIES));
        var actual = new CompactIntSet(original);
        Iterator<Integer> snapshot = actual.iterator();
        actual.add(1234567);
        actual.remove(1);
        var observed = new HashSet<Integer>();
        while (snapshot.hasNext()) {
            Integer value = snapshot.next();
            assertTrue(observed.add(value), "snapshot duplicate: " + value);
        }
        assertEquals(original, observed);
        assertTrue(actual.contains(1234567));
        assertFalse(actual.contains(1));
    }

    @Test
    @DisplayName("random bulk operations match HashSet including self operations")
    public void randomOperations() {
        var random = new Random(0xC01A7L);
        var expected = new HashSet<Integer>();
        var actual = new CompactIntSet();
        for (int step = 0; step < 10000; step++) {
            Integer value = randomId(random);
            List<Integer> batch = randomBatch(random);
            String where = "random step " + step;
            switch (random.nextInt(7)) {
                case 0 -> assertEquals(expected.add(value), actual.add(value), where + " add");
                case 1 -> assertEquals(
                        expected.remove(value), actual.remove(value), where + " remove");
                case 2 -> assertEquals(
                        expected.contains(value), actual.contains(value), where + " contains");
                case 3 -> assertEquals(
                        expected.removeAll(batch), actual.removeAll(batch), where + " removeAll");
                case 4 -> assertEquals(
                        expected.retainAll(batch), actual.retainAll(batch), where + " retainAll");
                case 5 -> assertEquals(
                        expected.addAll(batch), actual.addAll(batch), where + " addAll");
                case 6 -> {
                    actual.clear();
                    expected.clear();
                }
            }
            assertSameSet(expected, actual, where);
            assertEquals(
                    expected.containsAll(batch), actual.containsAll(batch), where + " containsAll");
        }
        actual.addAll(Arrays.asList(BOUNDARIES));
        assertTrue(actual.removeAll(actual));
        assertTrue(actual.isEmpty());
        assertFalse(actual.removeAll(actual));
        actual.addAll(Arrays.asList(BOUNDARIES));
        assertFalse(actual.retainAll(actual));
        assertSameSet(new HashSet<>(Arrays.asList(BOUNDARIES)), actual, "self retainAll");
    }

    @Test
    @DisplayName("Gson persists IDs as an array without exposing compact storage")
    public void gsonRoundTrip() {
        Gson gson = new Gson();
        var expected = new HashSet<>(Arrays.asList(BOUNDARIES));
        var original = new CompactIntSet(expected);
        String json = gson.toJson(original);
        assertTrue(JsonParser.parseString(json).isJsonArray());
        Set<Integer> decoded = gson.fromJson(json, new TypeToken<HashSet<Integer>>() {}.getType());
        assertEquals(expected, decoded);
        assertSameSet(
                expected, gson.fromJson(json, CompactIntSet.class), "Gson compact round trip");
        assertSameSet(expected, new CompactIntSet(decoded), "Gson collection reconstruction");
        assertEquals("[]", gson.toJson(new CompactIntSet()));
    }

    @Test
    @Timeout(30)
    @DisplayName(
            "concurrent duplicate additions and removals keep size and return values consistent")
    public void concurrentDuplicateAddsAndRemoves() throws Exception {
        var actual = new CompactIntSet();
        var expected = new HashSet<Integer>();
        for (int id = 0; id < 8192; id++) expected.add(id);
        expected.add(null);
        expected.add(-1);
        expected.add(Integer.MAX_VALUE);
        var additions = new AtomicInteger();
        runConcurrently(
                8,
                worker -> {
                    for (Integer id : expected) {
                        if (actual.add(id)) additions.incrementAndGet();
                    }
                });
        assertEquals(expected.size(), additions.get());
        assertSameSet(expected, actual, "concurrent additions");

        var removals = new AtomicInteger();
        runConcurrently(
                8,
                worker -> {
                    for (Integer id : expected) {
                        if (actual.remove(id)) removals.incrementAndGet();
                    }
                });
        assertEquals(expected.size(), removals.get());
        assertSameSet(Set.of(), actual, "concurrent removals");
    }

    @Test
    @Timeout(30)
    @DisplayName("simultaneous additions and removals preserve dense and sparse members")
    public void concurrentMixedOperations() throws Exception {
        var actual = new CompactIntSet();
        var expected = new HashSet<Integer>();
        for (int id = 0; id < 8192; id++) {
            if ((id & 1) == 1) actual.add(id);
            else expected.add(id);
        }
        runConcurrently(
                8,
                worker -> {
                    for (int id = worker % 2; id < 8192; id += 2) {
                        if ((worker & 1) == 0) actual.add(id);
                        else actual.remove(id);
                    }
                });
        assertSameSet(expected, actual, "simultaneous additions and removals");
    }

    private static void runConcurrently(int workers, IntConsumer action) throws Exception {
        var executor = Executors.newFixedThreadPool(workers);
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(workers);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int index = worker;
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    action.accept(index);
                                    return null;
                                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not reach the start barrier");
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "workers did not stop");
        }
    }

    private static Integer randomId(Random random) {
        if (random.nextBoolean()) return BOUNDARIES[random.nextInt(BOUNDARIES.length)];
        return random.nextInt(140000) - 70000;
    }

    private static List<Integer> randomBatch(Random random) {
        var batch = new ArrayList<Integer>();
        for (int index = random.nextInt(17); index > 0; index--) batch.add(randomId(random));
        return batch;
    }

    private static void assertSameSet(Set<Integer> expected, CompactIntSet actual, String where) {
        assertEquals(expected.size(), actual.size(), where + " size");
        assertEquals(expected.isEmpty(), actual.isEmpty(), where + " isEmpty");
        assertEquals(expected, actual, where + " equals");
        assertEquals(actual, expected, where + " symmetric equals");
        assertEquals(expected.hashCode(), actual.hashCode(), where + " hashCode");
        Object[] objects = actual.toArray();
        assertEquals(expected.size(), objects.length, where + " toArray length");
        assertEquals(expected, new HashSet<>(Arrays.asList(objects)), where + " toArray members");
        Integer[] typed = actual.toArray(new Integer[0]);
        assertEquals(expected.size(), typed.length, where + " typed toArray length");
        assertEquals(
                expected, new HashSet<>(Arrays.asList(typed)), where + " typed toArray members");
        Integer[] roomy = new Integer[expected.size() + 2];
        Arrays.fill(roomy, 42);
        assertSame(roomy, actual.toArray(roomy), where + " oversized array reuse");
        assertNull(roomy[expected.size()], where + " oversized array terminator");
        assertEquals(
                Integer.valueOf(42), roomy[expected.size() + 1], where + " oversized array tail");
        assertEquals(
                expected,
                new HashSet<>(Arrays.asList(roomy).subList(0, expected.size())),
                where + " oversized toArray members");
    }
}
