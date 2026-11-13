-- Product a vendor takes back from a store is a movement of its own kind.
ALTER TABLE inventory_movement
    DROP CONSTRAINT inventory_movement_type_known;
ALTER TABLE inventory_movement
    ADD CONSTRAINT inventory_movement_type_known CHECK (movement_type IN ('RECEIPT', 'ADJUSTMENT', 'VENDOR_RETURN'));

-- One delivery ticket can bring an item in and take the same item back, so a business event moves an item at a
-- location once per kind of movement.
ALTER TABLE inventory_movement
    DROP CONSTRAINT inventory_movement_once_per_event;
ALTER TABLE inventory_movement
    ADD CONSTRAINT inventory_movement_once_per_event
        UNIQUE (source_event_id, item_id, location_type, location_id, movement_type);
