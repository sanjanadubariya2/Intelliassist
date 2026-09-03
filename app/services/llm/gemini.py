"""Gemini LLM provider."""

from __future__ import annotations

import asyncio
import random
from collections.abc import AsyncIterator
from typing import Any

from google import genai
from google.genai import types
from tenacity import (
    AsyncRetrying,
    retry_if_exception,
    stop_after_attempt,
    wait_random_exponential,
)

from app.core.config import LLMConfig
from app.core.exceptions import ConfigurationError, LLMError
from app.core.logging import get_logger
from app.services.llm.base import LLMResponse, LLMUsage

logger = get_logger(__name__)

# Upstream conditions that clear on their own. Anything else — a retired model,
# a bad key, a malformed request — will fail identically on every attempt, so
# retrying it only adds latency to a failure the caller needs to see.
_TRANSIENT_MARKERS = ("503", "UNAVAILABLE", "429", "RESOURCE_EXHAUSTED", "INTERNAL", "500")

# A 429 covers two very different things. A per-minute rate limit clears in
# seconds and is worth waiting out; an exhausted plan quota will not clear for
# hours, so retrying only delays an error the caller needs to act on now.
_EXHAUSTED_MARKERS = ("exceeded your current quota", "billing", "quota_limit_value")


def _is_transient(exc: BaseException) -> bool:
    if isinstance(exc, TimeoutError):
        return True

    message = str(exc)
    if any(marker in message for marker in _EXHAUSTED_MARKERS):
        return False
    return any(marker in message for marker in _TRANSIENT_MARKERS)


class GeminiLLMProvider:
    def __init__(self, config: LLMConfig, *, api_key: str | None) -> None:
        if not api_key:
            raise ConfigurationError("GEMINI_API_KEY is required when LLM__PROVIDER is 'gemini'.")
        self._config = config
        self._client = genai.Client(api_key=api_key)

    @property
    def model(self) -> str:
        return self._config.model

    def _generation_config(self, system: str) -> types.GenerateContentConfig:
        return types.GenerateContentConfig(
            system_instruction=system,
            temperature=self._config.temperature,
            max_output_tokens=self._config.max_output_tokens,
        )

    async def complete(self, *, system: str, prompt: str) -> LLMResponse:
        """Generate an answer, retrying transient upstream failures.

        Gemini returns 503 under load often enough that a single attempt makes
        the service look flaky to Spring Boot for a condition that clears in
        under a second.
        """
        try:
            async for attempt in AsyncRetrying(
                retry=retry_if_exception(_is_transient),
                stop=stop_after_attempt(self._config.max_retries + 1),
                wait=wait_random_exponential(multiplier=0.5, max=8),
                reraise=True,
            ):
                with attempt:
                    response = await asyncio.wait_for(
                        asyncio.to_thread(
                            self._client.models.generate_content,
                            model=self._config.model,
                            contents=prompt,
                            config=self._generation_config(system),
                        ),
                        timeout=self._config.timeout_seconds,
                    )
        except TimeoutError as exc:
            raise LLMError(
                f"Gemini did not respond within {self._config.timeout_seconds}s."
            ) from exc
        except Exception as exc:
            raise LLMError(f"Gemini generation failed: {exc}") from exc

        return LLMResponse(
            text=response.text or "",
            usage=self._usage(response),
            model=self._config.model,
            finish_reason=self._finish_reason(response),
        )

    async def stream(self, *, system: str, prompt: str) -> AsyncIterator[str]:
        """Stream deltas, retrying transient failures *before the first token*.

        Once any token has reached the client a retry is not available: the
        caller already holds a partial answer and replaying would duplicate it.
        So the retry window closes the moment output starts, which is also when
        most transient failures have already surfaced.
        """
        attempts = self._config.max_retries + 1

        for attempt in range(attempts):
            emitted = 0
            try:
                async for delta in self._stream_once(system=system, prompt=prompt):
                    emitted += 1
                    yield delta
                return
            except Exception as exc:
                exhausted = attempt == attempts - 1
                if emitted or exhausted or not _is_transient(exc):
                    raise LLMError(f"Gemini streaming failed: {exc}") from exc
                delay = min(8.0, 0.5 * 2**attempt) + random.uniform(0, 0.4)
                logger.warning(
                    "gemini_stream_retry",
                    attempt=attempt + 1,
                    delay_seconds=round(delay, 2),
                    error=str(exc)[:120],
                )
                await asyncio.sleep(delay)

    async def _stream_once(self, *, system: str, prompt: str) -> AsyncIterator[str]:
        """Bridge the SDK's blocking generator onto the event loop.

        ``generate_content_stream`` yields synchronously, so iterating it
        directly would block every other request for the duration of the
        response. A worker thread pushes chunks into a queue this coroutine
        drains.
        """
        queue: asyncio.Queue[str | Exception | None] = asyncio.Queue(maxsize=32)
        loop = asyncio.get_running_loop()

        def produce() -> None:
            try:
                stream = self._client.models.generate_content_stream(
                    model=self._config.model,
                    contents=prompt,
                    config=self._generation_config(system),
                )
                for chunk in stream:
                    if text := (chunk.text or ""):
                        asyncio.run_coroutine_threadsafe(queue.put(text), loop).result()
            except Exception as exc:  # surfaced to the consumer below
                asyncio.run_coroutine_threadsafe(queue.put(exc), loop).result()
            finally:
                asyncio.run_coroutine_threadsafe(queue.put(None), loop).result()

        task = asyncio.create_task(asyncio.to_thread(produce))
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                if isinstance(item, Exception):
                    raise item
                yield item
        finally:
            # A client disconnect cancels this generator; the producer must not
            # be left running against a queue nobody drains.
            task.cancel()

    @staticmethod
    def _usage(response: Any) -> LLMUsage:
        metadata = getattr(response, "usage_metadata", None)
        if metadata is None:
            return LLMUsage()
        return LLMUsage(
            prompt_tokens=getattr(metadata, "prompt_token_count", 0) or 0,
            completion_tokens=getattr(metadata, "candidates_token_count", 0) or 0,
        )

    @staticmethod
    def _finish_reason(response: Any) -> str | None:
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return None
        reason = getattr(candidates[0], "finish_reason", None)
        return str(reason) if reason is not None else None
