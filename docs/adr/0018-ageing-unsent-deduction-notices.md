# 18. Ageing unsent deduction notices

Date: 2026-11-17

## Status

Accepted. Amends ADR 17, which left unsent deduction notices out of ageing.

## Context

A deduction notice is raised when an invoice is paid short and waits for a clerk to send it to the vendor
(ADR 13). ADR 17 aged the items that wait for a manager's decision and left notices out, because sending a
notice is not a decision. A notice left unsent still means a vendor that has not been told why it was paid
less. The business has decided that an unsent notice is overdue after 7 days and that it shows up in the same
list and gauges as the other items.

## Decision

- **A fourth kind.** An unsent deduction notice is an open item of kind `NOTICE`. It waits since the day it was
  raised and stops waiting when it is recorded as sent.
- **Overdue sooner.** A notice is overdue once it has waited more than
  `retail.payables.ageing.notice-days`, 7 unless configured. Sending is a clerical step and the vendor should
  hear before its next payment.
- **Same list and gauges.** Unsent notices appear in `direct-delivery-open-items` with the other kinds, and in
  the `retail.payables.open-items.open` and `.overdue` gauges under `kind=notice`. The amount shown is the
  deduction the notice tells of.
- **On each notice.** A notice that has not been sent shows its age in days and whether it is overdue; one that
  has shows neither.

## Consequences

- The open items list now mixes work for two roles: a clerk sends notices and a manager decides the rest. The
  kind tells them apart; the list cannot be narrowed by kind.
- A short-paid invoice whose deduction is disputed before its notice is sent appears twice in the list, once
  for each.
- As with the other kinds, the gauges remind nobody until an alert rule is written.
