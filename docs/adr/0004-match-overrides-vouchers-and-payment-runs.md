# 4. Match overrides, vouchers, and payment runs

Date: 2026-11-12

## Status

Accepted. The defaults marked below wait for the business to confirm them.

## Context

A matched vendor invoice was the end of procure to pay: accounts payable was credited and nothing paid it. The
PRD asks for a payable voucher released only after a successful three-way match or an approved override by
someone other than the invoice's creator, and for payment runs that select released vouchers by due date and
terms, take early-payment discounts when they are worth taking, and need approval before release. It leaves open
who resolves match exceptions, and the bank formats for ACH, checks, and positive pay.

## Decision

- **Override.** An invoice in exception because a case cost is outside tolerance can be accepted by an
  `Accounting.Manager` who did not enter it, with a reason. It then counts as matched: the accruals are relieved
  at purchase order cost and the whole difference goes to purchase price variance. An invoice that bills cases
  not received, or in another currency than the order, cannot be overridden; it waits for the goods and is
  matched again. Paying for goods that have not arrived needs vendor credits, which are not built.
- **Voucher.** One voucher per invoice, released in the transaction that matches the invoice, so nothing can be
  paid that did not pass the match or an override. Its due date and discount come from the vendor's payment
  terms, which payables keeps from `VendorCreated` and `VendorTermsChanged`: an invoice uses the terms in effect
  on its invoice date. A vendor whose terms have not reached payables is paid net 30
  (`retail.payables.default-net-days`, a default to confirm).
- **Payment run.** A clerk proposes a run with a payment date and a pay-through date, normally the day of the
  next run. It selects every released voucher due on or before the pay-through date, and every voucher whose
  discount is still available on the payment date and would run out by the pay-through date. A discount is taken
  whenever it is available on the payment date; there is no cost-of-money test. Selected vouchers are locked with
  their rows and reserved, so two runs cannot pay one voucher.
- **Approval.** An `Accounting.Manager` other than the proposer approves the run. Approval issues one payment per
  vendor and currency, dated the run's payment date, and marks the vouchers paid. A run whose payment date has
  passed cannot be approved, because its discounts were worked out for that day; it is cancelled and proposed
  again. Cancelling gives the vouchers back.
- **Ledger.** `PaymentIssued` debits accounts payable for what the vouchers owed, credits the bank for what was
  paid, and credits purchase discounts taken. Two placeholder accounts are seeded for it, as in ADR 2.
  `PayableVoucherReleased` posts nothing: accounts payable was credited when the invoice matched.

## Consequences

- A payment exists in the books but leaves no instruction for the bank: there is no ACH file, check, or positive
  pay file, and a payment cannot be voided. The bank formats are open questions in the PRD.
- With sign-in off (the `local` profile) every request is one user, so a payment run can be proposed and
  cancelled but not approved, and an override is refused. The same already holds for purchase orders over the
  approval threshold.
- A run pays everything that is due; a voucher cannot be held back or picked by hand.
- Terms that change after a voucher is released do not change that voucher.
