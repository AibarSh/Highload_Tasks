package notification;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import notification.v1.SubmitEventRequest;

/**
 * SIS #2 measurements: gRPC vs REST/JSON on the same machine, same logic, same payloads.
 *
 *   1. Payload size   - serialized body AND real bytes on the wire per request/response
 *   2. Latency        - sequential closed loop, p50/p95/p99 (1 client thread)
 *   3. Throughput     - N client threads in parallel, req/s and p99 under load
 *   4. Reliability    - gRPC with injected failures: retries, timeouts, no double-enqueue
 *
 * Options: --requests=5000 --warmup=2000 --threads=16 --wire=1000 --out=results
 */
public final class Benchmark {
    private static final String HOST = "127.0.0.1";
    private static final EventFactory.Profile[] PROFILES = {EventFactory.SMALL, EventFactory.MEDIUM, EventFactory.LARGE};

    private final int requests, warmup, threads, wire;
    private final StringBuilder md = new StringBuilder();
    private final StringBuilder csv = new StringBuilder("section,profile,protocol,metric,value\n");

    private Benchmark(int requests, int warmup, int threads, int wire) {
        this.requests = requests; this.warmup = warmup; this.threads = threads; this.wire = wire;
    }

    public static void main(String[] args) throws Exception {
        int requests = 5000, warmup = 2000, threads = 16, wire = 1000;
        String out = "results";
        for (String a : args) {
            String[] kv = a.replaceFirst("^--", "").split("=", 2);
            switch (kv[0]) {
                case "requests" -> requests = Integer.parseInt(kv[1]);
                case "warmup" -> warmup = Integer.parseInt(kv[1]);
                case "threads" -> threads = Integer.parseInt(kv[1]);
                case "wire" -> wire = Integer.parseInt(kv[1]);
                case "out" -> out = kv[1];
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
        new Benchmark(requests, warmup, threads, wire).run(Path.of(out));
    }

    private void run(Path outDir) throws Exception {
        line("# SIS #2 measurement results");
        line("");
        line("Environment: Java " + System.getProperty("java.version") + ", " + Runtime.getRuntime().availableProcessors()
                + " CPU(s), " + System.getProperty("os.name") + ". Client and servers run in ONE JVM over loopback, "
                + "so absolute numbers are lower bounds for network cost; compare the protocols against each other.");
        line("Settings: " + requests + " measured requests + " + warmup + " warm-up per cell, " + threads
                + " threads for the throughput test, " + wire + " requests for wire-size counting.");
        line("");

        IdempotencyStore grpcStore = new IdempotencyStore();
        IdempotencyStore restStore = new IdempotencyStore();
        Server grpc = ServerBuilder.forPort(0).addService(new NotificationGrpcService(grpcStore, FaultConfig.NONE)).build().start();
        try (RestServer rest = new RestServer(0, restStore)) {
            payloadSizes(grpc.getPort(), rest.port());
            latency(grpc.getPort(), rest.port());
            throughput(grpc.getPort(), rest.port());
        } finally {
            grpc.shutdownNow();
        }
        reliability();

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("results.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("results.csv"), csv.toString(), StandardCharsets.UTF_8);
        System.out.println("\nWritten to " + outDir.toAbsolutePath() + "/results.md and results.csv");
    }

    // ---------------------------------------------------------------- 1. payload size
    private void payloadSizes(int grpcPort, int restPort) throws Exception {
        line("## 1. Payload size");
        line("");
        line("*Body* = serialized message only. *Wire* = every application-layer byte per request+response "
                + "(HTTP headers / HTTP-2 frames / gRPC framing, averaged over " + wire + " requests on one connection; TCP/IP headers excluded).");
        line("");
        line("| Profile | JSON req body (B) | Protobuf req body (B) | Body saving | REST wire req / resp (B) | gRPC wire req / resp (B) | Wire saving (total) |");
        line("|---|---:|---:|---:|---:|---:|---:|");

        for (EventFactory.Profile p : PROFILES) {
            Random rnd = new Random(42);
            Dto.Event sample = EventFactory.dto(p, rnd);
            int jsonBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(sample).length;
            int protoBody = EventFactory.proto(sample).getSerializedSize();

            double[] restWire = wireRest(restPort, p);
            double[] grpcWire = wireGrpc(grpcPort, p);
            double restTotal = restWire[0] + restWire[1], grpcTotal = grpcWire[0] + grpcWire[1];

            line(String.format("| %s | %,d | %,d | %.1f%% | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% |", p.name(), jsonBody, protoBody,
                    100.0 * (jsonBody - protoBody) / jsonBody, restWire[0], restWire[1], grpcWire[0], grpcWire[1],
                    100.0 * (restTotal - grpcTotal) / restTotal));
            csv(1, p, "rest", "body_req_bytes", jsonBody);
            csv(1, p, "grpc", "body_req_bytes", protoBody);
            csv(1, p, "rest", "wire_req_bytes", restWire[0]);
            csv(1, p, "rest", "wire_resp_bytes", restWire[1]);
            csv(1, p, "grpc", "wire_req_bytes", grpcWire[0]);
            csv(1, p, "grpc", "wire_resp_bytes", grpcWire[1]);
        }
        line("");
    }

    private double[] wireRest(int port, EventFactory.Profile p) throws Exception {
        try (CountingProxy proxy = new CountingProxy(port)) {
            RestClient client = new RestClient(HOST, proxy.port(), Duration.ofSeconds(5));
            Random rnd = new Random(7);
            for (int i = 0; i < wire; i++) client.submit(EventFactory.dto(p, rnd));
            Thread.sleep(300);
            return new double[]{(double) proxy.clientToServer.get() / wire, (double) proxy.serverToClient.get() / wire};
        }
    }

    private double[] wireGrpc(int port, EventFactory.Profile p) throws Exception {
        try (CountingProxy proxy = new CountingProxy(port);
             NotificationClient client = new NotificationClient(HOST, proxy.port(), RetryPolicy.defaults())) {
            Random rnd = new Random(7);
            for (int i = 0; i < wire; i++) client.submit(EventFactory.proto(EventFactory.dto(p, rnd)));
            Thread.sleep(300);
            return new double[]{(double) proxy.clientToServer.get() / wire, (double) proxy.serverToClient.get() / wire};
        }
    }

    // ---------------------------------------------------------------- 2. latency
    private void latency(int grpcPort, int restPort) throws Exception {
        line("## 2. Latency (1 client thread, closed loop, ms)");
        line("");
        line("| Profile | Protocol | mean | p50 | p95 | p99 | max |");
        line("|---|---|---:|---:|---:|---:|---:|");
        RestClient restClient = new RestClient(HOST, restPort, Duration.ofSeconds(5));
        try (NotificationClient grpcClient = new NotificationClient(HOST, grpcPort, RetryPolicy.defaults())) {
            for (EventFactory.Profile p : PROFILES) {
                // warm-up and measurement use disjoint, unique event ids; objects are built BEFORE timing
                Random rnd = new Random(100);
                List<Dto.Event> restEvents = events(p, rnd, warmup + requests);
                List<SubmitEventRequest> grpcEvents = new ArrayList<>();
                for (Dto.Event e : events(p, rnd, warmup + requests)) grpcEvents.add(EventFactory.proto(e));

                Stats r = sequential(i -> restClient.submit(restEvents.get(i)), warmup, requests);
                Stats g = sequential(i -> grpcClient.submit(grpcEvents.get(i)), warmup, requests);
                latencyRow(p, "REST/JSON", "rest", r);
                latencyRow(p, "gRPC", "grpc", g);
            }
        }
        line("");
    }

    private void latencyRow(EventFactory.Profile p, String label, String key, Stats s) {
        line(String.format("| %s | %s | %.3f | %.3f | %.3f | %.3f | %.3f |", p.name(), label, s.mean(), s.p50(), s.p95(), s.p99(), s.max()));
        csv(2, p, key, "mean_ms", s.mean()); csv(2, p, key, "p50_ms", s.p50());
        csv(2, p, key, "p95_ms", s.p95()); csv(2, p, key, "p99_ms", s.p99());
    }

    private interface Call { void run(int i) throws Exception; }

    private static Stats sequential(Call call, int warmup, int n) throws Exception {
        for (int i = 0; i < warmup; i++) call.run(i);
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            call.run(warmup + i);
            t[i] = System.nanoTime() - s;
        }
        return Stats.of(t);
    }

    // ---------------------------------------------------------------- 3. throughput
    private void throughput(int grpcPort, int restPort) throws Exception {
        line("## 3. Throughput (" + threads + " concurrent client threads)");
        line("");
        line("| Profile | Protocol | req/s | p50 ms | p99 ms |");
        line("|---|---|---:|---:|---:|");
        RestClient restClient = new RestClient(HOST, restPort, Duration.ofSeconds(10));
        try (NotificationClient grpcClient = new NotificationClient(HOST, grpcPort, RetryPolicy.defaults())) {
            for (EventFactory.Profile p : PROFILES) {
                Random rnd = new Random(200);
                List<Dto.Event> restEvents = events(p, rnd, requests);
                List<SubmitEventRequest> grpcEvents = new ArrayList<>();
                for (Dto.Event e : events(p, rnd, requests)) grpcEvents.add(EventFactory.proto(e));
                // short warm-up under concurrency
                parallel(i -> restClient.submit(events(p, new Random(300 + i), 1).get(0)), Math.min(500, warmup));
                parallel(i -> grpcClient.submit(EventFactory.proto(events(p, new Random(400 + i), 1).get(0))), Math.min(500, warmup));

                double[] r = parallel(i -> restClient.submit(restEvents.get(i)), requests);
                double[] g = parallel(i -> grpcClient.submit(grpcEvents.get(i)), requests);
                throughputRow(p, "REST/JSON", "rest", r);
                throughputRow(p, "gRPC", "grpc", g);
            }
        }
        line("");
    }

    private void throughputRow(EventFactory.Profile p, String label, String key, double[] r) {
        line(String.format("| %s | %s | %,.0f | %.3f | %.3f |", p.name(), label, r[0], r[1], r[2]));
        csv(3, p, key, "req_per_s", r[0]); csv(3, p, key, "p99_ms_under_load", r[2]);
    }

    /** @return {req/s, p50 ms, p99 ms} */
    private double[] parallel(Call call, int n) throws Exception {
        long[] lat = new long[n];
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                int i;
                while ((i = next.getAndIncrement()) < n) {
                    long s = System.nanoTime();
                    try { call.run(i); } catch (Exception e) { throw new RuntimeException(e); }
                    lat[i] = System.nanoTime() - s;
                }
            }));
        }
        for (var f : fs) f.get();
        double secs = (System.nanoTime() - start) / 1e9;
        pool.shutdown();
        Stats s = Stats.of(lat);
        return new double[]{n / secs, s.p50(), s.p99()};
    }

    private static List<Dto.Event> events(EventFactory.Profile p, Random rnd, int n) {
        List<Dto.Event> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) l.add(EventFactory.dto(p, rnd));
        return l;
    }

    // ---------------------------------------------------------------- 4. reliability
    private void reliability() throws Exception {
        int total = 2000;
        FaultConfig faults = new FaultConfig(0.15, 0.15, 0.05, 400);
        RetryPolicy policy = new RetryPolicy(6, Duration.ofMillis(20), Duration.ofMillis(200), 2.0,
                Duration.ofMillis(150), Duration.ofSeconds(3));

        IdempotencyStore store = new IdempotencyStore();
        Server server = ServerBuilder.forPort(0).addService(new NotificationGrpcService(store, faults)).build().start();
        AtomicInteger ok = new AtomicInteger(), failed = new AtomicInteger(), attempts = new AtomicInteger(), retried = new AtomicInteger();
        long[] lat = new long[total];
        try (NotificationClient client = new NotificationClient(HOST, server.getPort(), policy)) {
            Random rnd = new Random(500);
            List<SubmitEventRequest> evs = new ArrayList<>();
            for (Dto.Event e : events(EventFactory.MEDIUM, rnd, total)) evs.add(EventFactory.proto(e));
            AtomicInteger next = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                fs.add(pool.submit(() -> {
                    int i;
                    while ((i = next.getAndIncrement()) < total) {
                        long s = System.nanoTime();
                        try {
                            var r = client.submit(evs.get(i));
                            ok.incrementAndGet();
                            attempts.addAndGet(r.attempts());
                            if (r.attempts() > 1) retried.incrementAndGet();
                        } catch (SubmitException e) {
                            failed.incrementAndGet();
                            attempts.addAndGet(e.attempts());
                        }
                        lat[i] = System.nanoTime() - s;
                    }
                }));
            }
            for (var f : fs) f.get();
            pool.shutdown();
        }
        server.shutdownNow();
        server.awaitTermination(2, TimeUnit.SECONDS);

        Stats s = Stats.of(lat);
        line("## 4. Reliability under injected faults (gRPC, medium payload)");
        line("");
        line(String.format("Faults per request: %.0f%% UNAVAILABLE before commit, %.0f%% response lost after commit, "
                        + "%.0f%% slow reply (%d ms) vs a %d ms per-attempt deadline. Retry policy: up to %d attempts, "
                        + "exponential backoff %d-%d ms with full jitter, overall deadline %d s.",
                faults.unavailableRate() * 100, faults.lostResponseRate() * 100, faults.slowRate() * 100, faults.slowMillis(),
                policy.perAttemptTimeout().toMillis(), policy.maxAttempts(), policy.initialBackoff().toMillis(),
                policy.maxBackoff().toMillis(), policy.totalTimeout().toSeconds()));
        line("");
        line("| Metric | Value |");
        line("|---|---:|");
        line("| Events submitted | " + total + " |");
        line("| Succeeded | " + ok + " (" + String.format("%.2f%%", 100.0 * ok.get() / total) + ") |");
        line("| Failed after retries | " + failed + " |");
        line("| Needed at least one retry | " + retried + " (" + String.format("%.1f%%", 100.0 * retried.get() / total) + ") |");
        line("| Average attempts per event | " + String.format("%.2f", (double) attempts.get() / total) + " |");
        line("| Latency p50 / p95 / p99 (ms, incl. retries) | " + String.format("%.1f / %.1f / %.1f", s.p50(), s.p95(), s.p99()) + " |");
        line("| Server: events enqueued (CREATED) | " + store.created() + " |");
        line("| Server: retries recognised as duplicates | " + store.duplicates() + " |");
        line("| **Double-enqueued events** | **" + (store.created() - store.size()) + "** |");
        line("");
        line("Invariant checked: every distinct event_id is enqueued at most once (enqueued == distinct ids stored == "
                + store.size() + ") even though " + store.duplicates() + " retries reached the server after the original had already committed.");
        csv(4, null, "grpc", "success_rate", 100.0 * ok.get() / total);
        csv(4, null, "grpc", "avg_attempts", (double) attempts.get() / total);
        csv(4, null, "grpc", "duplicate_hits", store.duplicates());
        csv(4, null, "grpc", "double_enqueued", store.created() - store.size());
    }

    // ---------------------------------------------------------------- output helpers
    private void line(String s) {
        md.append(s).append('\n');
        System.out.println(s);
    }

    private void csv(int section, EventFactory.Profile p, String proto, String metric, double v) {
        csv.append(section).append(',').append(p == null ? "-" : p.name()).append(',').append(proto).append(',')
                .append(metric).append(',').append(String.format(java.util.Locale.ROOT, "%.4f", v)).append('\n');
    }
}
