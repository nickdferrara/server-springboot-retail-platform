CREATE TABLE store
(
    id         UUID                     NOT NULL PRIMARY KEY,
    number     VARCHAR(6)               NOT NULL,
    name       VARCHAR(100)             NOT NULL,
    time_zone  VARCHAR(64)              NOT NULL,
    opened_on  DATE                     NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL,
    CONSTRAINT store_number_unique UNIQUE (number),
    CONSTRAINT store_number_format CHECK (number ~ '^[0-9]{1,6}$')
);
