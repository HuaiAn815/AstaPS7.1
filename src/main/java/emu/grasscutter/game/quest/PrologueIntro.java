package emu.grasscutter.game.quest;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.props.ElementType;
import emu.grasscutter.game.quest.enums.ParentQuestState;
import emu.grasscutter.game.quest.enums.QuestState;
import emu.grasscutter.server.packet.send.*;
import emu.grasscutter.utils.Utils;

import java.util.ArrayList;
import java.util.Set;

/** Plays the opening Paimon scene without enabling the complete quest system. */
public final class PrologueIntro {
    private static final Set<Integer> INTRO_MAIN_QUESTS = Set.of(351, 352);
    private static final Set<Integer> PROLOGUE_MAIN_QUESTS =
            Set.of(351, 352, 353, 354, 355, 356, 357);
    public static final int FIRST_QUEST = 35104;

    public static final int STAGE_NONE = 0;
    public static final int STAGE_PENDING = 1;
    public static final int STAGE_RUNNING = 2;
    public static final int STAGE_DONE = 3;

    private PrologueIntro() {}

    public static boolean isEnabled() {
        return GAME_OPTIONS.newAccountIntro.meetPaimon && !GAME_OPTIONS.questing.enabled;
    }

    public static boolean isActive(Player player) {
        int stage = player.getPrologueIntroStage();
        return stage == STAGE_PENDING || stage == STAGE_RUNNING;
    }

    public static boolean isIntroMainQuest(int mainQuestId) {
        return INTRO_MAIN_QUESTS.contains(mainQuestId);
    }

    public static void markNewAccount(Player player) {
        synchronized (player) {
            if (isEnabled() && player.getPrologueIntroStage() == STAGE_NONE) {
                player.setPrologueIntroStage(STAGE_PENDING);
            }
        }
    }

    public static void onLogin(Player player) {
        synchronized (player) {
            switch (player.getPrologueIntroStage()) {
                case STAGE_PENDING -> {
                    if (isEnabled()) start(player);
                    else conclude(player, "the intro was disabled before entering the world");
                }
                case STAGE_RUNNING ->
                        conclude(player, "the opening scene was interrupted on an earlier login");
                default -> {}
            }
        }
    }

    public static void onQuestFinished(GameQuest quest) {
        if (quest.getSubQuestId() != FIRST_QUEST) return;
        var player = quest.getOwner();
        synchronized (player) {
            if (player.getPrologueIntroStage() == STAGE_RUNNING) {
                conclude(player, "the opening scene finished");
            }
        }
    }

    public static boolean onClientPlotFinished(Player player, int plotId) {
        synchronized (player) {
            if (plotId != FIRST_QUEST || player.getPrologueIntroStage() != STAGE_RUNNING)
                return false;
            player.sendPacket(new PacketDelQuestNotify(FIRST_QUEST));
            conclude(player, "the opening scene finished");
            return true;
        }
    }

    public static boolean wentThrough(Player player) {
        return player.getPrologueIntroStage() != STAGE_NONE;
    }

    public static boolean allowAccept(Player player, QuestData questData) {
        return allowMainQuest(player, questData.getMainId());
    }

    public static boolean allowMainQuest(Player player, int mainQuestId) {
        if (GAME_OPTIONS.questing.enabled || !PROLOGUE_MAIN_QUESTS.contains(mainQuestId))
            return true;
        return isActive(player) && isIntroMainQuest(mainQuestId);
    }

    public static boolean isVisible(Player player, GameQuest quest) {
        return player.getPrologueIntroStage() == STAGE_RUNNING
                && isIntroMainQuest(quest.getMainQuestId());
    }

    public static boolean isVisible(Player player, GameMainQuest mainQuest) {
        return player.getPrologueIntroStage() == STAGE_RUNNING
                && isIntroMainQuest(mainQuest.getParentQuestId());
    }

