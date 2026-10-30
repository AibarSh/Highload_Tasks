package notification;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import java.time.Duration;
import java.util.Map;
import notification.v1.Channel;
import notification.v1.SubmitEventRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {
    private Server server;
    private NotificationClient client;
    private final IdempotencyStore store = new IdempotencyStore();

    private void start(FaultConfig faults, RetryPolicy policy) throws Exception {
        server = ServerBuilder.forPort(0).addService(new NotificationGrpcService(store, faults)).build().start();
        client = new NotificationClient("127.0.0.1", server.getPort(), policy);
    }

    @AfterEach
    void stop() {
        if (client != null) client.close();
        if (server != null) server.shutdownNow();
    }

    private static SubmitEventRequest event(String id, String templateId) {
        return SubmitEventRequest.newBuilder().setEventId(id).setRecipientId("user-1")
                .setChannel(Channel.CHANNEL_EMAIL).setTemplateId(templateId)
                .putAllData(Map.of("name", "Aidar")).build();
    }

    private static RetryPolicy fast(int attempts, int perAttemptMs) {
        return new RetryPolicy(attempts, Duration.ofMillis(5), Duration.ofMillis(20), 2.0,
                Duration.ofMillis(perAttemptMs), Duration.ofSeconds(5));
    }

    @Test
    void sameEventTwice_isEnqueuedOnce() throws Exception {
        start(FaultConfig.NONE, fast(3, 500));
        var first = client.submit(event("e-1", "welcome"));
        var second = client.submit(event("e-1", "welcome"));
        assertFalse(first.response().getDuplicate());
        assertTrue(second.response().getDuplicate());
        assertEquals(1, store.created());
        assertEquals(1, store.duplicates());
    }

    @Test
    void sameIdDifferentPayload_isRejected_andNotRetried() throws Exception {
        start(FaultConfig.NONE, fast(3, 500));
        client.submit(event("e-2", "welcome"));
        SubmitException ex = assertThrows(SubmitException.class, () -> client.submit(event("e-2", "other")));
        assertEquals(Status.Code.ALREADY_EXISTS, ex.status().getCode());
        assertEquals(1, ex.attempts());
    }

    @Test
    void invalidRequest_isRejected_andNotRetried() throws Exception {
        start(FaultConfig.NONE, fast(3, 500));
        SubmitException ex = assertThrows(SubmitException.class, () -> client.submit(event("", "welcome")));
        assertEquals(Status.Code.INVALID_ARGUMENT, ex.status().getCode());
        assertEquals(1, ex.attempts());
    }

    @Test
    void lostResponse_isRetried_withoutDoubleEnqueue() throws Exception {
        // every response is lost AFTER the commit -> the client retries until attempts run out
        start(new FaultConfig(0, 1.0, 0, 0), fast(4, 500));
        SubmitException ex = assertThrows(SubmitException.class, () -> client.submit(event("e-3", "welcome")));
        assertEquals(Status.Code.UNAVAILABLE, ex.status().getCode());
        assertEquals(4, ex.attempts());
        assertEquals(1, store.created());      // enqueued exactly once...
        assertEquals(3, store.duplicates());   // ...the other 3 attempts were recognised as retries
    }

    @Test
    void transientFailureBeforeCommit_isRetriedAndSucceeds() throws Exception {
        start(new FaultConfig(0.5, 0, 0, 0), fast(30, 500));
        for (int i = 0; i < 50; i++) client.submit(event("e-4-" + i, "welcome"));
        assertEquals(50, store.created());
        assertEquals(50, store.size());
    }

    @Test
    void slowServer_hitsDeadline_thenRetries_withoutDoubleEnqueue() throws Exception {
        start(new FaultConfig(0, 0, 1.0, 400), fast(2, 100)); // server needs 400 ms, client allows 100 ms
        SubmitException ex = assertThrows(SubmitException.class, () -> client.submit(event("e-5", "welcome")));
        assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.status().getCode());
        assertEquals(2, ex.attempts());
        assertEquals(1, store.created());
    }

    @Test
    void getNotification_returnsStatus_or_notFound() throws Exception {
        start(FaultConfig.NONE, fast(3, 500));
        client.submit(event("e-6", "welcome"));
        assertEquals("e-6", client.get("e-6").getEventId());
        io.grpc.StatusRuntimeException ex = assertThrows(io.grpc.StatusRuntimeException.class, () -> client.get("missing"));
        assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    }
}
