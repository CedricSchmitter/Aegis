-- Conversations with the household assistant (issue #25, ADR-0025). A conversation belongs to the member who started
-- it. Each question references the run answering it; the answer is stored with the question once the run applied it.
-- run_id is no foreign key, so deleting a conversation leaves its runs as content-free audit data.

CREATE TABLE chat_conversations (
    id          uuid         PRIMARY KEY,
    owner       uuid         NOT NULL,
    title       varchar(100) NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX chat_conversations_owner ON chat_conversations (owner, updated_at DESC);

CREATE TABLE chat_turns (
    id              uuid          PRIMARY KEY,
    conversation_id uuid          NOT NULL REFERENCES chat_conversations (id) ON DELETE CASCADE,
    seq             integer       NOT NULL CHECK (seq > 0),
    question        varchar(2000) NOT NULL,
    locale          varchar(2)    NOT NULL,
    asked_at        timestamptz   NOT NULL,
    run_id          uuid          NOT NULL,
    answer          jsonb,
    answered_at     timestamptz,
    UNIQUE (conversation_id, seq)
);
