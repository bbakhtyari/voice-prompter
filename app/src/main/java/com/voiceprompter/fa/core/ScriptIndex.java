package com.voiceprompter.fa.core;

import java.util.ArrayList;
import java.util.List;

/** Splits the script into words and remembers where each one sits in the text. */
public final class ScriptIndex {
    public final String text;
    private final int[] starts;
    private final int[] ends;
    private final String[] keys;

    public ScriptIndex(String text) {
        this.text = text;
        List<int[]> spans = new ArrayList<>();
        List<String> ks = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int s = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > s) {
                String k = TextNorm.key(text.substring(s, i));
                if (!k.isEmpty()) {
                    spans.add(new int[]{s, i});
                    ks.add(k);
                }
            }
        }
        starts = new int[spans.size()];
        ends = new int[spans.size()];
        for (int j = 0; j < starts.length; j++) {
            starts[j] = spans.get(j)[0];
            ends[j] = spans.get(j)[1];
        }
        keys = ks.toArray(new String[0]);
    }

    public int size() { return keys.length; }

    public String[] keys() { return keys; }

    /** Character offset where word {@code i} starts; text length when past the end. */
    public int start(int i) {
        if (i < 0) return 0;
        if (i >= starts.length) return text.length();
        return starts[i];
    }

    public int end(int i) {
        if (i < 0) return 0;
        if (i >= ends.length) return text.length();
        return ends[i];
    }

    public String raw(int i) { return text.substring(starts[i], ends[i]); }

    /** Index of the first word that ends after the given character offset. */
    public int wordAtOrAfter(int offset) {
        int lo = 0, hi = ends.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] > offset) hi = mid; else lo = mid + 1;
        }
        return lo;
    }
}
