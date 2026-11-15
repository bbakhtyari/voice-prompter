package com.voiceprompter.fa.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows the speaker through the script.
 *
 * The recogniser's latest words are aligned (fuzzy, order-preserving) against a
 * window of the script around the current position. Reading on moves the
 * position word by word; skipping ahead or re-reading an earlier passage is
 * followed only when several words agree, so one misheard word never makes
 * the text jump.
 */
public final class Tracker {
    private static final int TAIL = 8;       // recognised words considered
    private static final int CONTEXT = 4;    // words kept from the previous utterance
    private static final int SETTLE = 3;     // unchanged partials before the last word is trusted
    private static final int BACK = 40;      // script words searched behind the position
    private static final int AHEAD = 60;     // script words searched ahead

    private static final double GAP_T = 1.0; // a recognised word that is not in the script
    private static final double GAP_S = 1.5; // a script word that was not heard
    private static final double SUB = 1.5;   // a word heard as something else

    private final String[] keys;
    private int pos;
    private String[] context = new String[0];
    private String lastText = "";
    private int repeats;
    private int finalized;      // words heard in finished utterances
    private int lastMoveTotal;  // words heard when the position last moved
    private int offScript;      // words heard since then

    public Tracker(String[] keys) { this.keys = keys; }

    /** Index of the next word the speaker is expected to say. */
    public int position() { return pos; }

    public int size() { return keys.length; }

    public void setPosition(int p) {
        pos = Math.max(0, Math.min(keys.length, p));
        context = new String[0];
        lastText = "";
        repeats = 0;
        finalized = 0;
        lastMoveTotal = 0;
        offScript = 0;
    }

    /**
     * How many words have been heard since the position last moved. A handful of
     * words that fit nowhere near the reading position means the speaker slipped
     * or went off script.
     */
    public int offScriptWords() { return offScript; }

    /**
     * Feed a partial or final recogniser text. Call it for every audio chunk,
     * also when the text did not change: a partial that stays the same for a
     * few chunks is treated as settled. Returns true when the position moved.
     */
    public boolean onHypothesis(String text, boolean isFinal) {
        if (text == null) text = "";
        boolean settled = false;
        if (!isFinal) {
            if (text.equals(lastText)) {
                repeats++;
                if (repeats != SETTLE) return false; // nothing new to look at
                settled = true;
            } else {
                lastText = text;
                repeats = 0;
            }
        } else {
            lastText = "";
            repeats = 0;
        }

        List<String> all = new ArrayList<>();
        for (String c : context) all.add(c);
        int heard = 0;
        for (String raw : text.trim().split("\\s+")) {
            if (raw.isEmpty()) continue;
            heard++; // unknown words count as heard, they just cannot match
            if (raw.equals("[unk]")) continue;
            String k = TextNorm.key(raw);
            if (!k.isEmpty()) all.add(k);
        }
        int size = all.size();
        int fresh = size - context.length;

        boolean moved = false;
        if (fresh > 0) {
            if (isFinal || settled) {
                moved = align(tailOf(all, size), false);
            } else {
                // The last word of a partial result is often revised a moment later.
                // Trust everything before it fully; let the last word only confirm
                // the very next script word.
                if (fresh > 1) moved = align(tailOf(all, size - 1), false);
                moved |= align(tailOf(all, size), true);
            }
        }
        int total = finalized + heard;
        if (moved || total < lastMoveTotal) lastMoveTotal = total;
        offScript = total - lastMoveTotal;
        if (isFinal) {
            finalized = total;
            int cf = Math.max(0, size - CONTEXT);
            context = all.subList(cf, size).toArray(new String[0]);
        }
        return moved;
    }

    private static String[] tailOf(List<String> all, int end) {
        int from = Math.max(0, end - TAIL);
        return all.subList(from, end).toArray(new String[0]);
    }

    private static double weight(int len) { return Math.max(1, Math.min(len, 8)); }

