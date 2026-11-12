-- How many of an accrual line's received cases have been matched to a vendor invoice.
ALTER TABLE accrual_line
    ADD COLUMN cases_invoiced INT NOT NULL DEFAULT 0;
ALTER TABLE accrual_line
    ADD CONSTRAINT accrual_line_invoiced_within_received CHECK (cases_invoiced BETWEEN 0 AND cases_received);

-- A vendor's invoice against one purchase order. vendor_id and purchase_order_id refer to the vendors and
-- purchasing modules; there are no foreign keys across schemas.
CREATE TABLE vendor_invoice
(
    id                    UUID                     NOT NULL PRIMARY KEY,
    vendor_id             UUID                     NOT NULL,
    vendor_invoice_number VARCHAR(30)              NOT NULL,
    invoice_date          DATE                     NOT NULL,
    purchase_order_id     UUID                     NOT NULL,
    entered_by            TEXT                     NOT NULL,
    status                VARCHAR(20)              NOT NULL,
    matched_on            DATE,
    version               INT                      NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by            TEXT                     NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by            TEXT                     NOT NULL,
    -- A vendor's invoice number is entered once.
    CONSTRAINT vendor_invoice_once_per_vendor UNIQUE (vendor_id, vendor_invoice_number),
    CONSTRAINT vendor_invoice_status_known CHECK (status IN ('MATCHED', 'EXCEPTION')),
    CONSTRAINT vendor_invoice_matched_on_when_matched CHECK ((status = 'MATCHED') = (matched_on IS NOT NULL))
);

CREATE INDEX vendor_invoice_purchase_order_idx ON vendor_invoice (purchase_order_id);
CREATE INDEX vendor_invoice_exception_idx ON vendor_invoice (id) WHERE status = 'EXCEPTION';

CREATE TABLE vendor_invoice_line
(
    vendor_invoice_id          UUID           NOT NULL REFERENCES vendor_invoice (id) ON DELETE CASCADE,
    position                   INT            NOT NULL,
    purchase_order_line_number INT            NOT NULL,
    cases                      INT            NOT NULL,
    case_cost                  NUMERIC(19, 4) NOT NULL,
    currency                   CHAR(3)        NOT NULL,
    PRIMARY KEY (vendor_invoice_id, position),
    CONSTRAINT vendor_invoice_line_once UNIQUE (vendor_invoice_id, purchase_order_line_number),
    CONSTRAINT vendor_invoice_line_cases_positive CHECK (cases >= 1),
    CONSTRAINT vendor_invoice_line_cost_not_negative CHECK (case_cost >= 0)
);

-- Why an invoice did not match, from its latest match attempt.
CREATE TABLE vendor_invoice_exception
(
    vendor_invoice_id          UUID         NOT NULL REFERENCES vendor_invoice (id) ON DELETE CASCADE,
    position                   INT          NOT NULL,
    purchase_order_line_number INT          NOT NULL,
    reason                     VARCHAR(30)  NOT NULL,
    detail                     VARCHAR(200) NOT NULL,
    PRIMARY KEY (vendor_invoice_id, position)
);
