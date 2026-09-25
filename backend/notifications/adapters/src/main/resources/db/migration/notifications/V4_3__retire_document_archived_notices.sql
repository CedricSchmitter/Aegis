-- Issue #83 (F3): "archived and searchable" notices are no longer published; the upload already confirms saving and
-- the document shows its processing state. Stored rows are kept, but the inbox no longer lists them, so an unread one
-- could never be read and would stay pending for delivery. Mark those read; nothing else changes.
UPDATE notices
SET read_at = now()
WHERE kind = 'DOCUMENT_ARCHIVED'
  AND read_at IS NULL;
