# ADR-0001: Backend module layout, framework versions and transaction placement

Status: accepted (implemented in the first runnable test). Date: 2026-09-13.

## Context

FOUNDATIONS.md requires a Maven multimodule modular monolith with DDD and hexagonal boundaries, a plain-Java
domain and application core, and infrastructure-owned transactions. The first runnable test needs the contexts
identity, documents, planning, notifications, operations and portability.

## Decision

### Modules

One Maven module per layer and context (`backend/<context>/{domain,application,adapters}`), plus:

- `shared-kernel`: identifiers (`MemberId`, `DocumentId`, `TaskId`), `Actor`, the `HouseholdAccess`
  authorization contract and the cross-cutting exceptions (`NotFound`, `StaleVersion`, `InvalidInput`,
  `NotAuthorized`). Documented reason: every context needs the same identities and error contract; nothing
  else is shared.
- `bootstrap`: the only Spring Boot application; wiring, security, transactions, migrations location list,
  problem-detail mapping, SPA serving.
- `architecture-tests`: ArchUnit rules over all modules, including fixtures that prove violations are detected.

`operations` and `portability` have no domain module: they coordinate other contexts' public contracts and
own no aggregates.

### Context boundaries

A context may depend on another context only through that context's `application.api` package (public
contracts such as `DocumentReferenceQuery`, `MemberDirectory`, `PublishNotice`). Domain models, repositories
and adapters of other contexts are forbidden; the composition root is exempt. Cross-context reactions (task
assigned, document archival failed) leave a context through an outbound port (`PlanningEvents`,
`DocumentEvents`) that the bootstrap routes to the notifications contract, so the notice is written in the
same transaction as the business change.

### Versions

Java 21, Spring Boot 4.1.1 (Spring Framework 7, Security 7, Jackson 3, Hibernate 7), db-scheduler 16.12.0
(`db-scheduler-spring-boot-4-starter`), springdoc-openapi 3.1.1, ArchUnit 1.5.0, Testcontainers 2.0.5,
PostgreSQL 17, Paperless-ngx 3.1.3 (API version 10), Caddy 2.11. Versions are pinned in `backend/pom.xml` and
`deploy/compose.yaml`; Spring Boot 3.5 was rejected because its OSS support ended in June 2026.

### Transactions

Application services are plain classes. `bootstrap` wraps the mutating use-case interfaces with
`TransactionalProxy` (a JDK proxy around `TransactionTemplate`, propagation REQUIRED). Query services that
call Paperless (`DocumentQueryService`) and the export/restore services are deliberately not wrapped: no
database transaction waits on an external system. Capture is split into a non-transactional file write
(`DocumentCaptureService`) and a transactional registration (`RegisterReceivedDocument`) that stores the record
and schedules the job atomically; db-scheduler is wired with a `TransactionAwareDataSourceProxy` so the job row
joins the same transaction. `AtomicSchedulingIT` proves both the rollback and the commit path against
PostgreSQL rather than assuming the decorator works.

### Persistence

JPA entities live only in `adapters.persistence` packages (enforced by ArchUnit) and are mapped explicitly.
Domain repositories expose aggregate operations; optimistic locking uses `@Version` and an explicit
`expectedVersion` on every shared edit, mapped to HTTP 409 `version.conflict`. Flyway migrations are stored per
context (`db/migration/<context>`) with non-overlapping version prefixes (platform V0, identity V1, documents
V2, planning V3, notifications V4).

## Consequences

- Adding a context means three modules and a migration folder; generic global `controller/service/repository`
  buckets do not exist.
- Records serve as commands, results, value objects and DTOs; aggregates and entities are classes.
- Framework upgrades touch `bootstrap` and adapter modules only; domain and application modules have no
  framework dependency to upgrade.
