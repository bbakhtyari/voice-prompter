package com.voiceprompter.fa;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/** The speech engines the app can use, and which of them this build contains. */
final class Engines {
    private Engines() {}

    static final int VOSK = 0;
    static final int SHENAVA_RIZEH = 1;
    static final int SHENAVA_KOOCHIK = 2;

    static final int[] ALL = {VOSK, SHENAVA_RIZEH, SHENAVA_KOOCHIK};

    static String label(int engine) {
        switch (engine) {
            case SHENAVA_RIZEH: return "Shenava ریزه (سبک‌تر)";
            case SHENAVA_KOOCHIK: return "Shenava کوچیک (دقیق‌تر، سنگین‌تر)";
            default: return "Vosk (سریع، با محدودسازی لغات)";
        }
    }

    static String shortName(int engine) {
        switch (engine) {
            case SHENAVA_RIZEH: return "Shenava ریزه";
            case SHENAVA_KOOCHIK: return "Shenava کوچیک";
            default: return "Vosk";
        }
    }

    /** Asset folder of a Shenava model. */
    static String assetDir(int engine) {
        return engine == SHENAVA_KOOCHIK ? "shenava-koochik" : "shenava-rizeh";
    }

    static boolean available(Context c, int engine) {
        if (engine == VOSK) return true;
        if (!SherpaDecoder.AVAILABLE) return false;
        try (InputStream in = c.getAssets().open(assetDir(engine) + "/tokens.txt")) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The engine to use: the chosen one, or Vosk when this build does not have it. */
    static int effective(Context c) {
        int e = Prefs.i(c, Prefs.ENGINE);
        return available(c, e) ? e : VOSK;
    }
}
