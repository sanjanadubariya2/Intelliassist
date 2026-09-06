"""RAG orchestration, including the no-context fallback.

The abstention branch is the most important behaviour in the service: it is the
difference between "I don't know" and a confident fabrication.
"""

from __future__ import annotations

import uuid
from dataclasses import replace

from app.schemas.retrieval import RetrievalSource, RetrievedChunk, ScoredChunk
from app.services.llm.fake import FakeLLMProvider
from app.services.llm.prompts import NO_CONTEXT_ANSWER
from app.services.query_rewrite import ConversationTurn, RewriteResult
from app.services.rag_service import RagService
from app.services.reranking.noop import NoopReranker
from tests.conftest import make_settings

KB = uuid.uuid4()


def scored(text: str, score: float = 0.9) -> ScoredChunk:
    return ScoredChunk(
        chunk=RetrievedChunk(
            chunk_id=uuid.uuid5(uuid.NAMESPACE_OID, text),
            document_id=uuid.uuid4(),
            knowledge_base_id=KB,
            chunk_index=0,
            text=text,
            token_count=len(text.split()),
            filename="policy.pdf",
            page_number=2,
        ),
        score=score,
        source=RetrievalSource.FUSION,
    )


class _StubRetriever:
    def __init__(self, results: list[ScoredChunk]) -> None:
        self._results = results
        #: Every query the pipeline actually searched with.
        self.queries: list[str] = []

    async def retrieve(self, **kwargs: object) -> list[ScoredChunk]:
        self.queries.append(str(kwargs.get("query", "")))
        return list(self._results)


class _ScoringReranker:
    """A reranker that reports real scores, so the threshold can be exercised."""

    def __init__(self, score: float) -> None:
        self._score = score

    @property
    def name(self) -> str:
        return "stub-cross-encoder"

    @property
    def is_ready(self) -> bool:
        return True

    async def rerank(self, *, query: str, candidates, limit: int):  # type: ignore[no-untyped-def]
        return [
            candidate.rescored(self._score, RetrievalSource.RERANK)
            for candidate in candidates[:limit]
        ]


def build(retrieved: list[ScoredChunk], *, llm=None, reranker=None, query_rewriter=None):  # type: ignore[no-untyped-def]
    return RagService(
        settings=make_settings(),
        retriever=_StubRetriever(retrieved),
        reranker=reranker or NoopReranker(),
        llm=llm or FakeLLMProvider(),
        query_rewriter=query_rewriter,
    )


# ------------------------------------------------------------ answering ----


async def test_answer_is_grounded_and_cites_a_source() -> None:
    response = await build([scored("Employees get 25 days of leave.")]).answer(
        query="How much leave?", knowledge_base_id=KB
    )

    assert response.grounded is True
    assert response.citations
    assert response.citations[0].filename == "policy.pdf"


async def test_usage_and_timings_are_reported() -> None:
    response = await build([scored("some text")]).answer(query="q", knowledge_base_id=KB)

    assert response.usage.context_tokens > 0
    assert response.usage.candidates_retrieved == 1
    assert response.timings.total_ms >= 0


async def test_include_sources_false_omits_citations_but_keeps_the_answer() -> None:
    response = await build([scored("text")]).answer(
        query="q", knowledge_base_id=KB, include_sources=False
    )

    assert response.citations == []
    assert response.answer


# ------------------------------------------------------- no-context path ----


async def test_no_candidates_abstains_without_calling_the_model() -> None:
    llm = FakeLLMProvider()

    response = await build([], llm=llm).answer(query="anything", knowledge_base_id=KB)

    assert response.grounded is False
    assert response.answer == NO_CONTEXT_ANSWER
    assert response.citations == []
    # The guarantee: not a polite refusal from the model, but no call at all.
    assert llm.calls == []


async def test_score_below_threshold_abstains_without_calling_the_model() -> None:
    llm = FakeLLMProvider()
    # Default min_rerank_score is -2.0.
    service = build([scored("unrelated")], llm=llm, reranker=_ScoringReranker(-9.0))

    response = await service.answer(query="q", knowledge_base_id=KB)

    assert response.grounded is False
    assert llm.calls == []


async def test_score_above_threshold_proceeds_to_generation() -> None:
    llm = FakeLLMProvider()
    service = build([scored("relevant")], llm=llm, reranker=_ScoringReranker(4.0))

    response = await service.answer(query="q", knowledge_base_id=KB)

    assert response.grounded is True
    assert len(llm.calls) == 1


async def test_passthrough_reranker_scores_are_not_thresholded() -> None:
    # Fusion scores are ~0.016 and would fall below a cross-encoder threshold,
    # but they are on a different scale entirely — thresholding them would
    # abstain on everything.
    llm = FakeLLMProvider()

    response = await build([scored("text", score=0.016)], llm=llm).answer(
        query="q", knowledge_base_id=KB
    )

    assert response.grounded is True
    assert len(llm.calls) == 1


async def test_abstention_reports_zero_usage_but_real_retrieval_timing() -> None:
    response = await build([]).answer(query="q", knowledge_base_id=KB)

    assert response.usage.sources_used == 0
    assert response.timings.generation_ms == 0.0


# -------------------------------------------------- citation validation ----


async def test_fabricated_marker_is_stripped_from_the_answer() -> None:
    llm = FakeLLMProvider(scripted="Real fact [S1]. Invented fact [S8].")

    response = await build([scored("only source")], llm=llm).answer(query="q", knowledge_base_id=KB)

    assert "[S8]" not in response.answer
    assert "[S1]" in response.answer
    assert [citation.marker for citation in response.citations] == [1]


# ------------------------------------------------------------ streaming ----


