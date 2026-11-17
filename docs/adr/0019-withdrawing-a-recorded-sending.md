# 19. Withdrawing a recorded sending

Date: 2026-11-17

## Status

Accepted. Amends ADR 13, under which a sending was recorded once and could not be corrected.

## Context

An `Accounting.Clerk` records when, how, and to whom a deduction notice was sent (ADR 13). The record could
not be corrected: a wrong date, channel, or recipient stayed, and so did a sending recorded for a notice that
was never sent. Since an unsent notice ages and is flagged overdue (ADR 18), a wrong record also hides a vendor
that has not been told. The business has decided that a wrong sending is withdrawn and recorded again, that
withdrawn sendings are kept, and that a clerk may withdraw.

## Decision

- **Withdraw, then record again.** An `Accounting.Clerk` withdraws the recorded sending with a reason. The
  notice is unsent again: it waits since the day it was raised, shows in the open items list, and is overdue
  on the same rule as before. The right sending is then recorded as usual. There is no editing in place, so a
  notice that was never sent and one sent with a wrong detail are corrected the same way.
- **Kept on record.** Each withdrawn sending stays on the notice with the day it was withdrawn, who withdrew
  it, and why, the earliest first. A notice can be withdrawn and recorded any number of times.
- **Only a recorded sending can be withdrawn.** Withdrawing a notice that has no sending is refused.

## Consequences

- A notice withdrawn long after it was raised is overdue at once, because its wait is counted from the day it
  was raised. That is the point: the vendor has not been told.
- Withdrawing changes nothing else: the deduction, a dispute, and any concession stand.
- Any clerk may withdraw any sending, including one they did not record. The reason and the record are the
  control.
