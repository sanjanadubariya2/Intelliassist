"""Domain exception hierarchy.

Services and repositories raise these; ``app.api.errors`` is the single place
that turns them into HTTP responses. Nothing below the API layer imports
FastAPI or knows what a status code is.
"""

from __future__ import annotations

from typing import Any


class IntelliAssistError(Exception):
    """Base class for every error this service raises deliberately."""

    code: str = "internal_error"
    status_code: int = 500

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}


# --------------------------------------------------------------- client -----


class NotFoundError(IntelliAssistError):
    code = "not_found"
    status_code = 404


class ConflictError(IntelliAssistError):
    code = "conflict"
    status_code = 409


class InvalidRequestError(IntelliAssistError):
    """Semantically invalid input that Pydantic cannot express on its own."""

    code = "invalid_request"
    status_code = 422


class UnsupportedDocumentTypeError(InvalidRequestError):
    code = "unsupported_document_type"
    status_code = 415


class PayloadTooLargeError(InvalidRequestError):
    code = "payload_too_large"
    status_code = 413


class AuthenticationError(IntelliAssistError):
    code = "unauthorized"
    status_code = 401


# ------------------------------------------------------------- internal -----


class ConfigurationError(IntelliAssistError):
    code = "configuration_error"
    status_code = 500


class ProviderError(IntelliAssistError):
    """An upstream AI provider failed after exhausting retries."""

    code = "provider_error"
    status_code = 502


class EmbeddingError(ProviderError):
    code = "embedding_error"


class LLMError(ProviderError):
    code = "llm_error"


class ExtractionError(IntelliAssistError):
    code = "extraction_error"
    status_code = 422


class DependencyUnavailableError(IntelliAssistError):
    """Postgres, Redis or a model that should be there is not."""

    code = "dependency_unavailable"
    status_code = 503
