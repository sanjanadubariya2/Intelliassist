"""Cross-encoder reranking.

Fusion ranks chunks without ever comparing the query and the chunk together — a
bi-encoder embeds each independently, and BM25 counts terms. A cross-encoder
reads the pair jointly, which is far more accurate and far too slow to run over
a whole corpus. Running it over ~30 fused candidates to pick the final ~6 is
where that accuracy is affordable.

Two things matter operationally:

* The model is loaded once, at startup, not on first request. Otherwise the
  first user pays several seconds of model load.
* Scoring is blocking CPU/GPU work and runs in a thread. On the event loop it
  would stall every other request in the process for the duration.
"""

from __future__ import annotations

import asyncio
from collections.abc import Sequence
from typing import Any

from app.core.config import RerankerConfig
from app.core.logging import get_logger
from app.schemas.retrieval import RetrievalSource, ScoredChunk

logger = get_logger(__name__)


class CrossEncoderReranker:
    def __init__(self, config: RerankerConfig) -> None:
        self._config = config
        self._model: Any | None = None

    @property
    def name(self) -> str:
        return self._config.model

    @property
    def is_ready(self) -> bool:
        return self._model is not None

    async def load(self) -> None:
        """Import and load the model. Called from the app lifespan.

        sentence-transformers is an optional extra; when it is absent this
        raises and the caller falls back to the passthrough reranker.
        """
        if self._model is not None:
            return

        def _load() -> Any:
            from sentence_transformers import CrossEncoder

            device = None if self._config.device == "auto" else self._config.device
            return CrossEncoder(
                self._config.model,
                max_length=self._config.max_length,
                device=device,
            )

        self._model = await asyncio.to_thread(_load)
        logger.info("reranker_loaded", model=self._config.model, device=self._config.device)

    async def rerank(
        self, *, query: str, candidates: Sequence[ScoredChunk], limit: int
    ) -> list[ScoredChunk]:
        if not candidates:
            return []
        if self._model is None:
            raise RuntimeError("Reranker used before load() completed.")

        pairs = [(query, scored.chunk.text) for scored in candidates]
        scores = await asyncio.to_thread(self._predict, pairs)

        reranked = sorted(
            (
                scored.rescored(float(score), RetrievalSource.RERANK)
                for scored, score in zip(candidates, scores, strict=True)
            ),
            key=lambda scored: scored.score,
            reverse=True,
        )
        return reranked[:limit]

    def _predict(self, pairs: list[tuple[str, str]]) -> list[float]:
        assert self._model is not None
        return list(
            self._model.predict(
                pairs,
                batch_size=self._config.batch_size,
                show_progress_bar=False,
            )
        )
