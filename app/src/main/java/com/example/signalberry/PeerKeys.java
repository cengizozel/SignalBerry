package com.example.signalberry;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.signalberry.Utils.*;

/**
 * The single source of truth for peer keys, mirroring the bridge's rules:
 * digits of the phone number when a number is known, else the lowercase UUID.
 *
 * Maintains a persisted uuid→number map (learned from envelopes and
 * /v1/contacts). When a new mapping is learned, {@link Listener#onMappingLearned}
 * fires so Repo can merge the uuid-keyed rows into the number key — the
 * re-key is ongoing, not one-time: a uuid-only envelope can always arrive
 * before contacts are known.
 */
final class PeerKeys {

    interface Listener {
        void onMappingLearned(String uuidKey, String numberKey);
    }

    private static final String PREFS = "peer_map";

    private static PeerKeys instance;

    private final SharedPreferences prefs;
    private final Map<String, String> uuidToNumber = new HashMap<>();
    private Listener listener;

    static synchronized PeerKeys get(Context ctx) {
        if (instance == null) instance = new PeerKeys(ctx.getApplicationContext());
        return instance;
    }

    private PeerKeys(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) uuidToNumber.put(e.getKey(), (String) v);
        }
    }

    synchronized void setListener(Listener l) { listener = l; }

    synchronized Map<String, String> allMappings() { return new HashMap<>(uuidToNumber); }

    static boolean isUuidKey(String key) { return PeerKey.isUuidKey(key); }

    /** Normalize a single identifier — see {@link PeerKey#normalize}. */
    static String normalize(String numberOrUuid) { return PeerKey.normalize(numberOrUuid); }


    /**
     * Canonical key for an envelope/contact: number wins; a uuid is translated
     * through the learned map when possible, else used as-is (lowercase).
     */
    synchronized String resolve(String number, String uuid) {
        return PeerKey.resolve(number, uuid, uuidToNumber);
    }

    /** Record uuid→number; fires the re-key listener on first sighting. */
    synchronized void learn(String uuid, String number) {
        String u = normalize(uuid);
        String n = digits(number);
        if (u.isEmpty() || n.isEmpty() || n.length() > 15 || !isUuidKey(u)) return;
        String prev = uuidToNumber.get(u);
        if (n.equals(prev)) return;
        uuidToNumber.put(u, n);
        prefs.edit().putString(u, n).apply();
        if (listener != null) listener.onMappingLearned(u, n);
    }

    synchronized String numberFor(String uuidKey) {
        String v = uuidToNumber.get(normalize(uuidKey));
        return v != null ? v : "";
    }
}
