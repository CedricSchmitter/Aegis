# ADR-0026: Web Push to the members' devices

Status: accepted; first part of issue #28 implemented (device subscriptions, delivery, preferences, quiet hours).
The daily overview and the checks on both Android phones are still open. Date: 2026-10-03.

## Context

Notices (task assigned or completed, cancellation reminders, review cards, job, backup and disk incidents) reach the
members only when they open the in-app inbox. Issue #28 asks for device notifications on both Android phones with
explicit permission, preferences, quiet hours, privacy-conscious lock-screen text, deduplication, catch-up after
downtime without a flood, and truthful delivery states. Aegis is an installable web app reached over trusted LAN HTTPS;
there is no native app.

## Decision

**Standard Web Push, no vendor SDK.** Browsers subscribe through the Push API with the server's VAPID public key
(RFC 8292). The server encrypts each message for the browser (RFC 8291, `aes128gcm`) and posts it to the browser's push
service (RFC 8030): Firebase Cloud Messaging for Chrome and Samsung Internet on Android. Encryption, HKDF and the ES256
token use JDK cryptography only (`WebPushEncryption`, `VapidSigner`); a test decrypts as the browser does, and the
output was checked once against the reference JavaScript implementation `http_ece`. The push service sees only the
ciphertext, the endpoint and timing.

**Outbound only.** The VM needs outbound HTTPS to the push service; nothing new is reachable from outside the LAN. A
subscription may only point to a known push service host (`aegis.push.allowed-hosts`, default FCM, Mozilla, Apple and
Windows), so a member's browser cannot make the server post to an internal address.

**The server key lives in the database** (`push_server_keys`), created on first use. A restore brings it back together
with the subscriptions made for it; a new key would make every browser subscribe again.

**The notifications context owns subscriptions, preferences and delivery records.**

- `push_subscriptions`: one row per browser endpoint. The browser owns it: when the other member signs in on the same
  browser and subscribes, the row moves to that member and starts afresh. A push service answering 404 or 410 expires
  it; the settings page shows it as unregistered.
- `push_preferences`: per member, versioned (409 on a stale change): categories (tasks, deadlines, review, system),
  optional quiet hours in the household zone (default 22:00 to 07:00) and whether the lock screen may show details.
  Without details a push only names the kind of notice ("Dir wurde eine Aufgabe zugewiesen."); with details it may
  include a task title or contract name. Texts are rendered on the server in the member's locale (`push/messages_*`).
- `push_deliveries`: the latest attempt per notice and device. The in-app inbox stays the record.

**What is pushed.** The recurring job `dispatch-push-notices` (every minute) pushes a notice to each of the recipient's
active devices at most once: only while it is unread, created after the device subscribed and within the catch-up
window (24 hours), in a wanted category and outside quiet hours. Several notices due at once, for example after quiet
hours or downtime, become one summary push that opens the inbox. A notice read in the app before its push is never
pushed; a notice deleted with its record (ADR-0021) takes its delivery rows with it.

**Sending is outside transactions.** The ledger decides in one transaction, the sender posts, and the outcome is
recorded in another. A crash in between repeats the push on the next run; the message tag (shown notification) and the
`Topic` header (message waiting at the push service) replace the earlier copy instead of showing it twice.

**Delivery states stay truthful.** `SENT` means the push service accepted the message (2xx); push services do not
confirm display, so Aegis does not claim it. 429, 5xx and network errors are retried after 2, 4, 6 and 8 minutes, five
attempts in total; other 4xx answers count as failures too. Three consecutive failures show the device as failing. The
job appears on the status page and in the stale-job watch like the other household jobs (ADR-0013, ADR-0014).

## Consequences

- A member switches pushes on per device in Settings, can send a test push, and sees and removes the other devices.
  The app re-registers an existing subscription on start, so the server follows endpoint changes and member switches.
- Notifications need the trusted HTTPS address and a browser with Web Push; the settings say why when it is missing.
- Not yet covered: the configurable daily overview, and verification on both Samsung phones including power saving.
  Both stay open in issue #28.
