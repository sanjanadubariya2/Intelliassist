"""Gemini embedding provider.

Uses ``gemini-embedding-001`` with Matryoshka truncation to the configured
dimension. The model's native output is 3072, which pgvector can store but
cannot build an HNSW index over — so requesting 768 directly is not an
optimisation, it is what keeps vector search indexed at all.
"""

from __future__ import annotations

import asyncio
import math
from collections.abc import Sequence
from typing import Any

from google import genai
from google.genai import types
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from app.core.config import EmbeddingConfig
from app.core.exceptions import ConfigurationError, EmbeddingError
from app.core.logging import get_logger
from app.services.embeddings.base import EmbeddingTask

logger = get_logger(__name__)


class GeminiEmbeddingProvider:
    def __init__(self, config: EmbeddingConfig, *, api_key: str | None) -> None:
        if not api_key:
            raise ConfigurationError(
                "GEMINI_API_KEY is required when EMBEDDING__PROVIDER is 'gemini'."
            )
        self._config = config
        self._client = genai.Client(api_key=api_key)

    @property
    def model(self) -> str:
        return self._config.model

    @property
    def dimension(self) -> int:
        return self._config.dimension

    async def embed(
        self, texts: Sequence[str], *, task: EmbeddingTask = EmbeddingTask.DOCUMENT
    ) -> list[list[float]]:
        if not texts:
            return []

        vectors: list[list[float]] = []
        # Batched to bound both request size and the blast radius of a retry.
        for start in range(0, len(texts), self._config.batch_size):
            batch = list(texts[start : start + self._config.batch_size])
            vectors.extend(await self._embed_batch(batch, task=task))
        return vectors

    async def embed_one(
        self, text: str, *, task: EmbeddingTask = EmbeddingTask.QUERY
    ) -> list[float]:
        vectors = await self.embed([text], task=task)
        if not vectors:
            raise EmbeddingError("Provider returned no vector for a non-empty input.")
        return vectors[0]

    async def _embed_batch(self, batch: list[str], *, task: EmbeddingTask) -> list[list[float]]:
        try:
            response = await self._call_with_retry(batch, task=task)
        except Exception as exc:
            raise EmbeddingError(
                f"Gemini embedding request failed after retries: {exc}",
                details={"model": self.model, "batch_size": len(batch)},
            ) from exc

        embeddings = getattr(response, "embeddings", None) or []
        if len(embeddings) != len(batch):
            raise EmbeddingError(
                "Gemini returned a different number of vectors than inputs.",
                details={"expected": len(batch), "received": len(embeddings)},
            )

        return [self._normalise(embedding.values or []) for embedding in embeddings]

    @retry(
        retry=retry_if_exception_type(Exception),
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=1, max=10),
        reraise=True,
    )
    async def _call_with_retry(self, batch: list[str], *, task: EmbeddingTask) -> Any:
        task_type = (
            self._config.task_type_query
            if task is EmbeddingTask.QUERY
            else self._config.task_type_document
        )
        # The SDK call is synchronous; off-loading keeps the event loop free.
        return await asyncio.wait_for(
            asyncio.to_thread(
                self._client.models.embed_content,
                model=self._config.model,
                contents=batch,
                config=types.EmbedContentConfig(
                    task_type=task_type,
                    output_dimensionality=self._config.dimension,
                ),
            ),
            timeout=self._config.timeout_seconds,
        )

    def _normalise(self, values: Sequence[float]) -> list[float]:
        """Re-normalise to unit length.

        Gemini returns normalised vectors at the native width, but truncating a
        Matryoshka embedding shortens it and leaves the norm below 1. Cosine
        distance in pgvector assumes unit vectors, so the truncated vector has
        to be renormalised or every distance is subtly wrong.
        """
        if len(values) != self._config.dimension:
            raise EmbeddingError(
                "Gemini returned an unexpected vector width.",
                details={"expected": self._config.dimension, "received": len(values)},
            )

        norm = math.sqrt(sum(value * value for value in values))
        if norm == 0.0:
            raise EmbeddingError("Gemini returned a zero vector.")
        return [value / norm for value in values]
