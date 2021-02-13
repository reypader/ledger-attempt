-- LEDGER PROJECTION TABLES
CREATE TABLE IF NOT EXISTS ledger_account_info(
    account_id VARCHAR(255) NOT NULL,
    accounting_mode VARCHAR(10) NOT NULL,
    opened_on TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(account_id)
);

CREATE TABLE IF NOT EXISTS ledger_account_tags(
    id BIGSERIAL,
    account_id VARCHAR(255) NOT NULL,
    tag VARCHAR(255) NOT NULL,
    PRIMARY KEY(account_id, tag),
        CONSTRAINT fk_account_tag_account_info
          FOREIGN KEY(account_id)
          REFERENCES ledger_account_info(account_id)
          ON DELETE CASCADE
);

CREATE INDEX idx_account_tags_1 on ledger_account_tags (tag);

CREATE TABLE IF NOT EXISTS ledger_account_statement(
    id BIGSERIAL,
    account_id VARCHAR(255) NOT NULL,
    entry_id VARCHAR(255) NOT NULL,
    entry_code VARCHAR(255) NOT NULL,
    entry_type VARCHAR(255) NOT NULL,
    available_balance_change DECIMAL(18,2) NOT NULL,
    current_balance_change DECIMAL(18,2) NOT NULL,
    resulting_available_balance DECIMAL(18,2) NOT NULL,
    resulting_current_balance DECIMAL(18,2) NULL,
    authorized_on TIMESTAMPTZ NULL,
    posted_on TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(id),
        CONSTRAINT fk_account_statement_account_info
          FOREIGN KEY(account_id)
          REFERENCES ledger_account_info(account_id)
          ON DELETE CASCADE
);

CREATE INDEX idx_account_statement_1 on ledger_account_statement (entry_id, account_id);

CREATE TABLE IF NOT EXISTS ledger_account_overdraft_events(
    id BIGSERIAL,
    account_id VARCHAR(255) NOT NULL,
    entry_id VARCHAR(255) NOT NULL,
    entry_code VARCHAR(255) NOT NULL,
    overdraft_type VARCHAR(255) NOT NULL,
    happened_on TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(id),
        CONSTRAINT fk_account_overdraft_events_account_info
          FOREIGN KEY(account_id)
          REFERENCES ledger_account_info(account_id)
          ON DELETE CASCADE
);
CREATE INDEX idx_account_overdraft_events_1 on ledger_account_overdraft_events (account_id);
CREATE INDEX idx_account_overdraft_events_2 on ledger_account_overdraft_events (overdraft_type);

-- AKKA PERSISTENCE TABLES
CREATE TABLE IF NOT EXISTS event_journal(
  ordering BIGSERIAL,
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  deleted BOOLEAN DEFAULT FALSE NOT NULL,

  writer VARCHAR(255) NOT NULL,
  write_timestamp BIGINT,
  adapter_manifest VARCHAR(255),

  event_ser_id INTEGER NOT NULL,
  event_ser_manifest VARCHAR(255) NOT NULL,
  event_payload BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY(persistence_id, sequence_number)
);

CREATE UNIQUE INDEX event_journal_ordering_idx ON event_journal(ordering);

CREATE TABLE IF NOT EXISTS event_tag(
    event_id BIGINT,
    tag VARCHAR(256),
    PRIMARY KEY(event_id, tag),
    CONSTRAINT fk_event_journal
      FOREIGN KEY(event_id)
      REFERENCES event_journal(ordering)
      ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS snapshot (
  persistence_id VARCHAR(255) NOT NULL,
  sequence_number BIGINT NOT NULL,
  created BIGINT NOT NULL,

  snapshot_ser_id INTEGER NOT NULL,
  snapshot_ser_manifest VARCHAR(255) NOT NULL,
  snapshot_payload BYTEA NOT NULL,

  meta_ser_id INTEGER,
  meta_ser_manifest VARCHAR(255),
  meta_payload BYTEA,

  PRIMARY KEY(persistence_id, sequence_number)
);

-- AKKA PROJECTION TABLES

CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  current_offset VARCHAR(255) NOT NULL,
  manifest VARCHAR(4) NOT NULL,
  mergeable BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);

CREATE INDEX IF NOT EXISTS projection_name_index ON akka_projection_offset_store (projection_name); 