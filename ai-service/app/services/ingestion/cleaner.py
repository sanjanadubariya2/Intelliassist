"""Text cleaning.

Every transformation here exists because of a specific way raw extracted text
degrades retrieval. Nothing is cosmetic: a hyphen left across a line break makes
"agree- ment" unmatchable, and a footer repeated on 200 pages becomes the most
statistically prominent phrase in the corpus.
"""

from __future__ import annotations

import re
import unicodedata
from collections import Counter

from app.services.ingestion.extractors.base import ExtractedDocument, ExtractedPage

# Ligatures and typographic punctuation that break exact-term matching.
_REPLACEMENTS = {
    "ﬀ": "ff",
    "ﬁ": "fi",
    "ﬂ": "fl",
    "ﬃ": "ffi",
    "ﬄ": "ffl",
    "‘": "'",
    "’": "'",
    "‚": "'",
    "‛": "'",
    "“": '"',
    "”": '"',
    "„": '"',
    "–": "-",
    "—": "-",
    "−": "-",
    " ": " ",
    "​": "",
    "‌": "",
    "‍": "",
    "﻿": "",
    "…": "...",
}

_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_HYPHEN_BREAK = re.compile(r"(\w)-\s*\n\s*(\w)")
_TRAILING_SPACE = re.compile(r"[ \t]+(\n)")
_MANY_SPACES = re.compile(r"[ \t]{2,}")
_MANY_NEWLINES = re.compile(r"\n{3,}")
_PAGE_MARKER = re.compile(r"^\s*(?:page\s+)?\d+\s*(?:/\s*\d+)?\s*$", re.IGNORECASE)

# A line must appear on at least this share of pages to count as page furniture.
_REPEAT_THRESHOLD = 0.6
_MIN_PAGES_FOR_REPEAT_DETECTION = 4


def clean_text(text: str) -> str:
    """Normalise a single block of text."""
    text = unicodedata.normalize("NFKC", text)
    for source, target in _REPLACEMENTS.items():
        text = text.replace(source, target)

    text = _CONTROL.sub("", text)
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    # Rejoin words split across a line break before collapsing whitespace,
    # otherwise the hyphen and the newline stop being adjacent.
    text = _HYPHEN_BREAK.sub(r"\1\2", text)

    text = _TRAILING_SPACE.sub(r"\1", text)
    text = _MANY_SPACES.sub(" ", text)
    text = _MANY_NEWLINES.sub("\n\n", text)
    return text.strip()


def find_repeated_lines(pages: list[ExtractedPage]) -> set[str]:
    """Lines appearing on most pages: running headers, footers, confidentiality
    notices. Detected by frequency rather than position, because extractors do
    not reliably report where on the page a line sat."""
    if len(pages) < _MIN_PAGES_FOR_REPEAT_DETECTION:
        return set()

    counts: Counter[str] = Counter()
    for page in pages:
        # Count each distinct line once per page, so a word repeated within one
        # page does not look like a header.
        seen = {line.strip() for line in page.text.splitlines() if line.strip()}
        counts.update(seen)

    minimum = max(2, int(len(pages) * _REPEAT_THRESHOLD))
    return {
        line
        for line, count in counts.items()
        # Long lines that repeat are usually legitimate boilerplate content
        # (a repeated clause), not furniture. Keep the filter to short lines.
        if count >= minimum and len(line) <= 120
    }


def clean_document(document: ExtractedDocument) -> ExtractedDocument:
    """Clean every page and strip page furniture."""
    repeated = find_repeated_lines(document.pages)

    cleaned_pages = []
    for page in document.pages:
        lines = [
            line
            for line in page.text.splitlines()
            if line.strip() not in repeated and not _PAGE_MARKER.match(line)
        ]
        cleaned = clean_text("\n".join(lines))
        if cleaned:
            cleaned_pages.append(ExtractedPage(text=cleaned, page_number=page.page_number))

    return ExtractedDocument(
        pages=cleaned_pages,
        page_count=document.page_count,
        meta={**document.meta, "removed_repeated_lines": len(repeated)},
    )
