package dev.trung.colabterm;

import android.graphics.Insets;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

/** Applies safe system-bar and IME insets to an edge-to-edge content root. */
final class WindowInsetsHelper {
    private WindowInsetsHelper() { }

    static void apply(View contentRoot) {
        final int baseLeft = contentRoot.getPaddingLeft();
        final int baseTop = contentRoot.getPaddingTop();
        final int baseRight = contentRoot.getPaddingRight();
        final int baseBottom = contentRoot.getPaddingBottom();

        contentRoot.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left;
            int top;
            int right;
            int systemBottom;
            int imeBottom = 0;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                left = systemBars.left;
                top = systemBars.top;
                right = systemBars.right;
                systemBottom = systemBars.bottom;
                if (windowInsets.isVisible(WindowInsets.Type.ime())) {
                    imeBottom = ime.bottom;
                }
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                systemBottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        systemBottom = Math.max(systemBottom, cutout.getSafeInsetBottom());
                    }
                }
            }

            view.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    contentBottomPadding(baseBottom, systemBottom, imeBottom));
            return windowInsets;
        });
        contentRoot.requestApplyInsets();
    }

    static int contentBottomPadding(int baseBottom, int systemBarBottom, int imeBottom) {
        return baseBottom + Math.max(Math.max(0, systemBarBottom), Math.max(0, imeBottom));
    }
}
