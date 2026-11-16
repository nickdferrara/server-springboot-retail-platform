-- A buyer's acceptance of a unit cost outside tolerance can now come before a short-pay, so it is kept on a
-- short-paid invoice as it is on one accepted under-billed.
ALTER TABLE direct_delivery_invoice
    DROP CONSTRAINT direct_delivery_invoice_price_acceptance_when_waiting_or_under_billed;

ALTER TABLE direct_delivery_invoice
    ADD CONSTRAINT direct_delivery_invoice_price_acceptance_not_when_matched
        CHECK (price_accepted_by IS NULL OR status IN ('EXCEPTION', 'UNDER_BILLED', 'SHORT_PAID'));
