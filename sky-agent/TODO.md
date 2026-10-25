# 项目 TODO

集中记录项目阅读与开发中发现的待办；生产加固事项另见 [工程加固路线图](docs/engineering-hardening-roadmap.md)。

## 决策模型与协议

- [ ] **P2：梳理 SemanticDecision V2/V3 与内部决策模型，减少重复和命名歧义**（记录于 2026-10-07）
  - **现状**：`SemanticDecisionV3` 使用三个独立目标类型表达结构约束；`normalize_semantic_decision()` 将其转换为 `SemanticDecision`，供 `DecisionCompiler` 使用。原 `SemanticDecision` 同时承担 V2 输出协议和内部统一模型两种职责，两套提案字段基本重复。
  - **待评估方案**：若仍需兼容 V2，明确区分外部协议与内部模型的命名及转换边界；若已无兼容需求，评估将内部流程统一到 V3，再移除旧模型和转换代码。不要仅因已有 V3 就直接删除旧类。
  - **检查范围**：模型输出协议配置、provider 返回类型、目标校验、编译器、工作流恢复与持久化兼容，以及相关测试。
  - **验收标准**：明确支持的协议及迁移方案；更新类型和文档；验证指定订单、最新订单、指定工单、无目标请求及非法目标结构，并确认权限校验、用户确认和恢复流程不受影响。
  - **代码入口**：[schemas.py](app/agent/schemas.py)、[provider.py](app/agent/llm/provider.py)、[decision_compiler.py](app/agent/decision_compiler.py)。

## 流程图与节点组织

- [ ] **P2：拆分 graph.py 中混合的职责，明确流程组装、节点实现和监控边界**（记录于 2026-10-07）
  - **现状**：`graph.py` 同时包含 `build_graph()`、`_after_*()` 路由函数、`_instrument_node()` / `_instrument_tool_node()` 监控包装，以及 `_load_context()` / `_inspect_risk()` 节点实现，增加了阅读和维护成本。
  - **待评估方案**：保留流程组装和简短路由函数；将实际处理节点（如 `_load_context()`）移入 `nodes/`，将监控包装移入独立模块。核查当前仅返回 `{}` 的 `_inspect_risk()` 是否需要保留其节点及观测语义，再决定处理方式，避免仅按名称增加重复的策略检查。
  - **空节点简化补充（2026-10-15）**：当前 `_inspect_risk()` 仅返回 `{}`，没有额外风险判断。评估移除该函数及 `inspect_risk` 节点注册，将 `evaluate_policy → inspect_risk → _after_policy` 简化为在 `evaluate_policy` 后直接通过 `_after_policy` 的条件边分流，保留 `execute_tool`、`create_pending_action`、`execute_human_escalation` 和 `respond` 四个出口。
  - **简化配套与验收**：移除原有普通边及中间节点条件边，同步调整 [UI 追踪映射](app/ui/trace.py)、方法注释及相关流程和调试文档；验证允许、需确认、需人工、拒绝及评估异常分支，确认策略决定、状态合并与错误分流不变。明确监控和执行轨迹会减少一个节点；此处只记录待办，尚未实施代码调整。
  - **检查范围**：节点注册名、条件边、依赖注入、导入关系、状态更新、监控事件，以及确认和工作流恢复路径。
  - **验收标准**：职责和文件位置清晰；保持业务行为、权限与策略校验、确认重验、异常处理和事务边界不变；验证查询、写操作确认、拒绝、知识检索、记忆及工作流恢复分支，并确认监控与审计信息完整。
  - **代码入口**：[graph.py](app/agent/graph.py)、[nodes/](app/agent/nodes/)。

## 模型接入与本地化

