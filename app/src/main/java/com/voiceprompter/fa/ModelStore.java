package com.voiceprompter.fa;

import android.content.Context;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs (unzips) and loads the Persian Vosk model. */
public final class ModelStore {
    private ModelStore() {}

    /** Model zip bundled in the APK by the build (may be absent). */
    public static final String ASSET = "model-fa.zip";

    private static Model model;
    private static volatile boolean installing;

    private static File root(Context c) { return new File(c.getFilesDir(), "model-fa"); }

    private static boolean looksLikeModel(File d) {
        return d.isDirectory() && (new File(d, "am").isDirectory() || new File(d, "conf").isDirectory());
    }

    /** Folder that holds am/, conf/, graph/ ... or null when no model is installed. */
    public static File modelDir(Context c) {
        File r = root(c);
        if (looksLikeModel(r)) return r;
        File[] kids = r.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (looksLikeModel(k)) return k;
            }
        }
        return null;
    }

    public static boolean isInstalled(Context c) { return modelDir(c) != null; }

    public static boolean isInstalling() { return installing; }

    /** Call before starting the install thread, and {@link #installDone()} when it ends. */
    public static void installStarting() { installing = true; }

    public static void installDone() { installing = false; }

    public static boolean hasAsset(Context c) {
        try (InputStream in = c.getAssets().open(ASSET)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** words.txt of the installed model, or null when the model does not ship one. */
    public static File wordsFile(Context c) {
        File d = modelDir(c);
        if (d == null) return null;
        File w = new File(d, "graph/words.txt");
        return w.isFile() ? w : null;
    }

    /** Unzips a Vosk model zip. Blocking; call from a background thread. */
    public static void install(Context c, InputStream zip) throws IOException {
        installing = true;
        File tmp = new File(c.getFilesDir(), "model-fa.tmp");
        try {
            deleteTree(tmp);
            if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
            String base = tmp.getCanonicalPath() + File.separator;
            byte[] buf = new byte[64 * 1024];
            try (ZipInputStream zin = new ZipInputStream(zip)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    File out = new File(tmp, e.getName());
                    if (!out.getCanonicalPath().startsWith(base)) throw new IOException("bad zip entry");
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
            }
            boolean ok = looksLikeModel(tmp);
            File[] kids = tmp.listFiles();
            if (!ok && kids != null) {
                for (File k : kids) ok |= looksLikeModel(k);
            }
            if (!ok) throw new IOException("این فایل مدل Vosk نیست");
            synchronized (ModelStore.class) {
                if (model != null) {
                    model.close();
                    model = null;
                }
                File r = root(c);
                deleteTree(r);
                if (!tmp.renameTo(r)) throw new IOException("cannot move model into place");
            }
        } finally {
            deleteTree(tmp);
            installing = false;
        }
    }

    /** Loads the model once and keeps it for the life of the process. Blocking. */
    public static synchronized Model get(Context c) throws IOException {
        if (model == null) {
            File d = modelDir(c);
            if (d == null) throw new IOException("مدل فارسی نصب نیست");
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(d.getAbsolutePath());
        }
        return model;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
