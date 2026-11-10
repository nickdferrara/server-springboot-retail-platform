# Retail Operations Platform

A modular monolith for running a multi-store retail and convenience business: store ordering, warehouse fulfillment, purchasing and receiving, direct store delivery, production, inventory, pricing and promotions, fuel, point-of-sale sales import, and a general ledger that serves as the book of record.

**Status:** specification. Implementation starts with the Phase 0 foundation described in the PRD.

## Documentation

- [Product requirements](docs/prd.md): scope, architecture, module design, event catalog, accounting, and delivery phases.

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
