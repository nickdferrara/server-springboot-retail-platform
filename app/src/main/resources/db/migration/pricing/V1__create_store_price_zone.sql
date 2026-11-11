-- Events already handled by listeners in this module.
CREATE TABLE processed_event
(
    event_id     UUID                     NOT NULL,
    listener     TEXT                     NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (event_id, listener)
);

-- The price zone each store takes its retail prices from. store_id refers to a store in the locations module;
-- there is no foreign key across schemas.
CREATE TABLE store_price_zone
(
    store_id   UUID                     NOT NULL PRIMARY KEY,
    zone_code  VARCHAR(20)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL
);
