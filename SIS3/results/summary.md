# SIS #3 – measured results

All numbers come from `pgbench` per-transaction logs (latency in **ms**), same mixed workload in every step. Closed-loop clients, one shared CPU core for client + primary (+ replica + pooler), so read them as *relative* gains.

## 1. Before/after ladder (cumulative, one change per step)

\* = fewer than 100 samples for p99 (or 20 for p95): the untuned schema is so slow that rare transaction types complete only a handful of times; treat those cells as indicative and rely on the EXPLAIN ANALYZE comparison in section 4.

### Headline: BEFORE (L0 untuned) vs AFTER (L4 = indexes + partitioning + replica + PgBouncer)

| Transaction | before p95 ms | after p95 ms | before p99 ms | after p99 ms | p95 gain | p99 gain | before tx/s | after tx/s |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Dispatcher ingest (write) | 866 | 6.07 | 1,056 | 11.73 | 143x | 90x | 9.4 | 1,917 |
| Status API read | 249 | 2.07 | 305 | 5.31 | 120x | 57x | 28.2 | 2,814 |
| Provider-ACK update | 3.39 | 2.13 | 11.36 | 4.19 | 2x | 3x | 5.1 | 955 |
| Outbox relay claim | 1,231 | 2.62 | 1,385 | 5.05 | 471x | 274x | 0.9 | 194 |
| Retry scheduler poll | 1,564 | 11.51 | 1,980\* | 18.60 | 136x | 106x | 0.5 | 127 |
| Monitoring count | 465 | 9.52 | 504 | 27.78 | 49x | 18x | 1.7 | 154 |

### Dispatcher ingest transaction (write path, SIS #1 step 2)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 1,693 | 9.4 | 688 | 866 | 1,056 | 1.0x | 1.0x |
| L1 + indexes (S1) | 179,941 | 3,999 | 1.20 | 2.49 | 4.52 | 347.6x | 233.8x |
| L2 + partitioning (S2) | 178,963 | 3,977 | 1.16 | 2.19 | 3.62 | 395.2x | 291.6x |
| L3 + read replica | 100,731 | 2,238 | 1.46 | 5.64 | 11.74 | 153.5x | 90.0x |
| L4 + PgBouncer | 86,284 | 1,917 | 2.39 | 6.07 | 11.73 | 142.5x | 90.0x |

### Status API read `GET /v1/notifications/{id}` (read path)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 5,077 | 28.2 | 111 | 249 | 305 | 1.0x | 1.0x |
| L1 + indexes (S1) | 313,697 | 6,971 | 0.22 | 0.64 | 1.50 | 389.2x | 203.2x |
| L2 + partitioning (S2) | 195,634 | 4,347 | 0.47 | 1.14 | 3.18 | 218.7x | 95.8x |
| L3 + read replica | 121,160 | 2,692 | 0.61 | 3.09 | 8.49 | 80.6x | 35.9x |
| L4 + PgBouncer | 126,648 | 2,814 | 0.77 | 2.07 | 5.31 | 120.0x | 57.4x |

### Outbox relay claim

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 161 | 0.9 | 1,051 | 1,231 | 1,385 | 1.0x | 1.0x |
| L1 + indexes (S1) | 17,949 | 399 | 0.50 | 1.19 | 2.11 | 1037.5x | 655.1x |
| L2 + partitioning (S2) | 17,817 | 396 | 1.02 | 1.73 | 2.52 | 710.6x | 550.7x |
| L3 + read replica | 10,141 | 225 | 1.27 | 2.49 | 4.61 | 494.2x | 300.4x |
| L4 + PgBouncer | 8,738 | 194 | 1.33 | 2.62 | 5.05 | 470.9x | 274.1x |

