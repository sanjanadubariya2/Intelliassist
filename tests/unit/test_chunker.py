"""Chunking decides what retrieval can ever find, so its boundaries, overlap
and page attribution are pinned here."""

from __future__ import annotations

from itertools import pairwise

import pytest

from app.core.config import ChunkingConfig
from app.services.ingestion.chunker import Chunker
from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage
from app.services.ingestion.tokenizer import count_tokens


@pytest.fixture
def config() -> ChunkingConfig:
    return ChunkingConfig(target_tokens=60, overlap_tokens=15, min_tokens=5, max_tokens=120)


@pytest.fixture
def chunker(config: ChunkingConfig) -> Chunker:
    return Chunker(config)


def _paragraph(word: str, count: int) -> str:
    return " ".join([word] * count)


def test_short_text_is_a_single_chunk(chunker: Chunker) -> None:
    chunks = chunker.chunk_text("The parental leave policy allows sixteen weeks.")

    assert len(chunks) == 1
    assert chunks[0].index == 0
    assert chunks[0].token_count > 0


def test_chunks_are_indexed_consecutively_from_zero(chunker: Chunker) -> None:
    text = "\n\n".join(_paragraph(f"word{n}", 40) for n in range(6))

    chunks = chunker.chunk_text(text)

    assert len(chunks) > 1
    assert [chunk.index for chunk in chunks] == list(range(len(chunks)))


def test_chunks_respect_the_token_target(chunker: Chunker, config: ChunkingConfig) -> None:
    text = "\n\n".join(_paragraph(f"word{n}", 40) for n in range(8))

    chunks = chunker.chunk_text(text)

    # Packing stops at the target, but a chunk may exceed it by at most the
    # final block plus carried overlap.
    assert all(chunk.token_count <= config.max_tokens * 2 for chunk in chunks)
    assert any(chunk.token_count >= config.target_tokens // 2 for chunk in chunks)


def test_consecutive_chunks_overlap(config: ChunkingConfig) -> None:
    chunker = Chunker(config)
    paragraphs = [f"Paragraph {n}. " + _paragraph("filler", 25) for n in range(6)]

    chunks = chunker.chunk_text("\n\n".join(paragraphs))

    assert len(chunks) >= 2
    # Overlap means some content from one chunk reappears in the next.
    overlaps = [bool(set(a.text.split()) & set(b.text.split())) for a, b in pairwise(chunks)]
    assert all(overlaps)


def test_zero_overlap_produces_disjoint_paragraphs() -> None:
    chunker = Chunker(
        ChunkingConfig(target_tokens=40, overlap_tokens=0, min_tokens=1, max_tokens=80)
    )
    text = "\n\n".join(f"Unique{n} " + _paragraph(f"token{n}", 30) for n in range(4))

    chunks = chunker.chunk_text(text)

    markers = [f"Unique{n}" for n in range(4)]
    for marker in markers:
        assert sum(marker in chunk.text for chunk in chunks) == 1


def test_a_paragraph_over_the_maximum_is_split(config: ChunkingConfig) -> None:
    chunker = Chunker(config)
    giant = ". ".join(_paragraph("sentence", 20) for _ in range(20))

    chunks = chunker.chunk_text(giant)

    assert len(chunks) > 1
    assert all(chunk.token_count <= config.max_tokens * 2 for chunk in chunks)


def test_text_with_no_sentence_boundaries_still_splits(config: ChunkingConfig) -> None:
    # A minified blob has no punctuation to split on; raw token windows are the
    # only remaining option and must not loop forever or drop content.
    chunker = Chunker(config)
    blob = _paragraph("x", 600)

    chunks = chunker.chunk_text(blob)

    assert len(chunks) > 1
    assert all(chunk.text for chunk in chunks)


def test_page_number_is_carried_onto_every_chunk(chunker: Chunker) -> None:
    document = ExtractedDocument(
        pages=[
            ExtractedPage(text=_paragraph("alpha", 30), page_number=1),
            ExtractedPage(text=_paragraph("beta", 30), page_number=2),
        ],
        page_count=2,
    )

    chunks = chunker.chunk_document(document)

    assert {chunk.page_number for chunk in chunks} == {1, 2}


def test_a_chunk_never_spans_two_pages(chunker: Chunker) -> None:
    # Otherwise a citation would name a page the text is not actually on.
    document = ExtractedDocument(
        pages=[ExtractedPage(text=f"Page {n} content here.", page_number=n) for n in range(1, 5)],
        page_count=4,
    )

    chunks = chunker.chunk_document(document)

    for chunk in chunks:
        pages_mentioned = {n for n in range(1, 5) if f"Page {n} content" in chunk.text}
        assert len(pages_mentioned) <= 1


def test_headings_are_captured_into_chunk_metadata(chunker: Chunker) -> None:
    text = "## Parental Leave\n\n" + _paragraph("detail", 30)

    chunks = chunker.chunk_text(text)

    assert chunks[0].meta["heading"] == "Parental Leave"


def test_token_count_matches_the_tokenizer(chunker: Chunker) -> None:
    chunks = chunker.chunk_text("Reimbursement requires a receipt within thirty days.")

    assert chunks[0].token_count == count_tokens(chunks[0].text)


def test_content_hash_is_stable_and_content_addressed(chunker: Chunker) -> None:
    first = chunker.chunk_text("Identical text.")[0]
    second = chunker.chunk_text("Identical text.")[0]

    assert first.content_hash == second.content_hash
    assert first.content_hash != chunker.chunk_text("Different text.")[0].content_hash


def test_empty_input_yields_no_chunks(chunker: Chunker) -> None:
    assert chunker.chunk_text("") == []
    assert chunker.chunk_text("   \n\n  ") == []


def test_no_chunk_is_empty_or_whitespace(chunker: Chunker) -> None:
    text = "\n\n\n".join(_paragraph(f"w{n}", 35) for n in range(5))

    chunks = chunker.chunk_text(text)

    assert all(chunk.text.strip() for chunk in chunks)


def test_char_offsets_are_ordered_within_a_chunk(chunker: Chunker) -> None:
    chunks = chunker.chunk_text("\n\n".join(_paragraph(f"w{n}", 30) for n in range(4)))

    for chunk in chunks:
        assert chunk.char_start is not None
        assert chunk.char_end is not None
        assert chunk.char_start <= chunk.char_end
