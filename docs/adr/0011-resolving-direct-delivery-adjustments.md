# 11. Resolving direct delivery adjustments

Date: 2026-11-16

## Status

Accepted

## Context

A correction to a store's receipt that arrives after the ticket's invoice has matched, or after its credit has
been used, can leave an adjustment: an amount accrued as owed to the vendor that no invoice will match and no
payment run will pay (ADR 7). Adjustments were listed for accounting, but nothing resolved or closed them, so
the accrual never cleared. The business has decided that accounting either pays an adjustment or writes it off,
choosing for each one; that paying needs the vendor's own document and pays the full amount; that an
`Accounting.Manager` decides; and that an adjustment is resolved whole.

## Decision

- **Who.** An `Accounting.Manager`, with a reason. Nobody entered an adjustment, the system raised it, so there
  is no second person to require.
- **Pay.** The vendor is owed the amount and has billed it on a document of its own, a supplemental invoice or a
  debit memo. Accounting enters that document's number and date. A voucher is released for exactly the
  adjustment's amount, due and discounted on the vendor's payment terms from the document's date, and a payment
  run pays it like any other voucher.
- **Write off.** The vendor is not owed the amount. It leaves what is accrued as a favourable purchase price
  variance, as units never billed do (ADR 9). Nothing is paid.
- **Once and whole.** An adjustment is resolved one way, for its whole amount, and is then closed. It cannot be
  split, changed, or reopened.
- **A vendor's document number is used once.** The number an adjustment is paid against must not already be an
  invoice of that vendor, for a purchase order or a delivery ticket, nor the document of another adjustment;
  and it cannot be entered as an invoice afterwards. The one-invoice-per-ticket rule is unchanged: the document
  pays the adjustment, not the ticket.
- **Ledger.** Payables publishes `DirectDeliveryAdjustmentResolved`. The ledger debits accrued direct delivery
  payables for the amount and credits accounts payable when it is paid, or purchase price variance when it is
  written off.
- **Voucher.** The voucher carries the adjustment's identifier where an invoice's would be, and the vendor's
  document number and date as its invoice number and date.

## Consequences

- An adjustment that is paid at a different amount than the vendor's document shows cannot be recorded: the
  amount is the adjustment's. A difference is settled outside the system.
- Stock is not touched by either resolution; the correction already moved it.
- The local profile, with sign-in off, can resolve adjustments, because no second person is required.
- Entering a purchase order invoice now also refuses a number already used for the vendor's direct delivery
  invoices, which ADR 6 intended and only the direct delivery side checked.
- Nothing ages adjustments or reminds accounting of ones left open; they are listed with `openOnly=true`.
