package notification;

import io.grpc.Status;

/** Thrown when SubmitEvent finally fails (non-retryable error or retries exhausted). */
public final class SubmitException extends RuntimeException {
    private final Status status;
    private final int attempts;

    public SubmitException(Status status, int attempts) {
        super(status.getCode() + " after " + attempts + " attempt(s): " + status.getDescription(), status.getCause());
        this.status = status;
        this.attempts = attempts;
    }

    public Status status() { return status; }
    public int attempts() { return attempts; }
}
