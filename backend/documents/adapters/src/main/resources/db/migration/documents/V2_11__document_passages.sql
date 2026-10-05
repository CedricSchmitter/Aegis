-- Derived data only: originals and confirmed household facts remain authoritative.
CREATE TABLE document_passage_control (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    build_epoch bigint NOT NULL DEFAULT 1,
    data_epoch bigint NOT NULL DEFAULT 1
);
INSERT INTO document_passage_control (singleton) VALUES (true);

CREATE TABLE document_passage_index (
    document_id uuid PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    archive_id integer NOT NULL,
    original_checksum varchar(64) NOT NULL,
    generation bigint NOT NULL DEFAULT 1,
    build_epoch bigint NOT NULL,
    state varchar(30) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('CURRENT','PENDING','FAILED','NO_TEXT','SOURCE_UNAVAILABLE')),
    active_revision varchar(64),
    source_kind varchar(30),
    verified_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease uuid,
    lease_until timestamptz,
    failure_code varchar(100)
);
CREATE INDEX document_passage_due ON document_passage_index (next_attempt_at, document_id);

CREATE TABLE document_text_revisions (
    document_id uuid NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    revision varchar(64) NOT NULL,
    source_fingerprint varchar(64) NOT NULL,
    source_kind varchar(30) NOT NULL,
    extraction_version varchar(30) NOT NULL,
    chunk_version varchar(30) NOT NULL,
    original_text text NOT NULL,
    paged boolean NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (document_id, revision)
);
CREATE TABLE document_passages (
    document_id uuid NOT NULL,
    revision varchar(64) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    start_position integer NOT NULL CHECK (start_position >= 0),
    end_position integer NOT NULL CHECK (end_position > start_position),
    start_page integer,
    end_page integer,
    original_text text NOT NULL CHECK (length(original_text) <= 2000),
    normalized_text text NOT NULL,
    identifiers text[] NOT NULL,
    german tsvector GENERATED ALWAYS AS (to_tsvector('german'::regconfig, original_text)) STORED,
    english tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, original_text)) STORED,
    literal tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, normalized_text)) STORED,
    PRIMARY KEY (document_id, revision, ordinal),
    FOREIGN KEY (document_id, revision) REFERENCES document_text_revisions(document_id, revision) ON DELETE CASCADE,
    CHECK ((start_page IS NULL AND end_page IS NULL) OR (start_page > 0 AND end_page >= start_page))
);
CREATE INDEX document_passages_german ON document_passages USING gin (german);
CREATE INDEX document_passages_english ON document_passages USING gin (english);
CREATE INDEX document_passages_literal ON document_passages USING gin (literal);
CREATE INDEX document_passages_identifiers ON document_passages USING gin (identifiers);
