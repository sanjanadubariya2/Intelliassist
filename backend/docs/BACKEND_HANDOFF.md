# IntelliAssist backend — handoff

Spring Boot 3.5 on Java 21. Owns tenancy, authentication, and every byte the
browser sees. The Python AI/RAG service does retrieval and generation and is
never reachable from a browser.

---

## Architecture

```
                       ┌───────────────┐   ┌────────────────┐
   browser ─────────►  │               │   │                │
   (dashboard, JWT)    │  Spring Boot  │──►│  Python AI/RAG │──► Gemini
                       │   :8080       │   │  :8000 private │
   widget ──────────►  │               │   │                │
   (X-Bot-Key)         └───────┬───────┘   └────────┬───────┘
                               │                    │
                        ┌──────▼──────┐      ┌──────▼──────┐
                        │ Postgres    │      │ Postgres    │
                        │ _backend    │      │ (pgvector)  │
                        └─────────────┘      └─────────────┘
```

Two databases on one server. The AI service owns chunks and embeddings; the
backend owns tenants, bots, conversations and credentials. Neither reads the
other's tables — they talk over HTTP.

**Why the split.** Retrieval is Python's world (embeddings, rerankers,
tokenisers). Tenancy, auth and transactions are Java's. Forcing either into the
other's language would mean rewriting a working library or hand-rolling
security primitives Spring already provides.

**Why the AI service is private.** It has no notion of an organization. Every
tenancy check lives in Spring, so anything that could reach Python directly
would bypass all of them. In deployment it has no public route; locally it is
published on :8000 only so you can read its OpenAPI docs.

---

## Package structure

| Package | Holds |
| --- | --- |
| `config` | Properties classes, request logging, OpenAPI |
| `controller` | HTTP only — no business logic, no entities returned |
| `dto` | Request/response records. Entities never cross the HTTP boundary |
| `entity` | JPA entities and enums |
| `exception` | `GlobalExceptionHandler` and the typed exceptions it maps |
| `integration.ai` | The **only** code that talks to Python |
| `repository` | Spring Data interfaces. No unscoped read exists |
| `security` | JWT, bot-key filter, principals, `SecurityConfig` |
| `service` | Business logic, plus the `*Store` beans (see below) |

### The `*Store` beans

`ChatStore`, `KnowledgeStore` and `UsageStore` exist for one reason: Spring's
`@Transactional` is implemented with a proxy, so a transactional method called
via `this` is **never intercepted**. Extracting the transactional work into a
separate bean is what makes the annotation take effect.

This bit three times during development, and the third was invisible because a
best-effort `catch` swallowed the failure. If you add a method that must be
transactional and is called from within the same class, put it in a `*Store`.

---

## Database

Flyway, `V1`…`V7`, with `ddl-auto: validate` — Hibernate checks the schema
matches and refuses to start otherwise. It never writes DDL.

| Migration | Adds |
| --- | --- |
| `V1__baseline` | extensions, `set_updated_at()` trigger function |
| `V2__auth` | `users`, `organizations`, `organization_members` |
| `V3__bots` | `bots` |
| `V4__knowledge` | `knowledge_bases`, `documents` |
| `V5__conversations` | `conversations`, `messages`, `message_citations` |
| `V6__public_access` | `api_keys`, `allowed_domains` |
| `V7__usage` | `usage_records` |

17 entities. Every foreign key cascades from its owner, so deleting a bot takes
its knowledge base, conversations, keys and usage rows with it — and
`BotService.delete` also tells the AI service to drop the corpus, which is
otherwise orphaned.

All ids are UUIDs. Sequential integers would let one customer discover another's
row count by watching their own ids grow.

---

## Authentication

Two credential systems that never overlap.

**Dashboard — JWT.** `POST /auth/register` or `/auth/login` returns a signed
token (HS512, 12h). Passwords are BCrypt. `JwtAuthenticationFilter` produces an
`AuthenticatedUser` carrying the user id, organization id and role.

**Widget — `X-Bot-Key`.** A 256-bit random key, prefix `ia_`, shown once and
stored only as a **SHA-256 hash**. Not BCrypt: a 256-bit random value has no
guessable structure to slow an attacker down, and a slow hash would make lookup
by hash impossible. `PublicBotAuthentication` carries the bot and organization.

They are provably disjoint. A dashboard JWT on `/api/v1/public/**` is 401, and a
bot key on a dashboard route is 401 — both covered by tests.

Widget requests must also carry an `Origin` matching one of the bot's allowed
domains. The key is public by design (it ships in page source); the domain check
is what makes a copied key useless elsewhere.

