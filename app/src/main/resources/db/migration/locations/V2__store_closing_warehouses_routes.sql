ALTER TABLE store
    ADD COLUMN closed_on DATE,
    ADD CONSTRAINT store_closed_after_opening CHECK (closed_on IS NULL OR closed_on >= opened_on);

CREATE TABLE warehouse
(
    id         UUID                     NOT NULL PRIMARY KEY,
    number     VARCHAR(6)               NOT NULL,
    name       VARCHAR(100)             NOT NULL,
    time_zone  VARCHAR(64)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL,
    CONSTRAINT warehouse_number_unique UNIQUE (number),
    CONSTRAINT warehouse_number_format CHECK (number ~ '^[0-9]{1,6}$')
);

CREATE TABLE delivery_route
(
    id           UUID                     NOT NULL PRIMARY KEY,
    code         VARCHAR(10)              NOT NULL,
    name         VARCHAR(100)             NOT NULL,
    warehouse_id UUID                     NOT NULL REFERENCES warehouse (id),
    version      INT                      NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by   TEXT                     NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by   TEXT                     NOT NULL,
    CONSTRAINT delivery_route_code_unique UNIQUE (code)
);

CREATE TABLE route_stop
(
    route_id           UUID        NOT NULL REFERENCES delivery_route (id) ON DELETE CASCADE,
    position           INT         NOT NULL,
    store_id           UUID        NOT NULL REFERENCES store (id),
    delivery_day       VARCHAR(9)  NOT NULL,
    cutoff_days_before INT         NOT NULL,
    cutoff_time        TIME        NOT NULL,
    PRIMARY KEY (route_id, position),
    CONSTRAINT route_stop_once_per_day UNIQUE (route_id, store_id, delivery_day),
    CONSTRAINT route_stop_cutoff_days CHECK (cutoff_days_before BETWEEN 0 AND 6)
);

CREATE INDEX route_stop_store_idx ON route_stop (store_id);
