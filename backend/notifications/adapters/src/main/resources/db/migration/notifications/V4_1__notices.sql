-- Notifications context: persisted in-app inbox with per-member read state.
CREATE TABLE notices (
    id           UUID PRIMARY KEY,
    recipient_id UUID         NOT NULL,
    kind         VARCHAR(40)  NOT NULL,
    dedup_key    VARCHAR(200) NOT NULL,
    params_json  TEXT         NOT NULL DEFAULT '{}',
    link_type    VARCHAR(20),
    link_id      VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL,
    read_at      TIMESTAMPTZ
);

CREATE INDEX notices_recipient_created_idx ON notices (recipient_id, created_at DESC);
CREATE INDEX notices_recipient_unread_dedup_idx ON notices (recipient_id, dedup_key) WHERE read_at IS NULL;
