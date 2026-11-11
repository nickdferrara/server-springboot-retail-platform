-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- The catalog items inventory knows about. item_id refers to an item in the catalog module; there is no foreign
-- key across schemas.
CREATE TABLE stocked_item
(
    item_id     UUID                     NOT NULL PRIMARY KEY,
    item_number VARCHAR(10)              NOT NULL,
    base_unit   VARCHAR(10)              NOT NULL,
    version     INT                      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by  TEXT                     NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by  TEXT                     NOT NULL
);

-- On-hand quantity and weighted average cost per item and location: the running total of inventory_movement.
-- location_id refers to a warehouse or a store in the locations module.
CREATE TABLE stock_level
(
    id            UUID                     NOT NULL PRIMARY KEY,
    item_id       UUID                     NOT NULL REFERENCES stocked_item (item_id),
    location_type VARCHAR(10)              NOT NULL,
    location_id   UUID                     NOT NULL,
    on_hand       NUMERIC(19, 3)           NOT NULL,
    unit          VARCHAR(10)              NOT NULL,
    average_cost  NUMERIC(19, 4),
    currency      CHAR(3),
    version       INT                      NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by    TEXT                     NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by    TEXT                     NOT NULL,
    CONSTRAINT stock_level_once_per_location UNIQUE (item_id, location_type, location_id),
    CONSTRAINT stock_level_location_type_known CHECK (location_type IN ('WAREHOUSE', 'STORE')),
    CONSTRAINT stock_level_cost_complete CHECK ((average_cost IS NULL) = (currency IS NULL)),
    CONSTRAINT stock_level_cost_not_negative CHECK (average_cost IS NULL OR average_cost >= 0)
);

CREATE INDEX stock_level_location_idx ON stock_level (location_type, location_id);

-- The inventory ledger. quantity is signed: positive into the location, negative out of it.
CREATE TABLE inventory_movement
(
    id                 UUID                     NOT NULL PRIMARY KEY,
    item_id            UUID                     NOT NULL REFERENCES stocked_item (item_id),
    location_type      VARCHAR(10)              NOT NULL,
    location_id        UUID                     NOT NULL,
    movement_type      VARCHAR(20)              NOT NULL,
    reason             VARCHAR(20),
    quantity           NUMERIC(19, 3)           NOT NULL,
    unit               VARCHAR(10)              NOT NULL,
    unit_cost          NUMERIC(19, 4)           NOT NULL,
    extended_cost      NUMERIC(19, 4)           NOT NULL,
    currency           CHAR(3)                  NOT NULL,
    on_hand_after      NUMERIC(19, 3)           NOT NULL,
    average_cost_after NUMERIC(19, 4),
    business_date      DATE                     NOT NULL,
    occurred_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    source_reference   VARCHAR(50),
    source_event_id    UUID,
    recorded_by        TEXT                     NOT NULL,
    CONSTRAINT inventory_movement_location_type_known CHECK (location_type IN ('WAREHOUSE', 'STORE')),
    CONSTRAINT inventory_movement_type_known CHECK (movement_type IN ('RECEIPT', 'ADJUSTMENT')),
    CONSTRAINT inventory_movement_reason_known CHECK (reason IS NULL OR reason IN ('SHRINK', 'DAMAGE', 'COUNT')),
    CONSTRAINT inventory_movement_reason_for_adjustment CHECK ((movement_type = 'ADJUSTMENT') = (reason IS NOT NULL)),
    CONSTRAINT inventory_movement_moves_something CHECK (quantity <> 0),
    -- A business event moves an item at a location once, however often it is delivered.
    CONSTRAINT inventory_movement_once_per_event UNIQUE (source_event_id, item_id, location_type, location_id)
);

CREATE INDEX inventory_movement_stock_idx ON inventory_movement (item_id, location_type, location_id, id);
CREATE INDEX inventory_movement_business_date_idx ON inventory_movement (business_date);

-- The ledger is append-only: a mistake is corrected by a further entry, never by changing or removing one.
CREATE FUNCTION forbid_inventory_movement_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'inventory_movement is append-only';
END;
$$;

CREATE TRIGGER inventory_movement_append_only
    BEFORE UPDATE OR DELETE
    ON inventory_movement
    FOR EACH ROW
EXECUTE FUNCTION forbid_inventory_movement_change();
