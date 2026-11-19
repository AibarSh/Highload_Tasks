# SIS #3 – measured results

All numbers come from `pgbench` per-transaction logs (latency in **ms**), same mixed workload in every step. Closed-loop clients, one shared CPU core for client + primary (+ replica + pooler), so read them as *relative* gains.

## 1. Before/after ladder (cumulative, one change per step)

\* = fewer than 100 samples for p99 (or 20 for p95): the untuned schema is so slow that rare transaction types complete only a handful of times; treat those cells as indicative and rely on the EXPLAIN ANALYZE comparison in section 4.

### Headline: BEFORE (L0 untuned) vs AFTER (L4 = indexes + partitioning + replica + PgBouncer)

| Transaction | before p95 ms | after p95 ms | before p99 ms | after p99 ms | p95 gain | p99 gain | before tx/s | after tx/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Dispatcher ingest (write) | 1,958 | 24.89 | 2,141 | 33.80 | 79x | 63x | 4.0 | 403 |
| Status API read | 991 | 9.80 | 1,088 | 18.35 | 101x | 59x | 5.3 | 867 |
| Provider-ACK update | 54.43 | 15.74 | 75.94 | 23.57 | 3x | 3x | 1.7 | 202 |
| Outbox relay claim | 2,672 | 17.82 | 2,799\* | 25.80 | 150x | 108x | 0.3 | 39.8 |
| Retry scheduler poll | 2,931 | 42.08 | 3,362\* | 56.05 | 70x | 60x | 0.3 | 27.4 |
| Monitoring count | 1,252 | 28.71 | 1,368\* | 40.12 | 44x | 34x | 0.2 | 44.9 |

### Dispatcher ingest transaction (write path, SIS #1 step 2)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 725 | 4.0 | 1,596 | 1,958 | 2,141 | 1.0x | 1.0x |
| L1 + indexes (S1) | 33,025 | 734 | 6.42 | 14.66 | 20.53 | 133.5x | 104.3x |
| L2 + partitioning (S2) | 24,304 | 540 | 8.29 | 18.80 | 26.20 | 104.1x | 81.7x |
| L3 + read replica | 20,256 | 450 | 9.96 | 23.05 | 32.04 | 84.9x | 66.8x |
| L4 + PgBouncer | 18,129 | 403 | 12.04 | 24.89 | 33.80 | 78.6x | 63.3x |

### Status API read `GET /v1/notifications/{id}` (read path)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 962 | 5.3 | 673 | 991 | 1,088 | 1.0x | 1.0x |
| L1 + indexes (S1) | 140,312 | 3,118 | 0.34 | 3.55 | 11.23 | 279.1x | 96.8x |
| L2 + partitioning (S2) | 52,850 | 1,174 | 2.06 | 7.81 | 15.18 | 126.8x | 71.7x |
| L3 + read replica | 44,788 | 995 | 2.51 | 9.30 | 17.75 | 106.5x | 61.3x |
| L4 + PgBouncer | 39,029 | 867 | 3.11 | 9.80 | 18.35 | 101.1x | 59.3x |

### Outbox relay claim

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 55 | 0.3 | 2,472 | 2,672 | 2,799\* | 1.0x | 1.0x |
| L1 + indexes (S1) | 3,212 | 71.4 | 4.07 | 10.00 | 15.07 | 267.2x | 185.7x |
| L2 + partitioning (S2) | 2,469 | 54.9 | 7.53 | 15.15 | 22.21 | 176.4x | 126.0x |
| L3 + read replica | 2,045 | 45.4 | 8.19 | 17.37 | 26.14 | 153.8x | 107.1x |
| L4 + PgBouncer | 1,789 | 39.8 | 9.27 | 17.82 | 25.80 | 149.9x | 108.5x |

### Retry scheduler poll

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 56 | 0.3 | 2,664 | 2,931 | 3,362\* | 1.0x | 1.0x |
| L1 + indexes (S1) | 2,229 | 49.5 | 10.88 | 18.01 | 26.33 | 162.8x | 127.7x |
| L2 + partitioning (S2) | 1,620 | 36.0 | 18.71 | 32.48 | 43.26 | 90.2x | 77.7x |
| L3 + read replica | 1,353 | 30.1 | 23.52 | 41.87 | 54.19 | 70.0x | 62.0x |
| L4 + PgBouncer | 1,233 | 27.4 | 25.41 | 42.08 | 56.05 | 69.7x | 60.0x |

