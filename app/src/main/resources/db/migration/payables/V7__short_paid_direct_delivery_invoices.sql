-- A direct delivery invoice can be paid short: only the units the store received are paid for, and the rest of
-- what the vendor billed is held back as a deduction. Who decided and why are kept in the override columns.
ALTER TABLE direct_delivery_invoice
    ADD COLUMN deduction NUMERIC(19, 4);

ALTER TABLE direct_delivery_invoice
    DROP CONSTRAINT direct_delivery_invoice_status_known,
    DROP CONSTRAINT direct_delivery_invoice_matched_on_when_matched,
    DROP CONSTRAINT direct_delivery_invoice_override_when_matched;

ALTER TABLE direct_delivery_invoice
    ADD CONSTRAINT direct_delivery_invoice_status_known CHECK (status IN ('MATCHED', 'SHORT_PAID', 'EXCEPTION')),
    ADD CONSTRAINT direct_delivery_invoice_matched_on_when_payable
        CHECK ((status IN ('MATCHED', 'SHORT_PAID')) = (matched_on IS NOT NULL)),
    ADD CONSTRAINT direct_delivery_invoice_override_when_payable
        CHECK (override_approved_by IS NULL OR status IN ('MATCHED', 'SHORT_PAID')),
    ADD CONSTRAINT direct_delivery_invoice_deduction_when_short_paid
        CHECK ((status = 'SHORT_PAID') = (deduction IS NOT NULL)
            AND (deduction IS NULL OR (deduction > 0 AND override_approved_by IS NOT NULL)));
