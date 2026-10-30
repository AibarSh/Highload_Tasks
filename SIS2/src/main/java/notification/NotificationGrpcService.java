package notification;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ThreadLocalRandom;
import notification.v1.Channel;
import notification.v1.DeliveryStatus;
import notification.v1.GetNotificationRequest;
import notification.v1.GetNotificationResponse;
import notification.v1.NotificationServiceGrpc;
import notification.v1.SubmitEventRequest;
import notification.v1.SubmitEventResponse;

/** gRPC implementation of the SIS #1 interface. */
public final class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
    private final IdempotencyStore store;
    private final FaultConfig faults;

    public NotificationGrpcService(IdempotencyStore store, FaultConfig faults) {
        this.store = store;
        this.faults = faults;
    }

    @Override
    public void submitEvent(SubmitEventRequest req, StreamObserver<SubmitEventResponse> out) {
        String problem = validate(req);
        if (problem != null) { // REST 400
            out.onError(Status.INVALID_ARGUMENT.withDescription(problem).asRuntimeException());
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextDouble() < faults.unavailableRate()) { // nothing committed yet
            out.onError(Status.UNAVAILABLE.withDescription("injected: queue not reachable").asRuntimeException());
            return;
        }

        String fp = Fingerprint.of(req.getRecipientId(), req.getChannel().name(), req.getTemplateId(), req.getDataMap());
        IdempotencyStore.Outcome outcome = store.register(req.getEventId(), fp);
        if (outcome == IdempotencyStore.Outcome.CONFLICT) { // REST 409
            out.onError(Status.ALREADY_EXISTS
                    .withDescription("event_id " + req.getEventId() + " already used with a different payload")
                    .asRuntimeException());
            return;
        }

        if (rnd.nextDouble() < faults.lostResponseRate()) { // committed, but the client will not know
            out.onError(Status.UNAVAILABLE.withDescription("injected: response lost after commit").asRuntimeException());
            return;
        }
        if (rnd.nextDouble() < faults.slowRate()) {
            try {
                Thread.sleep(faults.slowMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ServerCallStreamObserver<SubmitEventResponse> call = (ServerCallStreamObserver<SubmitEventResponse>) out;
        if (call.isCancelled()) return; // client deadline already passed; it will retry with the same event_id

        out.onNext(SubmitEventResponse.newBuilder()
                .setEventId(req.getEventId())
                .setStatus(DeliveryStatus.DELIVERY_STATUS_QUEUED)
                .setDuplicate(outcome == IdempotencyStore.Outcome.DUPLICATE)
                .build());
        out.onCompleted();
    }

    @Override
    public void getNotification(GetNotificationRequest req, StreamObserver<GetNotificationResponse> out) {
        if (req.getEventId().isEmpty()) {
            out.onError(Status.INVALID_ARGUMENT.withDescription("event_id is required").asRuntimeException());
            return;
        }
        store.get(req.getEventId()).ifPresentOrElse(e -> {
            out.onNext(GetNotificationResponse.newBuilder()
                    .setEventId(req.getEventId())
                    .setStatus(DeliveryStatus.DELIVERY_STATUS_QUEUED)
                    .setAttempts(e.attempts())
                    .setUpdatedAt(Timestamp.newBuilder()
                            .setSeconds(e.updatedAt().getEpochSecond()).setNanos(e.updatedAt().getNano()))
                    .build());
            out.onCompleted();
        }, () -> out.onError(Status.NOT_FOUND.withDescription("unknown event_id").asRuntimeException())); // REST 404
    }

    private static String validate(SubmitEventRequest r) {
        if (r.getEventId().isBlank()) return "event_id is required";
        if (r.getRecipientId().isBlank()) return "recipient_id is required";
        if (r.getTemplateId().isBlank()) return "template_id is required";
        if (r.getChannel() == Channel.CHANNEL_UNSPECIFIED || r.getChannel() == Channel.UNRECOGNIZED) {
            return "channel must be EMAIL or PUSH";
        }
        return null;
    }
}
