package iq.ievent.domain;

/**
 * Length guard for free-text VARCHAR columns. Every form field and every
 * machine-translated string funnels through an entity setter, so clipping
 * here (instead of in each of the dozens of call sites) is what keeps a long
 * paste or a verbose translation from surfacing as a 500 at publish time.
 * Clips on code points so a trailing Arabic letter or emoji is never split.
 */
public final class Text {

    private Text() {}

    public static String clip(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int end = s.offsetByCodePoints(0, Math.min(s.codePointCount(0, s.length()), max));
        // a UTF-16 surrogate pair counts as one code point but two chars: back off if needed
        while (end > max) end = s.offsetByCodePoints(0, s.codePointCount(0, end) - 1);
        return s.substring(0, end).stripTrailing();
    }
}
