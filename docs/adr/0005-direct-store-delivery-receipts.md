# 5. Direct store delivery receipts and agreed costs

Date: 2026-11-13

## Status

Accepted

## Context

Beverage and snack vendors deliver straight to stores. There is no purchase order: the driver fills what has
sold, takes back what is out of date, and leaves a ticket. The PRD asks for the store to record that ticket,
including returns, for the goods to enter store inventory and be accrued, and for returns to become vendor
credits. The business has decided that cost varies from store to store, that deliveries are counted in single
units rather than cases, that store managers check deliveries in, and that a vendor may deliver both to stores
and to the warehouse.

## Decision

- **Agreed cost.** A buyer sets what a vendor charges for one base unit of an item at one or more stores, from
  a date on. Costs are kept in `receiving`, effective dated, and never set in the past. A delivery is costed at
  the cost in effect on the day it is checked in, and keeps that cost.
- **The cost list is also the permission.** A vendor may deliver an item to a store only if it has an agreed
  cost there. Nothing else marks a vendor or an item as direct delivery, so one vendor can serve the warehouse
  through purchase orders and stores directly.
- **Units.** Ticket lines are in the item's base unit, the unit inventory counts it in.
- **Receipt.** A `Store.Manager` checks in a ticket: per item, units delivered and units returned. A vendor's
  ticket number is checked in once per store. The receipt takes its number from the same series as warehouse
  receipts, is never changed, and is announced as `DirectDeliveryReceived` with the agreed costs on it.
- **Inventory.** Delivered units enter the store's stock at agreed cost and move its average. Returned units
  leave as a `VENDOR_RETURN` movement at average cost, like all stock leaving, and may take on-hand below zero:
  store stock has no opening balances and store sales do not relieve it yet.
- **Payables.** Delivered units are accrued per ticket; returned units become a vendor credit that stands on its
  own.
- **Ledger.** One entry per ticket: credit accrued direct delivery payables for what was delivered, debit
  accounts payable for the vendor credit, and put the difference to store inventory. Two placeholder accounts
  are seeded, as in ADR 2; store inventory is one account for every store.

## Consequences

- A manager cannot check in an item the buyer has not costed for that store. The delivery waits until the buyer
  sets the cost.
- Returns reduce inventory at average cost but the ledger at agreed cost, so the inventory ledger and the store
  inventory account can differ by the gap between the two.
- A receipt cannot be corrected yet. Whether a correction is still allowed depends on whether the ticket has
  been invoiced, which only payables knows, and receiving cannot listen to payables.
- Any `Store.Manager` can check in a delivery for any store; users are not yet limited to their own store.
- Receiving checks that the vendor and stores of a cost exist, but not the item. An item unknown to the catalog
  would be refused by inventory after the receipt is saved.
- Nothing matches or pays these accruals and credits yet; the invoice match and deducting credits from payments
  follow.
