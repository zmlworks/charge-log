package com.zmlworks.chargelog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Starts the service on launch; graph on top, numbers below, refreshed twice a second. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button toggle, view, reset;
    private GraphView graph;
    private SessionView stats;
    private Session lastFinished;
    private boolean showFinished;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20);
        title.setTextColor(Ui.TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button settings = new Button(this);
        settings.setText(R.string.settings);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(settings);
        root.addView(bar);

        graph = new GraphView(this);
        root.addView(graph, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, pad * 4);
        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);
        LinearLayout buttons = new LinearLayout(this);
        toggle = button(buttons, v -> {
            if (ChargeService.running) ChargeService.send(this, ChargeService.ACTION_STOP); else ChargeService.start(this);
            handler.postDelayed(this::update, 300);
        });
        view = button(buttons, v -> { showFinished = !showFinished; update(); });
        reset = button(buttons, v -> { ChargeService.send(this, ChargeService.ACTION_RESET); handler.postDelayed(this::update, 300); });
        reset.setText(R.string.reset);
        col.addView(buttons);
        stats = new SessionView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        col.addView(stats, lp);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        Ui.edgeToEdge(this, root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (b == null) ChargeService.start(this);
    }

    private Button button(LinearLayout row, View.OnClickListener l) {
        Button b = new Button(this);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginEnd(Ui.dp(this, 4));
        row.addView(b, lp);
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    void update() {
        Session live = ChargeService.live, fin = ChargeService.finished;
        if (fin != lastFinished) { lastFinished = fin; showFinished = fin != null; }   // a charge just ended: show it
        if (fin == null) showFinished = false;
        Session s = showFinished ? fin : live;
        if (!ChargeService.running && live == null && fin == null) status.setText(R.string.status_stopped);
        else if (showFinished) status.setText(R.string.status_finished);
        else if (live == null) status.setText(R.string.status_stopped);
        else status.setText(live.charge ? R.string.status_charging : R.string.status_discharging);
        graph.setSession(s);
        stats.show(s, showFinished, Prefs.capacity(this));
        toggle.setText(ChargeService.running ? R.string.stop_service : R.string.start_service);
        view.setVisibility(fin != null ? View.VISIBLE : View.GONE);
        view.setText(showFinished ? R.string.view_live : R.string.view_finished);
        reset.setEnabled(ChargeService.running);
    }
}
