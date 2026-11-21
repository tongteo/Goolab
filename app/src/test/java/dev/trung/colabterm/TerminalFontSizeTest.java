package dev.trung.colabterm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TerminalFontSizeTest {
    @Test
    public void usesReadableDefaultAndClampsToSupportedRange() {
        assertEquals(16, TerminalFontSize.DEFAULT_SP);
        assertEquals(TerminalFontSize.MIN_SP, TerminalFontSize.clamp(0));
        assertEquals(TerminalFontSize.MAX_SP, TerminalFontSize.clamp(100));
        assertEquals(18, TerminalFontSize.clamp(18));
    }

    @Test
    public void adjustsBySpWithoutCrossingLimits() {
        assertEquals(17, TerminalFontSize.adjust(16, 1));
        assertEquals(TerminalFontSize.MIN_SP, TerminalFontSize.adjust(TerminalFontSize.MIN_SP, -1));
        assertEquals(TerminalFontSize.MAX_SP, TerminalFontSize.adjust(TerminalFontSize.MAX_SP, 1));
    }

    @Test
    public void pinchScalingChangesFontAndRespectsLimits() {
        assertEquals(17, TerminalFontSize.scale(16, 1.05f));
        assertEquals(15, TerminalFontSize.scale(16, 0.95f));
        assertEquals(TerminalFontSize.MAX_SP, TerminalFontSize.scale(31, 1.1f));
        assertEquals(TerminalFontSize.MIN_SP, TerminalFontSize.scale(9, 0.8f));
        assertEquals(16, TerminalFontSize.scale(16, Float.NaN));
    }

    @Test
    public void convertsSpToPixelsUsingDisplayScaledDensity() {
        assertEquals(40, TerminalFontSize.toPixels(16, 2.5f));
        assertEquals(22, TerminalFontSize.toPixels(8, 2.75f));
    }
}
