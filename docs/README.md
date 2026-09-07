# Documentation

Component documentation lives with its component; this folder holds what spans
more than one.

## Start here

| | |
| --- | --- |
| [Repository README](../README.md) | What the project is, and the quick start |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Running it in production, and the settings that are not the defaults |

## Cross-cutting

| | |
| --- | --- |
| [QUERY_REWRITING.md](QUERY_REWRITING.md) | Turning a follow-up into a standalone search query — and why concatenating history into the embedding makes retrieval *worse* |
| [RAG_EVALUATION.md](RAG_EVALUATION.md) | Measuring answer quality: the dataset format, how each metric is computed, and what none of them measure |

## Per component

| | |
| --- | --- |
| [ai-service/README.md](../ai-service/README.md) | The RAG pipeline |
| [backend/README.md](../backend/README.md) | Tenancy, auth, the public API |
| [backend/docs/BACKEND_HANDOFF.md](../backend/docs/BACKEND_HANDOFF.md) | Full backend architecture, plus how to explain it in an interview |
| [backend/docs/AI_INTEGRATION_CONTRACT.md](../backend/docs/AI_INTEGRATION_CONTRACT.md) | The backend ↔ AI service boundary |
| [backend/docs/STREAMING.md](../backend/docs/STREAMING.md) | The SSE flow end to end |
| [frontend/README.md](../frontend/README.md) | The dashboard |
| [widget/README.md](../widget/README.md) | The embeddable widget |

## The one thing to know

The AI service has **no concept of an organization**. Every tenancy check lives
in the Spring Boot backend, so the backend must be the only public door —
anything that could reach the AI service directly would bypass all of them at
once. That single constraint explains most of the architecture, and most of
[DEPLOYMENT.md](DEPLOYMENT.md).
