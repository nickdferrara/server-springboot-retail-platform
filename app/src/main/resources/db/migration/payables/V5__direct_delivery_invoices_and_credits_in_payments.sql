-- A direct delivery accrual is relieved whole on the day the vendor's invoice for the ticket matches.
ALTER TABLE direct_delivery_accrual
    ADD COLUMN relieved_on DATE;
CREATE INDEX direct_delivery_accrual_open_idx ON direct_delivery_accrual (vendor_id, received_on)
    WHERE relieved_on IS NULL;

-- A vendor credit waits until a payment run deducts it from a payment to the vendor.
ALTER TABLE vendor_credit
    ADD COLUMN status         VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN payment_run_id UUID REFERENCES payment_run (id),
    ADD COLUMN payment_id     UUID REFERENCES payment (id),
    ADD COLUMN applied_on     DATE;
ALTER TABLE vendor_credit
    ADD CONSTRAINT vendor_credit_status_known CHECK (status IN ('OPEN', 'SCHEDULED', 'APPLIED')),
    ADD CONSTRAINT vendor_credit_run_when_scheduled CHECK ((status = 'OPEN') = (payment_run_id IS NULL)),
    ADD CONSTRAINT vendor_credit_payment_when_applied
        CHECK ((status = 'APPLIED') = (payment_id IS NOT NULL) AND (payment_id IS NULL) = (applied_on IS NULL));
CREATE INDEX vendor_credit_open_idx ON vendor_credit (vendor_id, credited_on) WHERE status = 'OPEN';

-- A voucher may now pay an invoice for a direct store delivery, which has no purchase order and is kept in
-- direct_delivery_invoice, so invoice_id no longer refers to vendor_invoice alone.
ALTER TABLE voucher
    ALTER COLUMN purchase_order_id DROP NOT NULL;
ALTER TABLE voucher
    DROP CONSTRAINT voucher_invoice_id_fkey;

-- A vendor's invoice for one direct store delivery ticket. vendor_id and store_id refer to the vendors and
-- locations modules; there are no foreign keys across schemas.
CREATE TABLE direct_delivery_invoice
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    vendor_id             UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    ticket_number         VARCHAR(30)              NOT NULL,
    vendor_invoice_number VARCHAR(30)              NOT NULL,
    invoice_date          DATE                     NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    entered_by            TEXT                     NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    matched_on            DATE,
    override_approved_by  TEXT,
    override_reason       VARCHAR(200),
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    -- One invoice per delivery ticket.
    CONSTRAINT direct_delivery_invoice_ticket_once UNIQUE (vendor_id, store_id, ticket_number),
    CONSTRAINT direct_delivery_invoice_number_once UNIQUE (vendor_id, vendor_invoice_number),
    CONSTRAINT direct_delivery_invoice_status_known CHECK (status IN ('MATCHED', 'EXCEPTION')),
    CONSTRAINT direct_delivery_invoice_matched_on_when_matched CHECK ((status = 'MATCHED') = (matched_on IS NOT NULL)),
    CONSTRAINT direct_delivery_invoice_override_complete
        CHECK ((override_approved_by IS NULL) = (override_reason IS NULL)),
    CONSTRAINT direct_delivery_invoice_override_when_matched
        CHECK (override_approved_by IS NULL OR status = 'MATCHED')
);

CREATE INDEX direct_delivery_invoice_exception_idx ON direct_delivery_invoice (id) WHERE status = 'EXCEPTION';

CREATE TABLE direct_delivery_invoice_line
(
    direct_delivery_invoice_id UUID           NOT NULL REFERENCES direct_delivery_invoice (id) ON DELETE CASCADE,
    position                   INT            NOT NULL,
    item_id                    UUID           NOT NULL,
    units                      INT            NOT NULL,
    unit_cost                  NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (direct_delivery_invoice_id, position),
    CONSTRAINT direct_delivery_invoice_line_item_once UNIQUE (direct_delivery_invoice_id, item_id),
    CONSTRAINT direct_delivery_invoice_line_units_positive CHECK (units >= 1),
    CONSTRAINT direct_delivery_invoice_line_cost_not_negative CHECK (unit_cost >= 0)
);

-- Why an invoice did not match, from its latest match attempt. item_id is null for a reason about the whole
-- ticket.
CREATE TABLE direct_delivery_invoice_exception
(
    direct_delivery_invoice_id UUID         NOT NULL REFERENCES direct_delivery_invoice (id) ON DELETE CASCADE,
    position                   INT          NOT NULL,
    item_id                    UUID,
    reason                     VARCHAR(30)  NOT NULL,
    detail                     VARCHAR(200) NOT NULL,
    PRIMARY KEY (direct_delivery_invoice_id, position)
);

-- The vendor credits a payment run deducts from what it pays their vendors.
CREATE TABLE payment_run_credit
(
    payment_run_id   UUID           NOT NULL REFERENCES payment_run (id) ON DELETE CASCADE,
    position         INT            NOT NULL,
    vendor_credit_id UUID           NOT NULL REFERENCES vendor_credit (id),
    vendor_id        UUID           NOT NULL,
    ticket_number    VARCHAR(30)    NOT NULL,
    amount           NUMERIC(19, 4) NOT NULL,
    currency         CHAR(3)        NOT NULL,
    PRIMARY KEY (payment_run_id, position),
    CONSTRAINT payment_run_credit_once UNIQUE (payment_run_id, vendor_credit_id),
    CONSTRAINT payment_run_credit_amount_positive CHECK (amount > 0)
);

-- A payment now pays what its vouchers owe, less discounts taken and vendor credits deducted.
ALTER TABLE payment
    ADD COLUMN credit NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE payment
    DROP CONSTRAINT payment_amounts_add_up,
    DROP CONSTRAINT payment_amounts_valid;
ALTER TABLE payment
    ADD CONSTRAINT payment_amounts_add_up CHECK (net_amount = gross_amount - discount - credit),
    ADD CONSTRAINT payment_amounts_valid
        CHECK (gross_amount > 0 AND discount >= 0 AND credit >= 0 AND net_amount >= 0);
