package com.voiceprompter.fa;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Scripts are plain UTF-8 files: first line is the title, the rest is the text. */
public final class ScriptStore {
    private ScriptStore() {}

    public static final class Item {
        public final String id;
        public final String title;

        Item(String id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() { return title; }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "scripts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String id) { return new File(dir(c), id + ".txt"); }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".txt")) continue;
            String id = name.substring(0, name.length() - 4);
            String[] s = load(c, id);
            if (s != null) out.add(new Item(id, s[0].isEmpty() ? "بدون عنوان" : s[0]));
        }
        return out;
    }

    /** Returns {title, body} or null. */
    public static String[] load(Context c, String id) {
        try {
            String all = new String(Files.readAllBytes(file(c, id).toPath()), StandardCharsets.UTF_8);
            int nl = all.indexOf('\n');
            if (nl < 0) return new String[]{all, ""};
            return new String[]{all.substring(0, nl), all.substring(nl + 1)};
        } catch (IOException e) {
            return null;
        }
    }

    /** Saves and returns the script id (a new one when id is null). */
    public static String save(Context c, String id, String title, String body) throws IOException {
        if (id == null) id = "s" + System.currentTimeMillis();
        String t = title.replace('\n', ' ').replace('\r', ' ').trim();
        Files.write(file(c, id).toPath(), (t + "\n" + body).getBytes(StandardCharsets.UTF_8));
        return id;
    }

    public static void delete(Context c, String id) {
        //noinspection ResultOfMethodCallIgnored
        file(c, id).delete();
    }
}
