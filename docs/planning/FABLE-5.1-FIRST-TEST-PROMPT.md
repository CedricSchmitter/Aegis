# Fable 5.1 implementation prompt

Paste the text below into a Fable 5.1 coding-agent session with this repository open and access to the local shell, GitHub CLI, and the existing SSH setup. The model selection, tool permissions and session continuation belong to the host application; prompt text alone cannot configure or bypass them.

---

You are implementing Aegis autonomously until its first runnable household test is deployed and verified on the existing server. The owner does not want to supervise routine implementation or repeatedly ask you to continue.

## Read the project and execute

Work in `D:\projekte\Aegis`, repository `https://github.com/CedricSchmitter/Aegis.git`. Read `CLAUDE.md`, `AGENT.md`, `README.md`, `docs/architecture/FOUNDATIONS.md`, `docs/VM-ACCESS.md`, and `docs/planning/FIRST-RUNNABLE-TEST.md`. Read all issues in the **First runnable test** milestone and their dependency/acceptance sections. The milestone contract defines this run, not every future phase of ROADMAP.md.

Inspect current files, Git state, issue/PR state and the server before changing anything. Resume completed work instead of rebuilding it. Keep `docs/planning/FIRST-TEST-STATUS.md` current with the implemented state, active issues, verification, deployed commit and next action. Never put secrets in that file.

## Authorization and working style

The owner explicitly authorizes you to create branches/worktrees, fetch, pull, commit, push, open/review/merge pull requests, resolve conflicts, and update/close project issues as needed. No additional owner approval is required for those Git operations. You may install dependencies, configure and deploy Aegis services, and restart Aegis containers inside the dedicated VM. Choose sensible defaults and finish the requested implementation rather than asking about routine choices.

Preserve unrelated work and maintain traceable commits. Do not treat this authorization as a requirement to rewrite history or bypass configured CI checks. If a host permission prompt is unavoidable, state the exact operation and resume when possible; do not claim the prompt overrides tool restrictions.

Give brief German progress updates with actual findings and the next concrete action. Keep source, comments, tests, commits, issues and technical documentation in English. Product-owned visible text must support German and English.

Independent reads/checks may run in parallel. If subagents are available, you may delegate bounded work in separate worktrees; keep doing independent useful work yourself and integrate/review their results. Avoid concurrent edits or deployments against the same worktree/runtime. Verify fast-moving dependency/API details against official documentation. Make focused edits and write tests for meaningful behavior and risks, not to inflate coverage.

## Target and access

Target only Proxmox VM 102, `aegis-dev`, `192.168.2.159`, user `aegis-agent` with `sudo -n`.

```powershell
ssh -i C:\Users\cedri\.ssh\aegis_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=C:\Users\cedri\.ssh\aegis_known_hosts aegis-agent@192.168.2.159
```

The verified server fingerprint is `SHA256:K9ggmxG5kX94EhiY/psFbP9CCWxQwx3F2xy6jNCs0mQ`. The VM has Debian 13, 4 vCPU, 8 GiB RAM and a 100 GiB virtual disk. Java 21, Maven, Docker/Compose, Git, Restic, rsync and the guest agent are installed. `/srv/aegis` is the working/deployment root. Recheck actual state.

Home Assistant (100), Mealie (101), the Proxmox host and unrelated networks/storage must remain unchanged. The external 1 TB disk already contains data. Prepare configurable backup scripts and a non-destructive connection checklist, but do not format, repartition or modify that disk. Do not block the first synthetic-data test on attaching it.

Use the available local GitHub login; do not copy personal tokens or private SSH keys onto the VM. Deploy identified source safely through Git archives/SCP or another credential-safe route. Keep generated credentials server-side and out of Git, issue comments, logs and test artifacts.

## Implement this complete slice

1. A Maven-multimodule Java/Spring backend using DDD and hexagonal architecture. Domain and application code remain independent of Spring/JPA/HTTP/vendor SDKs. Use separate persistence models and adapters, focused classes, suitable records, explicit mappers and architecture checks.
2. Real PostgreSQL persistence/migrations and durable processing jobs with retry/idempotency and visible failures.
3. Two separate logins into one shared household. Both users can see all documents/tasks, including records about the other person and the house. Subject metadata is not a privacy boundary.
4. A responsive React/TypeScript UI with German/English, login, documents, Today and in-app notices.
5. Real PDF upload, durable receipt, Paperless archival/processing, document search/list and original access. Failure and pending states must be honest.
6. Source-linked tasks with one owner or unassigned, actual deadline separate from planned work date, completion and shared updates without silent conflict loss.
7. Persistent in-app notices and truthful integration/operation status. No phone-push claim unless actually implemented and verified.
8. Portable export with original files, versioned structured data and working source links. Complete a non-destructive restore drill using synthetic data in an isolated destination. Prepare external backup configuration, clearly marked not configured until verified on the actual disk.
9. Reproducible Compose deployment, meaningful CI/tests, actual browser verification and persistence after application-container restart.

Do not require live mail credentials, calendar accounts, AI keys, a domain purchase, Telegram, MCP, phone access, or the external disk to complete this milestone. Clearly mark those integrations unavailable. Prepare calendar capability boundaries but perform no real calendar writes. Do not substitute fake integrations or mock data for real document/database functionality.

Prefer an available trusted HTTPS route. If none can be established without external owner input, deliver the explicitly labeled LAN-only HTTP preview with appropriate session configuration, no Web Push/PWA secure-context claims and a documented HTTPS follow-up. Do not expose database/Redis/Docker ports to the LAN or internet.

## Completion and evidence

Continue through implementation, fixes, integration, CI, deployment and verification. A scaffold, mock screen, passing unit test or container health check is not completion. Close issues only when their acceptance criteria are met and record evidence. Keep the final deployed code on `main` and identify its commit.

Verify through a browser at the actual server URL: log in, switch language, upload a synthetic PDF, wait for real Paperless processing, find/open the original, create/assign/complete its task, view Today/notices, and confirm shared access with the second account. Check preserved records after restarting only the Aegis application containers. Run the export/restore checks. If your runtime lacks a browser or network capability, try an available appropriate alternative and state exactly what remains unverified; never invent a successful test.

When the full first test is ready, notify the owner in this conversation in German with the real clickable URL, deployed commit, secure login/onboarding instructions, a short sequence to try, passed checks, and remaining limitations. Do not send external messages. Do not label a local restore drill as a working external backup or the first test as production-ready.

Stop only when that result is delivered, or a genuine external blocker prevents completion after all independent work is finished. In a blocker report, identify the exact failed step, attempted remedies, preserved progress and the minimum owner action needed. Do not finish with an unexecuted promise to implement the next required step.

If context is compacted or the session resumes, preserve this objective, authorization, scope exclusions, issue IDs/status, repository branch/commit, server identity, completed checks, failures and next action. Continue from evidence rather than restarting. Use the tracked status file as a durable handoff; do not persist credentials there.

---

## Prompt design reference

The prompt applies the guide's advice on clear completion criteria, authorized autonomous work, short progress updates, dependency-aware parallel work, focused changes and resumable context. Project-specific requirements come from Aegis documentation and the owner's instructions.

Source: [Prompting Claude Fable 5.1](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5-1). Use the host's Fable 5.1 model selection; the guide suggests starting at high effort. This file does not launch a session or create an automation.
