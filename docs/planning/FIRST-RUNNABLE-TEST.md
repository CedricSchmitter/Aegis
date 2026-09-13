# First runnable test: scope and issue map

Status: planned; no application implementation has been completed. This milestone is a synthetic-data development release, not the complete ROADMAP.md pilot or production recovery readiness.

- [GitHub milestone](https://github.com/CedricSchmitter/Aegis/milestone/1)
- [Tracking epic #1](https://github.com/CedricSchmitter/Aegis/issues/1)
- [Fable 5.1 implementation prompt](FABLE-5.1-FIRST-TEST-PROMPT.md)
- [Execution status](FIRST-TEST-STATUS.md)

## User-visible finish line

The owner opens a real URL served by the existing Aegis VM, logs in as either household member, switches German/English, uploads a PDF, waits for actual Paperless processing, finds and opens the unchanged original, creates and assigns a linked task, and completes it from Today. Both accounts see the same data. In-app notifications persist independently from task completion.

Records survive restarting the Aegis application containers. An export opens independently and an isolated local restore drill restores document/task relationships. The running version corresponds to a verified commit on GitHub main. CI and browser evidence are recorded, not inferred from a healthy container.

## Required work

| Issue | Deliverable | Prerequisites |
|---|---|---|
| [#2](https://github.com/CedricSchmitter/Aegis/issues/2) | Maven DDD/hexagonal modules and architecture checks | None |
| [#3](https://github.com/CedricSchmitter/Aegis/issues/3) | PostgreSQL, migrations, durable jobs | #2 |
| [#4](https://github.com/CedricSchmitter/Aegis/issues/4) | Two-user shared authentication/authorization | #3 |
| [#5](https://github.com/CedricSchmitter/Aegis/issues/5) | Responsive German/English UI and API client | #2 |
| [#6](https://github.com/CedricSchmitter/Aegis/issues/6) | Reproducible deployment to the existing VM | #2, #3 |
| [#7](https://github.com/CedricSchmitter/Aegis/issues/7) | Durable PDF capture and real Paperless archival | #3, #4, #6 |
| [#8](https://github.com/CedricSchmitter/Aegis/issues/8) | Document list/search/original and subject metadata | #5, #7 |
| [#9](https://github.com/CedricSchmitter/Aegis/issues/9) | Source-linked tasks and deterministic Today | #3, #4, #5, #8 |
| [#10](https://github.com/CedricSchmitter/Aegis/issues/10) | In-app notifications and honest status | #9 |
| [#11](https://github.com/CedricSchmitter/Aegis/issues/11) | Export, isolated restore, external-backup preparation | #6, #8, #9 |
| [#12](https://github.com/CedricSchmitter/Aegis/issues/12) | CI and executable integrated smoke coverage | Start after #2/#5; finalize with implemented flows |
| [#13](https://github.com/CedricSchmitter/Aegis/issues/13) | Final deployment, browser verification and owner handoff | All implementation acceptance criteria |

These dependencies specify required contracts/acceptance order, not an instruction to idle until entire issues close. Scaffold UI and CI alongside the backend; integrate and verify before claiming completion. #1 closes only after all required work is verified.

## Explicitly deferred

- Live mailbox accounts, cloud/local AI integration, MCP, Telegram, and Home Assistant changes.
- Real calendar read/write integration; prepare boundaries without fake live appointments.
- Trusted mobile Web Push and native sharing validation. In-app notifications are required; phone delivery is not claimed.
- Full historical ingestion, automatic contractual deadline extraction, proactive agent actions and the two-week user pilot.
- Actual external-disk setup. The owner confirmed the 1 TB disk contains existing data and can later remain connected. Prepare configuration and non-destructive inspection steps only.

This scope makes the first test independent of missing account credentials, DNS choices and disk attachment. Those later milestones remain open; do not silently mark roadmap phases complete.

## Deployment choices

- Target only VM 102, aegis-dev, 192.168.2.159; preserve other workloads and Proxmox configuration.
- Prefer available trusted HTTPS. If it needs external decisions, a labeled LAN-only HTTP preview is acceptable for this synthetic-data test. Do not claim secure-context PWA/Web Push behavior over HTTP.
- Use synthetic fixtures and generated credentials. Keep secrets outside Git, issues and CI logs; provide a secure onboarding/retrieval path.
- Do not export database, Redis or Docker management ports to the LAN/internet.
- External backup readiness requires the real separate destination and restore proof later. A same-VM local drill does not protect against VM/host loss.

## Final acceptance checklist

- [ ] Actual URL accessible from the owner's PC/browser environment.
- [ ] Two functional logins and shared visibility verified.
- [ ] German/English UI checked, including errors and empty states.
- [ ] PDF accepted durably, actually archived in Paperless, searchable and downloadable unchanged.
- [ ] Linked task assigned, rescheduled without moving its deadline, completed and visible to the other member.
- [ ] Today and in-app notices use persisted data.
- [ ] Retry/error handling and unauthenticated access checks pass.
- [ ] Records persist after Aegis application-container restart.
- [ ] Readable export and isolated restore pass with intact sources.
- [ ] External backup clearly marked not configured; disk data untouched.
- [ ] Relevant CI, domain/integration/architecture checks and browser smoke pass.
- [ ] Deployed commit is on main; status and issue evidence updated.
- [ ] Owner receives the URL, onboarding instructions, test sequence and known limitations in the current conversation.

## Execution authorization

The owner authorizes the implementing agent to fetch/pull, branch, commit/push, create and merge PRs, resolve conflicts and manage these issues without further Git approval, and to install/configure/deploy Aegis inside the existing VM. Respect actual tool permissions and preserve unrelated work. This does not authorize disk formatting, unrelated infrastructure changes, or outbound household communications.
