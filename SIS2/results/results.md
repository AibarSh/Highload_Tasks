# SIS #2 measurement results

Environment: Java 27.0.1.0.3, 8 CPU(s), Mac OS X. Client and servers run in ONE JVM over loopback, so absolute numbers are lower bounds for network cost; compare the protocols against each other.
Settings: 10000 measured requests + 2000 warm-up per cell, 32 threads for the throughput test, 2000 requests for wire-size counting.

## 1. Payload size

*Body* = serialized message only. *Wire* = every application-layer byte per request+response (HTTP headers / HTTP-2 frames / gRPC framing, averaged over 2000 requests on one connection; TCP/IP headers excluded).

| Profile | JSON req body (B) | Protobuf req body (B) | Body saving | REST wire req / resp (B) | gRPC wire req / resp (B) | Wire saving (total) |
|---|---:|---:|---:|---:|---:|---:|
| small | 213 | 145 | 31.9% | 371 / 183 | 184 / 77 | 53.0% |
| medium | 1,262 | 1,194 | 5.4% | 1,422 / 183 | 1,232 / 77 | 18.4% |
| large | 11,176 | 11,227 | -0.5% | 11,335 / 183 | 11,264 / 77 | 1.5% |

## 2. Latency (1 client thread, closed loop, ms)

| Profile | Protocol | mean | p50 | p95 | p99 | max |
|---|---|---:|---:|---:|---:|---:|
| small | REST/JSON | 0.164 | 0.148 | 0.231 | 0.301 | 15.001 |
| small | gRPC | 0.116 | 0.107 | 0.161 | 0.265 | 6.290 |
| medium | REST/JSON | 0.110 | 0.105 | 0.132 | 0.162 | 11.130 |
| medium | gRPC | 0.098 | 0.097 | 0.112 | 0.130 | 6.195 |
| large | REST/JSON | 0.153 | 0.151 | 0.169 | 0.185 | 14.321 |
| large | gRPC | 0.140 | 0.138 | 0.158 | 0.180 | 4.329 |

## 3. Throughput (32 concurrent client threads)

| Profile | Protocol | req/s | p50 ms | p99 ms |
|---|---|---:|---:|---:|
| small | REST/JSON | 25,426 | 1.102 | 2.664 |
| small | gRPC | 25,696 | 1.181 | 2.697 |
| medium | REST/JSON | 24,064 | 1.050 | 2.726 |
| medium | gRPC | 26,659 | 1.072 | 2.360 |
| large | REST/JSON | 21,974 | 1.211 | 2.883 |
| large | gRPC | 23,682 | 1.265 | 2.103 |

## 4. Reliability under injected faults (gRPC, medium payload)

Faults per request: 15% UNAVAILABLE before commit, 15% response lost after commit, 5% slow reply (400 ms) vs a 150 ms per-attempt deadline. Retry policy: up to 6 attempts, exponential backoff 20-200 ms with full jitter, overall deadline 3 s.

| Metric | Value |
|---|---:|
| Events submitted | 2000 |
| Succeeded | 2000 (100.00%) |
| Failed after retries | 0 |
| Needed at least one retry | 626 (31.3%) |
| Average attempts per event | 1.46 |
| Latency p50 / p95 / p99 (ms, incl. retries) | 0.3 / 153.3 / 207.6 |
| Server: events enqueued (CREATED) | 2000 |
| Server: retries recognised as duplicates | 463 |
| **Double-enqueued events** | **0** |

Invariant checked: every distinct event_id is enqueued at most once (enqueued == distinct ids stored == 2000) even though 463 retries reached the server after the original had already committed.
