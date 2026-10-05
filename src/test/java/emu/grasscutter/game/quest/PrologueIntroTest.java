package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.game.world.Position;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

@ExtendWith(ServerResourceFixture.class)
public final class PrologueIntroTest {
    private final Map<Integer, MainQuestData> savedMains = new HashMap<>();
    private final Map<Integer, QuestData> savedQuests = new HashMap<>();
    private boolean questingEnabled;
    private boolean meetPaimon;
    private RecordingPlayer player;

    @BeforeEach
    public void setup() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        meetPaimon = GAME_OPTIONS.newAccountIntro.meetPaimon;
        GAME_OPTIONS.questing.enabled = false;
        GAME_OPTIONS.newAccountIntro.meetPaimon = true;
        addMainData(351, 35104, 35105);
        addMainData(352, 35205);
        addMainData(353, 35301);
        player = new RecordingPlayer();
        for (int mainId : List.of(351, 352)) {
            var main = new RecordingMainQuest(player, mainId);
            player.getQuestManager().getMainQuests().put(mainId, main);
        }
    }

    @AfterEach
    public void restore() {
        GAME_OPTIONS.questing.enabled = questingEnabled;
        GAME_OPTIONS.newAccountIntro.meetPaimon = meetPaimon;
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
    }

    @Test
    public void newPlayerOpeningStartsOnlyThroughTheLoginSnapshot() {
        PrologueIntro.markNewAccount(player);
        assertTrue(PrologueIntro.isActive(player));

        player.getQuestManager().onLogin();
        PrologueIntro.onLogin(player);

        var quest = player.getQuestManager().getQuestById(PrologueIntro.FIRST_QUEST);
        assertEquals(PrologueIntro.STAGE_RUNNING, player.getPrologueIntroStage());
        assertEquals(QuestState.QUEST_STATE_UNFINISHED, quest.getState());
        assertTrue(quest.getStartTime() > 0);
        assertEquals(quest.getAcceptTime(), quest.getStartTime());
        assertTrue(player.packets.isEmpty());
        assertTrue(PrologueIntro.isVisible(player, quest));
        assertEquals(1, player.saves);
    }

    @Test
    public void oldAccountsDoNotAcquireAnOpeningSceneAtLogin() {
        PrologueIntro.onLogin(player);

        assertEquals(PrologueIntro.STAGE_NONE, player.getPrologueIntroStage());
        assertEquals(0, player.saves);
        assertTrue(player.packets.isEmpty());
        assertFalse(
                PrologueIntro.isVisible(player, player.getQuestManager().getMainQuestById(351)));
    }

    @Test
    public void interruptedOpeningCompletesWithoutRewindOrReplay() {
        player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);
        player.getQuestManager()
                .getQuestById(PrologueIntro.FIRST_QUEST)
                .setState(QuestState.QUEST_STATE_UNFINISHED);

        player.getQuestManager().onLogin();
        PrologueIntro.onLogin(player);
        assertOpeningFinished();
        assertEquals(1, player.saves);
        assertTrue(player.packets.isEmpty());

        PrologueIntro.onLogin(player);
        assertEquals(1, player.saves);
        assertFalse(PrologueIntro.onClientPlotFinished(player, PrologueIntro.FIRST_QUEST));
    }

    @Test
    public void onlyTheOpeningPlotCompletesTheIntroAndDuplicatePlotsAreIgnored() {
        player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);
        player.sentLoginPackets = true;
        assertFalse(PrologueIntro.onClientPlotFinished(player, 35205));
        assertTrue(player.packets.isEmpty());

        assertTrue(PrologueIntro.onClientPlotFinished(player, PrologueIntro.FIRST_QUEST));
        assertOpeningFinished();
        assertEquals(1, packetCount(PacketOpcodes.OpenStateUpdateNotify));
        assertEquals(1, packetCount(PacketOpcodes.SceneForceUnlockNotify));
        int packetsAfterFinishing = player.packets.size();

        assertFalse(PrologueIntro.onClientPlotFinished(player, PrologueIntro.FIRST_QUEST));
        PrologueIntro.onQuestFinished(
                player.getQuestManager().getQuestById(PrologueIntro.FIRST_QUEST));
        assertEquals(packetsAfterFinishing, player.packets.size());
        assertEquals(1, player.saves);
    }

    @Test
    public void disablingTheIntroAfterBirthSafelyCompletesThePendingState() {
        PrologueIntro.markNewAccount(player);
        GAME_OPTIONS.newAccountIntro.meetPaimon = false;

        PrologueIntro.onLogin(player);

        assertOpeningFinished();
        assertTrue(player.getUnlockedSceneAreas(3).contains(1));
    }

    @Test
    public void fullQuestingDoesNotStartTheStandaloneIntro() {
        GAME_OPTIONS.questing.enabled = true;

        PrologueIntro.markNewAccount(player);
        PrologueIntro.onLogin(player);

        assertEquals(PrologueIntro.STAGE_NONE, player.getPrologueIntroStage());
        assertTrue(PrologueIntro.allowMainQuest(player, 353));
        assertEquals(0, player.saves);
    }

    @Test
    public void missingQuestResourcesCompleteTheIntroInsteadOfBlockingLogin() {
        GameData.getQuestDataMap().remove(PrologueIntro.FIRST_QUEST);
        PrologueIntro.markNewAccount(player);

        assertDoesNotThrow(() -> PrologueIntro.onLogin(player));

        assertOpeningFinished();
        assertTrue(player.getUnlockedSceneAreas(3).contains(1));
    }

    @Test
    public void silentlyAddingAQuestWithIncompleteMainDataReturnsNull() {
        player.getQuestManager().getMainQuests().remove(351);
        GameData.getMainQuestDataMap()
                .put(351, JsonUtils.decode("{\"id\":351}", MainQuestData.class));
        assertNull(player.getQuestManager().addQuestSilently(PrologueIntro.FIRST_QUEST));
        GameData.getMainQuestDataMap().remove(351);
        assertNull(player.getQuestManager().addQuestSilently(PrologueIntro.FIRST_QUEST));
        assertTrue(player.packets.isEmpty());
    }

    @Test
    public void questHandoffsCannotStartTheRestOfThePrologueWhileQuestingIsDisabled() {
        for (int stage :
                List.of(
                        PrologueIntro.STAGE_NONE,
                        PrologueIntro.STAGE_RUNNING,
                        PrologueIntro.STAGE_DONE)) {
            player.setPrologueIntroStage(stage);
            assertFalse(PrologueIntro.allowMainQuest(player, 353));
            player.getQuestManager().startMainQuestIfUnlinked(353);
            assertNull(player.getQuestManager().getMainQuestById(353));
        }
        player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);
        assertTrue(PrologueIntro.allowMainQuest(player, 351));
        assertTrue(PrologueIntro.allowMainQuest(player, 352));
        assertTrue(PrologueIntro.allowMainQuest(player, 303));
        player.setPrologueIntroStage(PrologueIntro.STAGE_DONE);
        assertFalse(PrologueIntro.allowMainQuest(player, 351));
    }

    @Test
    public void concurrentPlotAndQuestCompletionUnlockOnlyOnce() throws Exception {
        player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);
        player.sentLoginPackets = true;
        var quest = player.getQuestManager().getQuestById(PrologueIntro.FIRST_QUEST);
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var plot =
                    executor.submit(
                            () -> {
                                ready.await();
                                return PrologueIntro.onClientPlotFinished(
                                        player, PrologueIntro.FIRST_QUEST);
                            });
            var finish =
                    executor.submit(
                            () -> {
                                ready.await();
                                PrologueIntro.onQuestFinished(quest);
                                return true;
                            });
            ready.countDown();
            plot.get();
            finish.get();
        }

        assertOpeningFinished();
        assertEquals(1, player.saves);
        assertEquals(1, packetCount(PacketOpcodes.OpenStateUpdateNotify));
        assertEquals(1, packetCount(PacketOpcodes.SceneForceUnlockNotify));
    }

    private long packetCount(int opcode) {
        return player.packets.stream().filter(packet -> packet.getOpcode() == opcode).count();
    }

    private void assertOpeningFinished() {
        assertEquals(PrologueIntro.STAGE_DONE, player.getPrologueIntroStage());
        for (int mainId : List.of(351, 352)) {
            var main = player.getQuestManager().getMainQuestById(mainId);
            assertTrue(main.isFinished());
            assertEquals(ParentQuestState.PARENT_QUEST_STATE_FINISHED, main.getState());
            for (var quest : main.getChildQuests().values()) {
                assertEquals(QuestState.QUEST_STATE_FINISHED, quest.getState());
            }
        }
    }

    private void addMainData(int mainId, int... subIds) {
        savedMains.put(mainId, GameData.getMainQuestDataMap().get(mainId));
        var children = new ArrayList<String>();
        for (int subId : subIds) {
            savedQuests.put(subId, GameData.getQuestDataMap().get(subId));
            var quest =
                    JsonUtils.decode(
                            "{\"subId\":"
                                    + subId
                                    + ",\"mainId\":"
                                    + mainId
                                    + ",\"acceptCond\":[{\"type\":\"QUEST_COND_STATE_EQUAL\",\"param\":[0,3]}],"
                                    + "\"finishCond\":[],\"failCond\":[],\"beginExec\":[],"
                                    + "\"finishExec\":[],\"gainItems\":[]}",
                            QuestData.class);
            GameData.getQuestDataMap().put(subId, quest);
            children.add("{\"subId\":" + subId + ",\"order\":1}");
        }
        var data =
                JsonUtils.decode(
                        "{\"id\":"
                                + mainId
                                + ",\"subQuests\":["
                                + String.join(",", children)
                                + "]}",
                        MainQuestData.class);
        GameData.getMainQuestDataMap().put(mainId, data);
    }

    private static final class RecordingPlayer extends Player {
        private final List<BasePacket> packets = new ArrayList<>();
        private int saves;
        private boolean sentLoginPackets;

        @Override
        public void save() {
            saves++;
        }

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }

        @Override
        public boolean hasSentLoginPackets() {
            return sentLoginPackets;
        }
    }

    private static final class RecordingMainQuest extends GameMainQuest {
        private RecordingMainQuest(Player owner, int mainId) {
            super(owner, mainId);
            getChildQuests()
                    .replaceAll(
                            (id, quest) ->
                                    new GameQuest(this, quest.getQuestData()) {
                                        @Override
                                        public void start() {
                                            Assertions.fail(
                                                    "The standalone intro must not execute normal"
                                                        + " quest startup");
                                        }

                                        @Override
                                        public void finish() {
                                            Assertions.fail(
                                                    "Intro completion must not execute normal quest"
                                                        + " rewards or scripts");
                                        }
                                    });
        }

        @Override
        public void save() {}

        @Override
        public List<Position> rewind() {
            Assertions.fail("An interrupted opening must not rewind before being concluded");
            return null;
        }

        @Override
        public void checkProgress() {
            Assertions.fail("An interrupted opening must not run quest progression at login");
        }
    }
}
