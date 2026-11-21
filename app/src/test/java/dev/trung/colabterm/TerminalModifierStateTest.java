package dev.trung.colabterm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TerminalModifierStateTest {
    @Test
    public void controlModifierIsConsumedByExactlyOneKey() {
        TerminalModifierState state = new TerminalModifierState();

        state.toggleControlKey();

        assertTrue(state.isControlKeyDown());
        assertTrue(state.consumeControlKey());
        assertFalse(state.isControlKeyDown());
        assertFalse(state.consumeControlKey());
    }

    @Test
    public void togglingControlOffCancelsPendingModifier() {
        TerminalModifierState state = new TerminalModifierState();

        state.toggleControlKey();
        state.toggleControlKey();

        assertFalse(state.isControlKeyDown());
        assertFalse(state.consumeControlKey());
    }
}
