# TPS weapons (7.1)

The 7.x third-person shooter mode: guns and grenades an avatar wears next to its normal weapon.
This folder has the 7.1 protocol recovered for it (`tps_7.1.proto`) and the tools that recovered it.

## What the server does

| | |
|---|---|
| Items | `TpsWeaponExcelConfigData` items (224001-224008) load as `ITEM_TPS_WEAPON`, one of each per player, sent as `Item.tps_weapon` with their accessories and affixes. |
| Gun models | An avatar's worn TPS weapons get weapon entities (gadgets 50016001-50016008) and are listed in `SceneAvatarInfo.tps_weapon_list` (31) and `AvatarInfo.tps_weapon_list` (37). |
| Abilities | Each worn weapon's affix (and unlocked accessory affixes) opens its `EquipAffixExcelConfigData.openConfig`, e.g. `TPS_Weapon_IceGun`, which adds `Avatar_TPS_IceGun_PressAim`, `Avatar_TPS_Ammo_Manager`, `Avatar_TPS_Ammo_Reload` and the rest to the avatar. `recalcStats` sends them with `AbilityChangeNotify`. |
| Switching | `WearTpsEquipReq` (20756) replaces the avatar's whole TPS list, at most 2 guns and 1 grenade (`CONST_VALUE_TPS_SLOT_WEAR_NUM_LIMIT`). A weapon worn by another avatar moves. Replies `WearTpsEquipRsp` (25902) and broadcasts `TpsEquipChangeNotify` (21312). |
| TPS traveler | The TPS dungeons (51334, 51336 shooting range, 51341-51345) allow only 10000134/10000135. Entering one swaps the team for the TPS traveler matching the player's traveler, as the level 20 trial avatar 10064/10065, wearing the player's TPS loadout (224001 the first time, `CONST_VALUE_INIT_TPS_WEAPON_ID`). Leaving the dungeon restores the team. Weapons chosen there are kept as the loadout. |
| Ammunition | A reserve per `TpsAmmunitionExcelConfigData` id, shared by the slots it fills (1001 feeds both rifles' slot 101/201), full by default, capped at `tpsAmmoLimit`. `ABILITY_META_UPDATE_TPS_WEAPON_AMMUNITION` (invoke argument 31) applies the client's `ammunition_list` changes. Each weapon's `ammunition_list` reports the reserve per slot. |

The openConfig loader also had to learn the obfuscated 7.x talent entries (`PHNIGFHBFMD` = AddAbility,
`GMOELNAHCOH` = ModifyAbility, `CMGFNDNMFFO` = UnlockTalentParam, key `NCCKLDFFDOH` = abilityName).
Without them every TPS openConfig was empty. The same entries are used by the Cryo Traveler and
LittleIdol talents, which now load too.

### Trying it

```
/tps give            all eight TPS weapons (or /tps give 224001)
/tps accessory       unlock every accessory of the weapons you own
/tps wear 224001 224004   the field avatar wears the Glacia rifle and a fire grenade
/tps refill          fill all ammunition and resend the weapons
```

Ammunition traffic is logged at debug level (`TPS ammunition ...`).

## How the protocol was recovered

Obfuscated names change every version, but structure does not, and inside one version an
obfuscated field name always stands for the same real name. The inputs were the 7.0 dump with
partial names (`7.0deof.proto`), its TPS name list, the 7.1 dump (`7.1Obfuscated.proto`) and the 7.1
name list (`7.1nt.txt`).

`tools/match.py OLD.proto NEW.proto NEW_NAMES.json NAME...` does it as constraint propagation:

1. A name the 7.1 list already knows is taken as is (`AbilityMetaUpdateTpsWeaponAmmunition`,
   `_SetWidgetQuickSlotListReq`). Otherwise the candidates are the 7.1 messages of the same shape:
   the multiset of field labels and kinds, where a kind is the scalar type, the real name of a type
   both versions know (`SceneWeaponInfo`), or the shape of the referenced type.
2. A resolved message resolves the types of its fields. Leaf structs are found this way: three
   `uint32`s look like 135 other messages, but `SceneWeaponInfo` field 12 has only one type.
3. Each resolved pair teaches old->new field names (`avatar_guid` -> `ECMNNFKNAIK`,
   `equip_guid_list` -> `OIMEKIFBCPB`). Every candidate must then use the mapped names and carry the
   resolved child types, and no 7.1 message may match two 7.0 ones.
4. Repeat until nothing changes.

```
python3 tools/match.py 7.0deof.proto 7.1Obfuscated.proto 7.1nt.txt \
    AbilityMetaUpdateTpsWeaponAmmunition PJFEBILHHJJ DHOKPFPMBAL JAPMAEHMHPG \
    TpsWeapon HGKMLCKCHBD JCGFEBNFLJP JPHBCDGGFOF HJOPNGJBLBN
```

resolves all of them; the CmdIds it gives for the packets (20756, 25902, 21312) are the ones this
repository already used. `tools/show.py` prints a 7.1 message with the known names filled in.

The field names in the 7.1 meta message line up with `SceneWeaponInfo.12`: `ammunition_type` and
the ammunition config id carry the same obfuscated names in both.

### Checking the 7.0 TPS name list

Inside one version a real field name always gets the same obfuscated name: in `7.1nt.txt` 2193
of 2194 field names have exactly one spelling, and e.g. `ECMNNFKNAIK` is `avatar_guid` in dozens
of messages. So a label from the 7.0 list can be checked: find the 7.1 field it became by
structure, and see whether 7.1 spells that real name the same way. `match.py --fields
7.0nameTranslation_TPS.txt ...` prints the result:

| 7.0 label | 7.0 obf | 7.1 by structure | Verdict |
|---|---|---|---|
| `item_id` | BMNOGGNIIFO | PPFJCENMNEO | wrong: 7.1 `item_id` is EIGJMOABCGH. The values are ammunition ids, so it is named `ammunition_config_id`, the alias the list itself gives |
| `change_count` | FBMKHDNKCAP | CEAMPEAIPPJ | wrong: 7.1 `change_count` is DOCFIDJNIFO. Name kept as a description |
| `weapon_list` | KPJFLNKEFBC | MJGFMDADPJE | wrong: 7.1 `weapon_list` is OCBIBFHOCAG. Same name as `SceneAvatarInfo.tps_weapon_list`, so it is named that |
| `level` | MMAHICOGIKB | (field 12 or 14) | wrong: the matched message has no `level` (MLADOODEJDP) |
| `progress` | FHKAJDHNIJN | DFEMHODHAAP | wrong: the matched message has no `progress` (GLEDHAJBGBN) |
| `avatar_guid`, `equip_guid_list`, `retcode`, `update_type`, `affix_map` | | | agree with 7.1 |
| the other 12 | | | 7.1 list has no such name to check against |

`TpsEntityFightPropUpdateNotify` (KHAAHLBEIAC) and its `entity_id` / `fight_prop_map` are not in
the 7.0 dump at all, so the list comes from a different build. No 7.1 packet has that shape
other than EntityFightPropUpdateNotify (9736) and EntityFightPropNotify (27270).

### What is not settled

- `TpsWeaponAccessoryInfo` fields 12 and 14: the 7.0 list calls them `slot_index` and `level`, but
  neither is 7.1's `level`. The server only logs them.
- `AbilityMetaUpdateTpsWeaponAmmunition` field 7, a bool.
- `TpsAmmunitionChangeNotify` (24371) is a guessed name, and whether its counts are deltas or totals
  is unknown, so the server does not send it.
- `GetWidgetQuickSlotListRsp` / `SetWidgetQuickSlotListRsp` are 5047 and 6316 in some order.
- Whether `TpsWeaponAmmunitionInfo.current_ammunition` is the reserve or the loaded magazine. The
  ability config initialises the magazine on the client (`Avatar_TPS_Ammo_Manager`), so the
  server sends the reserve.
- The TPS traveler is a trial avatar here. Whether the official server grants it as an owned
  avatar instead is unknown. Outside TPS dungeons any avatar can still wear the weapons, which is
  handy with `/tps wear` for testing.

A packet capture from the official client would settle all of these.

## Regenerating the Java

The repository ships generated Java but not the `.proto` sources. `tools/extract.py` recovers the
descriptors embedded in the Java, and `tools/desc2proto.py` turns them back into `.proto` files that
`protoc` 3.18.1 compiles to byte-identical Java. Edit or add `.proto` files there, then compile only
those:

```
python3 tools/extract.py src/generated/main/java/emu/grasscutter/net/proto repo.desc
python3 tools/desc2proto.py repo.desc proto_src
# edit / add files in proto_src
protoc -Iproto_src --java_out=src/generated/main/java File1.proto File2.proto
```

Do not put `.proto` files under `proto/` in the repository: the build would then regenerate, and
`clean` delete, every generated class.
