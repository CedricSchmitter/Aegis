-- Identity context: whether a member is a person or the household's assistant account (issue #83, ADR-0017).
-- Every existing member stays a person; the assistant is designated per installation through configuration
-- (aegis.members.assistants), never by a username in a migration.
ALTER TABLE members ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'PERSON';
ALTER TABLE members ADD CONSTRAINT members_kind_check CHECK (kind IN ('PERSON', 'ASSISTANT'));
