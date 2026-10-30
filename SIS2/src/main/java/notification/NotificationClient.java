package notification;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import notification.v1.GetNotificationRequest;
import notification.v1.GetNotificationResponse;
import notification.v1.NotificationServiceGrpc;
import notification.v1.SubmitEventRequest;
import notification.v1.SubmitEventResponse;

/**
 * gRPC client with:
 *  - a deadline per attempt AND an overall deadline,
 *  - retry on transient errors with exponential backoff + full jitter,
 *  - idempotent retries: the SAME request (same event_id) is re-sent, so the server enqueues it once.
 */
public final class NotificationClient implements AutoCloseable {
    public record Result(SubmitEventResponse response, int attempts) {}

    private final ManagedChannel channel;
    private final NotificationServiceGrpc.NotificationServiceBlockingStub stub;
    private final RetryPolicy policy;

    public NotificationClient(String host, int port, RetryPolicy policy) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = NotificationServiceGrpc.newBlockingStub(channel);
        this.policy = policy;
    }

    public Result submit(SubmitEventRequest request) {
        long deadlineNanos = System.nanoTime() + policy.totalTimeout().toNanos();
        Status last = Status.DEADLINE_EXCEEDED.withDescription("overall deadline exhausted");
        int attempt = 0;

        while (attempt < policy.maxAttempts()) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) break;
            attempt++;
            long timeoutMs = Math.min(remainingMs, policy.perAttemptTimeout().toMillis());
            try {
                SubmitEventResponse r = stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).submitEvent(request);
                return new Result(r, attempt);
            } catch (StatusRuntimeException e) {
                last = e.getStatus();
                if (!policy.isRetryable(last.getCode())) throw new SubmitException(last, attempt);
            }
            long sleepMs = Math.min(policy.backoffMillis(attempt),
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            if (attempt < policy.maxAttempts() && sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SubmitException(Status.CANCELLED.withDescription("interrupted"), attempt);
                }
            }
        }
        throw new SubmitException(last, attempt);
    }

    public GetNotificationResponse get(String eventId) {
        return stub.withDeadlineAfter(policy.perAttemptTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .getNotification(GetNotificationRequest.newBuilder().setEventId(eventId).build());
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow();
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
