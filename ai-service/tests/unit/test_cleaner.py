"""Each cleaning rule exists because of a specific way raw text breaks
retrieval, so each is tested against that failure."""

from __future__ import annotations

from app.services.ingestion.cleaner import clean_document, clean_text, find_repeated_lines
from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage


def test_hyphenated_line_break_is_rejoined() -> None:
    # "agree- \n ment" would otherwise never match a search for "agreement".
    assert "agreement" in clean_text("The agree-\nment is binding.")


def test_hyphenation_rejoin_does_not_eat_real_hyphens() -> None:
    assert "well-known" in clean_text("A well-known policy.")


def test_ligatures_are_expanded() -> None:
    # "workﬂow" and "workflow" are different strings to both BM25 and a
    # tokenizer, so a PDF ligature silently splits the term.
    assert clean_text("workﬂow and ofﬁce") == "workflow and office"


def test_smart_quotes_become_ascii() -> None:
    assert clean_text("“quoted” and ‘single’") == "\"quoted\" and 'single'"


def test_non_breaking_and_zero_width_spaces_are_normalised() -> None:
    cleaned = clean_text("thirty days​ here")

    assert " " not in cleaned
    assert "​" not in cleaned
    assert "thirty days" in cleaned


def test_control_characters_are_stripped() -> None:
    assert clean_text("text\x00with\x07control") == "textwithcontrol"


def test_excess_whitespace_collapses_but_paragraphs_survive() -> None:
    cleaned = clean_text("First    para\n\n\n\n\nSecond para")

    assert cleaned == "First para\n\nSecond para"


def test_windows_line_endings_are_normalised() -> None:
    assert "\r" not in clean_text("line one\r\nline two")


def test_repeated_headers_are_detected_across_pages() -> None:
    pages = [
        ExtractedPage(text=f"ACME CONFIDENTIAL\nBody of page {n}.", page_number=n)
        for n in range(1, 7)
    ]

    assert "ACME CONFIDENTIAL" in find_repeated_lines(pages)


def test_repeat_detection_is_skipped_for_short_documents() -> None:
    # With two pages, a line on both is far more likely to be content.
    pages = [ExtractedPage(text="Shared line\nBody.", page_number=n) for n in (1, 2)]

    assert find_repeated_lines(pages) == set()


def test_document_cleaning_removes_footers_and_page_numbers() -> None:
    pages = [
        ExtractedPage(
            text=f"ACME CONFIDENTIAL\nReal content for page {n}.\n{n}",
            page_number=n,
        )
        for n in range(1, 7)
    ]

    cleaned = clean_document(ExtractedDocument(pages=pages, page_count=6))

    body = "\n".join(page.text for page in cleaned.pages)
    assert "ACME CONFIDENTIAL" not in body
    assert "Real content for page 3." in body
    assert cleaned.meta["removed_repeated_lines"] >= 1


def test_long_repeated_lines_are_kept_as_content() -> None:
    # A repeated clause is boilerplate the user may legitimately ask about;
    # only short lines look like page furniture.
    # Comfortably past the 120-character furniture cutoff.
    long_line = "This agreement shall be governed by the laws of the state of Delaware " * 3
    pages = [ExtractedPage(text=f"{long_line}\nPage {n}.", page_number=n) for n in range(1, 7)]

    assert long_line.strip() not in find_repeated_lines(pages)


def test_pages_that_clean_to_nothing_are_dropped() -> None:
    pages = [
        ExtractedPage(text="Real content here.", page_number=1),
        ExtractedPage(text="   \n\n  ", page_number=2),
    ]

    cleaned = clean_document(ExtractedDocument(pages=pages, page_count=2))

    assert [page.page_number for page in cleaned.pages] == [1]


def test_page_numbers_survive_cleaning() -> None:
    pages = [ExtractedPage(text=f"Content {n}", page_number=n) for n in range(1, 4)]

    cleaned = clean_document(ExtractedDocument(pages=pages, page_count=3))

    assert [page.page_number for page in cleaned.pages] == [1, 2, 3]
