# ADR-0027: The household sets a daily and a session limit per workload

Status: accepted and implemented. Date: 2026-10-04.

## Context

The AI limits were configuration only (ADR-0003): one daily cost limit per provider (`daily-cost-limit` 0.25 USD for
the API, `provider-limits` 50 USD for the owner's subscription), shared by all workloads, and a cost budget per run
from the workload profile (`max-cost`) or the provider (`max-run-cost`). Changing them needed an edit of `.env` and a
container restart. A chat question failed with "ein Kosten- oder Nutzungslimit der KI ist erreicht", and the owner
asked on 2026-10-04 to set, for each of the three areas (classification, chat, the scheduled review), not only the
model (ADR-0020) but also the daily limit and the session limit in the app, with fields that accept money amounts
only.

## Decision

**Two limits per workload, set by the household.** `WorkloadCostLimits` (assistant domain) holds, per selectable
workload (classification, review, chat; not the connection check), a daily limit and a session limit with version,
member and time of the last change. The session limit is the cost budget of one run: one question, one document
classification, one review. Both are set together through `PUT /api/ai/workloads/{workload}/limits` with the version
the member saw; a stale version is a 409 `version.conflict`, setting the current limits again changes nothing. The
setting applies to both members and to scheduled work, like the model choice.

**Money amounts only.** A limit is a positive amount in the AI currency (USD) in whole cents (the currency's minor
unit) of at most 1000; the session limit is at most the daily limit, because a larger one could never be used. The
domain refuses anything else with stable codes (`ai.limits.notPositive`, `ai.limits.tooPrecise`, `ai.limits.tooHigh`,
`ai.limits.runAboveDaily`); a body that is not a number is a 400. The status page fields accept only digits with one
decimal separator (comma or point) and two decimals while typing or pasting, open the decimal keyboard on the phones,
and explain the same rules before anything is sent.

**Defaults until changed.** A workload without stored limits (`ai_cost_limits` has no row) keeps the configured
behavior: the provider's daily limit shared with the other workloads without own limits, and the profile's or
provider's run budget. Nothing changes for an installation until a member saves limits.

**Own limits replace the configured ones.** Once set, the session limit replaces the run budget of the profile and of
the provider (including the subscription's 0.50 USD), and the daily limit replaces the provider's daily limit for that
workload. Daily limits keep counting per provider (ADR-0003), so the subscription's API-equivalent estimates never use
up the API's money: a workload's own daily limit counts only its own model calls on the provider of the run, and the
shared limit counts only the calls of the workloads without own limits. Raising one area's limit therefore never
blocks or releases another area.

**When it applies.** The session limit is resolved when a run is requested and recorded with it, so queued and running
runs keep their budget and a retry uses the current one. The daily limit is read before every model call, so it applies
to the next call of every run. A run that already waits for the next budget day keeps waiting until then; new requests
use the new limit at once.

## Consequences

- The status page shows per workload the model, the daily limit with today's spending under it, the session limit,
  whether own limits or the defaults apply and who changed them last. The general line shows the shared daily limit
  and the spending of the workloads that still use it.
- The configured `daily-cost-limit`, `provider-limits` and `max-cost` remain the defaults and the limits of the
  connection check. The household can raise a limit up to 1000 USD per workload and day; the provider's billing
  controls stay authoritative (no auto-reload).
- Stored limits in another currency than the configured one are ignored, so a currency change falls back to the
  defaults instead of mixing currencies.
- Raising a daily limit does not wake runs already waiting for the next budget day; cancelling and retrying such a run
  starts it under the new limit.
