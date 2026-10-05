package emu.grasscutter.data.excels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import emu.grasscutter.data.excels.avatar.AvatarReplaceCostumeData;
import emu.grasscutter.data.excels.avatar.VehicleData;
import emu.grasscutter.data.excels.codex.CodexAnimalData;
import emu.grasscutter.utils.JsonUtils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public final class ResourceIdCasingTest {
    @Test
    @DisplayName("daily task rows accept the 7.1 ID field")
    public void dailyTaskAcceptsUppercaseId() {
        var data = JsonUtils.decode("{\"ID\":692001}", DailyTaskData.class);

        assertEquals(692001, data.getId());
    }

    @Test
    @DisplayName("animal codex rows accept the 7.1 Id field")
    public void animalCodexAcceptsCapitalizedId() {
        var data = JsonUtils.decode("{\"Id\":585001}", CodexAnimalData.class);

        assertEquals(585001, data.getId());
    }

    @Test
    @DisplayName("vehicle rows accept the 7.1 ID field")
    public void vehicleAcceptsUppercaseId() {
        var data = JsonUtils.decode("{\"ID\":49001}", VehicleData.class);

        assertEquals(49001, data.getId());
    }

    @Test
    @DisplayName("replacement costume rows accept the current resource field")
    public void replacementCostumeAcceptsCurrentName() {
        var data =
                JsonUtils.decode(
                        "{\"avatarId\":10000002,\"replaceCostumeId\":200201}",
                        AvatarReplaceCostumeData.class);

        assertEquals(10000002, data.getAvatarId());
        assertEquals(200201, data.getId());
    }

    @Test
    @DisplayName("replacement costume rows still accept legacy resources")
    public void replacementCostumeAcceptsLegacyName() {
        var data = JsonUtils.decode("{\"costumeId\":200201}", AvatarReplaceCostumeData.class);

        assertEquals(200201, data.getId());
    }
}
