# 7. Correcting a direct store delivery receipt

Date: 2026-11-13

## Status

Accepted

## Context

A store manager can check a driver's ticket in wrongly: miscount a line, or miss an item. ADR 5 left this out
because whether a correction is still harmless depends on whether the ticket has been invoiced, which only
payables knows, and receiving cannot ask payables: payables already listens to receiving, and the reverse would
be a module cycle. The business has decided that a correction is a further receipt rather than an edit; that it
may change units on existing lines and add a missed item; that any store manager may make one, with a reason
and no time limit; that it uses the cost the ticket already carries; that stock taken back out leaves at the
ticket's cost; that a correction arriving after the invoice has matched is still accepted; and that a
correction re-runs the invoice match automatically.

## Decision

- **Receiving always accepts a correction.** The manager says what each affected item should read. Receiving
  works out the change from what is on record, writes a correction with a receipt number of its own, and
  publishes `DirectDeliveryCorrected` with the changes. The original receipt is never edited; what the ticket
  reads now is the receipt plus its corrections. A correction must change something and must leave something
  delivered or returned.
- **Cost.** An item already on the ticket keeps its cost. A missed item is added at the cost agreed on the day
  of the delivery, not the day of the correction.
- **Inventory.** Units delivered by mistake leave at the ticket's cost, which moves the average back and undoes
  the receipt exactly. Units missed come in at the ticket's cost. A change to units returned moves stock at
  average cost, as a return does.
- **Payables decides what a correction means**, because it knows what has happened to the ticket:
  - While the accrual waits for its invoice it is corrected in place, and an invoice waiting in exception is
    matched again at once, so it may match and release its voucher with nobody acting.
  - Once the invoice has matched, fewer units delivered become a further vendor credit. More units delivered
    become an adjustment: an amount accrued as owed to the vendor that no invoice will match and no payment run
    will pay, listed for accounting.
  - A credit that has not been used is corrected in place. Otherwise more units returned become a further
    credit, and fewer become an adjustment.
- **Every delivery has an accrual row**, empty if nothing was delivered. A correction is only recorded once
  that row exists; if the correction's event is handled first it fails and is retried.
- **Ledger.** Payables publishes `DirectDeliveryCorrectionRecorded` with the change in inventory, in the
  accrual, and in vendor credits, and the ledger posts one balanced entry from it.

## Consequences

- A correction can make an invoice payable without a person looking at it. That is what the business asked for;
  the override and payment run approvals still stand between a voucher and a payment.
- An adjustment has no workflow: nothing resolves or closes it yet.
- A correction after the invoice matched does not reopen the match. If the vendor was paid for units that a
  later correction removes, the vendor credit recovers them from a later payment.
- The receipt cannot be voided as a whole, and a correction cannot move it to another vendor, store, or ticket
  number.
