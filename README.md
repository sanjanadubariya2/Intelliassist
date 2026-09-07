# IntelliAssist

A standalone retrieval-augmented generation service over isolated company
knowledge bases. It ingests PDF, DOCX and TXT documents, indexes them into
PostgreSQL with pgvector, and answers questions using hybrid retrieval and a
grounded LLM call that cites its sources.

This is an **AI service, not a SaaS backend**. It has no users, no login, no
organizations and no billing. A separate Spring Boot service owns all of that
and calls the HTTP API described below.

---

## What it does

```
Document  →  extract  →  clean  →  chunk  →  embed  →  index
Query     →  embed  →  dense + BM25  →  RRF  →  rerank  →  context  →  LLM  →  citations
```

| Concern | Choice |
| --- | --- |
| LLM | Gemini (`gemini-3.1-flash-lite`) behind an `LLMProvider` protocol |
| Embeddings | `gemini-embedding-001`, truncated to **768** dimensions |
| Vector store | PostgreSQL 17 + pgvector **≥ 0.8**, HNSW / cosine |
| Sparse retrieval | `rank-bm25`, per knowledge base |
| Fusion | Reciprocal Rank Fusion, `k = 60` |
| Reranking | `cross-encoder/ms-marco-MiniLM-L-6-v2` (optional extra) |
| Queue | `arq` on Redis |

768 dimensions is not an arbitrary default: **pgvector can store 16,000
dimensions but only index 2,000**, so the native 3072-dimension embedding would
lose its index and degrade every query to a sequential scan.

---

## Quick start

