# Retail Operations Platform

A modular monolith for running a multi-store retail and convenience business: store ordering, warehouse fulfillment, purchasing and receiving, direct store delivery, production, inventory, pricing and promotions, fuel, point-of-sale sales import, and a general ledger that serves as the book of record.

**Status:** scaffold. The build, quality gates, and an empty package for every module are in place; business logic follows in the phases described in the PRD.

## Documentation

- [Product requirements](docs/prd.md): scope, architecture, module design, event catalog, accounting, and delivery phases.
- [Architecture decision records](docs/adr): decisions that shaped the code, one file each.

## Getting started

Requires a Java 25 JDK (Eclipse Temurin). Everything else comes from the Gradle wrapper.

```bash
./gradlew build
```

runs compilation, Detekt, ktlint, the tests (including Spring Modulith module verification), and the Kover coverage gate. Any warning or finding fails the build.

```bash
./gradlew :app:bootRun
```

starts the application with the `local` profile on port 8080. It needs Docker: Spring Boot starts the services in `docker/docker-compose.yml` (PostgreSQL 17 and Azurite) and stops them on exit. The `local` profile needs no Azure access. Health is at `/actuator/health`.

Tests that start the application use Testcontainers, so they also need Docker.

```bash
docker build -f docker/Dockerfile -t retail-platform .
```

builds the container image.

### Database migrations

Each module owns one PostgreSQL schema, named after the module. Its Flyway scripts live in `app/src/main/resources/db/migration/<module>/` and are applied to that schema, which keeps its own migration history. To give a module its first table, add `V1__<description>.sql` to its folder; do not qualify table names with the schema.

### What the API covers so far

Master data and base pricing (Phase 1 of the PRD), and the start of procure to pay (Phase 2). Every module follows the same layout; `locations` is the simplest to read first.

| Module | Endpoints under `/api/v1` | Publishes |
| --- | --- | --- |
| `vendors` | `vendors` | `VendorCreated`, `VendorTermsChanged` |
| `locations` | `stores`, `warehouses`, `routes` | `StoreOpened`, `StoreClosed`, `RouteScheduleChanged` |
| `catalog` | `departments`, `units-of-measure`, `items` | `ItemCreated`, `ItemChanged`, `ItemDiscontinued`, `VendorCostChanged` |
| `pricing` | `price-zones`, `retail-prices` | `RetailPriceScheduled`, `PriceChangeEffective` |
| `purchasing` | `purchase-orders`, `purchasing/approval-threshold` | `PurchaseOrderApproved`, `PurchaseOrderSent`, `PurchaseOrderCancelled` |
| `receiving` | `expected-deliveries`, `receipts`, `direct-deliveries`, `direct-deliveries/costs` | `GoodsReceived`, `ReceiptDiscrepancyRecorded`, `DirectDeliveryReceived`, `DirectDeliveryCorrected` |
| `payables` | `payables/accruals`, `payables/accruals/balances`, `payables/invoices`, `payables/vouchers`, `payables/payment-runs`, `payables/payments`, `payables/direct-delivery-accruals`, `payables/direct-delivery-invoices`, `payables/direct-delivery-adjustments`, `payables/vendor-credits` | `AccrualRecorded`, `InvoiceMatched`, `InvoiceMatchException`, `DirectDeliveryInvoiceMatched`, `DirectDeliveryInvoiceMatchException`, `DirectDeliveryCorrectionRecorded`, `PayableVoucherReleased`, `PaymentIssued` |
| `ledger` | `ledger/accounts`, `ledger/posting-accounts`, `ledger/fiscal-periods`, `ledger/journal-entries`, `ledger/trial-balance` | `JournalEntryPosted` |
| `inventory` | `inventory/adjustments`, `inventory/counts`, `inventory/on-hand`, `inventory/movements` | `InventoryAdjusted` |

```bash
curl -X POST localhost:8080/api/v1/stores -H 'Content-Type: application/json' \
  -d '{"number": "0101", "name": "Main Street", "timeZone": "America/Chicago", "openedOn": "2026-12-01"}'
```

