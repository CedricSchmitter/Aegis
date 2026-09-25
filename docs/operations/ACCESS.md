# Household access

The household endpoint is `https://192.168.2.159` on the local network. Public access is not configured.
The optional hostname `aegis` is unconfigured; private-IP HTTPS works without local DNS.
For dated device and release verification, use the relevant issues, pull requests and CI runs.

## Accounts

Current usernames are `lisa`, `cedric` and `aegis-dev`. All three see the shared household collection; person
associations do not restrict visibility. Each account has its own preferences, responsibility and session history.
`aegis-dev` is the agent test login and the household's assistant account
([ADR-0017](../architecture/adr/ADR-0017-persons-and-the-assistant-account.md)): the installation designates it
through `AEGIS_ASSISTANT_MEMBERS=aegis-dev` in `/srv/aegis/deploy/.env` (set with
`ops/deploy/set-assistant-member.py`, see [DEPLOYMENT.md](DEPLOYMENT.md#assistant-account)), and the app shows it as
`Aegis`. It keeps its login username, receives every notice and can be responsible for a task, but it is not a
person: it is never offered as what a document is about ("Betrifft"), not shown in the subject filter and not part
of the persons the classifier receives. Lisa and Cedric are the persons. Its random password is stored only on the VM at
`/srv/aegis/deploy/secrets/aegis-dev-login.json` (mode 0600, readable by `aegis-agent`); retrieve it through the
verified SSH connection when needed and keep it out of Git, logs and issues. The provisioning seed was emptied again
after the account was created, so later restarts do not retain its plaintext password.
The owner confirmed individual passwords and successful fresh logins. Do not use stale provisioning
passwords for checks or print credentials in logs, Git or issues.

The completed migration from `anna` and `ben` preserved member IDs, password hashes, ownership and
sessions. Do not repeat it on the existing installation. For recovery of a legacy installation only,
`ops/deploy/onboard-household.py` runs as root inside aegis-dev, stops the application, keeps a restricted
recovery copy under `/srv/aegis/deploy/onboarding-recovery`, and applies `onboard-household.sql`
transactionally together with the provisioning seed update. It does not recreate members or reset passwords.

## Trusted HTTPS and new devices

`deploy/Caddyfile.https` serves the private IP and the optional local hostname through Caddy's persistent
local CA. `deploy/compose.https.yaml` publishes port 443 and enables Secure session cookies.
`AEGIS_HTTPS=true` in the private VM configuration preserves HTTPS across deployments.

1. Copy only the public CA certificate from
   `/srv/aegis/data/caddy/data/caddy/pki/authorities/local/root.crt` through verified SSH.
2. Verify its SHA-256 fingerprint against the trusted VM before installing it into the client's certificate
   trust store. Never distribute `root.key` or bypass certificate errors.
3. Open the private-IP HTTPS endpoint and verify login, upload, original retrieval and German/English UI.
   Android trust-store menus depend on the device/version.

Recorded public-root fingerprint:
`6B:26:73:D6:B7:F2:89:14:68:E8:1C:56:FB:42:EB:7D:82:75:C5:85:66:86:E6:A8:C7:5E:5C:76:4D:10:AD:5D`.
Investigate any difference through verified SSH; a changed fingerprint is not automatically trustworthy.

HTTP redirects preserve the requested host. Caddy's `default_sni` selects the IP certificate for clients
that omit SNI when connecting by IP. The installable app and the Android share target rely on this trusted HTTPS
endpoint (see [DEPLOYMENT.md](DEPLOYMENT.md#installable-app-and-android-share-target)); Web Push is not configured.
Router/resolver changes are outside the standing VM-only infrastructure scope.

References: [Caddy local HTTPS](https://caddyserver.com/docs/automatic-https#local-https),
[Caddy global options](https://caddyserver.com/docs/caddyfile/options#default-sni).
