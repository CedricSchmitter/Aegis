# ADR-0003: Bounded, replaceable AI execution

Status: accepted (implemented for issue #21). Date: 2026-09-18. Amended 2026-09-19: subscription fallback through a
host runner for `claude -p`.

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

## Decision

### Provider and runtime

All AI calls go through the provider-neutral `ModelGateway` port; Aegis owns the loop, tools and every limit.
The deployed provider is the Claude Messages API through the official Java SDK
(`assistant.adapters.anthropic`, provider `anthropic`), authenticated with a Console API key billed to its owner.

The owner selected direct API billing for both household members. The in-container CLI gateway with a
`setup-token` file and the bundled binary were removed. The Agent SDK would add another runtime; it is not required by
the existing Java tool loop. Anthropic permits a customer's authorized users to use a server-side API key, while
routing other users' requests through personal subscription credentials is restricted. See
[authentication and credential use](https://code.claude.com/docs/en/legal-and-compliance).
ArchUnit confines vendor types to the adapter and prohibits operating-system process execution in the application.

Amendment (2026-09-19): without an API key the owner's own Claude subscription is the fallback (provider
`claude-code`, `assistant.adapters.claudecode`). The owner logged in to the official Claude Code CLI on the VM as
`aegis-agent`; Aegis never reads, copies or stores that login. A separate host process, the Claude Code host runner
(`ops/claude-runner`), runs as that user and executes one headless `claude -p` per call with a fixed command line
without tools, settings, MCP servers, skills or stored sessions. The application reaches it only through a Unix socket
in a shared directory and sends the call fields (model, system prompt, prompt, JSON schema, effort, output allowance,
timeout); it cannot choose command-line flags. The adapter's `ClaudeCli` executor keeps the CLI protocol (result JSON,
error classification) apart from the transport (`HostRunnerClient`). The subscription is personal, so the gateway
serves only the members listed in `AEGIS_AI_SUBSCRIPTION_MEMBERS` (`ModelGateway.allows`); requests of other members
are refused as `ai.provider.memberNotAllowed` and a queued run whose member lost access fails as `NOT_AUTHORIZED`.
The provider accepts one user prompt without tools, which fits classification; tool workloads need the API.

Provider choice: a workload profile lists providers in order of preference (`AEGIS_AI_PROVIDERS`, default
`anthropic,claude-code`) with one model. A run uses the first provider that has credentials and serves the requesting
member at request time and records it; the call carries the same model, system prompt, schema, effort and output
allowance with either provider. A failing call is not repeated on another provider; a retry chooses the provider again
by the current preference and keeps the model.

The key is a server-side file mounted read-only into the application container:
`/srv/aegis/deploy/secrets/ai/anthropic-api-key` (mode 0640, group 10001), in a mode-0750 directory.
It is not stored in Git, environment variables, logs or the database. Missing/empty/unreadable files mean
"not configured"; changed files are read again without restart. The hidden-input installation script and
manual prepaid billing procedure are in DEPLOYMENT.md. Aegis has no billing/reload integration.

### Workload profiles

Providers in order of preference, model and budgets are configuration per workload. All profiles select
`anthropic`, then `claude-code`, with `claude-sonnet-5` (2 USD input / 10 USD output per million tokens). The daily limit is 0.25 USD and global
concurrency is one. Classification has a 0.05 USD run budget; review and chat have 0.10 USD each.
Model changes require a configured price and representative quality checks. The connection check performs
one structured call per distinct configured model, so the default profiles require only one check.
A successful check does not mean the classification, review or chat handlers have been implemented.

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
unless reprocessing is requested explicitly. A key names one unit of work (workload, sources with versions,
parameters; not the requester): reusing it for different work is rejected with `ai.run.keyConflict` (issue #59).

### Untrusted content and least privilege

- Tools are granted per workload in code and never derived from content. Only granted tools are offered to the model;
  calls to other tools are refused and audited, and the third refusal fails the run as `TOOL_DENIED`.
- Tools are read-only adapters over public application queries and run with the requesting member's actor, so the same
  `HouseholdAccess` check applies as in the UI. A member who is no longer active cannot start a run or use a tool.
- There is no shell, file, network or credential tool. The application starts no operating-system processes.
  The worker is a job thread inside the unprivileged
  application container (uid 10001) without Docker socket, SSH keys or host access; the API adapter only contacts the
  configured HTTPS endpoint, the subscription adapter only the host runner socket. The host runner validates every
  field, runs the CLI without built-in tools in an empty directory with a minimal environment, stops it on timeout or
  disconnect, and is confined by systemd (no privilege escalation, read-only system and home except the CLI state).
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
  (default global concurrency 1).
- Runs are operational audit data in PostgreSQL and part of the database backup; they are not part of the portable
  export. Significant conclusions become facts or proposals in their owning context.
- Every run needs a requesting member. Scheduled review (#24) must decide on whose behalf it runs or introduce a
  limited service principal.
- Streaming for chat, Message Batches for review and prompt caching are adapter extensions that do not change the run
  model. The cost of an interrupted attempt is counted when its call returns; a call lost with its process is not
  counted by Aegis.
- Household-wide automatic workloads run on behalf of an active member and use the same API connection.
- Subscription runs report API-equivalent costs from the CLI's usage; the same budgets apply although they are not
  charges. The runner must be installed and the CLI login kept valid on the VM (DEPLOYMENT.md); an expired login
  surfaces as `AUTHENTICATION`.
- Live verification needs the owner's API key. Automated tests use a controlled HTTP server; a separately enabled
  live test checks Sonnet 5 structured output and a tool round trip. No live verification is claimed before that key
  is supplied.
