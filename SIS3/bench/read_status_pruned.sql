-- STATUS API, partition-aware: the id carries its day (e.g. UUIDv7 / date-prefixed id), so the query can prune to ONE partition.
\set n random(1, :N)
\set ts_epoch :anchor - 604800 + (:n * 604800) / :N
SELECT status, attempts, updated_at FROM deliveries
 WHERE event_id = md5(CAST(:n AS text))::uuid
   AND accepted_at >= date_trunc('day', to_timestamp(:ts_epoch))
   AND accepted_at <  date_trunc('day', to_timestamp(:ts_epoch)) + interval '1 day';