Pricing learns about stores and items from their events, so a new store or item can be priced a moment after it is created.

Purchasing learns what each vendor supplies, and at what cost, from the catalog's `VendorCostChanged` event. A purchase order is drafted, approved, and sent; who may approve it is described in [ADR 1](docs/adr/0001-purchase-order-approval.md).

Inventory is an append-only ledger of movements per item and location, warehouse or store, with on-hand and weighted average cost kept as its running total. Stock is counted in the item's base unit, which inventory learns from `ItemCreated`. Goods received at a warehouse dock enter stock at purchase order cost and move the average.

Receiving expects every purchase order that purchasing sends. A clerk checks a delivery in against the order's lines; cases over, short, and damaged are recorded on the receipt and published as a discrepancy.

Vendors that deliver straight to stores have no purchase order. A buyer agrees a cost per unit with the vendor for each store; that cost list is also what allows the vendor to deliver an item there. A store manager checks in the driver's ticket in single units, with what was delivered and what was taken back. Delivered units enter the store's stock and are accrued; returned units leave it and become a vendor credit; see [ADR 5](docs/adr/0005-direct-store-delivery-receipts.md). The vendor sends one invoice per ticket, which is matched two ways: it must bill exactly the items and units the store received, at a unit cost within tolerance of the agreed cost. A buyer who may approve, and did not enter the invoice, can override a price exception. A match releases a voucher, and a payment run deducts the vendor's open credits from what it pays; see [ADR 6](docs/adr/0006-direct-delivery-invoices-and-vendor-credits.md). A manager who checked a ticket in wrongly corrects it by saying what each item should read; stock, the accrual, and credits follow, and an invoice waiting in exception is matched again; see [ADR 7](docs/adr/0007-direct-delivery-receipt-corrections.md). When an invoice bills more than the store received and the store's count stands, an accounting manager pays it short: the units received are paid for and the rest is held back as a deduction against the vendor; see [ADR 8](docs/adr/0008-short-paying-direct-delivery-invoices.md). When an invoice bills less than the store received, and no line bills more, an accounting manager accepts it as billed: it is paid in full, the ticket is closed, and the units never billed are released from the accrual as a favourable variance; see [ADR 9](docs/adr/0009-accepting-under-billed-direct-delivery-invoices.md). An invoice that is over its price as well as over or under on units needs both: the buyer accepts the price first, and accounting then pays it short or accepts it; see [ADR 10](docs/adr/0010-price-acceptance-before-short-pay.md). A correction that arrives after the invoice matched can leave an adjustment: an amount accrued as owed to the vendor with no invoice to match it. An accounting manager settles each one once and whole, either paying it against a document the vendor sent, which releases a voucher, or writing it off as a favourable variance; see [ADR 11](docs/adr/0011-resolving-direct-delivery-adjustments.md). A ticket is invoiced once, so a further bill from the vendor for a ticket whose invoice is already payable is recorded by a clerk as a late bill, with its total. An accounting manager who did not record it either rejects it or accepts it for payment, which releases a voucher and takes the whole amount as a cost variance; see [ADR 12](docs/adr/0012-late-bills-for-closed-delivery-tickets.md). Every short-pay raises a numbered deduction notice that lists the items and units held back. The system sends nothing itself: accounting sends the notice by its usual means and a clerk records when, how, and to whom; see [ADR 13](docs/adr/0013-deduction-notices.md). When the vendor disputes a deduction, a clerk records the claim against the notice, and an accounting manager other than the one who paid the invoice short decides it once: the deduction is upheld, or some or all of it is conceded, which releases a voucher for the conceded amount and takes it as a cost variance; see [ADR 14](docs/adr/0014-deduction-disputes.md). Units a short-pay held back are paid for once: an adjustment keeps the items and units it is for, units an open or paid adjustment covers can no longer be conceded, and an adjustment for units a concession already gave back can only be written off; see [ADR 15](docs/adr/0015-recovering-a-deduction-once.md). Part of an adjustment can be paid: the manager names the units of each item to pay and the rest is written off in the same step; see [ADR 16](docs/adr/0016-paying-part-of-an-adjustment.md). Adjustments, late bills, and disputes that wait for accounting show their age, and are flagged overdue after 30 days; a deduction notice not yet sent is overdue after 7 (`retail.payables.ageing`). One list shows all of them across vendors, oldest first, and can be narrowed to overdue items, one vendor, or some kinds, and the `retail.payables.open-items.*` gauges count the open and overdue ones by kind for alert rules; see [ADR 17](docs/adr/0017-ageing-open-direct-delivery-items.md) and [ADR 18](docs/adr/0018-ageing-unsent-deduction-notices.md).

