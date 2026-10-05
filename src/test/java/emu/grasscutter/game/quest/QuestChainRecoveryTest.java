package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.morphia.Datastore;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.TriggerExcelConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.quest.QuestData.QuestAcceptCondition;
import emu.grasscutter.database.DatabaseHelper;
import emu.grasscutter.database.DatabaseManager;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.LogicType;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestCond;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.World;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.QuestListUpdateNotifyOuterClass.QuestListUpdateNotify;
import emu.grasscutter.net.proto.QuestOuterClass.Quest;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sun.misc.Unsafe;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Exercises real quest startup and client packets against the LunaGC prologue resources. */
@ExtendWith(ServerResourceFixture.class)
public final class QuestChainRecoveryTest {
    private static final String FIXTURE = "/emu/grasscutter/game/quest/lunagc-7.1/";
    private Map<Integer, QuestData> savedQuests;
    private Map<Integer, MainQuestData> savedMains;
    private Map<String, List<QuestData>> savedConditions;
    private Map<Integer, Integer> savedTalks;
    private Map<Integer, TriggerExcelConfigData> savedTriggers;
    private Map<Integer, RewindData> savedRewinds;
    private final List<Object> savedEntities = new CopyOnWriteArrayList<>();
    private Field datastoreField;
    private Datastore savedDatastore;
    private boolean questingEnabled;
    private boolean triggerAllOnLogin;
    private boolean scriptsEnabled;
    private RecordingPlayer player;

    @BeforeEach
    void setup() throws Exception {
        drainAsyncWork();
        questingEnabled = GAME_OPTIONS.questing.enabled;
        triggerAllOnLogin = GAME_OPTIONS.questing.triggerAllOnLogin;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = true;
        GAME_OPTIONS.questing.triggerAllOnLogin = false;
        SERVER.game.enableScriptInBigWorld = true;

        savedQuests = new HashMap<>(GameData.getQuestDataMap());
        savedMains = new HashMap<>(GameData.getMainQuestDataMap());
        savedConditions = new HashMap<>();
        GameData.getBeginCondQuestMap()
                .forEach((key, quests) -> savedConditions.put(key, new ArrayList<>(quests)));
        savedTalks = new HashMap<>(GameData.getQuestTalkMap());
        savedTriggers = new HashMap<>(GameData.getTriggerExcelConfigDataMap());
        savedRewinds = new HashMap<>(GameData.getRewindDataMap());
        GameData.getQuestDataMap().clear();
        GameData.getMainQuestDataMap().clear();
        GameData.getBeginCondQuestMap().clear();
        GameData.getQuestTalkMap().clear();
        // Region loading and teleport coordinates are outside this acceptance regression.
        GameData.getTriggerExcelConfigDataMap().clear();
        GameData.getRewindDataMap().clear();

        for (var quest : readList("ExcelBinOutput/QuestExcelConfigData.json", QuestData.class)) {
            GameData.getQuestDataMap().put(quest.getId(), quest);
            quest.onLoad();
        }
        for (int mainId : List.of(351, 352)) {
            var main = read("BinOutput/Quest/" + mainId + ".json", MainQuestData.class);
            GameData.getMainQuestDataMap().put(mainId, main);
            main.onLoad();
        }

        datastoreField = DatabaseManager.class.getDeclaredField("gameDatastore");
        datastoreField.setAccessible(true);
        savedDatastore = (Datastore) datastoreField.get(null);
        var datastore =
                (Datastore)
                        Proxy.newProxyInstance(
                                Datastore.class.getClassLoader(),
                                new Class<?>[] {Datastore.class},
                                (ignored, method, args) -> {
                                    assertEquals("save", method.getName());
                                    savedEntities.add(args[0]);
                                    return args[0];
                                });
        datastoreField.set(null, datastore);
        player = new RecordingPlayer(questServer());
        player.setUid(960001);
        var world = (World) unsafe().allocateInstance(World.class);
        Field host = World.class.getDeclaredField("host");
        host.setAccessible(true);
        host.set(world, player);
        Field players = World.class.getDeclaredField("players");
        players.setAccessible(true);
        // No scene participants are needed to execute the real opening time-lock command.
        players.set(world, List.of());
        world.changeTime(0);
        player.setWorld(world);
    }

