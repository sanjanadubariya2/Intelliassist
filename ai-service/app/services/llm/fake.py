"""Deterministic LLM for tests.

By default it answers by quoting the first source and citing it, which is enough
for the citation extractor and the response assembly to be tested end to end
with no network. Tests that need a specific behaviour — a fabricated marker, an
empty answer, a refusal — pass ``scripted``.
"""

from __future__ import annotations

import re
from collections.abc import AsyncIterator

from app.services.llm.base import LLMResponse, LLMUsage

_FIRST_MARKER = re.compile(r"\[S(\d+)\]")


class FakeLLMProvider:
    def __init__(self, *, scripted: str | None = None, model: str = "fake-llm") -> None:
        self._scripted = scripted
        self._model = model
        self.calls: list[tuple[str, str]] = []

    @property
    def model(self) -> str:
        return self._model

    def _answer(self, prompt: str) -> str:
        if self._scripted is not None:
            return self._scripted

        markers = _FIRST_MARKER.findall(prompt)
        if not markers:
            return "I don't have information about that in the knowledge base."
        return f"Based on the supplied sources, this is the answer [S{markers[0]}]."

    async def complete(self, *, system: str, prompt: str) -> LLMResponse:
        self.calls.append((system, prompt))
        text = self._answer(prompt)
        return LLMResponse(
            text=text,
            usage=LLMUsage(prompt_tokens=len(prompt.split()), completion_tokens=len(text.split())),
            model=self._model,
            finish_reason="stop",
        )

    async def stream(self, *, system: str, prompt: str) -> AsyncIterator[str]:
        self.calls.append((system, prompt))
        # Word-at-a-time, so streaming tests see multiple deltas rather than one.
        for word in self._answer(prompt).split(" "):
            yield word + " "