async def test_stream_emits_the_documented_event_sequence() -> None:
    events = [
        event
        async for event in build([scored("streamed source")]).stream(
            query="q", knowledge_base_id=KB
        )
    ]
    kinds = [kind for kind, _ in events]

    assert kinds[0] == "metadata"
    assert kinds[-1] == "done"
    assert "token" in kinds
    assert kinds.count("citations") == 1


async def test_stream_sends_sources_before_the_first_token() -> None:
    # A UI can then render provenance while the answer is still arriving.
    events = [
        event
        async for event in build([scored("source text")]).stream(query="q", knowledge_base_id=KB)
    ]

    _, metadata = events[0]
    assert metadata["sources"]
    assert kinds_index(events, "token") > 0


async def test_stream_abstains_without_calling_the_model() -> None:
    llm = FakeLLMProvider()

    events = [event async for event in build([], llm=llm).stream(query="q", knowledge_base_id=KB)]
    payloads = {kind: data for kind, data in events}

    assert payloads["metadata"]["grounded"] is False
    assert payloads["done"]["grounded"] is False
    assert llm.calls == []


def kinds_index(events: list[tuple[str, dict[str, object]]], kind: str) -> int:
    return next(index for index, (name, _) in enumerate(events) if name == kind)


async def test_truncated_completion_is_flagged_to_the_caller() -> None:
    class _TruncatingLLM(FakeLLMProvider):
        async def complete(self, *, system: str, prompt: str):  # type: ignore[no-untyped-def]
            response = await super().complete(system=system, prompt=prompt)
            return replace(response, finish_reason="FinishReason.MAX_TOKENS")

    response = await build([scored("text")], llm=_TruncatingLLM()).answer(
        query="q", knowledge_base_id=KB
    )

    # The answer is still usable, but the caller must know it is incomplete.
    assert response.truncated is True
    assert response.answer


async def test_a_complete_answer_is_not_flagged_truncated() -> None:
    response = await build([scored("text")]).answer(query="q", knowledge_base_id=KB)

    assert response.truncated is False


# ------------------------------------------------- conversational rewriting --


class _FixedRewriter:
    """Always produces the same standalone query."""

    def __init__(self, rewritten: str) -> None:
        self._rewritten = rewritten
        self.seen: list[tuple[str, int]] = []

    async def rewrite(self, *, query: str, history):  # type: ignore[no-untyped-def]
        self.seen.append((query, len(history)))
        return RewriteResult(query, self._rewritten, changed=True)


async def test_the_rewritten_query_is_what_reaches_retrieval() -> None:
    """Spec item 10. The whole point of the feature.

    A follow-up embeds nowhere near its own topic; the rewritten form is what
    must reach the index.
    """
    service = build(
        [scored("Part-time staff receive a pro-rata entitlement.")],
        query_rewriter=_FixedRewriter("What leave do part-time staff receive?"),
    )

    await service.answer(
        query="What about part-timers?",
        knowledge_base_id=uuid.uuid4(),
        history=[ConversationTurn("user", "What is the leave policy?")],
    )

    retriever = service._retriever  # type: ignore[attr-defined]
    assert retriever.queries == ["What leave do part-time staff receive?"]


async def test_the_original_query_is_preserved_in_the_response() -> None:
    """Spec item 11.

    The caller persists this as the visitor's message. Storing our
    reconstruction of the question instead would show the user words they
    never typed.
    """
    service = build(
        [scored("Part-time staff receive a pro-rata entitlement.")],
        query_rewriter=_FixedRewriter("What leave do part-time staff receive?"),
    )

    response = await service.answer(
        query="What about part-timers?",
        knowledge_base_id=uuid.uuid4(),
        history=[ConversationTurn("user", "What is the leave policy?")],
    )

    assert response.query == "What about part-timers?"
    assert response.rewritten_query == "What leave do part-time staff receive?"


async def test_without_history_the_pipeline_behaves_exactly_as_before() -> None:
    """The existing contract. No history, no rewriter call, no new field."""
    rewriter = _FixedRewriter("should never be used")
    service = build([scored("Employees receive 25 days.")], query_rewriter=rewriter)

    response = await service.answer(query="How much leave?", knowledge_base_id=uuid.uuid4())

    assert rewriter.seen == []
    assert response.query == "How much leave?"
    assert response.rewritten_query is None
    retriever = service._retriever  # type: ignore[attr-defined]
    assert retriever.queries == ["How much leave?"]


async def test_a_rewriter_that_declines_leaves_retrieval_on_the_original() -> None:
    class _Declining:
        async def rewrite(self, *, query: str, history):  # type: ignore[no-untyped-def]
            return RewriteResult(query, query, changed=False, reason="error")

    service = build([scored("Employees receive 25 days.")], query_rewriter=_Declining())

    response = await service.answer(
        query="What about part-timers?",
        knowledge_base_id=uuid.uuid4(),
        history=[ConversationTurn("user", "What is the leave policy?")],
    )

    assert response.rewritten_query is None
    retriever = service._retriever  # type: ignore[attr-defined]
    assert retriever.queries == ["What about part-timers?"]


async def test_the_streamed_path_rewrites_too() -> None:
    service = build(
        [scored("Part-time staff receive a pro-rata entitlement.")],
        query_rewriter=_FixedRewriter("What leave do part-time staff receive?"),
    )

    events = [
        event
        async for event in service.stream(
            query="What about part-timers?",
            knowledge_base_id=uuid.uuid4(),
            history=[ConversationTurn("user", "What is the leave policy?")],
        )
    ]

    metadata = next(payload for name, payload in events if name == "metadata")
    assert metadata["rewritten_query"] == "What leave do part-time staff receive?"
    retriever = service._retriever  # type: ignore[attr-defined]
    assert retriever.queries == ["What leave do part-time staff receive?"]