### Retry scheduler poll

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 98 | 0.5 | 1,348 | 1,564 | 1,980\* | 1.0x | 1.0x |
| L1 + indexes (S1) | 12,022 | 267 | 3.37 | 7.03 | 12.70 | 222.5x | 155.9x |
| L2 + partitioning (S2) | 11,881 | 264 | 4.39 | 6.89 | 10.91 | 227.0x | 181.5x |
| L3 + read replica | 6,605 | 147 | 6.29 | 25.63 | 50.66 | 61.0x | 39.1x |
| L4 + PgBouncer | 5,723 | 127 | 5.56 | 11.51 | 18.60 | 135.9x | 106.5x |

### Provider-ACK status update

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 922 | 5.1 | 0.57 | 3.39 | 11.36 | 1.0x | 1.0x |
| L1 + indexes (S1) | 89,845 | 1,997 | 0.38 | 0.88 | 1.71 | 3.8x | 6.7x |
| L2 + partitioning (S2) | 89,822 | 1,996 | 0.42 | 0.91 | 1.50 | 3.7x | 7.6x |
| L3 + read replica | 50,458 | 1,121 | 0.61 | 3.53 | 7.08 | 1.0x | 1.6x |
| L4 + PgBouncer | 42,962 | 955 | 0.78 | 2.13 | 4.19 | 1.6x | 2.7x |

### Monitoring count (fixed 1-hour window)

| Step | samples | tps | p50 ms | p95 ms | p99 ms | p95 gain vs L0 | p99 gain vs L0 |
|---|---:|---:|---:|---:|---:|---:|---:|
| L0 untuned (S0) | 301 | 1.7 | 232 | 465 | 504 | 1.0x | 1.0x |
| L1 + indexes (S1) | 16,606 | 369 | 4.41 | 6.65 | 18.90 | 69.9x | 26.7x |
| L2 + partitioning (S2) | 10,188 | 226 | 5.02 | 9.66 | 23.56 | 48.1x | 21.4x |
| L3 + read replica | 6,329 | 141 | 5.38 | 12.50 | 52.95 | 37.2x | 9.5x |
| L4 + PgBouncer | 6,938 | 154 | 5.47 | 9.52 | 27.78 | 48.8x | 18.1x |

### Whole workload (all transaction types together)

| Step | total tx/s | p95 ms | p99 ms | WAL bytes / tx |
|---|---:|---:|---:|---:|
| L0 untuned (S0) | 45.8 | 809 | 1,263 | 627 |
| L1 + indexes (S1) | 14,001 | 2.95 | 5.02 | 888 |
| L2 + partitioning (S2) | 11,207 | 3.25 | 5.71 | 1,897 |
| L3 + read replica | 6,565 | 5.55 | 13.48 | 2,007 |
| L4 + PgBouncer | 6,162 | 5.43 | 9.80 | 978 |

### Side experiment: partition-aware status read (S2 schema)

Partitioning by `accepted_at` means `GET /v1/notifications/{id}` (which only knows `event_id`) must probe every partition. If the id carries its day (UUIDv7 / date-prefixed id), the query can prune to one partition.

| Status read | samples | tps | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|
| S1 (unpartitioned, one index probe) | 313,697 | 6,971 | 0.22 | 0.64 | 1.50 |
| S2, id only -> probes all partitions | 195,634 | 4,347 | 0.47 | 1.14 | 3.18 |
| S2, id + day -> prunes to one partition | 129,883 | 2,886 | 0.92 | 1.87 | 4.21 |

### Planning overhead: simple protocol (re-plans every query) vs prepared statements (plan reused)

Status read only, 4 reader clients and no writers, so the three schema variants are compared without interference.

| Variant | protocol | samples | tx/s | p50 ms | p95 ms | p99 ms |
|---|---|---:|---:|---:|---:|---:|
| S1 unpartitioned | simple | 788,561 | 39,428 | 0.09 | 0.17 | 0.24 |
| S1 unpartitioned | prepared | 1,170,800 | 58,540 | 0.06 | 0.11 | 0.17 |
| S2 partitioned, id only | simple | 304,443 | 15,222 | 0.23 | 0.45 | 0.59 |
| S2 partitioned, id only | prepared | 688,074 | 34,404 | 0.10 | 0.20 | 0.29 |
| S2 partitioned, id + day | simple | 151,142 | 7,557 | 0.45 | 0.86 | 1.32 |
| S2 partitioned, id + day | prepared | 777,101 | 38,855 | 0.08 | 0.19 | 0.28 |

