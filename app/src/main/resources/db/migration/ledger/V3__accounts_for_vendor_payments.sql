-- Placeholder accounts for vendor payments, to be replaced when the company's chart of accounts is migrated.
INSERT INTO account (number, name, type, version, created_at, created_by, updated_at, updated_by)
VALUES ('01-1000-0000-00', 'Bank (operating account)', 'ASSET', 1, now(), 'system', now(), 'system'),
       ('01-5200-0000-00', 'Purchase discounts taken', 'EXPENSE', 1, now(), 'system', now(), 'system');

INSERT INTO posting_account (role, account_number, version, created_at, created_by, updated_at, updated_by)
VALUES ('BANK', '01-1000-0000-00', 1, now(), 'system', now(), 'system'),
       ('PURCHASE_DISCOUNTS_TAKEN', '01-5200-0000-00', 1, now(), 'system', now(), 'system');
