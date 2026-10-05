package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.google.gson.JsonArray;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.TriggerExcelConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.activity.ActivityManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.EntityIdType;
import emu.grasscutter.game.props.WatcherTriggerType;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.QuestListUpdateNotifyOuterClass.QuestListUpdateNotify;
import emu.grasscutter.scripts.SceneScriptManager;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.constants.EventType;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.scripts.data.SceneRegion;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.utils.FileUtils;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import sun.misc.Unsafe;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.Bindings;

@ExtendWith(ServerResourceFixture.class)
public final class QuestStartTriggerRecoveryTest {
    private static final String FIXTURE = "/emu/grasscutter/game/quest/lunagc-7.1/";
    @TempDir Path scripts;
    private Map<Integer, QuestData> originalQuests;
    private Map<Integer, MainQuestData> originalMains;
    private Map<Integer, TriggerExcelConfigData> originalTriggers;
    private Map<String, List<QuestData>> originalConditions;
    private final Map<String, Object> originalScriptCaches = new HashMap<>();
    private Path originalScriptPath;
    private AtomicReference<Bindings> currentBindings;
    private Bindings originalBindings;
    private boolean questing;
    private boolean bigWorldScripts;
    private RecordingPlayer player;
    private RecordingWorld world;
    private RecordingScene scene;
    private RecordingMainQuest main;
    private GameQuest quest;
    private GameQuest stateObserver;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setup() throws Exception {
        questing = GAME_OPTIONS.questing.enabled;
        bigWorldScripts = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = false;
        originalQuests = new HashMap<>(GameData.getQuestDataMap());
        originalMains = new HashMap<>(GameData.getMainQuestDataMap());
        originalTriggers = new HashMap<>(GameData.getTriggerExcelConfigDataMap());
        originalConditions = new HashMap<>(GameData.getBeginCondQuestMap());
        GameData.getQuestDataMap().clear();
        GameData.getMainQuestDataMap().clear();
        GameData.getTriggerExcelConfigDataMap().clear();
        GameData.getBeginCondQuestMap().clear();

        ScriptLoader.init();
        var bindingField = ScriptLoader.class.getDeclaredField("currentBindings");
        bindingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var bindingReference = (AtomicReference<Bindings>) bindingField.get(null);
        currentBindings = bindingReference;
        originalBindings = currentBindings.get();
        for (var name : List.of("scriptSources", "scriptsCache")) {
            var field = ScriptLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            originalScriptCaches.put(name, field.get(null));
            field.set(null, new ConcurrentHashMap<>());
        }
        originalScriptPath = FileUtils.getScriptPath("");
        setScriptPath(scripts);
        logs = new ListAppender<>();
        logs.start();
        Grasscutter.getLogger().addAppender(logs);

        var server = allocate(GameServer.class);
        set(server, GameServer.class, "questSystem", new QuestSystem(server));
        player = new RecordingPlayer(server);
        player.setUid(950023);
        player.setSession(new RecordingSession(server, player));
        set(player, Player.class, "activityManager", allocate(RecordingActivityManager.class));
        world = allocate(RecordingWorld.class);
        scene = allocate(RecordingScene.class);
        set(scene, Scene.class, "world", world);
        set(scene, Scene.class, "scriptManager", new SceneScriptManager(scene));
        world.scene = scene;
        player.setWorld(world);
        player.setScene(scene);
        SERVER.game.enableScriptInBigWorld = true;

        var realQuest =
                readList("ExcelBinOutput/QuestExcelConfigData.json", QuestData.class).stream()
                        .filter(row -> row.getId() == 35100)
                        .findFirst()
                        .orElseThrow();
        realQuest.onLoad();
        GameData.getQuestDataMap().put(realQuest.getId(), realQuest);
        for (var trigger :
                readList(
                        "ExcelBinOutput/TriggerExcelConfigData.json",
                        TriggerExcelConfigData.class)) {
            GameData.getTriggerExcelConfigDataMap().put(trigger.getId(), trigger);
        }
        main = addMain(realQuest);
        quest = main.getChildQuestById(realQuest.getId());

        var observer =
                JsonUtils.decode(
                        "{\"subId\":9502301,\"mainId\":95023,"
                            + "\"acceptCond\":[{\"type\":\"QUEST_COND_STATE_EQUAL\",\"param\":[35100,2]}],"
                            + "\"finishCond\":[],\"failCond\":[],\"beginExec\":[],"
                            + "\"finishExec\":[],\"failExec\":[]}",
                        QuestData.class);
        observer.onLoad();
        GameData.getQuestDataMap().put(observer.getId(), observer);
        stateObserver = addMain(observer).getChildQuestById(observer.getId());
    }

