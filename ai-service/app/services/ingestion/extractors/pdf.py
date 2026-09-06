"""PDF extraction via PyMuPDF."""

from __future__ import annotations

import pymupdf

from app.core.exceptions import ExtractionError
from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage


class PdfExtractor:
    """Extracts text page by page so citations can name a page number.

    A PDF that yields no text is almost always a scan. That is reported as a
    specific failure rather than an empty success, because "indexed, 0 chunks"
    looks like the system working when it is not.
    """

    @property
    def mime_types(self) -> tuple[str, ...]:
        return ("application/pdf",)

    def extract(self, data: bytes, *, filename: str) -> ExtractedDocument:
        try:
            document = pymupdf.open(stream=data, filetype="pdf")
        except Exception as exc:
            raise ExtractionError(
                f"{filename} could not be opened as a PDF.",
                details={"filename": filename, "reason": str(exc)},
            ) from exc

        try:
            if document.needs_pass:
                raise ExtractionError(
                    f"{filename} is password protected.", details={"filename": filename}
                )

            pages = [
                # sort=True orders blocks by position, which keeps multi-column
                # layouts from interleaving into nonsense.
                ExtractedPage(text=page.get_text("text", sort=True), page_number=number)
                for number, page in enumerate(document, start=1)
            ]
            page_count = document.page_count
            meta = {
                key: value
                for key, value in (document.metadata or {}).items()
                if key in {"title", "author", "subject"} and value
            }
        finally:
            document.close()

        extracted = ExtractedDocument(pages=pages, page_count=page_count, meta=meta)
        if extracted.is_empty:
            raise ExtractionError(
                f"{filename} contains no extractable text. Scanned PDFs need OCR, "
                "which this service does not perform.",
                details={"filename": filename, "page_count": page_count},
            )
        return extracted
