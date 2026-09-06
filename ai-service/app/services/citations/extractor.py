"""Citation extraction and validation.

The prompt asks the model to cite [S1]..[Sn]. This turns those markers back into
real chunk references — and, critically, *drops any marker that does not
correspond to a supplied source*.

That validation is the point. A model that writes [S9] when six sources were
given has fabricated a reference, and a citation UI that renders it would be
presenting a hallucination as provenance. Checking mechanically is cheap and
certain; trusting the prompt is neither.
"""

from __future__ import annotations

import re
from collections.abc import Sequence

from app.schemas.rag import Citation
from app.services.citations.context_builder import ContextSource

_MARKER = re.compile(r"\[S(\d{1,3})\]")

# How much of the chunk to carry back as evidence the reader can scan.
_SNIPPET_CHARS = 320


def extract_citations(
    answer: str, sources: Sequence[ContextSource]
) -> tuple[list[Citation], list[int]]:
    """Return the citations actually used, plus any invalid markers found.

    Order follows first appearance in the answer, so the citation list reads in
    the same order as the prose.
    """
    by_marker = {source.marker: source for source in sources}

    used: list[int] = []
    invalid: list[int] = []
    for match in _MARKER.finditer(answer):
        marker = int(match.group(1))
        if marker in by_marker:
            if marker not in used:
                used.append(marker)
        elif marker not in invalid:
            invalid.append(marker)

    return [_to_citation(by_marker[marker]) for marker in used], invalid


def strip_invalid_markers(answer: str, valid_markers: set[int]) -> str:
    """Remove markers that point at nothing.

    Leaving them in the prose shows the reader a source they cannot open.
    """

    def replace(match: re.Match[str]) -> str:
        return match.group(0) if int(match.group(1)) in valid_markers else ""

    cleaned = _MARKER.sub(replace, answer)
    # Tidy the double spaces and space-before-punctuation a removal leaves.
    cleaned = re.sub(r" {2,}", " ", cleaned)
    return re.sub(r"\s+([.,;:!?])", r"\1", cleaned).strip()


def _to_citation(source: ContextSource) -> Citation:
    chunk = source.scored.chunk
    text = chunk.text.strip()
    snippet = text if len(text) <= _SNIPPET_CHARS else text[:_SNIPPET_CHARS].rstrip() + "…"

    return Citation(
        marker=source.marker,
        chunk_id=chunk.chunk_id,
        document_id=chunk.document_id,
        filename=chunk.filename,
        page_number=chunk.page_number,
        snippet=snippet,
        score=round(source.scored.score, 6),
    )
