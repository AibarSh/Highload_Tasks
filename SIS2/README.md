# Notification Service – SIS #2 gRPC used

Java implementation of the Notification Service interface from SIS #1, using **gRPC (HTTP/2)**,
with idempotency, timeouts and retries, plus measurements against the **REST/JSON baseline**.

## Run

Requirements: JDK 17+, Maven 3.8+.

```bash
mvn test                                   # 7 tests: idempotency, deadlines, retries, error codes
mvn compile exec:java                      # full benchmark -> results/results.md + results.csv
mvn compile exec:java -Dexec.args="--requests=10000 --threads=32 --wire=2000"
mvn compile exec:java -Dexec.mainClass=notification.ServerMain   # gRPC :50051 + REST :8080 for manual tests
```

Benchmark options: `--requests` (measured per cell), `--warmup`, `--threads`, `--wire`, `--out`.

## Layout

| File | Purpose |
|---|---|
| `src/main/proto/notification.proto` | The service contract (REST endpoints from SIS #1 -> RPCs) |
| `NotificationGrpcService` | Server: validation, idempotent `SubmitEvent`, `GetNotification`, fault injection |
| `NotificationClient` | Client: per-attempt + overall deadline, retry with backoff + full jitter |
| `RetryPolicy`, `SubmitException` | Retry settings, retryable status codes, final error with attempt count |
| `IdempotencyStore`, `Fingerprint` | Atomic "first writer wins" on `event_id`, payload hash to detect misuse |
| `RestServer`, `RestClient`, `Dto` | REST/JSON baseline (same logic, same store type) |
| `CountingProxy` | TCP proxy that counts real bytes on the wire |
| `Benchmark`, `EventFactory`, `Stats` | Measurements |
| `NotificationServiceTest` | JUnit 5 tests |

## Contract mapping (SIS #1 -> gRPC)

| SIS #1 REST | gRPC | Notes |
|---|---|---|
| `POST /v1/notification-events` -> 202 | `SubmitEvent` -> OK | OK is returned only after the (simulated) durable enqueue |
| `GET /v1/notifications/{id}` -> 200/404 | `GetNotification` -> OK / `NOT_FOUND` | |
| 400 | `INVALID_ARGUMENT` | not retried |
| 409 | `ALREADY_EXISTS` | same `event_id`, different payload; not retried |
| 429 | `RESOURCE_EXHAUSTED` | retried with backoff |
| 503 | `UNAVAILABLE` | retried |
| client timeout | `DEADLINE_EXCEEDED` | retried (safe because idempotent) |

## Design decisions

**Idempotency.** `event_id` is the idempotency key, chosen by the caller and reused on every retry.
The server does an atomic `putIfAbsent` and compares a SHA-256 fingerprint of the content:
new id -> enqueue; same id + same content -> return the original result with `duplicate=true`
(no second enqueue); same id + different content -> `ALREADY_EXISTS`. In production this is the
Kafka ACK + `UNIQUE(event_id)` from SIS #1; here it is an in-memory map.

**Timeouts.** Every attempt has its own deadline (default 500 ms) and the whole call has an overall
deadline (default 3 s), so a retry loop can never exceed the budget.

**Retries.** Only transient codes (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `ABORTED`),
max 5 attempts, exponential backoff 50 ms x2 up to 1 s with **full jitter** (avoids synchronized retry
storms). Retries are implemented in the application rather than via gRPC's built-in service-config retry
so that the idempotency key, attempt counting and the overall budget are explicit and testable.

**Delivery semantics.** A client-side failure after retries does *not* mean "not enqueued": the response
may have been lost after commit. The caller should re-submit later with the same `event_id`. This matches the
at-least-once guarantee in SIS #1.

## Measurement notes (read before quoting numbers)

* Client and servers run in one JVM over loopback. Compare protocols with each other; absolute latencies
  are lower bounds for a real network.
* Object construction is outside the timed region; (de)serialization is inside it for both protocols.
* REST baseline = JDK `HttpServer` + `HttpClient`, HTTP/1.1 keep-alive. `sun.net.httpserver.nodelay=true`
  is set, otherwise Nagle + delayed ACK adds ~40 ms per request (observed: p50 44 ms) - a JDK artefact
  that would make REST look unfairly bad.
* No TLS, no auth in either path (the JWT/API key from SIS #1 is out of scope here).
* Run on an otherwise idle machine, several times, and quote medians/ranges.
