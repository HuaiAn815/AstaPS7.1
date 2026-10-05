package emu.grasscutter.game.player;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.binout.ScenePointEntry;
import emu.grasscutter.data.common.PointData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ExtendWith(ServerResourceFixture.class)
public final class QuestStatueModeTest {
    private static final int POINT_ID = 65000;
    private static final int POINT_KEY = (3 << 16) + POINT_ID;
    private final Map<Integer, MainQuestData> savedMains = new HashMap<>();
    private final Map<Integer, QuestData> savedQuests = new HashMap<>();
    private boolean questingEnabled;
    private boolean scriptsEnabled;
    private ScenePointEntry savedPoint;
    private RecordingPlayer player;

    @BeforeEach
    void setup() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        player = new RecordingPlayer();
        quest(303, 30302);
        quest(303, 30303);
        quest(352, 35205);
        mainData(303, 30302);
        mainData(352, 35205);
        savedPoint = GameData.getScenePointEntryMap().get(POINT_KEY);
        var point =
                JsonUtils.decode(
                        "{\"id\":" + POINT_ID + ",\"areaId\":3,\"maxSpringVolume\":1}",
                        PointData.class);
        GameData.getScenePointEntryMap().put(POINT_KEY, new ScenePointEntry(3, point));
        player.getUnlockedScenePoints(3).add(POINT_ID);
    }

    @AfterEach
    void restore() throws Exception {
        QuestManager.eventExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        GAME_OPTIONS.questing.enabled = questingEnabled;
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
        if (savedPoint == null) GameData.getScenePointEntryMap().remove(POINT_KEY);
        else GameData.getScenePointEntryMap().put(POINT_KEY, savedPoint);
    }

    @Test
    void fullQuestingDoesNotSilentlyFinishExistingStatueOrStarterQuests() throws Exception {
        var statues = main(303);
        var starter = main(352);
        var progress = player.getProgressManager();

        addStatueQuestsOnLogin(progress);
        assertTrue(progress.buildForgedStatueTalkQuests().isEmpty());
        progress.refreshStatueTalkGate(3, POINT_ID);

        assertEquals(
                QuestState.QUEST_STATE_UNFINISHED, statues.getChildQuestById(30302).getState());
        assertEquals(
                QuestState.QUEST_STATE_UNFINISHED, starter.getChildQuestById(35205).getState());
        assertEquals(0, statues.saves);
        assertEquals(0, starter.saves);
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());
        assertTrue(player.getUnlockedSceneAreas(3).isEmpty());
        assertTrue(player.packets.isEmpty());
    }

    @Test
    void fullQuestingDoesNotCreateOrForgeMissingStatueQuests() throws Exception {
        var progress = player.getProgressManager();

        addStatueQuestsOnLogin(progress);
        assertTrue(progress.buildForgedStatueTalkQuests().isEmpty());
        progress.refreshStatueTalkGate(3, POINT_ID);

        assertTrue(player.getQuestManager().getMainQuests().isEmpty());
        assertNull(player.getQuestManager().getQuestById(30302));
        assertNull(player.getQuestManager().getQuestById(35205));
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());
        assertFalse(player.getUnlockedScenePoints(3).contains(7));
        assertTrue(player.packets.isEmpty());
    }

    @Test
    void questingOffLoginKeepsStatueAndStarterTalkConvenience() throws Exception {
        GAME_OPTIONS.questing.enabled = false;
        var statues = main(303);
        var starter = main(352);

        addStatueQuestsOnLogin(player.getProgressManager());

        assertEquals(QuestState.QUEST_STATE_FINISHED, statues.getChildQuestById(30302).getState());
        assertEquals(QuestState.QUEST_STATE_FINISHED, starter.getChildQuestById(35205).getState());
        assertEquals(1, statues.saves);
        assertEquals(1, starter.saves);
        assertTrue(player.getForgedStatueTalkQuests().contains(30303));
        assertNull(player.getQuestManager().getQuestById(30303));
        assertTrue(player.getUnlockedScenePoints(3).contains(7));
        assertEquals(1, questUpdateCount());
    }

    @Test
    void questingOffRefreshFinishesAnExistingGateOnce() {
        GAME_OPTIONS.questing.enabled = false;
        var statues = main(303);

        player.getProgressManager().refreshStatueTalkGate(3, POINT_ID);
        player.getProgressManager().refreshStatueTalkGate(3, POINT_ID);

        assertEquals(QuestState.QUEST_STATE_FINISHED, statues.getChildQuestById(30302).getState());
        assertEquals(1, statues.saves);
        assertEquals(1, questUpdateCount());
        assertTrue(player.getUnlockedSceneAreas(3).contains(3));
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());
    }

    @Test
    void questingOffRefreshForgesAMissingGateOnce() {
        GAME_OPTIONS.questing.enabled = false;

        player.getProgressManager().refreshStatueTalkGate(3, POINT_ID);
        player.getProgressManager().refreshStatueTalkGate(3, POINT_ID);

        assertNull(player.getQuestManager().getQuestById(30302));
        assertTrue(player.getForgedStatueTalkQuests().contains(30302));
        assertEquals(1, questUpdateCount());
        assertTrue(player.getUnlockedSceneAreas(3).contains(3));
    }

    private static void addStatueQuestsOnLogin(PlayerProgressManager progress) throws Exception {
        var method = PlayerProgressManager.class.getDeclaredMethod("addStatueQuestsOnLogin");
        method.setAccessible(true);
        method.invoke(progress);
    }

    private long questUpdateCount() {
        return player.packets.stream()
                .filter(packet -> packet.getOpcode() == PacketOpcodes.QuestListUpdateNotify)
                .count();
    }

    private void quest(int mainId, int subId) {
        savedQuests.put(subId, GameData.getQuestDataMap().get(subId));
        var data =
                JsonUtils.decode(
                        "{\"subId\":"
                                + subId
                                + ",\"mainId\":"
                                + mainId
                                + ",\"acceptCond\":[],\"finishCond\":[],\"failCond\":[]}",
                        QuestData.class);
        GameData.getQuestDataMap().put(subId, data);
    }

    private void mainData(int mainId, int subId) {
        savedMains.put(mainId, GameData.getMainQuestDataMap().get(mainId));
        GameData.getMainQuestDataMap()
                .put(
                        mainId,
                        JsonUtils.decode(
                                "{\"id\":" + mainId + ",\"subQuests\":[{\"subId\":" + subId + "}]}",
                                MainQuestData.class));
    }

    private RecordingMainQuest main(int id) {
        var main = new RecordingMainQuest(player, id);
        main.getChildQuests()
                .values()
                .forEach(quest -> quest.setState(QuestState.QUEST_STATE_UNFINISHED));
        player.getQuestManager().getMainQuests().put(id, main);
        return main;
    }

    private static final class RecordingPlayer extends Player {
        private final List<BasePacket> packets = new ArrayList<>();

        @Override
        public void save() {}

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }
    }

    private static final class RecordingMainQuest extends GameMainQuest {
        private int saves;

        private RecordingMainQuest(Player player, int id) {
            super(player, id);
        }

        @Override
        public void save() {
            saves++;
        }
    }
}
