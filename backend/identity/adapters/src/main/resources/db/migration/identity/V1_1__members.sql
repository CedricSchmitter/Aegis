-- Identity context: household members with individual logins.
CREATE TABLE members (
    id            UUID PRIMARY KEY,
    username      VARCHAR(40)  NOT NULL,
    display_name  VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    locale        VARCHAR(8)   NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT members_username_unique UNIQUE (username)
);
