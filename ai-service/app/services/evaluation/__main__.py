"""Run an evaluation dataset from the command line.

    python -m app.services.evaluation --dataset evaluation/acme.jsonl \\
        --knowledge-base-id <uuid> --output results.json

Deliberately a separate entry point from `python -m app.cli eval`, which runs
the older chunk-id golden set. Both exist because they answer different
questions: that one needs chunk ids and measures ranking precisely, this one
takes filenames and can be written by whoever wrote the documents.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import uuid
from pathlib import Path

from app.core.config import get_settings
from app.core.logging import configure_logging
from app.db.redis import create_redis_client, create_redis_pool
from app.db.session import create_engine, create_session_factory, session_scope
from app.repositories.chunk_repo import ChunkRepository
from app.services.evaluation.dataset import DatasetError, EvalCase, load_dataset
from app.services.evaluation.dataset_runner import DEFAULT_K, DatasetEvaluationRunner
from app.services.evaluation.report import EvalReport
from app.services.providers import build_embedding_provider, build_llm_provider
from app.services.rag_service import RagService
from app.services.reranking.noop import NoopReranker
from app.services.retrieval.bm25 import Bm25Retriever
from app.services.retrieval.dense import DenseRetriever
from app.services.retrieval.fusion import FusionRetriever
from app.services.retrieval.index.bm25_index import Bm25IndexCache


async def _run(
    *,
    cases: list[EvalCase],
    dataset_path: Path,
    knowledge_base_id: uuid.UUID,
    k: int,
) -> EvalReport:
    settings = get_settings()
    engine = create_engine(settings.database)
    redis_pool = create_redis_pool(settings.redis)
    redis = create_redis_client(redis_pool)

    try:
        async with session_scope(create_session_factory(engine)) as session:
            embeddings = build_embedding_provider(settings, redis=redis)
            chunk_repo = ChunkRepository(session)

            # The same assembly production uses. Evaluating a differently wired
            # pipeline would measure something nobody ships.
            service = RagService(
                settings=settings,
                retriever=FusionRetriever(
                    retrievers=[
                        DenseRetriever(chunk_repo=chunk_repo, embeddings=embeddings),
                        Bm25Retriever(chunk_repo=chunk_repo, cache=Bm25IndexCache(redis)),
                    ],
                    weights=[settings.retrieval.dense_weight, settings.retrieval.bm25_weight],
                    k=settings.retrieval.rrf_k,
                ),
                reranker=NoopReranker(),
                llm=build_llm_provider(settings),
            )

            return await DatasetEvaluationRunner(service, k=k).run(
                cases,
                knowledge_base_id=knowledge_base_id,
                dataset=str(dataset_path),
            )
    finally:
        await redis.aclose()
        await redis_pool.disconnect()
        await engine.dispose()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m app.services.evaluation",
        description="Run a RAG evaluation dataset against a knowledge base.",
    )
    parser.add_argument("--dataset", required=True, type=Path, help="a .json or .jsonl dataset")
    parser.add_argument("--knowledge-base-id", required=True, type=uuid.UUID)
    parser.add_argument("--output", type=Path, help="write the full results as JSON")
    parser.add_argument(
        "--k",
        type=int,
        default=DEFAULT_K,
        help=f"cutoff for hit@k and ranking metrics (default {DEFAULT_K})",
    )

    args = parser.parse_args(argv)

    try:
        # Loaded before anything is connected and before any model is called:
        # a malformed dataset should cost nothing to discover.
        cases = load_dataset(args.dataset)
    except DatasetError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    configure_logging(level=get_settings().app.log_level, json_output=False)

    report = asyncio.run(
        _run(
            cases=cases,
            dataset_path=args.dataset,
            knowledge_base_id=args.knowledge_base_id,
            k=args.k,
        )
    )

    print(report.render())

    # Written out here rather than inside the coroutine: blocking file I/O has
    # no business on the event loop, even briefly.
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report.to_json(), encoding="utf-8")
        print("\nresults written to " + str(args.output))

    # Non-zero when a question errored, so CI notices. Unmet expectations are a
    # quality signal rather than a broken run and do not fail the command — a
    # threshold for that belongs in whatever reads the JSON.
    return 1 if report.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
