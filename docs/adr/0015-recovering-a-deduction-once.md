# 15. Recovering a deduction once

Date: 2026-11-17

## Status

Accepted

## Context

Units a short-pay held back can come back to the vendor two ways. The store corrects its receipt, which leaves
an adjustment for the units received after all, and accounting pays it (ADRs 7 and 11). Or the vendor disputes
the deduction and accounting concedes some or all of it (ADR 14). Nothing linked the two, so the same units
could be paid for twice. An adjustment was only an amount, so the system could not tell which items it was
for. The business has decided to cap recovery item by item, and that an adjustment for units a concession
already gave back can only be written off.

## Decision

- **Adjustments keep their lines.** An adjustment now records the items and units it is for, at the ticket's
  cost, as the correction that raised it gave them.
- **What can still be conceded.** For each item on the deduction notice, the units held back less the units
  covered by adjustments on the same ticket for units received after the invoice, valued at the unit cost
  billed. An adjustment covers its units while it is open or paid; one that was written off covers nothing.
  Other items on the ticket, and adjustments of the other kind, change nothing.
- **Conceding.** A dispute is conceded for no more than can still be conceded. To concede units an open
  adjustment covers, accounting writes the adjustment off first. An open dispute shows the amount that can
  still be conceded.
- **Paying an adjustment after a concession.** Paying is refused when what was conceded is more than would be
  left to concede with this adjustment and those already paid on the ticket counted. Other open adjustments on
  the ticket do not stand in the way, so one for items that were never held back is still paid. The refused
  adjustment can only be written off; the store's stock was already corrected by the receipt correction.
- **The cap is on the concession, not on the adjustment.** An adjustment is paid whole or not at all (ADR 11).

## Consequences

- When a concession covered only part of what an adjustment is for, the whole adjustment is refused, and the
  part still owed is settled outside the system.
- The comparison is in units for adjustments and in money for the concession, because a concession is an
  amount, not units (ADR 14). An adjustment is at agreed cost and the deduction at billed cost; the cap uses
  billed cost throughout.
- An adjustment recorded before lines were kept covers nothing. Nothing has been deployed, so there are none.
- A late bill (ADR 12) is not part of this: a ticket with a late bill was not paid short for those units.