### Where the CPU went (percent of ONE core, averaged over the measured run)

This sandbox has a single core, so everything competes for it. The useful signal is *which process* does the work.

| Step | primary | replica | PgBouncer | total DB-side |
|---|---:|---:|---:|---:|
| L0 untuned (S0) | 267% | 0% | 0% | 267% |
| L1 + indexes (S1) | 506% | 0% | 0% | 506% |
| L2 + partitioning (S2) | 553% | 0% | 0% | 553% |
| L3 + read replica | 199% | 196% | 0% | 395% |
| L4 + PgBouncer | 188% | 200% | 43% | 431% |

## 2. Connection pooling (many concurrent client connections, S2 schema)

| Mode | client connections | PostgreSQL backends | tx/s | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|
| direct to PostgreSQL | 200 | 209 | 5,708 | 7.82 | 160 | 341 |
| via PgBouncer (transaction pooling) | 200 | 21 | 9,040 | 20.49 | 31.27 | 45.66 |

- `ingest`: p95 254 → 33.70 ms, p99 426 → 49.92 ms
- `status_read`: p95 70.67 → 29.00 ms, p99 200 → 43.26 ms
- `ack_update`: p95 72.97 → 28.83 ms, p99 172 → 42.71 ms

### Cost of connect → query → disconnect (an app *without* a pool pays this on every request)

| Path | mean ms | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| new connection straight to PostgreSQL | 3.106 | 2.601 | 5.592 | 9.224 |
| new client connection to PgBouncer (server connection reused) | 3.187 | 3.076 | 4.044 | 5.265 |

## 3. Read replica

**Replication visibility delay** (commit on primary → row readable on replica, measured under full benchmark load, 300 probes): mean 117.9 ms, p50 1.0 ms, p95 667.2 ms, p99 1587.6 ms, max 2809.8 ms.

**`pg_stat_replication` samples (1 per second during the run):** replay_lag p50 348.6 ms, p95 1831.1 ms, max 2829.9 ms; unreplayed WAL p95 16343 KiB, max 19363 KiB (61 samples).

Write attempt on the replica: `replica write rejected (expected): cannot execute INSERT in a read-only transaction`

## 4. EXPLAIN (ANALYZE, BUFFERS) before → after

*exec* = server-side execution time of one run on the seeded dataset, *plan* = planning time (matters for tiny queries), *pages* = shared buffers touched (hit + read).

