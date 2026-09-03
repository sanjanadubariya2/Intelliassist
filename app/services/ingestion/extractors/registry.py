"""Maps a MIME type to the extractor that handles it."""

from __future__ import annotations

import mimetypes
from pathlib import Path

from app.core.exceptions import UnsupportedDocumentTypeError
from app.services.ingestion.extractors.base import Extractor
from app.services.ingestion.extractors.docx import DocxExtractor
from app.services.ingestion.extractors.pdf import PdfExtractor
from app.services.ingestion.extractors.txt import TextExtractor

# Magic bytes, checked before any client-supplied MIME type. A browser will
# happily send application/octet-stream, and a caller can send anything at all.
_SIGNATURES: tuple[tuple[bytes, str], ...] = (
    (b"%PDF-", "application/pdf"),
    (
        b"PK\x03\x04",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ),
)

_EXTENSION_OVERRIDES = {
    ".md": "text/markdown",
    ".markdown": "text/markdown",
    ".txt": "text/plain",
    ".pdf": "application/pdf",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}


class ExtractorRegistry:
    def __init__(self, extractors: list[Extractor] | None = None) -> None:
        self._extractors = extractors or [PdfExtractor(), DocxExtractor(), TextExtractor()]
        self._by_mime: dict[str, Extractor] = {
            mime: extractor for extractor in self._extractors for mime in extractor.mime_types
        }

    @property
    def supported_mime_types(self) -> tuple[str, ...]:
        return tuple(sorted(self._by_mime))

    def get(self, mime_type: str) -> Extractor:
        extractor = self._by_mime.get(mime_type)
        if extractor is None:
            raise UnsupportedDocumentTypeError(
                f"No extractor for {mime_type!r}.",
                details={
                    "mime_type": mime_type,
                    "supported": list(self.supported_mime_types),
                },
            )
        return extractor

    def detect_mime_type(self, data: bytes, *, filename: str, declared: str | None = None) -> str:
        """Decide what this file actually is.

        Content wins over the declared type: a caller that mislabels a PDF as
        octet-stream should still succeed, and one that labels an executable as
        text/plain should not get to choose the parser.
        """
        for signature, mime_type in _SIGNATURES:
            if data.startswith(signature):
                # A .docx is a ZIP, but so is every other OOXML file. Only trust
                # the ZIP signature when the extension agrees.
                if mime_type.endswith("wordprocessingml.document"):
                    if Path(filename).suffix.lower() == ".docx":
                        return mime_type
                    continue
                return mime_type

        suffix = Path(filename).suffix.lower()
        if suffix in _EXTENSION_OVERRIDES:
            return _EXTENSION_OVERRIDES[suffix]

        if declared and declared in self._by_mime:
            return declared

        guessed, _ = mimetypes.guess_type(filename)
        if guessed in self._by_mime:
            return guessed

        raise UnsupportedDocumentTypeError(
            f"Cannot determine a supported type for {filename!r}.",
            details={
                "filename": filename,
                "declared": declared,
                "supported": list(self.supported_mime_types),
            },
        )
