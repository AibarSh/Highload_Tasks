-- ============================================================================
-- S1  + INDEXES designed for the workload (applied on top of S0, same data)
-- ============================================================================
\set ON_ERROR_STOP on
SET timezone = 'UTC';

-- Dedupe key from SIS #1 (UNIQUE deliveries(event_id, channel)).
-- Serves THREE queries: the ingest dedupe check / ON CONFLICT, and the status read
-- (leading column event_id).
CREATE UNIQUE INDEX deliveries_event_channel_uq ON deliveries (event_id, channel);

-- Retry scheduler: "which deliveries are due?"  PARTIAL index: only the ~1.5% of rows
-- that are still pending are indexed, so it stays tiny and cheap to maintain.
-- (SIS #1 proposed a full composite (status, next_attempt_at); see explain/index_size.sql for the comparison.)
CREATE INDEX deliveries_due_idx ON deliveries (next_attempt_at)
    WHERE status IN ('queued', 'retry');

-- Monitoring / reporting queries filter by time.
CREATE INDEX deliveries_accepted_at_idx ON deliveries (accepted_at);

-- Outbox relay: only the unpublished tail of the queue is indexed.
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

ANALYZE;
