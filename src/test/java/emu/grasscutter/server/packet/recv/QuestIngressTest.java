package emu.grasscutter.server.packet.recv;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.GameMainQuest;
import emu.grasscutter.game.quest.GameQuest;
import emu.grasscutter.game.quest.PrologueIntro;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.net.proto.AddQuestContentProgressBatchReq._AddQuestContentProgressBatchReq;
import emu.grasscutter.net.proto.AddQuestContentProgressReqOuterClass.AddQuestContentProgressReq;
import emu.grasscutter.net.proto.QuestProgressInfo._QuestProgressInfo;
import emu.grasscutter.net.proto.QuestQuickLaunchReq._QuestQuickLaunchReq;
import emu.grasscutter.net.proto.QuestQuickLaunchRsp._QuestQuickLaunchRsp;
import emu.grasscutter.net.proto.QuestUpdateQuestVarReqOuterClass.QuestUpdateQuestVarReq;
import emu.grasscutter.net.proto.QuestUpdateQuestVarRspOuterClass.QuestUpdateQuestVarRsp;
import emu.grasscutter.net.proto.QuestVarOpOuterClass.QuestVarOp;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(ServerResourceFixture.class)
public final class QuestIngressTest {
    private final Map<Integer, MainQuestData> savedMains = new HashMap<>();
    private final Map<Integer, QuestData> savedQuests = new HashMap<>();
    private boolean questingEnabled;
    private boolean scriptsEnabled;

