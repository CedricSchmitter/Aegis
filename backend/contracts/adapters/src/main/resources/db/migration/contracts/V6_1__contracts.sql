-- Contracts context (issue #22): contract facts with provenance, validity period and review state.
-- Facts are append-only: a correction inserts a new row and marks the corrected one superseded, so the value a
-- member decided on stays readable. Source documents are referenced by their stable Aegis id without a foreign
-- key, because the documents context owns that table and is reached only through its application contract.
CREATE TABLE contracts (
    id         UUID PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(10)  NOT NULL,
    created_by UUID         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT contracts_status_check CHECK (status IN ('ACTIVE', 'CANCELLED', 'ENDED'))
);

CREATE TABLE contract_facts (
    id                 UUID          PRIMARY KEY,
    contract_id        UUID          NOT NULL REFERENCES contracts (id) ON DELETE CASCADE,
    fact_type          VARCHAR(30)   NOT NULL,
    value_kind         VARCHAR(10)   NOT NULL,
    value_text         VARCHAR(500),
    value_date         DATE,
    value_amount       NUMERIC(19, 4),
    value_currency     VARCHAR(3),
    value_period       VARCHAR(30),
    value_flag         BOOLEAN,
    valid_from         DATE,
    valid_until        DATE,
    review_state       VARCHAR(12)   NOT NULL,
    origin             VARCHAR(10)   NOT NULL,
    confidence         DOUBLE PRECISION,
    source_document_id UUID,
    source_page        INTEGER,
    source_quote       VARCHAR(500),
    run_ref            VARCHAR(100),
    observed_by        UUID,
    observed_at        TIMESTAMPTZ   NOT NULL,
    decided_by         UUID,
    decided_at         TIMESTAMPTZ,
    superseded_by      UUID,
    corrects           UUID,
    note               VARCHAR(1000) NOT NULL DEFAULT '',
    CONSTRAINT contract_facts_kind_check CHECK (value_kind IN ('TEXT', 'DATE', 'MONEY', 'PERIOD', 'FLAG')),
    CONSTRAINT contract_facts_state_check
        CHECK (review_state IN ('EXTRACTED', 'CONFIRMED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT contract_facts_origin_check CHECK (origin IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT contract_facts_confidence_check
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- A page number is only ever stored when the source actually states it, and never without its document.
    CONSTRAINT contract_facts_page_check CHECK (source_page IS NULL OR source_page >= 1),
    CONSTRAINT contract_facts_source_check
        CHECK (source_document_id IS NOT NULL OR (source_page IS NULL AND source_quote IS NULL)),
    CONSTRAINT contract_facts_validity_check
        CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until >= valid_from),
    -- An extracted fact always names the document it was read in.
    CONSTRAINT contract_facts_extraction_source_check
        CHECK (origin = 'MANUAL' OR source_document_id IS NOT NULL)
);

CREATE INDEX contract_facts_contract_idx ON contract_facts (contract_id);
CREATE INDEX contract_facts_document_idx ON contract_facts (source_document_id);
CREATE INDEX contract_facts_review_idx ON contract_facts (review_state, fact_type);
