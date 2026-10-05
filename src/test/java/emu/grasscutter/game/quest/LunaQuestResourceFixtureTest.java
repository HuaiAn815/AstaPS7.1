package emu.grasscutter.game.quest;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.binout.MainQuestData;
import emu.grasscutter.data.excels.TriggerExcelConfigData;
import emu.grasscutter.data.excels.quest.QuestData;
import emu.grasscutter.game.quest.enums.QuestCond;
import emu.grasscutter.game.quest.enums.QuestContent;
import emu.grasscutter.scripts.ScriptLoader;
import emu.grasscutter.scripts.data.SceneGroup;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Regression fixture copied from the public LunaGC 7.1 resource pack. */
@ExtendWith(ServerResourceFixture.class)
public final class LunaQuestResourceFixtureTest {
    private static final String FIXTURE = "/emu/grasscutter/game/quest/lunagc-7.1/";
    private Map<Integer, QuestData> originalQuests;
    private Map<String, List<QuestData>> originalConditions;
    private Map<Integer, Integer> originalTalks;

    @BeforeEach
    void snapshotQuestCaches() {
        originalQuests = new HashMap<>(GameData.getQuestDataMap());
        originalConditions = new HashMap<>();
        GameData.getBeginCondQuestMap()
                .forEach((key, quests) -> originalConditions.put(key, new ArrayList<>(quests)));
        originalTalks = new HashMap<>(GameData.getQuestTalkMap());
        GameData.getQuestDataMap().clear();
        GameData.getBeginCondQuestMap().clear();
        GameData.getQuestTalkMap().clear();
    }

    @AfterEach
    void clearQuestCaches() {
        GameData.getQuestDataMap().clear();
        GameData.getQuestDataMap().putAll(originalQuests);
        GameData.getBeginCondQuestMap().clear();
        GameData.getBeginCondQuestMap().putAll(originalConditions);
        GameData.getQuestTalkMap().clear();
        GameData.getQuestTalkMap().putAll(originalTalks);
    }

    @Test
    void mainQuest351And352LoadTheirRealSubquestContracts() throws Exception {
        var questRows =
                readList("ExcelBinOutput/QuestExcelConfigData.json", QuestData.class).stream()
                        .filter(q -> q.getMainId() == 351 || q.getMainId() == 352)
                        .toList();
        for (var quest : questRows) {
            GameData.getQuestDataMap().put(quest.getId(), quest);
            quest.onLoad();
        }

        var main351 = read("BinOutput/Quest/351.json", MainQuestData.class);
        var main352 = read("BinOutput/Quest/352.json", MainQuestData.class);
        main351.onLoad();
        main352.onLoad();

        for (var main : List.of(main351, main352)) {
            for (var child : main.getSubQuests()) {
                var quest = GameData.getQuestDataMap().get(child.getSubId());
                assertNotNull(
                        quest, "main quest references a missing Excel row: " + child.getSubId());
                assertEquals(main.getId(), quest.getMainId());
            }
        }

        var first = GameData.getQuestDataMap().get(35104);
        var statue = GameData.getQuestDataMap().get(35205);
        assertNotNull(first);
        assertNotNull(statue);
        assertTrue(first.getAcceptCond().isEmpty());
        assertTrue(
                GameData.getQuestDataByConditions(QuestCond.QUEST_COND_NONE, 0, "").contains(first),
                "the opening quest must be discoverable by the login sweep");
        assertTrue(GameData.getQuestDataMap().get(35102).isFinishParent());
        assertTrue(first.isRewind());
        assertEquals("QUEST_CONTENT_FINISH_PLOT", first.getFinishCond().get(0).getType().name());
        assertEquals("QUEST_EXEC_UNLOCK_POINT", statue.getFinishExec().get(0).getType().name());
        assertArrayEquals(
                new int[] {3, 7},
                new int[] {
                    Integer.parseInt(statue.getFinishExec().get(0).getParam()[0]),
                    Integer.parseInt(statue.getFinishExec().get(0).getParam()[1])
                });
    }

