# Next session: establish trusted household HTTPS and continue the synthetic pilot

Continue Aegis in `D:\projekte\Aegis`. Read AGENT.md, docs/planning/INDEPENDENT-REVIEW.md and
operations/HOUSEHOLD-PILOT.md (under docs), then inspect GitHub and /api/system/version. Preserve existing work;
do not repeat the completed first implementation or restart the independent review from scratch.

## Completed review work

PR #15 is merged, and application commit `e148ef8af157f28ba4cd6f649db5e3da2e841e90` was deployed and
independently checked. The live account migration is completed: use lisa and cedric, not anna and ben.
An existing session survived the migration; IDs, password hashes, documents and tasks were unchanged.
Do not repeat onboarding as though it were still pending.

PR #15 corrects restore staging deletion, original checksum verification/retention, interrupted-upload temporary
files, concurrent export staging, malformed-request errors and invalid task owners. It hardens prepared external
backup and tests explicit household onboarding. The review records independent live facts separately from Claude's
inherited browser/drill report. The original main CI runs are confirmed green.

The VM Maven verification passed 64 unit, 8 architecture and 16 PostgreSQL integration tests; four hermetic
backup tests and six frontend tests also passed. See INDEPENDENT-REVIEW.md for the final PR/deployment evidence.
Do not infer a deployed commit from a branch name or from an old deployment report.

## Owner decisions (do not ask again)

- Names and login mapping: anna -> lisa / Lisa; ben -> cedric / Cedric. Use the tracked explicit onboarding
  procedure; preserve IDs, passwords, sessions, ownership and existing data. Do not create duplicate identities.
- Preferred hostname: aegis. Local-CA HTTPS and Secure cookies are prepared. The public root certificate was
  created on the Aegis VM and its fingerprint verified through trusted SSH. A loopback-only TLS proxy passed
  certificate verification and reached the live version endpoint. This does not mean the owner's devices trust it.
- Internet/public access is explicitly deferred until the local pilot proves useful. All HTTPS work is LAN-only.
  Local DNS is optional; the prepared configuration supports the private IP address as well. The owner asked
  for clarification of the router question; it was explained as local naming, and no router changes were made.
- The nonempty 1 TB disk is not attached. The owner deferred attachment until it becomes necessary. No formatting,
  partitioning, deletion, mounting, passthrough or unrelated host changes are authorized implicitly.
- Continue synthetic tests while the disk is absent. Before relying on real household data, require an actual
  external backup and recovery exercise. A local export drill is not an external backup.

## Next concrete step

Finish issue #16 as LAN-only HTTPS: establish certificate trust on the PC and both Android phones. The prepared
private-IP endpoint avoids making router/DNS configuration a blocker. If the owner wants the friendly name too,
arrange local DNS for aegis -> 192.168.2.159; it did not resolve on the PC during review. Ask only for device-side
actions or resolver details actually needed; do not modify router/Proxmox settings under VM-only authorization.
The root certificate is public; its private key must stay on the VM. Follow HOUSEHOLD-PILOT.md, enable
AEGIS_HTTPS=true only after access/trust preparation, then verify trusted HTTPS, redirects, Secure cookies and the
PDF workflow on the real devices. Do not accept a bypassed certificate warning as successful HTTPS.

Issue #17 tracks the external destination and actual recovery gate. Mail, calendar writes, AI, Telegram and Web
Push remain unconnected. No additional infrastructure or roadmap phases should start merely because TLS works.

## Scope and handoff

Only VM 102 aegis-dev at 192.168.2.159 may be changed. Existing Git and Aegis-VM authorization remains in effect;
other VMs, Proxmox and existing disk data stay untouched. Keep secrets outside Git/issues/logs. Repository text
stays English, product text supports German/English, and conversation with the owner is German. Finish scoped
changes with an identified commit, actual tests, deployment evidence and an accurate next step.
