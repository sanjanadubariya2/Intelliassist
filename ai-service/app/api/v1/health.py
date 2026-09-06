"""Liveness and readiness probes.

The distinction matters operationally: liveness answers "is this process
alive", readiness answers "can it serve a request right now". They differ
because model loading and connection pools take time, and traffic routed
before those are ready produces errors that look like application bugs.

Neither probe requires an API key — an orchestrator has no credentials.
"""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable

from fastapi import APIRouter, Response

from app.api.deps import EngineDep, RedisDep, RerankerDep, SettingsDep
from app.core.config import Settings
from app.db import redis as redis_db
from app.db import session as db
from app.schemas.common import HealthComponent, HealthResponse
from app.services.reranking.base import Reranker

router = APIRouter(tags=["health"])


async def _check(name: str, probe: Callable[[], Awaitable[None]]) -> HealthComponent:
    started = time.perf_counter()
    try:
        await probe()
    except Exception as exc:
        return HealthComponent(
            name=name,
            healthy=False,
            detail=f"{type(exc).__name__}: {exc}",
            latency_ms=round((time.perf_counter() - started) * 1000, 2),
        )
    return HealthComponent(
        name=name,
        healthy=True,
        latency_ms=round((time.perf_counter() - started) * 1000, 2),
    )


def _reranker_component(settings: Settings, reranker: Reranker) -> HealthComponent:
    """Reranking is optional, so a disabled or passthrough reranker is healthy —
    but an *enabled* model that has not finished loading is not ready for
    traffic, and routing requests to it would produce errors that look like
    application bugs."""
    if not settings.reranker.enabled:
        return HealthComponent(name="reranker", healthy=True, detail="disabled")
    return HealthComponent(
        name="reranker",
        healthy=reranker.is_ready,
        detail=reranker.name if reranker.is_ready else "loading",
    )


@router.get("/health/live", response_model=HealthResponse, summary="Liveness probe")
async def live(settings: SettingsDep) -> HealthResponse:
    """Process is up. Checks nothing external by design — a failing dependency
    must not cause the orchestrator to restart an otherwise healthy process."""
    return HealthResponse(
        status="ok",
        version=settings.app.version,
        environment=settings.app.env,
    )


@router.get(
    "/health/ready",
    response_model=HealthResponse,
    summary="Readiness probe",
    responses={503: {"model": HealthResponse, "description": "A dependency is unavailable"}},
)
async def ready(
    settings: SettingsDep,
    engine: EngineDep,
    redis_client: RedisDep,
    reranker: RerankerDep,
    response: Response,
) -> HealthResponse:
    components = [
        await _check("postgres", lambda: db.ping(engine)),
        await _check("redis", lambda: redis_db.ping(redis_client)),
        _reranker_component(settings, reranker),
    ]

    healthy = all(component.healthy for component in components)
    if not healthy:
        response.status_code = 503

    return HealthResponse(
        status="ok" if healthy else "degraded",
        version=settings.app.version,
        environment=settings.app.env,
        components=components,
    )
