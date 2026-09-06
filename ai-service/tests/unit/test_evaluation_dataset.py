"""The evaluation dataset and the metrics computed from it.

The runner is exercised against a stubbed RAG service so the numbers are
checked against known inputs. An evaluation harness whose own arithmetic is
unverified is worse than none: it reports confident figures nobody can trust.
"""

from __future__ import annotations

import uuid

import pytest

from app.schemas.rag import Citation, QueryTimings, QueryUsage, RagQueryResponse
from app.services.evaluation.dataset import DatasetError, load_dataset
from app.services.evaluation.dataset_runner import DatasetEvaluationRunner
from app.services.evaluation.metrics import (
    answer_token_f1,
    source_hit_at_k,
    source_precision,
    source_recall,
    source_reciprocal_rank,
)

pytestmark = pytest.mark.asyncio


# ------------------------------------------------------------------ loading --


def write(tmp_path, name: str, text: str):
    path = tmp_path / name
    path.write_text(text, encoding="utf-8")
    return path


async def test_a_jsonl_dataset_loads(tmp_path) -> None:
    path = write(
        tmp_path,
        "eval.jsonl",
        '{"id": "q001", "question": "What is the leave policy?", '
        '"reference_answer": "25 days", "expected_sources": ["HR Policy.pdf"]}\n'
        '{"id": "q002", "question": "What is the expense limit?"}\n',
    )

    cases = load_dataset(path)

    assert [case.id for case in cases] == ["q001", "q002"]
    assert cases[0].expected_sources == ["HR Policy.pdf"]
    assert cases[0].has_reference is True
    # A case may state nothing beyond the question.
    assert cases[1].expected_sources == []
    assert cases[1].has_reference is False


async def test_a_json_list_loads_too(tmp_path) -> None:
    path = write(
        tmp_path,
        "eval.json",
        '[{"id": "q001", "question": "What is the leave policy?", '
        '"expected_sources": ["HR Policy.pdf"]}]',
    )

    assert len(load_dataset(path)) == 1


async def test_blank_lines_and_comments_are_skipped(tmp_path) -> None:
    """A dataset a person maintains needs grouping and notes."""
    path = write(
        tmp_path,
        "eval.jsonl",
        "# leave questions\n"
        '{"question": "How much leave?"}\n'
        "\n"
        "# expenses\n"
        '{"question": "What is the receipt threshold?"}\n',
    )

    assert len(load_dataset(path)) == 2


async def test_ids_are_generated_when_absent(tmp_path) -> None:
    path = write(tmp_path, "eval.jsonl", '{"question": "One?"}\n{"question": "Two?"}\n')

    assert [case.id for case in load_dataset(path)] == ["case-001", "case-002"]


# --------------------------------------------------------------- malformed --


async def test_invalid_json_names_the_line(tmp_path) -> None:
    """The line number is the whole point: a 200-case file needs a pointer."""
    path = write(tmp_path, "eval.jsonl", '{"question": "fine"}\n{"question": broken}\n')

    with pytest.raises(DatasetError, match=r":2:"):
        load_dataset(path)


async def test_a_missing_question_is_rejected(tmp_path) -> None:
    path = write(tmp_path, "eval.jsonl", '{"id": "q001", "reference_answer": "..."}\n')

    with pytest.raises(DatasetError, match="'question' is required"):
        load_dataset(path)


async def test_an_empty_question_is_rejected(tmp_path) -> None:
    path = write(tmp_path, "eval.jsonl", '{"question": "   "}\n')

    with pytest.raises(DatasetError, match="'question' is required"):
        load_dataset(path)


async def test_expected_sources_must_be_strings(tmp_path) -> None:
    path = write(tmp_path, "eval.jsonl", '{"question": "q", "expected_sources": [1, 2]}\n')

    with pytest.raises(DatasetError, match="expected_sources"):
        load_dataset(path)


async def test_duplicate_ids_are_rejected(tmp_path) -> None:
    """Two cases with one id makes per-case results ambiguous."""
    path = write(
        tmp_path,
        "eval.jsonl",
        '{"id": "q001", "question": "One?"}\n{"id": "q001", "question": "Two?"}\n',
    )

    with pytest.raises(DatasetError, match="duplicate case ids"):
        load_dataset(path)


async def test_an_empty_file_is_rejected(tmp_path) -> None:
    with pytest.raises(DatasetError, match="empty"):
        load_dataset(write(tmp_path, "eval.jsonl", "   "))


async def test_a_missing_file_is_rejected(tmp_path) -> None:
    with pytest.raises(DatasetError, match="not found"):
        load_dataset(tmp_path / "nope.jsonl")


# ----------------------------------------------------------------- metrics --


