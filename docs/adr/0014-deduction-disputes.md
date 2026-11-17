# 14. Deduction disputes

Date: 2026-11-17

## Status

Accepted

## Context

A short-pay holds part of an invoice back and raises a deduction notice that tells the vendor what and why
(ADRs 8 and 13). Nothing happened if the vendor disagreed. The business has decided that an `Accounting.Clerk`
records a dispute; that an `Accounting.Manager` other than the one who paid the invoice short decides it; that
accounting may concede any part of the deduction; and that what is conceded is paid from this system and goes
to purchase price variance.

## Decision

- **Recording.** A clerk records the dispute against the deduction notice: the day the vendor's claim arrived,
  the vendor's reference for it, and what the vendor says. A deduction is disputed once. The day must fall
  between the day the notice was raised and today, and the vendor's reference must not have been used for
  another of the vendor's documents, because a concession is paid against it. Whether the notice has been
  recorded as sent does not matter: a vendor can dispute what it sees on its payment.
- **Deciding.** An `Accounting.Manager` other than the person who paid the invoice short, with a reason, once:
  - **Uphold.** The deduction stands in full. Nothing is paid and nothing is posted.
  - **Concede.** An amount more than nothing and no more than the deduction is given back; the rest stands. A
    voucher is released for the conceded amount, due and discounted on the vendor's payment terms from the day
    the claim arrived, and a payment run pays it like any other voucher.
- **Ledger.** Payables publishes `DeductionDisputeConceded`. The deduction was never a liability and the
  ticket's accrual is already relieved, so the ledger debits purchase price variance and credits accounts
  payable for the conceded amount.
- **Stock is not touched.** Conceding is a commercial decision. If the store's count was in fact wrong, the
  store corrects its receipt instead (ADR 7): stock moves, the extra units become an adjustment, and
  accounting pays that (ADR 11). The dispute is then upheld, so the vendor is not paid twice; nothing enforces
  this.
- **The invoice and the notice do not change.** The invoice stays short-paid with its deduction, and the notice
  keeps its lines. What became of the deduction is on the dispute.
- **Voucher.** As for an adjustment or a late bill, the voucher carries the dispute's identifier where an
  invoice's would be, and the vendor's claim reference and the day it arrived as its invoice number and date.

## Consequences

- A concession is an amount, not units: nothing says which items or units were given back.
- A dispute is decided once and cannot be reopened; a vendor that disagrees with the decision is answered
  outside the system.
- A dispute recorded against the wrong notice cannot be withdrawn; it can only be upheld.
- With sign-in off (the `local` profile) a dispute can be recorded but not decided when the same person paid
  the invoice short, which is every case there.
- Nothing ages disputes or reminds accounting of ones left open; they are listed with `openOnly=true`.
