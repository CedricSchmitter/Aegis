-- A read notice remains the same delivery; retries must never republish it.
DROP INDEX notices_recipient_unread_dedup_idx;

-- The previous policy allowed another delivery after the earlier one was read. Preserve those historical rows,
-- but reserve the original key for the newest unread delivery (or newest delivery when all have been read).
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY recipient_id, dedup_key
               ORDER BY (read_at IS NULL) DESC, created_at DESC, id DESC
           ) AS occurrence
    FROM notices
)
UPDATE notices
SET dedup_key = 'historical:' || notices.id::text
FROM ranked
WHERE notices.id = ranked.id
  AND ranked.occurrence > 1;

CREATE UNIQUE INDEX notices_recipient_dedup_uidx ON notices (recipient_id, dedup_key);
