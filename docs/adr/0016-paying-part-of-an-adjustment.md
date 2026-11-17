# 16. Paying part of an adjustment

Date: 2026-11-17

## Status

Accepted. Amends ADR 11, which resolved an adjustment whole, and removes a consequence of ADR 15.

## Context

An adjustment was paid whole or written off whole (ADR 11). When a concession had already given back some of
the units an adjustment is for, paying it was refused outright, and the part still owed had to be settled
outside the system (ADR 15). The business has decided that part of an adjustment can be paid, that accounting
says which part in units of each item, and that the rest is written off at once.

## Decision

- **Naming the part.** When paying an adjustment, an `Accounting.Manager` may name the units of each item to
  pay: items the adjustment is for, each once, at least one unit and no more than the adjustment has. Naming
  nothing pays the whole adjustment, as before; naming every unit is the same.
- **What is paid.** The units named, at the adjustment's cost. The voucher is for that amount, against the
  vendor's document, on the vendor's payment terms.
- **The rest is written off in the same step.** The adjustment is still settled once and then closed; one
  reason covers both. Its status stays `PAID`, with the amount paid, the amount written off, and the lines
  paid.
- **Ledger.** `DirectDeliveryAdjustmentResolved` now carries the amount written off alongside a payment. The
  ledger debits accrued direct delivery payables for the whole adjustment, credits accounts payable for what
  is paid, and credits purchase price variance for the rest.
- **Recovery.** Only the units paid count as covering units of a deduction (ADR 15). So when a concession gave
  back some of the units, accounting pays the others and the payment is accepted; paying units the concession
  already covered is still refused.

## Consequences

- An adjustment recorded before lines were kept has no units to name and can only be paid whole or written
  off.
- The part is in units, not money: an amount that is not a whole number of units at the adjustment's cost
  cannot be paid. A difference from the vendor's document is still settled outside the system.
- The rest cannot be left open to decide later.
