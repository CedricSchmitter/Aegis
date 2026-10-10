<p align="center">
  <img src="frontend/public/icons/favicon.svg" alt="Aegis logo" width="120" height="120">
</p>

<h1 align="center">Aegis</h1>

<p align="center">
  A self-hosted household workspace for shared documents, contracts and tasks.
</p>

<p align="center">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue" alt="License: PolyForm Noncommercial 1.0.0"></a>
</p>

---

## About

Aegis keeps a household's paperwork in one place and makes sure nothing important slips through. Two people share
one household with individual logins, mostly from their Android phones. Documents are archived in
[Paperless-ngx](https://github.com/paperless-ngx/paperless-ngx); Aegis adds folders, labels, contracts with
cancellation deadlines, tasks and a daily overview on top. AI is optional: storage, search, tasks and deadline
calculation work without it.

## Features

- **Capture everything.** Upload PDFs and photos, scan paper with the phone camera or share files from Android. An
  upload is confirmed only once it is stored durably.
- **Find it again.** Folder tree, structured labels, faceted and full-text search, readable filenames.
- **Never miss a deadline.** Contract facts with provenance, cancellation deadlines calculated from confirmed rules,
  reminders and a Today view.
- **One inbox.** Review cards and notices for everything that needs a decision.
- **Optional AI.** Automatic classification and folder placement, and a household assistant that answers questions
  with checked citations, all within household-set cost limits.
- **Recoverable by design.** Trash with undo, portable exports, encrypted daily backups and a status page that says
  whether everything is OK.
- **German and English** UI, installable as a web app.

See [Features](docs/FEATURES.md) for the full description of current behavior.

## Tech stack

| Area     | Technology                                                                 |
| -------- | -------------------------------------------------------------------------- |
| Backend  | Java 21, Spring Boot, Maven multimodule, DDD and hexagonal architecture    |
| Frontend | React, TypeScript, Vite                                                    |
| Data     | PostgreSQL, db-scheduler jobs, Paperless-ngx for document originals       |
| AI       | Claude API or Claude Code subscription, behind a replaceable adapter       |
| Runtime  | Docker Compose, Caddy, Restic backups                                      |

## Quick start

Requirements: Java 21, Node 24 and Docker for the integration tests.

```bash
# Backend: unit, architecture and PostgreSQL integration tests
cd backend && ./mvnw verify

# Frontend: checks, then dev server with API proxy to http://localhost:8080
cd frontend && npm ci && npm run check && npm run dev
```

Building, local runs, live tests and CI are described in [Development](docs/DEVELOPMENT.md). Running Aegis on a
server is covered by [Deployment](docs/operations/DEPLOYMENT.md).

## Documentation

- [Development](docs/DEVELOPMENT.md): repository layout, build, tests and CI
- [Features](docs/FEATURES.md): what Aegis does today, in detail
- [Architecture foundations](docs/architecture/FOUNDATIONS.md) and [decision records](docs/architecture/adr/README.md)
- [Deployment](docs/operations/DEPLOYMENT.md), [household access](docs/operations/ACCESS.md),
  [backup and restore](docs/operations/BACKUP.md), [VM access](docs/VM-ACCESS.md)
- [AGENT.md](AGENT.md): engineering rules for contributors, Codex and Claude

## Contributing

Ideas, bugs and planned work live in [GitHub Issues](https://github.com/CedricSchmitter/Aegis/issues). Changes reach
`main` through pull requests; please read [AGENT.md](AGENT.md) first.

## License

Aegis is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). Personal, hobby and other
noncommercial use, changes and redistribution are permitted; commercial use requires a separate license from the
copyright holder. Third-party components keep their own licenses. Paperless-ngx (GPL-3.0) is a separate service that
Aegis reaches only through its HTTP API; it is not part of this repository.

---

<sub>**Disclaimer:** Aegis is 100% vibe-coded. Every line was written by AI agents while a human pointed at things
and said "no, not like that". It has more ADRs than most startups and more tests than it strictly deserves. It
works on our phones. Whether it works on yours is between you and your LLM.</sub>
