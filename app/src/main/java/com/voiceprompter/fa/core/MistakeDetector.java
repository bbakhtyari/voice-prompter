package com.voiceprompter.fa.core;

/**
 * Decides when the speaker has slipped: several heard words in a row that fit
 * nowhere near the reading position. Misheard words look the same as a slip,
 * so this is a trade-off between missing short slips and stopping for nothing;
 * the sensitivity setting picks the balance.
 */
public final class MistakeDetector {
    private MistakeDetector() {}

    public static final int LOW = 0, MEDIUM = 1, HIGH = 2;

    /** Unmatched words needed while still speaking / after a pause, per sensitivity. */
    private static final int[] WHILE_SPEAKING = {8, 7, 5};
    private static final int[] AFTER_PAUSE = {4, 3, 2};

    public static boolean isMistake(Tracker t, boolean pauseJustEnded, int sensitivity) {
        int p = t.position();
        if (p <= 0 || p >= t.size()) return false; // before the first word or after the last
        int s = Math.max(LOW, Math.min(HIGH, sensitivity));
        int off = t.offScriptWords();
        return off >= WHILE_SPEAKING[s] || (pauseJustEnded && off >= AFTER_PAUSE[s]);
    }
}
