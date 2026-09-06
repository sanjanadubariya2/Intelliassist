# AI Service Integration Contract

The contract between the Spring Boot backend and the Python AI/RAG service.

**This document records what the Python service actually does.** Every endpoint,
field and status code below was extracted from the running service's generated
OpenAPI schema and its source, not from a design document. The Python service is
frozen; if this document and the Python code ever disagree, the Python code is
right and this file is the bug.

- Source of truth: `GET /openapi.json` on the AI service
- Extracted: 2026-09-04, against AI service version `0.1.0`

---

## 1. Service identity and transport

| Property | Value |
| --- | --- |
| Base URL (local) | `http://localhost:8000` |
| Base URL (compose) | `http://api:8000` |
| Protocol | HTTP/1.1, JSON, plus `multipart/form-data` for upload and SSE for streaming |
| Auth | `X-API-Key: <shared secret>` on every `/api/v1/**` route |
| Unauthenticated | `/health/live`, `/health/ready` only |
| Correlation | `X-Request-ID` request header is honoured and echoed on the response |

**The AI service is private.** It must sit on an internal network with no public
route. The browser and the widget must never call it, and the API key must never
leave the backend process.

### Auth is enforced but not self-documented

`X-API-Key` is enforced by a FastAPI dependency mounted on the whole `/api/v1`
router. It is **not** declared under `components.securitySchemes` in the OpenAPI
document, so a client generated from that schema will omit the header and get a
`401`. This is a documentation gap in a frozen service, not a behaviour we can
change — so the header is applied centrally in `AiServiceClient` rather than
per call site, where it cannot be forgotten.

Failure modes:

| Condition | Response |
| --- | --- |
| Header missing | `401` `{"error":{"code":"unauthorized","message":"Missing X-API-Key header."}}` |
| Header wrong | `401` `{"error":{"code":"unauthorized","message":"Invalid API key."}}` |

The comparison is constant-time on the Python side.

---

## 2. Endpoints

| Method | Path | Success | Purpose |
| --- | --- | --- | --- |
| `POST` | `/api/v1/knowledge-bases` | `201` | Create an isolated knowledge base |
| `GET` | `/api/v1/knowledge-bases` | `200` | List (paged) |
| `GET` | `/api/v1/knowledge-bases/{id}` | `200` | Fetch one |
| `DELETE` | `/api/v1/knowledge-bases/{id}` | `204` | Delete, cascades to documents and chunks |
| `GET` | `/api/v1/knowledge-bases/{id}/stats` | `200` | Document and chunk counts |
| `POST` | `/api/v1/knowledge/documents` | `202` | Upload a document — **asynchronous** |
| `GET` | `/api/v1/knowledge/documents/{id}` | `200` | Ingestion status |
| `DELETE` | `/api/v1/knowledge/documents/{id}` | `204` | Delete a document and its chunks |
| `GET` | `/api/v1/knowledge-bases/{id}/documents` | `200` | List documents in a knowledge base |
| `POST` | `/api/v1/rag/query` | `200` | Grounded answer with citations |
| `POST` | `/api/v1/rag/stream` | `200` | Same, as `text/event-stream` |
| `GET` | `/health/live` | `200` | Liveness — no dependencies checked |
| `GET` | `/health/ready` | `200`/`503` | Readiness — Postgres, Redis, reranker |

Every route can also return `422` for a request that fails validation.

---

## 3. Request and response schemas

Field names are **snake_case**. Jackson must be configured accordingly on the
DTOs used for this integration — see §7.

### 3.1 Create knowledge base

`POST /api/v1/knowledge-bases` → `201`

```jsonc
// request
{
  "external_ref": "bot_9f2c41",   // required, <= 255, UNIQUE across the service
  "name": "Support Handbook",     // required, <= 255
  "description": "optional",      // optional
  "metadata": {}                  // optional, free-form passthrough
}
```

