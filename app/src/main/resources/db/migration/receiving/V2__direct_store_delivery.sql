-- What a vendor charges a store for one base unit of an item it delivers there directly, from effective_from
-- until a later row replaces it. vendor_id, item_id, and store_id refer to the vendors, catalog, and locations
-- modules; there are no foreign keys across schemas. Rows are kept as history.
CREATE TABLE direct_delivery_cost
(
    vendor_id      UUID                     NOT NULL,
    item_id        UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    effective_from DATE                     NOT NULL,
    unit_cost      NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    set_by         TEXT                     NOT NULL,
    set_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (vendor_id, store_id, item_id, effective_from),
    CONSTRAINT direct_delivery_cost_not_negative CHECK (unit_cost >= 0)
);

-- A vendor's delivery straight to a store, checked in from the driver's ticket. Written once and never changed.
-- Its number comes from receipt_number_seq, so every receipt has a number of its own.
CREATE TABLE direct_delivery
(
    id             UUID                     NOT NULL PRIMARY KEY,
    receipt_number VARCHAR(10)              NOT NULL,
    vendor_id      UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    ticket_number  VARCHAR(30)              NOT NULL,
    received_on    DATE                     NOT NULL,
    received_by    TEXT                     NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT direct_delivery_receipt_number_unique UNIQUE (receipt_number),
    -- A vendor's ticket is checked in once at a store.
    CONSTRAINT direct_delivery_ticket_once UNIQUE (vendor_id, store_id, ticket_number)
);

CREATE INDEX direct_delivery_store_idx ON direct_delivery (store_id, id);

CREATE TABLE direct_delivery_line
(
    direct_delivery_id UUID           NOT NULL REFERENCES direct_delivery (id) ON DELETE CASCADE,
    position           INT            NOT NULL,
    line_number        INT            NOT NULL,
    item_id            UUID           NOT NULL,
    units_delivered    INT            NOT NULL,
    units_returned     INT            NOT NULL,
    unit_cost          NUMERIC(19, 4) NOT NULL,
    currency           CHAR(3)        NOT NULL,
    PRIMARY KEY (direct_delivery_id, position),
    CONSTRAINT direct_delivery_line_item_once UNIQUE (direct_delivery_id, item_id),
    CONSTRAINT direct_delivery_line_counts_valid
        CHECK (units_delivered >= 0 AND units_returned >= 0 AND units_delivered + units_returned > 0)
);
