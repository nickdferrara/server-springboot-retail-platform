-- An adjustment now keeps the items and units it is for, so that units a short-pay held back are not paid for
-- twice: once through the adjustment and again through a conceded dispute. An adjustment recorded before this
-- has no lines.
CREATE TABLE direct_delivery_adjustment_line
(
    direct_delivery_adjustment_id UUID           NOT NULL REFERENCES direct_delivery_adjustment (id) ON DELETE CASCADE,
    position                      INT            NOT NULL,
    item_id                       UUID           NOT NULL,
    units                         INT            NOT NULL,
    unit_cost                     NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (direct_delivery_adjustment_id, position),
    CONSTRAINT direct_delivery_adjustment_line_units_positive CHECK (units >= 1),
    CONSTRAINT direct_delivery_adjustment_line_cost_not_negative CHECK (unit_cost >= 0)
);

CREATE INDEX direct_delivery_adjustment_ticket_idx
    ON direct_delivery_adjustment (vendor_id, store_id, ticket_number);