```jsonc
// response — KnowledgeBaseResponse
{
  "id": "uuid",                   // store this on our KnowledgeBase row
  "external_ref": "bot_9f2c41",
  "name": "Support Handbook",
  "description": null,
  "status": "active",             // active | archived
  "embedding_model": "gemini-embedding-001",
  "embedding_dim": 768,
  "metadata": {},
  "created_at": "date-time",
  "updated_at": "date-time"
}
```

`external_ref` is an opaque caller-owned key that the AI service never
interprets. **We put our `Bot` id (or KnowledgeBase id) there**, which gives a
durable back-reference from an AI-side record to the owning tenant resource.

It is **globally unique**, so a duplicate create returns `409 conflict`. Creating
a knowledge base must therefore be idempotent on our side: on `409`, look it up
rather than failing the request.

### 3.2 Upload document

`POST /api/v1/knowledge/documents` → **`202 Accepted`**

`multipart/form-data`, exactly two parts:

| Part | Type | Notes |
| --- | --- | --- |
| `knowledge_base_id` | form field, UUID | required |
| `file` | file | required |

```jsonc
// response — DocumentAcceptedResponse
{
  "id": "uuid",                   // the AI document id -> Document.aiDocumentId
  "knowledge_base_id": "uuid",
  "filename": "handbook.pdf",
  "status": "pending",
  "duplicate": false,             // true when these exact bytes already existed
  "poll_url": "/api/v1/knowledge/documents/{id}"
}
```

Accepted MIME types: `application/pdf`,
`application/vnd.openxmlformats-officedocument.wordprocessingml.document`,
`text/plain`, `text/markdown`. Max size **50 MB**. The type is **sniffed from
content**, not trusted from the client, so a mislabelled upload is rejected with
`415` even if the declared content type is allowed.

**`duplicate: true` is a success, not an error.** The AI service hashes the bytes
and returns the existing document rather than indexing the corpus twice. The
backend must treat it as success and reuse the returned id.

### 3.3 Document status

`GET /api/v1/knowledge/documents/{id}` → `200`

```jsonc
// DocumentResponse
{
  "id": "uuid",
  "knowledge_base_id": "uuid",
  "filename": "handbook.pdf",
  "mime_type": "application/pdf",
  "status": "indexed",
  "byte_size": 51234,
  "page_count": 12,               // null for DOCX/TXT
  "chunk_count": 47,
  "error_message": null,          // populated only when status is "failed"
  "metadata": {},
  "created_at": "date-time",
  "indexed_at": "date-time"       // null until indexed
}
```

Status lifecycle:

```
pending -> extracting -> chunking -> embedding -> indexed
                                              \-> failed
```

**A document is only queryable at `indexed`.** `pending` and `failed` are the two
states the dashboard must surface clearly — `failed` always carries
`error_message`.

### 3.4 RAG query

`POST /api/v1/rag/query` → `200`

```jsonc
// request — RagQueryRequest
{
  "knowledge_base_id": "uuid",    // required
  "query": "How much leave?",     // required, 1..4000 chars
  "top_n": 6,                     // optional, 1..20
  "document_ids": ["uuid"],       // optional, restrict to these documents
  "include_sources": true         // optional, default true
}
```

```jsonc
// response — RagQueryResponse
{
  "query_id": "uuid",
  "knowledge_base_id": "uuid",
  "query": "How much leave?",
  "answer": "Full-time employees receive 25 days [S1].",
  "citations": [
    {
      "marker": 1,                // matches the [S1] marker in the answer
      "chunk_id": "uuid",
      "document_id": "uuid",      // maps back to Document.aiDocumentId
      "filename": "handbook.pdf",
      "page_number": 4,           // nullable
      "snippet": "...",
      "score": 0.83
    }
  ],
  "grounded": true,
  "model": "gemini-3.1-flash-lite",
  "truncated": false,
  "usage":   { "prompt_tokens": 0, "completion_tokens": 0, "context_tokens": 0,
               "candidates_retrieved": 0, "sources_used": 0 },
  "timings": { "retrieval_ms": 0, "rerank_ms": 0, "generation_ms": 0, "total_ms": 0 }
}
```

