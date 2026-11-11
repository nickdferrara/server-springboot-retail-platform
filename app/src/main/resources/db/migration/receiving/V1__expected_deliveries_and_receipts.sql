-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- Purchase orders sent to vendors, as the dock sees them. purchase_order_id, vendor_id, and warehouse_id refer to
-- the purchasing, vendors, and locations modules; there are no foreign keys across schemas.
CREATE TABLE expected_delivery
(
    purchase_order_id     UUID                     NOT NULL PRIMARY KEY,
    purchase_order_number VARCHAR(10)              NOT NULL,
    vendor_id             UUID                     NOT NULL,
    warehouse_id          UUID                     NOT NULL,
    expected_on           DATE                     NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT expected_delivery_status_known CHECK (status IN ('OPEN', 'COMPLETE', 'CANCELLED'))
);

CREATE INDEX expected_delivery_open_idx ON expected_delivery (warehouse_id, expected_on) WHERE status = 'OPEN';

CREATE TABLE expected_delivery_line
(
    purchase_order_id UUID           NOT NULL REFERENCES expected_delivery (purchase_order_id) ON DELETE CASCADE,
    position          INT            NOT NULL,
    line_number       INT            NOT NULL,
    item_id           UUID           NOT NULL,
    vendor_sku        VARCHAR(30)    NOT NULL,
    case_pack         INT            NOT NULL,
    case_cost         NUMERIC(19, 4) NOT NULL,
    currency          CHAR(3)        NOT NULL,
    cases_ordered     INT            NOT NULL,
    cases_received    INT            NOT NULL,
    PRIMARY KEY (purchase_order_id, position),
    CONSTRAINT expected_delivery_line_number_once UNIQUE (purchase_order_id, line_number),
    CONSTRAINT expected_delivery_line_received_not_negative CHECK (cases_received >= 0)
);

CREATE SEQUENCE receipt_number_seq START WITH 100001;

-- A receipt is written once and never changed.
CREATE TABLE receipt
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    number                VARCHAR(10)              NOT NULL,
    purchase_order_id     UUID                     NOT NULL REFERENCES expected_delivery (purchase_order_id),
    purchase_order_number VARCHAR(10)              NOT NULL,
    vendor_id             UUID                     NOT NULL,
    warehouse_id          UUID                     NOT NULL,
    received_on           DATE                     NOT NULL,
    received_by           TEXT                     NOT NULL,
    final_delivery        BOOLEAN                  NOT NULL,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    CONSTRAINT receipt_number_unique UNIQUE (number)
);

CREATE INDEX receipt_purchase_order_idx ON receipt (purchase_order_id);

CREATE TABLE receipt_line
(
    receipt_id     UUID           NOT NULL REFERENCES receipt (id) ON DELETE CASCADE,
    position       INT            NOT NULL,
    line_number    INT            NOT NULL,
    item_id        UUID           NOT NULL,
    case_pack      INT            NOT NULL,
    case_cost      NUMERIC(19, 4) NOT NULL,
    currency       CHAR(3)        NOT NULL,
    cases_received INT            NOT NULL,
    cases_damaged  INT            NOT NULL,
    cases_over     INT            NOT NULL,
    cases_short    INT            NOT NULL,
    PRIMARY KEY (receipt_id, position),
    CONSTRAINT receipt_line_counts_not_negative
        CHECK (cases_received >= 0 AND cases_damaged >= 0 AND cases_over >= 0 AND cases_short >= 0)
);
