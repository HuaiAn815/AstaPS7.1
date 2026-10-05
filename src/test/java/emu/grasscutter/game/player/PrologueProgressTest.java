package emu.grasscutter.game.player;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.OpenStateData;
import emu.grasscutter.game.quest.PrologueIntro;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

@ExtendWith(ServerResourceFixture.class)
public final class PrologueProgressTest {
    private boolean questingEnabled;
    private final List<OpenStateData> states = new ArrayList<>();

    @BeforeEach
    void disableQuesting() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        GAME_OPTIONS.questing.enabled = false;
    }

    @AfterEach
    void restoreConfiguration() {
        GAME_OPTIONS.questing.enabled = questingEnabled;
        GameData.getOpenStateList().removeAll(states);
    }

    @Test
    void activeIntroKeepsQuestFeaturesAndStarterStatueLocked() {
        var player = player(PrologueIntro.STAGE_RUNNING);
        addState(930001, false, "OPEN_STATE_COND_QUEST", 35104);
        addState(930002, true, null, 0);

        player.getProgressManager().onPlayerLogin();

        assertEquals(0, player.getProgressManager().getOpenState(930001));
        assertEquals(0, player.getProgressManager().getOpenState(930002));
        assertEquals(0, player.getProgressManager().getOpenState(47));
        assertFalse(player.getUnlockedScenePoints(3).contains(7));
        assertTrue(player.getUnlockedSceneAreas(3).isEmpty());
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());
        assertNull(player.getQuestManager().getQuestById(35205));
    }

    @Test
    void pendingIntroAlsoKeepsParentQuestFeatureLocked() {
        var player = player(PrologueIntro.STAGE_PENDING);
        addState(930003, false, "OPEN_STATE_COND_PARENT_QUEST", 351);

        player.getProgressManager().onPlayerLogin();

        assertEquals(0, player.getProgressManager().getOpenState(930003));
        assertFalse(player.getUnlockedScenePoints(3).contains(7));
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());
    }

    @Test
    void completedIntroRestoresFeaturesWithoutUnlockingStarterStatue() {
        var player = player(PrologueIntro.STAGE_DONE);
        addState(930004, false, "OPEN_STATE_COND_QUEST", 35104);
        addState(930005, true, null, 0);

        player.getProgressManager().finishPrologueIntro();

        assertEquals(1, player.getProgressManager().getOpenState(930004));
        assertEquals(1, player.getProgressManager().getOpenState(930005));
        assertEquals(1, player.getProgressManager().getOpenState(47));
        assertEquals(1, player.getProgressManager().getOpenState(48));
        assertTrue(player.getUnlockedSceneAreas(3).contains(1));
        assertFalse(player.getUnlockedScenePoints(3).contains(7));
        assertTrue(player.getForgedStatueTalkQuests().isEmpty());

        player.getProgressManager().onPlayerLogin();
        assertFalse(player.getUnlockedScenePoints(3).contains(7));
        assertFalse(player.getForgedStatueTalkQuests().isEmpty());
    }

    @Test
    void legacyAccountKeepsRegularQuestingOffUnlocks() {
        var player = player(PrologueIntro.STAGE_NONE);
        addState(930006, false, "OPEN_STATE_COND_PARENT_QUEST", 351);
        addState(930007, true, null, 0);

        player.getProgressManager().onPlayerLogin();

        assertEquals(1, player.getProgressManager().getOpenState(930006));
        assertEquals(1, player.getProgressManager().getOpenState(930007));
        assertTrue(player.getUnlockedScenePoints(3).contains(7));
        assertTrue(player.getUnlockedSceneAreas(3).contains(1));
    }

    @Test
    void fullQuestingStillRequiresQuestConditionsAfterIntro() {
        GAME_OPTIONS.questing.enabled = true;
        var player = player(PrologueIntro.STAGE_DONE);
        addState(930008, false, "OPEN_STATE_COND_PARENT_QUEST", 351);

        player.getProgressManager().tryUnlockOpenStates(false);

        assertEquals(0, player.getProgressManager().getOpenState(930008));
    }

    private void addState(int id, boolean clientOpen, String conditionType, int param) {
        String conditions =
                conditionType == null
                        ? "[]"
                        : "[{\"condType\":\"" + conditionType + "\",\"param\":" + param + "}]";
        var state =
                JsonUtils.decode(
                        "{\"id\":"
                                + id
                                + ",\"allowClientOpen\":"
                                + clientOpen
                                + ",\"cond\":"
                                + conditions
                                + "}",
                        OpenStateData.class);
        states.add(state);
        GameData.getOpenStateList().add(state);
    }

    private Player player(int stage) {
        var player =
                new Player() {
                    @Override
                    public void save() {}
                };
        player.setPrologueIntroStage(stage);
        player.setSession(
                new GameSession(null) {
                    @Override
                    public void send(BasePacket packet) {}
                });
        return player;
    }
}
