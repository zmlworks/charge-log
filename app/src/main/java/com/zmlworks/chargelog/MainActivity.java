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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** Starts the service on launch and shows the running session (or the latest saved one) once a second. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button toggle;
    private SessionView view;
    private Session previous;
    private boolean previousLoaded;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = Ui.dp(this, 12);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22);
        title.setTextColor(Ui.TEXT);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button history = new Button(this);
        history.setText(R.string.history);
        history.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        bar.addView(history);
        col.addView(bar);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, pad, 0, pad / 2);
        col.addView(status);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (ChargeService.running) ChargeService.stop(this); else ChargeService.start(this);
            handler.postDelayed(this::update, 300);
        });
        col.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        view = new SessionView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        col.addView(view, lp);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        if (b == null) ChargeService.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        previousLoaded = false;
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void update() {
        Session live = ChargeService.live;
        if (live != null) {
            status.setText(getString(R.string.status_recording, Session.plugName(live.plug)));
            previousLoaded = false;
            view.show(live);
        } else {
            String st = getString(ChargeService.running ? R.string.status_waiting : R.string.status_stopped);
            status.setText(previous == null ? st : st + "\n" + getString(R.string.showing_previous));
            if (!previousLoaded) {
                previousLoaded = true;
                loadPrevious();
            }
        }
        toggle.setText(ChargeService.running ? R.string.stop_service : R.string.start_service);
    }

    private void loadPrevious() {
        new Thread(() -> {
            Session s = null;
            List<File> fs = Store.list(this);
            for (File f : fs) {
                try { s = Store.load(f); break; } catch (Exception ignored) { /* skip unreadable */ }
            }
            Session found = s;
            runOnUiThread(() -> {
                previous = found;
                if (ChargeService.live == null) view.show(previous);
            });
        }).start();
    }
}
