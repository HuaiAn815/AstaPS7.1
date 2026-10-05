package emu.grasscutter.game.player;

import emu.grasscutter.utils.CompactIntSet;
import java.util.Map;
import java.util.Set;

/** Changes only the in-memory representation, not the persisted unlock IDs. */
public final class SceneUnlockSetNormalizer {
    private SceneUnlockSetNormalizer() {}

    public static void compact(Player player) {
        compact(player.getUnlockedSceneAreas());
        compact(player.getUnlockedScenePoints());
    }

    public static void compact(Map<Integer, Set<Integer>> scenes) {
        if (scenes == null) return;
        scenes.replaceAll((sceneId, ids) ->
                ids == null || ids instanceof CompactIntSet ? ids : new CompactIntSet(ids));
    }
}
