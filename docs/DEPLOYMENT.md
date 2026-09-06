# Deploying IntelliAssist

Four pieces, and the whole design rests on one rule: **the AI service must not be
reachable from the internet.**

It has no concept of an organization. Every tenancy check — whose bot this is,
whose documents those are, whose conversation that is — lives in Spring Boot.
Anything that could reach the AI service directly would bypass all of them at
once. It is not defence in depth; it is the only wall there is.

```
                    ┌──────────── public ────────────┐
                                                     │
  browser ─────► dashboard (Next.js)  ─────┐         │
                                           ├──► backend (Spring Boot) :8080
  customer site ─► widget ─────────────────┘         │
                                                     │
                    └────────────────────────────────┘
                                                     │  private network only
                                                     ▼
                                        ai-service (FastAPI) :8000
                                        ingestion worker (arq)
                                                     │
                                    ┌────────────────┼────────────────┐
                                    ▼                ▼                ▼
                              Postgres          Redis            Gemini API
                              + pgvector
```

---

## Before anything else: the four secrets

| Secret | Used by | Generate with |
| --- | --- | --- |
| `JWT_SECRET` | backend | `openssl rand -base64 48` |
| `AI_SERVICE_API_KEY` | backend → AI service | `openssl rand -base64 32` |
| `INTELLIASSIST_APP__API_KEY` | AI service | **the same value** as above |
| `GEMINI_API_KEY` | AI service | https://aistudio.google.com/apikey |

The two API-key entries are one shared secret seen from both ends. If they
differ, every backend call to the AI service returns 401 and nothing works.

Set `INTELLIASSIST_APP__ENV=production` on the AI service. It then **refuses to
start** without its API key and a Gemini key, rather than coming up in a state
where anything on the network could query it.

Never commit these. `.env` is gitignored; `.env.example` shows the shape.

---

## What you need

- **Postgres 16+ with pgvector ≥ 0.8.** The version matters: filtered vector
  search depends on iterative index scans, which arrived in 0.8. `pgvector/pgvector:pg17`
  ships it; on RDS or Cloud SQL check the extension version before you commit.
- **Redis** — the ingestion queue and the BM25 index cache.
- **Two databases** on the Postgres server: `intelliassist` (AI service, owns
  chunks and embeddings) and `intelliassist_backend` (tenancy, conversations).
  They are separate because neither service reads the other's tables.
- Somewhere to run three containers and one Node app.

---

## Option A — one VM with Docker Compose

The fastest honest path, and enough for a demo, a pilot, or an interview.

```bash
git clone <your-repo> && cd IntelliAssist
cp .env.example .env
# fill in GEMINI_API_KEY and JWT_SECRET, and set:
#   INTELLIASSIST_APP__ENV=production
#   CORS_ALLOWED_ORIGINS=https://app.yourdomain.com
#   API_DOCS_ENABLED=false

docker compose --profile app up -d --build
```

That starts Postgres, Redis, the AI service, the ingestion worker and the
backend. **Then close the AI service's port.**

`compose.yaml` publishes `8000:8000` for local inspection. In production, delete
that `ports:` block from the `api` service. The backend reaches it as
`http://api:8000` over the compose network, which does not require the port to
be published at all. Then make sure your firewall exposes **only 80 and 443**.

Put a reverse proxy in front for TLS. With Caddy, the whole config is:

```caddyfile
api.yourdomain.com {
    reverse_proxy localhost:8080
}

app.yourdomain.com {
    reverse_proxy localhost:3000
}
```

Caddy gets certificates automatically. With nginx you must add
`proxy_buffering off;` on the API host, or SSE answers arrive all at once when
the response finishes instead of streaming — the backend already sends
`X-Accel-Buffering: no`, but only nginx honours it.

The dashboard:

```bash
cd frontend
echo "NEXT_PUBLIC_API_URL=https://api.yourdomain.com" > .env.local
npm ci && npm run build && npm start
```

Run it under systemd or `pm2` so it survives a reboot.

> **`NEXT_PUBLIC_*` is baked in at build time.** Changing the API URL means
> rebuilding the frontend, not restarting it. Setting it only as a runtime
> variable is the most common way this deployment goes wrong: the app builds
> fine and then calls `localhost:8080` from your users' browsers.

---

## Option B — split across managed services

| Piece | Where | Notes |
| --- | --- | --- |
| Postgres | RDS / Cloud SQL / Neon | Confirm pgvector ≥ 0.8 |
| Redis | ElastiCache / Upstash | Small; it holds a queue and a cache |
| Backend | Fly.io / Render / ECS | Public. The only public service. |
| AI service + worker | Same private network | **No public route.** Internal DNS only. |
| Dashboard | Vercel / Netlify | Set `NEXT_PUBLIC_API_URL` as a build variable |

The AI service and its worker run the **same image** with different commands:

```
api:     uvicorn app.main:app --host 0.0.0.0 --port 8000
worker:  arq app.workers.ingestion_worker.WorkerSettings
```

The worker serves no HTTP. If your platform insists on a health check, check
that it can reach Redis rather than an HTTP port it does not have — compose
does exactly this, and an HTTP probe there is a permanently-red status that
hides real failures.