---

## Authorization and tenant isolation

The rule: **the organization id always comes from the authenticated principal,
never from the request.** Repositories expose no unscoped read — the query is
`findByIdAndOrganizationId`, so a wrong tenant returns empty rather than a row
someone forgot to check.

Cross-tenant access returns **404, not 403**. A 403 would confirm the id exists,
which is itself a leak.

---

## Public API (the widget)

```
POST /api/v1/public/chat                                → answer + conversationId
POST /api/v1/public/chat/stream                         → SSE
POST /api/v1/public/conversations/{id}/chat             → follow-up
POST /api/v1/public/conversations/{id}/chat/stream      → SSE follow-up
```

Rate limited per key (30 requests / 60s, in-memory fixed window). The response
deliberately omits `model` and `latencyMs`, which the dashboard's DTO includes —
a visitor has no business knowing which model answered.

**`X-Conversation-Id`.** A streamed reply returns the conversation id as a
response header. It cannot travel in the body: SSE events are a pass-through
from the AI service, which has no concept of a conversation. Without it, a
widget opens a new conversation on every message. The header must stay in the
public CORS `exposedHeaders` or browsers hide it — there is a test for exactly
that.

---

## AI service integration

`AiServiceClient` is the only class that talks to Python, over `WebClient` with
`X-API-Key`.

- **Reads are retried, writes never.** Ingestion is not idempotent; retrying it
  would index a document twice.
- **No transaction spans an HTTP call.** A slow AI response would otherwise hold
  a database connection open for its whole duration.
- Timeouts are configured per phase: connect, read, query, and for streams a
  first-event timeout plus an idle timeout.
- Upstream errors are logged with their request id and returned to the client as
  a generic message. Model names, provider errors and internal ids never escape.

---

## SSE streaming

Spring **MVC**, not WebFlux. `StreamingResponseBody`, not `SseEmitter`.

Three things that are easy to get wrong and are load-bearing here:

1. **Spring Security authorises every dispatch type**, including `ASYNC`. Without
   `.dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` the stream is truncated
   mid-answer by an `AuthorizationDeniedException` — with no useful error.
2. **The first event is awaited before the response is returned.** Nothing has
   been written yet, so a pre-stream failure can still be a real HTTP status
   instead of an error buried in a 200 body.
3. **Every event is flushed**, and `X-Accel-Buffering: no` is set, or nginx holds
   the whole answer until it finishes.

`StreamEventSanitizer` strips `knowledge_base_id`, `query_id` and `chunk_id`
from events, and replaces upstream error text with wording we chose. Unknown
event names are dropped rather than forwarded.

---

## Document upload

```
POST /api/v1/bots/{botId}/documents      multipart, → 202 Accepted
GET  /api/v1/documents/{documentId}      → status
```

Returns `202` with the document `PENDING`. Indexing happens on the Python
service's arq worker; clients poll until `INDEXED` or `FAILED`.

Spring never parses document contents — it validates and forwards the bytes.
Extraction is the AI service's job, and a PDF parser is a large attack surface to
put in front of authentication.

Limits: 50 MB, and only PDF, DOCX, TXT, MD. Filenames are stored, never used as
paths.

---

## Analytics

```
GET /api/v1/bots/{botId}/usage?from=YYYY-MM-DD&to=YYYY-MM-DD
```

A daily rollup table (`usage_records`), not an event log — the questions asked
are "how busy" and "how often could it not answer", and counters answer both.
Writes are an `ON CONFLICT DO NOTHING` upsert plus atomic column arithmetic, so
concurrent answers cannot lose a count.

Days with no activity are absent rather than zero-filled.

`messages` counts **both** sides of an exchange, so `answers` is half of it.
Because that convention is invisible to a client, `answers`, `groundedAnswers`
and `ungroundedAnswers` are all returned explicitly — deriving the grounding
rate from `messages` reports exactly double the true figure, which is a bug the
dashboard shipped with once.

Usage recording is best-effort: a failure there must never fail an answer the
customer already paid for.

---

## Environment variables

Required — the app refuses to start without them:

| Variable | Notes |
| --- | --- |
| `JWT_SECRET` | ≥ 32 bytes. `openssl rand -base64 48` |
| `AI_SERVICE_API_KEY` | Must equal the AI service's `INTELLIASSIST_APP__API_KEY` |

Supplied by compose, with defaults elsewhere:

