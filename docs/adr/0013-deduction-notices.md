# 13. Deduction notices

Date: 2026-11-17

## Status

Accepted

## Context

A short-pay holds part of a direct delivery invoice back from the vendor (ADRs 8 and 10). The deduction was a
figure on the invoice and nothing more: no document said what was held back, and nothing recorded whether the
vendor had been told. The system has no email or other outbound channel, and none is chosen. The business has
decided that the system produces a numbered notice for every short-pay, that accounting sends it by its usual
means, and that an `Accounting.Clerk` records the sending.

## Decision

- **One notice per short-pay, raised with it.** Paying an invoice short raises its deduction notice in the same
  transaction, so no deduction exists without one. A notice cannot be raised any other way.
- **What it says.** A number of its own, which the vendor can quote back; the vendor's invoice number and date,
  the store, and the ticket; the day it was raised; the reason accounting gave for the short-pay; and one line
  for each item billed beyond what the store received, with units billed, units received, units held back,
  the unit cost billed, and the amount. An item the store did not receive at all is held back in full. The
  lines add up to the invoice's deduction.
- **A snapshot.** The lines are fixed when the notice is raised. A later correction to the receipt does not
  change the notice, as it does not reopen the invoice (ADR 7).
- **Sending.** The system sends nothing. Accounting sends the notice by email, post, in person, or otherwise,
  and an `Accounting.Clerk` records the day it was sent, how, and to whom. The day must fall between the day
  the notice was raised and today. Sending is recorded once.
- **Finding them.** A vendor's notices are listed oldest first, and those not yet sent can be listed alone. A
  short-paid invoice leads to its notice.

## Consequences

- The notice names the vendor, store, and items by identifier. Payables does not hold vendor names, addresses,
  or item descriptions, so whatever presents the notice to the vendor adds them.
- The notice is data, not a rendered document: there is no PDF or letter, and no template.
- An invoice paid short before notices existed has none. Nothing has been deployed, so there are none to
  backfill.
- Nothing reminds accounting of a notice left unsent, and nothing happens if the vendor disputes a deduction.
- A notice recorded as sent in error cannot be corrected.
- Emailing notices from the system later needs an email service and a recipient chosen per vendor; the sending
  record already has the fields for it.
