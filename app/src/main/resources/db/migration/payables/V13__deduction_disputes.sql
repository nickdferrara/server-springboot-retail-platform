-- A vendor's dispute of a deduction, recorded against the notice that told the vendor of it. Accounting decides
-- it once: the deduction is upheld, or some or all of it is conceded and paid. vendor_id and store_id refer to
-- the vendors and locations modules; there are no foreign keys across schemas.
CREATE TABLE deduction_dispute
(
    id                      UUID                     NOT NULL PRIMARY KEY,
    deduction_notice_id     UUID                     NOT NULL REFERENCES deduction_notice (id),
    deduction_notice_number VARCHAR(10)              NOT NULL,
    vendor_id               UUID                     NOT NULL,
    store_id                UUID                     NOT NULL,
    ticket_number           VARCHAR(30)              NOT NULL,
    deduction               NUMERIC(19, 4)           NOT NULL,
    currency                CHAR(3)                  NOT NULL,
    deducted_by             TEXT                     NOT NULL,
    received_on             DATE                     NOT NULL,
    vendor_reference        VARCHAR(30)              NOT NULL,
    claim                   VARCHAR(200)             NOT NULL,
    recorded_by             TEXT                     NOT NULL,
    recorded_on             DATE                     NOT NULL,
    status                  VARCHAR(20)              NOT NULL,
    decided_on              DATE,
    decided_by              TEXT,
    decision_reason         VARCHAR(200),
    conceded                NUMERIC(19, 4),
    version                 INT                      NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              TEXT                     NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by              TEXT                     NOT NULL,
    -- A deduction is disputed once.
    CONSTRAINT deduction_dispute_once_per_notice UNIQUE (deduction_notice_id),
    CONSTRAINT deduction_dispute_reference_once UNIQUE (vendor_id, vendor_reference),
    CONSTRAINT deduction_dispute_status_known CHECK (status IN ('OPEN', 'UPHELD', 'CONCEDED')),
    CONSTRAINT deduction_dispute_decided_when_not_open
        CHECK ((status = 'OPEN') = (decided_on IS NULL)
            AND (decided_on IS NULL) = (decided_by IS NULL)
            AND (decided_on IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT deduction_dispute_conceded_when_conceded
        CHECK ((status = 'CONCEDED') = (conceded IS NOT NULL)
            AND (conceded IS NULL OR (conceded > 0 AND conceded <= deduction)))
);

CREATE INDEX deduction_dispute_vendor_idx ON deduction_dispute (vendor_id, recorded_on);
CREATE INDEX deduction_dispute_open_idx ON deduction_dispute (vendor_id, recorded_on) WHERE status = 'OPEN';
