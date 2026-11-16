-- A further bill from a vendor for a delivery ticket whose invoice is already payable. Nothing is matched or
-- accrued for it; accounting rejects it or accepts it for payment, once. vendor_id and store_id refer to the
-- vendors and locations modules; there are no foreign keys across schemas.
CREATE TABLE direct_delivery_late_bill
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    vendor_id             UUID                     NOT NULL,
    store_id              UUID                     NOT NULL,
    ticket_number         VARCHAR(30)              NOT NULL,
    vendor_invoice_number VARCHAR(30)              NOT NULL,
    bill_date             DATE                     NOT NULL,
    amount                NUMERIC(19, 4)           NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    recorded_by           TEXT                     NOT NULL,
    recorded_on           DATE                     NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    settled_on            DATE,
    settled_by            TEXT,
    settlement_reason     VARCHAR(200),
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT direct_delivery_late_bill_number_once UNIQUE (vendor_id, vendor_invoice_number),
    CONSTRAINT direct_delivery_late_bill_amount_positive CHECK (amount > 0),
    CONSTRAINT direct_delivery_late_bill_status_known CHECK (status IN ('OPEN', 'PAID', 'REJECTED')),
    CONSTRAINT direct_delivery_late_bill_settled_when_not_open
        CHECK ((status = 'OPEN') = (settled_on IS NULL)
            AND (settled_on IS NULL) = (settled_by IS NULL)
            AND (settled_on IS NULL) = (settlement_reason IS NULL))
);

CREATE INDEX direct_delivery_late_bill_vendor_idx ON direct_delivery_late_bill (vendor_id, recorded_on);
CREATE INDEX direct_delivery_late_bill_open_idx
    ON direct_delivery_late_bill (vendor_id, recorded_on) WHERE status = 'OPEN';
