-- Placeholder accounts for direct store delivery, to be replaced when the company's chart of accounts is
-- migrated. Store inventory is one account for every store until inventory is posted by location.
INSERT INTO account (number, name, type, version, created_at, created_by, updated_at, updated_by)
VALUES ('01-1410-0000-00', 'Store inventory', 'ASSET', 1, now(), 'system', now(), 'system'),
       ('01-2110-0000-00', 'Accrued direct store delivery payables', 'LIABILITY', 1, now(), 'system', now(),
        'system');

INSERT INTO posting_account (role, account_number, version, created_at, created_by, updated_at, updated_by)
VALUES ('STORE_INVENTORY', '01-1410-0000-00', 1, now(), 'system', now(), 'system'),
       ('ACCRUED_DIRECT_DELIVERY_PAYABLES', '01-2110-0000-00', 1, now(), 'system', now(), 'system');
