package com.zmlworks.chargelog;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/** Small helpers shared by activities: edge-to-edge handling for targetSdk 35. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF1C1D20, TEXT = 0xFFE4E5E7, HI = 0xFF4FB3FF,
            PANEL = 0xFF2A2C30, GRID = 0xFF383B40, DIM = 0xFF9AA0A6;

    /** Android 15 forces edge-to-edge; we opt in on every version and pad the root by the bar/IME insets. */
    @SuppressWarnings("deprecation")
    static void edgeToEdge(Activity a, View root) {
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                il = i.left; it = i.top; ir = i.right; ib = i.bottom;
            } else {
                il = in.getSystemWindowInsetLeft(); it = in.getSystemWindowInsetTop();
                ir = in.getSystemWindowInsetRight(); ib = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return in;
        });
        root.requestApplyInsets();
    }

    static int dp(android.content.Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