async def test_source_metrics_on_a_perfect_retrieval() -> None:
    cited = ["HR Policy.pdf"]

    assert source_hit_at_k(cited, ["HR Policy.pdf"], 5) == 1.0
    assert source_reciprocal_rank(cited, ["HR Policy.pdf"]) == 1.0
    assert source_recall(cited, ["HR Policy.pdf"]) == 1.0
    assert source_precision(cited, ["HR Policy.pdf"]) == 1.0


async def test_source_metrics_on_a_failed_retrieval() -> None:
    cited = ["Handbook.pdf", "Travel.pdf"]

    assert source_hit_at_k(cited, ["HR Policy.pdf"], 5) == 0.0
    assert source_reciprocal_rank(cited, ["HR Policy.pdf"]) == 0.0
    assert source_recall(cited, ["HR Policy.pdf"]) == 0.0


async def test_reciprocal_rank_rewards_ranking_the_right_document_first() -> None:
    assert source_reciprocal_rank(["A.pdf", "HR.pdf"], ["HR.pdf"]) == 0.5
    assert source_reciprocal_rank(["A.pdf", "B.pdf", "HR.pdf"], ["HR.pdf"]) == pytest.approx(1 / 3)


async def test_a_partially_missing_expected_source_halves_recall() -> None:
    """Hit@k says "found something"; recall says "found everything"."""
    cited = ["HR Policy.pdf"]
    expected = ["HR Policy.pdf", "Benefits.pdf"]

    assert source_hit_at_k(cited, expected, 5) == 1.0
    assert source_recall(cited, expected) == 0.5


async def test_filenames_match_across_paths_and_case() -> None:
    assert source_hit_at_k(["uploads/2026/hr policy.pdf"], ["HR Policy.pdf"], 5) == 1.0


async def test_hit_at_k_respects_the_cutoff() -> None:
    cited = ["A.pdf", "B.pdf", "C.pdf", "HR.pdf"]

    assert source_hit_at_k(cited, ["HR.pdf"], 3) == 0.0
    assert source_hit_at_k(cited, ["HR.pdf"], 4) == 1.0


async def test_token_f1_is_lexical_not_semantic() -> None:
    """Documented honestly rather than papered over.

    The second assertion is the metric's real limitation: a correct answer in
    different words scores badly. That is why it is reported as token overlap
    and never as accuracy.
    """
    assert answer_token_f1("25 days of annual leave", "annual leave is 25 days") == 1.0
    assert answer_token_f1("Employees get 25 days", "Staff receive twenty-five days") < 0.4


# ------------------------------------------------------------------ runner --


def response(
    *, answer: str, grounded: bool, filenames: list[str], total_ms: float = 100.0
) -> RagQueryResponse:
    return RagQueryResponse(
        query_id=uuid.uuid4(),
        knowledge_base_id=uuid.uuid4(),
        query="q",
        answer=answer,
        citations=[
            Citation(
                marker=index + 1,
                chunk_id=uuid.uuid4(),
                document_id=uuid.uuid4(),
                filename=name,
                page_number=1,
                snippet="…",
                score=0.9,
            )
            for index, name in enumerate(filenames)
        ],
        grounded=grounded,
        model="fake-model",
        usage=QueryUsage(),
        timings=QueryTimings(total_ms=total_ms),
    )


class StubService:
    """Replays scripted responses, or raises when one is an exception."""

    def __init__(self, *scripted: object) -> None:
        self._scripted = list(scripted)
        self.questions: list[str] = []

    async def answer(self, *, query: str, knowledge_base_id: uuid.UUID) -> RagQueryResponse:
        self.questions.append(query)
        item = self._scripted.pop(0)
        if isinstance(item, Exception):
            raise item
        return item  # type: ignore[return-value]


def case(**overrides: object) -> object:
    from app.services.evaluation.dataset import EvalCase

    defaults: dict = {"id": "q001", "question": "How much leave?"}
    defaults.update(overrides)
    return EvalCase(**defaults)  # type: ignore[arg-type]


async def test_a_perfect_case_scores_one_across_the_board() -> None:
    service = StubService(
        response(answer="Employees receive 25 days [S1].", grounded=True, filenames=["HR.pdf"])
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run(
        [case(expected_sources=["HR.pdf"], reference_answer="Employees receive 25 days")],
        knowledge_base_id=uuid.uuid4(),
    )

    assert report.total == 1
    assert report.successful == 1
    assert report.failed == 0
    assert report.metrics["hit_at_k"] == 1.0
    assert report.metrics["mrr"] == 1.0
    assert report.metrics["groundedness"] == 1.0
    assert report.cases[0].failed_expectation is False


async def test_a_missed_source_is_reported_as_an_unmet_expectation() -> None:
    service = StubService(
        response(answer="Something else [S1].", grounded=True, filenames=["Travel.pdf"])
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(expected_sources=["HR.pdf"])], knowledge_base_id=uuid.uuid4())

    assert report.metrics["hit_at_k"] == 0.0
    assert report.cases[0].failed_expectation is True
    # It ran fine; the pipeline was simply wrong. The two are not the same.
    assert report.failed == 0