### Provider-ACK status update

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 299 | 1.7 | 29.53 | 54.43 | 75.94 | 1.0x | 1.0x |
| L1 + indexes (S1) | 16,564 | 368 | 4.00 | 10.48 | 15.27 | 5.2x | 5.0x |
| L2 + partitioning (S2) | 12,116 | 269 | 5.61 | 13.90 | 20.68 | 3.9x | 3.7x |
| L3 + read replica | 10,048 | 223 | 6.34 | 16.08 | 24.38 | 3.4x | 3.1x |
| L4 + PgBouncer | 9,097 | 202 | 6.97 | 15.74 | 23.57 | 3.5x | 3.2x |

### Monitoring count (fixed 1-hour window)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 45 | 0.2 | 926 | 1,252 | 1,368\* | 1.0x | 1.0x |
| L1 + indexes (S1) | 7,131 | 158 | 2.89 | 14.73 | 21.14 | 85.0x | 64.7x |
| L2 + partitioning (S2) | 2,809 | 62.4 | 8.30 | 24.91 | 32.96 | 50.3x | 41.5x |
| L3 + read replica | 2,247 | 49.9 | 9.04 | 27.69 | 38.39 | 45.2x | 35.6x |
| L4 + PgBouncer | 2,021 | 44.9 | 10.72 | 28.71 | 40.12 | 43.6x | 34.1x |

### Whole workload (all transaction types together)

| Step | total tx/s | p95 ms | p99 ms | WAL bytes / tx |
|---|---:|---:|---:|---:|
| L0 untuned (S0) | 11.9 | 2,332 | 2,724 | 1,171 |
| L1 + indexes (S1) | 4,499 | 10.78 | 15.72 | 483 |
| L2 + partitioning (S2) | 2,137 | 16.19 | 24.39 | 792 |
| L3 + read replica | 1,794 | 19.23 | 29.45 | 3,338 |
| L4 + PgBouncer | 1,584 | 21.11 | 31.10 | 1,290 |

### Side experiment: partition-aware status read (S2 schema)

Partitioning by `accepted_at` means `GET /v1/notifications/{id}` (which only knows `event_id`) must probe every partition. If the id carries its day (UUIDv7 / date-prefixed id), the query can prune to one partition.

| Status read | samples | tps | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|
| S1 (unpartitioned, one index probe) | 140,312 | 3,118 | 0.34 | 3.55 | 11.23 |
| S2, id only -> probes all partitions | 52,850 | 1,174 | 2.06 | 7.81 | 15.18 |
| S2, id + day -> prunes to one partition | 37,531 | 834 | 3.50 | 9.98 | 16.31 |

### Planning overhead: simple protocol (re-plans every query) vs prepared statements (plan reused)

Status read only, 4 reader clients and no writers, so the three schema variants are compared without interference.

| Variant | protocol | samples | tx/s | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| S1 unpartitioned | simple | 469,411 | 23,471 | 0.14 | 0.29 | 0.48 |
| S1 unpartitioned | prepared | 905,652 | 45,283 | 0.07 | 0.14 | 0.21 |
| S2 partitioned, id only | simple | 78,825 | 3,941 | 0.86 | 2.22 | 3.32 |
| S2 partitioned, id only | prepared | 264,914 | 13,246 | 0.27 | 0.55 | 0.79 |
| S2 partitioned, id + day | simple | 46,380 | 2,319 | 1.59 | 3.39 | 4.41 |
| S2 partitioned, id + day | prepared | 685,695 | 34,285 | 0.10 | 0.20 | 0.28 |

### Where the CPU went (percent of ONE core, averaged over the measured run)

This sandbox has a single core, so everything competes for it. The useful signal is *which process* does the work.

