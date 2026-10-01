package emu.grasscutter.game.tps;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.common.FightPropData;
import emu.grasscutter.data.excels.EquipAffixData;
import emu.grasscutter.data.excels.tps.*;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.entity.EntityWeapon;
import emu.grasscutter.game.inventory.*;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.game.world.Scene;
import emu.grasscutter.net.proto.AbilityInvokeEntryOuterClass.AbilityInvokeEntry;
import emu.grasscutter.net.proto.AbilityMetaUpdateTpsWeaponAmmunitionOuterClass.AbilityMetaUpdateTpsWeaponAmmunition;
import emu.grasscutter.net.proto.AbilitySyncStateInfoOuterClass.AbilitySyncStateInfo;
import emu.grasscutter.net.proto.RetcodeOuterClass.Retcode;
import emu.grasscutter.net.proto.SceneWeaponInfoOuterClass.SceneWeaponInfo;
import emu.grasscutter.net.proto.TpsWeapon._TpsWeapon;
import emu.grasscutter.net.proto.TpsWeaponAmmunitionInfoOuterClass.TpsWeaponAmmunitionInfo;
import emu.grasscutter.server.packet.send.PacketTpsEquipChangeNotify;
import it.unimi.dsi.fastutil.ints.*;
import java.util.*;
import javax.annotation.Nullable;

/**
 * The 7.x third-person shooter weapons: guns and grenades an avatar wears next to its normal
 * weapon.
 *
 * <p>A worn TPS weapon is a weapon entity of its own (gadget 5001600x), listed in {@code
 * SceneAvatarInfo.tps_weapon_list} and {@code AvatarInfo.tps_weapon_list}, and its affixes'
 * openConfigs hand the avatar the {@code Avatar_TPS_*} abilities that aim, shoot and reload.
 * Ammunition is a reserve per TpsAmmunitionExcelConfigData id, shared by every slot it fills; the
 * client reports what it spends and picks up through {@code ABILITY_META_UPDATE_TPS_WEAPON_AMMUNITION}.
 */
public final class TpsWeaponSystem {
    /** CONST_VALUE_TPS_WEAPON_ITEM_LIMIT. */
    public static final int TPS_WEAPON_ITEM_LIMIT = 20;

    /** CONST_VALUE_TPS_SLOT_WEAR_NUM_LIMIT "1:2;2:1": two guns and one grenade. */
    private static final Int2IntMap WEAR_LIMIT = new Int2IntOpenHashMap(new int[] {1, 2}, new int[] {2, 1});

    private TpsWeaponSystem() {}

    public static boolean isTpsWeapon(@Nullable GameItem item) {
        return item != null
                && item.getItemData() != null
                && item.getItemType() == ItemType.ITEM_TPS_WEAPON;
    }

    /** The player's copy of a TPS weapon; there is at most one of each. */
    @Nullable public static GameItem findOwnedWeapon(Player player, int itemId) {
        for (GameItem item : player.getInventory().getItems().values()) {
            if (item.getItemId() == itemId && isTpsWeapon(item)) return item;
        }
        return null;
    }

    /** The TPS weapons the avatar wears, in wear order. Ids no longer in the bag are skipped. */
    public static List<GameItem> getWornWeapons(Avatar avatar) {
        var player = avatar.getPlayer();
        if (player == null || avatar.getTpsWeaponIds().isEmpty()) return List.of();

        var worn = new ArrayList<GameItem>(avatar.getTpsWeaponIds().size());
        for (int itemId : avatar.getTpsWeaponIds()) {
            var item = findOwnedWeapon(player, itemId);
            if (item != null) worn.add(item);
        }
        return worn;
    }

    /** Affix id to level: the weapon's base affix plus one per unlocked accessory. */
    public static Int2IntMap getAffixLevels(GameItem item) {
        var affixes = new Int2IntLinkedOpenHashMap();
        var weaponData = GameData.getTpsWeaponDataMap().get(item.getItemId());
        if (weaponData != null) {
            putAffix(affixes, weaponData.getEquipAffixId(), weaponData.getTpsWeaponBaseAffix());
        }
        for (int accessoryId : item.getTpsAccessoryIds()) {
            var accessory = GameData.getTpsWeaponAccessoryDataMap().get(accessoryId);
            if (accessory == null || accessory.getTpsWeaponId() != item.getItemId()) continue;
            putAffix(affixes, accessory.getEquipAffixId(), accessory.getTpsWeaponBaseAffix());
        }
        return affixes;
    }

    private static void putAffix(Int2IntMap affixes, int equipAffixId, List<Integer> baseAffixes) {
        if (equipAffixId > 0) {
            affixes.put(equipAffixId / 10, equipAffixId % 10);
        } else if (baseAffixes != null) {
            baseAffixes.stream().filter(id -> id > 0).forEach(id -> affixes.put((int) id, 0));
        }
    }

