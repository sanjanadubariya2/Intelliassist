"""LLM provider contract.

Two methods, because streaming and non-streaming have genuinely different
shapes and pretending otherwise leaks awkwardness into every caller. Adding
OpenAI later means one class satisfying this protocol; the RAG pipeline is
untouched.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True, slots=True)
class LLMUsage:
    prompt_tokens: int = 0
    completion_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens


@dataclass(frozen=True, slots=True)
class LLMResponse:
    text: str
    usage: LLMUsage
    model: str
    finish_reason: str | None = None


@runtime_checkable
class LLMProvider(Protocol):
    @property
    def model(self) -> str: ...

    async def complete(self, *, system: str, prompt: str) -> LLMResponse: ...

    def stream(self, *, system: str, prompt: str) -> AsyncIterator[str]:
        """Yield text deltas as they arrive."""
        ...
