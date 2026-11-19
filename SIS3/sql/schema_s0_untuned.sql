-- ============================================================================
-- S0  UNTUNED BASELINE  ("what you get from a first draft / ORM defaults")
--     Data model from SIS #1, primary keys only. No secondary indexes,
--     no partitioning. This is the BEFORE state of the benchmark.
--     (Foreign keys are omitted in ALL variants: FKs and time partitioning do
--      not combine well, and we want the variants to differ only in what we tune.
--      Integrity is enforced by the single dispatcher transaction + UNIQUE key.)
-- ============================================================================
\set ON_ERROR_STOP on
SET timezone = 'UTC';

CREATE TABLE preferences (
    recipient_id bigint  PRIMARY KEY,
    email_on     boolean NOT NULL,
    push_on      boolean NOT NULL
);

CREATE TABLE notification_events (
    event_id     uuid        PRIMARY KEY,
    recipient_id bigint      NOT NULL,
    channel      text        NOT NULL,
    payload_ref  text        NOT NULL,
    accepted_at  timestamptz NOT NULL
);

CREATE SEQUENCE deliveries_delivery_id_seq;
CREATE TABLE deliveries (
    delivery_id     bigint      PRIMARY KEY DEFAULT nextval('deliveries_delivery_id_seq'),
    event_id        uuid        NOT NULL,
    channel         text        NOT NULL,
    status          text        NOT NULL,           -- queued | retry | sent | failed
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    provider_id     text,
    accepted_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE SEQUENCE outbox_id_seq;
CREATE TABLE outbox (
    id           bigint      PRIMARY KEY DEFAULT nextval('outbox_id_seq'),
    delivery_id  bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz                         -- NULL = not yet relayed to Kafka
);

-- used only by the replication-visibility test
CREATE TABLE repl_probe (id bigserial PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT clock_timestamp());