    public static _TpsWeapon toTpsWeaponProto(GameItem item) {
        return _TpsWeapon.newBuilder()
                .addAllAccessoryIdList(item.getTpsAccessoryIds())
                .putAllAffixMap(getAffixLevels(item))
                .build();
    }

    /** Gives the weapon an entity in the scene, reusing the one it has if it is already there. */
    public static void ensureWeaponEntity(GameItem item, @Nullable Scene scene) {
        if (scene == null || scene.getWorld() == null) return;
        var entity = item.getWeaponEntity();
        if (entity != null && entity.getScene() == scene) return;

        entity = new EntityWeapon(scene, item.getItemData().getGadgetId());
        item.setWeaponEntity(entity);
        scene.getWeaponEntities().put(entity.getId(), entity);
    }

    public static void ensureWeaponEntities(Avatar avatar, @Nullable Scene scene) {
        getWornWeapons(avatar).forEach(item -> ensureWeaponEntity(item, scene));
    }

    public static SceneWeaponInfo toSceneWeaponInfo(Player player, GameItem item) {
        var info =
                SceneWeaponInfo.newBuilder()
                        .setEntityId(item.getWeaponEntity() != null ? item.getWeaponEntity().getId() : 0)
                        .setGadgetId(item.getItemData().getGadgetId())
                        .setItemId(item.getItemId())
                        .setGuid(item.getGuid())
                        .setLevel(item.getLevel())
                        .setPromoteLevel(item.getPromoteLevel())
                        .putAllAffixMap(getAffixLevels(item))
                        .setAbilityInfo(AbilitySyncStateInfo.newBuilder().setIsInited(true));

        var weaponData = GameData.getTpsWeaponDataMap().get(item.getItemId());
        if (weaponData != null && weaponData.getAmmoSlotIds() != null) {
            for (int slotId : weaponData.getAmmoSlotIds()) {
                var ammunition = getAmmunitionForSlot(slotId);
                if (ammunition == null) continue;
                info.addAmmunitionList(
                        TpsWeaponAmmunitionInfo.newBuilder()
                                .setAmmunitionType(slotId)
                                .setAmmunitionConfigId(ammunition.getId())
                                .setCurrentAmmunition(getReserve(player, ammunition.getId())));
            }
        }
        return info.build();
    }

    /**
     * {@code SceneAvatarInfo.tps_weapon_list} / {@code AvatarInfo.tps_weapon_list}. Weapon entities
     * are made when the avatar enters a scene, so an off-field avatar lists its weapons with entity 0.
     */
    public static List<SceneWeaponInfo> getSceneWeaponInfos(Avatar avatar) {
        var player = avatar.getPlayer();
        if (player == null) return List.of();
        return getWornWeapons(avatar).stream().map(item -> toSceneWeaponInfo(player, item)).toList();
    }

    /**
     * Adds the stats and ability embryos of the worn TPS weapons. Called from {@link
     * Avatar#recalcStats}, which tells the client when the embryos change.
     */
    public static void applyAffixes(Avatar avatar) {
        for (GameItem item : getWornWeapons(avatar)) {
            for (var affix : getAffixLevels(item).int2IntEntrySet()) {
                EquipAffixData affixData =
                        GameData.getEquipAffixDataMap().get(affix.getIntKey() * 10 + affix.getIntValue());
                if (affixData == null) continue;
                if (affixData.getAddProps() != null) {
                    for (FightPropData prop : affixData.getAddProps()) {
                        if (prop.getProp() != null) avatar.addFightProperty(prop.getProp(), prop.getValue());
                    }
                }
                avatar.addToExtraAbilityEmbryos(affixData.getOpenConfig(), true);
            }
        }
    }

    /**
     * WearTpsEquipReq: the avatar now wears exactly {@code equipGuids}. Weapons are taken off
     * whoever wore them before. The avatar may be the TPS traveler, a trial avatar: its choice is
     * kept as the player's TPS loadout instead.
     *
     * @return a {@link Retcode} value.
     */
    public static int wear(Player player, long avatarGuid, List<Long> equipGuids) {
        var avatar = findAvatar(player, avatarGuid);
        if (avatar == null) return Retcode.RET_CAN_NOT_FIND_AVATAR_VALUE;

        var itemIds = new ArrayList<Integer>(equipGuids.size());
        var slotCounts = new Int2IntOpenHashMap();
        for (long guid : equipGuids) {
            var item = player.getInventory().getItemByGuid(guid);
            if (!isTpsWeapon(item)) return Retcode.RET_ITEM_NOT_EXIST_VALUE;
            if (itemIds.contains(item.getItemId())) continue;

            var weaponData = GameData.getTpsWeaponDataMap().get(item.getItemId());
            int slotType = weaponData != null ? weaponData.getWearSlotType() : 0;
            if (slotCounts.addTo(slotType, 1) + 1 > WEAR_LIMIT.getOrDefault(slotType, 0)) {
                return Retcode.RET_EQUIP_EXCEED_LIMIT_VALUE;
            }
            itemIds.add(item.getItemId());
        }

        for (Avatar other : player.getAvatars()) {
            if (other == avatar || !other.getTpsWeaponIds().removeIf(itemIds::contains)) continue;
            other.save();
            other.recalcStats();
            sendEquipChange(other);
        }

        var loadout = player.getTpsLoadout();
        if (TpsAvatarSystem.isTpsAvatar(avatar)) {
            loadout.clear();
            loadout.addAll(itemIds);
            player.save();
        } else if (loadout.removeIf(itemIds::contains)) {
            player.save();
        }

        avatar.getTpsWeaponIds().clear();
        avatar.getTpsWeaponIds().addAll(itemIds);
        if (avatar.getTrialAvatarId() == 0) avatar.save();
        if (avatar.getAsEntity() != null) {
            ensureWeaponEntities(avatar, avatar.getAsEntity().getScene());
        }
        avatar.recalcStats();
        sendEquipChange(avatar);
        return Retcode.RET_SUCC_VALUE;
    }

