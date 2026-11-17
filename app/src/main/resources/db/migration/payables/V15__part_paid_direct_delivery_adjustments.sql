-- Part of an adjustment can be paid: accounting names the units of each item to pay, and the rest is written off
-- in the same step. paid_units holds the units of a line that were paid; it is null on every line of an
-- adjustment that was not paid in part.
ALTER TABLE direct_delivery_adjustment_line
    ADD COLUMN paid_units INT;

ALTER TABLE direct_delivery_adjustment_line
    ADD CONSTRAINT direct_delivery_adjustment_line_paid_units_valid
        CHECK (paid_units IS NULL OR (paid_units >= 0 AND paid_units <= units));
