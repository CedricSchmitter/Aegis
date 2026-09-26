# ADR-0018: The status page answers "is everything OK?" first, in stable codes

Status: accepted and implemented for issue #83 (finding F4). Date: 2026-09-26.

## Context

The household walkthrough of issue #83 (2026-09-25, phone width, German) found the status page read as a list of
errors by household members:

- Calendar, mailbox, push and Telegram were listed as "Nicht eingerichtet" although none of them is planned soon.
- After every restart or deployment AI showed "Eingeschränkt" / "Degraded" until somebody ran a paid connection check
  ([ADR-0003](ADR-0003-bounded-ai-execution.md) makes a model unverified after process start).
- Integration detail lines were raw English backend strings in the German UI, for example
  `no calendar provider connected; reads and writes are deferred` or the export generation with its UUID.
- The database showed "Verbunden" next to "Zuletzt erfolgreich: Noch nie", because the live probe never recorded a
  success.
- Backup and storage, processing counters, four recurring jobs and a long AI section with workloads and budgets
  followed. The owner needs them; the other members do not.

The owner decided on 2026-09-25 to change the page as proposed. Navigation stays with #27.

## Decision

1. **An overview first.** `GET /api/status` returns `checks`: one line per area that answers "is everything OK?",
   in this order: `database`, `archive` (Paperless), `backup`, `storage`, `ai`, and `jobs` only while a recurring job
   does not run as planned (`FAILING`, `STALE`, `NOT_SCHEDULED` or `UNKNOWN`, the states that raised the top warning of
   [ADR-0013](ADR-0013-recurring-job-health.md)). Each line has a state `OK`, `UNCHECKED` or `PROBLEM`, a stable reason
   code such as `backup.failed`, stable parameters such as `failureCode`, `disks`, `jobs`, `provider` or `failure`, and
   when the area last worked, if known. The API carries no free-text detail; the web app localizes the codes in German
   and English (FOUNDATIONS.md, localization contract) and uses generic words for a code it does not know.
2. **Operations owns the mapping.** Probes implement the operations port `HealthCheckSource` (database, archive, AI).
   The application service adds the backup and storage lines from the storage health
   ([ADR-0015](ADR-0015-backup-and-storage-health.md)) and the jobs line from the job health; the mapping lives in
   `StatusOverview` and is covered by plain unit tests. A probe that throws becomes `probe.failed` for its area without
   the exception message.
3. **Not set up means not listed.** A probe returns nothing when its integration is not set up. AI without any provider
   credentials is therefore left out, and the fixed "not connected" entries for calendar, mail, Web Push and Telegram are
   removed. A future adapter adds its own probe and appears once it is configured. The backup is not optional: without
   a verified destination it is listed as the problem `backup.notConfigured`.
4. **Unchecked is neutral.** A configured AI model without a finished run since the application started or its
   credentials changed is `UNCHECKED` ("Noch nicht geprüft" / "Not checked yet"), not a problem. The next AI run or a
   manual connection check verifies it; Aegis does not start a paid check by itself. Missing credentials of a selected
   provider and a latest run that failed for a provider reason stay problems.
5. **The database reports its last success.** A valid connection is a success at the time of the probe; the latest one
   is kept in memory, so an outage says when the database last answered since the application started.
6. **Technical details are collapsed.** Document processing counters, backup and disk details, recurring jobs, the AI
   section with workloads, budget, runs and the connection check, exports with their generations and the running
   version sit in one collapsed "Technische Details" / "Technical details" section. The separate export entry in the
   overview is gone; the export list shows the generations.
7. **Removed contract fields.** `integrations` with its free-text `detail` and the states `CONNECTED`, `DEGRADED`,
   `UNAVAILABLE`, `NOT_CONFIGURED` are replaced by `checks`. The integration list's `externalBackup` entry of ADR-0015
   is gone; the backup line takes its place. The documents contract `ArchiveHealth` no longer carries the Paperless
   version or error text.

## Consequences

- After a deployment AI reads "Noch nicht geprüft" until the next classification run or a connection check, which
  costs a few cents only when somebody starts it.
- The watches and notices of ADR-0014 and ADR-0015 are unchanged: they evaluate the job and storage health themselves,
  not the overview, and their notices still link to the status page.
- A problem in the collapsed details also shows as a line in the overview (backup, storage, jobs, AI), so collapsing
  hides no incident.
- Navigation, colours and layout are unchanged; the status entry moves with #27.
- Tests: `StatusOverviewTest` and `IntegrationStatusServiceTest` (backup, storage and job lines, hidden integrations,
  probe failure, order), `AiStatusSourceTest` (not set up, unchecked, missing credentials, provider failure),
  `ApiFlowIT` and `AiRunIT` (the contract against PostgreSQL), `status.test.ts` (every backend reason code has German
  and English text; fallbacks; lists) and the browser smoke test.