Three response fields carry meaning the backend must not flatten away:

- **`grounded: false`** — retrieval found nothing relevant. `answer` is a fixed
  refusal, `citations` is empty, and **the LLM was never called**
  (`timings.generation_ms == 0`). This is a normal `200`, not an error. Persist
  it as a normal assistant message; it is the correct answer to an unanswerable
  question.
- **`truncated: true`** — the model hit its output cap. The answer is usable but
  incomplete and may have lost trailing citation markers. Worth surfacing.
- **`citations[].document_id`** — join key back to our `Document.aiDocumentId`,
  which is how a citation becomes a real filename and download link in the UI.

### 3.5 RAG streaming

`POST /api/v1/rag/stream` → `200 text/event-stream`, same request body.

Event sequence, in order:

| Event | Payload | Notes |
| --- | --- | --- |
| `metadata` | `{query_id, knowledge_base_id, grounded, model, sources[]}` | **First**, before any token — lets the UI render sources while the answer streams |
| `token` | `{"text": "delta"}` | Many |
| `citations` | `{"citations": [Citation, ...]}` | Once, after generation |
| `done` | `{grounded, sources_used, context_tokens, retrieval_ms, rerank_ms, generation_ms, total_ms}` | Terminal |
| `error` | `{"code": "stream_failed", "message": "Generation failed."}` | Replaces the tail on failure |

Wire format is standard SSE: `event: <name>\ndata: <json>\n\n`.

An abstaining stream still emits `metadata` (with `grounded:false`), one `token`
carrying the refusal, an empty `citations`, then `done`.

The AI service checks for client disconnect between events and stops generating,
so **the backend proxy must propagate cancellation** — if it does not, a visitor
closing the tab still costs a full generation.

---

## 4. Error contract

Every non-2xx response has one shape:

```jsonc
{
  "error": {
    "code": "not_found",
    "message": "Knowledge base 3f1a… does not exist",
    "details": {},
    "request_id": "9f2c…"
  }
}
```

| HTTP | `code` | Meaning | Backend handling |
| --- | --- | --- | --- |
| `401` | `unauthorized` | Bad/missing `X-API-Key` | Misconfiguration → `500` to our client, never leak |
| `404` | `not_found` | Unknown knowledge base or document | `404` |
| `409` | `conflict` | Duplicate `external_ref` | Look up the existing record; do not fail |
| `413` | `payload_too_large` | Over 50 MB | `413` |
| `415` | `unsupported_document_type` | Sniffed type not allowed | `415` |
| `422` | `validation_error` / `invalid_request` / `extraction_error` | Bad request, or an unreadable document | `422` |
| `500` | `configuration_error` | AI service misconfigured | `502` |
| `502` | `provider_error` / `embedding_error` / `llm_error` | Gemini failed after retries | `503` — transient, retryable by the user |
| `503` | `dependency_unavailable` | Postgres/Redis down | `503` |

`request_id` should be logged on every failure. It is the join key between our
logs and the AI service's.

**Never propagate the AI service's `message` verbatim to a public client** — it
can name internal models and providers. Log it; return our own wording.

---

## 5. Retry policy

The AI service already retries Gemini internally with exponential backoff and
jitter, and distinguishes a transient rate limit from an exhausted quota. The
backend must **not** add a second retry layer on top of generation.

| Call | Retry? | Why |
| --- | --- | --- |
| `POST /knowledge-bases` | No | Not idempotent; a duplicate returns `409` |
| `POST /knowledge/documents` | **No** | Non-idempotent side effect. Content hashing makes a *manual* retry safe, but automatic retry risks duplicate work |
| `GET` document / knowledge base | Yes | Safe, read-only |
| `POST /rag/query` | **No** | Costs a model call; already retried upstream |
| `POST /rag/stream` | **No** | Partial output may already have shipped |
| `GET /health/*` | Yes | Safe |

