.PHONY: sync dev up down test lint typecheck migrate seed rag-ingest docker-validate ci-backend

sync:
	uv sync --frozen

dev:
	uv run --frozen uvicorn app.main:app --reload

up:
	docker compose up -d db qdrant

down:
	docker compose down

test:
	uv run --frozen pytest tests/test_agent.py tests/test_idempotency.py tests/test_authorization.py tests/test_checkpointing.py tests/test_workflow_interruption.py tests/test_decision_compiler.py tests/test_policies.py tests/test_tools.py

lint:
	uv run --frozen ruff check app tests scripts
	uv run --frozen ruff format --check app tests scripts

typecheck:
	uv run --frozen mypy app tests scripts

migrate:
	uv run --frozen alembic upgrade head

seed:
	uv run --frozen python -m scripts.seed

rag-ingest:
	uv run --frozen python -m scripts.rag_ingest

docker-validate:
	docker compose config --quiet
	docker build --tag sky-agent:local .

ci-backend: sync lint typecheck test
