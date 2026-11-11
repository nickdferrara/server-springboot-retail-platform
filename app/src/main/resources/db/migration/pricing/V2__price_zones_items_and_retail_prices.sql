CREATE TABLE price_zone
(
    code       VARCHAR(20)              NOT NULL PRIMARY KEY,
    name       VARCHAR(60)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL
);

-- Every store starts in this zone.
INSERT INTO price_zone (code, name, version, created_at, created_by, updated_at, updated_by)
VALUES ('DEFAULT', 'Default', 1, now(), 'system', now(), 'system');

ALTER TABLE store_price_zone
    ADD CONSTRAINT store_price_zone_zone_fk FOREIGN KEY (zone_code) REFERENCES price_zone (code);

-- The catalog items pricing knows about. item_id refers to an item in the catalog module; there is no foreign
-- key across schemas.
CREATE TABLE priced_item
(
    item_id         UUID                     NOT NULL PRIMARY KEY,
    item_number     VARCHAR(10)              NOT NULL,
    discontinued_on DATE,
    version         INT                      NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      TEXT                     NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by      TEXT                     NOT NULL
);

-- A price applies to a zone or to one store, never both. Rows are kept as history: a price is replaced by a
-- later row, not by an update.
CREATE TABLE retail_price
(
    id             UUID                     NOT NULL PRIMARY KEY,
    item_id        UUID                     NOT NULL REFERENCES priced_item (item_id),
    zone_code      VARCHAR(20) REFERENCES price_zone (code),
    store_id       UUID REFERENCES store_price_zone (store_id),
    amount         NUMERIC(19, 4)           NOT NULL,
    currency       CHAR(3)                  NOT NULL,
    effective_from DATE                     NOT NULL,
    announced_on   DATE,
    version        INT                      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by     TEXT                     NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by     TEXT                     NOT NULL,
    CONSTRAINT retail_price_one_scope CHECK ((zone_code IS NULL) <> (store_id IS NULL)),
    CONSTRAINT retail_price_positive CHECK (amount > 0)
);

CREATE UNIQUE INDEX retail_price_zone_once ON retail_price (item_id, zone_code, effective_from)
    WHERE zone_code IS NOT NULL;
CREATE UNIQUE INDEX retail_price_store_once ON retail_price (item_id, store_id, effective_from)
    WHERE store_id IS NOT NULL;
CREATE INDEX retail_price_due_idx ON retail_price (effective_from) WHERE announced_on IS NULL;
