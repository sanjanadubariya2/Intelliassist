"""DOCX extraction via python-docx."""

from __future__ import annotations

import io

import docx
from docx.table import Table
from docx.text.paragraph import Paragraph

from app.core.exceptions import ExtractionError
from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage

DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


class DocxExtractor:
    """Extracts body text in document order, including tables.

    DOCX has no pages — pagination is a rendering decision made by the viewer —
    so this emits one page with no page number rather than inventing one.
    Heading styles are preserved as Markdown hashes, which is what later lets
    the chunker split on structure instead of arbitrary offsets.
    """

    @property
    def mime_types(self) -> tuple[str, ...]:
        return (DOCX_MIME,)

    def extract(self, data: bytes, *, filename: str) -> ExtractedDocument:
        try:
            document = docx.Document(io.BytesIO(data))
        except Exception as exc:
            raise ExtractionError(
                f"{filename} could not be opened as a DOCX.",
                details={"filename": filename, "reason": str(exc)},
            ) from exc

        blocks: list[str] = []
        for element in document.element.body.iterchildren():
            if element.tag.endswith("}p"):
                text = self._paragraph_text(Paragraph(element, document))
                if text:
                    blocks.append(text)
            elif element.tag.endswith("}tbl"):
                table = self._table_text(Table(element, document))
                if table:
                    blocks.append(table)

        extracted = ExtractedDocument(
            pages=[ExtractedPage(text="\n\n".join(blocks), page_number=None)],
            page_count=None,
            meta={"paragraph_count": len(blocks)},
        )
        if extracted.is_empty:
            raise ExtractionError(
                f"{filename} contains no extractable text.", details={"filename": filename}
            )
        return extracted

    @staticmethod
    def _paragraph_text(paragraph: Paragraph) -> str:
        text = paragraph.text.strip()
        if not text:
            return ""

        style = (paragraph.style.name or "") if paragraph.style is not None else ""
        if style.startswith("Heading"):
            # "Heading 2" -> "## ", giving the chunker a structural boundary.
            level = "".join(character for character in style if character.isdigit())
            depth = int(level) if level else 1
            return f"{'#' * min(depth, 6)} {text}"
        return text

    @staticmethod
    def _table_text(table: Table) -> str:
        """Tables become pipe-delimited rows.

        Not pretty, but it keeps a cell attached to its row, which matters when
        a retrieved chunk has to answer "what is the limit for X".
        """
        rows = []
        for row in table.rows:
            cells = [cell.text.strip().replace("\n", " ") for cell in row.cells]
            if any(cells):
                rows.append(" | ".join(cells))
        return "\n".join(rows)
