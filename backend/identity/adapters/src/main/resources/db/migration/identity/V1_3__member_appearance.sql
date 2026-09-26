-- Identity context: each member's palette and effects preference (issue #27, ADR-0019). Stored on the member like the
-- locale. Every existing member keeps the look they have today: Streetkid with effects on.
ALTER TABLE members ADD COLUMN palette VARCHAR(20) NOT NULL DEFAULT 'STREETKID';
ALTER TABLE members ADD COLUMN effects_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE members ADD CONSTRAINT members_palette_check CHECK (palette IN ('STREETKID', 'CORPO', 'NOMAD'));
