-- OUTBOX RELAY (SIS #1 step 3): claim a batch of unpublished rows (many relays may run, hence SKIP LOCKED).
WITH c AS (
  SELECT id, created_at FROM outbox
   WHERE published_at IS NULL
   ORDER BY id LIMIT 50
   FOR UPDATE SKIP LOCKED)
UPDATE outbox o SET published_at = now()
  FROM c WHERE o.id = c.id AND o.created_at = c.created_at;
