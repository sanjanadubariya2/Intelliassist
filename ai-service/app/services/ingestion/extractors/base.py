"""Extractor protocol and the value it produces."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True, slots=True)
class ExtractedPage:
    """One page of a document.

    Formats without pagination emit a single page with ``page_number`` None,
    which keeps every extractor returning the same shape while staying honest
    about what a DOCX or TXT actually knows.
    """

    text: str
    page_number: int | None = None


@dataclass(frozen=True, slots=True)
class ExtractedDocument:
    pages: list[ExtractedPage]
    page_count: int | None = None
    meta: dict[str, Any] = field(default_factory=dict)

    @property
    def is_empty(self) -> bool:
        return not any(page.text.strip() for page in self.pages)


@runtime_checkable
class Extractor(Protocol):
    """Turns raw bytes into text, preserving page attribution where it exists."""

    @property
    def mime_types(self) -> tuple[str, ...]: ...

    def extract(self, data: bytes, *, filename: str) -> ExtractedDocument: ...
