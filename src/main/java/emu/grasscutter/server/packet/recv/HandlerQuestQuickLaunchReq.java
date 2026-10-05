package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.quest.PrologueIntro;
import emu.grasscutter.game.quest.QuestManager;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestQuickLaunchReq._QuestQuickLaunchReq;
import emu.grasscutter.net.proto.QuestQuickLaunchRsp._QuestQuickLaunchRsp;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;

/** Validates and starts a client-requested quest quick launch. */
@Opcodes(PacketOpcodes._QuestQuickLaunchReq)
public class HandlerQuestQuickLaunchReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = _QuestQuickLaunchReq.parseFrom(payload);
        Grasscutter.getLogger()
                .info(
                        "QuestQuickLaunchReq uid={} quest={} focus={}",
                        session.getPlayer().getUid(),
                        req.getQuestId(),
                        req.getIsEnterFocusMode());

        var player = session.getPlayer();
        var questManager = player.getQuestManager();
        int questId = req.getQuestId();
        Retcode retcode = Retcode.RET_SUCC;
        var questData = GameData.getQuestDataMap().get(questId);
        var mainData =
                questData == null
                        ? null
                        : GameData.getMainQuestDataMap().get(questData.getMainId());

        if (questData == null || mainData == null || mainData.getSubQuests() == null) {
            retcode = Retcode.RET_QUEST_NOT_EXIST;
        } else if (!java.util.Arrays.stream(mainData.getSubQuests())
                .filter(java.util.Objects::nonNull)
                .anyMatch(sub -> sub.getSubId() == questId)) {
            retcode = Retcode.RET_QUEST_NOT_EXIST;
        } else if (!QuestManager.isQuestingActive()
                || !PrologueIntro.allowMainQuest(player, questData.getMainId())) {
            retcode = Retcode.RET_QUEST_QUICK_LAUNCH_OPENSTATE_OFF;
        } else if (questManager.getMainQuestById(questData.getMainId()) != null
                && questManager.getMainQuestById(questData.getMainId()).getState()
                        == emu.grasscutter.game.quest.enums.ParentQuestState
                                .PARENT_QUEST_STATE_FINISHED) {
            retcode = Retcode.RET_QUEST_CONTENT_ERROR;
        } else {
            var existing = questManager.getQuestById(questId);
            if (existing != null && existing.getState() != QuestState.QUEST_STATE_UNSTARTED) {
                if (existing.getState() != QuestState.QUEST_STATE_FINISHED) {
                    retcode = Retcode.RET_QUEST_CONTENT_ERROR;
                }
            } else {
                try {
                    if (questManager.addQuest(questId) == null) {
                        retcode = Retcode.RET_QUEST_CONTENT_ERROR;
                    }
                } catch (RuntimeException t) {
                    retcode = Retcode.RET_QUEST_CONTENT_ERROR;
                    Grasscutter.getLogger()
                            .debug(
                                    "Quest quick launch failed for uid {} quest {}.",
                                    player.getUid(),
                                    questId,
                                    t);
                }
            }
        }

        var rsp =
                _QuestQuickLaunchRsp.newBuilder()
                        .setRetcode(retcode.getNumber())
                        .setQuestId(req.getQuestId())
                        .setIsEnterFocusMode(req.getIsEnterFocusMode())
                        .build();
        var packet = new BasePacket(PacketOpcodes._QuestQuickLaunchRsp);
        packet.setData(rsp);
        session.send(packet);
    }
}
