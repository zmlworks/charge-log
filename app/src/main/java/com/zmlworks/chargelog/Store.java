package com.zmlworks.chargelog;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sessions are plain text files: files/sessions/&lt;start ms&gt;.csv (see {@link Session#header()}). */
final class Store {
    private Store() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sessions");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("cannot create " + d);
        return d;
    }

    static File file(Context c, long start) { return new File(dir(c), start + ".csv"); }

    /** Newest first. */
    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.endsWith(".csv"));
        if (fs == null) return new ArrayList<>();
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        return new ArrayList<>(Arrays.asList(fs));
    }

    static Session load(File f) throws IOException {
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            return Session.read(r);
        }
    }
}