Payables accrues what is owed for each receipt at purchase order cost (goods received, not invoiced) from `GoodsReceived`, and reports the open balance and its age by vendor.

A vendor invoice is entered against a purchase order and matched three ways at once: the cases it bills must have been received and not yet invoiced, and its case cost must be within tolerance of the purchase order's (`retail.payables.match`). A match relieves the accruals, oldest receipt first; anything else waits as an exception and can be matched again.

A price exception can be overridden by an accounting manager who did not enter the invoice. A matched or overridden invoice releases a payable voucher, due and discounted by the vendor's payment terms. A payment run, proposed by one person and approved by another, pays the vouchers that are due or whose early-payment discount is about to run out, with one payment per vendor; see [ADR 4](docs/adr/0004-match-overrides-vouchers-and-payment-runs.md). Payments are recorded and posted, but no bank file or check is produced yet.

The ledger turns business events into balanced, append-only journal entries through posting rules, one per event type. Goods received debit warehouse inventory and credit accrued payables; a matched invoice debits accrued payables, credits accounts payable, and puts the difference to purchase price variance. A vendor payment debits accounts payable and credits the bank and purchase discounts taken. A rule names the role an account plays, and which account fills the role is data. The fiscal calendar and the seeded accounts are placeholders; see [ADR 2](docs/adr/0002-placeholder-fiscal-calendar-and-accounts.md).

### Event delivery

A listener that fails does not lose its event. Failed publications are retried every minute until the listener completes; a publication left behind by a stopped instance is found stale, marked failed, and retried the same way. Publications incomplete for more than 15 minutes are logged as an error with their listeners, and counted in the `retail.events.publications.*` gauges. Completed publications move to `platform.event_publication_archive` after 30 days. Settings are under `retail.events` and `spring.modulith.events.staleness`; see [ADR 3](docs/adr/0003-event-publication-retry-and-archive.md).

### Sign-in and roles

Outside the `local` profile every request except the health probes needs a Microsoft Entra ID access token (`ENTRA_TENANT_ID`, `ENTRA_API_AUDIENCE`). The token's `roles` claim carries the application roles in `Roles`, and a use case states the role it needs with `@RequiresRole`.

The `local` profile has no sign-in: every request runs as `local-developer` with the roles listed in `application-local.yaml`. The container image defaults to the `prod` profile so it can never start without sign-in by accident.

## Repository layout

- `app/`: the single application module. Each business module is a package under `com.retailplatform` declared in its `package-info.kt`.
- `build-logic/`: Gradle convention plugins (`retail.kotlin-conventions`, `retail.spring-conventions`, `retail.quality-conventions`, `retail.test-conventions`).
- `gradle/libs.versions.toml`: the version catalog, the one place to change versions.
- `config/detekt/`: Detekt rule overrides.
- `docker/`: the Dockerfile and the Docker Compose file for local development.
- `infra/`: Bicep templates for Azure; see `infra/README.md`. Nothing is deployed yet.

## Technology

- Kotlin on Java 25, Spring Boot 4, Spring Modulith 2
- Spring Data JDBC, PostgreSQL, Flyway, Spring Batch
- Azure Container Apps, Key Vault, Blob Storage, Application Insights
- Gradle (Kotlin DSL), GitHub Actions, Docker

## Architecture at a glance

- One deployable, one database, strictly separated business modules verified by Spring Modulith.
- Modules integrate through domain events recorded in the event publication registry.
- Hexagonal layout inside every module; domain code is plain Kotlin.
- Input validation happens only at the edges; business logic receives types that are already valid.
