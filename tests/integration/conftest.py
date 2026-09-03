"""Integration fixtures backed by a real Postgres with pgvector.

These tests run against a dedicated ``intelliassist_test`` database on the
Compose Postgres, created on first use and migrated with the real Alembic
migration — not ``create_all`` — so the migration itself stays under test.

Each test runs inside an outer transaction that is rolled back afterwards.
Sessions handed to application code join it as savepoints, so a service can
commit normally and still leave the database untouched between tests.
"""

from __future__ import annotations

import os
import subprocess
import sys
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import sqlalchemy as sa
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import (
    AsyncConnection,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.api.deps import get_redis, get_session_factory
from app.core.config import (
    AppConfig,
    DatabaseConfig,
    EmbeddingConfig,
    LLMConfig,
    RedisConfig,
    RerankerConfig,
    Settings,
)
from app.db.session import create_engine
from app.main import create_app
from app.services.embeddings.fake import FakeEmbeddingProvider
from app.services.ingestion.blob_store import UploadBlobStore
from app.services.llm.fake import FakeLLMProvider
from app.services.reranking.noop import NoopReranker
from app.workers.queue import RecordingJobQueue

PROJECT_ROOT = Path(__file__).resolve().parents[2]
TEST_DATABASE = "intelliassist_test"

_migrated = False


def _admin_url() -> str:
    return os.environ.get(
        "INTELLIASSIST_TEST_ADMIN_URL",
        "postgresql+asyncpg://intelliassist:intelliassist@localhost:5432/postgres",
    )


def _test_url() -> str:
    return os.environ.get(
        "INTELLIASSIST_TEST_DATABASE_URL",
        f"postgresql+asyncpg://intelliassist:intelliassist@localhost:5432/{TEST_DATABASE}",
    )


async def _ensure_database() -> None:
    """Create the test database if it is not there yet."""
    admin = create_async_engine(_admin_url(), isolation_level="AUTOCOMMIT")
    try:
        async with admin.connect() as connection:
            exists = await connection.scalar(
                sa.text("SELECT 1 FROM pg_database WHERE datname = :name"),
                {"name": TEST_DATABASE},
            )
            if not exists:
                await connection.execute(sa.text(f'CREATE DATABASE "{TEST_DATABASE}"'))
    finally:
        await admin.dispose()


def _run_migrations() -> None:
    """Apply the real migration chain once per test session."""
    global _migrated
    if _migrated:
        return

    env = {**os.environ, "INTELLIASSIST_DATABASE__URL": _test_url()}
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=PROJECT_ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=180,
    )
    if result.returncode != 0:
        raise RuntimeError(f"alembic upgrade failed:\n{result.stdout}\n{result.stderr}")
    _migrated = True


def integration_settings() -> Settings:
    """Real Postgres, fake AI providers.

    The point of these tests is the database, the migration and the wiring —
    not Gemini. Fakes keep them hermetic, free and fast while every SQL path,
    including pgvector search, runs for real.
    """
    return Settings(
        app=AppConfig(env="test", log_json=False, api_key=None),
        database=DatabaseConfig(url=_test_url()),
        redis=RedisConfig(url="redis://localhost:6379/1"),
        embedding=EmbeddingConfig(provider="fake", dimension=768),
        llm=LLMConfig(provider="fake"),
        reranker=RerankerConfig(enabled=False),
        _env_file=None,  # type: ignore[call-arg]
    )


@pytest.fixture
async def db_connection() -> AsyncIterator[AsyncConnection]:
    """An open connection inside a transaction that is always rolled back."""
    await _ensure_database()
    _run_migrations()

    engine = create_engine(integration_settings().database)
    connection = await engine.connect()
    transaction = await connection.begin()
    try:
        yield connection
    finally:
        await transaction.rollback()
        await connection.close()
        await engine.dispose()


@pytest.fixture
def session_factory(db_connection: AsyncConnection) -> async_sessionmaker[AsyncSession]:
    # Savepoints, so application code may commit freely while the outer
    # transaction still discards everything at the end of the test.
    return async_sessionmaker(
        bind=db_connection,
        expire_on_commit=False,
        join_transaction_mode="create_savepoint",
    )


@pytest.fixture
async def session(
    session_factory: async_sessionmaker[AsyncSession],
) -> AsyncIterator[AsyncSession]:
    async with session_factory() as db_session:
        yield db_session


class _FakeRedis:
    """Enough Redis for the code paths integration tests touch."""

    def __init__(self) -> None:
        self._store: dict[str, bytes] = {}

    async def get(self, key: str) -> bytes | None:
        return self._store.get(key)

    async def set(self, key: str, value: bytes, ex: int | None = None) -> None:
        self._store[key] = value

    async def setex(self, key: str, seconds: int, value: bytes) -> None:
        self._store[key] = value

    async def mget(self, keys: list[str]) -> list[bytes | None]:
        return [self._store.get(key) for key in keys]

    def pipeline(self, transaction: bool = True):  # type: ignore[no-untyped-def]
        outer = self

        class _Pipe:
            def __init__(self) -> None:
                self._ops: list[tuple[str, bytes]] = []

            async def __aenter__(self):  # type: ignore[no-untyped-def]
                return self

            async def __aexit__(self, *_: object) -> None:
                return None

            def set(self, key: str, value: bytes, ex: int | None = None) -> None:
                self._ops.append((key, value))

            async def execute(self) -> None:
                outer._store.update(dict(self._ops))

        return _Pipe()

    async def delete(self, *keys: str) -> int:
        removed = sum(1 for key in keys if self._store.pop(key, None) is not None)
        return removed

    async def ping(self) -> bool:
        return True

    async def aclose(self) -> None:
        return None


@pytest.fixture
def fake_redis() -> _FakeRedis:
    return _FakeRedis()


@pytest.fixture
def job_queue() -> RecordingJobQueue:
    return RecordingJobQueue()


@pytest.fixture
def app(
    session_factory: async_sessionmaker[AsyncSession],
    fake_redis: _FakeRedis,
    job_queue: RecordingJobQueue,
) -> FastAPI:
    """The real app, with only the process-wide singletons substituted.

    Lifespan does not run under ASGITransport, so the state it would populate
    is set here directly — which also keeps these tests off Redis and off the
    job queue while every request path stays exactly as in production.
    """
    settings = integration_settings()
    application = create_app(settings)

    application.state.embeddings = FakeEmbeddingProvider(dimension=settings.embedding.dimension)
    application.state.llm = FakeLLMProvider()
    application.state.reranker = NoopReranker()
    application.state.redis = fake_redis
    application.state.session_factory = session_factory
    application.state.job_queue = job_queue
    application.state.blobs = UploadBlobStore(fake_redis)  # type: ignore[arg-type]

    application.dependency_overrides[get_session_factory] = lambda: session_factory
    application.dependency_overrides[get_redis] = lambda: fake_redis
    return application


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[AsyncClient]:
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as async_client:
        yield async_client
