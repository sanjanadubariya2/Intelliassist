"""Aggregates the versioned API surface.

Health lives outside ``/api/v1`` and outside the API-key guard: probes are
infrastructure, not part of the contract Spring Boot consumes.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends

from app.api.deps import require_api_key
from app.api.v1 import documents, health, knowledge, rag

# Unauthenticated, unversioned.
public_router = APIRouter()
public_router.include_router(health.router)

# Everything the Spring Boot service calls, behind the API-key guard.
api_router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_api_key)])
api_router.include_router(knowledge.router)
api_router.include_router(documents.router)
api_router.include_router(rag.router)
