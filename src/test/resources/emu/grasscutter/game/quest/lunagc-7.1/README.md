# LunaGC 7.1 Quest Fixtures

Source: [capyb2222/LunaGC-Resources](https://github.com/capyb2222/LunaGC-Resources/tree/395a5ee6442142a803691f60d541ee1706ae9cd7), commit `395a5ee6442142a803691f60d541ee1706ae9cd7` (retrieved 2026-10-05).

The following files preserve the original game data and Lua. The Excel JSON fixtures contain only the rows used by main quests 351 and 352; formatting of those excerpts is normalized.

- [BinOutput/Quest/351.json](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/BinOutput/Quest/351.json)
- [BinOutput/Quest/352.json](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/BinOutput/Quest/352.json)
- [ExcelBinOutput/QuestExcelConfigData.json](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/ExcelBinOutput/QuestExcelConfigData.json): all rows with `mainId` 351 or 352.
- [ExcelBinOutput/TriggerExcelConfigData.json](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/ExcelBinOutput/TriggerExcelConfigData.json): only trigger IDs referenced by those quests.
- [Scripts/Quest/Share/Q351ShareConfig.lua](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/Scripts/Quest/Share/Q351ShareConfig.lua)
- [Scripts/Quest/Share/Q352ShareConfig.lua](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/Scripts/Quest/Share/Q352ShareConfig.lua)
- [Scripts/Scene/3/scene3_group133003901.lua](https://github.com/capyb2222/LunaGC-Resources/blob/395a5ee6442142a803691f60d541ee1706ae9cd7/Scripts/Scene/3/scene3_group133003901.lua)

These are parsing and cross-resource regression fixtures. They do not replace a complete 7.1 resource pack: actual quest execution also needs the matching scene/block scripts, dummy-point data, NPC born data, and other Excel tables.
