package com.zmlworks.chargelog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Starts the service on launch; buttons and status at the top, graph, then numbers and sensors. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button toggle, view, reset, settings;
    private LinearLayout row1, row2;
    private GraphView graph;
    private SessionView stats;
    private SensorView sensors;
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

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20);
        title.setTextColor(Ui.TEXT);
        title.setPadding(0, 0, 0, pad / 4);
        root.addView(title);

        row1 = new LinearLayout(this);
        row2 = new LinearLayout(this);
        view = button(row1, v -> { showFinished = !showFinished; update(); });
        reset = button(row1, v -> { ChargeService.send(this, ChargeService.ACTION_RESET); handler.postDelayed(this::update, 300); });
        reset.setText(R.string.reset);
        settings = button(row2, v -> startActivity(new Intent(this, SettingsActivity.class)));
        settings.setText(R.string.settings);
        toggle = button(row2, v -> {
            if (ChargeService.running) ChargeService.send(this, ChargeService.ACTION_STOP); else ChargeService.start(this);
            handler.postDelayed(this::update, 300);
        });
        LinearLayout.LayoutParams rowLp1 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp1.topMargin = pad / 4;
        LinearLayout.LayoutParams rowLp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp2.topMargin = pad / 4;
        root.addView(row1, rowLp1);
        root.addView(row2, rowLp2);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        graph = new GraphView(this);
        root.addView(graph, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 360)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, pad * 4);
        stats = new SessionView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        col.addView(stats, lp);
        sensors = new SensorView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = pad;
        col.addView(sensors, slp);
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
        view.setText(showFinished ? R.string.view_live : R.string.view_finished);
        reset.setEnabled(ChargeService.running);
        // When a finished charge exists, row1 is 充電結果/放電記録 + リセット; otherwise only row2 (リセット is moved there).
        if (fin != null) {
            if (view.getParent() != row1) { row2.removeView(view); row1.addView(view, 0); }
            if (reset.getParent() != row1) { row2.removeView(reset); row1.addView(reset); }
            row1.setVisibility(View.VISIBLE);
        } else {
            row1.setVisibility(View.GONE);
            if (reset.getParent() != row2) { row1.removeView(reset); row2.addView(reset, 0); }
        }
        sensors.show(s);
    }
}
