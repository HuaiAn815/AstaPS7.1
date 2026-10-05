package emu.grasscutter.game.player;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;
import static emu.grasscutter.config.Configuration.SERVER;

import static org.junit.jupiter.api.Assertions.*;

import dev.morphia.annotations.PostLoad;

import emu.grasscutter.GameConstants;
import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.game.friends.PlayerProfile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@ExtendWith(ServerResourceFixture.class)
public final class PlayerTeamManagerLifecycleTest {
    private static final int MAIN_AVATAR = 10000007;
    private static final int UNOWNED_AVATAR = 10000999;
    private boolean questingEnabled;
    private boolean scriptsEnabled;

    @BeforeEach
    public void saveConfiguration() {
        questingEnabled = GAME_OPTIONS.questing.enabled;
        scriptsEnabled = SERVER.game.enableScriptInBigWorld;
        SERVER.game.enableScriptInBigWorld = true;
    }

    @AfterEach
    public void restoreConfiguration() {
        GAME_OPTIONS.questing.enabled = questingEnabled;
        SERVER.game.enableScriptInBigWorld = scriptsEnabled;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void loginBindsSavedManagerBeforeCleaningTeams(boolean questing)
            throws ReflectiveOperationException {
        GAME_OPTIONS.questing.enabled = questing;
        var player = playerWithOwnedAvatar();
        var manager = savedManager(player);
        var teams = manager.getTeams();
        var current = manager.getCurrentSinglePlayerTeamInfo();
        assertNull(manager.getPlayer());

        stopAfterLoginTeamCleanup(player);

        assertSame(player, manager.getPlayer());
        assertSame(manager, player.getTeamManager());
        assertSame(teams, manager.getTeams());
        assertSame(current, manager.getCurrentSinglePlayerTeamInfo());
        assertEquals(2, manager.getCurrentTeamId());
        assertEquals("Saved team", current.getName());
        assertEquals(List.of(MAIN_AVATAR), current.getAvatars());
        assertTrue(teams.get(1).getAvatars().isEmpty());
    }

    @Test
    public void repeatedLoginRetainsManagerAndSavedTeam() throws ReflectiveOperationException {
        var player = playerWithOwnedAvatar();
        var manager = savedManager(player);
        var current = manager.getCurrentSinglePlayerTeamInfo();

        stopAfterLoginTeamCleanup(player);
        stopAfterLoginTeamCleanup(player);

        assertSame(manager, player.getTeamManager());
        assertSame(player, manager.getPlayer());
        assertSame(current, manager.getCurrentSinglePlayerTeamInfo());
        assertEquals("Saved team", current.getName());
        assertEquals(List.of(MAIN_AVATAR), current.getAvatars());
    }

    @Test
    public void loginCreatesMissingManagerAndPopulatesDefaultTeam()
            throws ReflectiveOperationException {
        var player = playerWithOwnedAvatar();
        assertNull(player.getTeamManager());

        stopAfterLoginTeamCleanup(player);

        var manager = player.getTeamManager();
        assertSame(player, manager.getPlayer());
        assertEquals(GameConstants.DEFAULT_TEAMS, manager.getTeams().size());
        assertEquals(1, manager.getCurrentTeamId());
        assertEquals(List.of(MAIN_AVATAR), manager.getCurrentSinglePlayerTeamInfo().getAvatars());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void postLoadRestoresOwnerOrCreatesMissingManager(boolean hasSavedManager)
            throws ReflectiveOperationException {
        var player = new StoppingPlayer();
        var saved = hasSavedManager ? savedManager(player) : null;
        var onLoad = Player.class.getDeclaredMethod("onLoad");
        assertTrue(onLoad.isAnnotationPresent(PostLoad.class));
        onLoad.setAccessible(true);

        onLoad.invoke(player);

        var manager = player.getTeamManager();
        assertNotNull(manager);
        assertSame(player, manager.getPlayer());
        if (hasSavedManager) {
            assertSame(saved, manager);
            assertEquals("Saved team", manager.getCurrentSinglePlayerTeamInfo().getName());
            assertEquals(
                    List.of(MAIN_AVATAR, UNOWNED_AVATAR),
                    manager.getCurrentSinglePlayerTeamInfo().getAvatars());
        } else {
            assertEquals(GameConstants.DEFAULT_TEAMS, manager.getTeams().size());
            assertEquals(1, manager.getCurrentTeamId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void databaseLoadInitializesManagerBeforeStartingOtherLoads(boolean hasSavedManager)
            throws ReflectiveOperationException {
        var player = new StoppingPlayer();
        var saved = hasSavedManager ? savedManager(player) : null;
        player.stopBeforeDatabaseLoads = true;

        assertSame(player.stop, assertThrows(Sentinel.class, player::loadFromDatabase));

        var manager = player.getTeamManager();
        assertNotNull(manager);
        assertSame(player, manager.getPlayer());
        if (hasSavedManager) {
            assertSame(saved, manager);
            assertEquals("Saved team", manager.getCurrentSinglePlayerTeamInfo().getName());
        } else {
            assertEquals(GameConstants.DEFAULT_TEAMS, manager.getTeams().size());
        }
    }

    private static void stopAfterLoginTeamCleanup(StoppingPlayer player) {
        // Reach the real login cleanup, then stop before world setup requires game resources.
        player.stopBeforeWorld = true;
        assertSame(player.stop, assertThrows(Sentinel.class, player::onLogin));
    }

    private static StoppingPlayer playerWithOwnedAvatar() throws ReflectiveOperationException {
        var player = new StoppingPlayer();
        var avatar = allocate(Avatar.class);
        setField(Avatar.class, avatar, "avatarId", MAIN_AVATAR);
        player.getAvatars().getAvatars().put(MAIN_AVATAR, avatar);
        player.setMainCharacterId(MAIN_AVATAR);
        return player;
    }

    private static TeamManager savedManager(Player player) throws ReflectiveOperationException {
        // Persisted teams survive deserialization, but the transient owner does not.
        var manager = new TeamManager();
        var teams = new LinkedHashMap<Integer, TeamInfo>();
        teams.put(1, new TeamInfo(new ArrayList<>(List.of(UNOWNED_AVATAR))));
        var current = new TeamInfo(new ArrayList<>(List.of(MAIN_AVATAR, UNOWNED_AVATAR)));
        current.setName("Saved team");
        teams.put(2, current);
        setField(TeamManager.class, manager, "teams", teams);
        setField(TeamManager.class, manager, "currentTeamIndex", 2);
        setField(Player.class, player, "teamManager", manager);
        return manager;
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(((Unsafe) field.get(null)).allocateInstance(type));
    }

    private static void setField(Class<?> type, Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Sentinel extends RuntimeException {}

    private static final class StoppingPlayer extends Player {
        private final Sentinel stop = new Sentinel();
        private boolean stopBeforeWorld;
        private boolean stopBeforeDatabaseLoads;

        @Override
        public int getSceneId() {
            if (stopBeforeWorld) throw stop;
            return super.getSceneId();
        }

        @Override
        public PlayerProfile getProfile() {
            if (stopBeforeDatabaseLoads) throw stop;
            return super.getProfile();
        }

        @Override
        public void save() {}
    }
}