async def test_an_answer_with_no_citations_is_not_grounded() -> None:
    """Groundedness is about evidence, not about the retrieval threshold.

    A live run exposed the difference: the pipeline reported
    `retrieval_grounded=True` for questions the model then declined to answer,
    so scoring that flag showed 100% groundedness on a run where a third of
    the answers cited nothing. Citations are the observable support.
    """
    service = StubService(
        # Answered, but backed by nothing.
        response(answer="Probably around 30 days.", grounded=True, filenames=[]),
        response(answer="Employees receive 25 days [S1].", grounded=True, filenames=["HR.pdf"]),
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(id="q001"), case(id="q002")], knowledge_base_id=uuid.uuid4())

    assert report.metrics["groundedness"] == 0.5
    assert report.metrics["answer_rate"] == 1.0


async def test_declining_does_not_count_against_groundedness() -> None:
    """A refusal is not an ungrounded answer; it is the absence of one.

    Counting it as ungrounded would push a correctly cautious bot's score down
    for doing the right thing. It shows up in answer_rate instead.
    """
    service = StubService(
        response(answer="I don't have information about that.", grounded=True, filenames=[]),
        response(answer="Employees receive 25 days [S1].", grounded=True, filenames=["HR.pdf"]),
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(id="q001"), case(id="q002")], knowledge_base_id=uuid.uuid4())

    assert report.cases[0].abstained is True
    assert report.metrics["groundedness"] == 1.0
    assert report.metrics["answer_rate"] == 0.5


async def test_a_case_that_should_abstain_is_scored_on_abstaining() -> None:
    service = StubService(
        response(answer="I don't have information about that.", grounded=False, filenames=[])
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(should_abstain=True)], knowledge_base_id=uuid.uuid4())

    assert report.metrics["abstention_accuracy"] == 1.0
    assert report.cases[0].failed_expectation is False


async def test_answering_a_question_that_should_abstain_is_a_failure() -> None:
    service = StubService(
        response(answer="Definitely 25 days [S1].", grounded=True, filenames=["HR.pdf"])
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(should_abstain=True)], knowledge_base_id=uuid.uuid4())

    assert report.metrics["abstention_accuracy"] == 0.0
    assert report.cases[0].failed_expectation is True


async def test_one_failing_question_does_not_discard_the_rest() -> None:
    """The run has already cost money; it must not be thrown away."""
    service = StubService(
        RuntimeError("model unavailable"),
        response(answer="Employees receive 25 days [S1].", grounded=True, filenames=["HR.pdf"]),
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run(
        [
            case(id="q001", expected_sources=["HR.pdf"]),
            case(id="q002", expected_sources=["HR.pdf"]),
        ],
        knowledge_base_id=uuid.uuid4(),
    )

    assert report.total == 2
    assert report.successful == 1
    assert report.failed == 1
    assert "RuntimeError" in (report.cases[0].error or "")
    # The failed case is excluded from the averages rather than scored zero.
    assert report.metrics["hit_at_k"] == 1.0


async def test_latency_statistics_come_from_the_pipeline_timings() -> None:
    service = StubService(
        response(answer="a [S1]", grounded=True, filenames=["HR.pdf"], total_ms=100.0),
        response(answer="b [S1]", grounded=True, filenames=["HR.pdf"], total_ms=300.0),
        response(answer="c [S1]", grounded=True, filenames=["HR.pdf"], total_ms=200.0),
    )
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run(
        [case(id=f"q{index}") for index in range(3)], knowledge_base_id=uuid.uuid4()
    )

    latency = report.latency
    assert latency["mean_ms"] == 200.0
    assert latency["p50_ms"] == 200.0
    assert latency["max_ms"] == 300.0


async def test_metrics_nobody_asked_for_are_null_not_zero() -> None:
    """A dataset with no expected sources must not score 0.0 on retrieval."""
    service = StubService(response(answer="a [S1]", grounded=True, filenames=["HR.pdf"]))
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case()], knowledge_base_id=uuid.uuid4())

    assert report.metrics["hit_at_k"] is None
    assert report.metrics["answer_token_f1"] is None
    # Groundedness needs no expectation from the dataset — it is observable
    # from whether the answer cited anything.
    assert report.metrics["groundedness"] == 1.0


async def test_the_report_serialises_to_json() -> None:
    service = StubService(response(answer="a [S1]", grounded=True, filenames=["HR.pdf"]))
    runner = DatasetEvaluationRunner(service)  # type: ignore[arg-type]

    report = await runner.run([case(expected_sources=["HR.pdf"])], knowledge_base_id=uuid.uuid4())

    import json

    payload = json.loads(report.to_json())
    assert payload["schema_version"] == 1
    assert payload["totals"]["total"] == 1
    assert payload["metrics"]["hit_at_k"] == 1.0
    assert payload["cases"][0]["id"] == "q001"
