package com.zmlworks.chargelog;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import java.io.File;

/** One saved session: numbers, graph and a delete button. */
public class SessionActivity extends Activity {
    static final String EXTRA_FILE = "file";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String name = getIntent().getStringExtra(EXTRA_FILE);
        File f = name == null ? null : new File(Store.dir(this), new File(name).getName());
        int pad = Ui.dp(this, 12);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        SessionView view = new SessionView(this);
        col.addView(view);
        Button del = new Button(this);
        del.setText(R.string.delete);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        col.addView(del, lp);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (f != null && f.delete()) finish();
                    else Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        Ui.edgeToEdge(this, sv);
        try {
            if (f == null) throw new java.io.IOException("no file");
            view.show(Store.load(f));
        } catch (Exception e) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
