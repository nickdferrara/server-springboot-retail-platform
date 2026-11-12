# 2. Placeholder fiscal calendar and accounts

Date: 2026-11-12

## Status

Accepted, to be superseded when the open questions below are answered

## Context

Every journal entry carries a fiscal period and posts to accounts. The PRD leaves both undecided: the 4-4-5
calendar rules (year start, period-end weekday, where the 53rd week falls) and the chart of accounts to migrate,
including how a warehouse maps to the location segment of an account string. The first accounting flow, the
accrual for goods received, should not wait for those answers.

## Decision

The ledger is built against placeholders that are data or one small type, so replacing them changes no posting
rule and no journal entry code.

- **Calendar.** `FiscalCalendarRules` generates a 4-4-5 year: weeks run Sunday to Saturday, a fiscal year starts
  on the Sunday on or before 1 January, and in a year of 53 weeks the extra week joins period 12. Periods are
  stored in `ledger.fiscal_period`; a year is generated the first time a date in it is needed. Business date to
  period is a lookup.
- **Accounts.** Two accounts are seeded: `01-1400-0000-00` Warehouse inventory and `01-2100-0000-00` Accrued
  payables. Location and department segments are zero. An account is one row keyed by its full string; segment
  values are not yet master data.
- **Account determination.** A posting rule names a `PostingAccount` role, and `ledger.posting_account` says
  which account fills it. One account fills a role for the whole company.

## Consequences

- Fiscal periods already stored will be wrong once the real rules are known. Before go-live, the stored periods
  must be regenerated; any journal entries posted against them are test data.
- Inventory is not yet posted by location, so the ledger cannot report inventory per warehouse. That needs the
  location segment and a rule that picks the account from the event's warehouse.
- Periods have no open or closed status, so nothing is rejected for a closed period yet.
