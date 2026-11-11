-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- Goods received, not invoiced: what is owed to a vendor for one receipt until its invoice is matched.
-- receipt_id, purchase_order_id, vendor_id, and warehouse_id refer to the receiving, purchasing, vendors, and
-- locations modules; there are no foreign keys across schemas.
CREATE TABLE accrual
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    receipt_id            UUID                     NOT NULL,
    receipt_number        VARCHAR(10)              NOT NULL,
    purchase_order_id     UUID                     NOT NULL,
    purchase_order_number VARCHAR(10)              NOT NULL,
    vendor_id             UUID                     NOT NULL,
    warehouse_id          UUID                     NOT NULL,
    received_on           DATE                     NOT NULL,
    amount                NUMERIC(19, 4)           NOT NULL,
    open_amount           NUMERIC(19, 4)           NOT NULL,
    currency              CHAR(3)                  NOT NULL,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT accrual_once_per_receipt UNIQUE (receipt_id),
    CONSTRAINT accrual_open_within_amount CHECK (open_amount BETWEEN 0 AND amount)
);

CREATE INDEX accrual_open_by_vendor_idx ON accrual (vendor_id, received_on) WHERE open_amount > 0;
CREATE INDEX accrual_purchase_order_idx ON accrual (purchase_order_id);

CREATE TABLE accrual_line
(
    accrual_id                 UUID           NOT NULL REFERENCES accrual (id) ON DELETE CASCADE,
    position                   INT            NOT NULL,
    purchase_order_line_number INT            NOT NULL,
    item_id                    UUID           NOT NULL,
    cases_received             INT            NOT NULL,
    case_cost                  NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (accrual_id, position),
    CONSTRAINT accrual_line_cases_positive CHECK (cases_received >= 1),
    CONSTRAINT accrual_line_cost_not_negative CHECK (case_cost >= 0)
);
