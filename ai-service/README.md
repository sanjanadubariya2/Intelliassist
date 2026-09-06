# IntelliAssist AI service

Retrieval and generation. Ingests documents, indexes them, and answers questions
from them with citations.

It knows nothing about tenants, users or conversations — those live entirely in
the Spring Boot backend, which is the only thing that calls this service. In a
deployment it has **no public route**.

```bash
uv sync --all-groups
uv run uvicorn app.main:app --reload            # API on :8000
uv run arq app.workers.ingestion_worker.WorkerSettings   # ingestion, second shell
```

Needs Postgres with pgvector and Redis; `docker compose up -d` from the repo
root starts both.

| | |
| --- | --- |
| API docs | http://localhost:8000/docs |
| Liveness | http://localhost:8000/health/live |
| Readiness | http://localhost:8000/health/ready |

## Layout

```
app/
  api/v1/         HTTP surface — the contract Spring Boot codes against
  core/           config (the only module that reads the environment), logging
  db/             session, Redis, Alembic migrations
  repositories/   chunk storage and vector search
  schemas/        request and response models
  services/
    ingestion/    extract → clean → chunk → embed → index
    retrieval/    dense, BM25, RRF fusion
    reranking/    cross-encoder, and a passthrough when torch is absent
    llm/          Gemini provider behind an LLMProvider protocol
    query_rewrite/  follow-up → standalone search query
    evaluation/   metrics, dataset runner, reporting
  workers/        arq ingestion worker
tests/            unit and integration
evaluation/       evaluation datasets
```

## The pipeline

**Ingestion:** extract → clean → chunk → embed → index.

**Query:** rewrite (if history is supplied) → dense + BM25 → RRF fusion →
cross-encoder rerank → build context → generate → extract citations.

Three decisions worth knowing:

**768 dimensions, not 3072.** pgvector stores 16,000 dimensions but only
*indexes* 2,000. The native embedding would lose its index and degrade every
query to a sequential scan, so it is Matryoshka-truncated and re-normalised.

**RRF fuses on rank, not score.** Cosine similarity, BM25 and cross-encoder
logits are on incomparable scales; adding them would let whichever has the
widest range dominate. Reciprocal rank fusion only needs the ordering.

**Abstention happens before generation.** When retrieval finds nothing above
the threshold, the model is never called. Asking a model to decline politely is
a hope; not sending the request is a guarantee, and it is cheaper.

## Documentation

- [`docs/QUERY_REWRITING.md`](../docs/QUERY_REWRITING.md)
- [`docs/RAG_EVALUATION.md`](../docs/RAG_EVALUATION.md)
- [`backend/docs/AI_INTEGRATION_CONTRACT.md`](../backend/docs/AI_INTEGRATION_CONTRACT.md)

## Tests

```bash
uv run pytest                      # everything
uv run pytest -m "not integration" # no database needed
uv run ruff check app tests
uv run mypy app
```

## Without torch

`sentence-transformers` pulls torch (~2.5 GB) and is an optional extra.
Everything runs without it using a passthrough reranker:

```bash
uv sync --all-groups                   # no reranking
uv sync --all-groups --extra rerank    # real cross-encoder reranking
```

Set `INTELLIASSIST_RERANKER__ENABLED=false` to match.
