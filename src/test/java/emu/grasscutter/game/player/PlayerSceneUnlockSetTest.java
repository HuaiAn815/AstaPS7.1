package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.annotations.PostLoad;
import emu.grasscutter.utils.CompactIntSet;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

public final class PlayerSceneUnlockSetTest {
    @Test
    public void sceneFactoriesCreateIndependentCompactSets() throws ReflectiveOperationException {
        Player player = player();
        Set<Integer> areas = player.getUnlockedSceneAreas(3);
        Set<Integer> points = player.getUnlockedScenePoints(3);
        Set<Integer> otherScene = player.getUnlockedSceneAreas(200);

        assertInstanceOf(CompactIntSet.class, areas);
        assertInstanceOf(CompactIntSet.class, points);
        assertInstanceOf(CompactIntSet.class, otherScene);
        assertTrue(areas.isEmpty());
        assertTrue(points.isEmpty());
        assertSame(areas, player.getUnlockedSceneAreas(3));
        assertSame(points, player.getUnlockedScenePoints(3));
        assertNotSame(areas, points);
        assertNotSame(areas, otherScene);

        areas.add(999);
        points.add(65536);
        otherScene.add(-1);
        assertEquals(Set.of(999), areas);
        assertEquals(Set.of(65536), points);
        assertEquals(Set.of(-1), otherScene);
    }

    @Test
    public void postLoadCompactsBothFieldsWithoutChangingIds() throws ReflectiveOperationException {
        Player player = player();
        Set<Integer> areaIds = new HashSet<>(
                Arrays.asList(null, Integer.MIN_VALUE, -1, 0, 1, 999, 4095, 4096, Integer.MAX_VALUE));
        Set<Integer> pointIds = new HashSet<>(Arrays.asList(null, 1, 7, 65536));
        player.getUnlockedSceneAreas().put(3, new HashSet<>(areaIds));
        player.getUnlockedScenePoints().put(3, new HashSet<>(pointIds));
        var codex = new NoDatabaseCodex();
        var progress = allocate(PlayerProgressManager.class);
        var team = allocate(TeamManager.class);
        set(player, "codex", codex);
        set(player, "progressManager", progress);
        set(player, "teamManager", team);
        Map<Integer, Set<Integer>> areas = player.getUnlockedSceneAreas();
        Map<Integer, Set<Integer>> points = player.getUnlockedScenePoints();

        var onLoad = Player.class.getDeclaredMethod("onLoad");
        assertTrue(onLoad.isAnnotationPresent(PostLoad.class), "Morphia must invoke the load hook");
        onLoad.setAccessible(true);
        onLoad.invoke(player);

        assertSame(areas, player.getUnlockedSceneAreas());
        assertSame(points, player.getUnlockedScenePoints());
        assertInstanceOf(CompactIntSet.class, areas.get(3));
        assertInstanceOf(CompactIntSet.class, points.get(3));
        assertEquals(areaIds, areas.get(3));
        assertEquals(pointIds, points.get(3));
        assertSame(player, codex.loadedPlayer);
        assertSame(player, progress.getPlayer());
        assertSame(player, team.getPlayer());

        Set<Integer> compactAreas = areas.get(3);
        Set<Integer> compactPoints = points.get(3);
        onLoad.invoke(player);
        assertSame(compactAreas, areas.get(3), "Repeated loads must retain existing compact sets");
        assertSame(compactPoints, points.get(3));
        assertEquals(areaIds, compactAreas);
        assertEquals(pointIds, compactPoints);
    }

    private static Player player() throws ReflectiveOperationException {
        // Bypass constructors that initialize game managers or access runtime services.
        Player player = allocate(Player.class);
        player.setUnlockedSceneAreas(new HashMap<>());
        player.setUnlockedScenePoints(new HashMap<>());
        return player;
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Player player, String name, Object value)
            throws ReflectiveOperationException {
        Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    private static final class NoDatabaseCodex extends PlayerCodex {
        private Player loadedPlayer;

        @Override
        public void setPlayer(Player player) {
            // The real codex load hook saves to MongoDB; this fixture only records rebinding.
            this.loadedPlayer = player;
        }
    }
}
