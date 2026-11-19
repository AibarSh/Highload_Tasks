-- ============================================================================
-- Deterministic seed: N deliveries spread evenly over the 7 days BEFORE :anchor (midnight UTC today).
--   event n  -> event_id = md5(n)::uuid, accepted_at = anchor - 7d + n * 7d / N   (monotonic in n, like a real append-only log)
-- The same arithmetic is used by the pgbench scripts, so "replayed" events hit exactly the rows seeded here.
-- Usage: psql -v n=2000000 -v anchor=<epoch seconds> -f seed.sql
-- ============================================================================
\set ON_ERROR_STOP on
SET timezone = 'UTC';
SET synchronous_commit = off;      -- bulk load only; the benchmark itself runs with synchronous_commit = on

\echo seeding preferences
INSERT INTO preferences
SELECT g, (g % 10) <> 0, (g % 7) <> 0 FROM generate_series(1, 500000) g;

\echo seeding notification_events
INSERT INTO notification_events (event_id, recipient_id, channel, payload_ref, accepted_at)
SELECT md5(g::text)::uuid,
       (g % 500000) + 1,
       CASE WHEN g % 5 = 0 THEN 'email' ELSE 'push' END,
       's3://notif-payloads/' || md5(g::text) || '/' || md5((g + 1)::text),
       to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n)
FROM generate_series(1, :n) g;

\echo seeding deliveries
INSERT INTO deliveries (delivery_id, event_id, channel, status, attempts, next_attempt_at, provider_id, accepted_at, updated_at)
SELECT g,
       md5(g::text)::uuid,
       CASE WHEN g % 5 = 0 THEN 'email' ELSE 'push' END,
       CASE WHEN g % 200 = 0 THEN 'queued'            -- 0.5 %  waiting
            WHEN g % 200 IN (1, 2) THEN 'retry'       -- 1.0 %  provider 429/5xx, retrying
            WHEN g % 200 = 3 THEN 'failed'            -- 0.5 %  exhausted
            ELSE 'sent' END,                          -- 98 %
       CASE WHEN g % 200 = 0 THEN 0 WHEN g % 200 IN (1, 2) THEN 2 WHEN g % 200 = 3 THEN 5 ELSE 1 END,
       CASE WHEN g % 200 IN (0, 1, 2) THEN to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n) + interval '1 minute' END,
       CASE WHEN g % 200 NOT IN (0, 1, 2, 3) THEN 'prov-' || (g % 1000) END,
       to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n),
       to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n) + ((g % 30) || ' seconds')::interval
FROM generate_series(1, :n) g;

\echo seeding outbox
INSERT INTO outbox (id, delivery_id, created_at, published_at)
SELECT g, g,
       to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n),
       CASE WHEN g > :n - 5000 THEN NULL                         -- newest 5 000 rows = relay backlog
            ELSE to_timestamp(:anchor - 604800 + (g::bigint * 604800) / :n) + interval '1 second' END
FROM generate_series(1, :n) g;

SELECT setval('deliveries_delivery_id_seq', :n);
SELECT setval('outbox_id_seq', :n);

\echo vacuum analyze
RESET synchronous_commit;
VACUUM (ANALYZE);
