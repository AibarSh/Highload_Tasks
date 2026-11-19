-- STATUS API (SIS #1): GET /v1/notifications/{id}  -> {status, attempts, updated_at}.  Looked up by event_id only.
\set n random(1, :N)
SELECT status, attempts, updated_at FROM deliveries WHERE event_id = md5(CAST(:n AS text))::uuid;