| Step | primary | replica | PgBouncer | total DB-side |
|---|---:|---:|---:|---:|
| L0 untuned (S0) | 64% | 0% | 0% | 64% |
| L1 + indexes (S1) | 92% | 0% | 0% | 92% |
| L2 + partitioning (S2) | 94% | 0% | 0% | 94% |
| L3 + read replica | 42% | 49% | 0% | 91% |
| L4 + PgBouncer | 41% | 47% | 5% | 92% |

## 2. Connection pooling (many concurrent client connections, S2 schema)

| Mode | client connections | PostgreSQL backends | tx/s | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|
| direct to PostgreSQL | 200 | 209 | 1,419 | 74.60 | 287 | 350 |
| via PgBouncer (transaction pooling) | 200 | 21 | 1,590 | 119 | 201 | 260 |

- `ingest`: p95 323 → 218 ms, p99 403 → 274 ms
- `status_read`: p95 89.81 → 188 ms, p99 115 → 237 ms
- `ack_update`: p95 101 → 194 ms, p99 149 → 244 ms

### Cost of connect → query → disconnect (an app *without* a pool pays this on every request)

| Path | mean ms | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| new connection straight to PostgreSQL | 3.311 | 3.244 | 3.747 | 4.196 |
| new client connection to PgBouncer (server connection reused) | 3.676 | 3.414 | 4.859 | 5.952 |

## 3. Read replica

**Replication visibility delay** (commit on primary → row readable on replica, measured under full benchmark load, 300 probes): mean 10.4 ms, p50 9.2 ms, p95 17.6 ms, p99 63.6 ms, max 105.9 ms.

**`pg_stat_replication` samples (1 per second during the run):** replay_lag p50 9.1 ms, p95 18.9 ms, max 21.3 ms; unreplayed WAL p95 280 KiB, max 382 KiB (52 samples).

Write attempt on the replica: `replica write rejected (expected): cannot execute INSERT in a read-only transaction`

## 4. EXPLAIN (ANALYZE, BUFFERS) before → after

*exec* = server-side execution time of one run on the seeded dataset, *plan* = planning time (matters for tiny queries), *pages* = shared buffers touched (hit + read).

