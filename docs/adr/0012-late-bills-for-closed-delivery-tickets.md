# 12. Late bills for closed delivery tickets

Date: 2026-11-16

## Status

Accepted. Supersedes the part of ADR 9 that says a late bill is handled outside the system.

## Context

A delivery ticket is invoiced once (ADR 6). When an invoice is accepted under-billed the ticket is closed and
the units never billed are released as a favourable variance (ADR 9); if the vendor bills the rest later, that
bill was refused and left to be handled outside the system. But this system is the book of record and pays
vendors itself, so a late bill paid elsewhere would never reach the ledger, and nothing recorded that the bill
had arrived or what became of it. The business has decided that a clerk records a late bill as a thing of its
own, with its total; that accounting either rejects it or pays it here; and that an `Accounting.Manager` other
than the person who recorded it decides.

## Decision

- **What a late bill is.** A further bill from a vendor for a ticket whose invoice is already payable: matched,
  short-paid, or accepted under-billed. It is not an invoice: it is not matched to the receipt, has no lines,
  and relieves nothing. Entering it as an invoice is still refused.
- **Recording.** An `Accounting.Clerk` records the vendor, store, ticket number, the vendor's document number
  and date, and the total billed. It is refused when the ticket has no invoice or its invoice still waits in
  exception, which is for the invoice's own resolution; when the bill is in another currency than the ticket's
  invoice; and when the vendor's document number was used before, on an invoice, an adjustment, or another late
  bill. A ticket may have more than one late bill.
- **Settling.** An `Accounting.Manager` other than the person who recorded it, with a reason, once:
  - **Reject.** Nothing is owed. The bill stays on record; nothing is paid and nothing is posted.
  - **Pay.** A voucher is released for the bill's total, due and discounted on the vendor's payment terms from
    the bill's date, and a payment run pays it like any other voucher.
- **Ledger.** Payables publishes `DirectDeliveryLateBillAccepted` when a late bill is paid. Nothing is accrued
  for it, so the ledger debits purchase price variance and credits accounts payable for the whole amount. After
  an under-billed invoice this reverses the favourable variance taken when the ticket was closed.
- **Voucher.** As for an adjustment (ADR 11), the voucher carries the late bill's identifier where an invoice's
  would be, and the vendor's document number and date as its invoice number and date.

## Consequences

- A late bill is paid on accounting's judgement, not on a match: nothing compares its total with what the
  first invoice left unbilled. The unbilled amount on the first invoice is there to compare by eye.
- A late bill is paid in full or not at all; it cannot be paid in part.
- Stock and the ticket's accrual are not touched.
- With sign-in off (the `local` profile) a late bill can be recorded but not settled, like overrides.
- Nothing ages late bills or reminds accounting of ones left open; they are listed with `openOnly=true`.
