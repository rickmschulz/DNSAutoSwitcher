package com.rickmschulz.dnsautoswitcher;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps a screen's content out of the status bar and the navigation bar.
 *
 * From Android 15 the system draws apps edge to edge whether they ask for it or not, so a layout
 * that used to sit below the status bar now starts underneath it unless it adds the insets itself.
 */
final class SystemBars {

    private SystemBars() {
    }

    /** Adds the status and navigation bar heights to the view's existing padding. */
    static void pad(View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