| Query | S0 untuned | S1 + indexes | S2 + partitioning |
|---|---|---|---|
| Q1 ingest dedupe lookup: does (event_id, channel) already exist? | exec **107.956 ms** + plan 0.457 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.098 ms** + plan 0.793 ms, 5 pages<br>Index Only Scan (deliveries_event_channel_uq) on deliveries | exec **0.287 ms** + plan 1.891 ms, 23 pages<br>Index Only Scan (deliveries_p20261111_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD; Index Only Scan (deliveries_p20261112_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD (+6 more) |
| Q2 status API: GET /v1/notifications/{id} | exec **106.986 ms** + plan 0.152 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.019 ms** + plan 0.088 ms, 4 pages<br>Index Scan (deliveries_event_channel_uq) on deliveries | exec **0.086 ms** + plan 0.327 ms, 23 pages<br>Index Scan (deliveries_p20261111_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD; Index Scan (deliveries_p20261112_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD (+6 more) |
| Q2b status API, partition-aware (id carries its day) | exec **119.532 ms** + plan 0.206 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.013 ms** + plan 0.081 ms, 4 pages<br>Index Scan (deliveries_event_channel_uq) on deliveries | exec **0.034 ms** + plan 0.642 ms, 4 pages, 8 partitions pruned<br>Index Scan (deliveries_p20261115_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD |
| Q3 worker ACK: UPDATE by delivery_id + accepted_at | exec **0.166 ms** + plan 0.237 ms, 19 pages<br>Index Scan (deliveries_pkey) on deliveries | exec **0.192 ms** + plan 0.065 ms, 25 pages<br>Index Scan (deliveries_pkey) on deliveries | exec **0.265 ms** + plan 0.081 ms, 31 pages<br>Index Scan (deliveries_p20261115_pkey) on deliveries_pYYYYMMDD |
| Q4 outbox relay: claim 50 unpublished rows | exec **131.224 ms** + plan 0.271 ms, 20,740 pages<br>Index Scan (outbox_pkey) on outbox | exec **0.622 ms** + plan 0.270 ms, 567 pages<br>Index Scan (outbox_unpublished_idx) on outbox; Index Scan (outbox_pkey) on outbox | exec **0.817 ms** + plan 1.104 ms, 633 pages<br>Index Scan (outbox_p20261111_id_idx) on outbox_pYYYYMMDD; Index Scan (outbox_p20261112_id_idx) on outbox_pYYYYMMDD (+16 more) |
| Q5 retry scheduler: claim 50 due deliveries | exec **137.077 ms** + plan 0.273 ms, 27,083 pages<br>Seq Scan on deliveries; Index Scan (deliveries_pkey) on deliveries | exec **1.096 ms** + plan 0.342 ms, 1,103 pages<br>Index Scan (deliveries_due_idx) on deliveries; Index Scan (deliveries_pkey) on deliveries | exec **1.766 ms** + plan 0.853 ms, 1,151 pages<br>Index Scan (deliveries_p20261111_next_attempt_at_idx) on deliveries_pYYYYMMDD; Index Scan (deliveries_p20261112_next_attempt_at_idx) on deliveries_pYYYYMMDD (+16 more) |
| Q6 monitoring: status counts for a 1-hour window | exec **136.046 ms** + plan 0.133 ms, 26,428 pages<br>Seq Scan on deliveries | exec **2.472 ms** + plan 0.139 ms, 236 pages<br>Index Scan (deliveries_accepted_at_idx) on deliveries | exec **3.259 ms** + plan 0.427 ms, 235 pages, 6 partitions pruned<br>Index Scan (deliveries_p20261117_accepted_at_idx) on deliveries_pYYYYMMDD |
| Q7 daily report: failed deliveries for ONE seeded day (time-range query) | exec **119.436 ms** + plan 0.154 ms, 26,354 pages<br>Seq Scan on deliveries | exec **38.065 ms** + plan 0.083 ms, 5,742 pages<br>Index Scan (deliveries_accepted_at_idx) on deliveries | exec **29.071 ms** + plan 0.492 ms, 3,765 pages, 8 partitions pruned<br>Seq Scan on deliveries_pYYYYMMDD |

### Sizes S0
```
table        | table_size | index_size | total  
---------------------+------------+------------+--------
 deliveries          | 206 MB     | 43 MB      | 249 MB
 notification_events | 301 MB     | 76 MB      | 377 MB
 outbox              | 115 MB     | 43 MB      | 158 MB
(3 rows)
```

### Sizes S1
```
table        | table_size | index_size | total  
---------------------+------------+------------+--------
 deliveries          | 206 MB     | 149 MB     | 356 MB
 notification_events | 301 MB     | 76 MB      | 377 MB
 outbox              | 115 MB     | 43 MB      | 158 MB
(3 rows)
```

### Sizes S2
```
table        | table_size | index_size | total  
---------------------+------------+------------+--------
 deliveries          | 206 MB     | 213 MB     | 419 MB
 notification_events | 301 MB     | 104 MB     | 405 MB
 outbox              | 115 MB     | 60 MB      | 176 MB
(3 rows)
```

## 5. Retention: removing the oldest day (≈1/7 of the data)

| Method | operation | time |
|---|---|---:|
| unpartitioned | DELETE notification_events | 0.50 s |
| unpartitioned | DELETE deliveries | 0.23 s |
| unpartitioned | DELETE outbox | 0.29 s |
| unpartitioned | VACUUM notification_events | 0.44 s |
| unpartitioned | VACUUM deliveries | 1.47 s |
| unpartitioned | VACUUM outbox | 0.13 s |
| **unpartitioned total** | DELETE + VACUUM | **3.05 s**; WAL written by the DELETEs: **146 MB** |
| partitioned | DETACH notification_events partition | 3.1 ms |
| partitioned | DETACH deliveries partition | 6.1 ms |
| partitioned | DETACH outbox partition | 1.6 ms |
| partitioned | DROP the three detached partitions | 179.4 ms |
| **partitioned total** | DETACH + DROP | **190 ms**; WAL written: **336 KB**; no dead rows, nothing to vacuum |
| **speed-up** | | **16x faster, 436x less WAL** |
