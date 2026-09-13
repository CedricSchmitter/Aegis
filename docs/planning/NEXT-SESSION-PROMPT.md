# Next session: review the first deployed test and prepare household use

Continue Aegis in `D:\projekte\Aegis`, repository https://github.com/CedricSchmitter/Aegis. Read AGENTS.md/AGENT.md, architecture foundations, the current execution status, and the operations guides before acting. Preserve existing work and inspect the live state rather than repeating completed implementation.

The household members are Lisa and Cedric. All household information is shared; person/house associations are metadata, not access silos. Initial logins anna/ben are test identities. Do not invent their mapping to the real people or blindly rename/recreate them.

Claude reported the first test deployed at http://192.168.2.159/ on VM 102 aegis-dev, with commit 6c9890dd1d1ae632447aa6a759d772b734163634 on main after PR #14. Treat this as a reported baseline and verify the current commit via /api/system/version and GitHub. The owner has tried the UI and found it promising.

Reported functionality: two logins, German/English UI, actual Paperless PDF archival, original checksum preservation, full-text search, source-linked tasks, distinct deadline/planned dates, shared notifications, persistent data/sessions after restart, export and isolated local restore. Claude reported green CI and browser tests; the final main CI run had still been pending in the report. Verify evidence and resolve any discrepancies in counts or status without assuming every test must be rerun.

Your first task is an independent focused review of the implemented release: authentication/authorization and shared access, durable uploads/retries, original retrieval, task invariants/concurrency, deployment configuration, export and restore. Check the latest CI result and actual deployed version. Report material findings with concrete evidence; fix confirmed issues required for safe continuation and test the affected behavior. Do not rebuild the application or expand into all later roadmap features.

Next prepare the path to household use: trusted HTTPS, real Lisa/Cedric onboarding, and an external backup destination. Investigate the existing configuration first. Ask concise questions only for genuinely missing choices such as hostname/certificate trust, account mapping and disk attachment. Continue independent work while awaiting answers. Prefer a small end-to-end household pilot before live mailbox and AI integration.

Important limits and facts:

- Current release is a LAN-only HTTP preview with synthetic data, not production ready.
- The available external 1 TB disk is NOT empty. It can eventually remain connected to Proxmox. Prepare non-destructive inspection/setup; do not format, repartition, erase existing files or assume it is already attached. A local restore drill is not an external backup.
- Email, calendar, AI, Telegram and Web Push are not connected. Initial notification direction is in-app plus later tested Android Web Push. Calendar ports may prepare future capabilities, but actual writes remain deferred until the core workflow is proven.
- Aegis VM access and verified SSH host identity are in docs/VM-ACCESS.md. User aegis-agent has sudo and was added to docker/aegis-app. Initial member credentials are on the VM at /srv/aegis/deploy/secrets/initial-members.json; never publish them in Git/issues/logs.
- Preserve Home Assistant VM 100, Mealie 101 and unrelated Proxmox/network/storage configuration.
- Backend: Java 21/Spring, DDD/hexagonal Maven modules, framework-free domain/application, separate JPA adapters, small cohesive classes, suitable records, meaningful tests rather than artificial coverage targets.
- Repository content is English; product presentation supports German and English. Communicate with the owner in German.
- The owner authorizes normal autonomous Git operations, including branches, commits, pushes, pulls, PRs and merges, and scoped Aegis VM work. Do not ask again for already authorized routine steps. Preserve others' work and respect actual tool permissions.

Keep GitHub issues and tracked status accurate. Give concise progress updates, distinguish verified facts from inherited reports, and finish each scoped change with evidence and remaining limitations. Start with inspection and the focused review now.