    private boolean align(String[] tail, boolean nextWordOnly) {
        final int n = keys.length;
        final int a = Math.max(0, pos - BACK), b = Math.min(n, pos + AHEAD);
        final int m = b - a, k = tail.length;
        if (m <= 0) return false;

        double[][] h = new double[k + 1][m + 1];
        int[][] nm = new int[k + 1][m + 1]; // matched script words at or after pos
        int[][] tm = new int[k + 1][m + 1]; // all matched script words
        int[][] run = new int[k + 1][m + 1]; // consecutive matched words ending here

        // best alignment that ends on each script word of the window
        double[] endS = new double[m];
        int[] endNm = new int[m];
        int[] endTm = new int[m];
        int[] endRun = new int[m];
        java.util.Arrays.fill(endS, -1);

        for (int i = 1; i <= k; i++) {
            String t = tail[i - 1];
            for (int j = 1; j <= m; j++) {
                int w = a + j - 1;
                String key = keys[w];

                double best = 0; int bNm = 0, bTm = 0;
                double v = h[i - 1][j] - GAP_T;
                if (v > best) { best = v; bNm = nm[i - 1][j]; bTm = tm[i - 1][j]; }
                v = h[i][j - 1] - GAP_S;
                if (v > best) { best = v; bNm = nm[i][j - 1]; bTm = tm[i][j - 1]; }
                v = h[i - 1][j - 1] - SUB;
                if (v > best) { best = v; bNm = nm[i - 1][j - 1]; bTm = tm[i - 1][j - 1]; }

                double ms = -1; int mNm = 0, mTm = 0, mRun = 0;
                int isNew = w >= pos ? 1 : 0;

                double s = TextNorm.sim(t, key);
                if (s > 0) {
                    ms = h[i - 1][j - 1] + s * weight(key.length());
                    mNm = nm[i - 1][j - 1] + isNew;
                    mTm = tm[i - 1][j - 1] + 1;
                    mRun = run[i - 1][j - 1] + 1;
                }
                if (i >= 2) { // "می" + "خواهم" heard for one written word
                    double s2 = TextNorm.sim(tail[i - 2] + t, key);
                    if (s2 >= 0.75) {
                        double c = h[i - 2][j - 1] + s2 * weight(key.length());
                        if (c > ms) {
                            ms = c;
                            mNm = nm[i - 2][j - 1] + isNew;
                            mTm = tm[i - 2][j - 1] + 1;
                            mRun = run[i - 2][j - 1] + 1;
                        }
                    }
                }
                if (j >= 2) { // one heard word covering two written words
                    String two = keys[w - 1] + key;
                    double s3 = TextNorm.sim(t, two);
                    if (s3 >= 0.75) {
                        double c = h[i - 1][j - 2] + s3 * weight(two.length());
                        if (c > ms) {
                            ms = c;
                            mNm = nm[i - 1][j - 2] + isNew + (w - 1 >= pos ? 1 : 0);
                            mTm = tm[i - 1][j - 2] + 2;
                            mRun = run[i - 1][j - 2] + 2;
                        }
                    }
                }

                int bRun = 0; // a gap or a wrong word breaks the run
                if (ms >= 0) {
                    if (ms > endS[j - 1]) {
                        endS[j - 1] = ms; endNm[j - 1] = mNm; endTm[j - 1] = mTm; endRun[j - 1] = mRun;
                    }
                    if (ms >= best) { best = ms; bNm = mNm; bTm = mTm; bRun = mRun; }
                }
                h[i][j] = best; nm[i][j] = bNm; tm[i][j] = bTm; run[i][j] = bRun;
            }
        }

        // How well do the heard words fit "we are still where we thought"?
        double stay = 0;
        for (int w = Math.max(a, pos - 3); w < pos; w++) {
            if (endS[w - a] > stay) stay = endS[w - a];
        }

        double fwdVal = -1, fwdS = 0; int fwdEnd = -1;
        int lastW = nextWordOnly ? Math.min(b, pos + 1) : b;
        for (int w = pos; w < lastW; w++) {
            double s = endS[w - a];
            if (s < 0) continue;
            int adv = w + 1 - pos;
            int r = Math.min(endRun[w - a], endNm[w - a]); // consecutive new words
            boolean ok;
            if (adv == 1) ok = true;                       // the very next word
            else if (adv == 2) ok = s >= 3.0 && s >= stay + 1.0;            // one word missed
            else if (adv <= 10) ok = r >= 2 && s >= 4.0 && s >= stay + 1.0; // short skip: two words in a row
            else ok = r >= 3 && s >= 12.0 && s >= stay + 9.0;               // long skip: clearly better
            if (!ok) continue;
            double val = s - 0.05 * (adv - 1);
            if (val > fwdVal) { fwdVal = val; fwdS = s; fwdEnd = w; }
        }
        if (nextWordOnly) {
            if (fwdEnd >= 0) { pos = fwdEnd + 1; return true; }
            return false;
        }

        double backS = -1; int backEnd = -1;
        for (int w = a; w + 1 <= pos - 3; w++) {
            double s = endS[w - a];
            if (s >= 14.0 && endTm[w - a] >= 4 && s > backS) { backS = s; backEnd = w; }
        }

        if (backEnd >= 0 && backS >= Math.max(stay, fwdS) + 5.0) {
            pos = backEnd + 1;
            return true;
        }
        if (fwdEnd >= 0) {
            pos = fwdEnd + 1;
            return true;
        }
        return false;
    }
}
