-- 7-day retention on an UNPARTITIONED schema: DELETE the oldest day, then VACUUM to make the space reusable.
\set ON_ERROR_STOP on
\pset pager off
\timing on
SET timezone = 'UTC';
SELECT to_timestamp(:anchor) - interval '6 days' AS cutoff \gset
SELECT pg_current_wal_lsn() AS lsn0 \gset
\echo === DELETE rows older than :cutoff
\echo >>> DELETE notification_events
DELETE FROM notification_events WHERE accepted_at < :'cutoff';
\echo >>> DELETE deliveries
DELETE FROM deliveries          WHERE accepted_at < :'cutoff';
\echo >>> DELETE outbox
DELETE FROM outbox              WHERE created_at  < :'cutoff';
SELECT pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), :'lsn0')) AS wal_written_by_delete,
       pg_wal_lsn_diff(pg_current_wal_lsn(), :'lsn0') AS wal_bytes;
SELECT relname, n_dead_tup FROM pg_stat_user_tables WHERE relname IN ('notification_events','deliveries','outbox') ORDER BY relname;
\echo === VACUUM (reclaim dead rows)
\echo >>> VACUUM notification_events
VACUUM notification_events;
\echo >>> VACUUM deliveries
VACUUM deliveries;
\echo >>> VACUUM outbox
VACUUM outbox;
