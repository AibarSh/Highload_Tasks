-- ============================================================================
-- S2  S1's indexes + DAILY RANGE PARTITIONING (SIS #1: "partition deliveries by date, 7-day retention")
--     We partition the three big append-heavy tables on a timestamp the producer fixes ONCE per event.
--     Design consequences (all deliberate, all explained in REPORT.md):
--       * every PRIMARY KEY / UNIQUE index must contain the partition key, so
--           deliveries   PK (delivery_id, accepted_at)    UNIQUE (event_id, channel, accepted_at)
--         The dedupe key therefore only works if the same event always carries the SAME accepted_at.
--         => accepted_at is assigned by the ingress API when the event is accepted and travels with it
--            through Kafka; it is NOT now() at insert time.
--       * workers carry accepted_at in their job message so UPDATEs can prune to a single partition.
-- ============================================================================
\set ON_ERROR_STOP on
SET timezone = 'UTC';

CREATE TABLE preferences (
    recipient_id bigint  PRIMARY KEY,
    email_on     boolean NOT NULL,
    push_on      boolean NOT NULL
);

CREATE TABLE notification_events (
    event_id     uuid        NOT NULL,
    recipient_id bigint      NOT NULL,
    channel      text        NOT NULL,
    payload_ref  text        NOT NULL,
    accepted_at  timestamptz NOT NULL,
    PRIMARY KEY (event_id, accepted_at)
) PARTITION BY RANGE (accepted_at);

CREATE SEQUENCE deliveries_delivery_id_seq;
CREATE TABLE deliveries (
    delivery_id     bigint      NOT NULL DEFAULT nextval('deliveries_delivery_id_seq'),
    event_id        uuid        NOT NULL,
    channel         text        NOT NULL,
    status          text        NOT NULL,
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    provider_id     text,
    accepted_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (delivery_id, accepted_at)
) PARTITION BY RANGE (accepted_at);

CREATE SEQUENCE outbox_id_seq;
CREATE TABLE outbox (
    id           bigint      NOT NULL DEFAULT nextval('outbox_id_seq'),
    delivery_id  bigint      NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Same indexes as S1 (created on the parent => automatically created on every partition)
CREATE UNIQUE INDEX deliveries_event_channel_uq ON deliveries (event_id, channel, accepted_at);
CREATE INDEX deliveries_due_idx         ON deliveries (next_attempt_at) WHERE status IN ('queued', 'retry');
CREATE INDEX deliveries_accepted_at_idx ON deliveries (accepted_at);
CREATE INDEX outbox_unpublished_idx     ON outbox (id) WHERE published_at IS NULL;

-- Helper that (pre-)creates one partition per UTC day. In production a cron job / pg_partman
-- calls this ahead of time (e.g. creates tomorrow's partition today) and drops expired ones.
CREATE OR REPLACE FUNCTION create_day_partitions(parent regclass, first_day date, last_day date)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE d date;
BEGIN
  FOR d IN SELECT generate_series(first_day, last_day, interval '1 day')::date LOOP
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %s FOR VALUES FROM (%L) TO (%L)',
                   parent::text || '_p' || to_char(d, 'YYYYMMDD'), parent,
                   (d::timestamp AT TIME ZONE 'UTC'), ((d + 1)::timestamp AT TIME ZONE 'UTC'));
  END LOOP;
END $$;

-- 7 days of history + today + tomorrow
SELECT create_day_partitions('notification_events', (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date + 1);
SELECT create_day_partitions('deliveries',          (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date + 1);
SELECT create_day_partitions('outbox',              (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date - 7, (to_timestamp(:anchor) AT TIME ZONE 'UTC')::date + 1);

CREATE TABLE repl_probe (id bigserial PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT clock_timestamp());
