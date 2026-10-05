package emu.grasscutter.game.avatar;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.data.excels.avatar.AvatarSkillDepotData;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

public final class AvatarSkillLevelInitializationTest {
    @Test
    public void missingAndNullLevelsInitializeWithoutNullUnboxing()
            throws ReflectiveOperationException {
        var stored = new HashMap<Integer, Integer>();
        stored.put(10, 9);
        stored.put(20, null);
        stored.put(777, 13);
        stored.put(null, 11);
        Avatar avatar = avatar(stored);

        Map<Integer, Integer> levels = avatar.getSkillLevelMap();

        assertInstanceOf(Int2IntOpenHashMap.class, levels);
        assertEquals(Map.of(10, 9, 20, 1, 30, 1, 40, 1), levels);
        assertEquals(Integer.valueOf(9), stored.get(10));
        assertEquals(Integer.valueOf(1), stored.get(20));
        assertEquals(Integer.valueOf(1), stored.get(30));
        assertEquals(Integer.valueOf(1), stored.get(40));
        assertEquals(Integer.valueOf(13), stored.get(777));
        assertEquals(Integer.valueOf(11), stored.get(null));
        assertFalse(levels.containsKey(777));
        assertFalse(levels.containsKey(null));
        assertFalse(levels.containsKey(0));
        assertFalse(levels.containsKey(-1));

        levels.put(10, 2);
        levels.remove(30);
        assertEquals(Integer.valueOf(9), stored.get(10));
        assertEquals(Integer.valueOf(1), stored.get(30));
        stored.put(20, 7);
        assertEquals(Integer.valueOf(1), levels.get(20));
        Map<Integer, Integer> second = avatar.getSkillLevelMap();
        assertEquals(Map.of(10, 9, 20, 7, 30, 1, 40, 1), second);
        assertNotSame(levels, second);
        assertNotSame(stored, second);
    }

    @Test
    public void existingNonNullLevelsAreNotSilentlyNormalized()
            throws ReflectiveOperationException {
        Map<Integer, Integer> stored = new HashMap<>(Map.of(10, 0, 20, -1, 30, 15, 40, 6));
        Map<Integer, Integer> levels = avatar(stored).getSkillLevelMap();

        assertEquals(Map.of(10, 0, 20, -1, 30, 15, 40, 6), levels);
        assertEquals(levels, stored);
        assertNotSame(stored, levels);
    }

    @Test
    public void fastutilStoredMapAlsoInitializesMissingSkills()
            throws ReflectiveOperationException {
        Map<Integer, Integer> stored = new Int2IntArrayMap();
        stored.put(10, 8);
        stored.put(777, 4);

        Map<Integer, Integer> levels = avatar(stored).getSkillLevelMap();

        assertEquals(Map.of(10, 8, 20, 1, 30, 1, 40, 1), levels);
        assertEquals(Map.of(10, 8, 20, 1, 30, 1, 40, 1, 777, 4), stored);
        levels.clear();
        assertEquals(5, stored.size());
        assertEquals(Integer.valueOf(8), stored.get(10));
    }

    private static Avatar avatar(Map<Integer, Integer> stored)
            throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        // Skip avatar construction, which requires loaded game resources and player managers.
        Avatar avatar = (Avatar) ((Unsafe) field.get(null)).allocateInstance(Avatar.class);
        var depot = new AvatarSkillDepotData();
        set(depot, "skills", List.of(10, 20, 0, -1, 10));
        set(depot, "energySkill", 30);
        set(depot, "attackModeSkill", 40);
        set(avatar, "skillDepot", depot);
        set(avatar, "skillLevelMap", stored);
        return avatar;
    }

    private static void set(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
