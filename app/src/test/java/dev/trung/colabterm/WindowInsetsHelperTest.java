package dev.trung.colabterm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WindowInsetsHelperTest {
    @Test
    public void keepsNavigationBarInsetWhenKeyboardIsHidden() {
        assertEquals(20, WindowInsetsHelper.contentBottomPadding(4, 16, 0));
    }

    @Test
    public void usesLargerKeyboardInsetInsteadOfAddingItToNavigationBar() {
        assertEquals(804, WindowInsetsHelper.contentBottomPadding(4, 24, 800));
    }

    @Test
    public void keepsSystemBarInsetIfReportedKeyboardInsetIsSmaller() {
        assertEquals(36, WindowInsetsHelper.contentBottomPadding(4, 32, 12));
    }
}
