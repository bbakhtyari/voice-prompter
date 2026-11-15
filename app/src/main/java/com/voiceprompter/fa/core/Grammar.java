package com.voiceprompter.fa.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the script into the phrase list Vosk uses to restrict its vocabulary.
 * With only the script's own words to choose from, the recogniser makes far
 * fewer mistakes than in free dictation.
 */
public final class Grammar {
    private Grammar() {}

    public static final class Result {
        /** Phrases for the recogniser; empty when nothing usable was found. */
        public final List<String> phrases;
        /** Script words the model does not know (only filled when the model has a word list). */
        public final List<String> unknown;

        Result(List<String> phrases, List<String> unknown) {
            this.phrases = phrases;
            this.unknown = unknown;
        }
    }

    private static List<List<String>> sentences(String script) {
        List<List<String>> out = new ArrayList<>();
        for (String sent : script.split("[\\n\\r.!?؟؛;:…]+")) {
            List<String> words = new ArrayList<>();
            for (String raw : sent.trim().split("\\s+")) {
                String w = TextNorm.clean(raw);
                if (!w.isEmpty()) words.add(w);
            }
            if (!words.isEmpty()) out.add(words);
        }
        return out;
    }

    /**
     * @param wordsTxt the model's graph/words.txt, or null when it has none
     */
    public static Result build(String script, File wordsTxt) {
        List<List<String>> sents = sentences(script);
        Set<String> phrases = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        String zw = String.valueOf(TextNorm.ZWNJ);

        if (wordsTxt == null) {
            // The model's spelling of half-space words is not known, so offer all three
            // spellings; the recogniser silently ignores the ones it does not have.
            for (List<String> s : sents) {
                // Persian keyboards and Persian models use ی and ک, not the Arabic ي and ك.
                String asIs = String.join(" ", s).replace('\u064A', '\u06CC').replace('\u0643', '\u06A9');
                phrases.add(asIs);
                phrases.add(asIs.replace(zw, ""));
                phrases.add(asIs.replace(zw, " "));
            }
            return new Result(new ArrayList<>(phrases), unknown);
        }

        // Collect every spelling we might need, then scan the word list once.
        Set<String> wanted = new HashSet<>();
        Map<String, String> byKey = new HashMap<>();
        for (List<String> s : sents) {
            for (String w : s) {
                wanted.add(w);
                wanted.add(w.replace(zw, ""));
                byKey.put(TextNorm.key(w), null);
                for (String part : w.split(zw)) {
                    if (part.isEmpty()) continue;
                    wanted.add(part);
                    byKey.put(TextNorm.key(part), null);
                }
            }
        }
        Set<String> known = new HashSet<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(wordsTxt), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                String tok = sp < 0 ? line : line.substring(0, sp);
                if (tok.isEmpty() || tok.charAt(0) == '<' || tok.charAt(0) == '#' || tok.charAt(0) == '!') continue;
                if (wanted.contains(tok)) known.add(tok);
                String k = TextNorm.key(tok);
                if (byKey.containsKey(k) && byKey.get(k) == null) byKey.put(k, tok);
            }
        } catch (IOException e) {
            return build(script, null);
        }

        Set<String> unknownSet = new LinkedHashSet<>();
        for (List<String> s : sents) {
            StringBuilder sb = new StringBuilder();
            for (String w : s) {
                String r = resolve(w, zw, known, byKey);
                if (r == null) {
                    unknownSet.add(w);
                    continue;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(r);
            }
            if (sb.length() > 0) phrases.add(sb.toString());
        }
        unknown.addAll(unknownSet);
        return new Result(new ArrayList<>(phrases), unknown);
    }

    private static String one(String w, Set<String> known, Map<String, String> byKey) {
        if (known.contains(w)) return w;
        return byKey.get(TextNorm.key(w));
    }

    private static String resolve(String w, String zw, Set<String> known, Map<String, String> byKey) {
        String r = one(w, known, byKey);
        if (r != null) return r;
        if (w.contains(zw)) {
            String joined = w.replace(zw, "");
            if (known.contains(joined)) return joined;
            StringBuilder sb = new StringBuilder();
            for (String part : w.split(zw)) {
                if (part.isEmpty()) continue;
                String p = one(part, known, byKey);
                if (p == null) return null;
                if (sb.length() > 0) sb.append(' ');
                sb.append(p);
            }
            return sb.length() > 0 ? sb.toString() : null;
        }
        return null;
    }
}
