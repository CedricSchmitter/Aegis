-- Automatic folder placement (issue #67, ADR-0005): where a document's placement came from, the placements a run
-- proposed but did not apply, and the ones a member rejected.

-- Provenance of the current folder placement. placed_by_member is sticky: once a member has placed or moved a
-- document (including back to Unsorted) no run files it automatically again. Existing placements were all made by
-- members or by a restore, so they start as manual member decisions.
ALTER TABLE document_classifications ADD COLUMN placement_origin VARCHAR(20)
    CHECK (placement_origin IN ('MANUAL', 'AUTOMATIC'));
ALTER TABLE document_classifications ADD COLUMN placement_confidence NUMERIC(4, 3)
    CHECK (placement_confidence IS NULL OR (placement_confidence >= 0 AND placement_confidence <= 1));
ALTER TABLE document_classifications ADD COLUMN placement_source_ref VARCHAR(200);
ALTER TABLE document_classifications ADD COLUMN placement_by UUID;
ALTER TABLE document_classifications ADD COLUMN placement_at TIMESTAMPTZ;
ALTER TABLE document_classifications ADD COLUMN placed_by_member BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE document_classifications
   SET placement_origin = 'MANUAL', placed_by_member = TRUE, placement_by = updated_by, placement_at = updated_at
 WHERE category_id IS NOT NULL;

-- At most one pending suggestion per document: the one of its latest finished run. target_key identifies the
-- target independently of spelling, so identical proposals of several documents group under it.
CREATE TABLE document_placement_suggestions (
    document_id       UUID PRIMARY KEY REFERENCES document_classifications (document_id) ON DELETE CASCADE,
    target_key        VARCHAR(400) NOT NULL,
    folder_id         UUID REFERENCES categories (id) ON DELETE CASCADE,
    anchor_id         UUID REFERENCES categories (id) ON DELETE CASCADE,
    new_path          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    generation        INTEGER      NOT NULL DEFAULT 0,
    confidence        NUMERIC(4, 3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    evidence          VARCHAR(250),
    run_ref           VARCHAR(200),
    model             VARCHAR(100),
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT document_placement_suggestions_target CHECK (
        (folder_id IS NOT NULL AND new_path = '[]'::jsonb) OR (folder_id IS NULL AND new_path <> '[]'::jsonb))
);
CREATE INDEX document_placement_suggestions_key_idx ON document_placement_suggestions (target_key);

-- Remembered rejections, including the folder a member took an automatic move back out of.
CREATE TABLE document_placement_rejections (
    document_id  UUID         NOT NULL REFERENCES document_classifications (document_id) ON DELETE CASCADE,
    target_key   VARCHAR(400) NOT NULL,
    rejected_by  UUID,
    rejected_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (document_id, target_key)
);
