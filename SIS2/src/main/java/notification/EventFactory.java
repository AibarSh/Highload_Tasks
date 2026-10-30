package notification;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import notification.v1.Channel;
import notification.v1.SubmitEventRequest;

/** Builds identical events for both protocols (same fields, same values). */
public final class EventFactory {
    private EventFactory() {}

    /** @param fields number of entries in data{}, @param valueLen characters per value */
    public record Profile(String name, int fields, int valueLen) {}

    public static final Profile SMALL = new Profile("small", 3, 12);     // ~0.15 KB: "order shipped"
    public static final Profile MEDIUM = new Profile("medium", 12, 80);  // ~1 KB: the SIS #1 estimate (1 KB event)
    public static final Profile LARGE = new Profile("large", 60, 170);   // ~10 KB: rich template data

    /** event_id must be unique across the whole run, whatever the random seed (reusing one with new data = 409/ALREADY_EXISTS). */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ";

    /** Unique event_id each call; data values are random but reproducible per seed. */
    public static Dto.Event dto(Profile p, Random rnd) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < p.fields(); i++) {
            StringBuilder sb = new StringBuilder(p.valueLen());
            for (int c = 0; c < p.valueLen(); c++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
            data.put(String.format("field_%02d", i), sb.toString());
        }
        String id = UUID.nameUUIDFromBytes(("evt-" + SEQ.incrementAndGet()).getBytes(StandardCharsets.UTF_8)).toString();
        return new Dto.Event(id, "user-" + rnd.nextInt(1_000_000), rnd.nextBoolean() ? "EMAIL" : "PUSH", "tmpl-welcome", data);
    }

    public static SubmitEventRequest proto(Dto.Event e) {
        return SubmitEventRequest.newBuilder()
                .setEventId(e.eventId())
                .setRecipientId(e.recipientId())
                .setChannel("EMAIL".equals(e.channel()) ? Channel.CHANNEL_EMAIL : Channel.CHANNEL_PUSH)
                .setTemplateId(e.templateId())
                .putAllData(e.data())
                .build();
    }
}