    @Test
    void prologueRegionConditionsResolveAgainstTheirSceneLua() throws Exception {
        ScriptLoader.init();
        var groupPath = copyFixture("Scripts/Scene/3/scene3_group133003901.lua");
        try {
            var group = SceneGroup.of(133003901);
            group.setOverrideScriptPath(groupPath.toAbsolutePath().toString());
            group.load(3);
            assertNotNull(group.getScript(), "the real prologue region group must compile");
            assertNotNull(group.regions);
            assertNotNull(group.triggers);

            Map<Integer, TriggerExcelConfigData> triggers = new HashMap<>();
            for (var trigger :
                    readList(
                            "ExcelBinOutput/TriggerExcelConfigData.json",
                            TriggerExcelConfigData.class)) {
                triggers.put(trigger.getId(), trigger);
            }
            for (var quest :
                    readList("ExcelBinOutput/QuestExcelConfigData.json", QuestData.class)) {
                for (var condition : quest.getFinishCond()) {
                    if (condition.getType() != QuestContent.QUEST_CONTENT_TRIGGER_FIRE) continue;
                    var trigger = triggers.get(condition.getParam()[0]);
                    assertNotNull(trigger, "missing trigger resource for quest " + quest.getId());
                    assertEquals(3, trigger.getSceneId());
                    assertEquals(group.id, trigger.getGroupId());
                    assertTrue(group.triggers.containsKey(trigger.getTriggerName()));
                    int regionId = Integer.parseInt(trigger.getTriggerName().substring(13));
                    assertTrue(group.regions.containsKey(regionId));
                }
            }
        } finally {
            Files.deleteIfExists(groupPath);
        }
    }

    @Test
    void q351ShareLuaProducesTeleportAndRewindMaps() throws Exception {
        ScriptLoader.init();
        var scriptPath = copyFixture("Scripts/Quest/Share/Q351ShareConfig.lua");
        try {
            var bindings = ScriptLoader.getEngine().createBindings();
            ScriptLoader.eval(ScriptLoader.getScript(scriptPath.toString(), true), bindings);

            Map<String, TeleportData> questData =
                    ScriptLoader.getSerializer()
                            .toMap(TeleportData.class, bindings.get("quest_data"));
            Map<String, RewindData> rewindData =
                    ScriptLoader.getSerializer()
                            .toMap(RewindData.class, bindings.get("rewind_data"));

            assertTrue(questData.containsKey("35104"));
            assertEquals(3, questData.get("35104").getNpcs().get(0).getScene_id());
            assertEquals("Q351FirstQuest", questData.get("35104").getNpcs().get(0).getPos());
            assertEquals("Q351PlayerRewind1", rewindData.get("35100").getAvatar().getPos());
        } finally {
            Files.deleteIfExists(scriptPath);
        }
    }

    @Test
    void differentQuestShareScriptsKeepTheirOwnBindings() throws Exception {
        ScriptLoader.init();
        var firstPath = copyFixture("Scripts/Quest/Share/Q351ShareConfig.lua");
        var secondPath = copyFixture("Scripts/Quest/Share/Q352ShareConfig.lua");
        try {
            var firstBindings = ScriptLoader.getEngine().createBindings();
            var secondBindings = ScriptLoader.getEngine().createBindings();
            ScriptLoader.eval(ScriptLoader.getScript(firstPath.toString(), true), firstBindings);
            ScriptLoader.eval(ScriptLoader.getScript(secondPath.toString(), true), secondBindings);

            var firstQuestData =
                    ScriptLoader.getSerializer()
                            .toMap(TeleportData.class, firstBindings.get("quest_data"));
            var secondQuestData =
                    ScriptLoader.getSerializer()
                            .toMap(TeleportData.class, secondBindings.get("quest_data"));
            var firstRewindData =
                    ScriptLoader.getSerializer()
                            .toMap(RewindData.class, firstBindings.get("rewind_data"));
            var secondRewindData =
                    ScriptLoader.getSerializer()
                            .toMap(RewindData.class, secondBindings.get("rewind_data"));

            assertTrue(firstQuestData.containsKey("35104"));
            assertFalse(firstQuestData.containsKey("35202"));
            assertTrue(secondQuestData.containsKey("35202"));
            assertFalse(secondQuestData.containsKey("35104"));
            assertEquals("Q351PlayerRewind1", firstRewindData.get("35100").getAvatar().getPos());
            assertEquals("Q352Queen", secondRewindData.get("35204").getNpcs().get(0).getPos());
        } finally {
            Files.deleteIfExists(firstPath);
            Files.deleteIfExists(secondPath);
        }
    }

    private static Path copyFixture(String path) throws Exception {
        var copy = Files.createTempFile("lunagc-quest-", ".lua");
        Files.writeString(copy, readText(path), StandardCharsets.UTF_8);
        return copy;
    }

    private static String readText(String path) throws Exception {
        try (var input = LunaQuestResourceFixtureTest.class.getResourceAsStream(FIXTURE + path)) {
            assertNotNull(input, "missing fixture " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T read(String path, Class<T> type) throws Exception {
        try (var input = LunaQuestResourceFixtureTest.class.getResourceAsStream(FIXTURE + path)) {
            assertNotNull(input, "missing fixture " + path);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonUtils.loadToClass(reader, type);
            }
        }
    }

    private static <T> List<T> readList(String path, Class<T> type) throws Exception {
        try (var input = LunaQuestResourceFixtureTest.class.getResourceAsStream(FIXTURE + path)) {
            assertNotNull(input, "missing fixture " + path);
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return JsonUtils.loadToList(reader, type);
            }
        }
    }
}
