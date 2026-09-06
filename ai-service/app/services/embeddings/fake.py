"""Deterministic embedding provider for tests.

Same text always yields the same vector, and similar text yields similar
vectors, because the hash seeds a token-bag projection rather than pure noise.
That second property is what makes it usable in retrieval tests: a query about
"vacation policy" ranks the vacation chunk above the expenses chunk, without a
network call or an API key.
"""

from __future__ import annotations

import hashlib
import math
import re
from collections.abc import Sequence

from app.services.embeddings.base import EmbeddingTask

_WORD = re.compile(r"\w+")


class FakeEmbeddingProvider:
    def __init__(self, *, dimension: int = 768, model: str = "fake-embedding") -> None:
        self._dimension = dimension
        self._model = model
        self.call_count = 0
        self.embedded_texts: list[str] = []

    @property
    def model(self) -> str:
        return self._model

    @property
    def dimension(self) -> int:
        return self._dimension

    async def embed(
        self, texts: Sequence[str], *, task: EmbeddingTask = EmbeddingTask.DOCUMENT
    ) -> list[list[float]]:
        self.call_count += 1
        self.embedded_texts.extend(texts)
        return [self._vector(text) for text in texts]

    async def embed_one(
        self, text: str, *, task: EmbeddingTask = EmbeddingTask.QUERY
    ) -> list[float]:
        vectors = await self.embed([text], task=task)
        return vectors[0]

    def _vector(self, text: str) -> list[float]:
        """Bag-of-words projected into a fixed space, then L2-normalised.

        Each distinct word contributes to a few deterministic dimensions, so
        texts sharing words end up close under cosine distance.
        """
        vector = [0.0] * self._dimension

        for word in _WORD.findall(text.lower()):
            digest = hashlib.sha256(word.encode("utf-8")).digest()
            # Three positions per word keeps collisions rare without making the
            # vector dense enough that everything looks similar.
            for offset in range(3):
                position = int.from_bytes(digest[offset * 4 : offset * 4 + 4], "big")
                sign = 1.0 if digest[offset + 12] % 2 == 0 else -1.0
                vector[position % self._dimension] += sign

        norm = math.sqrt(sum(component * component for component in vector))
        if norm == 0.0:
            # Empty or punctuation-only text still needs a valid unit vector.
            vector[0] = 1.0
            return vector
        return [component / norm for component in vector]
