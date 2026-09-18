-- Documents context: folder tree, label vocabulary and document classification (ADR-0002).
-- Aegis owns these; Paperless keeps originals and full text and is never written here.

CREATE TABLE labels (
    id          UUID PRIMARY KEY,
    facet       VARCHAR(20) NOT NULL,
    builtin_key VARCHAR(60) UNIQUE,
    name        VARCHAR(80),
    status      VARCHAR(20) NOT NULL,
    merged_into UUID REFERENCES labels (id),
    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT labels_facet_check CHECK (facet IN ('DOCUMENT_TYPE', 'ORGANIZATION', 'TOPIC', 'TAG')),
    CONSTRAINT labels_status_check CHECK (status IN ('ACTIVE', 'RETIRED', 'MERGED')),
    CONSTRAINT labels_name_present CHECK (builtin_key IS NOT NULL OR name IS NOT NULL),
    CONSTRAINT labels_merge_consistent CHECK ((status = 'MERGED') = (merged_into IS NOT NULL)),
    CONSTRAINT labels_no_self_merge CHECK (merged_into IS NULL OR merged_into <> id)
);

CREATE TABLE label_aliases (
    label_id UUID        NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    position INTEGER     NOT NULL,
    alias    VARCHAR(80) NOT NULL,
    PRIMARY KEY (label_id, position)
);

-- Normalized names and aliases; unique per facet so that equal names identify exactly one entry.
CREATE TABLE label_keys (
    facet     VARCHAR(20) NOT NULL,
    match_key VARCHAR(80) NOT NULL,
    label_id  UUID        NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (facet, match_key)
);
CREATE INDEX label_keys_label_idx ON label_keys (label_id);

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    parent_id  UUID REFERENCES categories (id),
    name       VARCHAR(80) NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT categories_no_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX categories_parent_idx ON categories (parent_id);

-- Explicit folder mappings: what documents inside the folder inherit.
CREATE TABLE category_implied_labels (
    category_id UUID NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    label_id    UUID NOT NULL REFERENCES labels (id),
    PRIMARY KEY (category_id, label_id)
);

CREATE TABLE category_implied_subjects (
    category_id UUID        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL,
    member_id   UUID,
    CONSTRAINT category_implied_subjects_unique UNIQUE NULLS NOT DISTINCT (category_id, kind, member_id),
    CONSTRAINT category_implied_subjects_kind_check CHECK (kind IN ('MEMBER', 'HOUSE', 'HOUSEHOLD'))
);

-- Derived from the mappings above including inheritance (nearest folder wins for the document type).
-- Rebuilt by the application on every tree or mapping change; safe to recompute at any time.
CREATE TABLE category_label_effects (
    category_id UUID        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    label_id    UUID        NOT NULL REFERENCES labels (id),
    facet       VARCHAR(20) NOT NULL,
    PRIMARY KEY (category_id, label_id)
);
CREATE INDEX category_label_effects_label_idx ON category_label_effects (label_id);
CREATE INDEX category_label_effects_facet_idx ON category_label_effects (facet);

CREATE TABLE category_subject_effects (
    category_id UUID        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL,
    member_id   UUID,
    CONSTRAINT category_subject_effects_unique UNIQUE NULLS NOT DISTINCT (category_id, kind, member_id)
);