    @BeforeEach
    public void setUp() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        addMainData(990, 99001);
        addMainData(991, 99101);
        addMainData(351, PrologueIntro.FIRST_QUEST);
        addMainData(352, 35205);
    }

    @AfterEach
    public void restore() {
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
    }

    @Test
    public void unknownProgressTypeIsIgnored() {
        var player = new RecordingPlayer();
        assertFalse(QuestContentProgressHandler.handle(player, Integer.MAX_VALUE, 1, 1));
        assertEquals(0, player.saves);
        assertTrue(player.packets.isEmpty());
    }

    @Test
    public void addQuestProgressRejectsValuesOutsideStoredRange() {
        var player = new RecordingPlayer();
        assertTrue(
                QuestContentProgressHandler.handle(
                        player,
                        QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS.getValue(),
                        4001,
                        (long) Integer.MAX_VALUE + 1));
        assertEquals(-1, player.getPlayerProgress().getCurrentProgress("4001"));
        assertEquals(0, player.saves);
    }

    @Test
    public void addQuestProgressRejectsCumulativeOverflowWithoutSaving() {
        var player = new RecordingPlayer();
        player.getPlayerProgress().getQuestProgressCountMap().put("4001", Integer.MAX_VALUE);

        assertTrue(
                QuestContentProgressHandler.handle(
                        player, QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS.getValue(), 4001, 1));

        assertEquals(Integer.MAX_VALUE, player.getPlayerProgress().getCurrentProgress("4001"));
        assertEquals(0, player.saves);
        assertTrue(player.packets.isEmpty());
    }

    @Test
    public void questVarOperationsApplyInOrderAndPreserveSignedDeltas() throws Exception {
        var player = new RecordingPlayer();
        var main = addMain(player, 990);
        var session = new RecordingSession(player);
        var req =
                varRequest(990, 99001)
                        .addQuestVarOpList(op(0, 10, false))
                        .addQuestVarOpList(op(0, 5, true))
                        .addQuestVarOpList(op(0, -8, true))
                        .addQuestVarOpList(op(4, -3, false))
                        .addQuestVarOpList(op(4, 2, true))
                        .build();

        new HandlerQuestUpdateQuestVarReq().handle(session, new byte[0], req.toByteArray());

        assertArrayEquals(new int[] {7, 0, 0, 0, -1}, main.getQuestVars());
        assertEquals(List.of("0:10", "0:15", "0:7", "4:-3", "4:-1"), main.varEvents);
        assertEquals(1, main.saves);
        assertVarResponse(session, Retcode.RET_SUCC);
    }

    @Test
    public void invalidLaterQuestVarIndexRejectsTheWholeList() throws Exception {
        var player = new RecordingPlayer();
        var main = addMain(player, 990);
        main.getQuestVars()[0] = 12;
        var session = new RecordingSession(player);
        var req =
                varRequest(990, 99001)
                        .addQuestVarOpList(op(0, 99, false))
                        .addQuestVarOpList(op(5, 1, false))
                        .build();

        new HandlerQuestUpdateQuestVarReq().handle(session, new byte[0], req.toByteArray());

        assertArrayEquals(new int[] {12, 0, 0, 0, 0}, main.getQuestVars());
        assertTrue(main.varEvents.isEmpty());
        assertEquals(0, main.saves);
        assertVarResponse(session, Retcode.RET_QUEST_CONTENT_ERROR);
    }

    @Test
    public void overflowingQuestVarDeltaRejectsEarlierValidOperations() throws Exception {
        for (int initial : List.of(Integer.MAX_VALUE, Integer.MIN_VALUE)) {
            var player = new RecordingPlayer();
            var main = addMain(player, 990);
            main.getQuestVars()[0] = initial;
            var session = new RecordingSession(player);
            var req =
                    varRequest(990, 99001)
                            .addQuestVarOpList(op(1, 17, false))
                            .addQuestVarOpList(op(0, initial > 0 ? 1 : -1, true))
                            .build();

            new HandlerQuestUpdateQuestVarReq().handle(session, new byte[0], req.toByteArray());

            assertArrayEquals(new int[] {initial, 0, 0, 0, 0}, main.getQuestVars());
            assertTrue(main.varEvents.isEmpty());
            assertEquals(0, main.saves);
            assertVarResponse(session, Retcode.RET_QUEST_CONTENT_ERROR);
        }
    }

    @Test
    public void questVarUpdatesRejectMismatchedParentAndChild() throws Exception {
        var player = new RecordingPlayer();
        var main = addMain(player, 990);
        var other = addMain(player, 991);
        var session = new RecordingSession(player);
        var req = varRequest(991, 99001).addQuestVarOpList(op(0, 99, false)).build();

        new HandlerQuestUpdateQuestVarReq().handle(session, new byte[0], req.toByteArray());

        assertArrayEquals(new int[5], main.getQuestVars());
        assertArrayEquals(new int[5], other.getQuestVars());
        assertTrue(main.varEvents.isEmpty());
        assertTrue(other.varEvents.isEmpty());
        assertVarResponse(session, Retcode.RET_QUEST_NOT_EXIST);
    }

    @Test
    public void singleAndBatchRequestsAccumulateTheSameProgress() throws Exception {
        var single = new RecordingPlayer();
        var singleSession = new RecordingSession(single);
        var batch = new RecordingPlayer();
        var batchSession = new RecordingSession(batch);
        int type = QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS.getValue();

        for (int amount : List.of(2, 3)) {
            var req =
                    AddQuestContentProgressReq.newBuilder()
                            .setContentType(type)
                            .setParam(4001)
                            .setAddProgress(amount)
                            .build();
            new HandlerAddQuestContentProgressReq()
                    .handle(singleSession, new byte[0], req.toByteArray());
        }
        var req =
                _AddQuestContentProgressBatchReq.newBuilder()
                        .addProgressInfoList(progress(type, 4001, 2))
                        .addProgressInfoList(progress(type, 4001, 3))
                        .build();
        new HandlerAddQuestContentProgressBatchReq()
                .handle(batchSession, new byte[0], req.toByteArray());

        assertEquals(5, single.getPlayerProgress().getCurrentProgress("4001"));
        assertEquals(5, batch.getPlayerProgress().getCurrentProgress("4001"));
        assertEquals(2, single.saves);
        assertEquals(2, batch.saves);
        assertEquals(2, singleSession.sent.size());
        assertTrue(batchSession.sent.isEmpty());
    }

    @Test
    public void singleAndBatchRequestsUseTheSameOpeningCompletion() throws Exception {
        GAME_OPTIONS.questing.enabled = false;
        for (boolean batch : List.of(false, true)) {
            var player = new RecordingPlayer();
            var first = addMain(player, 351);
            var second = addMain(player, 352);
            first.getChildQuestById(PrologueIntro.FIRST_QUEST)
                    .setState(QuestState.QUEST_STATE_UNFINISHED);
            player.setPrologueIntroStage(PrologueIntro.STAGE_RUNNING);
            var session = new RecordingSession(player);
            int type = QuestContent.QUEST_CONTENT_FINISH_PLOT.getValue();

            if (batch) {
                var req =
                        _AddQuestContentProgressBatchReq.newBuilder()
                                .addProgressInfoList(progress(type, PrologueIntro.FIRST_QUEST, 1))
                                .build();
                new HandlerAddQuestContentProgressBatchReq()
                        .handle(session, new byte[0], req.toByteArray());
            } else {
                var req =
                        AddQuestContentProgressReq.newBuilder()
                                .setContentType(type)
                                .setParam(PrologueIntro.FIRST_QUEST)
                                .setAddProgress(1)
                                .build();
                new HandlerAddQuestContentProgressReq()
                        .handle(session, new byte[0], req.toByteArray());
            }

            assertEquals(PrologueIntro.STAGE_DONE, player.getPrologueIntroStage());
            assertTrue(first.isFinished());
            assertTrue(second.isFinished());
            assertEquals(
                    QuestState.QUEST_STATE_FINISHED,
                    first.getChildQuestById(PrologueIntro.FIRST_QUEST).getState());
            assertEquals(1, player.saves);
            assertEquals(
                    1,
                    player.packets.stream()
                            .filter(p -> p.getOpcode() == PacketOpcodes.QuestDelNotify)
                            .count());
        }
    }

    @Test
    public void quickLaunchDoesNotRestartExistingTasks() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        for (var state :
                List.of(
                        QuestState.QUEST_STATE_UNFINISHED,
                        QuestState.QUEST_STATE_FINISHED,
                        QuestState.QUEST_STATE_FAILED)) {
            var player = new RecordingPlayer();
            var main = addMain(player, 990);
            var quest = main.getChildQuestById(99001);
            quest.setState(state);
            var session = new RecordingSession(player);
            var req =
                    _QuestQuickLaunchReq.newBuilder()
                            .setQuestId(99001)
                            .setIsEnterFocusMode(true)
                            .build();

            new HandlerQuestQuickLaunchReq().handle(session, new byte[0], req.toByteArray());

            assertEquals(state, quest.getState());
            assertEquals(0, main.saves);
            assertEquals(1, session.sent.size());
            var rsp = _QuestQuickLaunchRsp.parseFrom(session.sent.get(0).getData());
            assertEquals(99001, rsp.getQuestId());
            assertTrue(rsp.getIsEnterFocusMode());
            assertEquals(
                    state == QuestState.QUEST_STATE_FINISHED
                            ? Retcode.RET_SUCC_VALUE
                            : Retcode.RET_QUEST_CONTENT_ERROR_VALUE,
                    rsp.getRetcode());
        }
    }

    @Test
    public void quickLaunchRejectsUnknownQuest() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        var player = new RecordingPlayer();

        var rsp = quickLaunch(player, Integer.MAX_VALUE);

        assertEquals(Retcode.RET_QUEST_NOT_EXIST_VALUE, rsp.getRetcode());
        assertTrue(player.getQuestManager().getMainQuests().isEmpty());
        assertEquals(0, player.saves);
    }

    @Test
    public void quickLaunchRejectsMissingMainResourceOrSubQuestList() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        for (boolean missingMain : List.of(true, false)) {
            if (missingMain) {
                GameData.getMainQuestDataMap().remove(990);
            } else {
                GameData.getMainQuestDataMap()
                        .put(990, JsonUtils.decode("{\"id\":990}", MainQuestData.class));
            }
            var player = new RecordingPlayer();

            var rsp = quickLaunch(player, 99001);

            assertEquals(Retcode.RET_QUEST_NOT_EXIST_VALUE, rsp.getRetcode());
            assertTrue(player.getQuestManager().getMainQuests().isEmpty());
            assertEquals(0, player.saves);
        }
    }

    @Test
    public void quickLaunchRejectsQuestOutsideDeclaredParentChildren() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        GameData.getMainQuestDataMap()
                .put(
                        990,
                        JsonUtils.decode(
                                "{\"id\":990,\"subQuests\":[{\"subId\":99101,\"order\":1}]}",
                                MainQuestData.class));
        var player = new RecordingPlayer();

        var rsp = quickLaunch(player, 99001);

        assertEquals(Retcode.RET_QUEST_NOT_EXIST_VALUE, rsp.getRetcode());
        assertTrue(player.getQuestManager().getMainQuests().isEmpty());
        assertEquals(0, player.saves);
    }

    @Test
    public void quickLaunchRejectsDisabledQuestingOrScripts() throws Exception {
        for (boolean disabledQuesting : List.of(true, false)) {
            GAME_OPTIONS.questing.enabled = !disabledQuesting;
            SERVER.game.enableScriptInBigWorld = disabledQuesting;
            var player = new RecordingPlayer();
            var main = addMain(player, 990);
            var quest = main.getChildQuestById(99001);

            var rsp = quickLaunch(player, 99001);

            assertEquals(Retcode.RET_QUEST_QUICK_LAUNCH_OPENSTATE_OFF_VALUE, rsp.getRetcode());
            assertEquals(QuestState.QUEST_STATE_UNSTARTED, quest.getState());
            assertEquals(0, main.saves);
        }
    }

    @Test
    public void quickLaunchRejectsFinishedParentWithUnstartedChild() throws Exception {
        GAME_OPTIONS.questing.enabled = true;
        SERVER.game.enableScriptInBigWorld = true;
        var player = new RecordingPlayer();
        var main = addMain(player, 990);
        var state = GameMainQuest.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(main, ParentQuestState.PARENT_QUEST_STATE_FINISHED);
        var quest = main.getChildQuestById(99001);

        var rsp = quickLaunch(player, 99001);

        assertEquals(Retcode.RET_QUEST_CONTENT_ERROR_VALUE, rsp.getRetcode());
        assertEquals(QuestState.QUEST_STATE_UNSTARTED, quest.getState());
        assertEquals(ParentQuestState.PARENT_QUEST_STATE_FINISHED, main.getState());
        assertEquals(0, main.saves);
    }

    private static _QuestQuickLaunchRsp quickLaunch(RecordingPlayer player, int questId)
            throws Exception {
        var session = new RecordingSession(player);
        var req =
                _QuestQuickLaunchReq.newBuilder()
                        .setQuestId(questId)
                        .setIsEnterFocusMode(true)
                        .build();

        new HandlerQuestQuickLaunchReq().handle(session, new byte[0], req.toByteArray());

        assertEquals(1, session.sent.size());
        var rsp = _QuestQuickLaunchRsp.parseFrom(session.sent.get(0).getData());
        assertEquals(questId, rsp.getQuestId());
        assertTrue(rsp.getIsEnterFocusMode());
        return rsp;
    }

    private static QuestUpdateQuestVarReq.Builder varRequest(int mainId, int questId) {
        return QuestUpdateQuestVarReq.newBuilder()
                .setParentQuestId(mainId)
                .setQuestId(questId)
                .setParentQuestVarSeq(123);
    }

    private static QuestVarOp op(int index, int value, boolean add) {
        return QuestVarOp.newBuilder().setIndex(index).setValue(value).setIsAdd(add).build();
    }

    private static _QuestProgressInfo progress(int type, int param, int amount) {
        return _QuestProgressInfo.newBuilder()
                .setContentType(type)
                .setParam(param)
                .setAddProgress(amount)
                .build();
    }

    private static void assertVarResponse(RecordingSession session, Retcode retcode)
            throws Exception {
        assertEquals(1, session.sent.size());
        var packet = session.sent.get(0);
        assertEquals(PacketOpcodes.QuestUpdateQuestVarRsp, packet.getOpcode());
        var rsp = QuestUpdateQuestVarRsp.parseFrom(packet.getData());
        assertEquals(retcode.getNumber(), rsp.getRetcode());
        assertEquals(123, rsp.getParentQuestVarSeq());
    }

    private RecordingMainQuest addMain(RecordingPlayer player, int mainId) {
        var main = new RecordingMainQuest(player, mainId);
        player.getQuestManager().getMainQuests().put(mainId, main);
        return main;
    }

    private void addMainData(int mainId, int questId) {
        savedMains.put(mainId, GameData.getMainQuestDataMap().get(mainId));
        savedQuests.put(questId, GameData.getQuestDataMap().get(questId));
        GameData.getMainQuestDataMap()
                .put(
                        mainId,
                        JsonUtils.decode(
                                "{\"id\":"
                                        + mainId
                                        + ",\"subQuests\":[{\"subId\":"
                                        + questId
                                        + ",\"order\":1}]}",
                                MainQuestData.class));
        GameData.getQuestDataMap()
                .put(
                        questId,
                        JsonUtils.decode(
                                "{\"subId\":"
                                        + questId
                                        + ",\"mainId\":"
                                        + mainId
                                        + ",\"acceptCond\":[],\"finishCond\":[],\"failCond\":[],\"beginExec\":[],\"finishExec\":[],\"gainItems\":[]}",
                                QuestData.class));
    }

    private static final class RecordingMainQuest extends GameMainQuest {
        private final List<String> varEvents = new ArrayList<>();
        private int saves;

        private RecordingMainQuest(Player player, int mainId) {
            super(player, mainId);
            getChildQuests()
                    .replaceAll(
                            (id, quest) ->
                                    new GameQuest(this, quest.getQuestData()) {
                                        @Override
                                        public void start() {
                                            org.junit.jupiter.api.Assertions.fail(
                                                    "Existing quests must not restart through quick"
                                                            + " launch");
                                        }
                                    });
        }

        @Override
        public void triggerQuestVarAction(int index, int value) {
            varEvents.add(index + ":" + value);
        }

        @Override
        public void save() {
            saves++;
        }
    }

    private static final class RecordingPlayer extends Player {
        private final List<BasePacket> packets = new ArrayList<>();
        private int saves;

        @Override
        public void save() {
            saves++;
        }

        @Override
        public void sendPacket(BasePacket packet) {
            packets.add(packet);
        }
    }

    private static final class RecordingSession extends GameSession {
        private final Player player;
        private final List<BasePacket> sent = new ArrayList<>();

        private RecordingSession(Player player) {
            super(null);
            this.player = player;
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public void send(BasePacket packet) {
            sent.add(packet);
        }
    }
}
