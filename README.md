# Retail Operations Platform

A modular monolith for running a multi-store retail and convenience business: store ordering, warehouse fulfillment, purchasing and receiving, direct store delivery, production, inventory, pricing and promotions, fuel, point-of-sale sales import, and a general ledger that serves as the book of record.

**Status:** scaffold. The build, quality gates, and an empty package for every module are in place; business logic follows in the phases described in the PRD.

## Documentation

- [Product requirements](docs/prd.md): scope, architecture, module design, event catalog, accounting, and delivery phases.

## Getting started

Requires a Java 25 JDK (Eclipse Temurin). Everything else comes from the Gradle wrapper.

```bash
./gradlew build
```

runs compilation, Detekt, ktlint, the tests (including Spring Modulith module verification), and the Kover coverage gate. Any warning or finding fails the build.

```bash
./gradlew :app:bootRun
```

starts the application with the `local` profile on port 8080. The `local` profile needs no Azure access. Health is at `/actuator/health`.

## Repository layout

- `app/`: the single application module. Each business module is a package under `com.retailplatform` declared in its `package-info.kt`.
- `build-logic/`: Gradle convention plugins (`retail.kotlin-conventions`, `retail.spring-conventions`, `retail.quality-conventions`, `retail.test-conventions`).
- `gradle/libs.versions.toml`: the version catalog, the one place to change versions.
- `config/detekt/`: Detekt rule overrides.

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
