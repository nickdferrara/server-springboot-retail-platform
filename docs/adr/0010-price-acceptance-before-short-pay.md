# 10. Price acceptance before short-pay

Date: 2026-11-16

## Status

Accepted

## Context

An invoice that bills more than the store received and is also over its price had no way out of exception.
Short-pay refuses it over the price, which is the buyer's to accept (ADR 8). The buyer's override was refused
too, because accepting the price would still leave a quantity difference (ADR 6). ADR 9 let a buyer accept the
price of an invoice that only under-bills and leave the rest to accounting; this does the same for an invoice
that over-bills.

## Decision

- **Override.** A `Buyer.Approver` who did not enter the invoice overrides the price exception as before. The
  override is now also taken when, with the price accepted, the invoice could be paid short. It is kept on the
  invoice, which stays in exception for accounting.
- **Short-pay.** An `Accounting.Manager` who did not enter the invoice then pays it short as in ADR 8, at the
  price billed and accepted: units received or billed, whichever is fewer, are paid at the billed unit cost,
  and the rest of what was billed is the deduction. The short-paid invoice keeps the buyer's acceptance beside
  accounting's approval.
- **Still refused.** Short-pay without the buyer's acceptance while a price is outside tolerance; an override
  when there is no price exception, when the price was accepted before, or when accepting it would leave
  neither a match, nor under-billing, nor a short-pay; and a price in another currency, always.

## Consequences

- Two people decide such an invoice, in a fixed order: the buyer on price, then accounting on quantity.
- The deduction is valued at the billed price the buyer accepted, not at agreed cost, so the vendor is held to
  exactly what it billed for the units that did not arrive.
- The difference between the accepted price and agreed cost on the units paid goes to purchase price variance,
  as for any override.
