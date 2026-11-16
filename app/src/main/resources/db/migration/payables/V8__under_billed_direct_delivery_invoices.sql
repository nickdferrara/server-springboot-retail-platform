-- A direct delivery invoice that bills less than the store received, with no line that bills more, can be
-- accepted as billed: it is paid in full and the ticket is closed. unbilled is what the units received and never
-- billed were accrued at. Who accepted it and why are kept in the override columns. A buyer's acceptance of a
-- unit cost outside tolerance is kept apart, because it can be given while the invoice still waits for
-- accounting.
ALTER TABLE direct_delivery_invoice
    ADD COLUMN unbilled              NUMERIC(19, 4),
    ADD COLUMN price_accepted_by     TEXT,
    ADD COLUMN price_accepted_reason VARCHAR(200);

ALTER TABLE direct_delivery_invoice
    DROP CONSTRAINT direct_delivery_invoice_status_known,
    DROP CONSTRAINT direct_delivery_invoice_matched_on_when_payable,
    DROP CONSTRAINT direct_delivery_invoice_override_when_payable;

ALTER TABLE direct_delivery_invoice
    ADD CONSTRAINT direct_delivery_invoice_status_known
        CHECK (status IN ('MATCHED', 'SHORT_PAID', 'UNDER_BILLED', 'EXCEPTION')),
    ADD CONSTRAINT direct_delivery_invoice_matched_on_when_payable
        CHECK ((status IN ('MATCHED', 'SHORT_PAID', 'UNDER_BILLED')) = (matched_on IS NOT NULL)),
    ADD CONSTRAINT direct_delivery_invoice_override_when_payable
        CHECK (override_approved_by IS NULL OR status IN ('MATCHED', 'SHORT_PAID', 'UNDER_BILLED')),
    ADD CONSTRAINT direct_delivery_invoice_unbilled_when_under_billed
        CHECK ((status = 'UNDER_BILLED') = (unbilled IS NOT NULL)
            AND (unbilled IS NULL OR (unbilled >= 0 AND override_approved_by IS NOT NULL))),
    ADD CONSTRAINT direct_delivery_invoice_price_acceptance_complete
        CHECK ((price_accepted_by IS NULL) = (price_accepted_reason IS NULL)),
    ADD CONSTRAINT direct_delivery_invoice_price_acceptance_when_waiting_or_under_billed
        CHECK (price_accepted_by IS NULL OR status IN ('EXCEPTION', 'UNDER_BILLED'));
