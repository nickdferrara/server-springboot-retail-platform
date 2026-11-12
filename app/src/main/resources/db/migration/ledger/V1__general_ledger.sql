-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- The chart of accounts. An account string is company, natural account, location, and department.
CREATE TABLE account
(
    number     VARCHAR(15)              NOT NULL PRIMARY KEY,
    name       VARCHAR(100)             NOT NULL,
    type       VARCHAR(10)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL,
    CONSTRAINT account_number_format CHECK (number ~ '^[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT account_type_known CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);

-- Which account fills each role in the posting rules.
CREATE TABLE posting_account
(
    role           VARCHAR(40)              NOT NULL PRIMARY KEY,
    account_number VARCHAR(15)              NOT NULL REFERENCES account (number),
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL
);

-- Placeholder accounts, to be replaced when the company's chart of accounts is migrated.
INSERT INTO account (number, name, type, version, created_at, created_by, updated_at, updated_by)
VALUES ('01-1400-0000-00', 'Warehouse inventory', 'ASSET', 1, now(), 'system', now(), 'system'),
       ('01-2100-0000-00', 'Accrued payables (goods received, not invoiced)', 'LIABILITY', 1, now(), 'system', now(),
        'system');

INSERT INTO posting_account (role, account_number, version, created_at, created_by, updated_at, updated_by)
VALUES ('WAREHOUSE_INVENTORY', '01-1400-0000-00', 1, now(), 'system', now(), 'system'),
       ('ACCRUED_PAYABLES', '01-2100-0000-00', 1, now(), 'system', now(), 'system');

-- The fiscal calendar as data: business date to fiscal period is a lookup.
CREATE TABLE fiscal_period
(
    fiscal_year INT  NOT NULL,
    period      INT  NOT NULL,
    starts_on   DATE NOT NULL,
    ends_on     DATE NOT NULL,
    PRIMARY KEY (fiscal_year, period),
    CONSTRAINT fiscal_period_number_range CHECK (period BETWEEN 1 AND 12),
    CONSTRAINT fiscal_period_ends_after_start CHECK (ends_on >= starts_on),
    CONSTRAINT fiscal_period_starts_once UNIQUE (starts_on)
);

CREATE SEQUENCE journal_entry_number_seq START WITH 100001;

CREATE TABLE journal_entry
(
    id              UUID                     NOT NULL PRIMARY KEY,
    number          VARCHAR(10)              NOT NULL,
    journal         VARCHAR(20)              NOT NULL,
    business_date   DATE                     NOT NULL,
    fiscal_year     INT                      NOT NULL,
    fiscal_period   INT                      NOT NULL,
    source_module   VARCHAR(30)              NOT NULL,
    source_event_id UUID,
    source_document VARCHAR(60)              NOT NULL,
    description     VARCHAR(200)             NOT NULL,
    posted_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    posted_by       TEXT                     NOT NULL,
    CONSTRAINT journal_entry_number_unique UNIQUE (number),
    -- A business event is posted once, however often it is delivered.
    CONSTRAINT journal_entry_once_per_event UNIQUE (source_event_id),
    CONSTRAINT journal_entry_period_fk FOREIGN KEY (fiscal_year, fiscal_period)
        REFERENCES fiscal_period (fiscal_year, period),
    CONSTRAINT journal_entry_journal_known CHECK (journal IN
        ('SALES', 'INVENTORY', 'PURCHASES', 'CASH_RECEIPTS', 'CASH_DISBURSEMENTS', 'GENERAL', 'RECURRING',
         'REVERSING', 'CLOSING'))
);

CREATE INDEX journal_entry_business_date_idx ON journal_entry (business_date);

CREATE TABLE journal_entry_line
(
    journal_entry_id UUID           NOT NULL REFERENCES journal_entry (id),
    line_number      INT            NOT NULL,
    account_number   VARCHAR(15)    NOT NULL REFERENCES account (number),
    side             VARCHAR(6)     NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    PRIMARY KEY (journal_entry_id, line_number),
    CONSTRAINT journal_entry_line_side_known CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT journal_entry_line_amount_positive CHECK (amount > 0)
);

CREATE INDEX journal_entry_line_account_idx ON journal_entry_line (account_number);

-- Journal entries are append-only: a correction is a reversing entry and a new one.
CREATE FUNCTION forbid_journal_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE
    ON journal_entry
    FOR EACH ROW
EXECUTE FUNCTION forbid_journal_change();

CREATE TRIGGER journal_entry_line_append_only
    BEFORE UPDATE OR DELETE
    ON journal_entry_line
    FOR EACH ROW
EXECUTE FUNCTION forbid_journal_change();

-- Every entry must balance. Checked when the transaction commits, once all of an entry's lines are in. The
-- table is named with its schema because the check runs outside the migration's search path.
CREATE FUNCTION require_balanced_journal_entry() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    difference NUMERIC;
BEGIN
    SELECT coalesce(sum(CASE side WHEN 'DEBIT' THEN amount ELSE -amount END), 0)
    INTO difference
    FROM ledger.journal_entry_line
    WHERE journal_entry_id = NEW.journal_entry_id;
    IF difference <> 0 THEN
        RAISE EXCEPTION 'journal entry % does not balance: debits less credits is %', NEW.journal_entry_id, difference;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT
    ON journal_entry_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
EXECUTE FUNCTION require_balanced_journal_entry();
