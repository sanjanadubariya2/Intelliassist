# IntelliAssist backend

Java 21, Spring Boot 3.5. Owns tenancy, authentication, and every byte the
browser sees. The only service with a public route.

```bash
./mvnw spring-boot:run     # :8080, needs Postgres and Redis
./mvnw test                # 258 tests, real Postgres via Testcontainers
./mvnw clean package       # builds the widget in too — see below
```

`docker compose up -d` from the repo root starts the databases. Requires
`JWT_SECRET` and `AI_SERVICE_API_KEY`; the app refuses to start without them
rather than coming up half-configured.

| | |
| --- | --- |
| Health | http://localhost:8080/api/v1/health |
| Readiness | http://localhost:8080/actuator/health |
| OpenAPI | http://localhost:8080/swagger-ui.html |
| Widget script | http://localhost:8080/widget/intelliassist.js |

## Why this service exists separately

Retrieval is Python's world — embeddings, rerankers, tokenisers. Tenancy, auth
and transactions are Java's. The split lets each use its natural ecosystem.

The consequence is the rule everything else rests on: **the AI service has no
concept of an organization.** Every tenancy check lives here, so this must be
the only public door. Anything that could reach the AI service directly would
bypass all of them at once.

## Layout

```
src/main/java/com/intelliassist/backend/
  config/          properties, request logging, OpenAPI
  controller/      HTTP only — no business logic, no entities returned
  dto/             request/response records. Entities never cross the boundary
  entity/          JPA entities and enums
  exception/       GlobalExceptionHandler and the typed exceptions it maps
  integration/ai/  the only code that talks to Python
  repository/      Spring Data. No unscoped read exists
  security/        JWT, bot-key filter, principals, SecurityConfig
  service/         business logic, plus the *Store beans

src/main/resources/db/migration/   Flyway, V1…V9
```

### The `*Store` beans

`ChatStore`, `KnowledgeStore` and `UsageStore` exist for one reason: Spring's
`@Transactional` is proxy-based, so a transactional method called via `this` is
**never intercepted**. Extracting the transactional work into a separate bean is
what makes the annotation take effect.

This bit three times during development, and the third was invisible because a
best-effort `catch` swallowed the failure. If you add a method that must be
transactional and is called from within the same class, put it in a `*Store`.

## Things that are load-bearing

**Tenant isolation.** The organization id always comes from the authenticated
principal, never the request, and no repository exposes an unscoped read — the
query is `findByIdAndOrganizationId`. Cross-tenant access returns **404, not
403**, because a 403 confirms the id exists.

**Two credential systems.** Dashboard JWTs and widget bot keys are provably
disjoint: each is rejected on the other's routes, and tests assert it.

**SHA-256 for API keys, BCrypt for passwords.** BCrypt is slow on purpose, to
make guessing a human-chosen password expensive. A 256-bit random key has
nothing to guess, and BCrypt's per-hash salt would make lookup by hash
impossible.

**SSE needs `dispatcherTypeMatchers`.** Spring Security authorises *every*
dispatch type. Without permitting `ASYNC`, streamed answers truncate mid-sentence
with no useful error — it took dumping raw bytes to find.

**No transaction spans an HTTP call** to the AI service. Reads are retried,
writes never, because ingestion is not idempotent.

## Single sign-on (Google, GitHub, LinkedIn)

The authorization-code flow is implemented directly in `OAuthController`
rather than with Spring's `oauth2Login`, because that keeps a server session
and this service is stateless.

1. The dashboard links to `GET /api/v1/auth/oauth/{provider}/start`, which
   redirects to the provider with a signed, 10-minute `state` and sets a
   matching HttpOnly nonce cookie (`OAuthStateService`).
2. The provider returns to `/{provider}/callback`. The state and cookie must
   agree, which stops forged callbacks and login CSRF. The code is exchanged
   and the profile read (`OAuthClient`).
3. `AuthService.signInWithProvider` signs in the linked user, or links the
   provider to an existing **provider-only** account with the same verified
   email, or creates a user (no password) with their own workspace.
4. The browser lands on the dashboard's `/auth/callback#token=…`. The token is
   in the fragment so it never reaches a server log.

Accounts are matched on the provider's stable subject id, never email alone.
A provider sign-in never joins an account that has a password: password
sign-up does not verify addresses, so linking would let whoever registered an
address first take over its owner's sign-ins. Those users get
`sso_error=account_exists` and sign in with their password.

**Setup.** Create an OAuth app with each provider and register
`<OAUTH_PUBLIC_URL>/api/v1/auth/oauth/<google|github|linkedin>/callback` as its
redirect URI. Then set `<PROVIDER>_CLIENT_ID` and `<PROVIDER>_CLIENT_SECRET`
(see `.env.example`). Unconfigured providers show as greyed-out buttons.

| Provider | Console | Scopes requested |
| --- | --- | --- |
| Google | Google Cloud Console → APIs & Services → Credentials | `openid email profile` |
| GitHub | Settings → Developer settings → OAuth Apps | `read:user user:email` |
| LinkedIn | LinkedIn Developers → your app → Auth (add "Sign In with LinkedIn using OpenID Connect") | `openid profile email` |

## The widget ships inside the jar

`Dockerfile` builds `widget/` in a Node stage and copies the bundle into the
jar's static resources, so `GET /widget/intelliassist.js` serves exactly what
`widget/src` compiles to. That is the URL the dashboard's embed snippet hands
out; there is no CDN to configure.

Because of that, the Docker build context is the **repository root**, not this
folder — see `Dockerfile.dockerignore`.

## Documentation

- [`docs/BACKEND_HANDOFF.md`](docs/BACKEND_HANDOFF.md) — architecture, auth,
  endpoints, and how to explain it all in an interview
- [`docs/AI_INTEGRATION_CONTRACT.md`](docs/AI_INTEGRATION_CONTRACT.md) — the
  boundary with the Python service
- [`docs/STREAMING.md`](docs/STREAMING.md) — the SSE flow and its pitfalls
- [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md) — running it in production
