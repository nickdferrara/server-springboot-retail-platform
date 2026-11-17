CREATE SEQUENCE deduction_notice_number_seq START WITH 100001;

-- What a vendor is told was held back from a direct delivery invoice that was paid short, raised with the
-- short-pay. Accounting sends it by its own means; the sent columns record when, how, and to whom. vendor_id
-- and store_id refer to the vendors and locations modules; there are no foreign keys across schemas.
CREATE TABLE deduction_notice
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    number                VARCHAR(10)              NOT NULL,
    invoice_id            UUID                     NOT NULL REFERENCES direct_delivery_invoice (id),
    vendor_invoice_number VARCHAR(30)              NOT NULL,
    invoice_date          DATE                     NOT NULL,
    vendor_id             UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    ticket_number         VARCHAR(30)              NOT NULL,
    raised_on             DATE                     NOT NULL,
    reason                VARCHAR(200)             NOT NULL,
    amount                NUMERIC(19, 4)           NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    sent_on               DATE,
    sent_by               VARCHAR(20),
    sent_to               VARCHAR(100),
    sent_recorded_by      TEXT,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT deduction_notice_number_once UNIQUE (number),
    -- An invoice is paid short once, so it has one notice.
    CONSTRAINT deduction_notice_once_per_invoice UNIQUE (invoice_id),
    CONSTRAINT deduction_notice_amount_positive CHECK (amount > 0),
    CONSTRAINT deduction_notice_channel_known
        CHECK (sent_by IS NULL OR sent_by IN ('EMAIL', 'POST', 'IN_PERSON', 'OTHER')),
    CONSTRAINT deduction_notice_sending_complete
        CHECK ((sent_on IS NULL) = (sent_by IS NULL)
            AND (sent_on IS NULL) = (sent_to IS NULL)
            AND (sent_on IS NULL) = (sent_recorded_by IS NULL)),
    CONSTRAINT deduction_notice_sent_after_raised CHECK (sent_on IS NULL OR sent_on >= raised_on)
);

CREATE INDEX deduction_notice_vendor_idx ON deduction_notice (vendor_id, raised_on);
CREATE INDEX deduction_notice_unsent_idx ON deduction_notice (vendor_id, raised_on) WHERE sent_on IS NULL;

CREATE TABLE deduction_notice_line
(
    deduction_notice_id UUID           NOT NULL REFERENCES deduction_notice (id) ON DELETE CASCADE,
    position            INT            NOT NULL,
    item_id             UUID           NOT NULL,
    units_billed        INT            NOT NULL,
    units_received      INT            NOT NULL,
    unit_cost           NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (deduction_notice_id, position),
    CONSTRAINT deduction_notice_line_item_once UNIQUE (deduction_notice_id, item_id),
    CONSTRAINT deduction_notice_line_units_valid CHECK (units_received >= 0 AND units_billed > units_received),
    CONSTRAINT deduction_notice_line_cost_not_negative CHECK (unit_cost >= 0)
);
