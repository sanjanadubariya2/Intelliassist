"""compose.yaml and the configuration defaults must agree.

If they drift, the service starts cleanly and then fails to reach a database
that is running perfectly well two ports away — a confusing failure worth one
cheap test.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.core.config import DatabaseConfig, RedisConfig

# One more level up than before: this service now lives in ai-service/,
# while compose.yaml stays at the repo root because it wires all of the
# services together.
COMPOSE = Path(__file__).resolve().parents[3] / "compose.yaml"


def _published_host_port(service: str) -> str:
    """Host-side port that `service` publishes, from the compose file."""
    text = COMPOSE.read_text(encoding="utf-8")
    block = re.search(rf"^  {service}:\n(.*?)(?=^  \w|\Z)", text, re.MULTILINE | re.DOTALL)
    assert block is not None, f"service {service} not found in compose.yaml"

    mapping = re.search(r'-\s*"(\d+):(\d+)"', block.group(1))
    assert mapping is not None, f"service {service} publishes no port"
    return mapping.group(1)


def test_database_default_matches_the_published_postgres_port() -> None:
    assert f":{_published_host_port('postgres')}/" in DatabaseConfig().url


def test_redis_default_matches_the_published_redis_port() -> None:
    assert f":{_published_host_port('redis')}/" in RedisConfig().url


def test_compose_declares_an_explicit_project_name() -> None:
    # Without one, Compose derives the project from the directory name, so a
    # rename or a clone into a differently-named folder silently orphans the
    # existing containers and volumes.
    assert re.search(r"^name:\s*\S+", COMPOSE.read_text(encoding="utf-8"), re.MULTILINE)


def test_pgvector_image_is_pinned_to_a_version_with_iterative_scans() -> None:
    # Filtered HNSW search needs pgvector >= 0.8; pg17 images ship 0.8.x.
    assert "pgvector/pgvector:pg17" in COMPOSE.read_text(encoding="utf-8")
