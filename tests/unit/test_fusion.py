"""RRF is pure arithmetic over ranks, so it is tested against values computed
by hand rather than against whatever the implementation happens to produce."""

from __future__ import annotations

import uuid

import pytest

from app.schemas.retrieval import RetrievalSource, RetrievedChunk, ScoredChunk
from app.services.retrieval.fusion import (
    DEFAULT_RRF_K,
    FusionRetriever,
    reciprocal_rank_fusion,
)

KB = uuid.uuid4()


def chunk(name: str) -> RetrievedChunk:
    # Deterministic ids keep failures readable.
    return RetrievedChunk(
        chunk_id=uuid.uuid5(uuid.NAMESPACE_OID, name),
        document_id=uuid.uuid5(uuid.NAMESPACE_OID, "doc"),
        knowledge_base_id=KB,
        chunk_index=0,
        text=name,
        token_count=1,
        filename="doc.pdf",
    )


def scored(name: str, score: float, source: RetrievalSource) -> ScoredChunk:
    return ScoredChunk(chunk=chunk(name), score=score, source=source)


def dense(*names: str) -> list[ScoredChunk]:
    return [scored(name, 1.0 - i * 0.1, RetrievalSource.DENSE) for i, name in enumerate(names)]


def sparse(*names: str) -> list[ScoredChunk]:
    return [scored(name, 10.0 - i, RetrievalSource.BM25) for i, name in enumerate(names)]


def test_single_list_preserves_order() -> None:
    fused = reciprocal_rank_fusion([dense("a", "b", "c")])

    assert [item.chunk.text for item in fused] == ["a", "b", "c"]


def test_score_matches_the_formula() -> None:
    fused = reciprocal_rank_fusion([dense("a", "b")], k=60)

    assert fused[0].score == pytest.approx(1 / 61)
    assert fused[1].score == pytest.approx(1 / 62)


def test_agreement_between_retrievers_outranks_either_alone() -> None:
    # "b" is second in both lists; "a" is first in one and absent from the other.
    fused = reciprocal_rank_fusion([dense("a", "b"), sparse("c", "b")])

    # b: 1/62 + 1/62 = 0.03226   a: 1/61 = 0.01639
    assert fused[0].chunk.text == "b"
    assert fused[0].score == pytest.approx(2 / 62)


def test_weights_shift_the_balance() -> None:
    lists = [dense("a"), sparse("b")]

    dense_heavy = reciprocal_rank_fusion(lists, weights=[3.0, 1.0])
    sparse_heavy = reciprocal_rank_fusion(lists, weights=[1.0, 3.0])

    assert dense_heavy[0].chunk.text == "a"
    assert sparse_heavy[0].chunk.text == "b"


def test_k_damps_the_advantage_of_rank_one() -> None:
    small_k = reciprocal_rank_fusion([dense("a", "b")], k=1)
    large_k = reciprocal_rank_fusion([dense("a", "b")], k=1000)

    # The whole point of k: a larger k flattens the gap between positions.
    assert (small_k[0].score - small_k[1].score) > (large_k[0].score - large_k[1].score)


def test_result_is_marked_as_fused() -> None:
    fused = reciprocal_rank_fusion([dense("a")])

    # Downstream must not mistake a fused score for a dense similarity.
    assert fused[0].source is RetrievalSource.FUSION


def test_limit_truncates_after_ranking_not_before() -> None:
    fused = reciprocal_rank_fusion([dense("a", "b"), sparse("c", "b")], limit=1)

    assert len(fused) == 1
    assert fused[0].chunk.text == "b"


def test_empty_lists_fuse_to_nothing() -> None:
    assert reciprocal_rank_fusion([[], []]) == []


def test_mismatched_weights_are_rejected() -> None:
    with pytest.raises(ValueError, match="one entry per ranked list"):
        reciprocal_rank_fusion([dense("a")], weights=[1.0, 2.0])


def test_default_k_is_the_conventional_60() -> None:
    assert DEFAULT_RRF_K == 60


class _StubRetriever:
    def __init__(self, results: list[ScoredChunk], *, fail: bool = False) -> None:
        self._results = results
        self._fail = fail
        self.calls = 0

    async def retrieve(self, **_: object) -> list[ScoredChunk]:
        self.calls += 1
        if self._fail:
            raise RuntimeError("retriever exploded")
        return self._results


async def test_fusion_retriever_queries_every_retriever() -> None:
    first, second = _StubRetriever(dense("a")), _StubRetriever(sparse("b"))

    fused = await FusionRetriever(retrievers=[first, second]).retrieve(
        query="q", knowledge_base_id=KB, limit=10
    )

    assert first.calls == 1 and second.calls == 1
    assert {item.chunk.text for item in fused} == {"a", "b"}


async def test_one_failing_retriever_degrades_instead_of_failing_the_query() -> None:
    # Losing BM25 should cost precision, not availability.
    fused = await FusionRetriever(
        retrievers=[_StubRetriever(dense("a", "b")), _StubRetriever([], fail=True)]
    ).retrieve(query="q", knowledge_base_id=KB, limit=10)

    assert [item.chunk.text for item in fused] == ["a", "b"]


async def test_all_retrievers_failing_returns_nothing() -> None:
    fused = await FusionRetriever(
        retrievers=[_StubRetriever([], fail=True), _StubRetriever([], fail=True)]
    ).retrieve(query="q", knowledge_base_id=KB, limit=10)

    assert fused == []


def test_fusion_retriever_requires_at_least_one_retriever() -> None:
    with pytest.raises(ValueError, match="at least one"):
        FusionRetriever(retrievers=[])
