-- EXPLAIN (ANALYZE, BUFFERS) for every query in the workload. Run on S0, S1 and S2; outputs are compared in REPORT.md.
-- Usage: psql -v n=2000000 -v anchor=<epoch> -f sql/explain.sql
\set ON_ERROR_STOP on
\pset pager off
SET timezone = 'UTC';
SET track_io_timing = on;
SELECT :anchor - 604800 + (1234567::bigint * 604800) / :n AS ts_epoch \gset

\echo
\echo === Q1 ingest dedupe lookup: does (event_id, channel) already exist?
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT 1 FROM deliveries WHERE event_id = md5('1234567')::uuid AND channel = 'push';

\echo
\echo === Q2 status API: GET /v1/notifications/{id}
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT status, attempts, updated_at FROM deliveries WHERE event_id = md5('1234567')::uuid;

\echo
\echo === Q2b status API, partition-aware (id carries its day)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT status, attempts, updated_at FROM deliveries
 WHERE event_id = md5('1234567')::uuid
   AND accepted_at >= date_trunc('day', to_timestamp(:ts_epoch))
   AND accepted_at <  date_trunc('day', to_timestamp(:ts_epoch)) + interval '1 day';

\echo
\echo === Q3 worker ACK: UPDATE by delivery_id + accepted_at
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
UPDATE deliveries SET status = 'sent', provider_id = 'prov-1', updated_at = now()
 WHERE delivery_id = 1234567 AND accepted_at = to_timestamp(:ts_epoch);
ROLLBACK;

\echo
\echo === Q4 outbox relay: claim 50 unpublished rows
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH c AS (SELECT id, created_at FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED)
UPDATE outbox o SET published_at = now() FROM c WHERE o.id = c.id AND o.created_at = c.created_at;
ROLLBACK;

\echo
\echo === Q5 retry scheduler: claim 50 due deliveries
BEGIN;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH c AS (SELECT delivery_id, accepted_at FROM deliveries
            WHERE status IN ('queued', 'retry') AND next_attempt_at <= now()
            ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
UPDATE deliveries d SET attempts = d.attempts + 1, status = 'retry',
       next_attempt_at = now() + interval '5 seconds', updated_at = now()
  FROM c WHERE d.delivery_id = c.delivery_id AND d.accepted_at = c.accepted_at;
ROLLBACK;

\echo
\echo === Q6 monitoring: status counts for a 1-hour window
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT status, count(*) FROM deliveries
 WHERE accepted_at >= to_timestamp(:anchor) - interval '1 hour' AND accepted_at < to_timestamp(:anchor)
 GROUP BY status;

\echo
\echo === Q7 daily report: failed deliveries for ONE seeded day (time-range query)
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM deliveries
 WHERE accepted_at >= to_timestamp(:anchor) - interval '3 days'
   AND accepted_at <  to_timestamp(:anchor) - interval '2 days'
   AND status = 'failed';