    @AfterEach
    void restore() throws Exception {
        drainEvents();
        Grasscutter.getLogger().detachAppender(logs);
        logs.stop();
        setScriptPath(originalScriptPath);
        currentBindings.set(originalBindings);
        for (var entry : originalScriptCaches.entrySet()) {
            var field = ScriptLoader.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(null, entry.getValue());
        }
        GameData.getQuestDataMap().clear();
        GameData.getQuestDataMap().putAll(originalQuests);
        GameData.getMainQuestDataMap().clear();
        GameData.getMainQuestDataMap().putAll(originalMains);
        GameData.getTriggerExcelConfigDataMap().clear();
        GameData.getTriggerExcelConfigDataMap().putAll(originalTriggers);
        GameData.getBeginCondQuestMap().clear();
        GameData.getBeginCondQuestMap().putAll(originalConditions);
        GAME_OPTIONS.questing.enabled = questing;
        SERVER.game.enableScriptInBigWorld = bigWorldScripts;
    }

    @Test
    void real35100StartsAndCanFinishByPlotWithoutItsSceneGroupScript() throws Exception {
        assertFalse(Files.exists(FileUtils.getScriptPath(groupPath())));

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();
        assertEquals(
                0,
                scene.getScriptManager().getTriggersByEvent(EventType.EVENT_ENTER_REGION).size());
        assertTrue(quest.getTriggerData().isEmpty());
        assertArrayEquals(new int[] {0, 0}, quest.getFinishProgressList());
        assertLogged("group script is unavailable or incomplete");

        player.getQuestManager().triggerEvent(QuestContent.QUEST_CONTENT_FINISH_PLOT, "", 35100, 0);
        drainEvents();

        assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
        assertTrue(main.savedStates.contains(QuestState.QUEST_STATE_FINISHED));
        assertTrue(
                updates().stream()
                        .anyMatch(
                                update -> update.getQuestId() == 35100 && update.getState() == 3));
    }

    @Test
    void luaEvaluationFailureDoesNotInterruptQuestStartup() throws Exception {
        writeGroup("error('invalid quest group')");

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();

        assertTrue(quest.getTriggerData().isEmpty());
        assertLogged("group script is unavailable or incomplete");
    }

    @Test
    void registrationFailureDoesNotBlockTheNextValidTrigger() throws Exception {
        writeGroup(readText("Scripts/Scene/3/scene3_group133003901.lua"));
        addSecondTrigger(1017);
        scene.failTrigger = "ENTER_REGION_84";

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();

        assertEquals(List.of("ENTER_REGION_84", "ENTER_REGION_35"), scene.attemptedTriggers);
        var registered = scene.getScriptManager().getTriggersByEvent(EventType.EVENT_ENTER_REGION);
        assertEquals(1, registered.size());
        assertEquals("ENTER_REGION_35", registered.iterator().next().getName());
        assertEquals(1, quest.getTriggerData().size());
        assertTrue(quest.getTriggerData().containsKey("ENTER_REGION_35"));
        assertFalse(quest.getTriggerData().containsKey("ENTER_REGION_84"));
        assertFalse(quest.getTriggers().get("ENTER_REGION_35"));
        assertTrue(
                logs.list.stream()
                        .anyMatch(
                                event ->
                                        event.getFormattedMessage()
                                                        .contains("quest 35100 trigger 1053")
                                                && event.getThrowableProxy() != null));
    }

    @Test
    void missingSceneDoesNotInterruptQuestStartup() throws Exception {
        writeGroup(readText("Scripts/Scene/3/scene3_group133003901.lua"));
        world.scene = null;

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();

        assertTrue(quest.getTriggerData().isEmpty());
        assertLogged("scene is unavailable");
    }

    @Test
    void savedRegionKeysWithoutRuntimeDataAreClearedWhenTheirScriptIsMissing()
            throws Exception {
        seedSavedRegionKeys();

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();

        assertRegionCallbacksIgnoreUnavailableTriggers();
        assertLogged("group script is unavailable or incomplete");
    }

