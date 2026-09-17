# Household pilot preparation

Owner choices on 2026-09-13: hostname `aegis`; map anna to Lisa and ben to Cedric while preserving identities.
The disk decision changed on 2026-09-17; see the current evidence below. One real invoice is already stored.
The owner explicitly deferred internet/public access until the local pilot proves useful. This guide concerns
LAN-only HTTPS, not remote access; no router port forwarding or public exposure was configured.

## Account onboarding

Run the tracked `ops/deploy/onboard-household.py` as root inside aegis-dev only. It stops the application,
keeps a restricted local recovery copy and database dump under `/srv/aegis/deploy/onboarding-recovery`, updates
the provisioning seed and executes `onboard-household.sql` transactionally. It does not delete/recreate members
or reset passwords. Old sessions continue to resolve their member IDs and show the new names. Current logins
are `lisa` and `cedric`; the original provisioning passwords are stale after later password changes. Both
accounts currently use temporary test passwords. Change to individual passwords through Settings before
further real-document use. Never print or commit credentials.

## Trusted HTTPS (LAN endpoint enabled on 2026-09-17)

HTTPS is now enabled at `https://192.168.2.159`. The application remains at `e148ef8`;
the HTTPS configuration is tracked separately in `/srv/aegis/deploy/HTTPS_CONFIG_COMMIT` (`30554ee`).
Windows user root trust and a normal .NET HTTPS request were verified without bypassing validation.
Both household logins, all three original checksums, authorization and logout passed over verified TLS.
HTTP redirects preserve the requested host. `default_sni` selects the IP certificate for clients that omit
SNI when connecting by IP; Docker's internal address must not determine that certificate selection.
See [Caddy global options](https://caddyserver.com/docs/caddyfile/options#default-sni).
The owner subsequently confirmed the Android workflow below. Local DNS remains optional and unconfigured.
No router, public access or other VM changes were made by the Aegis deployment.

The persistent CA was generated and a loopback-only proxy successfully served the live version API with
certificate verification on 2026-09-13. Public root SHA-256 fingerprint (verified through trusted SSH):
`6B:26:73:D6:B7:F2:89:14:68:E8:1C:56:FB:42:EB:7D:82:75:C5:85:66:86:E6:A8:C7:5E:5C:76:4D:10:AD:5D`.
The certificate is available on the VM at the path below; no private key was copied to client devices.

`deploy/Caddyfile.https` serves `https://aegis` and an IP fallback using Caddy's local CA.
`deploy/compose.https.yaml` enables port 443 and Secure session cookies. Deployment honors
`AEGIS_HTTPS=true` in the private VM `.env`, so later releases retain the selected mode.

1. Optionally arrange local DNS `aegis -> 192.168.2.159` on the household resolver. This affects only the local
   name; it does not publish Aegis on the internet. The private IP HTTPS fallback can be used without DNS.
   Router changes are outside the current VM-only authorization.
2. Prepare Caddy's persistent CA in the VM; copy only its public root certificate from
   `/srv/aegis/data/caddy/data/caddy/pki/authorities/local/root.crt`. Never distribute `root.key`.
3. Check its SHA-256 fingerprint through verified SSH and install that public certificate into the trusted
   certificate store on the PC and both Android phones. Android menu names depend on device/version.
4. Set `AEGIS_HTTPS=true`, deploy the identified release, verify a trusted TLS handshake and HTTP redirect,
   and confirm Secure/HttpOnly/SameSite session cookies. Do not bypass certificate errors as acceptance.
5. Verify login, PDF upload/retrieval and both locales on the actual phones. The HTTPS pilot banner still
   states that external backup and push are unconfigured. HTTPS alone does not implement PWA or Web Push.

Reference: [Caddy local HTTPS](https://caddyserver.com/docs/automatic-https#local-https).
The operator must establish client trust; Caddy running in a container cannot install trust on remote devices.

## Next data-safety gate

Before further real-document use, expose the server-attached disk to VM 102 through a separately authorized
non-destructive Proxmox change. Inspect filesystem, free space and health read-only before choosing a dedicated
directory. Do not format, repartition or delete existing data. Follow BACKUP.md; store the recovery password
outside the VM, run an actual external backup and restore it into an isolated target before starting real use.

## Owner-confirmed pilot and disk update (2026-09-17)

The owner confirmed trusted HTTPS, login and original PDF access on both Android phones. A mixer invoice
was uploaded; its task was assigned to Lisa, surfaced as an in-app notice and completed by her. These are
owner-reported device observations. The invoice is real data and must be preserved.
Temporary test passwords were changed through HTTPS for both accounts and new logins independently verified;
provisioning seed passwords are stale. Replace test passwords with individual credentials before further real use.

The owner subsequently reported personally formatting the 1 TB disk and attaching it directly to the server.
This supersedes the earlier deferred-attachment notes. A read-only check in VM 102 on 2026-09-17 showed only
the 100 GB QEMU system disk and virtual DVD drive; no external block or USB disk is visible there. A screenshot
supplied by the owner shows two 1 TB disks on the Proxmox host, one WDC/ext4 and one Toshiba/NTFS. Neither is
yet tied to the `BackupIntenso` label or a USB port; UUID and contents remain unknown. No further formatting
or deletion is authorized. External backup and isolated recovery are the next gate.
PR #18 merged after all four CI checks passed (run 35216385600).
