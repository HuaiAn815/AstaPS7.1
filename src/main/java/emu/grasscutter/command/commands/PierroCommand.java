package emu.grasscutter.command.commands;

import static emu.grasscutter.utils.lang.Language.translate;

import emu.grasscutter.command.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.server.packet.send.PacketAbilityChangeNotify;
import java.util.List;

/**
 * Turns Il Pierro's support button from the 7.1 Ronova fight on or off for a player's team.
 *
 * <p>The button is the team ability {@code Level_Ronova_SupportSkillHandler}: its SupportSkillMixin
 * draws Pierro's head on the team bar, and pressing it stops Ronova for three seconds and drops
 * gadget 42916022 on her. The quest that grants it in the official game is not in the resources,
 * so nothing here would ever add it on its own. It lasts until the player logs out.
 */
@Command(
        label = "pierro",
        aliases = {"ronova"},
        usage = {"[on|off]"},
        permission = "player.pierro",
        permissionTargeted = "player.pierro.others")
public final class PierroCommand implements CommandHandler {
    private static final String SUPPORT_SKILL = "Level_Ronova_SupportSkillHandler";

    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        var teamManager = targetPlayer.getTeamManager();
        var embryos = teamManager.getTeamAbilityEmbryos();

        boolean enable;
        if (args.isEmpty()) {
            enable = !embryos.contains(SUPPORT_SKILL);
        } else {
            switch (args.get(0).toLowerCase()) {
                case "on" -> enable = true;
                case "off" -> enable = false;
                default -> {
                    this.sendUsageMessage(sender);
                    return;
                }
            }
        }

        var team = teamManager.getEntity();
        if (enable) {
            embryos.add(SUPPORT_SKILL);
            if (team != null
                    && team.getInstancedAbilities().stream()
                            .noneMatch(
                                    a ->
                                            a != null
                                                    && a.getData() != null
                                                    && SUPPORT_SKILL.equals(a.getData().abilityName))) {
                targetPlayer.getAbilityManager().addAbilityToEntity(team, SUPPORT_SKILL);
            }
        } else {
            embryos.remove(SUPPORT_SKILL);
            if (team != null) {
                team.getInstancedAbilities()
                        .removeIf(
                                a ->
                                        a != null
                                                && a.getData() != null
                                                && SUPPORT_SKILL.equals(a.getData().abilityName));
            }
        }

        // The team's control block already lists teamAbilityEmbryos; resending it is what makes the
        // client draw or drop the button without a scene change.
        if (team != null) {
            targetPlayer.sendPacket(
                    new PacketAbilityChangeNotify(team.getId(), teamManager.getAbilityControlBlock()));
        }

        CommandHandler.sendMessage(
                sender, translate(sender, enable ? "commands.pierro.on" : "commands.pierro.off"));
    }
}
