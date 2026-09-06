"""Context construction and citation extraction.

The validation behaviour here is the load-bearing part: a marker the model
invented must never reach the caller as provenance.
"""

from __future__ import annotations

import uuid

from app.schemas.retrieval import RetrievalSource, RetrievedChunk, ScoredChunk
from app.services.citations.context_builder import ContextBuilder
from app.services.citations.extractor import extract_citations, strip_invalid_markers

KB = uuid.uuid4()
DOC = uuid.uuid4()


def scored(text: str, *, page: int | None = None, filename: str = "handbook.pdf") -> ScoredChunk:
    return ScoredChunk(
        chunk=RetrievedChunk(
            chunk_id=uuid.uuid5(uuid.NAMESPACE_OID, text),
            document_id=DOC,
            knowledge_base_id=KB,
            chunk_index=0,
            text=text,
            token_count=len(text.split()),
            filename=filename,
            page_number=page,
        ),
        score=0.9,
        source=RetrievalSource.RERANK,
    )


# ------------------------------------------------------------- context ------


def test_sources_are_numbered_from_one() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("first"), scored("second")])

    assert [source.marker for source in built.sources] == [1, 2]
    assert "[S1]" in built.text and "[S2]" in built.text


def test_page_number_is_carried_into_the_context() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("policy", page=7)])

    assert "page 7" in built.text


def test_identical_text_is_not_included_twice() -> None:
    # Overlapping chunks repeat text; paying budget for it twice buys nothing.
    built = ContextBuilder(token_budget=1000).build(
        [scored("same body"), scored("same body"), scored("different")]
    )

    assert len(built.sources) == 2


def test_deduplication_ignores_whitespace_differences() -> None:
    built = ContextBuilder(token_budget=1000).build(
        [scored("hello world"), scored("hello   world")]
    )

    assert len(built.sources) == 1


def test_budget_is_respected() -> None:
    built = ContextBuilder(token_budget=12).build(
        [scored(" ".join(["word"] * 200)), scored("tiny")]
    )

    assert built.token_count <= 12
    assert all("word word" not in source.scored.chunk.text for source in built.sources)


def test_empty_candidates_produce_empty_context() -> None:
    built = ContextBuilder(token_budget=100).build([])

    assert built.is_empty
    assert built.text == ""


# ----------------------------------------------------------- citations ------


def test_only_cited_sources_are_returned() -> None:
    built = ContextBuilder(token_budget=1000).build(
        [scored("alpha"), scored("beta"), scored("gamma")]
    )

    citations, invalid = extract_citations(
        "The answer is alpha [S1] and gamma [S3].", built.sources
    )

    assert [citation.marker for citation in citations] == [1, 3]
    assert invalid == []


def test_citations_follow_order_of_appearance() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("a"), scored("b")])

    citations, _ = extract_citations("Second [S2] then first [S1].", built.sources)

    assert [citation.marker for citation in citations] == [2, 1]


def test_repeated_marker_yields_one_citation() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("a")])

    citations, _ = extract_citations("One [S1]. Two [S1]. Three [S1].", built.sources)

    assert len(citations) == 1


def test_fabricated_marker_is_reported_and_not_cited() -> None:
    # The model wrote [S9] when only two sources existed. Rendering that would
    # present a hallucination as provenance.
    built = ContextBuilder(token_budget=1000).build([scored("a"), scored("b")])

    citations, invalid = extract_citations("Real [S1], invented [S9].", built.sources)

    assert [citation.marker for citation in citations] == [1]
    assert invalid == [9]


def test_invalid_markers_are_stripped_from_the_prose() -> None:
    cleaned = strip_invalid_markers("Real [S1], invented [S9].", {1})

    assert "[S9]" not in cleaned
    assert "[S1]" in cleaned
    # And the removal must not leave " ." behind.
    assert ", invented." in cleaned


def test_stripping_tidies_double_spaces() -> None:
    assert "  " not in strip_invalid_markers("A [S7] B", set())


def test_citation_carries_provenance_fields() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("policy text", page=4)])

    citations, _ = extract_citations("Answer [S1].", built.sources)

    citation = citations[0]
    assert citation.filename == "handbook.pdf"
    assert citation.page_number == 4
    assert citation.document_id == DOC
    assert citation.snippet == "policy text"


def test_long_snippets_are_truncated() -> None:
    built = ContextBuilder(token_budget=10_000).build([scored("x " * 400)])

    citations, _ = extract_citations("Answer [S1].", built.sources)

    assert len(citations[0].snippet) <= 321
    assert citations[0].snippet.endswith("…")


def test_answer_with_no_markers_yields_no_citations() -> None:
    built = ContextBuilder(token_budget=1000).build([scored("a")])

    citations, invalid = extract_citations("An answer with no citations.", built.sources)

    assert citations == [] and invalid == []
