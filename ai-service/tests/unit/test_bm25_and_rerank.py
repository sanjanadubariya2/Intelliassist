"""BM25 index behaviour and reranker contracts."""

from __future__ import annotations

import uuid

from app.schemas.retrieval import RetrievalSource, RetrievedChunk, ScoredChunk
from app.services.reranking.noop import NoopReranker
from app.services.retrieval.index.bm25_index import Bm25Index, tokenize

KB = uuid.uuid4()


def scored(text: str, score: float) -> ScoredChunk:
    return ScoredChunk(
        chunk=RetrievedChunk(
            chunk_id=uuid.uuid5(uuid.NAMESPACE_OID, text),
            document_id=uuid.uuid4(),
            knowledge_base_id=KB,
            chunk_index=0,
            text=text,
            token_count=len(text.split()),
            filename="f.pdf",
        ),
        score=score,
        source=RetrievalSource.FUSION,
    )


# ------------------------------------------------------------ tokenising ---


def test_tokenizer_lowercases_and_drops_punctuation() -> None:
    assert tokenize("Hello, World!") == ["hello", "world"]


def test_tokenizer_removes_stopwords() -> None:
    # Stopwords appear in nearly every chunk and flatten BM25 scores.
    assert tokenize("the cost of the licence") == ["cost", "licence"]


def test_tokenizer_keeps_identifiers_and_digits() -> None:
    # Exactly the tokens dense retrieval is weakest on.
    assert tokenize("error code E4092") == ["error", "code", "e4092"]


# ----------------------------------------------------------------- index ---


def corpus(*texts: str) -> list[tuple[uuid.UUID, str]]:
    return [(uuid.uuid5(uuid.NAMESPACE_OID, text), text) for text in texts]


def test_exact_term_match_ranks_first() -> None:
    index = Bm25Index.build(
        corpus(
            "vacation and leave entitlements for staff",
            "the espresso machine is on the third floor",
            "error code E4092 indicates a licence failure",
        )
    )
    assert index is not None

    top = index.top("E4092 licence", limit=3)

    assert top
    assert top[0][0] == uuid.uuid5(
        uuid.NAMESPACE_OID, "error code E4092 indicates a licence failure"
    )


def test_chunks_with_no_matching_term_are_excluded() -> None:
    index = Bm25Index.build(corpus("alpha beta", "gamma delta"))
    assert index is not None

    # Padding the candidate list with non-matching noise only gives fusion
    # more to sort out.
    top = index.top("alpha", limit=10)

    assert [chunk_id for chunk_id, _ in top] == [uuid.uuid5(uuid.NAMESPACE_OID, "alpha beta")]


def test_a_term_common_to_every_chunk_still_matches() -> None:
    # BM25 IDF is negative for a term in most documents. Filtering on score
    # sign would return nothing here, which is the opposite of correct.
    index = Bm25Index.build(corpus("alpha one", "alpha two", "alpha three"))
    assert index is not None

    assert len(index.top("alpha", limit=10)) == 3


def test_query_of_only_stopwords_matches_nothing() -> None:
    index = Bm25Index.build(corpus("real content here"))
    assert index is not None

    assert index.top("the and of", limit=5) == []


def test_empty_corpus_builds_no_index() -> None:
    assert Bm25Index.build([]) is None


def test_corpus_of_only_stopwords_builds_no_index() -> None:
    # BM25Okapi would divide by an average document length of zero.
    assert Bm25Index.build(corpus("the and of", "a an the")) is None


def test_limit_is_respected() -> None:
    index = Bm25Index.build(corpus("alpha one", "alpha two", "alpha three"))
    assert index is not None

    assert len(index.top("alpha", limit=2)) == 2


# -------------------------------------------------------------- reranker ---


async def test_noop_reranker_preserves_order() -> None:
    candidates = [scored("a", 0.9), scored("b", 0.8), scored("c", 0.7)]

    result = await NoopReranker().rerank(query="q", candidates=candidates, limit=3)

    assert [item.chunk.text for item in result] == ["a", "b", "c"]


async def test_noop_reranker_truncates_to_limit() -> None:
    candidates = [scored("a", 0.9), scored("b", 0.8), scored("c", 0.7)]

    result = await NoopReranker().rerank(query="q", candidates=candidates, limit=2)

    assert len(result) == 2


async def test_noop_reranker_handles_no_candidates() -> None:
    assert await NoopReranker().rerank(query="q", candidates=[], limit=5) == []


def test_noop_reranker_is_identifiable_and_ready() -> None:
    # rag_service checks the name to decide whether scores are threshold-worthy.
    reranker = NoopReranker()

    assert reranker.name == "noop"
    assert reranker.is_ready is True
