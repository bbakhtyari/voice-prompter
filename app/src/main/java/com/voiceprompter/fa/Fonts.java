package com.voiceprompter.fa;

import android.content.Context;
import android.graphics.Typeface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Built-in Vazirmatn, the device font, or any font file the user imported. */
final class Fonts {
    private Fonts() {}

    static final String VAZIR = "";
    static final String SYSTEM = "system";

    private static Typeface regular, bold;
    private static String customName;
    private static Typeface custom;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** File names of the imported fonts. */
    static List<String> imported(Context c) {
        List<String> out = new ArrayList<>();
        String[] names = dir(c).list();
        if (names != null) {
            Arrays.sort(names);
            out.addAll(Arrays.asList(names));
        }
        return out;
    }

    static String displayName(String name) {
        if (VAZIR.equals(name)) return "وزیرمتن";
        if (SYSTEM.equals(name)) return "فونت دستگاه";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static Typeface get(Context c, String name, boolean isBold) {
        try {
            if (VAZIR.equals(name)) {
                if (isBold) {
                    if (bold == null) bold = Typeface.createFromAsset(c.getAssets(), "fonts/Vazirmatn-Bold.ttf");
                    return bold;
                }
                if (regular == null) regular = Typeface.createFromAsset(c.getAssets(), "fonts/Vazirmatn-Regular.ttf");
                return regular;
            }
            if (!SYSTEM.equals(name)) {
                if (custom == null || !name.equals(customName)) {
                    File f = new File(dir(c), name);
                    if (!f.isFile()) throw new RuntimeException("missing font");
                    custom = Typeface.createFromFile(f);
                    customName = name;
                }
                return isBold ? Typeface.create(custom, Typeface.BOLD) : custom;
            }
        } catch (RuntimeException e) {
            // fall through to the device font
        }
        return Typeface.create(Typeface.DEFAULT, isBold ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** True when the file can be opened as a font. */
    static boolean isUsable(File f) {
        try {
            return Typeface.createFromFile(f) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
