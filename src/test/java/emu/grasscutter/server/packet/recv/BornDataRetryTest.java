package emu.grasscutter.server.packet.recv;

import static emu.grasscutter.config.Configuration.GAME_OPTIONS;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.proto.SetPlayerBornDataReqOuterClass.SetPlayerBornDataReq;
import emu.grasscutter.server.born.BornIntroGate;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketSetPlayerBornDataRsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

@ExtendWith(ServerResourceFixture.class)
public class BornDataRetryTest {
    @Test
    public void repeatedConfirmationOnlyResendsResponse() throws Exception {
        var player =
                new Player() {
                    @Override
                    public void setNickname(String nickname) {
                        fail("A repeated confirmation must not update the persisted profile");
                    }

                    @Override
                    public String getNickname() {
                        return "Chosen name";
                    }
                };
        player.setUid(920001);
        player.getAvatars().getAvatars().put(10000005, null);
        var session = new RecordingSession(player);
        session.setState(GameSession.SessionState.ACTIVE);
        BornIntroGate.armNativeIntro(session);
        int configuredRsp = GAME_OPTIONS.newAccountIntro.setPlayerBornDataRsp;
        try {
            GAME_OPTIONS.newAccountIntro.setPlayerBornDataRsp = 0;
            var payload =
                    SetPlayerBornDataReq.newBuilder()
                            .setAvatarId(10000005)
                            .setNickName("Repeated name")
                            .build()
                            .toByteArray();

            new HandlerSetPlayerBornDataReq().handle(session, new byte[0], payload);
            new HandlerSetPlayerBornDataReq().handle(session, new byte[0], payload);

            assertEquals(2, session.sent.size());
            assertTrue(
                    session.sent.stream()
                            .allMatch(p -> p.getOpcode() == PacketSetPlayerBornDataRsp.CMD_ID));
            assertEquals("Chosen name", player.getNickname());
            assertEquals(1, player.getAvatars().getAvatarCount());
            assertNull(player.getWorld());
            assertTrue(BornIntroGate.isNativeIntroForSession(session));
        } finally {
            GAME_OPTIONS.newAccountIntro.setPlayerBornDataRsp = configuredRsp;
        }
    }

    @Test
    public void reconnectAndAutomaticBirthDoNotAllowNativeRetries() {
        var player = new Player();
        player.setUid(920002);
        var origin = new RecordingSession(player);
        var replacement = new RecordingSession(player);
        BornIntroGate.armNativeIntro(origin);
        assertTrue(BornIntroGate.isNativeIntroForSession(origin));
        assertFalse(BornIntroGate.isNativeIntroForSession(replacement));
        BornIntroGate.armSceneReady(origin);
        assertFalse(BornIntroGate.isNativeIntroForSession(origin));
    }

    private static final class RecordingSession extends GameSession {
        private final Player player;
        private final List<BasePacket> sent = new ArrayList<>();

        private RecordingSession(Player player) {
            super(null);
            this.player = player;
        }

        @Override
        public Player getPlayer() {
            return player;
        }

        @Override
        public void send(BasePacket packet) {
            sent.add(packet);
        }
    }
}