| Variable | Default |
| --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | — |
| `AI_SERVICE_BASE_URL` | `http://api:8000` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` |
| `JWT_EXPIRATION` | `12h` |
| `MAX_FILE_SIZE` | `50MB` |
| `API_DOCS_ENABLED` | `true` (turn **off** in production) |
| `AI_SERVICE_*_TIMEOUT` | 2s connect, 30s idle, etc. |

No secret is hardcoded. `.env` is gitignored; `.env.example` carries the shape.

---

## Running locally

```bash
cp -n .env.example .env          # set GEMINI_API_KEY and JWT_SECRET
docker compose --profile app up -d --build
cd frontend && npm install && npm run dev
```

| | |
| --- | --- |
| Dashboard | http://localhost:3000 |
| Health | http://localhost:8080/api/v1/health |
| OpenAPI | http://localhost:8080/swagger-ui.html |
| Widget script | http://localhost:8080/widget/intelliassist.js |

```bash
cd backend && ./mvnw test        # 227 tests, Testcontainers
```

---

## Endpoints

| Method | Path | Auth |
| --- | --- | --- |
| POST | `/api/v1/auth/register` · `/login` | none |
| GET | `/api/v1/auth/me` | JWT |
| GET/POST | `/api/v1/bots` | JWT |
| GET/PUT/DELETE | `/api/v1/bots/{botId}` | JWT |
| GET/POST | `/api/v1/bots/{botId}/knowledge` | JWT |
| GET/POST | `/api/v1/bots/{botId}/documents` | JWT |
| GET/DELETE | `/api/v1/documents/{documentId}` | JWT |
| GET/POST | `/api/v1/bots/{botId}/conversations` | JWT |
| GET | `/api/v1/conversations/{id}` · `/messages` | JWT |
| POST | `/api/v1/conversations/{id}/messages` | JWT |
| POST | `/api/v1/conversations/{id}/messages/stream` | JWT (SSE) |
| POST | `/api/v1/conversations/{id}/close` | JWT |
| GET | `/api/v1/bots/{botId}/usage` | JWT |
| GET/POST | `/api/v1/bots/{botId}/api-keys` · `/domains` | JWT |
| DELETE | `/api/v1/api-keys/{id}` · `/domains/{id}` | JWT |
| POST | `/api/v1/public/chat` (+ `/stream`, `/conversations/{id}/…`) | `X-Bot-Key` + Origin |
| GET | `/api/v1/health` · `/actuator/health` | none |

---

## How to explain this architecture in an interview

**"Why two services?"**
Retrieval is a Python problem — embeddings, rerankers, tokenisers all live
there. Tenancy, auth and transactions are a Java problem. Splitting them let
each use its natural ecosystem. The cost is a network hop and a contract to keep
in sync; the benefit is not reimplementing either half badly.

**"How do you stop one customer reading another's data?"**
The organization id comes from the authenticated principal, never the request,
and no repository exposes an unscoped read — the query is always
`findByIdAndOrganizationId`. Cross-tenant access returns 404 rather than 403,
because 403 confirms the id exists. Every integration test runs with two
populated organizations, so isolation is asserted rather than assumed.

**"The widget's API key is in the page source. Isn't that broken?"**
It's public by design. The key names one bot and carries no dashboard authority
— it can't list bots or mint keys. What makes a copied key useless is the
server-side `Origin` check against the bot's registered domains, plus per-key
rate limiting and instant revocation. Treating it as a secret would be security
theatre; treating it as a public identifier with server-side constraints is the
real control.

**"Why SHA-256 for API keys but BCrypt for passwords?"**
BCrypt is slow on purpose, to make guessing a human-chosen password expensive.
An API key is 256 bits of randomness — there is nothing to guess, so slowness
buys nothing. And BCrypt salts each hash, which would make looking a key up by
its hash impossible. Different threat, different tool.

**"What was the hardest bug?"**
SSE truncating mid-answer. I chased the client, the buffering and the timeouts
before dumping raw bytes and finding an `AuthorizationDeniedException` in the
stream. Spring Security authorises *every* dispatch type, and the `ASYNC`
dispatch that resumes a streamed response was being denied. One
`dispatcherTypeMatchers` line fixed it. The lesson was to stop reasoning about
where the problem should be and go look at what actually crossed the wire.

**"What would you do differently at scale?"**
Rate limiting is per-instance and in-memory, so it multiplies by replica count —
Redis is the fix. The dashboard fans out one usage call per bot because there's
no org-wide aggregate endpoint; that's fine at ten bots and wrong at a thousand.
And follow-up questions retrieve poorly because no conversation history is sent
— query rewriting belongs in the AI service, not here.
