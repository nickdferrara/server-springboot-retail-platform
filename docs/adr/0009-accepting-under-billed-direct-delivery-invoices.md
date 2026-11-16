# 9. Accepting under-billed direct delivery invoices

Date: 2026-11-16

## Status

Accepted

## Context

A direct delivery invoice matches only when it bills exactly what the store received (ADR 6). Short-pay
(ADR 8) resolves an invoice that bills more. An invoice that only under-bills, one that bills fewer units than
the store received or omits a received item, with no line that bills more, had no resolution: there is nothing
to hold back, the store's count is right so a correction (ADR 7) does not help, and its accrual stayed open.
The business has decided that accounting accepts such an invoice as billed and closes the ticket.

## Decision

- **Who.** An `Accounting.Manager` other than the person who entered the invoice, with a reason. The invoice
  must be waiting in exception. Nothing is accepted automatically, however small the difference: it always
  waits for a person.
- **What is paid.** The invoice, in full, as billed.
- **The ticket is closed.** The ticket's accrual is relieved whole. The units received and never billed are not
  owed: what they were accrued at leaves accrued payables as a favourable purchase price variance. That amount
  is recorded on the invoice as its unbilled amount, with who decided and why, and the invoice's status becomes
  `UNDER_BILLED`.
- **No second invoice.** A ticket is still invoiced once. If the vendor bills the rest later, that invoice is
  refused because the ticket already has one, and it is handled outside the system.
- **When it is refused.** The ticket has no accrual waiting; the invoice bills an item the store did not
  receive, or more units of an item than it received, which is for short-pay; it bills everything that was
  received, so there is no under-billing to accept; or a billed price is outside tolerance and no buyer has
  accepted it.
- **Price outside tolerance.** The price is still the buyer's to accept. A `Buyer.Approver` who did not enter
  the invoice overrides the price exception as before (ADR 6). When all that then keeps the invoice from
  matching is under-billing, the override is taken and kept on the invoice, which stays in exception for
  accounting. Any later match, including the one a receipt correction runs, honours the acceptance. An override
  is still refused when accepting the price would leave anything other than under-billing.
- **What follows** is what follows a match: a voucher is released for the invoice amount and
  `DirectDeliveryInvoiceMatched` is published, now carrying the unbilled amount. The ledger posting rule is
  unchanged: it debits accrued direct delivery payables for what was accrued, credits accounts payable for what
  is billed, and credits the difference to purchase price variance.

## Consequences

- Stock is not touched: the store keeps the units it received, at the cost they were received at. Only the
  liability is released.
- The favourable variance mixes the units never billed with any price difference on the units that were. The
  unbilled amount on the invoice and on the event tells the two apart.
- If the store later corrects its receipt, the invoice is not reopened; the change becomes a vendor credit or
  an adjustment for accounting, as after any match (ADR 7).
- A price acceptance given while an invoice waits is not withdrawn by a later receipt correction, and cannot be
  given twice.
- An invoice that bills more than was received and is also over its price is still stuck: short-pay refuses it
  over the price, and the buyer's override is refused because what would remain is not under-billing.
- With sign-in off (the `local` profile) accepting under-billing is refused, like overrides and short-pay.
