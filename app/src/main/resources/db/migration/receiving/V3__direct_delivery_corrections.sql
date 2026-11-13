-- A correction to a direct store delivery: a further receipt, numbered from receipt_number_seq, that records how
-- the counts changed and why. Written once and never changed.
CREATE TABLE direct_delivery_correction
(
    id                 UUID                     NOT NULL PRIMARY KEY,
    direct_delivery_id UUID                     NOT NULL REFERENCES direct_delivery (id),
    receipt_number     VARCHAR(10)              NOT NULL,
    corrected_on       DATE                     NOT NULL,
    corrected_by       TEXT                     NOT NULL,
    reason             VARCHAR(200)             NOT NULL,
    version            INT                      NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by         TEXT                     NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by         TEXT                     NOT NULL,
    CONSTRAINT direct_delivery_correction_receipt_number_unique UNIQUE (receipt_number)
);

CREATE INDEX direct_delivery_correction_delivery_idx ON direct_delivery_correction (direct_delivery_id, id);

-- How a correction changed one item: positive when more was delivered or returned than was on record.
CREATE TABLE direct_delivery_correction_line
(
    direct_delivery_correction_id UUID           NOT NULL REFERENCES direct_delivery_correction (id) ON DELETE CASCADE,
    position                      INT            NOT NULL,
    item_id                       UUID           NOT NULL,
    units_delivered_change        INT            NOT NULL,
    units_returned_change         INT            NOT NULL,
    unit_cost                     NUMERIC(19, 4) NOT NULL,
    currency                      CHAR(3)        NOT NULL,
    PRIMARY KEY (direct_delivery_correction_id, position),
    CONSTRAINT direct_delivery_correction_line_item_once UNIQUE (direct_delivery_correction_id, item_id),
    CONSTRAINT direct_delivery_correction_line_changes CHECK (units_delivered_change <> 0 OR units_returned_change <> 0)
);
