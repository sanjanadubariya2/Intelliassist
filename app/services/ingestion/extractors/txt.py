"""Plain text and Markdown extraction."""

from __future__ import annotations

from charset_normalizer import from_bytes

from app.core.exceptions import ExtractionError
from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage


class TextExtractor:
    """Decodes bytes to text, detecting the encoding rather than assuming UTF-8.

    Company documents arrive as Windows-1252 and UTF-16 more often than anyone
    expects, and a wrong guess corrupts every accented character in the corpus
    silently — the text still indexes, it just no longer matches queries.
    """

    @property
    def mime_types(self) -> tuple[str, ...]:
        return ("text/plain", "text/markdown")

    def extract(self, data: bytes, *, filename: str) -> ExtractedDocument:
        if not data.strip():
            raise ExtractionError(f"{filename} is empty.", details={"filename": filename})

        text, encoding = self._decode(data, filename=filename)

        return ExtractedDocument(
            pages=[ExtractedPage(text=text, page_number=None)],
            page_count=None,
            meta={"encoding": encoding},
        )

    @staticmethod
    def _decode(data: bytes, *, filename: str) -> tuple[str, str]:
        # UTF-8 first: it is the overwhelmingly common case and detection is
        # probabilistic, so there is no reason to guess when we can know.
        try:
            return data.decode("utf-8"), "utf-8"
        except UnicodeDecodeError:
            pass

        best_match = from_bytes(data).best()
        if best_match is None:
            raise ExtractionError(
                f"{filename} is not decodable as text in any detected encoding.",
                details={"filename": filename},
            )
        return str(best_match), best_match.encoding
