-- What is owed to a vendor for the units it delivered straight to a store on one ticket, at agreed cost, until
-- its invoice is matched. delivery_id, vendor_id, and store_id refer to the receiving, vendors, and locations
-- modules; there are no foreign keys across schemas.
CREATE TABLE direct_delivery_accrual
(
    delivery_id    UUID                     NOT NULL PRIMARY KEY,
    receipt_number VARCHAR(10)              NOT NULL,
    vendor_id      UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    ticket_number  VARCHAR(30)              NOT NULL,
    received_on    DATE                     NOT NULL,
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT direct_delivery_accrual_ticket_once UNIQUE (vendor_id, store_id, ticket_number)
);

CREATE INDEX direct_delivery_accrual_vendor_idx ON direct_delivery_accrual (vendor_id, received_on);

CREATE TABLE direct_delivery_accrual_line
(
    delivery_id UUID           NOT NULL REFERENCES direct_delivery_accrual (delivery_id) ON DELETE CASCADE,
    position    INT            NOT NULL,
    item_id     UUID           NOT NULL,
    units       INT            NOT NULL,
    unit_cost   NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (delivery_id, position),
    CONSTRAINT direct_delivery_accrual_line_units_positive CHECK (units >= 1),
    CONSTRAINT direct_delivery_accrual_line_cost_not_negative CHECK (unit_cost >= 0)
);

-- What a vendor owes for product its driver took back from a store on one ticket, at agreed cost.
CREATE TABLE vendor_credit
(
    id             UUID                     NOT NULL PRIMARY KEY,
    delivery_id    UUID                     NOT NULL,
    receipt_number VARCHAR(10)              NOT NULL,
    vendor_id      UUID                     NOT NULL,
    store_id       UUID                     NOT NULL,
    ticket_number  VARCHAR(30)              NOT NULL,
    credited_on    DATE                     NOT NULL,
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT vendor_credit_once_per_delivery UNIQUE (delivery_id)
);

CREATE INDEX vendor_credit_vendor_idx ON vendor_credit (vendor_id, credited_on);

CREATE TABLE vendor_credit_line
(
    vendor_credit_id UUID           NOT NULL REFERENCES vendor_credit (id) ON DELETE CASCADE,
    position         INT            NOT NULL,
    item_id          UUID           NOT NULL,
    units            INT            NOT NULL,
    unit_cost        NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (vendor_credit_id, position),
    CONSTRAINT vendor_credit_line_units_positive CHECK (units >= 1),
    CONSTRAINT vendor_credit_line_cost_not_negative CHECK (unit_cost >= 0)
);
