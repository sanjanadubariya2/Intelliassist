"""Extractors are tested against real generated PDF and DOCX bytes rather than
mocks — the failure modes worth catching all live in the file formats."""

from __future__ import annotations

import io

import docx
import pymupdf
import pytest

from app.core.exceptions import ExtractionError, UnsupportedDocumentTypeError
from app.services.ingestion.extractors.docx import DOCX_MIME, DocxExtractor
from app.services.ingestion.extractors.pdf import PdfExtractor
from app.services.ingestion.extractors.registry import ExtractorRegistry
from app.services.ingestion.extractors.txt import TextExtractor


def make_pdf(pages: list[str]) -> bytes:
    document = pymupdf.open()
    for body in pages:
        page = document.new_page()
        page.insert_text((72, 96), body, fontsize=11)
    data: bytes = document.tobytes()
    document.close()
    return data


def make_docx(paragraphs: list[tuple[str, str | None]]) -> bytes:
    document = docx.Document()
    for text, style in paragraphs:
        document.add_paragraph(text, style=style) if style else document.add_paragraph(text)
    buffer = io.BytesIO()
    document.save(buffer)
    return buffer.getvalue()


# ------------------------------------------------------------------- pdf ----


def test_pdf_extracts_text_per_page() -> None:
    data = make_pdf(["Parental leave is sixteen weeks.", "Expenses need a receipt."])

    extracted = PdfExtractor().extract(data, filename="policy.pdf")

    assert extracted.page_count == 2
    assert [page.page_number for page in extracted.pages] == [1, 2]
    assert "sixteen weeks" in extracted.pages[0].text
    assert "receipt" in extracted.pages[1].text


def test_pdf_with_no_text_is_reported_not_silently_empty() -> None:
    # A scanned PDF must fail loudly. "indexed, 0 chunks" looks like success.
    blank = pymupdf.open()
    blank.new_page()
    data = blank.tobytes()
    blank.close()

    with pytest.raises(ExtractionError, match="no extractable text"):
        PdfExtractor().extract(data, filename="scan.pdf")


def test_corrupt_pdf_raises_extraction_error() -> None:
    with pytest.raises(ExtractionError):
        PdfExtractor().extract(b"%PDF-1.4 this is not really a pdf", filename="broken.pdf")


# ------------------------------------------------------------------ docx ----


def test_docx_extracts_paragraphs_in_order() -> None:
    data = make_docx([("First paragraph.", None), ("Second paragraph.", None)])

    extracted = DocxExtractor().extract(data, filename="handbook.docx")

    text = extracted.pages[0].text
    assert text.index("First paragraph.") < text.index("Second paragraph.")


def test_docx_has_no_page_numbers() -> None:
    # Pagination in DOCX is a rendering decision, so inventing a page number
    # would put a false page on a citation.
    data = make_docx([("Body text.", None)])

    extracted = DocxExtractor().extract(data, filename="handbook.docx")

    assert extracted.pages[0].page_number is None
    assert extracted.page_count is None


def test_docx_headings_become_markdown() -> None:
    data = make_docx([("Parental Leave", "Heading 2"), ("Sixteen weeks.", None)])

    text = DocxExtractor().extract(data, filename="handbook.docx").pages[0].text

    assert "## Parental Leave" in text


def test_empty_docx_raises() -> None:
    with pytest.raises(ExtractionError, match="no extractable text"):
        DocxExtractor().extract(make_docx([]), filename="empty.docx")


# ------------------------------------------------------------------- txt ----


def test_utf8_text_is_decoded() -> None:
    extracted = TextExtractor().extract("Café policy — updated".encode(), filename="notes.txt")

    assert "Café" in extracted.pages[0].text
    assert extracted.meta["encoding"] == "utf-8"


def test_non_utf8_encoding_is_detected_rather_than_mangled() -> None:
    # Windows-1252 decoded as UTF-8 would corrupt every accent silently, and
    # corrupted text still indexes — it just stops matching queries.
    data = "Café résumé naïve façade".encode("windows-1252")

    text = TextExtractor().extract(data, filename="legacy.txt").pages[0].text

    assert "Café" in text


def test_empty_text_file_raises() -> None:
    with pytest.raises(ExtractionError, match="empty"):
        TextExtractor().extract(b"   \n  ", filename="blank.txt")


# -------------------------------------------------------------- registry ----


def test_registry_detects_pdf_from_magic_bytes_not_the_declared_type() -> None:
    registry = ExtractorRegistry()
    data = make_pdf(["Hello."])

    detected = registry.detect_mime_type(
        data, filename="mislabelled.bin", declared="application/octet-stream"
    )

    assert detected == "application/pdf"


def test_registry_requires_the_docx_extension_for_zip_content() -> None:
    # Every OOXML file starts with the ZIP signature, so bytes alone cannot
    # distinguish a .docx from a .xlsx.
    registry = ExtractorRegistry()

    with pytest.raises(UnsupportedDocumentTypeError):
        registry.detect_mime_type(b"PK\x03\x04rest", filename="book.xlsx", declared=None)


def test_registry_detects_docx_when_extension_agrees() -> None:
    registry = ExtractorRegistry()
    data = make_docx([("Body.", None)])

    assert registry.detect_mime_type(data, filename="handbook.docx", declared=None) == DOCX_MIME


def test_registry_falls_back_to_extension_for_text() -> None:
    registry = ExtractorRegistry()

    assert registry.detect_mime_type(b"plain words", filename="a.md", declared=None) == (
        "text/markdown"
    )


def test_unknown_type_is_rejected_with_the_supported_list() -> None:
    registry = ExtractorRegistry()

    with pytest.raises(UnsupportedDocumentTypeError) as excinfo:
        registry.detect_mime_type(b"\x00\x01binary", filename="a.exe", declared=None)

    assert "application/pdf" in excinfo.value.details["supported"]


def test_registry_returns_the_right_extractor_per_type() -> None:
    registry = ExtractorRegistry()

    assert isinstance(registry.get("application/pdf"), PdfExtractor)
    assert isinstance(registry.get(DOCX_MIME), DocxExtractor)
    assert isinstance(registry.get("text/plain"), TextExtractor)