    @Test
    void rewindClearsSavedRegionKeysWhenTheirSceneIsUnavailable() throws Exception {
        writeGroup(readText("Scripts/Scene/3/scene3_group133003901.lua"));
        seedSavedRegionKeys();
        world.scene = null;

        assertDoesNotThrow(() -> assertTrue(quest.rewind(false)));
        assertStartupCompleted(2);

        assertEquals(QuestState.QUEST_STATE_UNSTARTED, main.savedStates.get(0));
        assertRegionCallbacksIgnoreUnavailableTriggers();
        assertLogged("scene is unavailable");
    }

    @Test
    void restartingAfterRegistrationClearsOldMetFlagsWhenTheSceneDisappears()
            throws Exception {
        writeGroup(readText("Scripts/Scene/3/scene3_group133003901.lua"));
        quest.start();
        assertStartupCompleted();
        assertTrue(quest.getTriggerData().containsKey("ENTER_REGION_84"));
        quest.getTriggers().put("ENTER_REGION_84", true);
        quest.getTriggers().put("LEAVE_REGION_84", true);
        main.savedStates.clear();
        player.packets.clear();
        world.scene = null;

        assertDoesNotThrow(quest::start);
        assertStartupCompleted();

        assertRegionCallbacksIgnoreUnavailableTriggers();
        assertLogged("scene is unavailable");
    }

    private void seedSavedRegionKeys() throws Exception {
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        quest.getTriggers().put("ENTER_REGION_84", true);
        quest.getTriggers().put("LEAVE_REGION_84", true);
        set(quest, GameQuest.class, "triggerData", null);
    }

    private void assertRegionCallbacksIgnoreUnavailableTriggers() throws Exception {
        assertNotNull(quest.getTriggerData());
        assertTrue(quest.getTriggerData().isEmpty());
        assertTrue(quest.getTriggers().isEmpty());
        var region = new SceneRegion();
        region.config_id = 84;
        region.group = SceneGroup.of(133003901);

        assertDoesNotThrow(() -> player.onEnterRegion(region));
        assertDoesNotThrow(() -> player.onLeaveRegion(region));
        drainEvents();

        assertEquals(QuestState.QUEST_STATE_UNFINISHED, quest.getState());
        assertArrayEquals(new int[] {0, 0}, quest.getFinishProgressList());
    }

    private void assertStartupCompleted() throws Exception {
        assertStartupCompleted(1);
    }

