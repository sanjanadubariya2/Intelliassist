"""Phase 0 gate: the app boots, serves liveness, and reports dependency failure
honestly rather than pretending to be ready."""

from __future__ import annotations

from fastapi.testclient import TestClient


def test_liveness_is_ok_without_any_dependencies(client: TestClient) -> None:
    response = client.get("/health/live")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["environment"] == "test"


def test_liveness_does_not_depend_on_postgres_or_redis(client: TestClient) -> None:
    # Neither is running in unit tests. Liveness must still pass, otherwise an
    # orchestrator would restart a process whose only problem is elsewhere.
    assert client.get("/health/live").json()["components"] == []


def test_readiness_reports_503_when_dependencies_are_unreachable(client: TestClient) -> None:
    response = client.get("/health/ready")

    assert response.status_code == 503
    body = response.json()
    assert body["status"] == "degraded"

    components = {c["name"]: c for c in body["components"]}
    assert components["postgres"]["healthy"] is False
    assert components["redis"]["healthy"] is False
    assert components["postgres"]["detail"]  # the failure reason is surfaced


def test_disabled_reranker_counts_as_healthy(client: TestClient) -> None:
    components = {c["name"]: c for c in client.get("/health/ready").json()["components"]}

    assert components["reranker"]["healthy"] is True
    assert components["reranker"]["detail"] == "disabled"


def test_every_response_carries_a_request_id(client: TestClient) -> None:
    response = client.get("/health/live")
    assert response.headers["X-Request-ID"]


def test_inbound_request_id_is_preserved(client: TestClient) -> None:
    # A trace started in Spring Boot must keep its id across the hop.
    response = client.get("/health/live", headers={"X-Request-ID": "trace-from-spring"})
    assert response.headers["X-Request-ID"] == "trace-from-spring"
