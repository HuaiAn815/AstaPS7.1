package emu.grasscutter.command.commands;

import static emu.grasscutter.utils.lang.Language.translate;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.command.*;
import emu.grasscutter.data.GameData;
import emu.grasscutter.game.entity.EntityNPC;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Position;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Places an NPC model from NpcExcelConfigData in front of the player, facing them.
 *
 * <p>The NPC only shows its model: it has no dialogue and does not move, since nothing behind it
 * comes from a scene script. It stays until {@code /npc clear} or until the scene unloads.
 */
@Command(
        label = "npc",
        usage = {"<npcId>", "near [radius]", "clear"},
        permission = "server.npc",
        permissionTargeted = "server.npc.others")
public final class NpcCommand implements CommandHandler {
    /** How far in front of the player the NPC stands, in metres. */
    private static final double DISTANCE = 2.5;

    @Override
    public void execute(Player sender, Player targetPlayer, List<String> args) {
        if (args.isEmpty()) {
            this.sendUsageMessage(sender);
            return;
        }

        var scene = targetPlayer.getScene();
        if (scene == null) return;

        if (args.get(0).equalsIgnoreCase("near")) {
            this.listNearby(sender, targetPlayer, args);
            return;
        }

        if (args.get(0).equalsIgnoreCase("clear")) {
            var placed = new ArrayList<EntityNPC>();
            for (var entity : scene.getEntities().values()) {
                if (entity instanceof EntityNPC npc && npc.isStandalone()) placed.add(npc);
            }
            placed.forEach(scene::removeEntity);
            CommandHandler.sendMessage(
                    sender, translate(sender, "commands.npc.cleared", placed.size()));
            return;
        }

        int npcId;
        try {
            npcId = Integer.parseInt(args.get(0));
        } catch (NumberFormatException e) {
            this.sendUsageMessage(sender);
            return;
        }
        if (!GameData.getNpcDataMap().containsKey(npcId)) {
            CommandHandler.sendMessage(sender, translate(sender, "commands.npc.not_found", npcId));
            return;
        }

        // Rotation is in degrees about y; 0 faces +z.
        double yaw = Math.toRadians(targetPlayer.getRotation().getY());
        var pos =
                targetPlayer
                        .getPosition()
                        .clone()
                        .addX((float) (Math.sin(yaw) * DISTANCE))
                        .addZ((float) (Math.cos(yaw) * DISTANCE));
        var rot = new Position(0, (targetPlayer.getRotation().getY() + 180) % 360, 0);

        var npc = new EntityNPC(scene, npcId, pos, rot);
        // The client files NPCs under the scene block they belong to and drops one whose block it
        // does not have loaded; block 0 never is, so give it the block the player stands in.
        for (var block : scene.getLoadedBlocks()) {
            if (block.contains(pos)) {
                npc.setBlockId(block.id);
                break;
            }
        }
        scene.addEntity(npc);
        Grasscutter.getLogger()
                .info(
                        "[npc] placed npc {} as entity {} in scene {} block {} at {}",
                        npcId,
                        npc.getId(),
                        scene.getId(),
                        npc.getBlockId(),
                        pos);
        CommandHandler.sendMessage(sender, translate(sender, "commands.npc.spawned", npcId));
    }

    /**
     * Lists the NPCs the scene's spawn data places around the player, nearest first. The 7.1 text
     * map no longer names NPCs, so standing next to one is the practical way to learn its id.
     */
    private void listNearby(Player sender, Player targetPlayer, List<String> args) {
        float radius = 15f;
        if (args.size() > 1) {
            try {
                radius = Float.parseFloat(args.get(1));
            } catch (NumberFormatException e) {
                this.sendUsageMessage(sender);
                return;
            }
        }

        var data = GameData.getSceneNpcBornData().get(targetPlayer.getSceneId());
        var here = targetPlayer.getPosition();
        var lines = new ArrayList<String>();
        if (data != null && data.getBornPosList() != null) {
            final float r = radius;
            data.getBornPosList().stream()
                    .filter(e -> e.getPos() != null && e.getPos().computeDistance(here) <= r)
                    .sorted(Comparator.comparingDouble(e -> e.getPos().computeDistance(here)))
                    .limit(20)
                    .forEach(
                            e ->
                                    lines.add(
                                            "%d  %.1fm  group %d"
                                                    .formatted(
                                                            e.getId(),
                                                            e.getPos().computeDistance(here),
                                                            e.getGroupId())));
        }

        if (lines.isEmpty()) {
            CommandHandler.sendMessage(sender, translate(sender, "commands.npc.none_near", radius));
            return;
        }
        CommandHandler.sendMessage(sender, String.join("\n", lines));
    }
}
