"""Configuration is the one place model names, dimensions and thresholds live,
so its guard rails are worth testing directly."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.core.config import (
    MAX_INDEXABLE_DIMENSION,
    AppConfig,
    ChunkingConfig,
    EmbeddingConfig,
    LLMConfig,
    Settings,
)


def test_embedding_dimension_above_the_hnsw_ceiling_is_rejected() -> None:
    # 3072 is the native output of gemini-embedding-001. Accepting it would
    # silently cost every query an index and fall back to sequential scan.
    with pytest.raises(ValidationError, match="HNSW"):
        EmbeddingConfig(dimension=3072)


def test_dimension_at_the_ceiling_is_allowed() -> None:
    assert EmbeddingConfig(dimension=MAX_INDEXABLE_DIMENSION).dimension == 2000


def test_default_dimension_is_768() -> None:
    assert EmbeddingConfig().dimension == 768


def test_chunk_overlap_must_be_smaller_than_the_chunk() -> None:
    with pytest.raises(ValidationError, match="overlap_tokens"):
        ChunkingConfig(target_tokens=200, overlap_tokens=200)


def test_max_tokens_cannot_undercut_the_target() -> None:
    with pytest.raises(ValidationError, match="max_tokens"):
        ChunkingConfig(target_tokens=500, max_tokens=400)


def test_production_requires_an_api_key() -> None:
    with pytest.raises(ValidationError, match="API_KEY"):
        Settings(
            app=AppConfig(env="production", api_key=None),
            gemini_api_key="present",  # type: ignore[arg-type]
            _env_file=None,  # type: ignore[call-arg]
        )


def test_production_with_gemini_requires_a_provider_key() -> None:
    with pytest.raises(ValidationError, match="GEMINI_API_KEY"):
        Settings(
            app=AppConfig(env="production", api_key="set"),  # type: ignore[arg-type]
            llm=LLMConfig(provider="gemini"),
            gemini_api_key=None,
            _env_file=None,  # type: ignore[call-arg]
        )


def test_local_environment_needs_no_secrets() -> None:
    settings = Settings(app=AppConfig(env="local"), _env_file=None)  # type: ignore[call-arg]

    assert settings.is_local is True
    assert settings.app.api_key is None


def test_api_key_is_not_exposed_by_repr() -> None:
    settings = Settings(
        app=AppConfig(env="local", api_key="super-secret"),  # type: ignore[arg-type]
        _env_file=None,  # type: ignore[call-arg]
    )

    assert "super-secret" not in repr(settings)
    assert settings.app.api_key is not None
    assert settings.app.api_key.get_secret_value() == "super-secret"
