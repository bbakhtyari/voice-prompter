package com.voiceprompter.fa.core;

/**
 * Persian text normalisation used for matching speech against the script.
 * Both the script and the recogniser output go through {@link #key(String)},
 * so spelling differences (ي/ی, ك/ک, half-space, diacritics) never matter.
 */
public final class TextNorm {
    private TextNorm() {}

    public static final char ZWNJ = '‌';

    /** Matching key: unified letters only, no half-space, no punctuation. */
    public static String key(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char m = mapChar(s.charAt(i));
            if (m != 0) sb.append(m);
        }
        return sb.toString();
    }

    /** Word as it may appear in a speech model vocabulary: letters and half-space only. */
    public static String clean(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ZWNJ) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ZWNJ) sb.append(c);
            } else if (Character.isDigit(c)) {
                return ""; // digits are never spoken as written
            } else if (isMark(c) || c == 'ـ') {
                // drop diacritics and tatweel
            } else if (Character.isLetter(c)) {
                sb.append(c);
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ZWNJ) end--;
        return sb.substring(0, end);
    }

    private static boolean isMark(char c) {
        return (c >= 'ً' && c <= 'ٟ') || c == 'ٰ'
                || (c >= 'ۖ' && c <= 'ۭ');
    }

    /** Returns 0 for characters that are dropped. */
    static char mapChar(char c) {
        switch (c) {
            case 'ي': // ي
            case 'ى': // ى
            case 'ئ': // ئ
                return 'ی'; // ی
            case 'ك': // ك
                return 'ک'; // ک
            case 'ة': // ة
            case 'ۀ': // ۀ
            case 'ہ':
            case 'ە':
                return 'ه'; // ه
            case 'أ': // أ
            case 'إ': // إ
            case 'آ': // آ
            case 'ٱ':
                return 'ا'; // ا
            case 'ؤ': // ؤ
                return 'و'; // و
            case 'ء': // ء
            case 'ـ': // tatweel
            case '‌':
            case '‍':
            case '‎':
            case '‏':
            case '­':
                return 0;
            default:
                break;
        }
        if (isMark(c)) return 0;
        if (c >= '۰' && c <= '۹') return (char) ('0' + (c - '۰'));
        if (c >= '٠' && c <= '٩') return (char) ('0' + (c - '٠'));
        if (Character.isLetterOrDigit(c)) return Character.toLowerCase(c);
        return 0;
    }

    private static final String[][] VARIANTS = {
            {"را", "رو"}, {"یک", "یه"}, {"ان", "اون"}, {"انها", "اونا"}, {"انها", "اونها"},
            {"چه", "چی"}, {"تو", "توی"}, {"اگر", "اگه"}, {"دیگر", "دیگه"}, {"است", "هست"},
            {"مگر", "مگه"}, {"اخر", "اخه"}, {"شود", "شه"}, {"بشود", "بشه"},
            {"میشود", "میشه"}, {"نمیشود", "نمیشه"}, {"میدهد", "میده"},
            {"میگوید", "میگه"}, {"میرود", "میره"}, {"میاید", "میاد"}, {"هستند", "هستن"}
    };

    private static boolean variant(String a, String b) {
        for (String[] v : VARIANTS) {
            if ((v[0].equals(a) && v[1].equals(b)) || (v[0].equals(b) && v[1].equals(a))) return true;
        }
        return false;
    }

    /**
     * Similarity of two keys in [0,1]; 0 means "not the same word".
     * Tolerates the usual written/spoken differences (می‌خواهم / می‌خوام, خانه / خونه).
     */
    public static double sim(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (variant(a, b)) return 0.9;
        int la = a.length(), lb = b.length();
        int mx = Math.max(la, lb), mn = Math.min(la, lb);
        if (mn < 3) return 0;
        if (mx - mn > 3) return 0;
        int d = lev(a, b);
        if (mx <= 4) return d <= 1 ? 1.0 - (double) d / mx : 0;
        double s = 1.0 - (double) d / mx;
        return s >= 0.6 ? s : 0;
    }

    static int lev(String a, String b) {
        int la = a.length(), lb = b.length();
        int[] prev = new int[lb + 1], cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int c = prev[j - 1] + (ca == b.charAt(j - 1) ? 0 : 1);
                int ins = cur[j - 1] + 1, del = prev[j] + 1;
                cur[j] = Math.min(c, Math.min(ins, del));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[lb];
    }
}
