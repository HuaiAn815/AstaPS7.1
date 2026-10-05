package emu.grasscutter.game.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public final class GridPositionTest {
    @Test
    public void equivalentConstructorsHaveEqualHashes() {
        int[][] coordinates = {
            {0, 0, 20},
            {123, -456, 250},
            {-99, -100, 1000},
            {Integer.MIN_VALUE, Integer.MAX_VALUE, 40}
        };
        for (int[] coordinate : coordinates) {
            var original = new GridPosition(coordinate[0], coordinate[1], coordinate[2]);
            List<GridPosition> equivalents =
                    List.of(
                            original.clone(),
                            new GridPosition(original),
                            new GridPosition(original.toString()),
                            new GridPosition(List.of(coordinate[0], coordinate[1], coordinate[2])));
            for (GridPosition equivalent : equivalents) {
                assertEquals(original, equivalent);
                assertEquals(equivalent, original);
                assertEquals(
                        original.hashCode(),
                        equivalent.hashCode(),
                        "equal positions must have equal hashes");
            }
        }
    }

    @Test
    public void coordinatesContributeToDistributedHashes() {
        var origin = new GridPosition(0, 0, 20);
        assertNotEquals(
                origin.hashCode(), new GridPosition(1, 0, 20).hashCode(), "X must contribute to the hash");
        assertNotEquals(
                origin.hashCode(), new GridPosition(0, 1, 20).hashCode(), "Z must contribute to the hash");
        assertNotEquals(
                origin.hashCode(),
                new GridPosition(0, 0, 40).hashCode(),
                "width must contribute to the hash");

        Set<Integer> hashes = new HashSet<>();
        for (int i = 0; i < 10000; i++) hashes.add(positionAt(i).hashCode());
        assertTrue(hashes.size() > 2000, "grid hashes are insufficiently distributed");
    }

    @Test
    public void copiedCoordinatesFindTheirOriginalMapEntries() {
        Map<GridPosition, Integer> positions = new HashMap<>();
        for (int i = 0; i < 10000; i++) {
            assertNull(
                    positions.put(positionAt(i), i),
                    "distinct coordinates must not replace an existing key");
        }
        assertEquals(10000, positions.size(), "all coordinates must remain in the map");
        for (int i = 0; i < 10000; i++) {
            assertEquals(
                    Integer.valueOf(i),
                    positions.get(positionAt(i).clone()),
                    "lookup with an equal copied coordinate failed");
        }
        assertNull(positions.get(new GridPosition(500, 500, 20)));
    }

    private static GridPosition positionAt(int index) {
        return new GridPosition(index % 100 - 50, index / 100 - 50, 20);
    }
}
