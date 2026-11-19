# SIS #2 measurement results

Environment: Java 27.0.1.0.3, 8 CPU(s), Mac OS X. Client and servers run in ONE JVM over loopback, so absolute numbers are lower bounds for network cost; compare the protocols against each other.
Settings: 5000 measured requests + 2000 warm-up per cell, 16 threads for the throughput test, 1000 requests for wire-size counting.

## 1. Payload size

*Body* = serialized message only. *Wire* = every application-layer byte per request+response (HTTP headers / HTTP-2 frames / gRPC framing, averaged over 1000 requests on one connection; TCP/IP headers excluded).

| Profile | JSON req body (B) | Protobuf req body (B) | Body saving | REST wire req / resp (B) | gRPC wire req / resp (B) | Wire saving (total) |
|---|---:|---:|---:|---:|---:|---:|
| small | 213 | 145 | 31.9% | 371 / 183 | 184 / 77 | 52.9% |
| medium | 1,262 | 1,194 | 5.4% | 1,422 / 183 | 1,233 / 77 | 18.4% |
| large | 11,176 | 11,227 | -0.5% | 11,335 / 183 | 11,264 / 78 | 1.5% |

## 2. Latency (1 client thread, closed loop, ms)

| Profile | Protocol | mean | p50 | p95 | p99 | max |
|---|---|---:|---:|---:|---:|---:|
| small | REST/JSON | 0.173 | 0.160 | 0.245 | 0.290 | 4.019 |
| small | gRPC | 0.130 | 0.123 | 0.171 | 0.273 | 3.421 |
| medium | REST/JSON | 0.156 | 0.137 | 0.228 | 0.270 | 11.328 |
| medium | gRPC | 0.123 | 0.116 | 0.162 | 0.191 | 7.295 |
| large | REST/JSON | 0.180 | 0.176 | 0.209 | 0.238 | 6.422 |
| large | gRPC | 0.142 | 0.139 | 0.165 | 0.198 | 5.906 |

## 3. Throughput (16 concurrent client threads)

| Profile | Protocol | req/s | p50 ms | p99 ms |
|---|---|---:|---:|---:|
| small | REST/JSON | 24,974 | 0.631 | 1.069 |
| small | gRPC | 20,046 | 0.755 | 1.960 |
| medium | REST/JSON | 28,058 | 0.538 | 0.997 |
| medium | gRPC | 33,920 | 0.440 | 0.889 |
| large | REST/JSON | 19,762 | 0.697 | 1.677 |
| large | gRPC | 20,506 | 0.713 | 1.441 |

## 4. Reliability under injected faults (gRPC, medium payload)

Faults per request: 15% UNAVAILABLE before commit, 15% response lost after commit, 5% slow reply (400 ms) vs a 150 ms per-attempt deadline. Retry policy: up to 6 attempts, exponential backoff 20-200 ms with full jitter, overall deadline 3 s.

| Metric | Value |
|---|---:|
| Events submitted | 2000 |
| Succeeded | 1998 (99.90%) |
| Failed after retries | 2 |
| Needed at least one retry | 616 (30.8%) |
| Average attempts per event | 1.44 |
| Latency p50 / p95 / p99 (ms, incl. retries) | 0.3 / 161.0 / 210.9 |
| Server: events enqueued (CREATED) | 2000 |
| Server: retries recognised as duplicates | 458 |
| **Double-enqueued events** | **0** |

Invariant checked: every distinct event_id is enqueued at most once (enqueued == distinct ids stored == 2000) even though 458 retries reached the server after the original had already committed.
