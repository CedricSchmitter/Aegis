# ADR-0007: Contract facts are observations with provenance, not contract fields

Status: accepted (first slice implemented for issue #22). Date: 2026-09-20.

## Context

[FOUNDATIONS.md](../FOUNDATIONS.md) has listed a Contracts context ("contract facts and validity/review state",
integration boundary "documents application contract") as a described boundary since the first module split. It
had no module. Issue #63 needs one: it turns a confirmed cancellation deadline into a source-linked task with a
seven-day reminder and must work without AI, and it consumes those facts "through the public application contract
from #22".

The obvious shape - a `contracts` table with `end_date`, `notice_period` and `amount` columns - cannot carry what
this household actually has:

- The same fact arrives from several documents. A contract states an amount, an amendment raises it a year later,
  an invoice shows a third number. Overwriting a column loses which document said what.
- A value can be a proposal. Automatic extraction (#23/#24) produces candidates that a member has to accept;
  writing them into the same column as a confirmed value makes an unreviewed guess indistinguishable from a
  decision - and a wrong cancellation deadline is a real loss, not a display bug.
- A value is only true for a period. An amendment does not make the original wrong; it takes over from its own
  start date, and both have to stay readable.
- A deadline may follow from rules rather than being stated. Notice period and contract end are two facts whose
  combination produces a third value that is not itself a fact.

## Decision

### 1. A fact is an observation, and observations are append-only

`Contract` holds a list of `ContractFact`. Each fact is one observed statement: a typed value for a
`ValidityPeriod`, with its `FactProvenance` (origin, source document, optional page and quote, reported
confidence, run reference, who observed it and when) and a `ReviewState`.

Nothing edits a value in place. A correction records a new confirmed fact that supersedes the corrected one, so
the value a member saw when they decided stays readable, and `supersededBy` / `corrects` record the relationship.
This is the observation and confirmation history the issue asks for; no separate history table exists, because
the facts themselves are the history.

### 2. Only a member's decision makes a fact effective

`ReviewState` is `EXTRACTED`, `CONFIRMED`, `REJECTED` or `SUPERSEDED`, and only `CONFIRMED` is effective. The
aggregate enforces the asymmetry between members and machines in two operations:

- `enter` is a member's statement. It is confirmed at once, because a member decided it.
- `observe` is an extraction run's proposal. It is always stored as `EXTRACTED`, whatever its confidence, and it
  never changes an existing fact. When it contradicts a confirmed fact, the outcome says so and the contradiction
  becomes a conflict for review; when it repeats a known value, nothing is stored at all, so reprocessing the same
  document stays idempotent.

A rejected value is kept rather than deleted, so a later run that proposes it again is visibly the same rejected
value and not new knowledge.

### 3. Validity periods separate amendments from corrections

A newly confirmed fact supersedes confirmed facts of the same type **with exactly the same validity period**. A
fact with a different period amends instead: `effective(type, on)` takes the confirmed facts covering that day and
prefers the one whose validity starts latest. The original stays `CONFIRMED` and readable for the days before.

When two confirmed facts cover the same day with the same start and different values, there is no rule that can
decide between them. `effective` returns nothing and `conflicts(on)` reports the disagreement. A contradicting
invoice therefore never overwrites a confirmed amount, and it never silently wins either.

### 4. Deadlines are calculated from confirmed rules only, or not at all

`CancellationDeadlinePolicy.calculate(contract, on)` returns either `Determined` - the deadline, the known
contract end, which rule it used (`STATED_DEADLINE` or `NOTICE_PERIOD_BEFORE_END`) and the facts it rests on - or
`Insufficient`, naming the missing and the contradicting fact types. There is no estimate in between.

The deadline is a **contractual** date. `Determined.reminderDate(leadDays)` counts back from the deadline, not
from the contract end, and planning owns planned work dates; rescheduling work never moves this date. All dates
are household local dates (`LocalDate`) from an injected `Clock` in `aegis.household.zone`; the evaluation day is
a parameter of the public contract, so a caller catching up after downtime decides which day it calculates for.

### 5. Values are typed, with one canonical written form

`FactType` fixes the `ValueKind` of its value, so a notice period cannot be stored as free text. `FactValue` is a
sealed type (text, date, money, period, flag) and every value has a canonical, locale-independent text
(`2028-01-01`, `P3M`, `29.90 EUR`, `true`) used by the API, the export and the readable CSV. Persistence keeps the
typed column of the kind - `NUMERIC(19,4)` plus an explicit currency for money - rather than flattening everything
into text. Presentation is localized at the boundary; the stored value never is.

### 6. Provenance is what the source says, never what Aegis guesses

A `SourceReference` carries the document id and, only when the source states it, a page and a quoted passage. The
domain never derives a page number, a page without a document is rejected, and an extracted fact must name the
document it was read in - in the aggregate and again as a database constraint. Documents are reached through
`DocumentReferenceQuery`, the documents application contract; `contract_facts.source_document_id` therefore has no
foreign key to the documents table, which stays owned by that context.

## Consequences

- `backend/contracts/{domain,application,adapters}` exist and are enforced by the architecture tests like every
  other context: plain-Java core, dependencies pointing inward, other contexts reachable only through
  `application.api`, JPA entities private to the persistence adapter.
- Issue #63 has the contract it needs: `ContractDeadlineQuery` yields contract identity, name, counterparty,
  contract end, the confirmed deadline with its basis and source document, contract status and the contract
  version to reconcile against.
- Issues #23 and #24 have `RecordContractObservation` and need no new state: an extraction run proposes, a member
  decides. Nothing in this slice calls a model.
- The export gains `data/contracts.json` and `readable/contracts.csv` (schema version 5) with every observation,
  its provenance and the supersede/correct relationships; a restore brings the fact history back under the same
  ids and leaves an existing contract untouched.
- Contract facts are stored per contract, not per document. A document that states facts about several contracts
  is represented by several contracts referencing it, which the source list of each contract shows.
- The first slice ships the domain, the public contract, persistence and the manual REST surface. The web UI for
  inspecting and confirming facts, the automatic extraction that fills them and the task/reminder workflow are
  separate issues (#22 follow-up, #23/#24, #63).