    @AfterEach
    void restore() throws Exception {
        try {
            drainAsyncWork();
        } finally {
            datastoreField.set(null, savedDatastore);
            GAME_OPTIONS.questing.enabled = questingEnabled;
            GAME_OPTIONS.questing.triggerAllOnLogin = triggerAllOnLogin;
            SERVER.game.enableScriptInBigWorld = scriptsEnabled;
            GameData.getQuestDataMap().clear();
            GameData.getQuestDataMap().putAll(savedQuests);
            GameData.getMainQuestDataMap().clear();
            GameData.getMainQuestDataMap().putAll(savedMains);
            GameData.getBeginCondQuestMap().clear();
            GameData.getBeginCondQuestMap().putAll(savedConditions);
            GameData.getQuestTalkMap().clear();
            GameData.getQuestTalkMap().putAll(savedTalks);
            GameData.getTriggerExcelConfigDataMap().clear();
            GameData.getTriggerExcelConfigDataMap().putAll(savedTriggers);
            GameData.getRewindDataMap().clear();
            GameData.getRewindDataMap().putAll(savedRewinds);
        }
    }

    @Test
    void loginRestoresMissing352AfterTheReal35102PrerequisiteFinished() throws Exception {
        var previous = savedMain351(QuestState.QUEST_STATE_FINISHED);
        assertNull(player.getQuestManager().getMainQuestById(352));

        player.getQuestManager().onLogin();
        drainAsyncWork();

        var next = player.getQuestManager().getMainQuestById(352);
        assertNotNull(next);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, next.getChildQuestById(35200).getState());
        assertStartedPacket(35200, 352);
        assertTrue(savedEntities.contains(next), "the restored parent must be persisted");
        assertEquals(1, packetCount(PacketOpcodes.FinishedParentQuestUpdateNotify));

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertSame(next, player.getQuestManager().getMainQuestById(352));
        assertSame(previous, player.getQuestManager().getMainQuestById(351));
        assertEquals(1, packetCount(PacketOpcodes.FinishedParentQuestUpdateNotify));
        assertEquals(ParentQuestState.PARENT_QUEST_STATE_FINISHED, previous.getState());
        assertEquals(QuestState.QUEST_STATE_FINISHED, previous.getChildQuestById(35102).getState());
    }

    @Test
    void loginDoesNotRestore352When35102WasNotFinished() throws Exception {
        savedMain351(QuestState.QUEST_STATE_UNFINISHED);

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertNull(player.getQuestManager().getMainQuestById(352));
        assertTrue(questUpdates(35200).isEmpty());
    }

    @Test
    void loginChecksTheOtherAndConditionBeforeRestoring352() throws Exception {
        savedMain351(QuestState.QUEST_STATE_FINISHED);
        replaceOpeningConditions(
                LogicType.LOGIC_AND,
                condition(QuestCond.QUEST_COND_STATE_EQUAL, 35102, 3),
                condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 21));

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertNull(player.getQuestManager().getMainQuestById(352));
        assertTrue(questUpdates(35200).isEmpty());
    }

    @Test
    void loginDoesNotInventADynamicEventInAMixedOrCondition() throws Exception {
        savedMain351(QuestState.QUEST_STATE_FINISHED);
        replaceOpeningConditions(
                LogicType.LOGIC_OR,
                condition(QuestCond.QUEST_COND_STATE_EQUAL, 35102, 3),
                condition(QuestCond.QUEST_COND_LUA_NOTIFY, 960));

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertNull(player.getQuestManager().getMainQuestById(352));
        assertTrue(questUpdates(35200).isEmpty());
    }

    @Test
    void satisfiedLevelAndOpenStateWithoutASavedPredecessorDoNotCreateAParent() throws Exception {
        var data = new JsonObject();
        data.addProperty("subId", 9600101);
        data.addProperty("mainId", 96001);
        data.addProperty("order", 1);
        data.addProperty("acceptCondComb", LogicType.LOGIC_AND.name());
        data.add(
                "acceptCond",
                JsonUtils.toJson(
                        List.of(
                                condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18),
                                condition(QuestCond.QUEST_COND_OPEN_STATE_EQUAL, 960, 1))));
        for (String field :
                List.of("finishCond", "failCond", "beginExec", "finishExec", "failExec")) {
            data.add(field, new JsonArray());
        }
        var quest = JsonUtils.decode(data, QuestData.class);
        GameData.getQuestDataMap().put(quest.getId(), quest);
        quest.onLoad();
        GameData.getMainQuestDataMap()
                .put(
                        96001,
                        JsonUtils.decode(
                                "{\"id\":96001,\"subQuests\":[{\"subId\":9600101,\"order\":1}]}",
                                MainQuestData.class));
        player.getOpenStates().put(960, 1);

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertNull(player.getQuestManager().getMainQuestById(96001));
        assertTrue(questUpdates(9600101).isEmpty());
    }

    @Test
    void finishingTheRealOpeningStarts35100AndSendsItsClientUpdate() throws Exception {
        var main = new GameMainQuest(player, 351);
        player.getQuestManager().getMainQuests().put(351, main);
        var first = main.getChildQuestById(35104);
        first.setState(QuestState.QUEST_STATE_FINISHED);

        first.triggerStateEvents();
        drainAsyncWork();

        assertEquals(QuestState.QUEST_STATE_UNFINISHED, main.getChildQuestById(35100).getState());
        assertEquals(1, questUpdates(35100).size());
        assertStartedPacket(35100, 351);
        assertTrue(savedEntities.contains(main));
    }

    @ParameterizedTest
    @ValueSource(ints = {PrologueIntro.STAGE_PENDING, PrologueIntro.STAGE_RUNNING})
    void questLoginTransfersTheIntroStageBeforeRestoringItsNextStep(int stage) throws Exception {
        var main = new GameMainQuest(player, 351);
        player.getQuestManager().getMainQuests().put(351, main);
        main.getChildQuestById(35104).setState(QuestState.QUEST_STATE_FINISHED);
        player.setPrologueIntroStage(stage);

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertEquals(PrologueIntro.STAGE_NONE, player.getPrologueIntroStage());
        assertEquals(QuestState.QUEST_STATE_FINISHED, main.getChildQuestById(35104).getState());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, main.getChildQuestById(35100).getState());
        assertStartedPacket(35100, 351);
        assertEquals(1, player.saves);
    }

    @ParameterizedTest
    @ValueSource(ints = {PrologueIntro.STAGE_PENDING, PrologueIntro.STAGE_RUNNING})
    void questLoginInitializesTheMissingParentForAnUnfinishedStandaloneIntro(int stage)
            throws Exception {
        player.setPrologueIntroStage(stage);
        assertNull(player.getQuestManager().getMainQuestById(351));

        player.getQuestManager().onLogin();
        drainAsyncWork();

        var main = player.getQuestManager().getMainQuestById(351);
        assertNotNull(main);
        assertEquals(PrologueIntro.STAGE_NONE, player.getPrologueIntroStage());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, main.getChildQuestById(35104).getState());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED, main.getChildQuestById(35100).getState());
        assertStartedPacket(35104, 351);
        assertTrue(player.getWorld().isTimeLocked(), "the real opening begin-exec must run");
        assertTrue(savedEntities.contains(main));
        assertEquals(1, packetCount(PacketOpcodes.FinishedParentQuestUpdateNotify));
        assertEquals(1, player.saves);

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertSame(main, player.getQuestManager().getMainQuestById(351));
        assertEquals(1, player.getQuestManager().getMainQuests().size());
        assertEquals(1, packetCount(PacketOpcodes.FinishedParentQuestUpdateNotify));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, main.getChildQuestById(35104).getState());
        assertEquals(1, player.saves);
    }

    @Test
    void loginDoesNotReopenAnAlreadyCompletedNextParent() throws Exception {
        savedMain351(QuestState.QUEST_STATE_FINISHED);
        var next = new GameMainQuest(player, 352);
        next.getChildQuests().values().forEach(q -> q.setState(QuestState.QUEST_STATE_FINISHED));
        next.finishWithoutRewards();
        player.getQuestManager().getMainQuests().put(352, next);

        player.getQuestManager().onLogin();
        drainAsyncWork();

        assertSame(next, player.getQuestManager().getMainQuestById(352));
        assertEquals(ParentQuestState.PARENT_QUEST_STATE_FINISHED, next.getState());
        assertEquals(QuestState.QUEST_STATE_FINISHED, next.getChildQuestById(35200).getState());
        assertTrue(questUpdates(35200).isEmpty());
        assertEquals(0, packetCount(PacketOpcodes.FinishedParentQuestUpdateNotify));
    }

    private GameMainQuest savedMain351(QuestState predecessorState) {
        var previous = new GameMainQuest(player, 351);
        previous.getChildQuestById(35102).setState(predecessorState);
        previous.finishWithoutRewards();
        player.getQuestManager().getMainQuests().put(351, previous);
        return previous;
    }

    private void replaceOpeningConditions(LogicType logic, QuestAcceptCondition... conditions) {
        var json = JsonUtils.toJson(GameData.getQuestDataMap().get(35200)).getAsJsonObject();
        json.addProperty("acceptCondComb", logic.name());
        json.add("acceptCond", JsonUtils.toJson(List.of(conditions)));
        var replacement = JsonUtils.decode(json, QuestData.class);
        GameData.getQuestDataMap().put(35200, replacement);
        GameData.getBeginCondQuestMap().clear();
        GameData.getQuestDataMap().values().forEach(QuestData::onLoad);
    }

    private static QuestAcceptCondition condition(QuestCond type, int... params) {
        var condition = new QuestAcceptCondition();
        condition.setType(type);
        condition.setParam(params);
        return condition;
    }

    private void assertStartedPacket(int questId, int parentId) throws Exception {
        var updates = questUpdates(questId);
        assertFalse(updates.isEmpty(), "the next quest must be sent to the client");
        for (var quest : updates) {
            assertEquals(parentId, quest.getParentQuestId());
            assertEquals(QuestState.QUEST_STATE_UNFINISHED.getValue(), quest.getState());
            assertTrue(quest.getStartTime() > 0);
            assertEquals(quest.getStartTime(), quest.getAcceptTime());
        }
    }

    private List<Quest> questUpdates(int questId) throws Exception {
        var updates = new ArrayList<Quest>();
        for (var packet : player.packets) {
            if (packet.getOpcode() != PacketOpcodes.QuestListUpdateNotify) continue;
            QuestListUpdateNotify.parseFrom(packet.getData()).getQuestListList().stream()
                    .filter(quest -> quest.getQuestId() == questId)
                    .forEach(updates::add);
        }
        return updates;
    }

    private long packetCount(int opcode) {
        return player.packets.stream().filter(packet -> packet.getOpcode() == opcode).count();
    }

    private static void drainAsyncWork() throws Exception {
        // Startup submits checks to the general pool, which can submit more quest events.
        drain(Grasscutter.getThreadPool());
        drain(QuestManager.eventExecutor);
        drain(Grasscutter.getThreadPool());
        drain(QuestManager.eventExecutor);
        drain(DatabaseHelper.getEventExecutor());
    }

    private static void drain(ExecutorService executor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var pool = (ThreadPoolExecutor) executor;
        do {
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "asynchronous quest work did not become idle");
            executor.submit(() -> {}).get(remaining, TimeUnit.NANOSECONDS);
            Thread.yield();
        } while (pool.getActiveCount() != 0 || !pool.getQueue().isEmpty());
    }

    private static GameServer questServer() throws ReflectiveOperationException {
        var server = (GameServer) unsafe().allocateInstance(GameServer.class);
        Field questSystem = GameServer.class.getDeclaredField("questSystem");
        questSystem.setAccessible(true);
        questSystem.set(server, new QuestSystem(null));
        return server;
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static <T> T read(String path, Class<T> type) throws Exception {
        try (var input = QuestChainRecoveryTest.class.getResourceAsStream(FIXTURE + path)) {
            assertNotNull(input, "missing fixture " + path);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonUtils.loadToClass(reader, type);
            }
        }
    }

    private static <T> List<T> readList(String path, Class<T> type) throws Exception {
        try (var input = QuestChainRecoveryTest.class.getResourceAsStream(FIXTURE + path)) {
            assertNotNull(input, "missing fixture " + path);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonUtils.loadToList(reader, type);
            }
        }
    }

    private static final class RecordingPlayer extends Player {
        private final GameServer server;
        private final List<BasePacket> packets = new CopyOnWriteArrayList<>();
        private int saves;

        private RecordingPlayer(GameServer server) {
            this.server = server;
        }

        @Override
        public GameServer getServer() {
            return server;
        }

        @Override
        public int getLevel() {
            return 20;
        }

        @Override
        public void save() {
            saves++;
        }

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }
    }
}
