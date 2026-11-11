package com.zmlworks.chargelog;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** List of saved sessions, newest first; tap to open one. */
public class HistoryActivity extends Activity {
    private final List<File> files = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Ui.GRID));
        list.setDividerHeight(1);
        empty = new TextView(this);
        empty.setText(R.string.loading);
        empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), 0, 0);
        TextView head = new TextView(this);
        head.setText(R.string.history_title);
        head.setTextSize(22);
        head.setTextColor(Ui.TEXT);
        int pad = Ui.dp(this, 12);
        head.setPadding(pad, pad, pad, pad);
        list.addHeaderView(head, null, false);
        list.addFooterView(empty, null, false);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup p) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(HistoryActivity.this);
                t.setPadding(pad, pad, pad, pad);
                t.setTextSize(15);
                t.setTextColor(Ui.TEXT);
                t.setText(rows.get(i));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            int i = pos - list.getHeaderViewsCount();
            if (i >= 0 && i < files.size())
                startActivity(new Intent(this, SessionActivity.class).putExtra(SessionActivity.EXTRA_FILE, files.get(i).getName()));
        });
        setContentView(list);
        Ui.edgeToEdge(this, list);
        list.setClipToPadding(false);
    }

    @Override protected void onResume() {
        super.onResume();
        new Thread(() -> {
            List<File> fs = new ArrayList<>();
            List<String> rs = new ArrayList<>();
            SimpleDateFormat df = new SimpleDateFormat("yyyy/M/d (E) HH:mm", Locale.JAPAN);
            for (File f : Store.list(this)) {
                try {
                    Session s = Store.load(f);
                    if (s.size() == 0) continue;
                    rs.add(df.format(new Date(s.start)) + "  " + Session.plugName(s.plug) + "\n"
                            + Session.duration(s.elapsedMs()) + "   " + s.levelStart() + "→" + s.last().level + "%   "
                            + Session.num(s.mah(), "%.0f mAh") + "   " + Session.num(s.wh(), "%.2f Wh"));
                    fs.add(f);
                } catch (Exception ignored) { /* unreadable file: skip */ }
            }
            runOnUiThread(() -> {
                files.clear(); files.addAll(fs);
                rows.clear(); rows.addAll(rs);
                adapter.notifyDataSetChanged();
                empty.setText(rows.isEmpty() ? getString(R.string.no_history) : "");
            });
        }).start();
    }
}
