# ADR-0003: Bounded, replaceable AI execution

Status: accepted (implemented for issue #21). Date: 2026-09-18.

## Context

Issue #21 asks for one application-owned boundary through which every AI workload runs: automatic document
classification (#23, lightweight model), scheduled document review with task creation (#24, stronger model) and
source-grounded chat (#25). AGENT.md and FOUNDATIONS.md require that AI stays optional for capture, search, tasks and
deterministic planning; that provider SDK types stay in adapters; that durable work uses db-scheduler with bounded
retries and at-least-once semantics; that document and mail text is untrusted; and that every entry point uses the
same application authorization.

## Options evaluated

Checked on 2026-09-18 against the current Anthropic documentation (Claude Code "Legal and compliance" and
"Run Claude Code programmatically", the Claude API models overview and structured outputs pages) and Maven Central.

| Criterion | Claude Code CLI, headless (`claude -p`) | Claude Agent SDK | Claude API (Messages) through the official Java SDK |
|---|---|---|---|
| Runtime in Aegis | Separate CLI binary in the application image, one subprocess per run | Python or TypeScript library bundling the Claude Code harness; no Java SDK, so a second service | In-process library `com.anthropic:anthropic-java` 2.63.0 |
| Authentication in Aegis | Owner-supplied OAuth credential through `claude setup-token`; `--bare` mode reads only `ANTHROPIC_API_KEY`. | Console API key or cloud provider authentication | Console API key, billed to the key owner |
| Tool surface | Built-in Bash, file read/write/edit, web tools; each must be denied per run; Aegis tools only via MCP | Same built-in tools plus MCP and permission callbacks | Only the tools Aegis declares; no shell, file or network tool exists |
| Budgets | `--max-turns`, client-side cost estimate in the JSON result, process timeout | Turn and budget options, client-side cost estimate | Aegis enforces time, cost from reported usage, model calls, tool calls, attempts and concurrency; SDK retries disabled |
| Structured output | `--json-schema` | Supported | `output_config.format` with a JSON schema (supported by Claude Haiku 4.5, Sonnet 5, Opus 5) |
| Fit | Built for coding and file-system agents; the shell surface contradicts least privilege | Adds a runtime and a service boundary for the same capabilities | Classification is one structured call; review and chat are bounded tool loops over Aegis tools |

Current Claude API models and list prices per million input/output tokens (2026-09-18): Claude Haiku 4.5
(`claude-haiku-4-5`, 1/5 USD, 200k context, retirement not sooner than 2026-10-15), Claude Sonnet 5 (`claude-sonnet-5`,
2/10 USD), Claude Opus 5 (`claude-opus-5`, 5/25 USD), Claude Fable 5.1 (`claude-fable-5-1`, 10/50 USD). Batch requests
cost 50 %, cache reads 10 % of the input price.

## Decision

### Provider and runtime

All AI calls go through the provider-neutral `ModelGateway` port; Aegis owns the loop, the tools and every limit.
Two gateways exist, selected per workload (`AEGIS_AI_PROVIDER`):

1. **Claude Messages API** through the official Java SDK (`assistant.adapters.anthropic`, provider `anthropic`),
   authenticated with a Console API key billed to its owner. It supports tool loops and is the path for household-wide
   workloads.
2. **The owner's Claude subscription** through the unmodified official Claude Code CLI (`assistant.adapters.claudecode`,
   provider `claude-code`). The owner decided on 2026-09-18 to supply his subscription credential instead of a
   Console key. `claude setup-token` provides an OAuth token in `CLAUDE_CODE_OAUTH_TOKEN`, which is not read in
   `--bare` mode. All active household members use the same application authorization and bounded execution path.

The Agent SDK remains rejected (no Java SDK, second runtime). The CLI is confined as follows: one process per call with
`--disallowedTools "*"` (every built-in tool removed), `--strict-mcp-config` without servers, `--disable-slash-commands`,
`--no-session-persistence`, `--permission-mode dontAsk`, `--max-turns 3`, the output schema via `--json-schema`, an empty
temporary home and working directory deleted afterwards, and an environment that contains only `PATH`, the token and
switches that disable non-essential traffic and auto-updates, so database passwords and other application secrets are
not inherited. The process tree is killed when the attempt's time is up. Tool loops and multi-turn conversations are
refused by this gateway; workloads that need tools use the API provider (or a later MCP bridge to Aegis tools).
ArchUnit confines the Anthropic SDK to its adapter and process creation to the Claude Code adapter. The CLI (musl
build, pinned version, npm integrity check) is part of the application image. A further provider or local inference
(#35) is another `ModelGateway` implementation.

Credentials are server-side files mounted read-only into the application container from
`/srv/aegis/deploy/secrets/ai` (mode 0750, group 10001): `claude-code-oauth-token` or `anthropic-api-key`, each mode
0640. They are not in Git, the environment file, logs or the database; a missing or empty file means "not
configured", and a changed file is read again without a restart. The subscription token is a personal token: placing
it on the VM is an explicit owner exception to the rule that personal tokens stay off the VM, and only the owner
generates and stores it.

### Workload profiles

Provider, model and limits are configuration per workload (`aegis.ai.workloads.*`); model names are preferences, not
code. Defaults: provider `claude-code`; classification `claude-haiku-4-5`, review `claude-opus-5`, chat `claude-opus-5`.
A model can run only with a configured list price (`aegis.ai.prices`), because otherwise its cost budget could not be
enforced. With the subscription, amounts are API-equivalent estimates that bound quota use, not charges; the CLI's
output token cap is not configurable, so its per-call cost is bounded by the turn limit and checked after the call. Because
Haiku 4.5 may be retired from 2026-10-15, switching classification to a successor is a configuration change plus a
price entry. Availability is never assumed: the connection check performs one structured call per distinct configured
model with the real key.

### Run model (`assistant` context)

The new bounded context `assistant` (domain, application, adapters) owns runs. An `AiRun` has a stable id, the
workload, an idempotency key, the requesting member, the model selection, the budget, the analyzed source versions
and small parameters. Its visible state is `QUEUED`, `RUNNING`, `WAITING_RETRY`, `SUCCEEDED`, `FAILED` or
`CANCELLED`, plus attempt, usage (tokens, model calls, tool calls, denied tool calls), cost with currency, the
validated output and the latest failure as a stable category and code. Append-only steps record attempts, model and
tool calls, denials, failures, cancellation and completion without prompts, document text or tool arguments.

Budgets per run: wall-clock time, model calls and tool calls per attempt; cost across all attempts; maximum output
tokens per call; maximum attempts. Household limits: a daily cost limit (sum of all model calls of the household day
in Europe/Berlin), a global concurrency limit and a per-workload concurrency limit. Before each model call the output
allowance shrinks so that the worst-case cost (conservative input estimate plus the allowance at the output price)
fits the remaining run and daily budgets; after the call the reported usage is recorded and checked again. Retryable
failures (rate limit, provider outage, timeout, invalid output, interruption, source outage, internal error) wait with
exponential backoff that respects the provider's retry-after; all others end the run.

### Execution and exactly-once effects

A run is created together with its db-scheduler job in one transaction. Each job execution performs at most one
attempt: short transactions (`RunExecution`) admit the attempt, admit every model and tool call, record usage and
complete or fail the attempt; model calls and tools run between them, never inside a database transaction. The SDK's
own retries are disabled and every call carries the remaining attempt time as timeout.

Writes happen only after the model finished, in the completion transaction, through the workload handler and the
owning context's public application commands. Four guards prevent duplicate or unwanted writes:

1. Attempt fencing: only the current attempt of a running run may call, record or complete; an executor that lost its
   attempt (restart, revived job) is told it was superseded and writes nothing.
2. Cancellation is checked at every checkpoint and last before the result is applied; a cancelled run never applies
   its result, and its spent cost stays recorded.
3. Source versions: the handler confirms inside the completion transaction that every analyzed source still has the
   recorded version; otherwise the run fails as `STALE_SOURCE`.
4. Effect ledger: the handler's effect key (for example workload plus source version) is claimed in
   `ai_run_effects` in the same transaction; a second claim by a retry, revived job or reprocessing run stores its
   result but does not apply the effect again.

A process restart leaves a run `RUNNING`; the revived job records the interrupted attempt and starts the next one
within the attempt and cost budgets. Idempotent requests return the active run for the same key, and a succeeded run
unless reprocessing is requested explicitly.

### Untrusted content and least privilege

- Tools are granted per workload in code and never derived from content. Only granted tools are offered to the model;
  calls to other tools are refused and audited, and the third refusal fails the run as `TOOL_DENIED`.
- Tools are read-only adapters over public application queries and run with the requesting member's actor, so the same
  `HouseholdAccess` check applies as in the UI. A member who is no longer active cannot start a run or use a tool.
- There is no shell, file, network or credential tool. The only process Aegis starts is the locked-down Claude Code
  CLI described above, whose built-in tools are all removed. The worker is a job thread inside the unprivileged
  application container (uid 10001) without Docker socket, SSH keys or host access; the API adapter only contacts the
  configured HTTPS endpoint.
- Household content is framed as data (`<untrusted_content>`, with frame tags inside the content neutralized, control
  characters removed and visible truncation), and every system prompt states that framed content and tool results
  cannot change the task, tools or permissions. This framing is defense in depth; the structural limits above are the
  actual protection.
- Final answers must match the workload's JSON schema and are validated by the handler before anything is applied;
  results become proposals or other reviewable data defined by the owning context (for #23: `SUGGESTED` or explicitly
  policy-`APPLIED` labels under ADR-0002).

### Degradation and visibility

Capture, search, tasks and planning never call the AI context. Requests that cannot run (no credentials, no handler,
no price) are refused with `ai.notConfigured`, `ai.workload.unavailable` or `ai.price.unknown` instead of queuing work.
The status page shows the AI integration, judged per configured model from its latest succeeded or failed
run (not configured; degraded while a model is unverified or its latest run failed for a provider reason;
otherwise connected), the workload profiles with their budgets, today's spending, the connection check and recent runs with
cancel and retry. REST: `/api/ai/overview`, `/api/ai/runs`, `/api/ai/runs/{id}`, `/api/ai/runs/{id}/cancel`,
`/api/ai/runs/{id}/retry`, `/api/ai/connection-checks`.

## Consequences

- #23, #24 and #25 add a `WorkloadHandler` (prompt, output schema, validation, source check, effect) and read-only
  tools; they do not build execution, budget or retry infrastructure. `search_documents` is the first tool.
- Costs are estimates from configured list prices and reported usage; the provider invoice is authoritative. With
  several runs in flight the daily limit can be exceeded by at most the worst-case cost of calls already running
  (global concurrency 2).
- Runs are operational audit data in PostgreSQL and part of the database backup; they are not part of the portable
  export. Significant conclusions become facts or proposals in their owning context.
- Every run needs a requesting member. Scheduled review (#24) must decide on whose behalf it runs or introduce a
  limited service principal.
- Streaming for chat, Message Batches for review and prompt caching are adapter extensions that do not change the run
  model. The cost of an interrupted attempt is counted when its call returns; a call lost with its process is not
  counted by Aegis.
- Household-wide automatic workloads (for example classifying every upload in #23) run on behalf of an active
  member. Tool-using workloads need the API provider. Subscription usage limits surface as `RATE_LIMITED` with the
  reported reset time.
- The subscription gateway classifies CLI failures by their messages (observed with Claude Code 2.1.276); unknown
  failures count as provider outages and are retried within the attempt budget. Updating the pinned CLI version is a
  deliberate image change.
- The live verification of credentials and models needs the owner's token or key; the automated tests use controlled
  adapters (a local HTTP server for the API, a stand-in executable for the CLI) and a separately enabled API live test.
