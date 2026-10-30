package notification;

import io.grpc.Status;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client retry policy: per-attempt timeout, overall deadline, exponential backoff with full jitter.
 * Retrying is safe ONLY because SubmitEvent is idempotent on event_id.
 */
public record RetryPolicy(int maxAttempts,
                          Duration initialBackoff,
                          Duration maxBackoff,
                          double multiplier,
                          Duration perAttemptTimeout,
                          Duration totalTimeout) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(5, Duration.ofMillis(50), Duration.ofSeconds(1), 2.0,
                Duration.ofMillis(500), Duration.ofSeconds(3));
    }

    /** Transient codes only. INVALID_ARGUMENT, ALREADY_EXISTS, NOT_FOUND, UNAUTHENTICATED are final. */
    public boolean isRetryable(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED -> true;
            default -> false;
        };
    }

    /** Full jitter: uniform in [0, min(maxBackoff, initial * multiplier^(attempt-1))]. */
    public long backoffMillis(int attempt) {
        double cap = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() * Math.pow(multiplier, attempt - 1));
        return ThreadLocalRandom.current().nextLong((long) cap + 1);
    }
}
