"""Structure-aware chunking.

The strategy, in order of preference:

1. Split on structure — headings first, then paragraphs. A chunk that begins at
   a heading and ends before the next one is a chunk a human would recognise.
2. Pack consecutive blocks up to a token target, so chunks are uniformly sized
   without cutting mid-thought.
3. Only when a single block exceeds the hard maximum, split it on sentence
   boundaries, and only then on raw tokens.

Overlap is carried between chunks so a fact sitting on a boundary appears whole
in at least one of them.

A chunk never spans two pages of a PDF, which is what keeps page attribution on
a citation truthful.
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field
from typing import Any

from app.core.config import ChunkingConfig
from app.services.ingestion.extractors.base import ExtractedDocument
from app.services.ingestion.tokenizer import count_tokens, decode, encode

_HEADING = re.compile(r"^(#{1,6})\s+(.+)$")
_PARAGRAPH_SPLIT = re.compile(r"\n\s*\n")
# Sentence end followed by whitespace and a capital/quote/digit.
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?])\s+(?=[A-Z\"'(\[0-9])")


@dataclass(frozen=True, slots=True)
class Chunk:
    text: str
    index: int
    token_count: int
    page_number: int | None = None
    char_start: int | None = None
    char_end: int | None = None
    meta: dict[str, Any] = field(default_factory=dict)

    @property
    def content_hash(self) -> str:
        return hashlib.sha256(self.text.encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class _Block:
    """A paragraph or heading, with the heading trail that leads to it."""

    text: str
    tokens: int
    page_number: int | None
    heading: str | None
    char_start: int
    char_end: int


class Chunker:
    def __init__(self, config: ChunkingConfig) -> None:
        self._config = config

    def chunk_document(self, document: ExtractedDocument) -> list[Chunk]:
        chunks: list[Chunk] = []
        for block_group in self._blocks_by_page(document):
            chunks.extend(self._pack(block_group, start_index=len(chunks)))
        return chunks

    def chunk_text(self, text: str, *, page_number: int | None = None) -> list[Chunk]:
        """Chunk a bare string. Used by tests and by the text ingestion path."""
        blocks = list(self._blocks(text, page_number=page_number))
        return self._pack(blocks, start_index=0)

    # ------------------------------------------------------------ blocking --

    def _blocks_by_page(self, document: ExtractedDocument) -> list[list[_Block]]:
        """Group blocks per page so packing never merges across a page break."""
        return [
            blocks
            for page in document.pages
            if (blocks := list(self._blocks(page.text, page_number=page.page_number)))
        ]

    def _blocks(self, text: str, *, page_number: int | None):  # type: ignore[no-untyped-def]
        heading: str | None = None
        cursor = 0

        for raw in _PARAGRAPH_SPLIT.split(text):
            block = raw.strip()
            if not block:
                continue

            # Locate the block in the original text so char offsets stay real.
            found = text.find(block, cursor)
            char_start = found if found >= 0 else cursor
            char_end = char_start + len(block)
            cursor = char_end

            match = _HEADING.match(block)
            if match:
                heading = match.group(2).strip()

            yield _Block(
                text=block,
                tokens=count_tokens(block),
                page_number=page_number,
                heading=heading,
                char_start=char_start,
                char_end=char_end,
            )

    # ------------------------------------------------------------- packing --

    def _pack(self, blocks: list[_Block], *, start_index: int) -> list[Chunk]:
        chunks: list[Chunk] = []
        buffer: list[_Block] = []
        buffered_tokens = 0

        def flush() -> None:
            nonlocal buffer, buffered_tokens
            if not buffer:
                return
            chunks.append(self._to_chunk(buffer, index=start_index + len(chunks)))
            # Carry the tail of this chunk into the next one.
            buffer = self._overlap_tail(buffer)
            buffered_tokens = sum(block.tokens for block in buffer)

        for block in blocks:
            if block.tokens > self._config.max_tokens:
                flush()
                for piece in self._split_oversized(block):
                    chunks.append(self._to_chunk([piece], index=start_index + len(chunks)))
                buffer, buffered_tokens = [], 0
                continue

            if buffered_tokens + block.tokens > self._config.target_tokens and buffer:
                flush()

            buffer.append(block)
            buffered_tokens += block.tokens

        # The final buffer may be only overlap carried from the previous chunk,
        # which would duplicate content already indexed.
        if buffer and not self._is_only_overlap(buffer, chunks):
            chunks.append(self._to_chunk(buffer, index=start_index + len(chunks)))

        return [
            chunk
            for chunk in chunks
            if chunk.token_count >= self._config.min_tokens or len(chunks) == 1
        ]

    def _overlap_tail(self, buffer: list[_Block]) -> list[_Block]:
        if self._config.overlap_tokens <= 0:
            return []

        tail: list[_Block] = []
        total = 0
        for block in reversed(buffer):
            if total + block.tokens > self._config.overlap_tokens:
                break
            tail.insert(0, block)
            total += block.tokens
        return tail

    @staticmethod
    def _is_only_overlap(buffer: list[_Block], chunks: list[Chunk]) -> bool:
        if not chunks:
            return False
        text = "\n\n".join(block.text for block in buffer)
        return text in chunks[-1].text

    def _split_oversized(self, block: _Block):  # type: ignore[no-untyped-def]
        """A single paragraph larger than the maximum: sentences, then tokens."""
        sentences = _SENTENCE_SPLIT.split(block.text)

        current: list[str] = []
        current_tokens = 0
        for sentence in sentences:
            sentence_tokens = count_tokens(sentence)

            if sentence_tokens > self._config.max_tokens:
                if current:
                    yield self._rebuild(block, " ".join(current))
                    current, current_tokens = [], 0
                # No sentence boundary to use — fall back to raw token windows.
                for window in self._token_windows(sentence):
                    yield self._rebuild(block, window)
                continue

            if current_tokens + sentence_tokens > self._config.target_tokens and current:
                yield self._rebuild(block, " ".join(current))
                current, current_tokens = [], 0

            current.append(sentence)
            current_tokens += sentence_tokens

        if current:
            yield self._rebuild(block, " ".join(current))

    def _token_windows(self, text: str):  # type: ignore[no-untyped-def]
        tokens = encode(text)
        step = max(1, self._config.target_tokens - self._config.overlap_tokens)
        for start in range(0, len(tokens), step):
            window = tokens[start : start + self._config.target_tokens]
            if window:
                yield decode(window)

    @staticmethod
    def _rebuild(block: _Block, text: str) -> _Block:
        stripped = text.strip()
        return _Block(
            text=stripped,
            tokens=count_tokens(stripped),
            page_number=block.page_number,
            heading=block.heading,
            char_start=block.char_start,
            char_end=block.char_end,
        )

    def _to_chunk(self, blocks: list[_Block], *, index: int) -> Chunk:
        text = "\n\n".join(block.text for block in blocks)
        heading = next((block.heading for block in blocks if block.heading), None)

        meta: dict[str, Any] = {}
        if heading:
            # Carried into the retrieved context so a chunk keeps the section it
            # came from, which is often what makes it interpretable.
            meta["heading"] = heading

        return Chunk(
            text=text,
            index=index,
            token_count=count_tokens(text),
            page_number=blocks[0].page_number,
            char_start=blocks[0].char_start,
            char_end=blocks[-1].char_end,
            meta=meta,
        )
