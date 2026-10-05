package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.data.excels.quest.QuestData.QuestAcceptCondition;
import emu.grasscutter.data.excels.quest.QuestData.QuestContentCondition;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.content.ContentUnlockArea;
import emu.grasscutter.game.quest.enums.LogicType;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestCond;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameServer;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(ServerResourceFixture.class)
public final class QuestRecoveryTest {
    private final Map<Integer, MainQuestData> savedMains = new HashMap<>();
    private final Map<Integer, QuestData> savedQuests = new HashMap<>();
    private final Map<String, List<QuestData>> savedConditions = new HashMap<>();
    private boolean questingEnabled;
    private boolean triggerAllOnLogin;
    private boolean scriptsEnabled;
    private RecordingPlayer player;

    @BeforeEach
    public void setup() throws ReflectiveOperationException {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        triggerAllOnLogin = GAME_OPTIONS.questing.triggerAllOnLogin;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = true;
        GAME_OPTIONS.questing.triggerAllOnLogin = false;
        SERVER.game.enableScriptInBigWorld = true;
        player = new RecordingPlayer(questServer());
        player.setUid(950001);
        player.level = 20;
    }

    @AfterEach
    public void restore() {
        GAME_OPTIONS.questing.enabled = questingEnabled;
        GAME_OPTIONS.questing.triggerAllOnLogin = triggerAllOnLogin;
        SERVER.game.enableScriptInBigWorld = scriptsEnabled;
        savedMains.forEach(
                (id, data) -> {
                    if (data == null) GameData.getMainQuestDataMap().remove(id.intValue());
                    else GameData.getMainQuestDataMap().put(id.intValue(), data);
                });
        savedQuests.forEach(
                (id, data) -> {
                    if (data == null) GameData.getQuestDataMap().remove(id.intValue());
                    else GameData.getQuestDataMap().put(id.intValue(), data);
                });
        savedConditions.forEach(
                (key, data) -> {
                    if (data == null) GameData.getBeginCondQuestMap().remove(key);
                    else GameData.getBeginCondQuestMap().put(key, data);
                });
    }

