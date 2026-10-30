package notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical hash of an event's content. Used to tell a legitimate retry
 * (same event_id, same content) from a client bug (same event_id, different content).
 * Shared by the gRPC and REST servers so both behave identically.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String of(String recipientId, String channel, String templateId, Map<String, String> data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            put(md, recipientId);
            put(md, channel);
            put(md, templateId);
            for (Map.Entry<String, String> e : new TreeMap<>(data).entrySet()) { // sorted: map order must not matter
                put(md, e.getKey());
                put(md, e.getValue());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(MessageDigest md, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        md.update((byte) (b.length >>> 24));
        md.update((byte) (b.length >>> 16));
        md.update((byte) (b.length >>> 8));
        md.update((byte) b.length); // length prefix avoids ambiguity ("ab","c" vs "a","bc")
        md.update(b);
    }
}