Requires [uv](https://docs.astral.sh/uv/), Docker, and Node 20+.

Three pieces run together: the **Python AI service** (retrieval and generation),
the **Spring Boot backend** (tenants, auth, everything the browser talks to),
and the **Next.js dashboard**. The widget is served by the backend.

```bash
# 1. configuration
#    -n so a re-run never clobbers an .env you have already filled in.
cp -n .env.example .env
#    then set, at minimum:
#      GEMINI_API_KEY   your key from https://aistudio.google.com/apikey
#      JWT_SECRET       openssl rand -base64 48
#    AI_SERVICE_API_KEY must equal INTELLIASSIST_APP__API_KEY. The defaults
#    already match, so leave both alone unless you change one.

# 2. everything except the dashboard: Postgres, Redis, AI service,
#    ingestion worker, and the backend
docker compose --profile app up -d --build

# 3. the dashboard
cd frontend
npm install
cp .env.local.example .env.local
npm run dev
```

Then open <http://localhost:3000>, create an account, create a bot, and upload a
document. Once it reaches **Ready**, the Playground tab will answer from it.

| | |
| --- | --- |
| Dashboard | <http://localhost:3000> |
| Backend API | <http://localhost:8080/api/v1/health> |
| AI service docs | <http://localhost:8000/docs> |
| Widget script | <http://localhost:8080/widget/intelliassist.js> |

The AI service is published on :8000 for local inspection only. In a deployed
environment it sits on a private network — the browser and the widget talk to
the Spring Boot API and nothing else.

### Working on the AI service alone

It runs on its own, without the backend:

```bash
docker compose up -d          # just Postgres and Redis

cd ai-service
uv sync --all-groups
uv run uvicorn app.main:app --reload
uv run arq app.workers.ingestion_worker.WorkerSettings   # in a second shell
```

- API docs: <http://localhost:8000/docs>
- Liveness: <http://localhost:8000/health/live>
- Readiness: <http://localhost:8000/health/ready>

### Trying the widget

Any static server works; the page injects the widget exactly as a customer's
site would.

```bash
npx serve widget    # then open http://localhost:3000/demo/
```

Create an API key and add `localhost` as an allowed domain first, from the
bot's **Embed** tab — without a matching domain every request is rejected with
`401`, which is the point of the domain check.

### Without torch

`sentence-transformers` pulls torch (~2.5 GB) and is deliberately an optional
extra. Everything runs without it, using a passthrough reranker:

```bash
uv sync --all-groups                   # no reranking
uv sync --all-groups --extra rerank    # real cross-encoder reranking
```

Set `INTELLIASSIST_RERANKER__ENABLED=false` to match.

---

## Testing

```bash
cd ai-service
uv run pytest -m "not integration"   # unit: no database, no network, no API key
uv run pytest                         # everything (integration needs Docker)
uv run ruff check app tests
uv run mypy app
```

Unit tests use deterministic fake embedding and LLM providers, so the suite
needs no API key and makes no network calls.

Integration tests run against the Compose Postgres in a dedicated
`intelliassist_test` database, migrated with the **real Alembic migration**
rather than `create_all` — so the migration itself stays under test. Each test
runs inside a transaction that is rolled back, and application code commits into
savepoints, so the database is unchanged between tests. They still use the fake
AI providers: the point is the SQL, the pgvector index and the wiring.

---

## Configuration

`ai-service/app/core/config.py` is the only module that reads the environment, and the only
place a model name, dimension or threshold appears. Settings are nested, joined
by a double underscore:

```bash
INTELLIASSIST_LLM__MODEL=gemini-3.1-flash-lite
INTELLIASSIST_RETRIEVAL__FINAL_TOP_N=6
INTELLIASSIST_EMBEDDING__DIMENSION=768
```

See [`.env.example`](.env.example) for the full annotated set. The API key for
`X-API-Key` and the Gemini key are both optional locally and **required** when
`INTELLIASSIST_APP__ENV` is `staging` or `production` — enforced at startup, so
a misconfigured deploy fails immediately rather than at first request.

---

## HTTP API

All `/api/v1` routes require the `X-API-Key` header. `/health/*` never does.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/knowledge-bases` | Create an isolated knowledge base |
| `POST` | `/api/v1/knowledge/documents` | Upload a document — returns `202` |
| `GET` | `/api/v1/knowledge/documents/{id}` | Ingestion status |
| `DELETE` | `/api/v1/knowledge/documents/{id}` | Remove a document and its chunks |
| `POST` | `/api/v1/rag/query` | Answer with citations |
| `POST` | `/api/v1/rag/stream` | Same, streamed over SSE |
| `GET` | `/health/live`, `/health/ready` | Probes |

Ingestion is asynchronous because a 200-page PDF takes minutes to extract and
embed. Upload returns a document id immediately; poll the status endpoint until
`status` is `indexed`.

Every error, whatever the cause, has one shape:

```json
{
  "error": {
    "code": "not_found",
    "message": "Knowledge base 3f1a… does not exist",
    "details": {},
    "request_id": "9f2c…"
  }
}
```

`request_id` matches the `X-Request-ID` response header, and an inbound
`X-Request-ID` is preserved — so a trace started in Spring Boot keeps one id
across the hop.

---

## Deploying

See [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — a single VM with Compose, or
split across managed services, with the production settings that are not the
defaults and a checklist before you call it live.

The one rule everything else rests on: **the AI service must not be reachable
from the internet.** It has no concept of an organization, so anything that
could reach it directly would bypass every tenancy check at once.

---

## Security and deployment topology

This service is **not** internet-facing. It sits behind the Spring Boot public
API on a private network, and the two controls are independent on purpose — if
either one fails, the other still holds.

```
        Internet
           │
           ▼
   Spring Boot public API        ← owns auth, users, orgs, billing, TLS
           │
           │  private network only (no public route, no ingress)
           ▼
   Python RAG service            ← requires X-API-Key on every /api/v1 call
           │
           ▼
   Postgres + pgvector · Redis   ← never published outside the private network
```

**What `X-API-Key` is and is not.** It is a shared secret proving the caller is
the Spring Boot service. It carries no identity, no user, no roles, and no
claims. It is not authentication and must never be treated as such — end-user
identity is resolved entirely in Spring Boot, before the call reaches here.

Handling rules, all enforced in code:

| Rule | How |
| --- | --- |
| From environment only | `INTELLIASSIST_APP__API_KEY`, read once in `core/config.py` |
| Never committed | `.env` is gitignored; only `.env.example` is tracked, with a placeholder |
| Never logged | Held as `SecretStr`; startup logs `api_key_enforced=True`, never the value |
| Never in the database | No column, no migration, no cache key references it |
| Never sent to a browser | Nothing here is browser-facing; the key stays server-to-server |
| Compared securely | `secrets.compare_digest`, so a wrong key cannot be timed out character by character |

`Settings` refuses to start when `APP__ENV` is `staging` or `production` and no
key is set, so a deployment cannot silently come up unguarded. Locally the guard
is inert, which is what lets you `curl` the service without ceremony.

Health endpoints are deliberately exempt: an orchestrator has no credentials, and
a guarded probe would fail closed and take the deployment down with it.

For production, additionally: bind the container to the private network only,
publish no host port, terminate TLS at the Spring Boot edge, and rotate the key
by setting the new value and restarting — there is no persisted copy to migrate.

## Layout

Four deployable pieces, one folder each.

```
ai-service/     Python · FastAPI · the RAG pipeline. No public route.
backend/        Java · Spring Boot · tenancy, auth, and the only public API.
frontend/       TypeScript · Next.js · the dashboard.
widget/         TypeScript · the embeddable chat widget.

compose.yaml    wires them together for local development
docker/         shared infrastructure (Postgres init)
docs/           cross-cutting documentation — start at docs/README.md
```

Each folder has its own README covering how to run it, how it is laid out, and
the decisions that are load-bearing:
[ai-service](ai-service/README.md) ·
[backend](backend/README.md) ·
[frontend](frontend/README.md) ·
[widget](widget/README.md)

The dependency direction is one-way and deliberate:

```
browser ──► frontend ──┐
                       ├──► backend ──► ai-service ──► Gemini
customer site ─► widget┘
```

Nothing reaches the AI service except the backend, because the AI service has
no concept of an organization — every tenancy check lives in Spring Boot, so
anything that could bypass it would bypass all of them.

Inside `ai-service/app`:

```
api/            HTTP layer — routing, guards, error translation
core/           config, logging, exception hierarchy
db/             engine, session, Redis pool, migrations
models/         SQLAlchemy ORM — never crosses the API boundary
schemas/        Pydantic — the public contract
repositories/   all SQL, including vector search
services/
  ingestion/    extractors · cleaner · chunker · pipeline
  embeddings/   provider protocol + Gemini + fake + cache
  retrieval/    dense · bm25 · fusion (RRF)
  reranking/    cross-encoder · noop
  llm/          provider protocol + Gemini + fake + prompts
  query_rewrite/  follow-up → standalone search query
  evaluation/   metrics · dataset runner · reporting
  citations/    context builder · citation extractor
workers/        arq ingestion worker
```

Provider boundaries are `typing.Protocol`, not base classes: a test fake is a
plain object with the right methods. That is what keeps the unit suite offline.

---

## Build status

All eleven phases are implemented.

| Phase | | |
| --- | --- | --- |
| 0 | Skeleton, config, logging, Docker, health, CI | done |
| 1 | Models, migration, repositories, knowledge-base CRUD | done |
| 2 | Extraction (PDF/DOCX/TXT), cleaning, chunking | done |
| 3 | Embeddings, cache, ingestion pipeline, arq worker | done |
| 4 | Dense retrieval over pgvector | done |
| 5 | BM25 + Reciprocal Rank Fusion | done |
| 6 | Cross-encoder reranking | done |
| 7 | Generation, citations, `/rag/query` | done |
| 8 | SSE streaming | done |
| 9 | Evaluation harness | done |
| 10 | Integration tests, CI, documentation | done |

## Operator CLI

```bash
cd ai-service
uv run intelliassist config      # effective configuration, secrets masked
uv run intelliassist reindex --knowledge-base-id <uuid>
uv run intelliassist eval --knowledge-base-id <uuid> --golden-set evaluation/golden_set.example.json
```

`eval` reports hit@5, recall@5, precision@5, MRR, nDCG@5, citation precision and
abstention accuracy. `--json` emits a machine-readable report so two commits can
be compared. See `evaluation/golden_set.example.json` for the format.

Abstention accuracy is scored in both directions deliberately: a pipeline that
refuses to answer anything would otherwise score perfectly on groundedness.

## Running the worker

Ingestion happens off the request path. The API returns `202`; this consumes the
queue:

```bash
cd ai-service
uv run arq app.workers.ingestion_worker.WorkerSettings
```

Without a worker running, uploads stay at `status: pending` forever — which is
the first thing to check if a document never becomes queryable.
