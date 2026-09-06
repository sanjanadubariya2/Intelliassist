"""The evaluation dataset.

Cases name their expected sources by **filename**, not chunk id. That is the
whole difference between a dataset a person will actually maintain and one they
will not: chunk ids are assigned at ingestion time, change on every reindex, and
cannot be looked up without querying the database. A filename is something the
author already knows while writing the question.

Both JSON (a list) and JSONL (one object per line) are accepted. JSONL is the
better default for anything version-controlled: adding a case is a one-line
diff, and two people adding cases on separate branches do not conflict.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class DatasetError(ValueError):
    """A dataset that cannot be evaluated.

    Raised eagerly, with the offending line, because a typo discovered halfway
    through a run has already cost real model calls.
    """


@dataclass(frozen=True, slots=True)
class EvalCase:
    """One question and what a good answer to it looks like.

    Every expectation is optional. A case with only a question still measures
    groundedness and latency, which is enough to be worth writing, and lowering
    the cost of a first case is how a dataset gets started at all.
    """

    id: str
    question: str

    #: Filenames that should be cited. Matched case-insensitively on basename,
    #: so "HR Policy.pdf" matches whatever path the document was stored under.
    expected_sources: list[str] = field(default_factory=list)

    #: A known-good answer. Used for a lexical overlap score — see metrics.py
    #: for exactly what that does and does not tell you.
    reference_answer: str | None = None

    #: True when the right behaviour is to decline. A pipeline that never
    #: abstains looks excellent on every other metric while confidently making
    #: things up, so these cases are the ones that catch it.
    should_abstain: bool = False

    @property
    def has_reference(self) -> bool:
        return bool(self.reference_answer and self.reference_answer.strip())


def _case_from(entry: Any, *, where: str, index: int) -> EvalCase:
    if not isinstance(entry, dict):
        raise DatasetError(f"{where}: expected an object, found {type(entry).__name__}")

    question = entry.get("question")
    if not isinstance(question, str) or not question.strip():
        raise DatasetError(f"{where}: 'question' is required and must be a non-empty string")

    sources = entry.get("expected_sources", [])
    if not isinstance(sources, list) or any(not isinstance(item, str) for item in sources):
        raise DatasetError(f"{where}: 'expected_sources' must be a list of filenames")

    reference = entry.get("reference_answer")
    if reference is not None and not isinstance(reference, str):
        raise DatasetError(f"{where}: 'reference_answer' must be a string when present")

    should_abstain = entry.get("should_abstain", False)
    if not isinstance(should_abstain, bool):
        raise DatasetError(f"{where}: 'should_abstain' must be true or false")

    return EvalCase(
        # Generated when absent so results always have a stable handle, but
        # authors are encouraged to set it: an id that survives reordering is
        # what lets two runs be compared case by case.
        id=str(entry.get("id") or f"case-{index + 1:03d}"),
        question=question.strip(),
        expected_sources=[item.strip() for item in sources if item.strip()],
        reference_answer=reference,
        should_abstain=should_abstain,
    )


def load_dataset(path: Path) -> list[EvalCase]:
    """Reads a `.json` list or a `.jsonl` file of cases.

    The format is chosen by extension, then by content, so a `.txt` holding
    JSONL still loads rather than failing on a technicality.
    """
    if not path.exists():
        raise DatasetError(f"dataset not found: {path}")

    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise DatasetError(f"dataset is empty: {path}")

    cases = (
        _load_json(text, path)
        if path.suffix.lower() == ".json" or text.lstrip().startswith("[")
        else _load_jsonl(text, path)
    )

    if not cases:
        raise DatasetError(f"dataset contains no cases: {path}")

    duplicates = _duplicate_ids(cases)
    if duplicates:
        # Two cases sharing an id makes per-case results ambiguous, which
        # defeats the point of having ids at all.
        raise DatasetError(f"duplicate case ids in {path}: {', '.join(sorted(duplicates))}")

    return cases


def _load_json(text: str, path: Path) -> list[EvalCase]:
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise DatasetError(f"{path}: invalid JSON — {exc.msg} at line {exc.lineno}") from exc

    if not isinstance(payload, list):
        raise DatasetError(f"{path}: expected a list of cases at the top level")

    return [
        _case_from(entry, where=f"{path}[{index}]", index=index)
        for index, entry in enumerate(payload)
    ]


def _load_jsonl(text: str, path: Path) -> list[EvalCase]:
    cases: list[EvalCase] = []
    index = 0

    for line_number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        # Blank lines and `#` comments are skipped so a dataset can be
        # annotated and grouped by a human without a separate format.
        if not stripped or stripped.startswith("#"):
            continue

        try:
            entry = json.loads(stripped)
        except json.JSONDecodeError as exc:
            raise DatasetError(f"{path}:{line_number}: invalid JSON — {exc.msg}") from exc

        cases.append(_case_from(entry, where=f"{path}:{line_number}", index=index))
        index += 1

    return cases


def _duplicate_ids(cases: list[EvalCase]) -> set[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for case in cases:
        if case.id in seen:
            duplicates.add(case.id)
        seen.add(case.id)
    return duplicates
