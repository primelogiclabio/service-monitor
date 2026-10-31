# Service Monitor: sign-in and access plan

The plan for how people sign in to the hub and from where, agreed on 2026-10-26, with what is built so far. The reference says how the built parts work; this page says what comes next and why.

## Why

The hub is the central dashboard for every connected agent and the only place alerts are sent from. People used to sign in by pasting an API token (or as the one local admin, which only handed out a 12-hour token), the sign-in page asked for a hub address, tokens sat in the browser's storage, there were no read-only people, no audit, and nothing limited where a sign-in could come from.

## Decisions

- **Sign-in is a user name and password.** No API token and no hub address on the sign-in page: the dashboard is served by the hub it talks to.
- **A new hub has `admin` / `admin`.** That password, and any temporary one an administrator gives out, works only from the internal network and allows nothing but choosing a new password.
- **A Settings screen replaces Tokens:** your own password and sign-ins, users (read-only or administrator), revoking agent credentials, and listing and revoking the CLI's tokens. Making them there came later (https://github.com/primelogiclabio/service-monitor-ui/pull/21); a token belongs to no account, so the help says to revoke a leaver's tokens.
- **Internal only by default.** `svcmon.access.allow-outside` (in `application.yml`, false by default) refuses people outside the internal network; the sign-in page says it is not allowed. Opened, the set-up until phase 5 is a gateway that signs people in first (Keycloak, Oracle OAM or another), then the hub's own sign-in; from phase 5, people outside sign in with an identity provider that the hub talks to itself.
- **No one-time codes for now.** Behind a gateway they were judged more than needed; they stay on the list for a hub reachable without one.
- **The CLI keeps API tokens.** It is not exposed publicly, so those tokens work from the internal network only. Agents keep their own credentials and are never affected by where they connect from.
- **Audit:** sign-ins kept 30 days, changes 365 days.
- **Identity providers:** the hub never trusts an identity header (such as OAM's `OAM_REMOTE_USER`), because anyone who reaches the hub's port without the gateway could set one. A gateway is a gate only, and the hub's own sign-in always follows.
- **Phase 5 (agreed 2026-10-31):** the hub signs people in with an identity provider itself, so it works with any provider and needs no one's gateway. It is for internal people with the right roles, not for public use. Provider roles map to the hub's roles, the settings are properties with a read-only screen, and the hub proves who it is to the provider with a key it makes, so there is no client secret at rest. A token handed on by a gateway or a proxy is still never accepted.

## Phases

Each phase is a hub pull request, plus a dashboard one where needed, merged when the owner says so.

| Phase | What | State |
|---|---|---|
| 0 | Strict settings: an unknown or removed `svcmon.*` setting stops the hub before it touches the database, naming the setting and where it came from | Done, https://github.com/primelogiclabio/service-monitor/pull/37 |
| 1 | Users and cookie sessions, forced password change, Settings screen, `users reset` | Done, https://github.com/primelogiclabio/service-monitor/pull/38 and https://github.com/primelogiclabio/service-monitor-ui/pull/16 |
| 2 | Internal-only access | Done, https://github.com/primelogiclabio/service-monitor/pull/40 and https://github.com/primelogiclabio/service-monitor-ui/pull/17 |
| 3 | Audit of sign-ins and changes | Done, https://github.com/primelogiclabio/service-monitor/pull/41 and https://github.com/primelogiclabio/service-monitor-ui/pull/18 |
| 4 | The gateway as a gate only | Done, https://github.com/primelogiclabio/service-monitor/pull/42 and https://github.com/primelogiclabio/service-monitor-ui/pull/19 |
| 5 | Sign-in with an identity provider, run by the hub: provider roles mapped, no client secret | Planned |

Phases 0 to 4 are in the 0.1.0 release. With phase 2, a hub reachable from outside refuses the people there unless `allow-outside` is set.

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

### Phase 3, as built

As planned above, plus:

- A failed sign-in's reason is one of `unknown_user`, `wrong_password`, `account_disabled` and `initial_password_inside_only`; the person still gets the same answer for all of them. A wrong current password when changing one's own is recorded too.
- A refusal for where a request comes from is recorded for any request, not only signing in, once per address, refusal and 15 minutes.
- Changes include refused ones (for example a read-only user's write, recorded as `forbidden`), and the user screens name the user (`dave`, with `role READ`) instead of an id. Signing in and out, password changes and resets, and ending a sign-in are sign-in events and are not written again as changes; the live ticket is neither.
- `GET /api/v1/audit` pages by event id: `before` takes the `next` of the previous page.
- Writing an event never fails a request: a database error is logged instead.

### Phase 4: the gateway as a gate only

- The hub stops turning a gateway's JWT into dashboard access: the dashboard's proxy mode (`svcmon.ui.proxy.*`) and the JWT on the live view go, and the hub's own sign-in always follows the gateway's.
- To settle then: whether `svcmon.jwt.*` goes entirely, or stays so the hub can check the gateway's token as proof that the gate was passed, without granting anything.
- A deployment such as the droplet changes its nginx: it keeps the gateway's sign-in as the gate, stops exchanging the session for a token, passes the hub's cookie and `X-Forwarded-For`/`-Proto` through, and lets `/api/v1/agent/*` past the gate. The owner applies that change.

### Phase 4, as built

- **Settled:** `svcmon.jwt.*` goes entirely. The hub accepts no provider token at all, not even as proof of the gate, and has no connection to the provider. A JWT sent as a bearer token is just an unknown token (401).
- **The gate:** any signed-in user of the provider gets past it (nginx asks the gateway's check with no API name, so no role and no token exchange); the hub's users and roles decide the rest.
- **Removed settings:** `svcmon.jwt.*` and `svcmon.ui.proxy.*` stop the hub with advice. Removed settings are now also caught in environment variables (`SVCMON_JWT_ISSUER`, `SVCMON_LOCALADMIN_PASSWORD` and the like); other environment variables are still not checked, because of the CLI's own.
- **The dashboard:** proxy mode and its gate page are gone. A 401 the hub did not send (no hub error code, or `gateway_sign_in`) makes the page reload once, so the gateway can sign the person in again; a second one within 30 seconds is reported instead. A `config.json` that still says `"proxy"` is reported on the sign-in page, and the form still works.
- **Tested locally:** nginx (Docker) with the gate configuration from the reference, a stand-in for the gateway, and the hub. That run also showed that nginx needs `absolute_redirect off`, and that a first or temporary password cannot be used through the gate (it works from inside only), so a new user's first sign-in is from inside.

### Phase 5: sign-in with an identity provider (planned)

Why: behind a gate, a person signs in twice, a new user's first sign-in needs the internal network, and the gate is one deployment's own set-up rather than part of the hub. With phase 5 the hub talks to the provider itself (OpenID Connect), so any organisation can point its hub at its own provider.

- **The flow:** the authorization code flow with PKCE (S256), run by the hub. The hub checks `state` and `nonce`, and the ID token's signature (from the provider's published keys), issuer, audience and expiry. The redirect URI is `<svcmon.public-url>/api/v1/oidc/callback`, built from the setting and never from the request's `Host`, so `public-url` is required. One provider per hub.
- **Where it works:** from inside, the sign-in page offers the password form and "Sign in with <provider>"; from outside (with `allow-outside` true and over HTTPS), only the provider. Passwords, first and temporary passwords and API tokens stay inside only. Agents are not affected.
- **Roles:** `admin-role` and `read-role` name the provider roles that make someone a hub `ADMIN` or `READ` user, read from the ID token at `roles-claim` (a dotted path such as `realm_access.roles`). Someone with neither is refused, and the refusal is recorded. Someone with both is an administrator.
- **Users:** the first sign-in makes a hub user with no password, linked to the provider's issuer and subject, never matched by name. The role is taken from the provider again at every sign-in, so a role removed there ends access at the next sign-in, and a sign-in lasts at most `svcmon.sessions.max-hours`. An administrator can disable such a user in the hub, which wins over the provider; such a user cannot be given a password. Users shows where each account signs in.
- **The session:** the same cookie session as a password sign-in, with the same limits and CSRF rules. The hub keeps no provider token. Signing out ends the hub's sign-in and, when the provider has an end-session endpoint, the provider's.
- **No client secret at rest:** the hub authenticates to the provider with `private_key_jwt`. At its first start with phase 5 configured, it makes an EC P-256 key pair and keeps the private key in its data folder, readable only by the hub; it never leaves the hub. The public key is served at `GET /api/v1/oidc/jwks` (open to anyone, as a public key is not a secret), for the provider to fetch or to be pasted into it. `java -jar svcmon-hub.jar oidc rotate-key`, run on the server, makes a new key and keeps the old one published for a while. A provider that only takes a client secret gets `client-secret-file`: the path of a file (a Docker or Kubernetes secret, a systemd credential), never the secret itself in a setting or environment variable.
- **Settings:** `svcmon.oidc.issuer`, `client-id`, `roles-claim`, `admin-role`, `read-role`, `button-label`, `scopes` (`openid profile email`), `client-auth` (`private-key-jwt`, or `client-secret` with `client-secret-file`). In `application.yml` or environment variables, strictly checked; off unless `issuer` is set, and with `issuer` set a missing role setting stops the start-up.
- **Screens:** nothing about the provider can be changed from the dashboard. Settings gets a read-only Sign-in section for administrators: the provider, the client id, how the hub authenticates (private key, with its key id), the redirect URI and the key URL to give the provider, and whether the provider answered. The start-up summary gets a `Sign-in:` line.
- **Without the provider:** the hub starts while the provider is down and keeps trying to reach it; until then the provider button says it is unavailable, and password sign-in from inside keeps working.
- **Audit:** provider sign-ins that succeeded, failed or were refused for having no role, by name; never a token or a code.
- **Trust policy, in the same pull request:** a new outbound connection (the hub to the provider, for its discovery document, its keys and its token endpoint), new stored data (provider users' issuer, subject and name; the hub's key pair) and a new library (Spring Security's OAuth 2.0 client, Apache-2.0).
- **The droplet afterwards (the owner applies it):** a Keycloak client for the hub (client authentication "Signed JWT" with the hub's key URL, the redirect URI, realm roles in the ID token), `SVCMON_OIDC_*` in the compose service, and nginx's `monitor.conf` back to a plain proxy without the gate.
- **To settle when it is built:** the hub name of a provider user whose name is taken by another user; how long an old key stays published after rotation; whether `read-role` may be left empty for a hub with administrators only.

## Later

- One-time codes (TOTP), for a hub that is reachable without a gateway.
- A Content-Security-Policy header.
- Alert channel secrets encrypted at rest.
- Re-enrolling a server whose agent credential was revoked under its old name (today the server has to be removed first).
- Auditing changes to the checks an agent declares.
- Noting when an API or agent token was last used (today the Settings screen shows when each server last reported instead).
- The hub's private key protected by the machine (a TPM through systemd credentials on Linux, DPAPI on Windows, the Keychain on macOS) rather than only by the data folder's permissions.
