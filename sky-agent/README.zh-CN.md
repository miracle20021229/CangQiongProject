# Sky Agent（苍穹外卖智能客服）

本目录是从 `agentic-customer-service-platform` 迁入的 Python Agent。第一阶段保留原有 Agent 核心实现、数据库迁移和关键测试；苍穹外卖 Java API、用户 JWT 与订单工具接入尚待改造。

Python 3.12，使用 FastAPI、LangGraph、PostgreSQL 和 Qdrant。用户、订单及业务写操作由 Java 后端负责，Agent 通过内部 HTTP API 调用 Java，不直连业务数据库。

## 本地启动

将 `.env.example` 复制为 `.env` 并填写本机配置。`.env` 含本地配置，不要提交。

```powershell
uv sync --frozen
docker compose up -d db qdrant
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
```

## 关键测试

```powershell
uv run pytest tests/test_agent.py tests/test_idempotency.py tests/test_authorization.py tests/test_checkpointing.py tests/test_workflow_interruption.py tests/test_decision_compiler.py tests/test_policies.py tests/test_tools.py
```

现阶段保留原项目演示业务表和 local_demo 能力。订单查询、未付款取消、催单、中文知识问答及身份边界，按迁移计划分阶段改造和验收。
