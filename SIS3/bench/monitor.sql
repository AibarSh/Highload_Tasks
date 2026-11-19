-- MONITORING (SIS #1: "alert on errors, retries"): delivery state counts for a 1-hour window.
-- The window is FIXED (the hour before the reference time) so every benchmark step does identical work;
-- in production it would be "now() - interval '15 minutes'".
SELECT status, count(*) FROM deliveries
 WHERE accepted_at >= to_timestamp(:anchor) - interval '1 hour' AND accepted_at < to_timestamp(:anchor)
 GROUP BY status;
