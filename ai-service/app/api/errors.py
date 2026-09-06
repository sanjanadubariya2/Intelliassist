"""Exception handlers.

The single translation point between the domain exception hierarchy and HTTP.
Every failure leaves this service in the ``ErrorResponse`` envelope, so the
Spring Boot client can parse one shape regardless of what went wrong.
"""

from __future__ import annotations

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.exceptions import IntelliAssistError
from app.core.logging import get_logger, get_request_id
from app.schemas.common import ErrorDetail, ErrorResponse

logger = get_logger(__name__)


def _envelope(
    *, code: str, message: str, status_code: int, details: dict[str, object] | None = None
) -> JSONResponse:
    payload = ErrorResponse(
        error=ErrorDetail(
            code=code,
            message=message,
            details=details or {},
            request_id=get_request_id(),
        )
    )
    return JSONResponse(status_code=status_code, content=payload.model_dump(mode="json"))


async def handle_domain_error(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, IntelliAssistError)
    # 5xx means we broke; 4xx means the caller did. Only the former is our bug.
    log = logger.error if exc.status_code >= 500 else logger.info
    log("domain_error", code=exc.code, message=exc.message, details=exc.details)
    return _envelope(
        code=exc.code,
        message=exc.message,
        status_code=exc.status_code,
        details=exc.details,
    )


async def handle_validation_error(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    return _envelope(
        code="validation_error",
        message="Request body or parameters failed validation.",
        status_code=422,
        details={"errors": exc.errors()},
    )


async def handle_http_exception(_request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    return _envelope(
        code="http_error",
        message=str(exc.detail),
        status_code=exc.status_code,
    )


async def handle_unexpected_error(_request: Request, exc: Exception) -> JSONResponse:
    # Never leak an internal message or stack trace to the caller.
    logger.exception("unhandled_exception", error_type=type(exc).__name__)
    return _envelope(
        code="internal_error",
        message="An internal error occurred.",
        status_code=500,
    )


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(IntelliAssistError, handle_domain_error)
    app.add_exception_handler(RequestValidationError, handle_validation_error)
    app.add_exception_handler(StarletteHTTPException, handle_http_exception)
    app.add_exception_handler(Exception, handle_unexpected_error)
