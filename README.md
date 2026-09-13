# Aegis

A private household workspace for documents, tasks, calendars, and a future personal AI assistant.

## Start here

- [Shared engineering instructions](AGENT.md): canonical rules for contributors, Codex, and Claude.
- [Architecture foundations](docs/architecture/FOUNDATIONS.md): DDD, hexagonal architecture, localization, and engineering decisions.
- [Roadmap](ROADMAP.md): product scope, data ownership, and phased acceptance criteria.
- [VM access and setup](docs/VM-ACCESS.md): verified Proxmox development environment.
- [VM bootstrap](ops/bootstrap-vm.sh): reproducible tooling installation inside the dedicated VM.

AGENTS.md and CLAUDE.md reference AGENT.md to avoid competing instruction sets.

## Technical direction

Java 21 and Spring Boot form a modular backend with DDD and hexagonal boundaries, enforced through Maven modules. PostgreSQL stores application data. React/TypeScript provides the web UI. Paperless owns document originals. Calendar, mail, and AI providers connect through adapters.

All repository-maintained code and technical documentation are in English. Product-owned user-facing text supports German and English. User content and source documents retain their original language.

## Current state

Planning, private GitHub access, and VM development tooling are available. The application, database services, external backup, and restore test are not implemented yet.

Source is maintained in this repository; the Aegis VM is the development/test environment. Private keys, tokens, household documents, and service data must not be committed.

## Next milestone

Create the Spring application foundation with PostgreSQL, migrations, architecture checks, and verifiable operation under roadmap phase 1. Mail provider, calendar accounts, and the external backup destination remain to be established.
