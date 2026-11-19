# SIS #3 – Database tuning: Notification Service on PostgreSQL

**Task:** design the schema and indexes for the workload, analyse query plans with `EXPLAIN ANALYZE`, apply partitioning, read replicas and
connection pooling, and submit a before/after benchmark with p95/p99 and an explanation of every gain.

Everything below was **measured** on PostgreSQL 16.15 + PgBouncer 1.22 (see section 10 for the limits of the test bed).
Raw output: `results/summary.md`, `results/summary.csv`, `results/plans/`, `results/charts/`. Reproduce with `./scripts/run_all.sh` (see `README.md`).

---

## 1. Summary

| Transaction (SIS #1 flow) | before p95 → after p95 | before p99 → after p99 | gain (p95 / p99) |
|---|---|---|---|
| Dispatcher ingest: dedupe + event + delivery + outbox, one txn | 1,958 → 24.9 ms | 2,141 → 33.8 ms | 79× / 63× |
| Status API read `GET /v1/notifications/{id}` | 991 → 9.8 ms | 1,088 → 18.4 ms | 101× / 59× |
| Outbox relay claim | 2,672 → 17.8 ms | 2,799\* → 25.8 ms | 150× / 108× |
| Retry scheduler poll | 2,931 → 42.1 ms | 3,362\* → 56.1 ms | 70× / 60× |
| Provider-ACK update | 54.4 → 15.7 ms | 75.9 → 23.6 ms | 3× / 3× |
| Monitoring count (1-hour window) | 1,252 → 28.7 ms | 1,368\* → 40.1 ms | 44× / 34× |

*Before* = untuned schema (primary keys only). *After* = indexes + daily partitioning + read replica + PgBouncer.
Total throughput of the same mixed workload: **11.9 → 1,584 tx/s**. \* = fewer than 100 samples (the untuned system completes these rare transactions only ~50 times in 3 minutes).

**What actually produced the gain, in one paragraph.** Almost all of it (≈ 99 %) comes from **three indexes**: without them every dedupe check, status
lookup, outbox claim and retry poll scans millions of rows (26,000+ pages) instead of touching 4–5 pages. **Partitioning did not make
queries faster**. It made ordinary lookups slower (status read p50 0.34 → 2.06 ms) because the planner must consider 9 partitions, but it makes
retention 16× faster with 436× less WAL, and the lookup penalty almost disappears with prepared statements and a partition-aware key (section 6).
The **read replica** moved ~half of the CPU work off the primary (94 % → 42 % of a core) at the cost of 9–64 ms of replication delay; it could not
make anything *faster* on a single shared core. **PgBouncer** cut PostgreSQL backends from 209 to 21 and lowered p99 under 200 connections by 26 %,
but did nothing at 12 connections and made light reads queue. Sections 4–8 give the evidence, section 9 the full explanation table.

**Against the SIS #1 latency budget.** SIS #1 allots **0.45 s** to "DB + outbox commit" (inside a 4.25 s p99 budget). The untuned ingest transaction has p99 = 2.14 s, so it
*alone* exceeds the whole DB budget 4.8×; after tuning its p99 is 34 ms, 7 % of the budget.

---

## 2. Workload (from the SIS #1 design)

SIS #1: peak 3,000 events/s, at-least-once delivery, `UNIQUE(event_id, channel)`, `INDEX(status, next_attempt_at)`, deliveries partitioned by date with
7-day retention, state in PostgreSQL, outbox relay to Kafka. We turned each flow into one transaction script (`bench/*.sql`):

| Flow | Transaction | Mix weight | Tables |
|---|---|---:|---|
| Dispatcher (SIS #1 step 2) | read preference; insert event; insert delivery + outbox **in one txn**; 10 % are Kafka replays (duplicates) | 60 % of writers | `preferences`, `notification_events`, `deliveries`, `outbox` |
| Worker: save provider ACK | `UPDATE deliveries SET status='sent' … WHERE delivery_id = ?` | 30 % of writers | `deliveries` |
| Outbox relay | claim 50 unpublished rows, `FOR UPDATE SKIP LOCKED` | 6 % of writers | `outbox` |
| Retry scheduler | claim 50 deliveries whose backoff expired | 4 % of writers | `deliveries` |
| Status API | `SELECT … WHERE event_id = ?` | 95 % of readers | `deliveries` |
| Monitoring | status counts for a time window | 5 % of readers | `deliveries` |

Data: **2,000,000 deliveries** (+ 2 M events, 2 M outbox rows, 500 k recipients) spread over 7 days, 98 % `sent`, 1.5 % pending (`queued`/`retry`),
0.5 % `failed`, newest 5,000 outbox rows unpublished. This is a scaled-down window: SIS #1's 7-day state is ≈ 1.8 billion deliveries (259.2 M/day, ≈ 52 GB/day of delivery state), about 900× more than the 2 M tested.
Load: 8 writer + 4 reader clients (closed loop), run at the same time; durable commits (`synchronous_commit = on`) in every measured run.

**How the hot spots were found:** `pg_stat_statements` on the untuned system shows that 5 statements consume 99.9 % of database time:
the dedupe insert 52.1 %, status read 32.3 %, retry poll 6.9 %, outbox claim 6.6 %, monitoring 2.1 % (`results/pg_stat_statements_s0.txt`).
All five are lookups on a column nothing indexes.

---

## 3. Schema and index design

### 3.1 Tables (`sql/schema_s0_untuned.sql`, final partitioned form in `sql/schema_s2_partitioned.sql`)

| Table | Key | Notes |
|---|---|---|
| `preferences(recipient_id, email_on, push_on)` | PK `recipient_id` | 500 k rows, point lookup only |
| `notification_events(event_id, recipient_id, channel, payload_ref, accepted_at)` | PK `event_id` | append-only; payload lives in object storage (`payload_ref`), keeping rows ~150 B |
| `deliveries(delivery_id, event_id, channel, status, attempts, next_attempt_at, provider_id, accepted_at, updated_at)` | PK `delivery_id` | **hot table**: inserted, then updated 1–5 times. `updated_at` added because the status API returns it |
| `outbox(id, delivery_id, created_at, published_at)` | PK `id` | a queue: `published_at IS NULL` = waiting |

Choices: `uuid` (16 B) instead of text for `event_id`; `bigint` surrogate keys from sequences; `timestamptz` everywhere; `status` as short text.
Foreign keys are **not** declared in any variant: they cannot reference a time-partitioned parent without adding the partition key to every referenced
key, and we wanted the variants to differ only in what we tune. Integrity is provided by the single dispatcher transaction and the UNIQUE key.

### 3.2 Indexes (`sql/schema_s1_indexes.sql`): each one exists for a measured query

| Index | Serves | Why this shape | Size |
|---|---|---|---:|
| `UNIQUE (event_id, channel)` on `deliveries` | ingest dedupe (`ON CONFLICT DO NOTHING`); status read (leading column `event_id`) | one index does both jobs; it is the SIS #1 dedupe key | 77 MB |
| **partial** `(next_attempt_at) WHERE status IN ('queued','retry')` | retry scheduler | only the 1.5 % pending rows are indexed | **0.56 MB** |
| `(accepted_at)` on `deliveries` | monitoring / time-range reports | range scans on time | 28 MB |
| **partial** `outbox (id) WHERE published_at IS NULL` | outbox relay | only the unpublished tail of the queue is indexed | **0.08 MB** (the PK is 43 MB) |

**Deviation from SIS #1, with evidence.** SIS #1 proposed `INDEX(status, next_attempt_at)`. We built it and compared it with the partial index on the retry
poll (`results/plans/index_size_s1.txt`):

| | size | execution time | buffers touched |
|---|---:|---:|---:|
| full composite `(status, next_attempt_at)` | 14.7 MB | **40.9 ms** | 19,108 |
| partial `(next_attempt_at) WHERE status IN (…)` | 0.56 MB | **0.16 ms** | 69 |

With `status IN ('queued','retry')` the composite index returns all 30,691 pending rows and then sorts them to find the oldest 50; the partial
index is already ordered by `next_attempt_at`, so it stops after 50 rows (**≈ 250× faster**, 26× smaller). The partial index is also cheaper to
maintain: a row leaving the pending set is the only thing that touches it, whereas the composite index entry changes on *every* status update.

Index cost: S0 → S1 grows `deliveries` indexes from 43 MB to 149 MB (+106 MB, +13 % of the three tables' 784 MB total).

---

## 4. Query-plan analysis (`EXPLAIN (ANALYZE, BUFFERS)`)

Server-side time of one execution on the 2 M-row dataset; *pages* = shared buffers touched. Full plans: `results/plans/s0.txt`, `s1.txt`, `s2.txt`.

| Query | S0 untuned | S1 + indexes | S2 + partitioning |
|---|---|---|---|
| **Q1** dedupe lookup `(event_id, channel)` | 108 ms, **Seq Scan**, 26,354 pages | **0.098 ms**, Index Only Scan, 5 pages | 0.29 ms, 8 partition index probes, 23 pages |
| **Q2** status read by `event_id` | 107 ms, **Seq Scan**, 26,354 pages | **0.019 ms**, Index Scan, 4 pages | 0.086 ms, 8 probes, 23 pages |
| **Q2b** status read with the day known | – | – | **0.034 ms**, 8 partitions pruned, 4 pages |
| **Q3** ACK update | 0.17 ms (PK) | 0.19 ms | 0.27 ms (PK of one partition) |
| **Q4** outbox claim, 50 rows | 131 ms, PK index walked past ~2 M published rows, 20,740 pages | **0.62 ms**, partial index, 567 pages | 0.82 ms, partial index per partition |
| **Q5** retry poll, 50 rows | 137 ms, **Seq Scan** + top-N sort, 27,083 pages | **1.10 ms**, partial index, 1,103 pages | 1.77 ms |
| **Q6** monitoring, 1-hour window | 136 ms, Seq Scan, 26,428 pages | 2.5 ms, index range scan | 3.3 ms, 6 partitions pruned |
| **Q7** one-day report (failed count) | 119 ms, Seq Scan | 38 ms, index scan (5,742 pages) | **29 ms**, 8 partitions pruned, scans one partition (3,765 pages) |

What the plans show:

* **Q1, Q2, Q5, Q6, Q7 in S0 are `Seq Scan`s** over all 26,354 pages of `deliveries`: ~110–140 ms each, and *every ingest runs Q1*, so a single event costs a
  full table scan. Cost grows linearly with table size; at SIS #1's real volume this is hopeless.
* **Q4 is the classic queue-table trap.** The primary key is walked in `id` order and filtered by `published_at IS NULL`; the unpublished rows are the *newest*,
  so PostgreSQL reads past ~2 M already-published entries (20,740 pages) to find them. The partial index contains only waiting rows.
* **Q3 never needed tuning** (a primary-key lookup); it is the control that shows the gain is not a measurement artefact.
* **Partitioned plans touch more pages for point lookups** (Q1, Q2: 23 vs 5 pages) because the planner/executor probes every partition's index when only `event_id`
  is known. Only Q2b, Q6 and Q7 can prune.
* After the relay has run for a while, Q4's partial index touches hundreds of pages even for 50 rows: published entries stay in the index until vacuumed.
  Queue tables need aggressive autovacuum (recommendation 6).

---

## 5. Partitioning

**Design (`sql/schema_s2_partitioned.sql`).** `notification_events`, `deliveries` and `outbox` are `PARTITION BY RANGE` on a timestamp, one partition per UTC day:
7 days of history + today + tomorrow. This implements SIS #1's "partition deliveries by date, 7-day retention" and extends it to the other two big append-heavy tables.

**Consequences we had to design around:**

1. *Every primary key and UNIQUE index must contain the partition key.* The SIS #1 key `UNIQUE(event_id, channel)` cannot be enforced across days; it
   becomes `UNIQUE(event_id, channel, accepted_at)` and PKs become `(delivery_id, accepted_at)` / `(event_id, accepted_at)` / `(id, created_at)`.
   This dedupes correctly **only if the same event always carries the same `accepted_at`**. So `accepted_at` must be assigned once by the ingress API when the
   event is accepted and travel with the event through Kafka, never be `now()` at insert time. A replay arriving 2 days later still lands in the original partition and is deduplicated.
   **This is a correction to the SIS #1 design.**
2. *Workers must carry `accepted_at` in their job message* so `UPDATE … WHERE delivery_id = ? AND accepted_at = ?` prunes to one partition (Q3: 0.27 ms).
3. *The status API only knows `event_id`*, so it probes all partitions, which is the main performance cost (below).
4. Partitions must be created ahead of time (`create_day_partitions()`; in production cron or `pg_partman` creates tomorrow's partition today) and dropped on expiry.

**What partitioning cost (measured, S1 → S2, same workload):**

| | S1 | S2 |
|---|---:|---:|
| Status read p50 / p95 | 0.34 / 3.55 ms | 2.06 / 7.81 ms |
| Ingest p95 | 14.7 ms | 18.8 ms |
| Whole-mix throughput | 4,499 tx/s | 2,137 tx/s |
| Index + table size (3 tables) | 891 MB | 1,000 MB (+12 %) |

Two earlier complete runs (made with an earlier version of the harness) showed the same direction for status read (p50 0.24 → 1.83 ms and 0.43 → 2.20 ms); the ingest p95 change varied
between roughly 0 % and +28 % across runs, so treat it as small.

**Why: planning, not execution.** `EXPLAIN` shows execution for Q2 rising only 0.019 → 0.086 ms, but *planning* rising 0.088 → 0.327 ms (and 0.79 → 1.89 ms for Q1):
with the simple protocol every query is re-planned across 9 partitions with several indexes each. We tested this directly, with reader clients only
(`E3_*` runs, 4 clients, 20 s each):

| Status read | simple protocol (plans every call) | prepared statements (plan reused) |
|---|---:|---:|
| S1 unpartitioned | p50 0.14 ms, 23,471 tx/s | p50 **0.07 ms**, 45,283 tx/s |
| S2, event_id only | p50 0.86 ms, 3,941 tx/s | p50 0.27 ms, 13,246 tx/s |
| S2, event_id + day (prunes to 1 partition) | p50 1.59 ms, 2,319 tx/s | p50 **0.10 ms**, 34,285 tx/s |

With prepared statements (the default in most Java/JDBC and Go drivers after a few calls) and a lookup that carries the day, the partitioned table is within ~0.03 ms
of the unpartitioned one. With the simple protocol, partitioning is a clear loss for point lookups. Our main benchmark uses the simple protocol (it is compatible with
transaction pooling out of the box), so **its S2 numbers are the pessimistic case**.

**What partitioning gained:**

| | unpartitioned (S1) | partitioned (S2) |
|---|---:|---:|
| Retention of the oldest day (≈285 k rows × 3 tables) | `DELETE` 1.02 s + `VACUUM` 2.04 s = **3.05 s** | `DETACH` + `DROP` = **190 ms** (16× faster) |
| WAL written by retention | **146 MB** | **336 KB** (436× less) |
| Dead rows / bloat left behind | 285 k per table until vacuumed | none |
| Time-range report (Q7) | 38 ms | 29 ms, scans only 1 of 9 partitions |
| Monitoring window (Q6) | 2.5 ms | 3.3 ms (no gain; the index already handled it) |

The value of partitioning here is **maintenance**: expiry is a metadata operation instead of deleting hundreds of millions of rows a day (SIS #1: 259.2 M events/day) and replaying those deletes
on the replica, and vacuum/analyze work per partition instead of on one table. It is not a latency optimisation for these queries.

---

## 6. Read replica (streaming replication)

**Setup** (`scripts/setup_replica.sh`): `pg_basebackup -R -X stream` → hot standby on a second port, asynchronous streaming. PgBouncer exposes it as database `notif_ro`.

**Routing rule.** Replica: the status API and monitoring/reports (read-only, tolerate small staleness). Primary: dedupe + ingest, outbox relay, retry scheduler, ACK updates
(they write, or must see the newest state). The replica rejected a write as expected: `cannot execute INSERT in a read-only transaction`.

**Measured (under the full benchmark load, 300 probes + 1 Hz sampling):**

| | value |
|---|---|
| Replication visibility delay (commit on primary → readable on replica) | p50 9.2 ms, p95 17.6 ms, p99 63.6 ms, max 106 ms |
| `pg_stat_replication` replay lag | p50 9.1 ms, p95 18.9 ms, max 21.3 ms |
| Un-replayed WAL | p95 280 KiB, max 382 KiB |
| CPU (percent of one core) before → after moving reads | primary **94 % → 42 %**, replica 49 % |

An earlier run on the same test bed showed a worse p99 visibility delay (240 ms), so the tail varies; plan for hundreds of milliseconds, not microseconds.

**What the replica gave and did not give.** It **moved the read work**: the primary's CPU share more than halved, so on separate machines the primary would have headroom
for the 3,000 writes/s. On this one-core test bed it could *not* improve latency: both servers share the core, replay is extra work (the replica also does ~49 % of a core),
and measured ingest p95 rose 18.8 → 23.1 ms and read p95 7.8 → 9.3 ms. The latency benefit of a replica is therefore **not demonstrated here**; what is demonstrated is the offload
(CPU table) and the cost (lag).

**Consequences for the API:** a client that does `POST` then immediately `GET` may see 404 or `queued` on the replica for ~10–60 ms (read-your-writes). Return the status
in the `202` response, or send reads within ~1 s of the write to the primary. Alert on replay lag > 1 s. Not tested here: long replica queries conflicting with replay
(`max_standby_streaming_delay`, `hot_standby_feedback`), failover.

---

## 7. Connection pooling (PgBouncer, transaction mode, pool size 20)

SIS #1 plans 12–24 API pods and 12–24 workers; each with its own connection pool easily means hundreds of PostgreSQL connections.

**200 concurrent client connections, same mixed workload on the primary (S2):**

| | PostgreSQL backends | tx/s | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| direct | **209** | 1,419 | 74.6 ms | 287 ms | 350 ms |
| via PgBouncer | **21** | 1,590 (+12 %) | 119 ms | 201 ms (−30 %) | 260 ms (−26 %) |

Per transaction type: ingest p95 323 → 218 ms (better); status read p95 90 → 188 ms and ACK update 101 → 194 ms (worse).

**Why:** 200 runnable backends on a few cores waste time context-switching and thrash caches; PgBouncer admits only 20 at a time, which smooths the tail and raises throughput.
The cost is queueing: a 0.3 ms read must now wait for one of 20 server connections behind slower writes, so light queries lose and heavy ones win.
The real gains of pooling are bounded backend count (memory: ~5–10 MB per backend, not measured here) and protection from connection storms, not raw speed.

**Not a measurable gain here:** connect → query → disconnect cost was 3.31 ms direct vs 3.68 ms through PgBouncer. Locally there is no TLS, no password hashing and no network
round trip, so the saving that a pool normally provides is not visible; do not claim it from this test.
**At normal concurrency (12 clients, L3 → L4) PgBouncer changed nothing** (ingest p95 23.1 → 24.9 ms, 5 % of a core used by the pooler).

**Operating rules:** transaction pooling forbids session state (`SET`, session advisory locks, `LISTEN`); prepared statements need PgBouncer ≥ 1.21 with `max_prepared_statements`;
size the pool at roughly 2–4× the primary's cores.

---

## 8. Before/after benchmark (cumulative ladder, one change per step)

Same data, same mixed workload, 8 writers + 4 readers; L0 measured 180 s, other steps 45 s after a 10 s warm-up. Percentiles from per-transaction pgbench logs. Charts: `results/charts/`.

**Dispatcher ingest transaction** (write path)

| Step | samples | tx/s | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| L0 untuned | 725 | 4.0 | 1,596 ms | 1,958 ms | 2,141 ms |
| L1 + indexes | 33,025 | 734 | 6.42 | 14.66 | 20.53 |
| L2 + partitioning | 24,304 | 540 | 8.29 | 18.80 | 26.20 |
| L3 + read replica | 20,256 | 450 | 9.96 | 23.05 | 32.04 |
| L4 + PgBouncer | 18,129 | 403 | 12.04 | 24.89 | 33.80 |

**Status API read**

| Step | samples | tx/s | p50 | p95 | p99 |
|---|---:|---:|---:|---:|---:|
| L0 untuned | 962 | 5.3 | 673 ms | 991 ms | 1,088 ms |
| L1 + indexes | 140,312 | 3,118 | 0.34 | 3.55 | 11.23 |
| L2 + partitioning | 52,850 | 1,174 | 2.06 | 7.81 | 15.18 |
| L3 + read replica | 44,788 | 995 | 2.51 | 9.30 | 17.75 |
| L4 + PgBouncer | 39,029 | 867 | 3.11 | 9.80 | 18.35 |

**Other transactions, p95 / p99 in ms** (\* = n < 100 at L0)

| Step | Outbox relay | Retry poll | Provider-ACK update | Monitoring |
|---|---|---|---|---|
| L0 untuned | 2,672 / 2,799\* | 2,931 / 3,362\* | 54.4 / 75.9 | 1,252 / 1,368\* |
| L1 + indexes | 10.0 / 15.1 | 18.0 / 26.3 | 10.5 / 15.3 | 14.7 / 21.1 |
| L2 + partitioning | 15.2 / 22.2 | 32.5 / 43.3 | 13.9 / 20.7 | 24.9 / 33.0 |
| L3 + read replica | 17.4 / 26.1 | 41.9 / 54.2 | 16.1 / 24.4 | 27.7 / 38.4 |
| L4 + PgBouncer | 17.8 / 25.8 | 42.1 / 56.1 | 15.7 / 23.6 | 28.7 / 40.1 |

**Whole workload:** 11.9 → 4,499 → 2,137 → 1,794 → 1,584 tx/s; p95 2,332 → 10.8 → 16.2 → 19.2 → 21.1 ms; p99 2,724 → 15.7 → 24.4 → 29.5 → 31.1 ms.

**Reading the ladder honestly.** The one enormous step is **L0 → L1** (indexes). Every later step is *slightly worse* on this test bed; that is expected and explained in section 9:
partitioning adds planning cost (simple protocol), and the replica and the pooler are extra processes competing for the **single CPU core** that also runs the load generator.
Their benefits (maintenance, offload, connection limits) show up in sections 5–7, not in this ladder. Do not read "L4 is slower than L1" as "tuning made it worse"; L1 has none of the maintenance,
scaling and connection-management properties of L4.

---

## 9. Explanation of every gain

| # | Change | Mechanism | Evidence | Gain | Trade-off |
|---|---|---|---|---|---|
| 1 | `UNIQUE (event_id, channel)` | dedupe and status lookup become a 4–5 page B-tree descent instead of reading 26,354 pages | Q1 108 → 0.098 ms; Q2 107 → 0.019 ms; ingest p95 1,958 → 14.7 ms; status p95 991 → 3.55 ms | ≈ 133× / 279× (p95) | +77 MB; one more index to maintain on every insert |
| 2 | partial index on pending deliveries | retry poll reads an index of only 1.5 % of rows, already ordered by `next_attempt_at`, and stops after 50 | Q5 137 → 1.10 ms; vs composite index 40.9 → 0.16 ms; retry p95 2,931 → 18.0 ms | ≈ 160× | 0.56 MB; only matches queries with the same status predicate |
| 3 | partial index on unpublished outbox | the relay no longer walks ~2 M published PK entries | Q4 131 → 0.62 ms; relay p95 2,672 → 10.0 ms | ≈ 267× | 0.08 MB; published entries need vacuum to leave the index |
| 4 | index on `accepted_at` | time-window queries range-scan instead of scanning the table | Q6 136 → 2.5 ms; monitoring p95 1,252 → 14.7 ms | ≈ 85× | 28 MB |
| 5 | (side effect of 1–4) | the CPU is no longer spent scanning, so *every* transaction, even untouched ones, gets faster | ACK update p95 54.4 → 10.5 ms though Q3 is unchanged; total 11.9 → 4,499 tx/s | ≈ 5× for ACK | – |
| 6 | daily range partitioning | expiry becomes `DETACH` + `DROP`; time-range queries prune partitions | retention 3.05 s / 146 MB WAL → 0.19 s / 336 KB; Q7 38 → 29 ms (8 partitions pruned) | 16× faster, 436× less WAL | status read p50 0.34 → 2.06 ms with simple protocol (planning over 9 partitions); PK/UNIQUE must include the key; +12 % size |
| 7 | partition-aware lookups + prepared statements | pruning to 1 partition and reusing the plan removes the planning cost | status read p50 0.86 → 0.27 → **0.10 ms** vs 0.07 ms unpartitioned | recovers ~90 % of the partitioning penalty | the id must encode its day (UUIDv7 / date prefix) |
| 8 | read replica | status/monitoring reads execute on another server | primary CPU 94 % → 42 %; replica 49 % | primary headroom (≈ 2× less read load); **no latency gain on 1 core** | 9–64 ms staleness, WAL shipping, one more server |
| 9 | PgBouncer (transaction mode) | caps concurrent backends at 20 | 209 → 21 backends; p99 350 → 260 ms, +12 % tx/s at 200 clients | smoother tail, bounded connections | light reads queue (read p95 90 → 188 ms); no session state; no gain at 12 clients |

---

## 10. Recommendations for the SIS #1 design

1. **Index the dedupe key, the pending set and the unpublished outbox tail from day one** (rows 1–3). They are the entire difference between 4 and 400+ ingest transactions/s here.
2. **Replace `INDEX(status, next_attempt_at)` with a partial index on `next_attempt_at WHERE status IN ('queued','retry')`.**
3. **Fix the dedupe key for partitioning:** `UNIQUE(event_id, channel, accepted_at)` with `accepted_at` assigned once at ingress and carried through Kafka.
4. **Make ids carry their day** (UUIDv7 or date-prefixed) so `GET /v1/notifications/{id}` prunes to one partition; use prepared statements.
5. **Automate partitions:** create tomorrow's today, drop the 8th day (`pg_partman` or cron), and alert if tomorrow's partition is missing (inserts would fail).
6. **Treat `outbox` as a queue:** per-table aggressive autovacuum (low `autovacuum_vacuum_scale_factor`, high cost limit) and/or partition/rotate it, otherwise dead index entries slow the relay.
7. **Replica:** route reads per section 6, return status in the `202`, alert on replay lag > 1 s.
8. **PgBouncer:** one pool per role (API, dispatcher, workers), sized from core count, not from pod count.
9. **Capacity (indicative only):** on this single shared core the dispatcher transaction sustained ≈ 540–730 events/s alongside the rest of the mix. SIS #1's 3,000 events/s therefore
   needs several cores and/or batched inserts, to be validated on production-like hardware.

---

## 11. Limitations (what these numbers do and do not show)

* **One CPU core, 4 GB RAM** shared by the load generator, primary, replica and PgBouncer. Absolute latencies are not transferable; relative gains from indexes are huge and robust,
  but **replica and pooler gains that depend on separate hardware cannot appear** and their costs are exaggerated.
* **Scale:** 2 M deliveries (≈ 784 MB), fits in memory. SIS #1's volume is ≈ 900× larger, and cold I/O would make the untuned scans far worse.
* **One measurement per configuration** (45 s; L0 180 s) in the final run. Two earlier complete runs (earlier harness version) agreed on every direction and on the order of magnitude (ingest p95 ≈ 1.9 s before, ≈ 17–18 ms after indexes), but numbers differ by up to ±20 % between runs;
  differences smaller than that (for example ingest p95 L1 vs L2) are not conclusions.
* Cells marked \* rest on fewer than 100 samples; the plan timings in section 4 are the reliable evidence for those.
* The main benchmark uses the simple query protocol (PgBouncer-compatible); prepared statements were only tested for status reads (section 5).
* WAL bytes per transaction in the L3/L4 rows are inflated by checkpoint full-page images after the base backup and are not reported.
* Not tested: failover, vacuum/bloat over days, replica query conflicts, TLS/SCRAM connection cost, network latency, PgBouncer HA.
