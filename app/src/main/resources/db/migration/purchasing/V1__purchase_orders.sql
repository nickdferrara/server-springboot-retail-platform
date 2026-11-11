-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- What each vendor supplies and at what cost per case, learned from the catalog. vendor_id and item_id refer to
-- the vendors and catalog modules; there are no foreign keys across schemas.
CREATE TABLE vendor_item_cost
(
    vendor_id  UUID                     NOT NULL,
    item_id    UUID                     NOT NULL,
    vendor_sku VARCHAR(30)              NOT NULL,
    case_pack  INT                      NOT NULL,
    amount     NUMERIC(19, 4)           NOT NULL,
    currency   CHAR(3)                  NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (vendor_id, item_id),
    CONSTRAINT vendor_item_cost_case_pack_positive CHECK (case_pack >= 1),
    CONSTRAINT vendor_item_cost_not_negative CHECK (amount >= 0)
);

-- The order total above which a purchase order needs a second person's approval. Rows are kept as history: the
-- threshold in effect is the row with the latest effective date that has arrived.
CREATE TABLE approval_threshold
(
    id             UUID                     NOT NULL PRIMARY KEY,
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    effective_from DATE                     NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT approval_threshold_once_per_day UNIQUE (effective_from),
    CONSTRAINT approval_threshold_not_negative CHECK (amount >= 0)
);

CREATE SEQUENCE purchase_order_number_seq START WITH 100001;

-- vendor_id and warehouse_id refer to the vendors and locations modules.
CREATE TABLE purchase_order
(
    id            UUID                     NOT NULL PRIMARY KEY,
    number        VARCHAR(10)              NOT NULL,
    vendor_id     UUID                     NOT NULL,
    warehouse_id  UUID                     NOT NULL,
    buyer         TEXT                     NOT NULL,
    expected_on   DATE                     NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    approved_by   TEXT,
    approved_on   DATE,
    sent_on       DATE,
    cancelled_on  DATE,
    cancel_reason VARCHAR(200),
    version       INT                      NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by    TEXT                     NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by    TEXT                     NOT NULL,
    CONSTRAINT purchase_order_number_unique UNIQUE (number),
    CONSTRAINT purchase_order_number_format CHECK (number ~ '^[0-9]{1,10}$'),
    CONSTRAINT purchase_order_status_known CHECK (status IN ('DRAFT', 'APPROVED', 'SENT', 'CANCELLED')),
    CONSTRAINT purchase_order_approval_complete CHECK ((approved_by IS NULL) = (approved_on IS NULL)),
    CONSTRAINT purchase_order_sent_after_approval CHECK (sent_on IS NULL OR approved_on IS NOT NULL),
    CONSTRAINT purchase_order_cancellation_complete CHECK ((cancelled_on IS NULL) = (cancel_reason IS NULL))
);

CREATE INDEX purchase_order_vendor_idx ON purchase_order (vendor_id);

CREATE TABLE purchase_order_line
(
    purchase_order_id UUID           NOT NULL REFERENCES purchase_order (id) ON DELETE CASCADE,
    position          INT            NOT NULL,
    item_id           UUID           NOT NULL,
    vendor_sku        VARCHAR(30)    NOT NULL,
    case_pack         INT            NOT NULL,
    cases             INT            NOT NULL,
    case_cost         NUMERIC(19, 4) NOT NULL,
    currency          CHAR(3)        NOT NULL,
    PRIMARY KEY (purchase_order_id, position),
    CONSTRAINT purchase_order_line_item_once UNIQUE (purchase_order_id, item_id),
    CONSTRAINT purchase_order_line_case_pack_positive CHECK (case_pack >= 1),
    CONSTRAINT purchase_order_line_cases_positive CHECK (cases >= 1),
    CONSTRAINT purchase_order_line_cost_not_negative CHECK (case_cost >= 0)
);
