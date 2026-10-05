# Quest system migration

This change adapts the LunaGC 7.1 quest lifecycle to AstaPS's 7.1 protocol and
existing player features. Source comparison used LunaGC commit
`66a10913f0ce5873e0c9a43368b7ac5223097fd3`. Much of the quest condition,
content and execution code is already shared by the two projects.

## Configuration

Set these fields in `config.json`, keeping the other settings:

```json
{
  "server": {
    "game": {
      "enableScriptInBigWorld": true,
      "gameOptions": {
        "questing": {
          "enabled": true,
          "triggerAllOnLogin": false
        }
      }
    }
  }
}
```

Both questing and big-world scripts must be enabled for the full task flow.
When they are enabled, statue convenience gates do not silently complete task
303 or 35205. Disabling questing keeps the existing convenience behavior.

`triggerAllOnLogin` remains false by default. Saved, unfinished parent quests
always recover child quests with satisfied static acceptance conditions, and
finished parents recover their suggested unlinked follow-ups. Dynamic Lua,
time and variable conditions still depend on their normal events. Setting
`triggerAllOnLogin` to true additionally runs the broad LunaGC eligibility
sweep; it can populate tasks outside the current story chain.

## Resources and saved games

Use a complete, matching 7.1 resource pack, including QuestExcelConfigData,
BinOutput/Quest, Quest Share Lua, TriggerExcelConfigData and scene/group scripts.
Runtime resource packs are not included in this repository. The small test
fixtures have pinned source URLs in
`src/test/resources/emu/grasscutter/game/quest/lunagc-7.1/README.md`.

Login waits for saved quests to load before recovery. Missing child resources
are retained in the saved parent and skipped during active processing; they
are restored when matching resources become available. No database wipe or
manual quest reset is required by this migration.

Malformed quest files and failing scene groups are reported individually so
other files, groups, region checks and scene loading can continue. These guards
do not replace missing scripts or implement every unsupported quest condition.

## Validation

Run the resource-independent regression suite and build with Java 21:

```sh
./gradlew test jar -PexcludeTags=integration -PskipHandbook=1 -PjarFilename=grasscutter
```

Tests cover login recovery, saved quest variables, both progress request
shapes, quick launch, resource contracts, scene group isolation and statue
mode behavior. Full client story progression still requires validation with
MongoDB, the matching resource pack and a 7.1 client.
