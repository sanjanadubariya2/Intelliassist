"""Transient-error classification for the Gemini provider.

Found live: Gemini returns 503 "experiencing high demand" often enough that a
single attempt makes the service look flaky for a condition that clears in
under a second. Equally, retrying a retired model or a bad key only delays a
failure the caller needs to see.
"""

from __future__ import annotations

import pytest

from app.services.llm.base import LLMResponse, LLMUsage
from app.services.llm.gemini import _is_transient


@pytest.mark.parametrize(
    "message",
    [
        "503 UNAVAILABLE. The model is currently experiencing high demand.",
        "429 RESOURCE_EXHAUSTED. Quota exceeded.",
        "500 INTERNAL. Internal error encountered.",
    ],
)
def test_transient_upstream_failures_are_retried(message: str) -> None:
    assert _is_transient(Exception(message)) is True


def test_timeouts_are_retried() -> None:
    assert _is_transient(TimeoutError()) is True


@pytest.mark.parametrize(
    "message",
    [
        "404 NOT_FOUND. This model is no longer available to new users.",
        "400 INVALID_ARGUMENT. Request contains an invalid argument.",
        "403 PERMISSION_DENIED. API key not valid.",
    ],
)
def test_permanent_failures_are_not_retried(message: str) -> None:
    # These fail identically on every attempt; retrying only adds latency.
    assert _is_transient(Exception(message)) is False


def test_retry_budget_comes_from_configuration() -> None:
    from app.core.config import LLMConfig

    # The setting existed but was unused before this was wired up.
    assert LLMConfig().max_retries == 4


def test_plan_quota_exhaustion_is_not_retried() -> None:
    # A 429 covers two different things. A per-minute rate limit clears in
    # seconds; an exhausted plan quota will not clear for hours, so retrying
    # only delays an error the caller needs to act on now.
    exhausted = Exception(
        "429 RESOURCE_EXHAUSTED. You exceeded your current quota, please check "
        "your plan and billing details."
    )

    assert _is_transient(exhausted) is False


def test_plain_rate_limiting_is_still_retried() -> None:
    assert _is_transient(Exception("429 RESOURCE_EXHAUSTED. Too many requests.")) is True


# --------------------------------------------------- truncation handling ----


def _response(finish_reason: str | None) -> LLMResponse:
    return LLMResponse(text="answer", usage=LLMUsage(), model="m", finish_reason=finish_reason)


def test_hitting_the_output_cap_is_reported_as_truncated() -> None:
    # Found live: a tight budget returned 'Full' — a truncated answer that had
    # lost its citation marker, presented as a complete one.
    assert _response("FinishReason.MAX_TOKENS").truncated is True


def test_a_normal_stop_is_not_truncated() -> None:
    assert _response("FinishReason.STOP").truncated is False


def test_missing_finish_reason_is_not_truncated() -> None:
    assert _response(None).truncated is False