| Query | S0 untuned | S1 + indexes | S2 + partitioning |
|---|---|---|---|
| Q1 ingest dedupe lookup: does (event_id, channel) already exist? | exec **50.728 ms** + plan 1.967 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.057 ms** + plan 2.077 ms, 5 pages<br>Index Only Scan (deliveries_event_channel_uq) on deliveries | exec **0.989 ms** + plan 3.797 ms, 23 pages<br>Index Only Scan (deliveries_p20261112_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD; Index Only Scan (deliveries_p20261113_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD (+6 more) |
| Q2 status API: GET /v1/notifications/{id} | exec **37.988 ms** + plan 0.069 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.008 ms** + plan 0.033 ms, 4 pages<br>Index Scan (deliveries_event_channel_uq) on deliveries | exec **0.033 ms** + plan 0.185 ms, 23 pages<br>Index Scan (deliveries_p20261112_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD; Index Scan (deliveries_p20261113_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD (+6 more) |
| Q2b status API, partition-aware (id carries its day) | exec **59.046 ms** + plan 0.227 ms, 26,354 pages<br>Seq Scan on deliveries | exec **0.007 ms** + plan 0.207 ms, 4 pages<br>Index Scan (deliveries_event_channel_uq) on deliveries | exec **0.018 ms** + plan 1.146 ms, 4 pages, 8 partitions pruned<br>Index Scan (deliveries_p20261116_event_id_channel_accepted_at_idx) on deliveries_pYYYYMMDD |
| Q3 worker ACK: UPDATE by delivery_id + accepted_at | exec **0.761 ms** + plan 0.106 ms, 19 pages<br>Index Scan (deliveries_pkey) on deliveries | exec **0.153 ms** + plan 0.033 ms, 25 pages<br>Index Scan (deliveries_pkey) on deliveries | exec **0.228 ms** + plan 0.046 ms, 31 pages<br>Index Scan (deliveries_p20261116_pkey) on deliveries_pYYYYMMDD |
| Q4 outbox relay: claim 50 unpublished rows | exec **133.281 ms** + plan 0.191 ms, 20,740 pages<br>Index Scan (outbox_pkey) on outbox | exec **0.125 ms** + plan 0.199 ms, 193 pages<br>Index Scan (outbox_unpublished_idx) on outbox; Index Scan (outbox_pkey) on outbox | exec **0.533 ms** + plan 1.124 ms, 633 pages<br>Index Scan (outbox_p20261112_id_idx) on outbox_pYYYYMMDD; Index Scan (outbox_p20261113_id_idx) on outbox_pYYYYMMDD (+16 more) |
| Q5 retry scheduler: claim 50 due deliveries | exec **167.116 ms** + plan 0.143 ms, 27,083 pages<br>Seq Scan on deliveries; Index Scan (deliveries_pkey) on deliveries | exec **1.070 ms** + plan 0.104 ms, 1,100 pages<br>Index Scan (deliveries_due_idx) on deliveries; Index Scan (deliveries_pkey) on deliveries | exec **8.598 ms** + plan 0.414 ms, 1,151 pages<br>Index Scan (deliveries_p20261112_next_attempt_at_idx) on deliveries_pYYYYMMDD; Index Scan (deliveries_p20261113_next_attempt_at_idx) on deliveries_pYYYYMMDD (+16 more) |
| Q6 monitoring: status counts for a 1-hour window | exec **56.801 ms** + plan 0.055 ms, 26,428 pages<br>Seq Scan on deliveries | exec **2.015 ms** + plan 0.066 ms, 241 pages<br>Index Scan (deliveries_accepted_at_idx) on deliveries | exec **2.624 ms** + plan 0.319 ms, 235 pages, 6 partitions pruned<br>Index Scan (deliveries_p20261118_accepted_at_idx) on deliveries_pYYYYMMDD |
| Q7 daily report: failed deliveries for ONE seeded day (time-range query) | exec **54.998 ms** + plan 0.049 ms, 26,354 pages<br>Seq Scan on deliveries | exec **13.453 ms** + plan 0.047 ms, 6,351 pages<br>Index Scan (deliveries_accepted_at_idx) on deliveries | exec **11.751 ms** + plan 0.260 ms, 3,765 pages, 8 partitions pruned<br>Seq Scan on deliveries_pYYYYMMDD |

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
 deliveries          | 207 MB     | 149 MB     | 356 MB
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
| unpartitioned | DELETE notification_events | 0.53 s |
| unpartitioned | DELETE deliveries | 0.25 s |
| unpartitioned | DELETE outbox | 0.29 s |
| unpartitioned | VACUUM notification_events | 0.40 s |
| unpartitioned | VACUUM deliveries | 0.79 s |
| unpartitioned | VACUUM outbox | 0.25 s |
| **unpartitioned total** | DELETE + VACUUM | **2.51 s**; WAL written by the DELETEs: **156 MB** |
| partitioned | DETACH notification_events partition | 16.0 ms |
| partitioned | DETACH deliveries partition | 1.1 ms |
| partitioned | DETACH outbox partition | 2.0 ms |
| partitioned | DROP the three detached partitions | 35.6 ms |
| **partitioned total** | DETACH + DROP | **55 ms**; WAL written: **357 KB**; no dead rows, nothing to vacuum |
| **speed-up** | | **46x faster, 437x less WAL** |
