CREATE TABLE vendor
(
    id                   UUID                     NOT NULL PRIMARY KEY,
    number               VARCHAR(10)              NOT NULL,
    name                 VARCHAR(100)             NOT NULL,
    net_days             INT                      NOT NULL,
    discount_percent     NUMERIC(7, 4),
    discount_days        INT,
    remit_to_line1       VARCHAR(100)             NOT NULL,
    remit_to_line2       VARCHAR(100),
    remit_to_city        VARCHAR(100)             NOT NULL,
    remit_to_region      VARCHAR(100)             NOT NULL,
    remit_to_postal_code VARCHAR(12)              NOT NULL,
    remit_to_country_code CHAR(2)                 NOT NULL,
    version              INT                      NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by           TEXT                     NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by           TEXT                     NOT NULL,
    CONSTRAINT vendor_number_unique UNIQUE (number),
    CONSTRAINT vendor_number_format CHECK (number ~ '^[0-9]{1,10}$'),
    CONSTRAINT vendor_net_days_range CHECK (net_days BETWEEN 0 AND 365),
    CONSTRAINT vendor_discount_complete CHECK ((discount_percent IS NULL) = (discount_days IS NULL)),
    CONSTRAINT vendor_discount_before_due CHECK (discount_days IS NULL OR discount_days < net_days)
);

CREATE TABLE vendor_contact
(
    vendor_id UUID         NOT NULL REFERENCES vendor (id) ON DELETE CASCADE,
    position  INT          NOT NULL,
    name      VARCHAR(100) NOT NULL,
    email     VARCHAR(100),
    phone     VARCHAR(100),
    PRIMARY KEY (vendor_id, position)
);
