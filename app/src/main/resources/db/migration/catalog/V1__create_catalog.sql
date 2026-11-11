CREATE TABLE unit_of_measure
(
    code       VARCHAR(10)              NOT NULL PRIMARY KEY,
    name       VARCHAR(60)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL
);

INSERT INTO unit_of_measure (code, name, version, created_at, created_by, updated_at, updated_by)
VALUES ('EA', 'Each', 1, now(), 'system', now(), 'system'),
       ('CS', 'Case', 1, now(), 'system', now(), 'system'),
       ('LB', 'Pound', 1, now(), 'system', now(), 'system'),
       ('GAL', 'Gallon', 1, now(), 'system', now(), 'system');

CREATE TABLE department
(
    code       VARCHAR(10)              NOT NULL PRIMARY KEY,
    name       VARCHAR(60)              NOT NULL,
    version    INT                      NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by TEXT                     NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by TEXT                     NOT NULL
);

CREATE TABLE category
(
    code            VARCHAR(10)              NOT NULL PRIMARY KEY,
    department_code VARCHAR(10)              NOT NULL REFERENCES department (code),
    name            VARCHAR(60)              NOT NULL,
    version         INT                      NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      TEXT                     NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by      TEXT                     NOT NULL
);

CREATE TABLE item
(
    id              UUID                     NOT NULL PRIMARY KEY,
    number          VARCHAR(10)              NOT NULL,
    description     VARCHAR(100)             NOT NULL,
    category_code   VARCHAR(10)              NOT NULL REFERENCES category (code),
    department_code VARCHAR(10)              NOT NULL REFERENCES department (code),
    base_unit       VARCHAR(10)              NOT NULL REFERENCES unit_of_measure (code),
    discontinued_on DATE,
    version         INT                      NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      TEXT                     NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by      TEXT                     NOT NULL,
    CONSTRAINT item_number_unique UNIQUE (number),
    CONSTRAINT item_number_format CHECK (number ~ '^[0-9]{1,10}$')
);

CREATE TABLE item_upc
(
    item_id UUID        NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    upc     VARCHAR(13) NOT NULL,
    PRIMARY KEY (item_id, upc),
    CONSTRAINT item_upc_unique UNIQUE (upc)
);

CREATE TABLE item_pack
(
    item_id    UUID           NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    unit       VARCHAR(10)    NOT NULL REFERENCES unit_of_measure (code),
    base_units NUMERIC(19, 3) NOT NULL,
    PRIMARY KEY (item_id, position),
    CONSTRAINT item_pack_unit_once UNIQUE (item_id, unit),
    CONSTRAINT item_pack_positive CHECK (base_units > 0)
);

-- vendor_id refers to a vendor in the vendors module; there is no foreign key across schemas.
CREATE TABLE item_vendor
(
    item_id    UUID           NOT NULL REFERENCES item (id) ON DELETE CASCADE,
    position   INT            NOT NULL,
    vendor_id  UUID           NOT NULL,
    vendor_sku VARCHAR(30)    NOT NULL,
    case_pack  INT            NOT NULL,
    list_cost  NUMERIC(19, 4) NOT NULL,
    currency   CHAR(3)        NOT NULL,
    PRIMARY KEY (item_id, position),
    CONSTRAINT item_vendor_once UNIQUE (item_id, vendor_id),
    CONSTRAINT item_vendor_case_pack CHECK (case_pack >= 1),
    CONSTRAINT item_vendor_cost CHECK (list_cost >= 0)
);

CREATE INDEX item_vendor_vendor_idx ON item_vendor (vendor_id);
