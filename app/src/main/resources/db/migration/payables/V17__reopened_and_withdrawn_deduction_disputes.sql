-- A dispute recorded in error is withdrawn and kept, with the day, who, and why in the decision columns: a
-- withdrawn dispute was never decided, so they are free. The deduction can then be disputed again and the
-- vendor's reference used again, so both are unique only among disputes that were not withdrawn.
ALTER TABLE deduction_dispute
    DROP CONSTRAINT deduction_dispute_once_per_notice,
    DROP CONSTRAINT deduction_dispute_reference_once,
    DROP CONSTRAINT deduction_dispute_status_known,
    ADD CONSTRAINT deduction_dispute_status_known CHECK (status IN ('OPEN', 'UPHELD', 'CONCEDED', 'WITHDRAWN'));

CREATE UNIQUE INDEX deduction_dispute_once_per_notice
    ON deduction_dispute (deduction_notice_id) WHERE status <> 'WITHDRAWN';
CREATE UNIQUE INDEX deduction_dispute_reference_once
    ON deduction_dispute (vendor_id, vendor_reference) WHERE status <> 'WITHDRAWN';

-- An upheld dispute can be reopened, which makes it wait again. The decision set aside is kept here, with who
-- reopened the dispute, when, and why.
CREATE TABLE deduction_dispute_reopening
(
    deduction_dispute_id UUID         NOT NULL REFERENCES deduction_dispute (id) ON DELETE CASCADE,
    position             INT          NOT NULL,
    decided_on           DATE         NOT NULL,
    decided_by           TEXT         NOT NULL,
    decision_reason      VARCHAR(200) NOT NULL,
    reopened_on          DATE         NOT NULL,
    reopened_by          TEXT         NOT NULL,
    reason               VARCHAR(200) NOT NULL,
    PRIMARY KEY (deduction_dispute_id, position)
);
