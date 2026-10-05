package emu.grasscutter.server.packet.send;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.PrologueIntro;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.proto.FinishedParentQuestNotifyOuterClass.FinishedParentQuestNotify;
import emu.grasscutter.net.proto.GetScenePointRspOuterClass.GetScenePointRsp;
import emu.grasscutter.net.proto.QuestListNotifyOuterClass.QuestListNotify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ExtendWith(ServerResourceFixture.class)
public final class ProloguePacketsTest {
    private boolean questingEnabled;
    private boolean scriptsEnabled;

    @BeforeEach
    void disableQuesting() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        GAME_OPTIONS.questing.enabled = false;
        SERVER.game.enableScriptInBigWorld = true;
    }

    @AfterEach
    void restoreConfiguration() {
        GAME_OPTIONS.questing.enabled = questingEnabled;
        SERVER.game.enableScriptInBigWorld = scriptsEnabled;
    }

    @Test
    void runningIntroShowsOnlyItsUnfinishedQuestsAndFinishedHistory() throws Exception {
        var player = player(PrologueIntro.STAGE_RUNNING);

        assertEquals(Set.of(35104, 35201, 90001), questIds(player));
        assertEquals(Set.of(351, 352, 900), parentIds(player));
    }

    @Test
    void pendingCompletedAndLegacyStagesHideUnfinishedQuestHistory() throws Exception {
        for (int stage :
                new int[] {
                    PrologueIntro.STAGE_NONE, PrologueIntro.STAGE_PENDING, PrologueIntro.STAGE_DONE
                }) {
            var player = player(stage);

            assertEquals(Set.of(90001), questIds(player));
            assertEquals(Set.of(900), parentIds(player));
        }
    }

    @Test
    void fullQuestingShowsAllStartedQuestsButRespectsScriptAvailability() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        var player = player(PrologueIntro.STAGE_DONE);

        assertEquals(Set.of(35104, 35201, 35301, 90001, 90101), questIds(player));
        assertEquals(Set.of(351, 352, 353, 900), parentIds(player));

        SERVER.game.enableScriptInBigWorld = false;
        assertEquals(Set.of(90001), questIds(player));
        assertEquals(Set.of(900), parentIds(player));
    }

    @Test
    void activeIntroDoesNotInventAnUnlockedAreaOrStarterPoint() throws Exception {
        var player = new Player();
        player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);

        var active = GetScenePointRsp.parseFrom(new PacketGetScenePointRsp(player, 3).getData());
        assertTrue(active.getUnlockAreaListList().isEmpty());
        assertTrue(active.getUnlockedPointListList().isEmpty());

        player.setPrologueIntroStage(PrologueIntro.STAGE_DONE);
        var done = GetScenePointRsp.parseFrom(new PacketGetScenePointRsp(player, 3).getData());
        assertEquals(java.util.List.of(1), done.getUnlockAreaListList());
        assertTrue(done.getUnlockedPointListList().isEmpty());

        player.getUnlockedScenePoints(3).add(7);
        player.getUnlockedSceneAreas(3).add(2);
        var unlocked = GetScenePointRsp.parseFrom(new PacketGetScenePointRsp(player, 3).getData());
        assertEquals(java.util.List.of(7), unlocked.getUnlockedPointListList());
        assertEquals(java.util.List.of(2), unlocked.getUnlockAreaListList());
    }

    private Set<Integer> questIds(Player player) throws Exception {
        return QuestListNotify.parseFrom(new PacketQuestListNotify(player).getData())
                .getQuestListList()
                .stream()
                .map(q -> q.getQuestId())
                .collect(Collectors.toSet());
    }

    private Set<Integer> parentIds(Player player) throws Exception {
        return FinishedParentQuestNotify.parseFrom(
                        new PacketFinishedParentQuestNotify(player).getData())
                .getParentQuestListList()
                .stream()
                .map(q -> q.getParentQuestId())
                .collect(Collectors.toSet());
    }

    private Player player(int stage) {
        var player = new Player();
        player.setPrologueIntroStage(stage);
        addQuest(
                player,
                351,
                35104,
                QuestState.QUEST_STATE_UNFINISHED,
                ParentQuestState.PARENT_QUEST_STATE_NONE);
        addQuest(
                player,
                352,
                35201,
                QuestState.QUEST_STATE_UNFINISHED,
                ParentQuestState.PARENT_QUEST_STATE_NONE);
        addQuest(
                player,
                353,
                35301,
                QuestState.QUEST_STATE_UNFINISHED,
                ParentQuestState.PARENT_QUEST_STATE_NONE);
        addQuest(
                player,
                900,
                90001,
                QuestState.QUEST_STATE_FINISHED,
                ParentQuestState.PARENT_QUEST_STATE_FINISHED);
        addQuest(
                player,
                901,
                90101,
                QuestState.QUEST_STATE_UNFINISHED,
                ParentQuestState.PARENT_QUEST_STATE_CANCELED);
        addQuest(
                player,
                902,
                90201,
                QuestState.QUEST_STATE_UNSTARTED,
                ParentQuestState.PARENT_QUEST_STATE_CANCELED);
        return player;
    }

    private void addQuest(
            Player player,
            int mainId,
            int questId,
            QuestState state,
            ParentQuestState parentState) {
        var children = new HashMap<Integer, GameQuest>();
        var main =
                new GameMainQuest() {
                    @Override
                    public int getParentQuestId() {
                        return mainId;
                    }

                    @Override
                    public ParentQuestState getState() {
                        return parentState;
                    }

                    @Override
                    public boolean isFinished() {
                        return parentState == ParentQuestState.PARENT_QUEST_STATE_FINISHED;
                    }

                    @Override
                    public int[] getQuestVars() {
                        return new int[5];
                    }

                    @Override
                    public Map<Integer, GameQuest> getChildQuests() {
                        return children;
                    }
                };
        var quest =
                new GameQuest() {
                    @Override
                    public int getMainQuestId() {
                        return mainId;
                    }

                    @Override
                    public int getSubQuestId() {
                        return questId;
                    }
                };
        quest.setState(state);
        children.put(questId, quest);
        player.getQuestManager().getMainQuests().put(mainId, main);
    }
}