    private void assertStartupCompleted(int saves) throws Exception {
        drainEvents();
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, quest.getState());
        assertTrue(quest.getStartTime() > 0);
        assertEquals(quest.getStartTime(), quest.getAcceptTime());
        assertEquals(saves, main.savedStates.size());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, main.savedStates.get(saves - 1));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, stateObserver.getState());
        assertEquals(
                1,
                updates().stream()
                        .filter(update -> update.getQuestId() == 35100 && update.getState() == 2)
                        .count());
    }

    private List<emu.grasscutter.net.proto.QuestOuterClass.Quest> updates() throws Exception {
        var updates = new ArrayList<emu.grasscutter.net.proto.QuestOuterClass.Quest>();
        for (var packet : player.packets) {
            if (packet.getOpcode() == PacketOpcodes.QuestListUpdateNotify) {
                updates.addAll(
                        QuestListUpdateNotify.parseFrom(packet.getData()).getQuestListList());
            }
        }
        return updates;
    }

    private void assertLogged(String reason) {
        assertTrue(
                logs.list.stream()
                        .anyMatch(
                                event -> {
                                    var message = event.getFormattedMessage();
                                    return message.contains("quest 35100 trigger 1053")
                                            && message.contains("scene 3, group 133003901")
                                            && message.contains(reason);
                                }));
    }

    private void addSecondTrigger(int triggerId) {
        var json = JsonUtils.toJson(quest.getQuestData()).getAsJsonObject();
        var condition = new QuestData.QuestContentCondition();
        condition.setType(QuestContent.QUEST_CONTENT_TRIGGER_FIRE);
        condition.setParam(new int[] {triggerId, 0});
        json.getAsJsonArray("finishCond").add(JsonUtils.toJson(condition));
        var data = JsonUtils.decode(json, QuestData.class);
        GameData.getQuestDataMap().put(data.getId(), data);
        main.getChildQuests().put(data.getId(), new GameQuest(main, data));
        quest = main.getChildQuestById(data.getId());
    }

    private RecordingMainQuest addMain(QuestData data) {
        var child = new com.google.gson.JsonObject();
        child.addProperty("subId", data.getId());
        child.addProperty("order", data.getOrder());
        var children = new JsonArray();
        children.add(child);
        var parent = new com.google.gson.JsonObject();
        parent.addProperty("id", data.getMainId());
        parent.add("subQuests", children);
        GameData.getMainQuestDataMap()
                .put(data.getMainId(), JsonUtils.decode(parent, MainQuestData.class));
        var result = new RecordingMainQuest(player, data.getMainId());
        player.getQuestManager().getMainQuests().put(data.getMainId(), result);
        return result;
    }

    private void writeGroup(String source) throws Exception {
        var path = scripts.resolve(groupPath());
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
    }

    private static String groupPath() {
        return "Scene/3/scene3_group133003901.lua";
    }

    private static void drainEvents() throws Exception {
        // An acceptance event can enqueue the newly started quest's state events.
        QuestManager.eventExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        QuestManager.eventExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static String readText(String name) throws Exception {
        try (var input = QuestStartTriggerRecoveryTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T> List<T> readList(String name, Class<T> type) throws Exception {
        try (var input = QuestStartTriggerRecoveryTest.class.getResourceAsStream(FIXTURE + name)) {
            assertNotNull(input);
            return JsonUtils.loadToList(new InputStreamReader(input, StandardCharsets.UTF_8), type);
        }
    }

    private static Unsafe unsafe() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(unsafe().allocateInstance(type));
    }

    private static void set(Object instance, Class<?> owner, String name, Object value)
            throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static void setScriptPath(Path path) throws Exception {
        // Isolate resource lookup without touching the server's installed scene scripts.
        Field field = FileUtils.class.getDeclaredField("SCRIPTS_PATH");
        var unsafe = unsafe();
        unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), path);
    }

    private static final class RecordingPlayer extends Player {
        private final GameServer server;
        private final List<BasePacket> packets = new CopyOnWriteArrayList<>();

        private RecordingPlayer(GameServer server) {
            this.server = server;
        }

        @Override
        public GameServer getServer() {
            return server;
        }

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }
    }

    private static final class RecordingSession extends GameSession {
        private final RecordingPlayer player;

        private RecordingSession(GameServer server, RecordingPlayer player) {
            super(server);
            this.player = player;
        }

        @Override
        public void send(BasePacket packet) {
            player.sendPacket(packet);
        }
    }

    private static final class RecordingMainQuest extends GameMainQuest {
        private final List<QuestState> savedStates = new CopyOnWriteArrayList<>();

        private RecordingMainQuest(Player owner, int id) {
            super(owner, id);
        }

        @Override
        public void save() {
            savedStates.add(getChildQuests().values().iterator().next().getState());
        }
    }

    private static final class RecordingActivityManager extends ActivityManager {
        private RecordingActivityManager() {
            super(null);
        }

        @Override
        public void triggerWatcher(WatcherTriggerType type, String... params) {}
    }

    private static final class RecordingWorld extends World {
        private Scene scene;
        private int nextId;

        private RecordingWorld() {
            super((Player) null);
        }

        @Override
        public long getTotalGameTimeDays() {
            return 0;
        }

        @Override
        public Scene getSceneById(int id) {
            return scene;
        }

        @Override
        public int getNextEntityId(EntityIdType type) {
            return ++nextId;
        }
    }

    private static final class RecordingScene extends Scene {
        private String failTrigger;
        private List<String> attemptedTriggers;

        private RecordingScene() {
            super(null, null);
        }

        @Override
        public int getId() {
            return 3;
        }

        @Override
        public void loadTriggerFromGroup(SceneGroup group, String triggerName) {
            if (attemptedTriggers == null) attemptedTriggers = new ArrayList<>();
            attemptedTriggers.add(triggerName);
            if (triggerName.equals(failTrigger))
                throw new IllegalStateException("invalid trigger registration");
            super.loadTriggerFromGroup(group, triggerName);
        }
    }
}
