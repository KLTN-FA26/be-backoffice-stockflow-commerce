# ADR-0006: Server-side sessions so a token can be ended early

Status: accepted (2026-09-20)

## Context

Tokens were self-contained RS256 JWTs valid for a fixed hour, with no refresh token, no logout and no
way to end one early. `IdentityServiceImpl` called the hour "short enough that a leaked token stops
mattering quickly and long enough that this sprint's demo does not need a refresh flow": a demo
placeholder, not a policy. It had three consequences:

- a password change could not sign out a stolen or forgotten device (SCRUM-374 asked for exactly that);
- a locked account or a revoked role kept working until the token expired (ADR-0004 already records
  that a token is a snapshot of its permissions);
- staff were forced to sign in again every hour, because the lifetime was the only revocation
  mechanism there was.

## Decision

Every login inserts a row in `identity.user_session`; its id is the token's `sid` (and `jti`) claim.
The resource server's `JwtDecoder` runs a `SessionValidator` after the signature and expiry checks
and accepts the token only while the row exists, is not revoked and has not expired. A session is
ended by setting `revoked_at`:

| Action | Endpoint / trigger | Reason stored |
|---|---|---|
| Sign this device out | `POST /identity/auth/logout` | `LOGOUT` |
| Sign out every other device | `POST /identity/auth/logout-others` | `LOGOUT_OTHERS` |
| Change password, optionally with "sign out other devices" | `PUT /identity/auth/password` | `PASSWORD_CHANGED` |
| Roles granted or revoked for a user | `assignRole` / `revokeRole`, when something actually changed | `ROLE_CHANGED` |

`GET /identity/auth/sessions` lists the live ones, marking the caller's own.

Because a token can now be revoked, the lifetime only bounds how long an abandoned device keeps
working, so `stockflow.security.token-ttl` defaults to **8 hours** (a working day; at most 24h).

## Alternatives considered

- **Short access token plus rotating refresh token.** The standard answer, but it needs a token-refresh
  endpoint, refresh storage and reuse detection on the client and the server, and still cannot end an
  access token before it expires. Rejected as more work for a weaker guarantee.
- **A per-user "valid after" timestamp.** Gives "sign out all" but not "sign out this device" or a
  device list, and would need the same per-request lookup anyway.
- **A Redis denylist of `jti`s.** Loses revocations if Redis restarts, which silently un-does a
  password-change sign-out. The session table is durable.

## Consequences

- **Every authenticated request costs one primary-key read** of `user_session`, uncached on purpose (a
  cache would delay exactly what the user asked for). About 0.1 ms; the guard loads the row by id and
  judges revocation and expiry in Java so the planner cannot pick another index.
- **The database is now on the authentication path.** If it is unreachable the validator throws and the
  request fails (a 5xx, not an accepted token): revocation fails closed.
- **Tokens without a `sid` are accepted until they expire.** That is what keeps everyone signed in when
  this ships. It is also a loophole: any code path that issues a token without creating a session
  escapes revocation. `issueToken` therefore takes the session as an argument, so a new caller (for
  example customer registration, SCRUM-46) fails to compile until it creates one.
- **Rows are purged a day after expiry** by `SessionPurgeJob` (ShedLock, hourly).
- The `permissions` claim is still a snapshot for the life of the session. Role changes now end the
  user's sessions; editing what a role grants (no API yet) and locking or disabling an account (no API
  yet) must call `revokeLive` when those endpoints are written.
