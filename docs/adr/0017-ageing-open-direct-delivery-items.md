# 17. Ageing open direct delivery items

Date: 2026-11-17

## Status

Accepted

## Context

Three kinds of direct delivery item wait for accounting to decide: adjustments (ADR 11), late bills (ADR 12),
and deduction disputes (ADR 14). Each could be listed for one vendor, but nothing showed how long an item had
waited, nothing listed them across vendors, and nothing could remind accounting of one left open. The system
has no email. The business has decided that each kind is overdue after 30 days, with a setting of its own, and
that accounting is reminded by flags on the items, one list across vendors, and gauges an alert can use.

## Decision

- **When the wait starts.** An adjustment waits since the day of the correction that raised it, a late bill
  since the day it was recorded, and a dispute since the day the vendor's claim arrived.
- **Overdue.** An item is overdue once it has waited more than its kind's number of days:
  `retail.payables.ageing.adjustment-days`, `late-bill-days`, and `dispute-days`, each 30 unless configured.
  Overdue is worked out when it is asked for, not stored, so changing a setting changes it at once.
- **On each item.** An open adjustment, late bill, or dispute shows its age in days and whether it is overdue.
  A settled item shows neither.
- **One list.** `direct-delivery-open-items` lists every open item of the three kinds across vendors, the one
  that has waited longest first, with its kind, ticket, amount, age, and overdue flag. It can be narrowed to
  overdue items and to one vendor. The amount is the adjustment's, the late bill's, or the deduction a dispute
  contests.
- **Gauges.** `retail.payables.open-items.open` and `retail.payables.open-items.overdue`, each tagged by kind,
  are refreshed every five minutes (`retail.payables.ageing.monitor-interval`). An alert rule on the overdue
  gauge is the reminder; the system sends nothing itself.

## Consequences

- Nothing is escalated, assigned, or settled automatically when an item is overdue.
- Deduction notices not yet sent are not part of this: a notice waits for a clerk to send it, not for a
  decision. They are still listed with `unsentOnly=true`.
- The list reads the three tables directly, as a read model; it holds no state of its own.
- Alert rules live with the monitoring setup, not in this repository, so the gauges remind nobody until a rule
  is written.
- A dispute recorded late shows the age of the vendor's wait, not the time since it was recorded, so it can be
  overdue on the day it is entered.
