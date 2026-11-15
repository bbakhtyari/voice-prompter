package com.voiceprompter.fa;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

/** Maps keys of a Bluetooth remote, pedal or keyboard to prompter actions. */
public final class Keys {
    private Keys() {}

    public static final int NONE = 0;
    public static final int PLAY = 1;
    public static final int FASTER = 2;
    public static final int SLOWER = 3;
    public static final int BACK = 4;
    public static final int FORWARD = 5;
    public static final int RETAKE = 6;

    public static final int[] ACTIONS = {PLAY, FASTER, SLOWER, BACK, FORWARD, RETAKE};

    public static String label(int action) {
        switch (action) {
            case PLAY: return "پخش / توقف";
            case FASTER: return "سرعت بیشتر (در حالت صدا: یک خط جلو)";
            case SLOWER: return "سرعت کمتر (در حالت صدا: یک خط عقب)";
            case BACK: return "یک خط عقب";
            case FORWARD: return "یک خط جلو";
            case RETAKE: return "اشتباه شد: توقف و دو خط عقب";
            default: return "";
        }
    }

    private static String prefKey(int action) { return "key_" + action; }

    /** Key the user assigned to this action, or 0. */
    public static int custom(Context c, int action) { return Prefs.sp(c).getInt(prefKey(action), 0); }

    public static void setCustom(Context c, int action, int keyCode) {
        SharedPreferences.Editor e = Prefs.sp(c).edit();
        if (keyCode != 0) {
            for (int a : ACTIONS) {
                if (a != action && custom(c, a) == keyCode) e.putInt(prefKey(a), 0);
            }
        }
        e.putInt(prefKey(action), keyCode).apply();
    }

    public static int actionFor(Context c, int keyCode) {
        if (keyCode == 0 || keyCode == KeyEvent.KEYCODE_BACK) return NONE;
        for (int a : ACTIONS) {
            if (custom(c, a) == keyCode) return a;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return PLAY;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return FASTER;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return SLOWER;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                return BACK;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return FORWARD;
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_R:
                return RETAKE;
            default:
                return NONE;
        }
    }

    public static String defaultsFor(int action) {
        switch (action) {
            case PLAY: return "Space / Enter";
            case FASTER: return "→  یا  +";
            case SLOWER: return "←  یا  −";
            case BACK: return "↑ / PageUp";
            case FORWARD: return "↓ / PageDown";
            case RETAKE: return "Backspace / R";
            default: return "";
        }
    }

    public static String name(int keyCode) {
        String s = KeyEvent.keyCodeToString(keyCode);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }
}
