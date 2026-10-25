# Sky Agent

苍穹外卖智能客服 Agent。当前目录由 `agentic-customer-service-platform` 按《sky_agent迁移计划》迁入，第一阶段保留原 Agent 核心实现、数据库迁移和关键测试；面向苍穹外卖的 Java API、用户 JWT 与订单工具仍待改造。

Python 3.12，FastAPI、LangGraph、PostgreSQL、Qdrant。Java 后端是用户、订单及业务写操作的唯一权威入口；Agent 不应直连 Java 业务数据库。

## 本地依赖

复制 `.env.example` 为 `.env`，按本机环境填写配置。不要提交 `.env`。

```powershell
uv sync --frozen
docker compose up -d db qdrant
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
```

运行迁移计划列出的关键测试：

```powershell
uv run pytest tests/test_agent.py tests/test_idempotency.py tests/test_authorization.py tests/test_checkpointing.py tests/test_workflow_interruption.py tests/test_decision_compiler.py tests/test_policies.py tests/test_tools.py
```

当前阶段仍包含原项目的演示数据模型和 local_demo 能力；订单接入、身份验证和生产配置须按迁移计划逐步替换后再用于苍穹外卖。
