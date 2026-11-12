-- A price exception accepted by someone other than the person who entered the invoice.
ALTER TABLE vendor_invoice
    ADD COLUMN override_approved_by TEXT,
    ADD COLUMN override_reason      VARCHAR(200);
ALTER TABLE vendor_invoice
    ADD CONSTRAINT vendor_invoice_override_complete
        CHECK ((override_approved_by IS NULL) = (override_reason IS NULL)),
    ADD CONSTRAINT vendor_invoice_override_when_matched
        CHECK (override_approved_by IS NULL OR status = 'MATCHED');

-- Each vendor's payment terms, as announced by the vendors module. An invoice uses the row with the latest
-- effective_from on or before its date. vendor_id refers to the vendors module.
CREATE TABLE vendor_terms
(
    vendor_id        UUID          NOT NULL,
    effective_from   DATE          NOT NULL,
    net_days         INT           NOT NULL,
    discount_percent NUMERIC(7, 4),
    discount_days    INT,
    PRIMARY KEY (vendor_id, effective_from),
    CONSTRAINT vendor_terms_net_days_not_negative CHECK (net_days >= 0),
    CONSTRAINT vendor_terms_discount_complete CHECK ((discount_percent IS NULL) = (discount_days IS NULL)),
    CONSTRAINT vendor_terms_discount_valid CHECK (discount_percent IS NULL OR
                                                  (discount_percent > 0 AND discount_percent <= 100 AND
                                                   discount_days >= 0))
);

CREATE SEQUENCE voucher_number_seq START WITH 100001;
CREATE SEQUENCE payment_number_seq START WITH 100001;

-- The vouchers selected to be paid on one day, proposed by one person and approved by another.
CREATE TABLE payment_run
(
    id           UUID                     NOT NULL PRIMARY KEY,
    payment_date DATE                     NOT NULL,
    pay_through  DATE                     NOT NULL,
    proposed_by  TEXT                     NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    decided_by   TEXT,
    decided_on   DATE,
    version      INT                      NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by   TEXT                     NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by   TEXT                     NOT NULL,
    CONSTRAINT payment_run_status_known CHECK (status IN ('PROPOSED', 'APPROVED', 'CANCELLED')),
    CONSTRAINT payment_run_window_ordered CHECK (pay_through >= payment_date),
    CONSTRAINT payment_run_decided_when_not_proposed
        CHECK ((status = 'PROPOSED') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_on IS NULL)),
    -- Segregation of duties: a run is not approved by the person who proposed it.
    CONSTRAINT payment_run_approver_not_proposer CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)
);

-- A payment to one vendor, issued when its payment run was approved. Never changed afterwards.
CREATE TABLE payment
(
    id             UUID                     NOT NULL PRIMARY KEY,
    number         VARCHAR(10)              NOT NULL,
    payment_run_id UUID                     NOT NULL REFERENCES payment_run (id),
    vendor_id      UUID                     NOT NULL,
    paid_on        DATE                     NOT NULL,
    gross_amount   NUMERIC(19, 4)           NOT NULL,
    discount       NUMERIC(19, 4)           NOT NULL,
    net_amount     NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT payment_number_once UNIQUE (number),
    CONSTRAINT payment_amounts_add_up CHECK (net_amount = gross_amount - discount),
    CONSTRAINT payment_amounts_valid CHECK (gross_amount > 0 AND discount >= 0 AND net_amount >= 0)
);

CREATE INDEX payment_run_idx ON payment (payment_run_id);
CREATE INDEX payment_vendor_idx ON payment (vendor_id, paid_on);

-- A payable voucher: the amount owed on one matched invoice, approved for payment. invoice_id refers to
-- vendor_invoice; vendor_id and purchase_order_id refer to the vendors and purchasing modules.
CREATE TABLE voucher
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    number                VARCHAR(10)              NOT NULL,
    invoice_id            UUID                     NOT NULL REFERENCES vendor_invoice (id),
    vendor_invoice_number VARCHAR(30)              NOT NULL,
    vendor_id             UUID                     NOT NULL,
    purchase_order_id     UUID                     NOT NULL,
    invoice_date          DATE                     NOT NULL,
    amount                NUMERIC(19, 4)           NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    due_on                DATE                     NOT NULL,
    discount_percent      NUMERIC(7, 4),
    discount_good_through DATE,
    released_on           DATE                     NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    payment_run_id        UUID REFERENCES payment_run (id),
    payment_id            UUID REFERENCES payment (id),
    paid_on               DATE,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT voucher_number_once UNIQUE (number),
    -- An invoice is vouchered once.
    CONSTRAINT voucher_once_per_invoice UNIQUE (invoice_id),
    CONSTRAINT voucher_amount_positive CHECK (amount > 0),
    CONSTRAINT voucher_status_known CHECK (status IN ('RELEASED', 'SCHEDULED', 'PAID')),
    CONSTRAINT voucher_discount_complete CHECK ((discount_percent IS NULL) = (discount_good_through IS NULL)),
    CONSTRAINT voucher_run_when_scheduled CHECK ((status = 'RELEASED') = (payment_run_id IS NULL)),
    CONSTRAINT voucher_payment_when_paid
        CHECK ((status = 'PAID') = (payment_id IS NOT NULL) AND (payment_id IS NULL) = (paid_on IS NULL))
);

CREATE INDEX voucher_released_by_due_idx ON voucher (due_on) WHERE status = 'RELEASED';
CREATE INDEX voucher_vendor_idx ON voucher (vendor_id, due_on);
CREATE INDEX voucher_payment_idx ON voucher (payment_id);

-- The vouchers a payment run selected, with the discount worked out for its payment date.
CREATE TABLE payment_run_line
(
    payment_run_id        UUID           NOT NULL REFERENCES payment_run (id) ON DELETE CASCADE,
    position              INT            NOT NULL,
    voucher_id            UUID           NOT NULL REFERENCES voucher (id),
    voucher_number        VARCHAR(10)    NOT NULL,
    vendor_id             UUID           NOT NULL,
    vendor_invoice_number VARCHAR(30)    NOT NULL,
    amount                NUMERIC(19, 4) NOT NULL,
    discount              NUMERIC(19, 4) NOT NULL,
    currency              CHAR(3)        NOT NULL,
    PRIMARY KEY (payment_run_id, position),
    CONSTRAINT payment_run_line_voucher_once UNIQUE (payment_run_id, voucher_id),
    CONSTRAINT payment_run_line_discount_within_amount CHECK (discount BETWEEN 0 AND amount)
);
