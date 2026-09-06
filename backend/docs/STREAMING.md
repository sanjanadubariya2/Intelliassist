# Streaming

How a streamed answer travels from the Python AI service to a browser, and why
it is built this way.

---

## Why SSE

A streamed answer is **one-directional and text-only**: the server pushes
tokens, the client never sends anything back on that channel. Server-Sent
Events fit exactly that, and cost nothing to adopt — it is plain HTTP, so
proxies, load balancers and `curl` all work unchanged.

WebSockets would add a second protocol, a second failure mode and a second
security boundary, in exchange for a return channel nothing uses. The AI
service already speaks SSE, so proxying it keeps one protocol end to end.

---

## The flow

```
Browser / widget
      │  POST /api/v1/conversations/{id}/messages/stream
      │  Authorization: Bearer <JWT>
      ▼
Spring Boot ── authorise ──▶ 404 / 403 if not yours   (before any byte is sent)
      │
      │  POST /api/v1/rag/stream
      │  X-API-Key: <server-side only>
      ▼
Python AI service ── retrieval → rerank → Gemini
      │
      │  event: metadata / token / citations / done
      ▼
Spring Boot ── sanitise ── accumulate ── forward
      │
      ▼
Browser / widget          then: persist the finished answer
```

The browser never addresses Python and never sees the API key.

---

## The Python contract

`POST /api/v1/rag/stream`, `Content-Type: application/json`, `X-API-Key`
required. Body is the same shape as `/api/v1/rag/query`.

Response is `text/event-stream`, framed as `event: <name>\ndata: <json>\n\n`:

| Event | When | Payload |
| --- | --- | --- |
| `metadata` | first, before any token | `query_id`, `knowledge_base_id`, `grounded`, `model`, `sources[]` |
| `token` | many | `{"text": "..."}` |
| `citations` | after the last token | `{"citations": [...]}` |
| `done` | last | `grounded`, `sources_used`, `context_tokens`, timings |
| `error` | instead of `done` | `{"code": "...", "message": "..."}` |

Sources arrive **before** the first token, so a UI can show provenance while
the answer is still being written.

An ungrounded answer is not an error: it emits all four events with
`grounded: false`, a fixed refusal as its single token, and no citations.

---

## What Spring changes

Event **names and order are preserved exactly**, so a client written against
the AI contract works against ours. Two payload changes:

**Internal identifiers are stripped.** `metadata.knowledge_base_id`,
`metadata.query_id` and every `chunk_id` are removed. They address a private
service the browser must never reach, and a `chunk_id` is meaningless to a
client anyway — it changes whenever a document is re-ingested. Marker,
`document_id`, filename, page number, snippet and score all survive.

**Upstream error text is replaced.** An AI `error` event becomes
`{"code":"AI_SERVICE_ERROR","message":"The assistant could not complete the
answer."}`. The original names models and providers and is logged, not sent.

---

## Persistence

Nothing is written until the answer is whole.

`StreamAccumulator` reassembles the token fragments and records whether a
`done` event arrived. Only then is the exchange persisted — both turns
together, exactly as the synchronous endpoint does.

**Why not persist progressively:** a transcript holding half an answer is worse
than one holding none. It reads as authoritative while being cut off, quite
possibly before the sentence that qualified it.

| Outcome | Persisted? |
| --- | --- |
| Stream completes with `done` | Yes — question and answer |
| Client disconnects after `done` | Yes — the answer is whole |
| Client disconnects mid-answer | **No** |
| Upstream fails mid-answer | **No** |
| Upstream fails before starting | **No** |
| Ungrounded refusal | Yes — a refusal is a real answer, and recording it is what makes corpus gaps visible |

Token usage is not reported over the stream, so streamed messages store `0`.
The synchronous endpoint is where accounting figures come from.

---

## Errors

The dividing line is **whether anything has been written yet**, because once
bytes are on the wire the status line cannot be taken back.

The first upstream event is awaited *before* the response body begins. Until
that arrives the status is still ours to choose:

| When | Result |
| --- | --- |
| Not your conversation | `404`, normal error body |
| Not authenticated | `401` |
| Bot inactive / no knowledge base | `409`, normal error body |
| AI service down, before first event | `503` |
| AI service timeout, before first event | `504` |
| Failure **after** the first event | `200` + `event: error`, stream closed |

---

## Implementation notes

**`StreamingResponseBody`, not `Flux` or `SseEmitter`.** This is a Spring MVC
application. `Flux<ServerSentEvent>` is the WebFlux idiom and MVC does not
unwrap it — event names end up buried inside JSON. `SseEmitter` frames
correctly but is completed from whichever thread the reactive pipeline is on,
which can race the container's async initialisation and truncate the response
before its terminating chunk. `StreamingResponseBody` has neither problem: the
container owns the thread and the framing is written explicitly.

The cost is one container thread per active stream, bounding concurrency at
Tomcat's pool size. That is the right trade here — predictable behaviour over a
reactive rewrite of an otherwise blocking application.

**Spring Security must permit the ASYNC dispatch.** Spring Security 6
authorises *every* dispatcher type by default. A streamed response continues on
an `ASYNC` dispatch that carries no authentication, so without
`dispatcherTypeMatchers(ASYNC, ERROR).permitAll()` the continuation of an
already-authorised request is denied and the response is truncated mid-stream.
This is safe: the original `REQUEST` dispatch was fully authorised before the
controller returned.

**Timeouts bound latency, not length.** A long answer is legitimately long, so
there is no total-duration cap.

| Setting | Default | Bounds |
| --- | --- | --- |
| `stream-first-event-timeout` | 75s | Waiting for retrieval, reranking and the first token |
| `stream-idle-timeout` | 30s | Silence between events once flowing |

The connection-level read guard is sized from the largest per-call budget plus
a margin, so it never fires before a stream's own timeout.

**Cancellation propagates.** The response body iterates a closeable view of the
reactive stream; a write failure means the client has gone, and closing that
view cancels the subscription, which closes the connection to the AI service so
it stops generating tokens nobody will read.

**Buffering is defeated deliberately.** Each event is flushed individually, and
the response carries `Cache-Control: no-cache` and `X-Accel-Buffering: no` —
nginx buffers by default, which would hold every token until the answer
finished.

---

## Security boundaries

Two, and they are unrelated.

**Client → Spring** uses the dashboard JWT, exactly as every other `/api/v1`
route does.

**Spring → Python** uses `X-API-Key`, attached by a `WebClient` filter so no
call site can forget it. The key exists only in server-side configuration. It
is never returned, never logged, never placed in an SSE event, never stored in
the database and never sent to a browser. Two tests assert the last of those
directly.
