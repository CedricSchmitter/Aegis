CREATE TABLE review_items (
    id UUID PRIMARY KEY,
    fingerprint VARCHAR(300) NOT NULL UNIQUE,
    producer VARCHAR(80) NOT NULL,
    source_kind VARCHAR(40) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    source_version BIGINT NOT NULL,
    category VARCHAR(50) NOT NULL,
    reason VARCHAR(80) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    title_key VARCHAR(100) NOT NULL,
    params_json TEXT NOT NULL DEFAULT '{}',
    link_type VARCHAR(30) NOT NULL,
    link_id VARCHAR(80) NOT NULL,
    actions_json TEXT NOT NULL DEFAULT '[]',
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    decided_by UUID,
    decided_at TIMESTAMPTZ
);
CREATE INDEX review_items_status_created_idx ON review_items(status, created_at DESC);
CREATE INDEX review_items_source_idx ON review_items(source_kind, source_id);
