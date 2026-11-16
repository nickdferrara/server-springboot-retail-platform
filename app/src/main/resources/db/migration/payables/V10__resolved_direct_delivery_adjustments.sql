-- Accounting settles a direct delivery adjustment once and whole: it is paid against a document the vendor
-- sent, or written off as not owed. Who decided and why are kept either way.
ALTER TABLE direct_delivery_adjustment
    ADD COLUMN status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN resolved_on       DATE,
    ADD COLUMN resolved_by       TEXT,
    ADD COLUMN resolution_reason VARCHAR(200),
    ADD COLUMN vendor_reference  VARCHAR(30),
    ADD COLUMN reference_date    DATE;

ALTER TABLE direct_delivery_adjustment
    ADD CONSTRAINT direct_delivery_adjustment_status_known CHECK (status IN ('OPEN', 'PAID', 'WRITTEN_OFF')),
    ADD CONSTRAINT direct_delivery_adjustment_resolved_when_not_open
        CHECK ((status = 'OPEN') = (resolved_on IS NULL)
            AND (resolved_on IS NULL) = (resolved_by IS NULL)
            AND (resolved_on IS NULL) = (resolution_reason IS NULL)),
    ADD CONSTRAINT direct_delivery_adjustment_reference_when_paid
        CHECK ((status = 'PAID') = (vendor_reference IS NOT NULL)
            AND (vendor_reference IS NULL) = (reference_date IS NULL));

-- A vendor's document pays one adjustment.
CREATE UNIQUE INDEX direct_delivery_adjustment_reference_once
    ON direct_delivery_adjustment (vendor_id, vendor_reference) WHERE vendor_reference IS NOT NULL;

CREATE INDEX direct_delivery_adjustment_open_idx
    ON direct_delivery_adjustment (vendor_id, recorded_on) WHERE status = 'OPEN';
