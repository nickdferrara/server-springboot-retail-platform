# 8. Short-paying a direct delivery invoice

Date: 2026-11-13

## Status

Accepted

## Context

A direct delivery invoice matches only when it bills exactly what the store received (ADR 6). When it bills
more and the store's count is right, correcting the receipt (ADR 7) does not help, because the receipt is
already correct, and a second invoice for the ticket cannot be entered. The business has decided that
accounting then short-pays: it pays for what was received and records the difference as a deduction against
the vendor.

## Decision

- **Who.** An `Accounting.Manager` other than the person who entered the invoice, with a reason. The invoice
  must be waiting in exception.
- **What is paid.** For each item billed, the units the store received or the units billed, whichever is
  fewer, at the unit cost billed. An item billed that is not on the ticket is not paid at all.
- **What is held back.** The deduction is what the invoice bills less what is paid. It is recorded on the
  invoice, whose status becomes `SHORT_PAID`, with who decided and why.
- **When it is refused.** The ticket has no accrual waiting; a billed price is outside tolerance, which is the
  buyer's override to give, not accounting's; nothing would be held back, because the invoice bills no more
  than was received; or nothing would be paid, because none of the units billed were received.
- **What follows** is what follows a match: the ticket's accrual is relieved whole, a voucher is released for
  what is paid, and `DirectDeliveryInvoiceMatched` is published, now carrying the deduction. The ledger credits
  accounts payable for what is owed, not for what was billed; the deduction is never a liability.

## Consequences

- A deduction is a record, not a document: nothing is sent to the vendor, and nothing happens if the vendor
  disputes it.
- An invoice that bills fewer units than were received, with no line that bills more, still has no
  resolution: there is nothing to hold back, and it does not match. The accrual stays open.
- A short-paid invoice relieves the whole accrual, including units received and never billed; the difference
  goes to purchase price variance.
- If the store later finds the missing units and corrects its receipt, the invoice is not reopened; the extra
  units become an adjustment for accounting (ADR 7).
- With sign-in off (the `local` profile) short-pay is refused, like overrides.