Whatever the platform, the rule holds: **the AI service gets a private address
and nothing else.** On Fly that is `.internal` DNS; on ECS a service in a
private subnet with no load balancer; on Render a private service.

---

## Database migrations

Both run automatically at startup, and both are safe to re-run.

- **Backend** — Flyway, `V1`…`V9`, on boot. Hibernate is set to
  `ddl-auto: validate`: it checks the schema matches and refuses to start
  otherwise. It never writes DDL, so the migrations are the only source of
  truth.
- **AI service** — Alembic. `cd ai-service && uv run alembic upgrade head`, or
  let the container entrypoint do it.

Take a backup before deploying a new version. Neither system has a down
migration you would want to rely on at three in the morning.

---

## Production settings that are not the default

| Variable | Default | Set it to | Why |
| --- | --- | --- | --- |
| `API_DOCS_ENABLED` | `true` | **`false`** | Swagger publishes every endpoint and DTO — a catalogue for anyone probing |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | your dashboard origin | Dashboard CORS is credentialed and must never be `*` |
| `INTELLIASSIST_APP__ENV` | `local` | `production` | Makes the API key mandatory instead of optional |
| `INTELLIASSIST_APP__LOG_JSON` | `false` | `true` | Structured logs your aggregator can actually query |
| `JWT_EXPIRATION` | `12h` | shorter, if you like | There is no refresh token; shorter means re-login |
| `MAX_FILE_SIZE` | `50MB` | as you need | Applies before anything is parsed |

The widget's CORS is separate and deliberately permissive (`*` origins, no
credentials) — it has to be, because it runs on customers' sites. What secures
it is not CORS but the server-side check of `Origin` against each bot's allowed
domains, plus per-key rate limiting.

---

## The widget

You do not deploy it separately. `backend/Dockerfile` builds it in a Node stage
and bakes it into the jar, so the backend serves it at:

```
https://api.yourdomain.com/widget/intelliassist.js
```

That is exactly the URL the dashboard's embed snippet hands out. Customers add
one script tag; there is no CDN to configure.

Each customer must add their domain in **Domains** before their widget works.
Without a matching allowed domain every request is a 401 — which is the point.

---

## Health checks

| Endpoint | Use for |
| --- | --- |
| `GET /api/v1/health` | Backend liveness |
| `GET /actuator/health` | Backend readiness — includes database and AI service |
| `GET /health/live` | AI service liveness (private) |
| `GET /health/ready` | AI service readiness — database and Redis (private) |

Use `/actuator/health` for readiness rather than `/api/v1/health`: the latter
returns 200 while the database is down.

---

## Scaling, and where it stops

Honest limits, so you find them before your users do:

**Rate limiting is in-memory and per-instance.** Two backend replicas mean a
caller gets double the intended allowance. One replica is correct today; more
than one needs the limiter moved to Redis first.

**The dashboard fans out per bot.** The overview and analytics screens call the
usage endpoint once per bot because there is no org-wide aggregate. Fine at ten
bots, wrong at a thousand — the fix is an aggregate endpoint, not a cleverer
client.

**The AI service is stateless and scales horizontally.** The worker does too;
arq distributes jobs across however many you run.

**The reranker is the memory cost.** With `INTELLIASSIST_RERANKER__ENABLED=true`
the image pulls torch (~2.5 GB) and loads a cross-encoder. Leave it off and the
pipeline uses a passthrough reranker — retrieval quality drops, nothing breaks.
Decide deliberately; do not discover it from an OOM kill.

**Gemini quota is a real ceiling.** Free-tier limits are easy to hit with a
handful of users; the service surfaces exhaustion as a 503 rather than
pretending. Watch for it.

---

## Before you call it live

- [ ] AI service has **no public route** — verify from outside your network
- [ ] `API_DOCS_ENABLED=false`
- [ ] `INTELLIASSIST_APP__ENV=production`
- [ ] `CORS_ALLOWED_ORIGINS` is your real dashboard origin, not `*`
- [ ] `JWT_SECRET` is 32+ random bytes, not a passphrase
- [ ] `AI_SERVICE_API_KEY` matches `INTELLIASSIST_APP__API_KEY`
- [ ] TLS on both public hosts
- [ ] `proxy_buffering off` if you are behind nginx
- [ ] Postgres backups scheduled and **restore tested once**
- [ ] Frontend built with the production `NEXT_PUBLIC_API_URL`
- [ ] Register a test account, upload a document, ask a question, check the
      citation. Nothing else proves the whole chain works.

---

## When something is wrong

**Backend will not start** — read the first error. A missing `JWT_SECRET` or
`AI_SERVICE_API_KEY` says so explicitly and by name; that is deliberate.

**Every chat returns 503** — the backend cannot reach the AI service. Check
`AI_SERVICE_BASE_URL` and that both API keys match.

**Documents stay PENDING for ever** — the worker is not running, or cannot reach
Redis. Check its logs; a crash-looping worker is silent from the dashboard.

**Streaming arrives all at once** — proxy buffering. See the nginx note above.

**Widget returns 401 on a customer's site** — their domain is not in the bot's
allowed domains, or the key was revoked.

**Dashboard calls localhost in production** — the frontend was built without
`NEXT_PUBLIC_API_URL`. Rebuild, do not restart.
