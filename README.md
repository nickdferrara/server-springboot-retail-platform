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
| `receiving` | `expected-deliveries`, `receipts` | `GoodsReceived`, `ReceiptDiscrepancyRecorded` |
| `inventory` | `inventory/adjustments`, `inventory/counts`, `inventory/on-hand`, `inventory/movements` | `InventoryAdjusted` |

```bash
curl -X POST localhost:8080/api/v1/stores -H 'Content-Type: application/json' \
  -d '{"number": "0101", "name": "Main Street", "timeZone": "America/Chicago", "openedOn": "2026-12-01"}'
```

Pricing learns about stores and items from their events, so a new store or item can be priced a moment after it is created.

Purchasing learns what each vendor supplies, and at what cost, from the catalog's `VendorCostChanged` event. A purchase order is drafted, approved, and sent; who may approve it is described in [ADR 1](docs/adr/0001-purchase-order-approval.md).

Inventory is an append-only ledger of movements per item and location, warehouse or store, with on-hand and weighted average cost kept as its running total. Stock is counted in the item's base unit, which inventory learns from `ItemCreated`. Goods received at a warehouse dock enter stock at purchase order cost and move the average.

Receiving expects every purchase order that purchasing sends. A clerk checks a delivery in against the order's lines; cases over, short, and damaged are recorded on the receipt and published as a discrepancy.

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
