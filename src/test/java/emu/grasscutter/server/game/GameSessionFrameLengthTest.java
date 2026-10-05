package emu.grasscutter.server.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GameSessionFrameLengthTest {
    @Test
    void acceptsExactFrameBoundary() {
        assertTrue(GameSession.hasValidFrameLengths(4, 8, 14));
    }

    @Test
    void acceptsRemainingBytesForAnotherFrame() {
        assertTrue(GameSession.hasValidFrameLengths(4, 8, 30));
    }

    @Test
    void rejectsNegativeLengths() {
        assertFalse(GameSession.hasValidFrameLengths(-1, 8, 14));
        assertFalse(GameSession.hasValidFrameLengths(4, -1, 14));
    }

    @Test
    void rejectsTruncatedHeaderOrPayload() {
        assertFalse(GameSession.hasValidFrameLengths(4, 8, 13));
        assertFalse(GameSession.hasValidFrameLengths(4, 8, 2));
    }

    @Test
    void rejectsIntegerOverflowInsteadOfWrapping() {
        assertFalse(GameSession.hasValidFrameLengths(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
