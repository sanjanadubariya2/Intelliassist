"""The X-API-Key guard is the only thing standing between the ingestion
endpoint and anything that can reach the network, so its behaviour is pinned
here before any real route sits behind it."""

from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.deps import require_api_key
from app.core.config import AppConfig, Settings
from app.main import create_app
from tests.conftest import make_settings


def _settings(api_key: str | None) -> Settings:
    return make_settings(app=AppConfig(env="test", log_json=False, api_key=api_key))


def _app_with_guarded_route(api_key: str | None) -> FastAPI:
    from fastapi import Depends

    app = create_app(_settings(api_key))

    @app.get("/api/v1/_probe", dependencies=[Depends(require_api_key)])
    async def _probe() -> dict[str, bool]:
        return {"reached": True}

    return app


@pytest.fixture
def guarded() -> TestClient:
    with TestClient(_app_with_guarded_route("correct-key")) as client:
        yield client


def test_request_without_the_header_is_rejected(guarded: TestClient) -> None:
    response = guarded.get("/api/v1/_probe")

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"


def test_wrong_key_is_rejected(guarded: TestClient) -> None:
    response = guarded.get("/api/v1/_probe", headers={"X-API-Key": "wrong-key"})

    assert response.status_code == 401


def test_correct_key_passes(guarded: TestClient) -> None:
    response = guarded.get("/api/v1/_probe", headers={"X-API-Key": "correct-key"})

    assert response.status_code == 200
    assert response.json() == {"reached": True}


def test_guard_is_inert_when_no_key_is_configured() -> None:
    # Allowed locally so a developer can curl the service without ceremony.
    # Settings validation is what makes this impossible in staging/production.
    with TestClient(_app_with_guarded_route(None)) as client:
        assert client.get("/api/v1/_probe").status_code == 200


def test_health_is_never_guarded(guarded: TestClient) -> None:
    # An orchestrator has no credentials; a guarded probe would fail closed and
    # take the whole deployment down.
    assert guarded.get("/health/live").status_code == 200


def test_error_envelope_shape_is_stable(guarded: TestClient) -> None:
    error = guarded.get("/api/v1/_probe").json()["error"]

    assert set(error) == {"code", "message", "details", "request_id"}
    assert error["request_id"]