Timeouts: connect **2s**; read **10s** for reads, **60s** for query (the AI
service's own LLM timeout is 60s), and **no read timeout** for the stream — only
an overall cap — since SSE is idle between tokens by design.

---

## 6. Which backend entities talk to the AI service

| Backend entity | AI-side counterpart | Link field |
| --- | --- | --- |
| `Bot` | — | Owns a `KnowledgeBase`; supplies `external_ref` |
| `KnowledgeBase` | AI knowledge base | `aiKnowledgeBaseId` (UUID) |
| `Document` | AI document | `aiDocumentId` (UUID) |
| `Conversation` | — | Backend only; the AI service is stateless per query |
| `Message` | RAG query/response | Assistant message stores the answer and citations |

**The AI service holds no conversation state.** It answers one question at a
time against a knowledge base. Conversation history, threading and memory belong
entirely to Spring Boot, and any multi-turn context must be composed by us into
the single `query` string.

Tenant isolation is enforced **twice**: the backend checks that the caller's
organization owns the bot, and the AI service scopes every retrieval to one
knowledge base. Neither layer is trusted to be the only one.

---

## 7. Integration notes for the Spring implementation

1. **snake_case.** Annotate the AI DTOs with
   `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)` rather than
   changing the global Jackson strategy, which would also rewrite our own public
   API.
2. **One client.** All calls go through `AiServiceClient`. The API key is
   attached in a `WebClient` filter, so it cannot be omitted at a call site.
3. **Never log the key.** Also never log full document bytes or answer text at
   `INFO`.
4. **`202` means asynchronous.** Persist the AI document id and `status:
   pending`, then reconcile on read. Do not block the upload request waiting for
   `indexed`.
5. **Ingestion needs a running worker.** The AI service enqueues to Redis and an
   `arq` worker performs the work. If no worker runs, documents stay `pending`
   forever — that is the first thing to check when ingestion appears stuck.
6. **`grounded: false` is a valid answer**, not an error path.

---

## 8. Verified behaviour

Confirmed against the live service, not inferred:

- Upload → `202` → worker indexed the document in **0.84 s** → `status: indexed`
  with 768-dimension vectors stored.
- Query → grounded answer citing the correct facts with a resolvable
  `document_id`.
- Query against an empty knowledge base → `grounded: false`,
  `generation_ms: 0.0` — the model genuinely was not called.
- Stream → `metadata → token → citations → done` with a cited answer over
  `text/event-stream; charset=utf-8`.
- Missing/incorrect `X-API-Key` → `401`; `/health/live` unauthenticated → `200`.

---

## 8. Streaming endpoint

`POST /api/v1/rag/stream` — same request body as `/api/v1/rag/query`, same
`X-API-Key` header. Responds `text/event-stream`, framed as
`event: <name>
data: <compact json>

`.

| Event | Order | Payload |
| --- | --- | --- |
| `metadata` | first | `query_id`, `knowledge_base_id`, `grounded`, `model`, `sources[]` |
| `token` | many | `{"text": "..."}` |
| `citations` | after tokens | `{"citations": [...]}` |
| `done` | last | `grounded`, `sources_used`, `context_tokens`, `retrieval_ms`, `rerank_ms`, `generation_ms`, `total_ms` |
| `error` | replaces `done` | `{"code": "...", "message": "..."}` |

`sources[]` entries carry `marker`, `chunk_id`, `document_id`, `filename`,
`page_number`, `score`. Citations additionally carry `snippet`.

Sources arrive before the first token, so provenance can be rendered while the
answer is still being written.

An ungrounded answer emits the full sequence with `grounded: false`, one token
containing the fixed refusal, and an empty citation list. It is a normal `200`,
not an error.

**Failure modes.** An error *before* the stream opens is an ordinary HTTP error
with the standard envelope (section 4). An error *after* it has opened arrives
as an `error` event on an already-`200` response.

**Not retried.** Generation costs a model call, and part of the answer may
already have reached the client. See section 5.

Spring's proxy behaviour, payload sanitisation and persistence rules are in
[STREAMING.md](STREAMING.md).
