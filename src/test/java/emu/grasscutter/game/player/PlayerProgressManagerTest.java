package emu.grasscutter.game.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.GameData;
import emu.grasscutter.data.excels.OpenStateData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

@ExtendWith(ServerResourceFixture.class)
public final class PlayerProgressManagerTest {
    @Test
    public void unlockedStatesDoNotRecheckTheirConditions() {
        var player = new Player();
        player.getOpenStates().put(42, 1);
        int[] conditionChecks = {0};
        var state =
                new OpenStateData() {
                    @Override
                    public int getId() {
                        return 42;
                    }

                    @Override
                    public List<OpenStateCond> getCond() {
                        conditionChecks[0]++;
                        return List.of();
                    }
                };
        GameData.getOpenStateList().add(state);
        try {
            player.getProgressManager().tryUnlockOpenStates(false);

            assertEquals(0, conditionChecks[0]);
            assertEquals(1, player.getOpenStates().get(42));
        } finally {
            GameData.getOpenStateList().remove(state);
        }
    }
}
