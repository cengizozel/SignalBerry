package com.example.signalberry;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure peer-key logic — deliberately free of any Android dependency so it can
 * be unit-tested on the JVM. {@link PeerKeys} is the stateful Android-bound
 * singleton (prefs, learned uuid→number map, listener); it delegates every
 * actual key decision here. Keeping this split is why the PNI/group/E.164
 * rules can be regression-tested without an emulator.
 */
final class PeerKey {

    private PeerKey() {}

    static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    static final Pattern UUID_SEARCH = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }

    static boolean isUuidKey(String key) {
        return key != null && UUID_RE.matcher(key).matches();
    }

    /** Normalize a single identifier: group id verbatim, uuid → lowercase,
     *  service-id (PNI:/ACI:) → the embedded uuid, number → digits (≤15). */
    static String normalize(String numberOrUuid) {
        if (isEmpty(numberOrUuid)) return "";
        String s = numberOrUuid.trim();
        if (s.startsWith("group:")) return s; // base64 group id: case-significant
        if (UUID_RE.matcher(s).matches()) return s.toLowerCase(Locale.US);
        // service-id forms like "PNI:<uuid>" (sent after a contact changes
        // phones / re-registers) must key as the uuid — extracting digits from
        // one forges a fake phone number and forks the conversation
        Matcher uuidIn = UUID_SEARCH.matcher(s);
        if (uuidIn.find()) return uuidIn.group().toLowerCase(Locale.US);
        String d = digits(s);
        // no real phone number exceeds 15 digits (E.164) — refuse the forgery
        return (!d.isEmpty() && d.length() <= 15) ? d : s.toLowerCase(Locale.US);
    }

    /**
     * Canonical key for an envelope/contact: group wins, then number, then a
     * uuid translated through the learned map when possible, else used as-is.
     */
    static String resolve(String number, String uuid, Map<String, String> uuidToNumber) {
        if (!isEmpty(number) && number.startsWith("group:")) return number;
        if (!isEmpty(uuid) && uuid.startsWith("group:")) return uuid;
        // a uuid smuggled into the number slot must not be digit-stripped
        if (!isEmpty(number) && UUID_SEARCH.matcher(number).find())
            return normalize(number);
        String n = digits(number);
        if (n.length() > 15) n = "";
        if (!n.isEmpty()) return n;
        String u = normalize(uuid);
        if (u.isEmpty()) return "";
        String mapped = uuidToNumber == null ? null : uuidToNumber.get(u);
        return mapped != null ? mapped : u;
    }
}
