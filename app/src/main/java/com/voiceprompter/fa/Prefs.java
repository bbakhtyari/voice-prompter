package com.voiceprompter.fa;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings, with their defaults in one place. */
public final class Prefs {
    private Prefs() {}

    public static final String FONT_SP = "font_sp";
    public static final String LINE_SPACING = "line_spacing_x10";
    public static final String MARGIN = "margin_pct";
    public static final String READ_POS = "read_pos_pct";
    public static final String SPEED = "speed";
    public static final String MIC = "mic_source";
    /** Speech engine: 0 = Vosk, 1 = Shenava Rizeh, 2 = Shenava Koochik. */
    public static final String ENGINE = "engine";

    public static final String MIRROR_H = "mirror_h";
    public static final String MIRROR_V = "mirror_v";
    /** 0 = right, 1 = centre, 2 = left. */
    public static final String ALIGN = "align";
    public static final String BOLD = "bold";
    /** Screen rotation: 0 = follow the device, 1 = landscape, 2 = portrait, 3/4 = the same, upside down. */
    public static final String ORIENT = "orientation";
    /** "" = Vazirmatn, "system" = device font, otherwise the file name of an imported font. */
    public static final String FONT = "font_name";
    /** Stop and go two lines back when the speaker slips. */
    public static final String AUTO_RETAKE = "auto_retake";
    public static final String RETAKE_SENS = "retake_sensitivity";
    public static final String HIGHLIGHT = "highlight";
    public static final String SHOW_HEARD = "show_heard";
    public static final String RESTRICT = "restrict_vocab";
    public static final String VOICE_MODE = "voice_mode";

    /** Set while a vocabulary-restricted recogniser is being created (crash guard). */
    public static final String GRAMMAR_GUARD = "grammar_guard";
    /** Set while a Shenava model is loaded (crash guard). */
    public static final String ENGINE_GUARD = "engine_guard";
    public static final String SEEDED = "seeded";

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    private static int defInt(String key) {
        switch (key) {
            case FONT_SP: return 48;
            case LINE_SPACING: return 13;
            case MARGIN: return 6;
            case READ_POS: return 35;
            case SPEED: return 18;
            case RETAKE_SENS: return 1;
            default: return 0;
        }
    }

    private static boolean defBool(String key) {
        switch (key) {
            case HIGHLIGHT:
            case SHOW_HEARD:
            case RESTRICT:
            case VOICE_MODE:
                return true;
            default:
                return false;
        }
    }

    public static int i(Context c, String key) { return sp(c).getInt(key, defInt(key)); }

    public static boolean b(Context c, String key) { return sp(c).getBoolean(key, defBool(key)); }

    public static void put(Context c, String key, int value) { sp(c).edit().putInt(key, value).apply(); }

    public static void put(Context c, String key, boolean value) { sp(c).edit().putBoolean(key, value).apply(); }

    public static String s(Context c, String key) { return sp(c).getString(key, ""); }

    public static void put(Context c, String key, String value) { sp(c).edit().putString(key, value).apply(); }
}