    private static boolean hasIntroQuestData() {
        for (int mainId : INTRO_MAIN_QUESTS) {
            var mainData = GameData.getMainQuestDataMap().get(mainId);
            if (mainData == null
                    || mainData.getSubQuests() == null
                    || mainData.getSubQuests().length == 0) {
                return false;
            }
            for (var sub : mainData.getSubQuests()) {
                if (sub == null || GameData.getQuestDataMap().get(sub.getSubId()) == null)
                    return false;
            }
        }
        return GameData.getQuestDataMap().containsKey(FIRST_QUEST);
    }

    private static void start(Player player) {
        player.setPrologueIntroStage(STAGE_RUNNING);
        if (!hasIntroQuestData()) {
            conclude(player, "the prologue quests are missing");
            return;
        }

        var quest = player.getQuestManager().addQuestSilently(FIRST_QUEST);
        if (quest == null) {
            conclude(player, "the opening quest is unavailable");
            return;
        }
        player.save();
        // The login snapshot starts the client actor once, without server begin-execs or updates.
        quest.setAcceptTime(Utils.getCurrentSeconds());
        quest.setStartTime(quest.getAcceptTime());
        quest.setState(QuestState.QUEST_STATE_UNFINISHED);
        quest.save();
        Grasscutter.getLogger()
                .info("[intro] {} started the prologue (quest {}).", player.getUid(), FIRST_QUEST);
    }

    private static void conclude(Player player, String reason) {
        if (!isActive(player)) return;
        // Reject queued quest accepts before completing the saved opening quests.
        player.setPrologueIntroStage(STAGE_DONE);
        int marked = finishRemaining(player);

        var world = player.getWorld();
        if (world != null && world.isTimeLocked()) world.lockTime(false);

        var mainAvatar = player.getAvatars().getAvatarById(player.getMainCharacterId());
        if (mainAvatar != null
                && mainAvatar.getSkillDepot() != null
                && mainAvatar.getSkillDepot().getElementType() != ElementType.Wind) {
            mainAvatar.changeElement(ElementType.Wind, player.hasSentLoginPackets());
            mainAvatar.save();
        }

        player.getProgressManager().finishPrologueIntro();
        player.save();

        if (player.hasSentLoginPackets()) {
            player.sendPacket(new PacketOpenStateUpdateNotify(player));
            player.sendPacket(new PacketSceneForceUnlockNotify(1, true));
        }

        Grasscutter.getLogger()
                .info(
                        "[intro] {} finished the prologue intro: {} (marked {} step(s) finished).",
                        player.getUid(),
                        reason,
                        marked);
    }

    private static int finishRemaining(Player player) {
        var questManager = player.getQuestManager();
        var finished = new ArrayList<GameQuest>();
        var finishedMains = new ArrayList<GameMainQuest>();

        for (int mainId : INTRO_MAIN_QUESTS) {
            var mainQuest = questManager.getMainQuestById(mainId);
            if (mainQuest == null) {
                var mainData = GameData.getMainQuestDataMap().get(mainId);
                if (mainData == null || mainData.getSubQuests() == null) continue;
                for (var sub : mainData.getSubQuests()) {
                    if (sub != null && questManager.addQuestSilently(sub.getSubId()) != null) {
                        mainQuest = questManager.getMainQuestById(mainId);
                        break;
                    }
                }
            }
            if (mainQuest == null || mainQuest.getChildQuests() == null) continue;

            for (var quest : mainQuest.getChildQuests().values()) {
                if (quest.getState() == QuestState.QUEST_STATE_FINISHED) continue;
                quest.setState(QuestState.QUEST_STATE_FINISHED);
                quest.setFinishTime(Utils.getCurrentSeconds());
                quest.save();
                finished.add(quest);
            }
            if (mainQuest.getState() != ParentQuestState.PARENT_QUEST_STATE_FINISHED) {
                mainQuest.finishWithoutRewards();
                finishedMains.add(mainQuest);
            }
        }

        if (player.hasSentLoginPackets()) {
            if (!finished.isEmpty()) {
                player.sendPacket(
                        new PacketQuestListUpdateNotify(
                                finished.stream().map(GameQuest::toProto).toList()));
            }
            if (!finishedMains.isEmpty()) {
                player.sendPacket(new PacketFinishedParentQuestUpdateNotify(finishedMains));
            }
        }
        return finished.size();
    }
}
