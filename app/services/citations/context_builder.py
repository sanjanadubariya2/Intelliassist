"""Context construction.

Turns reranked chunks into the numbered source block the prompt consumes, under
a token budget. Three things happen here that matter for answer quality:

* **Numbering.** Sources become [S1]..[Sn]. The number is the contract between
  the prompt, the model's output and the citation extractor.
* **Budgeting.** Chunks are added while they fit. Exceeding the window either
  errors or silently truncates the *end* of the prompt — which is where the
  question lives.
* **Deduplication.** Overlapping chunks from adjacent windows repeat text.
  Spending budget on a paragraph twice buys nothing.
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from dataclasses import dataclass

from app.schemas.retrieval import ScoredChunk
from app.services.ingestion.tokenizer import count_tokens

_WHITESPACE = re.compile(r"\s+")


@dataclass(frozen=True, slots=True)
class ContextSource:
    """One numbered source, as the model will see it."""

    marker: int
    scored: ScoredChunk

    @property
    def label(self) -> str:
        chunk = self.scored.chunk
        if chunk.page_number is not None:
            return f"{chunk.filename}, page {chunk.page_number}"
        return chunk.filename


@dataclass(frozen=True, slots=True)
class BuiltContext:
    text: str
    sources: list[ContextSource]
    token_count: int

    @property
    def is_empty(self) -> bool:
        return not self.sources


class ContextBuilder:
    def __init__(self, *, token_budget: int) -> None:
        self._budget = token_budget

    def build(self, candidates: Sequence[ScoredChunk]) -> BuiltContext:
        blocks: list[str] = []
        sources: list[ContextSource] = []
        used = 0
        seen: set[str] = set()

        for scored in candidates:
            normalised = self._normalise(scored.chunk.text)
            if normalised in seen:
                continue

            marker = len(sources) + 1
            block = self._render(marker, scored)
            cost = count_tokens(block)

            if used + cost > self._budget:
                # Keep going rather than stopping: a later chunk may be small
                # enough to fit where this one was not.
                continue

            seen.add(normalised)
            blocks.append(block)
            sources.append(ContextSource(marker=marker, scored=scored))
            used += cost

        return BuiltContext(text="\n\n".join(blocks), sources=sources, token_count=used)

    @staticmethod
    def _normalise(text: str) -> str:
        return _WHITESPACE.sub(" ", text).strip().lower()

    @staticmethod
    def _render(marker: int, scored: ScoredChunk) -> str:
        chunk = scored.chunk
        heading = chunk.meta.get("heading") if isinstance(chunk.meta, dict) else None

        attribution = f"{chunk.filename}"
        if chunk.page_number is not None:
            attribution += f", page {chunk.page_number}"
        if heading:
            attribution += f", section: {heading}"

        return f"[S{marker}] ({attribution})\n{chunk.text.strip()}"
