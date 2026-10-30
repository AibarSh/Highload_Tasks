package notification;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory stand-in for the "unique event_id" rule from SIS #1
 * (in production: Kafka ACK + UNIQUE constraint in PostgreSQL).
 * register() is atomic, so concurrent retries of one event cannot both be CREATED.
 */
public final class IdempotencyStore {
    public enum Outcome { CREATED, DUPLICATE, CONFLICT }

    public record Entry(String fingerprint, String status, int attempts, Instant updatedAt) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder created = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder conflicts = new LongAdder();

    public Outcome register(String eventId, String fingerprint) {
        Entry prev = entries.putIfAbsent(eventId, new Entry(fingerprint, "queued", 0, Instant.now()));
        if (prev == null) {
            created.increment();
            return Outcome.CREATED;
        }
        if (prev.fingerprint().equals(fingerprint)) {
            duplicates.increment();
            return Outcome.DUPLICATE;
        }
        conflicts.increment();
        return Outcome.CONFLICT;
    }

    public Optional<Entry> get(String eventId) { return Optional.ofNullable(entries.get(eventId)); }
    public long created() { return created.sum(); }
    public long duplicates() { return duplicates.sum(); }
    public long conflicts() { return conflicts.sum(); }
    public int size() { return entries.size(); }
}
