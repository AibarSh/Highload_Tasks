-- DISPATCHER (SIS #1 step 2): "saves delivery + outbox in one DB txn". 90 % new events, 10 % Kafka replays (duplicates).
-- Dedupe = UNIQUE index + ON CONFLICT DO NOTHING (used by S1 and S2).
\set is_new random(1, 100)
\set rid random(1, 500000)
\set newk random(100000000, 2000000000)
\set oldk random(1, :N)
\if :is_new <= 90
  \set k :newk
  \set ts_epoch 0
\else
  \set k :oldk
  \set ts_epoch :anchor - 604800 + (:k * 604800) / :N
\endif
\set chmod :k % 5
BEGIN;
SELECT email_on, push_on FROM preferences WHERE recipient_id = :rid;
INSERT INTO notification_events (event_id, recipient_id, channel, payload_ref, accepted_at)
VALUES (md5(CAST(:k AS text))::uuid, :rid, CASE WHEN :chmod = 0 THEN 'email' ELSE 'push' END,
        's3://notif-payloads/' || md5(CAST(:k AS text)),
        CASE WHEN :ts_epoch = 0 THEN now() ELSE to_timestamp(:ts_epoch) END)
ON CONFLICT DO NOTHING;
WITH d AS (
  INSERT INTO deliveries (event_id, channel, status, attempts, next_attempt_at, accepted_at, updated_at)
  VALUES (md5(CAST(:k AS text))::uuid, CASE WHEN :chmod = 0 THEN 'email' ELSE 'push' END, 'queued', 0, now(),
          CASE WHEN :ts_epoch = 0 THEN now() ELSE to_timestamp(:ts_epoch) END, now())
  ON CONFLICT DO NOTHING
  RETURNING delivery_id, accepted_at)
INSERT INTO outbox (delivery_id, created_at) SELECT delivery_id, accepted_at FROM d;
COMMIT;