    /** An owned avatar, or one of the trial avatars in the current team. */
    @Nullable private static Avatar findAvatar(Player player, long avatarGuid) {
        var avatar = player.getAvatars().getAvatarByGuid(avatarGuid);
        if (avatar != null) return avatar;
        return player.getTeamManager().getTrialAvatars().values().stream()
                .filter(trial -> trial.getGuid() == avatarGuid)
                .findFirst()
                .orElse(null);
    }

    /** TpsEquipChangeNotify to everyone in the scene when the avatar is on the field. */
    public static void sendEquipChange(Avatar avatar) {
        var player = avatar.getPlayer();
        if (player == null || !player.hasSentLoginPackets()) return;

        var packet = new PacketTpsEquipChangeNotify(avatar, getSceneWeaponInfos(avatar));
        var entity = avatar.getAsEntity();
        if (entity != null && entity.getScene() != null) {
            entity.getScene().broadcastPacket(packet);
        } else {
            player.sendPacket(packet);
        }
    }

    @Nullable public static TpsAmmunitionData getAmmunitionForSlot(int slotId) {
        for (var ammunition : GameData.getTpsAmmunitionDataMap().values()) {
            if (ammunition.getAmmoSlotIds() != null && ammunition.getAmmoSlotIds().contains(slotId)) {
                return ammunition;
            }
        }
        return null;
    }

    /** Reserve of one ammunition. A player who never used it has a full reserve. */
    public static int getReserve(Player player, int ammunitionId) {
        var data = GameData.getTpsAmmunitionDataMap().get(ammunitionId);
        int limit = data != null ? data.getTpsAmmoLimit() : 0;
        return player.getTpsAmmunition().getOrDefault(ammunitionId, limit);
    }

    /** Moves a reserve by {@code delta}, kept within [0, tpsAmmoLimit]. Returns the new reserve. */
    public static int changeReserve(Player player, int ammunitionId, int delta) {
        var data = GameData.getTpsAmmunitionDataMap().get(ammunitionId);
        if (data == null) return 0;
        int value =
                (int) Math.max(0, Math.min((long) getReserve(player, ammunitionId) + delta, data.getTpsAmmoLimit()));
        player.getTpsAmmunition().put(ammunitionId, value);
        return value;
    }

    /** Fills every reserve and tells the client through the ammunition list of each worn weapon. */
    public static void refillAmmunition(Player player) {
        for (var data : GameData.getTpsAmmunitionDataMap().values()) {
            player.getTpsAmmunition().put(data.getId(), data.getTpsAmmoLimit());
        }
        player.save();
        for (Avatar avatar : player.getAvatars()) {
            if (!avatar.getTpsWeaponIds().isEmpty()) sendEquipChange(avatar);
        }
    }

    /** ABILITY_META_UPDATE_TPS_WEAPON_AMMUNITION: the client spent, reloaded, picked up or was supplied. */
    public static void onAmmunitionInvoke(Player player, AbilityInvokeEntry invoke) throws Exception {
        var update = AbilityMetaUpdateTpsWeaponAmmunition.parseFrom(invoke.getAbilityData());
        for (var change : update.getAmmunitionListList()) {
            int reserve = changeReserve(player, change.getItemId(), change.getChangeCount());
            Grasscutter.getLogger()
                    .debug(
                            "TPS ammunition {} {} {} -> {}",
                            update.getUpdateType(),
                            change.getItemId(),
                            change.getChangeCount(),
                            reserve);
        }
        // Fields 12/14 of the accessory entries are not identified yet; log them so a capture can
        // settle which one is the magazine count.
        for (var accessory : update.getAccessoryListList()) {
            Grasscutter.getLogger()
                    .debug(
                            "TPS ammunition slot type={} item={} f12={} f14={} (entity {})",
                            accessory.getAmmunitionType(),
                            accessory.getItemId(),
                            accessory.getKAPHEDKKFGK(),
                            accessory.getMHFBBNKOBPK(),
                            invoke.getEntityId());
        }
    }
}
