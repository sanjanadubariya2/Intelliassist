"""Shared test fixtures.

Unit tests must run with no Postgres, no Redis, no API key and no network. The
app starts anyway because both connection pools are lazy — which is exactly what
lets a readiness test assert the 503 path without any infrastructure.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.core.config import (
    AppConfig,
    DatabaseConfig,
    EmbeddingConfig,
    LLMConfig,
    RedisConfig,
    RerankerConfig,
    Settings,
)
from app.main import create_app


def make_settings(**overrides: object) -> Settings:
    """Settings that never touch the network or the developer's real .env.

    Both providers are the deterministic fakes, so no unit test needs an API
    key. The database and Redis URLs point at closed ports on purpose: both
    pools are lazy, so the app still boots and the readiness test can assert
    the 503 path with no infrastructure running.
    """
    defaults: dict[str, object] = {
        "app": AppConfig(env="test", debug=True, log_json=False, api_key=None),
        "database": DatabaseConfig(url="postgresql+asyncpg://unused:unused@127.0.0.1:59999/unused"),
        "redis": RedisConfig(url="redis://127.0.0.1:59998/0"),
        "embedding": EmbeddingConfig(provider="fake", dimension=768),
        "llm": LLMConfig(provider="fake"),
        "reranker": RerankerConfig(enabled=False),
    }
    defaults.update(overrides)
    return Settings(_env_file=None, **defaults)  # type: ignore[arg-type]


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def app(settings: Settings) -> FastAPI:
    return create_app(settings)


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client
