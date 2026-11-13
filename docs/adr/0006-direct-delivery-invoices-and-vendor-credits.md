# 6. Direct delivery invoices and vendor credits in payments

Date: 2026-11-13

## Status

Accepted

## Context

A direct store delivery is accrued when the store checks in the driver's ticket, and what the driver took back
is a vendor credit (ADR 5). The PRD asks for the vendor's invoice to be matched two ways, to the store receipt
for quantities and to the agreed cost list for prices, with differences going to an exception queue for the
buyer, and for returns to be netted against what the vendor is paid. The business has decided that there is one
invoice per delivery ticket, matched by vendor, store, and ticket number; that a credit stands on its own and is
deducted from the vendor's next payment; that price tolerance is the same as for warehouse invoices and
quantity must equal the receipt exactly; and that a `Buyer.Approver` who did not enter the invoice may override
a price exception.

## Decision

- **Invoice.** An accounting clerk enters the vendor's invoice for a ticket: units and unit cost by item. It is
  a separate aggregate from a purchase order invoice, because it is keyed by ticket and item, not by purchase
  order line. A ticket is invoiced once, and a vendor's invoice number is entered once across both kinds.
- **Two-way match.** The invoice matches when it bills exactly the items and units the store received on the
  ticket, each at a unit cost within tolerance of the agreed cost the receipt carries. Anything else is an
  exception: the ticket was not checked in, an item is not on the ticket, an item on the ticket is not billed,
  the units differ, or the price is over tolerance. An invoice entered before the store checks in the ticket
  waits and is matched again.
- **Override.** Only a price exception can be overridden, by a `Buyer.Approver` other than the person who
  entered the invoice, with a reason. Quantities are never overridden.
- **On a match** the ticket's accrual is relieved whole, the difference from agreed cost goes to purchase price
  variance, and a voucher is released on the vendor's payment terms, exactly as for a warehouse invoice. A
  voucher can now pay an invoice with no purchase order.
- **Credits in payment runs.** When a run is proposed, for each vendor it pays it deducts that vendor's open
  credits, oldest first, each one whole and only while it fits within what the run pays the vendor. A credit
  that does not fit waits for a later run. Credits are reserved with the run, given back if it is cancelled,
  and marked applied to the payment when it is approved.
- **Ledger.** A matched direct delivery invoice debits accrued direct delivery payables and credits accounts
  payable, with the difference to purchase price variance. A payment debits accounts payable for what its
  vouchers owed less the credits deducted, because each credit already reduced accounts payable when it was
  recorded.

## Consequences

- A vendor that is owed less than it has in credits keeps a credit balance until enough is owed; nothing bills
  the vendor for it. A credit is never split across payments.
- A ticket that only returned product has a credit and no accrual, so there is nothing to invoice.
- A quantity difference can only be resolved by correcting the receipt, which is not built (see ADR 5), or by
  the vendor sending a corrected invoice, which cannot be entered while the first is on file.
- Direct delivery variances share the purchase price variance account with warehouse invoices.
- With sign-in off (the `local` profile) an override is refused, as for warehouse invoices.
