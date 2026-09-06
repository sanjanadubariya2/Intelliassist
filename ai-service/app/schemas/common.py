"""Shared response envelopes.

These are part of the public contract the Spring Boot service codes against, so
the shapes here should change only deliberately.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ErrorDetail(BaseModel):
    code: str = Field(description="Stable machine-readable error code.")
    message: str = Field(description="Human-readable explanation.")
    details: dict[str, Any] = Field(default_factory=dict)
    request_id: str | None = Field(
        default=None, description="Correlates with the X-Request-ID response header."
    )


class ErrorResponse(BaseModel):
    """Every non-2xx response from this service has this shape."""

    model_config = ConfigDict(
        json_schema_extra={
            "example": {
                "error": {
                    "code": "not_found",
                    "message": "Knowledge base 3f1a... does not exist",
                    "details": {},
                    "request_id": "01JB7T9K2M4Q",
                }
            }
        }
    )

    error: ErrorDetail


class Page[T](BaseModel):
    items: list[T]
    total: int
    limit: int
    offset: int


class HealthComponent(BaseModel):
    name: str
    healthy: bool
    detail: str | None = None
    latency_ms: float | None = None


class HealthResponse(BaseModel):
    status: str = Field(description="'ok' or 'degraded'.")
    version: str
    environment: str
    components: list[HealthComponent] = Field(default_factory=list)