-- One folder placement per document; absent or null category means Unsorted.
CREATE TABLE document_classifications (
    document_id UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    category_id UUID REFERENCES categories (id),
    updated_by  UUID,
    updated_at  TIMESTAMPTZ,
    version     BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX document_classifications_category_idx ON document_classifications (category_id);

CREATE TABLE document_labels (
    document_id  UUID          NOT NULL REFERENCES document_classifications (document_id) ON DELETE CASCADE,
    label_id     UUID          NOT NULL REFERENCES labels (id),
    origin       VARCHAR(20)   NOT NULL,
    review_state VARCHAR(20)   NOT NULL,
    confidence   NUMERIC(4, 3),
    source_ref   VARCHAR(200),
    decided_by   UUID,
    decided_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (document_id, label_id),
    CONSTRAINT document_labels_origin_check CHECK (origin IN ('MANUAL', 'AUTOMATIC')),
    CONSTRAINT document_labels_state_check CHECK (review_state IN ('SUGGESTED', 'APPLIED', 'CONFIRMED',
        'REJECTED')),
    CONSTRAINT document_labels_manual_confirmed CHECK (origin <> 'MANUAL' OR review_state IN ('CONFIRMED',
        'REJECTED')),
    CONSTRAINT document_labels_confidence_range CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);
CREATE INDEX document_labels_label_idx ON document_labels (label_id, review_state);

-- Every document already captured starts Unsorted without labels.
INSERT INTO document_classifications (document_id, version)
SELECT id, 0 FROM documents;

CREATE INDEX document_subjects_kind_idx ON document_subjects (kind, member_id);

-- Effective values used by search and counting: own assignments plus what the folder passes on.
-- An own document type hides the folder type; multi-valued facets are unioned.
CREATE VIEW document_effective_labels AS
SELECT dl.document_id, dl.label_id, l.facet
  FROM document_labels dl
  JOIN labels l ON l.id = dl.label_id
 WHERE dl.review_state IN ('APPLIED', 'CONFIRMED')
UNION
SELECT dc.document_id, e.label_id, e.facet
  FROM document_classifications dc
  JOIN category_label_effects e ON e.category_id = dc.category_id
 WHERE e.facet <> 'DOCUMENT_TYPE'
    OR NOT EXISTS (SELECT 1
                     FROM document_labels o
                     JOIN labels ol ON ol.id = o.label_id
                    WHERE o.document_id = dc.document_id
                      AND o.review_state IN ('APPLIED', 'CONFIRMED')
                      AND ol.facet = 'DOCUMENT_TYPE');

CREATE VIEW document_effective_subjects AS
SELECT ds.document_id, ds.kind, ds.member_id
  FROM document_subjects ds
UNION
SELECT dc.document_id, e.kind, e.member_id
  FROM document_classifications dc
  JOIN category_subject_effects e ON e.category_id = dc.category_id;

-- Built-in vocabulary with fixed ids and language-independent keys. The UI translates the keys
-- (labels.builtin.<key>); the German and English names are stored as aliases so that manual entry,
-- autocomplete and automatic classification resolve them to the same entry.
CREATE TEMP TABLE builtin_label_seed (
    id          UUID,
    facet       VARCHAR(20),
    builtin_key VARCHAR(60),
    de          VARCHAR(80),
    en          VARCHAR(80)
) ON COMMIT DROP;

INSERT INTO builtin_label_seed VALUES
    ('11111111-0000-4000-8000-000000000001', 'DOCUMENT_TYPE', 'invoice', 'Rechnung', 'Invoice'),
    ('11111111-0000-4000-8000-000000000002', 'DOCUMENT_TYPE', 'contract', 'Vertrag', 'Contract'),
    ('11111111-0000-4000-8000-000000000003', 'DOCUMENT_TYPE', 'insurancePolicy', 'Versicherungspolice',
        'Insurance policy'),
    ('11111111-0000-4000-8000-000000000004', 'DOCUMENT_TYPE', 'accountStatement', 'Kontoauszug',
        'Account statement'),
    ('11111111-0000-4000-8000-000000000005', 'DOCUMENT_TYPE', 'officialNotice', 'Bescheid', 'Official notice'),
    ('11111111-0000-4000-8000-000000000006', 'DOCUMENT_TYPE', 'letter', 'Schreiben', 'Letter'),
    ('11111111-0000-4000-8000-000000000007', 'DOCUMENT_TYPE', 'receipt', 'Beleg', 'Receipt'),
    ('11111111-0000-4000-8000-000000000008', 'DOCUMENT_TYPE', 'paymentReminder', 'Mahnung', 'Payment reminder'),
    ('11111111-0000-4000-8000-000000000009', 'DOCUMENT_TYPE', 'certificate', 'Bescheinigung', 'Certificate'),
    ('11111111-0000-4000-8000-000000000010', 'DOCUMENT_TYPE', 'warranty', 'Garantie', 'Warranty'),
    ('11111111-0000-4000-8000-000000000011', 'DOCUMENT_TYPE', 'manual', 'Anleitung', 'Manual'),
    ('11111111-0000-4000-8000-000000000012', 'DOCUMENT_TYPE', 'payslip', 'Gehaltsabrechnung', 'Payslip'),
    ('11111111-0000-4000-8000-000000000013', 'DOCUMENT_TYPE', 'taxDocument', 'Steuerunterlage', 'Tax document'),
    ('11111111-0000-4000-8000-000000000014', 'DOCUMENT_TYPE', 'cancellation', 'Kündigung', 'Cancellation'),
    ('11111111-0000-4000-8000-000000000015', 'DOCUMENT_TYPE', 'medicalDocument', 'Arztbericht',
        'Medical document'),
    ('22222222-0000-4000-8000-000000000001', 'TOPIC', 'internet', 'Internet', 'Internet'),
    ('22222222-0000-4000-8000-000000000002', 'TOPIC', 'phone', 'Telefon', 'Phone'),
    ('22222222-0000-4000-8000-000000000003', 'TOPIC', 'electricity', 'Strom', 'Electricity'),
    ('22222222-0000-4000-8000-000000000004', 'TOPIC', 'gas', 'Gas', 'Gas'),
    ('22222222-0000-4000-8000-000000000005', 'TOPIC', 'heating', 'Heizung', 'Heating'),
    ('22222222-0000-4000-8000-000000000006', 'TOPIC', 'water', 'Wasser', 'Water'),
    ('22222222-0000-4000-8000-000000000007', 'TOPIC', 'waste', 'Abfall', 'Waste'),
    ('22222222-0000-4000-8000-000000000008', 'TOPIC', 'insurance', 'Versicherung', 'Insurance'),
    ('22222222-0000-4000-8000-000000000009', 'TOPIC', 'taxes', 'Steuern', 'Taxes'),
    ('22222222-0000-4000-8000-000000000010', 'TOPIC', 'banking', 'Bank', 'Banking'),
    ('22222222-0000-4000-8000-000000000011', 'TOPIC', 'health', 'Gesundheit', 'Health'),
    ('22222222-0000-4000-8000-000000000012', 'TOPIC', 'car', 'Auto', 'Car'),
    ('22222222-0000-4000-8000-000000000013', 'TOPIC', 'housing', 'Wohnen', 'Housing'),
    ('22222222-0000-4000-8000-000000000014', 'TOPIC', 'work', 'Arbeit', 'Work'),
    ('22222222-0000-4000-8000-000000000015', 'TOPIC', 'purchases', 'Anschaffungen', 'Purchases'),
    ('22222222-0000-4000-8000-000000000016', 'TOPIC', 'broadcastingFee', 'Rundfunkbeitrag', 'Broadcasting fee'),
    ('22222222-0000-4000-8000-000000000017', 'TOPIC', 'pension', 'Vorsorge', 'Pension'),
    ('22222222-0000-4000-8000-000000000018', 'TOPIC', 'travel', 'Reisen', 'Travel'),
    ('22222222-0000-4000-8000-000000000019', 'TOPIC', 'education', 'Bildung', 'Education'),
    ('22222222-0000-4000-8000-000000000020', 'TOPIC', 'pets', 'Haustiere', 'Pets');

INSERT INTO labels (id, facet, builtin_key, name, status, created_at, updated_at, version)
SELECT id, facet, builtin_key, NULL, 'ACTIVE', now(), now(), 0 FROM builtin_label_seed;

INSERT INTO label_aliases (label_id, position, alias)
SELECT id, 0, de FROM builtin_label_seed
UNION ALL
SELECT id, 1, en FROM builtin_label_seed WHERE lower(en) <> lower(de);

INSERT INTO label_keys (facet, match_key, label_id)
SELECT DISTINCT l.facet, lower(a.alias), a.label_id
  FROM label_aliases a
  JOIN labels l ON l.id = a.label_id;
