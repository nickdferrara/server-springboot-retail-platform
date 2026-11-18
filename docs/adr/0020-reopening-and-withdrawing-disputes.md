# 20. Reopening and withdrawing disputes

Date: 2026-11-18

## Status

Accepted. Amends ADR 14, under which a dispute was decided once and one recorded in error could only be upheld.
Who may reopen and withdraw, and that a conceded dispute stays closed, are working defaults that the business
has not yet confirmed.

## Context

A clerk records a vendor's dispute of a deduction and a manager decides it (ADR 14). Neither step could be
corrected. A dispute recorded against the wrong notice, or under the wrong reference, could only be upheld, which
left a decision on record that nobody made and kept the deduction from being disputed properly. A dispute
upheld by mistake, or before the vendor sent its evidence, stayed upheld, and the vendor was answered outside
the system.

## Decision

- **Withdraw a dispute recorded in error.** An `Accounting.Clerk` withdraws a dispute that still waits, with a
  reason. It no longer waits for accounting: it leaves the open items list and the gauges, and shows no age.
- **Kept on record.** A withdrawn dispute stays, with the day it was withdrawn, who withdrew it, and why. It is
  listed with the vendor's other disputes and found by its identifier, but not through its notice.
- **The deduction can be disputed again.** A notice has one dispute that stands at a time. After a withdrawal the
  dispute is recorded again as usual, and the vendor's reference on the withdrawn one is free to use again.
- **Reopen a dispute that was upheld.** An `Accounting.Manager` other than the one who paid the invoice short
  reopens it with a reason. It waits again, counted from the day the vendor's claim arrived as before, and is
  then upheld or conceded as usual. The decision set aside stays on the dispute with who reopened it, when, and
  why, the earliest first. A dispute can be upheld and reopened any number of times.
- **A conceded dispute is neither reopened nor withdrawn.** Its voucher has been released and the ledger has
  taken the variance, and nothing here takes those back.
- **A decided dispute is not withdrawn.** One that was upheld against the wrong notice is reopened first and
  then withdrawn.

## Consequences

- A dispute reopened long after the claim arrived is overdue at once. That is the point: the vendor has waited
  that long.
- The manager who upheld a dispute may reopen it; only the person who paid the invoice short may not. The reason
  and the record are the control.
- Any clerk may withdraw any waiting dispute, including one they did not record, as for a recorded sending
  (ADR 19).
- A concession made in error, or one the vendor says is too small, is still settled outside the system.
- Withdrawing changes nothing else: the notice, its sending, and the deduction stand.
