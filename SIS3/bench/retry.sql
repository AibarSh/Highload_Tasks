-- RETRY SCHEDULER (SIS #1 step 6): pick deliveries whose backoff has expired and reschedule them.
WITH c AS (
  SELECT delivery_id, accepted_at FROM deliveries
   WHERE status IN ('queued', 'retry') AND next_attempt_at <= now()
   ORDER BY next_attempt_at LIMIT 50
   FOR UPDATE SKIP LOCKED)
UPDATE deliveries d
   SET attempts = d.attempts + 1, status = 'retry',
       next_attempt_at = now() + interval '5 seconds', updated_at = now()
  FROM c WHERE d.delivery_id = c.delivery_id AND d.accepted_at = c.accepted_at;
