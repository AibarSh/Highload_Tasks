-- WORKER (SIS #1 step 5): "Success: save provider ACK". Job message carries delivery_id AND accepted_at.
\set id random(1, :N)
\set ts_epoch :anchor - 604800 + (:id * 604800) / :N
\set prov :id % 1000
UPDATE deliveries
   SET status = 'sent', attempts = attempts + 1, provider_id = 'prov-' || :prov, updated_at = now()
 WHERE delivery_id = :id AND accepted_at = to_timestamp(:ts_epoch);
