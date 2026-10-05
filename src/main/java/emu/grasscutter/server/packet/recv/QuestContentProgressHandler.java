package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.quest.PrologueIntro;
import emu.grasscutter.game.quest.enums.QuestContent;

/** Applies the two 7.1 quest-progress request shapes through one event path. */
final class QuestContentProgressHandler {
    private QuestContentProgressHandler() {}

    static boolean handle(Player player, int contentType, int param, long addProgress) {
        var type = QuestContent.getContentTriggerByValue(contentType);
        if (type == QuestContent.QUEST_CONTENT_NONE || type == QuestContent.QUEST_CONTENT_UNKNOWN)
            return false;

        if (type == QuestContent.QUEST_CONTENT_FINISH_PLOT
                && PrologueIntro.onClientPlotFinished(player, param)) {
            return true;
        }

        if (type == QuestContent.QUEST_CONTENT_ADD_QUEST_PROGRESS) {
            if (addProgress < 0 || addProgress > Integer.MAX_VALUE) {
                Grasscutter.getLogger()
                        .debug(
                                "Ignoring unsigned quest progress {} for uid {}.",
                                addProgress,
                                player.getUid());
                return true;
            }
            player.getProgressManager().addQuestProgress(param, (int) addProgress);
            return true;
        }

        player.getQuestManager().queueEvent(type, param);
        return true;
    }
}
