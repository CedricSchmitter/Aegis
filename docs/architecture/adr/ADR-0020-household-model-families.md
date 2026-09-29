# ADR-0020: The household chooses a model family per workload; each provider uses its newest model

Status: accepted and implemented for the model-selection part of issue #64. Date: 2026-09-29.

## Context

Until now every workload profile named one model in `application.yml` (`claude-sonnet-5`). Changing it needed a
configuration change and a redeploy, and a new release such as Sonnet 5.5 was not used until someone edited the
file. The owner asked on 2026-09-29 for:

- a dropdown per AI task (classification, review, chat) to choose which model is used;
- one setting for the whole application, not per member;
- the subscription (`claude -p`) always using the newest Opus, Sonnet or Haiku.

Issue #64 already required a server-controlled catalog instead of browser-supplied model ids or prices, optimistic
concurrency, a record of who changed the setting, and that the change affects new runs only.

## Decision

**Members choose a family, not a version.** The dropdown offers Opus, Sonnet and Haiku (`ModelFamily`). There are no
pinned versions: the owner chose families only, so the household never has to maintain version numbers.

**The API follows a server catalog.** `aegis.ai.models` names the current API model of each family
(`claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-4-5` on 2026-09-29) and whether it accepts an effort level.
Every catalog model needs a price in `aegis.ai.prices`, otherwise the application does not start, because a run's cost
budget could not be enforced. When a new model is released, its predecessor is replaced in the catalog and the new
price is added. The Models API is not used to discover newer models automatically: it reports no prices, and an
estimated price would weaken the budgets. Predecessors stay priced so that queued runs and retries can still run.

**The subscription follows Claude Code.** The `claude-code` gateway sends the family alias (`opus`, `sonnet`,
`haiku`) instead of the catalog id, so the CLI uses the newest model of the family it offers, even before the catalog
names it. The run still records the catalog model and prices its API-equivalent estimate with it; the audit step
records the model that actually answered (from the CLI's `modelUsage`).

**One household setting per workload.** `WorkloadModelChoice` (assistant domain) holds workload, family, version,
member and time of the last change. It is stored in `ai_model_choices` only once a member changes it; until then the
profile's `family` in `application.yml` is the default (Sonnet for all workloads). `PUT
/api/ai/workloads/{workload}/model` takes the family and the version the member saw; a stale version is a 409
`version.conflict` and never overwrites the other member's change. Choosing the current family again changes nothing.
The connection check has no model of its own and cannot be chosen.

**Resolved at request time.** A run resolves the family to the catalog model when it is requested and records provider
and model, as before (ADR-0003). A change therefore affects new runs only; queued and running runs keep their model,
and a retry keeps the model of the failed run. Provider preference (API key first, subscription for the listed members
without a key) is unchanged and remains configuration. The connection check checks the currently chosen models.

**Effort only where accepted.** Haiku 4.5 rejects an effort level, so the effort of a profile is sent only to catalog
models that accept one. Models outside the catalog keep the behavior they were requested with.

## Consequences

- Sonnet 5.5 replaces Sonnet 5 for all workloads without a household action. Opus 5.5 is priced at 4 / 20 USD per
  million tokens, Sonnet 5.5 at 2 / 10 USD, Haiku 4.5 at 1 / 5 USD. The per-run budgets and daily limits are
  unchanged, so choosing Opus cannot raise them; an expensive model reaches its budget sooner.
- The status page shows the family, the API model and its prices per workload, the scope of the setting and who
  changed it last. Both members see and can change it.
- A new family (for example a future tier) needs a code change: the enum, the database check constraint and the
  translations.
- Chat's per-conversation override (#64) and a second provider (#35) are not part of this decision.
