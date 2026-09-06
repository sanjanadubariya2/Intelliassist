"""Provider construction from configuration.

One place decides which concrete provider a name maps to. The API process and
the worker process both call these, so they cannot drift into using different
models — which, for embeddings, would corrupt an index silently.
"""

from __future__ import annotations

from redis.asyncio import Redis

from app.core.config import Settings
from app.core.exceptions import ConfigurationError
from app.services.embeddings.base import EmbeddingProvider
from app.services.embeddings.cache import CachedEmbeddingProvider
from app.services.embeddings.fake import FakeEmbeddingProvider
from app.services.llm.base import LLMProvider
from app.services.llm.fake import FakeLLMProvider


def build_embedding_provider(
    settings: Settings, *, redis: Redis | None = None
) -> EmbeddingProvider:
    """Build the configured embedding provider, wrapped in a cache when Redis
    is available."""
    provider: EmbeddingProvider

    match settings.embedding.provider:
        case "gemini":
            # Imported lazily so the google-genai client is never constructed in
            # test runs that use the fake provider.
            from app.services.embeddings.gemini import GeminiEmbeddingProvider

            api_key = settings.gemini_api_key
            provider = GeminiEmbeddingProvider(
                settings.embedding,
                api_key=api_key.get_secret_value() if api_key else None,
            )
        case "fake":
            provider = FakeEmbeddingProvider(dimension=settings.embedding.dimension)
        case unknown:  # pragma: no cover - Literal already constrains this
            raise ConfigurationError(f"Unknown embedding provider {unknown!r}.")

    if redis is None:
        return provider
    return CachedEmbeddingProvider(
        provider, redis, ttl_seconds=settings.embedding.cache_ttl_seconds
    )


def build_llm_provider(settings: Settings) -> LLMProvider:
    match settings.llm.provider:
        case "gemini":
            from app.services.llm.gemini import GeminiLLMProvider

            api_key = settings.gemini_api_key
            return GeminiLLMProvider(
                settings.llm,
                api_key=api_key.get_secret_value() if api_key else None,
            )
        case "fake":
            return FakeLLMProvider()
        case unknown:  # pragma: no cover
            raise ConfigurationError(f"Unknown LLM provider {unknown!r}.")
