package emu.grasscutter.game.world;

import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.managers.blossom.BlossomManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.SceneType;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.SceneBlock;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.SceneInitConfig;
import emu.grasscutter.scripts.data.ScriptArgs;
import emu.grasscutter.server.scheduler.ServerTaskScheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@ExtendWith(ServerResourceFixture.class)
public final class SceneQuestGroupIsolationTest {
    private boolean scriptsEnabled;

    @BeforeEach
    void disableBackgroundScriptLoading() {
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        SERVER.game.enableScriptInBigWorld = false;
    }

    @AfterEach
    void restoreConfiguration() {
        SERVER.game.enableScriptInBigWorld = scriptsEnabled;
    }

    @Test
    void brokenLuaGroupDoesNotPreventLaterQuestGroupFromLoading() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        set(scene, Scene.class, "scriptManager", manager);
        var broken = group(133003901);
        var healthy = group(133003429);
        manager.failedLoads.add(broken.id);

        assertDoesNotThrow(() -> scene.onLoadGroup(List.of(broken, healthy)));

        assertEquals(Set.of(healthy), scene.getLoadedGroups());
        assertEquals(List.of(healthy.id), manager.refreshedGroups);
        assertEquals(List.of(healthy.id), manager.loadedEvents);
        assertFalse(manager.getLoadedGroupSetPerBlock().get(1).contains(broken));
    }

    @Test
    void failedQuestSuiteDoesNotEmitSuccessfulLoadEventOrBlockNextSuite() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        set(scene, Scene.class, "scriptManager", manager);
        var broken = group(133003901);
        var healthy = group(133003429);
        manager.failedRefreshes.add(broken.id);

        assertDoesNotThrow(() -> scene.onLoadGroup(List.of(broken, healthy)));

        assertEquals(Set.of(healthy), scene.getLoadedGroups());
        assertEquals(List.of(healthy.id), manager.loadedEvents);
        assertEquals(1, manager.instances.get(healthy.id).getActiveSuiteId());
        assertEquals(0, manager.instances.get(broken.id).getActiveSuiteId());
    }

    @Test
    void unavailableQuestSuiteCanRetryWithoutEmittingAnEarlyLoadEvent() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        set(scene, Scene.class, "scriptManager", manager);
        var unavailable = group(133003901);
        var healthy = group(133003429);
        manager.unavailableSuites.add(unavailable.id);

        scene.onLoadGroup(List.of(unavailable, healthy));

        assertEquals(Set.of(healthy), scene.getLoadedGroups());
        assertEquals(List.of(healthy.id), manager.loadedEvents);
        manager.unavailableSuites.clear();
        scene.onLoadGroup(List.of(unavailable, healthy));
        assertEquals(Set.of(unavailable, healthy), scene.getLoadedGroups());
        assertEquals(List.of(healthy.id, unavailable.id), manager.loadedEvents);
    }

    @Test
    void failedGroupStreamingStillChecksRegionsAndFinishesSceneLoading() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        manager.initialized = true;
        scene.failGroupStream = true;
        set(scene, Scene.class, "scriptManager", manager);
        int[] callbacks = {0};
        scene.runWhenFinished(() -> callbacks[0]++);

        assertDoesNotThrow(scene::onTick);

        assertEquals(1, scene.groupChecks);
        assertEquals(1, manager.regionChecks);
        assertTrue(scene.isFinishedLoading());
        assertEquals(1, callbacks[0]);
        assertEquals(1, scene.npcChecks);
        assertEquals(1, scene.respawnChecks);
    }

    @Test
    void disabledBigWorldScriptsFinishInitializationAndUseFallbackSpawns() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        set(scene, Scene.class, "scriptManager", manager);

        assertFalse(manager.isInit());
        assertTrue(manager.isInitAttempted());
        assertDoesNotThrow(scene::onTick);

        assertEquals(1, scene.spawnChecks);
        assertEquals(0, scene.groupChecks);
        assertTrue(scene.isFinishedLoading());
    }

    @Test
    void failedSceneMetadataInitializationReleasesFallbackSpawning() throws Exception {
        var scene = scene();
        var manager = new RecordingScriptManager(scene);
        set(scene, Scene.class, "scriptManager", manager);
        set(manager, SceneScriptManager.class, "initAttempted", false);
        var cache = ScriptLoader.class.getDeclaredField("sceneMetaCache");
        cache.setAccessible(true);
        var previous = cache.get(null);
        try {
            cache.set(
                    null,
                    new HashMap<Integer, Object>() {
                        @Override
                        public Object get(Object key) {
                            throw new IllegalStateException("invalid scene metadata");
                        }
                    });
            var init = SceneScriptManager.class.getDeclaredMethod("init");
            init.setAccessible(true);

            assertDoesNotThrow(() -> init.invoke(manager));
            assertFalse(manager.isInit());
            assertTrue(manager.isInitAttempted());
            assertDoesNotThrow(scene::onTick);
            assertEquals(1, scene.spawnChecks);
            assertTrue(scene.isFinishedLoading());
        } finally {
            cache.set(null, previous);
        }
    }

    private static SceneGroup group(int id) {
        var group = SceneGroup.of(id);
        group.block_id = 1;
        group.init_config = new SceneInitConfig();
        group.init_config.suite = 1;
        return group;
    }

    private static RecordingScene scene() throws Exception {
        // Scene construction starts runtime services; this fixture exercises only its load
        // lifecycle.
        var scene = allocate(RecordingScene.class);
        set(scene, Scene.class, "players", new ArrayList<Player>());
        set(scene, Scene.class, "loadedGroups", new HashSet<SceneGroup>());
        set(scene, Scene.class, "entities", new HashMap<Integer, GameEntity>());
        set(scene, Scene.class, "reportedStages", new HashSet<String>());
        set(scene, Scene.class, "afterLoadedCallbacks", new ArrayList<Runnable>());
        set(scene, Scene.class, "scheduler", new ServerTaskScheduler());
        set(scene, Scene.class, "blossomManager", new BlossomManager(scene));
        return scene;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void set(Object instance, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static final class RecordingScene extends Scene {
        private boolean failGroupStream;
        private int groupChecks;
        private int spawnChecks;
        private int npcChecks;
        private int respawnChecks;

        private RecordingScene() {
            super(null, null);
        }

        @Override
        public int getId() {
            return 3;
        }

        @Override
        public SceneType getSceneType() {
            return SceneType.SCENE_WORLD;
        }

        @Override
        public int getSceneTimeSeconds() {
            return 0;
        }

        @Override
        public void checkGroups() {
            groupChecks++;
            if (failGroupStream) throw new IllegalStateException("invalid quest group Lua");
        }

        @Override
        public void checkSpawns() {
            spawnChecks++;
        }

        @Override
        public void checkNpcGroup() {
            npcChecks++;
        }

        @Override
        protected void checkPlayerRespawn() {
            respawnChecks++;
        }
    }

    private static final class RecordingScriptManager extends SceneScriptManager {
        private boolean initialized;
        private int regionChecks;
        private final Set<Integer> failedLoads = new HashSet<>();
        private final Set<Integer> failedRefreshes = new HashSet<>();
        private final Set<Integer> unavailableSuites = new HashSet<>();
        private final Map<Integer, SceneGroupInstance> instances = new HashMap<>();
        private final List<Integer> refreshedGroups = new ArrayList<>();
        private final List<Integer> loadedEvents = new ArrayList<>();

        private RecordingScriptManager(Scene scene) {
            super(scene);
            getLoadedGroupSetPerBlock().put(1, new HashSet<>());
        }

        @Override
        public boolean isInit() {
            return initialized;
        }

        @Override
        public void loadGroupFromScript(SceneGroup group) {
            if (failedLoads.contains(group.id)) {
                throw new IllegalArgumentException("invalid Lua for group " + group.id);
            }
            instances.put(group.id, new SceneGroupInstance(group, new Player()));
        }

        @Override
        public Map<Integer, SceneBlock> getBlocks() {
            return Map.of();
        }

        @Override
        public SceneGroupInstance getGroupInstanceById(int groupId) {
            return instances.get(groupId);
        }

        @Override
        public SceneGroupInstance getCachedGroupInstanceById(int groupId) {
            return null;
        }

        @Override
        public int refreshGroup(SceneGroupInstance group, int suiteIndex, boolean excludePrev) {
            if (failedRefreshes.contains(group.getGroupId())) {
                throw new IllegalStateException("invalid suite for group " + group.getGroupId());
            }
            if (unavailableSuites.contains(group.getGroupId())) return 0;
            refreshedGroups.add(group.getGroupId());
            group.setActiveSuiteId(1);
            return 1;
        }

        @Override
        public Future<?> callEvent(ScriptArgs args) {
            if (args.type == EventType.EVENT_GROUP_LOAD) loadedEvents.add(args.group_id);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void checkRegions() {
            regionChecks++;
        }

        @Override
        public void meetEntities(List<? extends GameEntity> entities) {}
    }
}
