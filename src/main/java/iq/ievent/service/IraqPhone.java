package iq.ievent.service;

import java.util.Optional;

/**
 * Iraqi mobile numbers (R35). There is no SMS verification yet, so the format check is
 * the only gate; it is strict about the numbering plan and forgiving about how people
 * type it.
 *
 * Numbering plan (Iraq, +964): a mobile number is ten digits starting with 7, the second
 * digit 3 to 9 (075x Korek, 077x Asiacell, 078x/079x Zain, 074x/076x smaller operators);
 * written domestically with the trunk zero as 07XX XXX XXXX. Fixed lines (Baghdad 01,
 * provinces 02 to 06) are deliberately rejected: hosts are contacted on a mobile.
 *
 * Accepted input: Western or Arabic-Indic digits (٠١٢٣٤٥٦٧٨٩ and the Persian/Kurdish
 * ۰-۹ forms), any spaces, dashes, dots or parentheses, and the prefixes +964, 00964,
 * 964 or the trunk 0 (also the common "+964 07..." mistake). Canonical stored form is
 * E.164: +9647XXXXXXXX.
 */
public final class IraqPhone {

    /** Country calling code, for the fixed prefix shown beside the input. */
    public static final String COUNTRY_CODE = "+964";

    private static final String ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩";
    private static final String EXTENDED_ARABIC_INDIC = "۰۱۲۳۴۵۶۷۸۹";

    private IraqPhone() {}

    /**
     * Canonical E.164 form ("+9647901234567") of an Iraqi mobile number typed in any of
     * the accepted shapes, or empty when the input is not a valid Iraqi mobile number
     * (including blank input: callers decide whether blank is allowed).
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) return Optional.empty();
        StringBuilder digits = new StringBuilder();
        boolean plus = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int ai = ARABIC_INDIC.indexOf(c);
            int ei = EXTENDED_ARABIC_INDIC.indexOf(c);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (ai >= 0) {
                digits.append((char) ('0' + ai));
            } else if (ei >= 0) {
                digits.append((char) ('0' + ei));
            } else if (c == '+' && digits.length() == 0 && !plus) {
                plus = true;
            } else if (c == ' ' || c == '-' || c == '.' || c == '(' || c == ')' || c == '\t'
                    || c == ' ' || c == '‎' || c == '‏' || c == '‪' || c == '‫'
                    || c == '‬' || c == '‭' || c == '‮' || c == '⁦' || c == '⁧'
                    || c == '⁨' || c == '⁩') {
                // separators and bidi control marks: ignored
            } else {
                return Optional.empty(); // letters or anything else: not a phone number
            }
        }
        String d = digits.toString();
        if (d.isEmpty()) return Optional.empty();

        String national;
        if (d.startsWith("00964")) {
            national = d.substring(5);
        } else if (d.startsWith("964") && (plus || d.length() >= 13)) {
            national = d.substring(3);
        } else if (plus) {
            return Optional.empty(); // "+" with a non-Iraqi country code
        } else {
            national = d;
        }
        // trunk zero (07...), including the "+964 07..." mistake
        if (national.startsWith("0")) national = national.substring(1);

        if (national.length() != 10 || national.charAt(0) != '7') return Optional.empty();
        char second = national.charAt(1);
        if (second < '3' || second > '9') return Optional.empty();
        for (int i = 2; i < 10; i++) {
            char c = national.charAt(i);
            if (c < '0' || c > '9') return Optional.empty();
        }
        return Optional.of(COUNTRY_CODE + national);
    }

    /** True when the input is a valid Iraqi mobile number in any accepted shape. */
    public static boolean isValid(String raw) {
        return normalize(raw).isPresent();
    }

    /**
     * Readable form of a stored number: "+964 790 123 4567" for E.164 input. Any other
     * stored value (legacy free text) comes back unchanged, so nothing on an existing
     * profile breaks.
     */
    public static String display(String stored) {
        if (stored == null) return "";
        String s = stored.trim();
        if (s.length() == 14 && s.startsWith(COUNTRY_CODE)) {
            return COUNTRY_CODE + " " + s.substring(4, 7) + " " + s.substring(7, 10) + " " + s.substring(10);
        }
        return s;
    }

    /** National part without the trunk zero ("790 123 4567"): what goes in an input
     *  that already shows the +964 prefix. Empty for anything that is not E.164. */
    public static String national(String stored) {
        if (stored == null) return "";
        String s = stored.trim();
        if (s.length() == 14 && s.startsWith(COUNTRY_CODE)) {
            return s.substring(4, 7) + " " + s.substring(7, 10) + " " + s.substring(10);
        }
        return "";
    }

    /** Domestic form with the trunk zero ("0790 123 4567"), the way Iraqis write it. */
    public static String domestic(String stored) {
        if (stored == null) return "";
        String s = stored.trim();
        if (s.length() == 14 && s.startsWith(COUNTRY_CODE)) {
            return "0" + s.substring(4, 7) + " " + s.substring(7, 10) + " " + s.substring(10);
        }
        return s;
    }

    /** Digits only ("9647901234567"), the shape WhatsApp and most SMS APIs want. */
    public static String digits(String stored) {
        if (stored == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : stored.toCharArray()) {
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }
}