    @Test
    public void loginRecoversAlreadySatisfiedStateLevelAndOpenStateConditions() {
        var prior = quest(95001, 9500101, LogicType.LOGIC_AND);
        var next =
                quest(
                        95001,
                        9500102,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_STATE_EQUAL, prior.getId(), 3),
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18),
                        condition(QuestCond.QUEST_COND_OPEN_STATE_EQUAL, 951, 1));
        var main = main(95001, prior, next);
        main.getChildQuestById(prior.getId()).setState(QuestState.QUEST_STATE_FINISHED);
        player.getOpenStates().put(951, 1);

        player.getQuestManager().onLogin();

        assertStartedOnce(next);
        assertEquals(
                QuestState.QUEST_STATE_FINISHED, main.getChildQuestById(prior.getId()).getState());
    }

    @Test
    public void loginDoesNotAcceptAnAndConditionWithAnUnmetSavedPrerequisite() {
        var data =
                quest(
                        95002,
                        9500201,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18),
                        condition(QuestCond.QUEST_COND_OPEN_STATE_EQUAL, 952, 1));
        main(95002, data);

        player.getQuestManager().onLogin();

        assertUnstarted(data);
    }

    @Test
    public void malformedStaticConditionsDoNotBlockOtherChildrenOnLogin() {
        var malformed =
                quest(
                        95020,
                        9502001,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_STATE_EQUAL, 9502002));
        var valid =
                quest(
                        95020,
                        9502002,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        main(95020, malformed, valid);

        assertDoesNotThrow(() -> player.getQuestManager().onLogin());

        assertUnstarted(malformed);
        assertStartedOnce(valid);
    }

    @Test
    public void loginDoesNotInventDynamicEventsEvenInAMixedOrCondition() {
        var data =
                quest(
                        95003,
                        9500301,
                        LogicType.LOGIC_OR,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18),
                        condition(QuestCond.QUEST_COND_LUA_NOTIFY, 953));
        main(95003, data);

        player.getQuestManager().onLogin();

        assertUnstarted(data);
    }

    @Test
    public void loginDoesNotInvertAnUnfulfilledConditionIntoAQuestStart() {
        var data =
                quest(
                        95015,
                        9501501,
                        LogicType.LOGIC_NOT,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 30));
        main(95015, data);

        player.getQuestManager().onLogin();

        assertUnstarted(data);
    }

    @Test
    public void loginDoesNotReopenFinishedOrMissingParents() {
        var finished =
                quest(
                        95004,
                        9500401,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        main(95004, finished).finishWithoutRewards();
        var absent =
                quest(
                        95005,
                        9500501,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        var missingResource =
                quest(
                        95014,
                        9501401,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        main(95014, missingResource);
        GameData.getMainQuestDataMap().remove(missingResource.getMainId());

        player.getQuestManager().onLogin();
        player.getQuestManager()
                .triggerEvent(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, "", 20);

        assertUnstarted(finished);
        assertUnstarted(missingResource);
        assertNull(player.getQuestManager().getMainQuestById(absent.getMainId()));
        assertEquals(
                ParentQuestState.PARENT_QUEST_STATE_FINISHED,
                player.getQuestManager().getMainQuestById(finished.getMainId()).getState());
    }

    @Test
    public void loginDoesNotRestartExistingUnfinishedOrFinishedChildren() {
        var active =
                quest(
                        95006,
                        9500601,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        var finished =
                quest(
                        95006,
                        9500602,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        var main = main(95006, active, finished);
        var activeQuest = main.getChildQuestById(active.getId());
        activeQuest.setState(QuestState.QUEST_STATE_UNFINISHED);
        activeQuest.setStartTime(123);
        main.getChildQuestById(finished.getId()).setState(QuestState.QUEST_STATE_FINISHED);

        player.getQuestManager().onLogin();

        assertEquals(0, recorded(active).starts);
        assertEquals(123, activeQuest.getStartTime());
        assertEquals(0, recorded(finished).starts);
        assertEquals(QuestState.QUEST_STATE_FINISHED, recorded(finished).getState());
    }

    @Test
    public void loginEvaluatesAPassBeforeStartingItsAcceptedChildren() {
        var first =
                quest(
                        95007,
                        9500701,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        var second =
                quest(
                        95007,
                        9500702,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_STATE_EQUAL, first.getId(), 2));
        main(95007, first, second);

        player.getQuestManager().onLogin();

        assertStartedOnce(first);
        assertUnstarted(second);

        player.getQuestManager().onLogin();

        assertStartedOnce(first);
        assertStartedOnce(second);
    }

    @Test
    public void levelEventsFindThresholdsBelowTheNewLevel() {
        var eligible =
                quest(
                        95008,
                        9500801,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18));
        var later =
                quest(
                        95008,
                        9500802,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 25));
        main(95008, eligible, later);

        player.getQuestManager()
                .triggerEvent(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, "", 20);

        assertStartedOnce(eligible);
        assertUnstarted(later);
    }

    @Test
    public void stateEventsReevaluateTheOtherPersistedConditionsInAnAnd() {
        var prior = quest(95009, 9500901, LogicType.LOGIC_AND);
        var next =
                quest(
                        95009,
                        9500902,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_PLAYER_LEVEL_EQUAL_GREATER, 18),
                        condition(QuestCond.QUEST_COND_STATE_EQUAL, prior.getId(), 3));
        var main = main(95009, prior, next);
        main.getChildQuestById(prior.getId()).setState(QuestState.QUEST_STATE_FINISHED);

        player.getQuestManager()
                .triggerEvent(QuestCond.QUEST_COND_STATE_EQUAL, "", prior.getId(), 3);

        assertStartedOnce(next);
        assertArrayEquals(
                new int[] {1, 1},
                player.getQuestManager().getAcceptProgressLists().get(next.getId()));
    }

    @Test
    public void stateEventsDoNotSatisfyAnUnrelatedDynamicCondition() {
        var prior = quest(95010, 9501001, LogicType.LOGIC_AND);
        var next =
                quest(
                        95010,
                        9501002,
                        LogicType.LOGIC_AND,
                        condition(QuestCond.QUEST_COND_STATE_EQUAL, prior.getId(), 3),
                        condition(QuestCond.QUEST_COND_LUA_NOTIFY, 954));
        var main = main(95010, prior, next);
        main.getChildQuestById(prior.getId()).setState(QuestState.QUEST_STATE_FINISHED);

        player.getQuestManager()
                .triggerEvent(QuestCond.QUEST_COND_STATE_EQUAL, "", prior.getId(), 3);

        assertUnstarted(next);
        assertArrayEquals(
                new int[] {1, 0},
                player.getQuestManager().getAcceptProgressLists().get(next.getId()));
    }

    @Test
    public void rebindingAPersistedMainQuestRestoresItsCurrentManager() {
        var main = main(95011, quest(95011, 9501101, LogicType.LOGIC_AND));
        var replacement = new RecordingPlayer(player.getServer());
        replacement.setUid(player.getUid());

        main.setOwner(replacement);

        assertSame(replacement, main.getOwner());
        assertSame(replacement.getQuestManager(), main.getQuestManager());
        var stranger = new RecordingPlayer(player.getServer());
        stranger.setUid(player.getUid() + 1);
        main.setOwner(stranger);
        assertSame(replacement, main.getOwner());
        assertSame(replacement.getQuestManager(), main.getQuestManager());
    }

    @Test
    public void rebindingAddsResourceChildrenWithoutReplacingSavedOrUnknownChildren()
            throws ReflectiveOperationException {
        var original = quest(95013, 9501301, LogicType.LOGIC_AND);
        var main = main(95013, original);
        var saved = main.getChildQuestById(original.getId());
        saved.setState(QuestState.QUEST_STATE_FINISHED);
        saved.setFinishTime(321);
        var unavailable = unavailableQuest(main, 9501302);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);
        var added = quest(95013, 9501303, LogicType.LOGIC_AND);
        var data = new JsonObject();
        data.addProperty("id", 95013);
        data.add(
                "subQuests",
                JsonUtils.toJson(GameData.getMainQuestDataMap().get(95013).getSubQuests()));
        var child = new JsonObject();
        child.addProperty("subId", added.getId());
        child.addProperty("order", added.getOrder());
        data.getAsJsonArray("subQuests").add(child);
        GameData.getMainQuestDataMap().put(95013, JsonUtils.decode(data, MainQuestData.class));

        main.setOwner(player);

        assertSame(saved, main.getChildQuestById(original.getId()));
        assertEquals(QuestState.QUEST_STATE_FINISHED, saved.getState());
        assertEquals(321, saved.getFinishTime());
        assertSame(unavailable, main.getChildQuestById(unavailable.getSubQuestId()));
        assertNotNull(main.getChildQuestById(added.getId()));
        assertEquals(
                QuestState.QUEST_STATE_UNSTARTED, main.getChildQuestById(added.getId()).getState());
        assertTrue(
                main.toProto(true).getChildQuestListList().stream()
                        .anyMatch(
                                childQuest ->
                                        childQuest.getQuestId() == original.getId()
                                                && childQuest.getState()
                                                        == QuestState.QUEST_STATE_FINISHED
                                                                .getValue()));
    }

    @Test
    public void missingChildResourcesRemainStoredWithoutBlockingValidChildEvents()
            throws ReflectiveOperationException {
        var data =
                questWithContent(
                        95012,
                        9501201,
                        LogicType.LOGIC_AND,
                        List.of(content(QuestContent.QUEST_CONTENT_UNLOCK_AREA, 3, 1)),
                        List.of(content(QuestContent.QUEST_CONTENT_UNLOCK_AREA, 3, 1)));
        var main = main(95012, data);
        var valid = recorded(data);
        valid.setState(QuestState.QUEST_STATE_UNFINISHED);
        var unavailable = unavailableQuest(main, 9501202);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);

        assertEquals(List.of(valid), main.getActiveQuests());
        assertDoesNotThrow(main::checkProgress);
        main.tryFailSubQuests(QuestContent.QUEST_CONTENT_UNLOCK_AREA, "", 4, 1);
        main.tryFinishSubQuests(QuestContent.QUEST_CONTENT_UNLOCK_AREA, "", 4, 1);

        assertEquals(1, valid.failUpdates);
        assertEquals(1, valid.finishUpdates);
        assertSame(unavailable, main.getChildQuests().get(unavailable.getSubQuestId()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, unavailable.getState());
    }

    @Test
    public void activeQuestIterationSkipsStoredChildrenWithoutResources()
            throws ReflectiveOperationException {
        var validData = quest(95021, 9502101, LogicType.LOGIC_AND);
        var finishedData = quest(95021, 9502102, LogicType.LOGIC_AND);
        var main = main(95021, validData, finishedData);
        var valid = recorded(validData);
        valid.setState(QuestState.QUEST_STATE_UNFINISHED);
        recorded(finishedData).setState(QuestState.QUEST_STATE_FINISHED);
        var unavailable = unavailableQuest(main, 9502103);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);
        var visitedIds = new ArrayList<Integer>();

        assertDoesNotThrow(
                () ->
                        player.getQuestManager()
                                .forEachActiveQuest(
                                        child -> visitedIds.add(child.getQuestData().getId())));

        assertEquals(List.of(validData.getId()), visitedIds);
        assertSame(unavailable, main.getChildQuestById(unavailable.getSubQuestId()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, unavailable.getState());
    }

    @Test
    public void rewindingAChildWithoutResourcesLeavesItsSavedStateIntact()
            throws ReflectiveOperationException {
        var data = quest(95017, 9501701, LogicType.LOGIC_AND);
        var main = main(95017, data);
        var unavailable = unavailableQuest(main, 9501702);
        unavailable.setStartTime(123);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);

        assertFalse(unavailable.rewind(true));

        assertSame(unavailable, main.getChildQuestById(unavailable.getSubQuestId()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, unavailable.getState());
        assertEquals(123, unavailable.getStartTime());
        assertEquals(0, recorded(data).starts);
    }

    @Test
    public void rewindingResetsLaterValidChildrenAndPreservesUnavailableSiblings()
            throws ReflectiveOperationException {
        var earlierData = quest(95018, 9501801, LogicType.LOGIC_AND);
        var targetData = quest(95018, 9501802, LogicType.LOGIC_AND);
        var laterData = quest(95018, 9501803, LogicType.LOGIC_AND);
        var main = main(95018, earlierData, targetData, laterData);
        var earlier = new RewindRecordingQuest(main, earlierData);
        var target = new RewindRecordingQuest(main, targetData);
        var later = new RewindRecordingQuest(main, laterData);
        for (var child : List.of(earlier, target, later)) {
            child.setState(QuestState.QUEST_STATE_FINISHED);
            main.getChildQuests().put(child.getSubQuestId(), child);
        }
        var unavailable = unavailableQuest(main, 9501804);
        unavailable.setStartTime(123);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);

        assertTrue(target.rewind(true));

        assertEquals(0, earlier.clears);
        assertEquals(QuestState.QUEST_STATE_FINISHED, earlier.getState());
        assertEquals(1, target.clears);
        assertEquals(1, target.starts);
        assertTrue(target.lastNotifyDelete);
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, target.getState());
        assertEquals(1, later.clears);
        assertEquals(0, later.starts);
        assertTrue(later.lastNotifyDelete);
        assertEquals(QuestState.QUEST_STATE_UNSTARTED, later.getState());
        assertSame(unavailable, main.getChildQuestById(unavailable.getSubQuestId()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, unavailable.getState());
        assertEquals(123, unavailable.getStartTime());
    }

    @Test
    public void dungeonLookupSkipsUnavailableAndMalformedChildren()
            throws ReflectiveOperationException {
        var validData =
                questWithContent(
                        95019,
                        9501901,
                        LogicType.LOGIC_AND,
                        List.of(
                                content(QuestContent.QUEST_CONTENT_ENTER_DUNGEON, 951, 31),
                                content(QuestContent.QUEST_CONTENT_ENTER_DUNGEON, 952, 32)),
                        List.of());
        var missingConditionsData = quest(95019, 9501902, LogicType.LOGIC_AND);
        var shortParamsData =
                questWithContent(
                        95019,
                        9501903,
                        LogicType.LOGIC_AND,
                        List.of(content(QuestContent.QUEST_CONTENT_ENTER_DUNGEON, 953)),
                        List.of());
        var nullParamsData =
                questWithContent(
                        95019,
                        9501904,
                        LogicType.LOGIC_AND,
                        List.of(content(QuestContent.QUEST_CONTENT_ENTER_DUNGEON, (int[]) null)),
                        List.of());
        var main = main(95019, validData, missingConditionsData, shortParamsData, nullParamsData);
        main.getChildQuests()
                .values()
                .forEach(child -> child.setState(QuestState.QUEST_STATE_UNFINISHED));
        var unavailable = unavailableQuest(main, 9501905);
        main.getChildQuests().put(unavailable.getSubQuestId(), unavailable);
        var missingConditionsJson = JsonUtils.toJson(missingConditionsData).getAsJsonObject();
        missingConditionsJson.remove("finishCond");
        var missingConditions = main.getChildQuestById(missingConditionsData.getId());
        missingConditions.setConfig(JsonUtils.decode(missingConditionsJson, QuestData.class));
        var pointData = new PointData();
        pointData.setId(31);
        var point = new ScenePointEntry(3, pointData);

        assertEquals(List.of(951), player.getQuestManager().questsForDungeon(point));

        assertTrue(unavailable.getDungeonIds().isEmpty());
        assertTrue(missingConditions.getDungeonIds().isEmpty());
        assertTrue(main.getChildQuestById(shortParamsData.getId()).getDungeonIds().isEmpty());
        assertTrue(main.getChildQuestById(nullParamsData.getId()).getDungeonIds().isEmpty());
        assertSame(unavailable, main.getChildQuestById(unavailable.getSubQuestId()));
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, unavailable.getState());
    }

    @Test
    public void areaUnlockContentRequiresTheSceneAndAreaPair() {
        var handler = new ContentUnlockArea();
        var condition = content(QuestContent.QUEST_CONTENT_UNLOCK_AREA, 3, 1);

        assertTrue(handler.execute(null, condition, "", 3, 1));
        assertFalse(handler.execute(null, condition, "", 4, 1));
        assertFalse(handler.execute(null, condition, "", 3, 2));
        assertFalse(handler.execute(null, condition, "", 3));
        assertFalse(
                handler.execute(
                        null, content(QuestContent.QUEST_CONTENT_UNLOCK_AREA, 3), "", 3, 1));
    }

    @Test
    public void questVariableMutationsSaveTheirValuesBeforeSendingNotifications() throws Exception {
        var main = main(95016, quest(95016, 9501601, LogicType.LOGIC_AND));
        for (var type :
                List.of(
                        QuestCond.QUEST_COND_QUEST_VAR_EQUAL,
                        QuestCond.QUEST_COND_QUEST_VAR_GREATER,
                        QuestCond.QUEST_COND_QUEST_VAR_LESS)) {
            var key = QuestData.questConditionKey(type, 1, "");
            rememberCache(key);
            GameData.getBeginCondQuestMap().remove(key);
        }

        main.setQuestVar(1, 7);
        main.incQuestVar(1, 2);
        main.decQuestVar(1, 1);
        main.randomQuestVar(1, 8, 9);
        QuestManager.eventExecutor.submit(() -> {}).get();

        assertEquals(4, main.savedVars.size());
        assertEquals(List.of(7, 9, 8, 8), main.savedVars.stream().map(vars -> vars[1]).toList());
        assertEquals(8, main.getQuestVar(1));
        assertEquals(
                4,
                player.packets.stream()
                        .filter(
                                packet ->
                                        packet.getOpcode()
                                                == PacketOpcodes.QuestUpdateQuestVarNotify)
                        .count());
    }

    private QuestData quest(
            int mainId, int subId, LogicType logic, QuestAcceptCondition... conditions) {
        return questWithContent(mainId, subId, logic, List.of(), List.of(), conditions);
    }

    private QuestData questWithContent(
            int mainId,
            int subId,
            LogicType logic,
            List<QuestContentCondition> finish,
            List<QuestContentCondition> fail,
            QuestAcceptCondition... conditions) {
        if (!savedQuests.containsKey(subId)) {
            savedQuests.put(subId, GameData.getQuestDataMap().get(subId));
        }
        var data = new JsonObject();
        data.addProperty("subId", subId);
        data.addProperty("mainId", mainId);
        data.addProperty("order", subId);
        data.addProperty("acceptCondComb", logic.name());
        for (String field :
                List.of(
                        "acceptCond",
                        "finishCond",
                        "failCond",
                        "beginExec",
                        "finishExec",
                        "failExec")) {
            data.add(field, new JsonArray());
        }
        data.add("acceptCond", JsonUtils.toJson(List.of(conditions)));
        data.add("finishCond", JsonUtils.toJson(finish));
        data.add("failCond", JsonUtils.toJson(fail));
        var quest = JsonUtils.decode(data, QuestData.class);
        if (conditions.length == 0) {
            rememberCache(QuestData.questConditionKey(QuestCond.QUEST_COND_NONE, 0, null));
        } else {
            for (var condition : conditions) rememberCache(condition.asKey());
        }
        quest.onLoad();
        GameData.getQuestDataMap().put(subId, quest);
        return quest;
    }

    private void rememberCache(String key) {
        if (savedConditions.containsKey(key)) return;
        var previous = GameData.getBeginCondQuestMap().get(key);
        savedConditions.put(key, previous == null ? null : new ArrayList<>(previous));
    }

    private RecordingMainQuest main(int mainId, QuestData... quests) {
        if (!savedMains.containsKey(mainId)) {
            savedMains.put(mainId, GameData.getMainQuestDataMap().get(mainId));
        }
        var data = new JsonObject();
        data.addProperty("id", mainId);
        var children = new JsonArray();
        for (var quest : quests) {
            var child = new JsonObject();
            child.addProperty("subId", quest.getId());
            child.addProperty("order", quest.getOrder());
            children.add(child);
        }
        data.add("subQuests", children);
        GameData.getMainQuestDataMap().put(mainId, JsonUtils.decode(data, MainQuestData.class));
        var main = new RecordingMainQuest(player, mainId);
        player.getQuestManager().getMainQuests().put(mainId, main);
        return main;
    }

    private static QuestAcceptCondition condition(QuestCond type, int... params) {
        var condition = new QuestAcceptCondition();
        condition.setType(type);
        condition.setParam(params);
        return condition;
    }

    private static QuestContentCondition content(QuestContent type, int... params) {
        var condition = new QuestContentCondition();
        condition.setType(type);
        condition.setParam(params);
        return condition;
    }

    private static GameQuest unavailableQuest(GameMainQuest main, int id)
            throws ReflectiveOperationException {
        var quest = new GameQuest();
        quest.setMainQuest(main);
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        Field subId = GameQuest.class.getDeclaredField("subQuestId");
        subId.setAccessible(true);
        subId.setInt(quest, id);
        return quest;
    }

    private RecordingQuest recorded(QuestData data) {
        return (RecordingQuest) player.getQuestManager().getQuestById(data.getId());
    }

    private void assertStartedOnce(QuestData data) {
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, recorded(data).getState());
        assertEquals(1, recorded(data).starts);
    }

    private void assertUnstarted(QuestData data) {
        assertEquals(QuestState.QUEST_STATE_UNSTARTED, recorded(data).getState());
        assertEquals(0, recorded(data).starts);
    }

    private static GameServer questServer() throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (Unsafe) unsafeField.get(null);
        // Only the real quest system is needed; skip sockets and other game systems.
        var server = (GameServer) unsafe.allocateInstance(GameServer.class);
        Field questSystem = GameServer.class.getDeclaredField("questSystem");
        questSystem.setAccessible(true);
        questSystem.set(server, new QuestSystem(null));
        return server;
    }

    private static final class RecordingPlayer extends Player {
        private final GameServer server;
        private final List<BasePacket> packets = new ArrayList<>();
        private int level;

        private RecordingPlayer(GameServer server) {
            this.server = server;
        }

        @Override
        public GameServer getServer() {
            return server;
        }

        @Override
        public int getLevel() {
            return level;
        }

        @Override
        public void save() {}

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }
    }

    private static final class RecordingMainQuest extends GameMainQuest {
        private final List<int[]> savedVars = new ArrayList<>();

        private RecordingMainQuest(Player owner, int mainId) {
            super(owner, mainId);
            getChildQuests()
                    .replaceAll((id, quest) -> new RecordingQuest(this, quest.getQuestData()));
        }

        @Override
        public void save() {
            savedVars.add(getQuestVars().clone());
        }

        @Override
        public List<Position> rewind() {
            return null;
        }
    }

    private static final class RecordingQuest extends GameQuest {
        private int starts;
        private int finishUpdates;
        private int failUpdates;

        private RecordingQuest(GameMainQuest main, QuestData data) {
            super(main, data);
        }

        @Override
        public void start() {
            starts++;
            setState(QuestState.QUEST_STATE_UNFINISHED);
        }

        @Override
        public void setFinishProgress(int index, int value) {
            super.setFinishProgress(index, value);
            finishUpdates++;
        }

        @Override
        public void setFailProgress(int index, int value) {
            super.setFailProgress(index, value);
            failUpdates++;
        }
    }

    private static final class RewindRecordingQuest extends GameQuest {
        private int starts;
        private int clears;
        private boolean lastNotifyDelete;

        private RewindRecordingQuest(GameMainQuest main, QuestData data) {
            super(main, data);
        }

        @Override
        public void start() {
            starts++;
            setState(QuestState.QUEST_STATE_UNFINISHED);
        }

        @Override
        public boolean clearProgress(boolean notifyDelete) {
            clears++;
            lastNotifyDelete = notifyDelete;
            setState(QuestState.QUEST_STATE_UNSTARTED);
            return true;
        }
    }
}
