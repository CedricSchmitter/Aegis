-- Issue #28: Web Push to the members' devices. The in-app inbox stays the record; these tables hold the subscribed
-- browsers, each member's push preferences, one row per pushed notice and device, and the server's VAPID key.
CREATE TABLE push_subscriptions (
    id                   UUID PRIMARY KEY,
    member_id            UUID          NOT NULL,
    endpoint             VARCHAR(1000) NOT NULL,
    p256dh               VARCHAR(120)  NOT NULL,
    auth                 VARCHAR(40)   NOT NULL,
    device_label         VARCHAR(80),
    created_at           TIMESTAMPTZ   NOT NULL,
    subscribed_at        TIMESTAMPTZ   NOT NULL,
    last_sent_at         TIMESTAMPTZ,
    consecutive_failures INTEGER       NOT NULL DEFAULT 0,
    expired_at           TIMESTAMPTZ,
    CONSTRAINT push_subscriptions_endpoint_uq UNIQUE (endpoint)
);

CREATE INDEX push_subscriptions_member_idx ON push_subscriptions (member_id);

CREATE TABLE push_preferences (
    member_id    UUID PRIMARY KEY,
    categories   VARCHAR(200) NOT NULL,
    quiet_start  TIME,
    quiet_end    TIME,
    show_details BOOLEAN      NOT NULL,
    version      BIGINT       NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT push_preferences_quiet_hours_ck CHECK ((quiet_start IS NULL) = (quiet_end IS NULL))
);

CREATE TABLE push_deliveries (
    notice_id       UUID        NOT NULL REFERENCES notices (id) ON DELETE CASCADE,
    subscription_id UUID        NOT NULL REFERENCES push_subscriptions (id) ON DELETE CASCADE,
    state           VARCHAR(20) NOT NULL CHECK (state IN ('SENT', 'FAILED', 'EXPIRED')),
    attempts        INTEGER     NOT NULL,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    status_code     INTEGER,
    PRIMARY KEY (subscription_id, notice_id)
);

CREATE INDEX push_deliveries_notice_idx ON push_deliveries (notice_id);

-- The server's VAPID key pair (RFC 8292), created on first use. Browsers subscribe with the public key; replacing
-- the pair invalidates every subscription, so it is kept with the database and restored with it.
CREATE TABLE push_server_keys (
    id          SMALLINT PRIMARY KEY CHECK (id = 1),
    public_key  VARCHAR(120) NOT NULL,
    private_key TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
