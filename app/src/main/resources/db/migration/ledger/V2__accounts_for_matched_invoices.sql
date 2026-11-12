-- Placeholder accounts for matched vendor invoices, to be replaced when the company's chart of accounts is
-- migrated.
INSERT INTO account (number, name, type, version, created_at, created_by, updated_at, updated_by)
VALUES ('01-2000-0000-00', 'Accounts payable', 'LIABILITY', 1, now(), 'system', now(), 'system'),
       ('01-5100-0000-00', 'Purchase price variance', 'EXPENSE', 1, now(), 'system', now(), 'system');

INSERT INTO posting_account (role, account_number, version, created_at, created_by, updated_at, updated_by)
VALUES ('ACCOUNTS_PAYABLE', '01-2000-0000-00', 1, now(), 'system', now(), 'system'),
       ('PURCHASE_PRICE_VARIANCE', '01-5100-0000-00', 1, now(), 'system', now(), 'system');
