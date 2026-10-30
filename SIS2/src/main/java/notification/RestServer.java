package notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** REST/JSON baseline from SIS #1 (HTTP/1.1 + JSON), same logic and same idempotency store as the gRPC service. */
public final class RestServer implements AutoCloseable {
    static {
        // JDK HttpServer sends headers and body as separate writes; with Nagle + delayed ACK that adds ~40 ms
        // per request on loopback. That is a JDK artefact, not a cost of REST, so switch it off for a fair baseline.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private final HttpServer http;
    private final ExecutorService pool = Executors.newFixedThreadPool(32);
    private final ObjectMapper mapper = new ObjectMapper();
    private final IdempotencyStore store;

    public RestServer(int port, IdempotencyStore store) throws IOException {
        this.store = store;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/v1/notification-events", this::submit);
        http.createContext("/v1/notifications/", this::get);
        http.setExecutor(pool);
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }

    private void submit(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) { reply(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
            Dto.Event ev;
            try {
                ev = mapper.readValue(ex.getRequestBody().readAllBytes(), Dto.Event.class);
            } catch (IOException e) {
                reply(ex, 400, "{\"error\":\"malformed JSON\"}");
                return;
            }
            if (ev.eventId() == null || ev.eventId().isBlank() || ev.recipientId() == null || ev.recipientId().isBlank()
                    || ev.templateId() == null || ev.templateId().isBlank() || ev.data() == null
                    || !("EMAIL".equals(ev.channel()) || "PUSH".equals(ev.channel()))) {
                reply(ex, 400, "{\"error\":\"invalid event\"}");
                return;
            }
            String fp = Fingerprint.of(ev.recipientId(), "CHANNEL_" + ev.channel(), ev.templateId(), ev.data());
            if (store.register(ev.eventId(), fp) == IdempotencyStore.Outcome.CONFLICT) {
                reply(ex, 409, "{\"error\":\"event_id already used with a different payload\"}");
                return;
            }
            reply(ex, 202, mapper.writeValueAsString(new Dto.Ack(ev.eventId(), "queued")));
        }
    }

    private void get(HttpExchange ex) throws IOException {
        try (ex) {
            String id = ex.getRequestURI().getPath().substring("/v1/notifications/".length());
            var entry = store.get(id);
            if (entry.isEmpty()) { reply(ex, 404, "{\"error\":\"not found\"}"); return; }
            reply(ex, 200, "{\"status\":\"queued\",\"attempts\":" + entry.get().attempts()
                    + ",\"updated_at\":\"" + entry.get().updatedAt() + "\"}");
        }
    }

    private static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    @Override
    public void close() {
        http.stop(0);
        pool.shutdownNow();
    }
}
