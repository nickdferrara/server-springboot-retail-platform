-- A correction to a store's receipt can call for a further credit on the same delivery, so a delivery may have
-- one credit per receipt: the delivery's own and each correction's.
ALTER TABLE vendor_credit
    DROP CONSTRAINT vendor_credit_once_per_delivery;
ALTER TABLE vendor_credit
    ADD CONSTRAINT vendor_credit_once_per_receipt UNIQUE (delivery_id, receipt_number);

-- An amount owed to a vendor that a late correction to a store's receipt brought to light. It is accrued, but no
-- invoice will match it and no payment run will pay it: accounting settles it with the vendor.
CREATE TABLE direct_delivery_adjustment
(
    id             UUID                     NOT NULL PRIMARY KEY,
    delivery_id    UUID                     NOT NULL,
    receipt_number VARCHAR(10)              NOT NULL,
    vendor_id      UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    ticket_number  VARCHAR(30)              NOT NULL,
    recorded_on    DATE                     NOT NULL,
    kind           VARCHAR(40)              NOT NULL,
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT direct_delivery_adjustment_kind_known
        CHECK (kind IN ('RECEIVED_AFTER_INVOICE', 'RETURN_REVERSED_AFTER_CREDIT_USED')),
    CONSTRAINT direct_delivery_adjustment_once UNIQUE (delivery_id, receipt_number, kind),
    CONSTRAINT direct_delivery_adjustment_amount_positive CHECK (amount > 0)
);

CREATE INDEX direct_delivery_adjustment_vendor_idx ON direct_delivery_adjustment (vendor_id, recorded_on);
