from collections.abc import Callable
from app.agent.schemas import AgentErrorCategory
from app.agent.state import AgentState
from app.rag.answer_generator import GroundedAnswerGenerator, normalize_knowledge_query
from app.rag.interfaces import KnowledgeRetriever
from app.resilience.config import ResilienceConfig
from app.resilience.control import ReliabilityController
from app.resilience.errors import ResilienceError, RetryExhaustedError
from app.resilience.fallbacks import degraded_message
from app.resilience.retry import run_with_retry


def make_retrieve_node(
    retriever: KnowledgeRetriever,
    generator: GroundedAnswerGenerator,
    resilience_config: ResilienceConfig | None = None,
    reliability_controller: ReliabilityController | None = None,
) -> Callable[[AgentState], AgentState]:
    def retrieve_knowledge(state: AgentState) -> AgentState:
        """同步检索知识，组织证据回答或降级说明，并返回 AgentState 的增量更新。

        使用外层注入的检索器、证据回答生成器和可靠性依赖；节点完成后进入 respond。
        检索查询可做语义归一化，回答依据仍需结合用户原问题校验。
        """
        # 1.取得最后一条用户消息，保留原问题供后面的证据相关性及回答校验使用。
        customer_query = _latest_user_message(state)
        # 1.1.将支持的问题表达归一化为知识库检索词，例如映射到退款到账时间主题。
        query = normalize_knowledge_query(customer_query)
        # 1.2.若原问题未被改写，优先使用状态中的 knowledge_query；为空时仍用原问题。
        if query == customer_query:
            query = normalize_knowledge_query(state.get("knowledge_query") or customer_query)
        # 2.读取检索超时配置；未注入配置时使用默认值。
        timeout_seconds = (resilience_config or ResilienceConfig()).retrieval_timeout_seconds
        try:
            # 2.1.通过可靠性封装同步检索，按配置和故障类型限制重试，并应用超时及依赖保护。
            # lambda 延后调用 retriever.retrieve(query)，让封装函数负责发起每次检索。
            retrieval = run_with_retry(
                lambda: retriever.retrieve(query),
                dependency="retrieval",
                config=resilience_config,
                controller=reliability_controller,
                service_identity=f"retrieval:{type(retriever).__name__}",
                timeout_seconds=timeout_seconds,
            )
        # 3.重试耗尽或发生可靠性异常时降级；这里只捕获列出的两类异常。
        except (RetryExhaustedError, ResilienceError) as error:
            # 3.1.按是否已有业务工具结果区分降级方式，并生成对应的故障说明。
            knowledge_only = state.get("tool_result") is None
            fallback = degraded_message(error.category, knowledge_only=knowledge_only)
            # 3.2.没有业务结果可保留时，返回检索错误及无可用证据标记，交给 respond 回复。
            if knowledge_only:
                return {
                    "last_error": fallback,
                    "error_category": AgentErrorCategory.RETRIEVAL_ERROR,
                    "failure_category": error.category.value,
                    "recovery_action": "degraded",
                    "degraded_components": ["retrieval"],
                    "answer_grounding": _unavailable_grounding(),
                }
            # 3.3.已有业务结果时保留该结果，准备“业务状态 + 检索不可用”的降级回答。
            # 这里只补充回复，不重新执行或撤销前面的业务工具。
            existing_tool_result = state.get("tool_result") or {}
            current_status = existing_tool_result.get("status")
            business_fallback = (
                f"The current business result is {current_status}. "
                if current_status is not None
                else "The current business result was retrieved. "
            )
            return {
                # 返回已知业务信息和降级说明，同时标明知识检索不可用。
                "knowledge_answer": business_fallback + fallback,
                "failure_category": error.category.value,
                "recovery_action": "degraded",
                "degraded_components": ["retrieval"],
                "answer_grounding": _unavailable_grounding(),
            }
        # 4.正常取得检索结果后，读取文档片段、降级组件和本次检索的运行信息。
        chunks = retrieval.chunks
        degraded = retrieval.degraded_components
        metadata = retrieval.metadata
        # 4.1.整理检索元数据供追踪和展示；这些字段描述检索过程，不是政策正文。
        retrieval_metadata = {
            # 检索后端、向量服务、重排开关，以及检索数量、耗时和回退状态。
            "backend": metadata.backend,
            "embedding_provider": metadata.embedding_provider,
            "reranker_enabled": metadata.reranker_enabled,
            "retrieval_count": metadata.retrieval_count,
            "latency_seconds": metadata.latency_seconds,
            "fallback_status": metadata.fallback_status,
            # 混合检索标记、候选融合策略，以及稠密向量检索和稀疏检索的候选数量。
            "hybrid": metadata.hybrid,
            "fusion_strategy": metadata.fusion_strategy,
            "dense_candidate_count": metadata.dense_candidate_count,
            "sparse_candidate_count": metadata.sparse_candidate_count,
        }
        # 5.把用户原问题和检索片段交给生成器，避免宽泛检索词使无关证据看起来相关。
        # Retrieval may use a normalized semantic query, but grounding must be
        # checked against the customer's observable question. This prevents a
        # broad classifier query from making unrelated evidence look relevant.
        # 5.1.当前生成器按证据摘录组织回答和引用，再校验依据；不额外调用聊天模型。
        grounded = generator.answer(customer_query, chunks)
        knowledge_answer = grounded.answer
        # 6.检查是否还需要结合前面查询到的业务结果，补充具体资源的实际状态。
        tool_result = state.get("tool_result")
        if tool_result is not None:
            status = tool_result.get("status")
            # 6.1.业务结果包含字符串状态时追加说明；不把政策文档当作当前订单状态。
            if isinstance(status, str):
                knowledge_answer += (
                    f" The business system reports the specific resource status as '{status}'."
                )
            # 6.2.只要已有业务结果，就声明具体业务状态以业务系统为准。
            knowledge_answer += (
                " Business-system state is authoritative for the specific customer request."
            )
        # 7.返回状态增量，由 LangGraph 合并后进入 respond；本方法不直接返回最终 API 响应。
        return {
            # 7.1.保存检索片段及运行信息；模型转为适合 JSON 的字典，便于状态传递。
            "retrieved_chunks": [chunk.model_dump(mode="json") for chunk in chunks],
            "retrieval_metadata": retrieval_metadata,
            # 7.2.保存回答文本及结构化引用，供 respond 和响应构建逻辑读取。
            "knowledge_answer": knowledge_answer,
            "citations": [citation.model_dump(mode="json") for citation in grounded.citations],
            # 7.3.保存回答依据校验：状态、来源及引用数、覆盖率、无依据主张数、置信度和是否通过。
            "answer_grounding": {
                "status": grounded.status.value,
                "sources_used": grounded.source_count,
                "citation_count": len(grounded.citations),
                "citation_coverage": grounded.validation.citation_coverage,
                "unsupported_claim_count": len(grounded.unsupported_claims),
                "confidence": grounded.confidence,
                "accepted": grounded.validation.accepted,
            },
            # 7.4.保留检索结果中的降级组件；存在降级时标记 degraded，否则恢复动作为 None。
            "degraded_components": list(degraded),
            "recovery_action": "degraded" if degraded else None,
        }

    return retrieve_knowledge


def _unavailable_grounding() -> dict[str, object]:
    return {
        "status": "retrieval_unavailable",
        "sources_used": 0,
        "citation_count": 0,
        "citation_coverage": 0.0,
        "unsupported_claim_count": 0,
        "confidence": 0.0,
        "accepted": False,
    }


def _latest_user_message(state: AgentState) -> str:
    for message in reversed(state.get("messages", [])):
        if message["role"] == "user":
            return message["content"]
    return ""
