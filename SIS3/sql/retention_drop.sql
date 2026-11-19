-- 7-day retention on the PARTITIONED schema: detach + drop the oldest day's partitions (metadata operation).
\set ON_ERROR_STOP on
\pset pager off
\timing on
SET timezone = 'UTC';
SELECT 'notification_events_p' || to_char((to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, 'YYYYMMDD') AS ev_p,
       'deliveries_p'          || to_char((to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, 'YYYYMMDD') AS dl_p,
       'outbox_p'              || to_char((to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, 'YYYYMMDD') AS ob_p \gset
SELECT pg_current_wal_lsn() AS lsn0 \gset
\echo === rows in the partitions about to be dropped
SELECT count(*) AS deliveries_rows FROM :dl_p;
\echo === DETACH + DROP :dl_p, :ev_p, :ob_p
\echo >>> DETACH notification_events partition
ALTER TABLE notification_events DETACH PARTITION :ev_p;
\echo >>> DETACH deliveries partition
ALTER TABLE deliveries          DETACH PARTITION :dl_p;
\echo >>> DETACH outbox partition
ALTER TABLE outbox              DETACH PARTITION :ob_p;
\echo >>> DROP the three detached partitions
DROP TABLE :ev_p, :dl_p, :ob_p;
SELECT pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), :'lsn0')) AS wal_written_by_drop,
       pg_wal_lsn_diff(pg_current_wal_lsn(), :'lsn0') AS wal_bytes;
