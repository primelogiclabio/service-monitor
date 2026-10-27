# Service Monitor: sign-in and access plan

The plan for how people sign in to the hub and from where, agreed on 2026-10-26, with what is built so far. The reference says how the built parts work; this page says what comes next and why.

## Why

The hub is the central dashboard for every connected agent and the only place alerts are sent from. People used to sign in by pasting an API token (or as the one local admin, which only handed out a 12-hour token), the sign-in page asked for a hub address, tokens sat in the browser's storage, there were no read-only people, no audit, and nothing limited where a sign-in could come from.

## Decisions

- **Sign-in is a user name and password.** No API token and no hub address on the sign-in page: the dashboard is served by the hub it talks to.
- **A new hub has `admin` / `admin`.** That password, and any temporary one an administrator gives out, works only from the internal network and allows nothing but choosing a new password.
- **A Settings screen replaces Tokens:** your own password and sign-ins, users (read-only or administrator), revoking agent credentials, and listing and revoking the CLI's tokens.
- **Internal only by default.** `svcmon.access.allow-outside` (in `application.yml`, false by default) refuses people outside the internal network; the sign-in page says it is not allowed. Opened, the intended public set-up is a gateway that signs people in first (Keycloak, Oracle OAM or another), then the hub's own sign-in.
- **No one-time codes for now.** Behind a gateway they were judged more than needed; they stay on the list for a hub reachable without one.
- **The CLI keeps API tokens.** It is not exposed publicly, so those tokens work from the internal network only. Agents keep their own credentials and are never affected by where they connect from.
- **Audit:** sign-ins kept 30 days, changes 365 days.
- **Identity providers:** the hub trusts an identity only when it can check it itself, as it does a signed JWT. It never trusts an identity header (such as OAM's `OAM_REMOTE_USER`), because anyone who reaches the hub's port without the gateway could set one. In the end a gateway is a gate only, and the hub's own sign-in always follows.

## Phases

Each phase is a hub pull request, plus a dashboard one where needed, merged when the owner says so.

| Phase | What | State |
|---|---|---|
| 0 | Strict settings: an unknown or removed `svcmon.*` setting stops the hub before it touches the database, naming the setting and where it came from | Done, https://github.com/primelogiclabio/service-monitor/pull/37 |
| 1 | Users and cookie sessions, forced password change, Settings screen, `users reset` | Done, https://github.com/primelogiclabio/service-monitor/pull/38 and https://github.com/primelogiclabio/service-monitor-ui/pull/16 |
| 2 | Internal-only access | Built, in review |
| 3 | Audit of sign-ins and changes | Next |
| 4 | The gateway as a gate only | Planned; details settled when it starts |

Phases 0 to 3 should all be in the next release. Phase 1 alone is safe to run inside a network; with phase 2, a hub reachable from outside refuses the people there unless `allow-outside` is set.

### Phase 1, as built

- Users are `ADMIN` or `READ`; a role counts as the token scope of the same name.
- The session is a cookie: `HttpOnly`, `SameSite=Strict`, and `__Host-svcmon` with `Secure` over HTTPS. A change sent with it also needs the header `X-Svcmon-Request: 1`. The live view takes a one-time ticket and never reads the cookie.
- Sessions end after `svcmon.sessions.max-hours` (12), after `idle-minutes` (60) without use (the page's own refreshing does not count), and at once on a password change, reset, role change, disable or delete.
- Every failed sign-in gets the same answer. A name nobody has is not logged. Guessing is throttled per address, per account and in total, apart for inside and outside.
- The old `svcmon.local-admin.*` settings stop the hub with their replacements. The old local admin keeps its password as user `admin`.
- Which addresses count as internal was fixed in phase 1 (loopback, the private IPv4 ranges, IPv6 unique local addresses); phase 2 made it a setting.

### Phase 2, as built

As planned below, plus:

- `GET /api/v1/session` answers `network`, `address`, `signInAllowed` and, when sign-in is refused, `signInRefusal` (`outside_not_allowed` or `insecure_transport`). It does not report a sign-in that may not be used from where the browser is, so the page shows the refusal instead of a dashboard that cannot load.
- `GET /api/v1/access` (any signed-in user) returns the setting, both lists and how the hub sees the caller, for the Settings screen's Access section.
- A JWT from outside is refused before it is checked, so the provider is not asked about it.
- With `allow-outside` true, a request from outside with no credential at all gets 401 (sign in), not 403.
- The start-up summary has an `Access:` line.

### Phase 2: internal-only access

- **Settings:** `svcmon.access.allow-outside` (false), `svcmon.access.internal-networks` (by default loopback, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`) and `svcmon.access.public-proxies` (empty). Values are IP addresses or ranges only, never names; `/0` is refused.
- **Where a request comes from:** the address Tomcat settles on after reading `X-Forwarded-For` from trusted proxies. A request is from outside when that address is not internal, or when it is a listed public proxy (a gateway that forgot `X-Forwarded-For` then shows up as itself and is refused, not let in). A forged header from inside can only make a request look like it comes from outside.

| Who | From outside |
|---|---|
| A signed-in user | Only when `allow-outside` is true and the request came over HTTPS (otherwise 403 `insecure_transport`) |
| A first or temporary password | Never |
| An ADMIN or READ API token (the CLI) | Never |
| A JWT through the gateway | While `allow-outside` is true, as today, until phase 4 |
| Agents, enrollment tokens, join keys | Not affected |

- **Refusal:** 403 `outside_not_allowed`, the live view included. `GET /api/v1/session` adds the network, the address and whether sign-in is allowed, so the sign-in page says "Access from outside the internal network is not allowed" instead of showing the form.
- **Checking it:** the start-up log states the policy; Settings gets a read-only Access section showing the setting and "this browser is seen as outside (address)", to check from a phone on mobile data.
- **Deployment notes for the docs:** narrow `server.tomcat.remoteip.internal-proxies` to the load balancers; a proxy must set or append `X-Forwarded-For` and send `X-Forwarded-Proto`; for nginx on the hub's own machine, bind it to `127.0.0.2` and list that in `public-proxies`, so an SSH tunnel from `127.0.0.1` stays inside.
- **Upgrade:** a hub that is public today (such as one behind a public nginx) refuses everyone from outside until `allow-outside: true` is set.

### Phase 3: audit

- **Table** `audit_events` (Flyway V6): time, category (sign-in or change), kind, outcome, who, from which address, inside or outside, what, and a short detail. Text is trimmed and control characters are stripped. No link to the users table, so events outlive the users they name.
- **Sign-ins:** succeeded, or failed with the reason (a name nobody has is recorded as "unknown"); refused for too many attempts (once per address and window) or from outside; signed out; ended by an administrator; password changed or reset; `users reset`; the first use of each JWT.
- **Changes:** every change a person makes through the API, recorded after it runs: the method, the route, the id in the path, the result and who. Request bodies are never recorded, so no secrets. Of what agents do, only a new server's registration is recorded.
- **API and screen:** `GET /api/v1/audit` for administrators, and an Activity section in Settings.
- **Retention:** `svcmon.audit.sign-in-days` (30) and `svcmon.audit.change-days` (365).

### Phase 4: the gateway as a gate only

- The hub stops turning a gateway's JWT into dashboard access: the dashboard's proxy mode (`svcmon.ui.proxy.*`) and the JWT on the live view go, and the hub's own sign-in always follows the gateway's.
- To settle then: whether `svcmon.jwt.*` goes entirely, or stays so the hub can check the gateway's token as proof that the gate was passed, without granting anything.
- A deployment such as the droplet changes its nginx: it keeps the gateway's sign-in as the gate, stops exchanging the session for a token, passes the hub's cookie and `X-Forwarded-For`/`-Proto` through, and lets `/api/v1/agent/*` past the gate. The owner applies that change.

## Later

- One-time codes (TOTP), for a hub that is reachable without a gateway.
- A Content-Security-Policy header.
- Alert channel secrets encrypted at rest.
- Re-enrolling a server whose agent credential was revoked under its old name (today the server has to be removed first).
- Auditing changes to the checks an agent declares.
- Noting when an API or agent token was last used (today the Settings screen shows when each server last reported instead).

## Pending idea

- Rename "checks" to "watchdogs" where people see them: dashboard, CLI (`svcmon check ...`), `checks.json`, API and docs. It needs its own plan: the hub already uses "watchdog" for the alert when an agent goes silent (`svcmon.watchdog-*` settings), and existing agents and scripts use the old names.
