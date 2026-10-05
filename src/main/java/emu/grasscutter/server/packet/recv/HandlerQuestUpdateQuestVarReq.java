package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.QuestUpdateQuestVarReqOuterClass.QuestUpdateQuestVarReq;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketQuestUpdateQuestVarRsp;

import java.util.Arrays;

@Opcodes(PacketOpcodes.QuestUpdateQuestVarReq)
public class HandlerQuestUpdateQuestVarReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = QuestUpdateQuestVarReq.parseFrom(payload);
        var questManager = session.getPlayer().getQuestManager();
        var subQuest = questManager.getQuestById(req.getQuestId());
        var mainQuest = questManager.getMainQuestById(req.getParentQuestId());
        if (mainQuest == null && subQuest != null) {
            mainQuest = subQuest.getMainQuest();
        }
        if (mainQuest == null
                || (req.getQuestId() > 0 && subQuest == null)
                || (subQuest != null && subQuest.getMainQuestId() != mainQuest.getParentQuestId())
                || (req.getParentQuestId() > 0
                        && req.getParentQuestId() != mainQuest.getParentQuestId())) {
            session.send(new PacketQuestUpdateQuestVarRsp(req, Retcode.RET_QUEST_NOT_EXIST));
            Grasscutter.getLogger()
                    .debug(
                            "trying to update QuestVar for non existing quest s{} m{}",
                            req.getQuestId(),
                            req.getParentQuestId());
            return;
        }
        // Validate the ordered batch before changing saved quest state.
        var nextVars = Arrays.copyOf(mainQuest.getQuestVars(), mainQuest.getQuestVars().length);
        for (var op : req.getQuestVarOpListList()) {
            int index = op.getIndex();
            if (index < 0 || index >= nextVars.length) {
                session.send(
                        new PacketQuestUpdateQuestVarRsp(req, Retcode.RET_QUEST_CONTENT_ERROR));
                return;
            }

            long next = op.getIsAdd() ? (long) nextVars[index] + op.getValue() : op.getValue();
            if (next < Integer.MIN_VALUE || next > Integer.MAX_VALUE) {
                session.send(
                        new PacketQuestUpdateQuestVarRsp(req, Retcode.RET_QUEST_CONTENT_ERROR));
                return;
            }
            nextVars[index] = (int) next;
        }

        for (var op : req.getQuestVarOpListList()) {
            if (op.getIsAdd()) {
                mainQuest.incQuestVar(op.getIndex(), op.getValue());
            } else {
                mainQuest.setQuestVar(op.getIndex(), op.getValue());
            }
        }
        if (!req.getQuestVarOpListList().isEmpty()) mainQuest.save();
        session.send(new PacketQuestUpdateQuestVarRsp(req));
    }
}