- [ ] **P2：支持 DeepSeek / Qwen 接入，并明确本地化范围**（记录于 2026-10-07）
  - **现状**：`OpenAICompatibleProvider` 已支持配置模型、地址和密钥，但 `runtime.py` 的 `LIVE_PROPOSAL` 分支只接受 OpenAI 官方 API 地址，不满足条件时回退到确定性 V3 provider；部分服务展示标签仍写死为 `OpenAI`。非实时分支沿用已有 provider，模式标签与真实调用来源也需核对。
  - **待评估方案**：先明确使用 DeepSeek 云端 API、Qwen 云端 API，还是本地部署 Qwen；本地部署需结合可用硬件选择模型与推理服务。调整 `LLM_PROVIDER`、`LLM_BASE_URL`、`LLM_MODEL`、`LLM_API_KEY` 及结构化输出配置，按目标服务能力修改实时模式选择与回退逻辑，并修正 provider 展示名称。
  - **检查范围**：目标服务对 `SemanticDecisionV3`、JSON Schema / function calling 的支持，嵌套目标字段解析、超时与异常处理，以及实际模式和调用统计。若要求整个 RAG 本地化，还需独立核对 Embedding、重排序及其他外部依赖；仅替换对话模型不代表全链路本地化。
  - **验收标准**：确认调用的是目标模型而非确定性回退；验证结构化提案、查询、写操作确认与拒绝、政策检索回答及失败回退。不能仅凭聊天成功或 HTTP 200 判定业务链路通过；全本地方案还需验证运行时依赖符合离线要求。权限、确认、重验和幂等边界保持不变，保留原配置的恢复方式。
  - **代码入口**：[config.py](app/core/config.py)、[provider.py](app/agent/llm/provider.py)、[runtime.py](app/agent/runtime.py)、[.env.example](.env.example)。

- [ ] **P2：将测试用 Embedding 替换为真正的本地中文语义模型**（记录于 2026-10-15）
  - **现状**：当前 `.env` 使用 `EMBEDDING_PROVIDER=deterministic` 和 32 维哈希向量；该实现仅提取英文和数字，纯中文问题会生成全零向量。虽然模型名写着 `text-embedding-3-small`，确定性分支并未加载它；Qdrant 存储后端与 Embedding 模型独立配置。
  - **模型与依赖**：复用现有 `HuggingFaceEmbeddingProvider`，先以 `BAAI/bge-small-zh-v1.5`（512 维）验证本地中文向量化；将 `langchain-huggingface`、`sentence-transformers` 纳入项目依赖和锁文件，确认 CPU/GPU 运行方式、模型下载缓存与离线加载方式。
  - **配置调整**：设置 `EMBEDDING_PROVIDER=huggingface`、`EMBEDDING_MODEL=BAAI/bge-small-zh-v1.5`、`EMBEDDING_DIMENSION=512`，保留 `RAG_BACKEND=qdrant`；同步配置示例及部署说明。先关闭当前英文词面重排器（`RERANKER_ENABLED=false`），再单独接入中文重排；按模型输入长度调整切片大小，并评估查询指令和向量归一化配置。
  - **索引迁移**：确认 Qdrant 可用后运行 `python -m scripts.rag_ingest build`，用同一模型重新计算全部文档向量并启用新知识快照，再重启应用；不能混用旧的 32 维哈希向量与新的语义向量。保留旧快照及原配置，回退时同步恢复匹配的模型配置和索引。
  - **中文检索配套**：准备中文知识文档和问题样例，适配稀疏检索的中文分词及重排策略；仅替换 Embedding 不等于完成整个中文 RAG 改造。
  - **验收标准**：中文问题输出有限、非零且维度正确的向量；文档与查询使用相同模型，Qdrant 就绪检查通过；用“退款什么时候到账”“钱什么时候退回来”等不同表述验证相关证据命中、引用来源及回答正确性，并验证无关问题、重启和回退。不能仅凭输出 `512 True` 判定检索效果合格。
  - **代码入口**：[providers.py](app/rag/embeddings/providers.py)、[config.py](app/core/config.py)、[service.py](app/rag/retrieval/service.py)、[rag_ingest.py](scripts/rag_ingest.py)、[lexical.py](app/rag/retrieval/lexical.py)、[deterministic.py](app/rag/rerankers/deterministic.py)、[pyproject.toml](pyproject.toml)。
