-- S1 only: SIS #1 proposed INDEX deliveries(status, next_attempt_at). Compare it with our PARTIAL index.
\set ON_ERROR_STOP on
\pset pager off
SET timezone = 'UTC';
CREATE INDEX tmp_status_next_idx ON deliveries (status, next_attempt_at);
\echo === sizes of the candidate indexes
SELECT relname AS index, pg_size_pretty(pg_relation_size(oid)) AS size, pg_relation_size(oid) AS bytes
  FROM pg_class
 WHERE relname IN ('deliveries_due_idx', 'tmp_status_next_idx', 'deliveries_event_channel_uq', 'deliveries_pkey',
                   'deliveries_accepted_at_idx', 'outbox_unpublished_idx', 'outbox_pkey')
 ORDER BY bytes DESC;
\echo
\echo === Q5 with the FULL composite index (status, next_attempt_at) only
BEGIN;
DROP INDEX deliveries_due_idx;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH c AS (SELECT delivery_id, accepted_at FROM deliveries
            WHERE status IN ('queued', 'retry') AND next_attempt_at <= now()
            ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
SELECT count(*) FROM c;
ROLLBACK;
\echo
\echo === Q5 with the PARTIAL index only
BEGIN;
DROP INDEX tmp_status_next_idx;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH c AS (SELECT delivery_id, accepted_at FROM deliveries
            WHERE status IN ('queued', 'retry') AND next_attempt_at <= now()
            ORDER BY next_attempt_at LIMIT 50 FOR UPDATE SKIP LOCKED)
SELECT count(*) FROM c;
ROLLBACK;
DROP INDEX IF EXISTS tmp_status_next_idx;
