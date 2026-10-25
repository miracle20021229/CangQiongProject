"""Agent 运行入口：准备依赖与身份，运行流程图，再把状态转换成接口响应。

调用链：API chat → AgentRuntime.run → build_graph / graph.invoke → AgentResponse。
本模块使用同步调用；节点负责具体处理，工具及仓储负责各自的业务写入与持久化。
"""

# 标准库：日志、类型提示与本轮运行标识。
import logging
from typing import cast
from uuid import uuid4
# 基础设施：会话检查点、链路追踪与数据库会话。
from langgraph.checkpoint.base import BaseCheckpointSaver
from opentelemetry import trace
from sqlalchemy.orm import Session
# Agent 核心：错误分类、流程图、模型提案协议与响应结构。
from app.agent.errors import RuntimeFailureSource, classify_runtime_error
from app.agent.graph import build_graph
from app.agent.llm.base import DecisionProposalProvider
from app.agent.llm.integration import (
    DeterministicIntegrationDecisionProvider,
    DeterministicSemanticDecisionProvider,
    DeterministicSemanticDecisionV3Provider,
)
from app.agent.llm.provider import OpenAICompatibleProvider
from app.agent.schemas import (
    AgentExecutionMode,
    AgentProposal,
    AgentRequestType,
    AgentResponse,
    AgentToolCall,
    Intent,
    ProposalValidationStatus,
    ProviderRunMetadata,
)
from app.agent.state import AgentState
# 应用依赖：身份配置、记忆、监控、持久化、策略、RAG、故障控制和展示记录。
from app.auth.models import ActorType, Principal
from app.core.config import DecisionContractVersion, LLMProvider, Settings, get_settings
from app.core.context import ExecutionContext
from app.memory.service import MemoryService
from app.observability.metrics import get_metrics, record_agent_run_summary
from app.observability.tracing import span
from app.persistence.checkpoint import (
    CheckpointBackend,
    MemoryCheckpointProvider,
    checkpoint_thread_id,
    checkpoint_thread_id_hash,
)
from app.policies.confirmation import Clock, SystemClock
from app.policies.engine import PolicyEngine
from app.policies.repository import (
    PolicyAuditRepository,
    build_policy_audit_repository,
)
from app.rag.answer_generator import GroundedAnswerGenerator
from app.rag.interfaces import (
    KnowledgeRetriever,
    ManagedKnowledgeRetriever,
    ReadyKnowledgeRetriever,
)
from app.rag.retrieval.service import build_default_knowledge_service
from app.resilience.config import ResilienceConfig
from app.resilience.control import ReliabilityController
from app.ui.projection import get_projection_store
from app.ui.repository import AgentRunProjectionRepository, build_agent_run_projection_repository

logger = logging.getLogger(__name__)  # 本模块日志记录器，记录展示视图等非业务主流程故障。


class AgentRuntime:
    """协调一轮 Agent 请求，并复用模型、策略、记忆和检查点等依赖。

    类似 Java 的应用服务：负责组织调用，不代替节点做业务判断或授予执行权限。
    """

    def __init__(
        self,
        provider: DecisionProposalProvider | None = None,
        checkpointer: BaseCheckpointSaver[str] | None = None,
        checkpoint_backend: CheckpointBackend = CheckpointBackend.MEMORY,
        policy_engine: PolicyEngine | None = None,
        clock: Clock | None = None,
        audit_log: PolicyAuditRepository | None = None,
        confirmation_ttl_seconds: int | None = None,
        knowledge_retriever: KnowledgeRetriever | None = None,
        grounded_generator: GroundedAnswerGenerator | None = None,
        memory_service: MemoryService | None = None,
        resilience_config: ResilienceConfig | None = None,
        projection_repository: AgentRunProjectionRepository | None = None,
        decision_contract_version: DecisionContractVersion | None = None,
        reliability_controller: ReliabilityController | None = None,
    ) -> None:
        """初始化运行依赖：优先使用传入实例，未提供的依赖按配置创建。

        provider / decision_contract_version：提案服务及输出协议。
        checkpointer / checkpoint_backend：状态保存器及用于观测的后端标识。
        policy_engine / clock / confirmation_ttl_seconds：策略与确认有效期。
        audit_log / projection_repository：可选的审计与展示记录仓储覆盖项。
        knowledge_retriever / grounded_generator：知识检索及基于证据的回答生成。
        memory_service：用户记忆服务；resilience_config / reliability_controller：故障控制。
        """
        # 1. 确定配置、协议和 provider，后续节点必须按同一协议解析提案。
        settings = get_settings()
        self.settings = settings  # 应用配置，供本轮 provider 和仓储构建使用。
        # 当前运行器默认使用的提案协议，单轮实时模式可以另选 V3。
        self.decision_contract_version = _resolve_decision_contract(
            settings,
            provider=provider,
            explicit_contract=decision_contract_version,
        )
        # 默认提案服务；可以是确定性实现，也可以是真实模型适配器。
        self.provider = provider or _build_decision_provider(settings, self.decision_contract_version)
        # 2. 保存跨轮状态与策略依赖；后端标识本身不会切换保存器实现。
        self.checkpointer = checkpointer or MemoryCheckpointProvider().checkpointer  # 默认内存检查点。
        self.checkpoint_backend = checkpoint_backend  # 用于追踪和指标的检查点后端标识。
        self.policy_engine = policy_engine or PolicyEngine()  # 确定性业务策略引擎。
        self.clock = clock or SystemClock()  # 可替换时钟，用于确认过期检查及审计时间。
        self.audit_repository_override = audit_log  # 指定后覆盖每轮按配置创建的审计仓储。
        # Exposed only when an explicit bounded test/local adapter is injected.
        self.audit_log = audit_log  # 保留显式注入的测试或本地审计适配器访问入口。
        # 待确认动作的有效期；当前逻辑在传入值为假值时采用配置值。
        self.confirmation_ttl_seconds = (
            confirmation_ttl_seconds or settings.confirmation_ttl_seconds
        )
        # 3. RAG 与用户记忆：与会话检查点不同，记忆服务处理用户长期信息。
        self.knowledge_retriever = knowledge_retriever or build_default_knowledge_service(settings)  # 知识检索服务。
        # 使用检索证据组织回答，限制最终上下文条数。
        self.grounded_generator = grounded_generator or GroundedAnswerGenerator(
            settings.rag_final_context_count
        )
        self.memory_service = memory_service or MemoryService(  # 记忆读取、写入与生命周期管理。
            enabled=settings.memory_enabled,  # 是否启用用户记忆。
            max_context_items=settings.memory_max_context_items,  # 每轮提供的最大记忆条数。
            default_ttl_days=settings.memory_default_ttl_days,  # 默认记忆有效天数。
            support_context_ttl_days=settings.memory_support_context_ttl_days,  # 客服上下文有效天数。
        )
        # 4. 故障控制及展示仓储；具体数据库仓储可在 run 中绑定当轮 session。
        self.resilience_config = resilience_config or ResilienceConfig.from_settings(settings)  # 重试等故障控制配置。
        # 统一的请求限流及依赖可靠性控制器，由多个节点共享。
        self.reliability_controller = reliability_controller or ReliabilityController(self.resilience_config)
        self.projection_repository_override = projection_repository  # 可选的运行展示记录仓储覆盖项。

    def close(self) -> None:
        """释放实现了资源管理接口的知识检索器；不在这里关闭调用方的数据库会话。"""
        # 普通检索器不一定持有需要释放的客户端或连接。
        if isinstance(self.knowledge_retriever, ManagedKnowledgeRetriever):
            self.knowledge_retriever.close()

    def is_ready(self) -> bool:
        """检查知识检索边界是否就绪；不代表数据库、模型和全部业务已验证可用。"""
        if isinstance(self.knowledge_retriever, ReadyKnowledgeRetriever):
            return self.knowledge_retriever.is_ready()
        return True  # 未实现就绪检查接口的检索器按兼容行为视为就绪。

    def readiness_category(self) -> str:
        """Return the retrieval boundary's safe readiness category after a check.

        返回检索器的安全就绪分类；优先调用方法，否则读取最近一次记录或默认值。
        """
        category = getattr(self.knowledge_retriever, "readiness_category", None)
        if callable(category):
            return str(category())
        return str(getattr(self.knowledge_retriever, "last_readiness_category", "ready"))

    def run(
        self,
        *,
        message: str,
        session: Session,
        context: ExecutionContext | None = None,
        conversation_id: str | None = None,
        customer_id: int | None = None,
        execution_mode: AgentExecutionMode = AgentExecutionMode.RECORDED_REPLAY,
    ) -> AgentResponse:
        """同步执行一轮对话，将最终 AgentState 转换为 AgentResponse。

        message 是本轮消息，session 由调用方提供，context 是可信执行身份。
        conversation_id / customer_id 仅用于没有 context 时的旧调用兼容入口。
        execution_mode 表示请求模式；配置不满足实时要求时，响应会注明实际回退模式。
        图中的业务错误可作为响应返回；未处理异常记录后继续抛给调用方。
        """
        # 1. 确定身份并执行入口限流；可信 context 优先于旧参数。
        execution_context = context or _legacy_execution_context(conversation_id, customer_id)
        self.reliability_controller.enforce_request_limits(execution_context)
        conversation_id = execution_context.conversation_id
        customer_id = execution_context.effective_customer_id
        thread_id = checkpoint_thread_id(execution_context)  # 同一身份与会话范围内的检查点键。
        thread_id_hash = checkpoint_thread_id_hash(execution_context)  # 供观测使用的哈希标识。
        # 2. 返回本轮 provider、协议、实际模式和可选回退说明，不修改运行器默认依赖。
        provider, decision_contract_version, actual_mode, fallback_message = (
            self._provider_for_execution(execution_mode)
        )
        # 检查点线程用于标识会话或工作流。每次调用 run()，
        # 包括确认请求和重放请求，都会发起一次新的图执行，
        # 因此每次调用都会获得独立的运行标识。
        # thread_id 跨轮复用；每轮调用（包括确认或回放）都有独立 agent_run_id。
        agent_run_id = str(uuid4())
        metric = get_metrics()  # 获取 Agent 计数器和耗时指标。
        labels = {"status": "ok"}  # 默认成功；出现业务错误或未处理异常时改为 error。
        metric.agent_runs_total.add(1)
        # 3. 创建本轮根追踪；身份与会话字段来自服务端执行上下文。
        with span(
            "agent.run",
            attributes={
                "agent.run_id": agent_run_id,  # 本轮 Agent 运行 ID。
                "request.id": execution_context.request_id,  # 外层请求 ID。
                "conversation.id": conversation_id,  # 多轮会话 ID。
                "actor.id": execution_context.principal.actor_id,  # 实际调用者 ID。
                "actor.type": execution_context.principal.actor_type.value,  # 调用者类型。
                "actor.roles": execution_context.principal.roles,  # 已认证角色。
                "customer.id": customer_id,  # 本轮操作所服务的客户。
                "checkpoint.backend": self.checkpoint_backend.value,  # 状态保存后端标识。
                "checkpoint.thread_id": thread_id_hash,  # 观测中记录哈希，不使用完整键。
            },
        ) as root_span:
            import time

            started = time.perf_counter()  # 从此处计时，不包含前面的身份准备和限流。
            try:
                # 4. 关联追踪，选择展示与审计仓储；未显式注入时绑定本轮数据库会话。
                current_span = trace.get_current_span().get_span_context()
                trace_id = f"{current_span.trace_id:032x}" if current_span.is_valid else None
                projection_store = get_projection_store()
                projection_repository = self.projection_repository_override or (
                    build_agent_run_projection_repository(self.settings, session)
                )
                audit_repository = self.audit_repository_override or build_policy_audit_repository(
                    self.settings, session
                )
                # 5. 注入依赖并构建图；build_graph 只组装流程，不执行本轮需求。
                graph = build_graph(
                    provider,
                    session,
                    self.checkpointer,
                    policy_engine=self.policy_engine,
                    clock=self.clock,
                    ttl_seconds=self.confirmation_ttl_seconds,
                    audit_repository=audit_repository,
                    knowledge_retriever=self.knowledge_retriever,
                    grounded_generator=self.grounded_generator,
                    memory_service=self.memory_service,
                    resilience_config=self.resilience_config,
                    reliability_controller=self.reliability_controller,
                    decision_contract_version=decision_contract_version,
                )
                # 6. 捕获运行展示信息，在 capture 范围内同步执行图并构造结果。
                with projection_store.capture(
                    run_id=agent_run_id,
                    context=execution_context,
                    trace_id=trace_id,
                ) as projection:
                    state = cast(
                        AgentState,
                        graph.invoke(  # 同步执行到本轮 END；等待用户确认时由图先回复结束。
                            # 本轮输入状态：历史状态由 checkpointer 按 thread_id 恢复。
                            {
                                "execution_context": execution_context,  # 可信身份与租户范围。
                                "conversation_id": conversation_id,  # 本轮所属会话。
                                "agent_run_id": agent_run_id,  # 本轮独立运行标识。
                                "messages": [{"role": "user", "content": message}],  # 追加用户消息。
                            },
                            # 图配置与业务状态分开：configurable 用于检查点，metadata 用于观测。
                            config={
                                "configurable": {
                                    "thread_id": thread_id,  # 恢复或保存同一会话的流程状态。
                                },
                                "metadata": {
                                    "checkpoint_backend": self.checkpoint_backend.value,  # 后端标识。
                                    "thread_id_hash": thread_id_hash,  # 会话状态键的哈希。
                                    "actor_id": execution_context.principal.actor_id,  # 实际调用者。
                                    "actor_type": execution_context.principal.actor_type.value,  # 调用者类型。
                                    "effective_customer_id": customer_id,  # 实际服务客户。
                                },
                            },
                        ),
                    )
                    # 7. 从最终状态组装接口响应，工具结果与提案摘要在这里汇总。
                    response = _response_from_state(
                        state,
                        execution_mode=actual_mode,
                        fallback_message=fallback_message,
                    )
                    # 动作 ID 关联一次业务动作；必要时从待确认记录补取，不与运行 ID 混用。
                    action_id = state.get("action_id")
                    if not isinstance(action_id, str):
                        pending_action = state.get("pending_action")
                        action_id = pending_action.action_id if pending_action is not None else None
                    if action_id is not None:
                        root_span.set_attribute("agent.action_id", action_id)
                    # 8. 构造并保存展示视图；展示持久化失败不推翻已完成的业务结果。
                    try:
                        view = projection_store.build_view(
                            projection,
                            response=response,
                            state=state,
                            policy_events=audit_repository.list_for_agent_run(
                                agent_run_id, tenant_id=execution_context.tenant_id
                            ),
                            duration_ms=(time.perf_counter() - started) * 1000,
                        )
                        projection.view = view
                        projection_repository.upsert(view)
                    except Exception as error:
                        # The projection is observational. Never turn a committed business
                        # result into a replayable failure because its read model is unavailable.
                        # 这里只记录展示故障，不向外抛出，避免调用方把已提交业务当失败重试。
                        logger.warning(
                            "Agent run projection persistence failed.",
                            extra={
                                "projection_error_type": type(error).__name__,  # 异常类名，不输出原始载荷。
                                "error_category": classify_runtime_error(
                                    error, source=RuntimeFailureSource.PROJECTION
                                ).category.value,
                                "agent_run_id": agent_run_id,  # 关联发生展示故障的这一轮。
                            },
                        )
                # 9. 汇总业务响应的成功/错误状态，再返回给 API 调用方。
                root_span.set_attribute("agent.intent", response.intent.value)
                root_span.set_attribute("agent.request_type", response.request_type.value)
                if response.error_category is not None:
                    labels["status"] = "error"
                    labels["error_category"] = response.error_category.value
                    root_span.set_attribute("agent.status", "error")
                    root_span.set_attribute("error.category", response.error_category.value)
                else:
                    root_span.set_attribute("agent.status", "ok")
                return response
            except Exception as error:
                # 10. 未被节点或展示降级处理的异常：记录安全分类后原样抛出，不伪造业务成功。
                classification = classify_runtime_error(error, source=RuntimeFailureSource.RUNTIME)
                labels["status"] = "error"
                root_span.set_attribute("agent.status", "error")
                root_span.set_attribute("error.category", classification.category.value)
                root_span.add_event(
                    "agent.persistence_or_execution_error",
                    attributes={
                        "checkpoint.backend": self.checkpoint_backend.value,  # 便于定位状态保存问题。
                        "checkpoint.thread_id": thread_id_hash,  # 脱离原始会话键的关联标识。
                        "error.type": type(error).__name__,  # 异常类型。
                        "error.category": classification.category.value,  # 统一错误分类。
                    },
                )
                raise
            finally:
                # 11. 无论返回或抛出，都记录此 try 范围内的耗时和错误指标。
                duration_seconds = time.perf_counter() - started
                metric.agent_run_duration_seconds.record(duration_seconds, labels)
                record_agent_run_summary(
                    duration_seconds=duration_seconds,
                    error=labels["status"] == "error",
                )
                if labels["status"] == "error":
                    metric.agent_errors_total.add(1, labels)

    def _provider_for_execution(
        self, requested_mode: AgentExecutionMode
    ) -> tuple[
        DecisionProposalProvider,
        DecisionContractVersion,
        AgentExecutionMode,
        str | None,
    ]:
        """选择本轮提案服务，返回 (provider, 协议版本, 实际模式, 回退说明)。"""
        # 非实时模式沿用已有 provider；RECORDED_REPLAY 标签不保证 provider 一定不联网。
        if requested_mode != AgentExecutionMode.LIVE_PROPOSAL:
            return self.provider, self.decision_contract_version, requested_mode, None
        # 实时入口当前只接受配置为 OpenAI 兼容、带密钥且地址为官方 API 的服务。
        if (
            self.settings.llm_provider != LLMProvider.OPENAI_COMPATIBLE
            or not self.settings.llm_api_key
            or not _is_openai_api_base_url(self.settings.llm_base_url)
        ):
            # 配置不满足要求时使用确定性 V3 提案服务；这是配置回退，不是网络探活结果。
            return (
                DeterministicSemanticDecisionV3Provider(),
                "semantic_decision_v3",
                AgentExecutionMode.RECORDED_REPLAY,
                "Live model unavailable. Showing bounded evidence replay.",
            )
        # 复制配置并固定 V3，不改变运行器持有的原配置。
        live_settings = self.settings.model_copy(
            update={
                "agent_decision_contract_version": "semantic_decision_v3",  # 实时提案协议。
                "llm_structured_output_mode": self.settings.llm_structured_output_mode,  # 沿用结构化输出方式。
            }
        )
        return (
            OpenAICompatibleProvider(live_settings),
            "semantic_decision_v3",
            AgentExecutionMode.LIVE_PROPOSAL,
            None,
        )


def _legacy_execution_context(
    conversation_id: str | None, customer_id: int | None
) -> ExecutionContext:
    """为旧的直接调用参数构造兼容上下文；此函数不执行登录认证。

    仅供可信调用方兼容旧接口，API 正常路径应传入经过认证的 context。
    """
    # 缺少会话或客户信息就拒绝构造，避免无范围地进入业务流程。
    if conversation_id is None or customer_id is None:
        raise ValueError("Execution context is required.")
    return ExecutionContext(
        request_id=str(uuid4()),  # 兼容调用也有独立请求 ID。
        conversation_id=conversation_id,  # 旧入口传入的会话标识。
        principal=Principal(
            actor_id="legacy-runtime",  # 固定的兼容调用者，不是登录用户 ID。
            actor_type=ActorType.SUPPORT_OPERATOR,  # 兼容路径使用客服操作员身份类型。
            roles=["support_operator"],  # 固定兼容角色。
        ),
        tenant_id="default",  # 旧接口没有租户字段，固定默认租户。
        effective_customer_id=customer_id,  # 旧入口指定的目标客户。
    )


def _build_decision_provider(
    settings: Settings, contract_version: DecisionContractVersion | None = None
) -> DecisionProposalProvider:
    """按配置构建提案服务：确定性模式按协议选择实现，否则创建模型 API 适配器。"""
    selected_contract = contract_version or settings.agent_decision_contract_version
    selected_settings = settings.model_copy(
        update={"agent_decision_contract_version": selected_contract}  # 只在副本中覆盖协议。
    )
    # 确定性 provider 按固定规则生成提案，方便集成验证；不依赖实时 LLM。
    if selected_settings.llm_provider == LLMProvider.DETERMINISTIC_INTEGRATION:
        if selected_contract == "semantic_decision_v2":
            return DeterministicSemanticDecisionProvider()
        if selected_contract == "semantic_decision_v3":
            return DeterministicSemanticDecisionV3Provider()
        return DeterministicIntegrationDecisionProvider()  # 旧版直接工具协议的兼容实现。
    return OpenAICompatibleProvider(selected_settings)


def _resolve_decision_contract(
    settings: Settings,
    *,
    provider: DecisionProposalProvider | None,
    explicit_contract: DecisionContractVersion | None,
) -> DecisionContractVersion:
    """确定协议：显式参数优先；否则使用配置或注入 provider 自己声明的协议。"""
    # 调用方显式指定协议时优先采用，不在这里验证 provider 是否实现该协议。
    if explicit_contract is not None:
        return explicit_contract
    if provider is None:
        return settings.agent_decision_contract_version

    # 注入的 provider 可以声明自己返回哪一种提案结构，只接受已知协议名称。
    declared_contract = getattr(provider, "decision_contract_version", None)
    if declared_contract in {
        "direct_tool_v1",
        "semantic_decision_v2",
        "semantic_decision_v3",
    }:
        return cast(DecisionContractVersion, declared_contract)

    # Injecting a provider is an explicit compatibility/test boundary. Providers
    # without a declared semantic contract retain the legacy direct contract;
    # the normal settings-built runtime never enters this branch.
    # 未声明协议的注入实例按旧 direct_tool_v1 兼容；正常按配置创建的实例不走此分支。
    return "direct_tool_v1"


def _response_from_state(
    state: AgentState,
    *,
    execution_mode: AgentExecutionMode,
    fallback_message: str | None,
) -> AgentResponse:
    """将图的最终状态投影成接口响应；不执行工具，也不再次进行授权判断。"""
    provider_metadata = state.get("provider_metadata")
    tool_call = None
    # 只有存在工具且记录了执行成功/失败，才生成调用摘要；仅选中或待确认不算执行。
    if state.get("selected_tool") and state.get("tool_execution_status") in {
        "executed",
        "failed",
    }:
        tool_call = AgentToolCall(
            name=state["selected_tool"],  # 实际记录执行状态的工具。
            status=state["tool_execution_status"] or "failed",  # 工具执行状态。
            result=state.get("tool_result"),  # 工具返回的业务数据。
        )
    return AgentResponse(
        # 会话标识与本轮需求。
        conversation_id=state["conversation_id"],  # 关联多轮对话。
        agent_run_id=state["agent_run_id"],  # 标识这一轮运行。
        message=state["final_response"],  # respond 节点组织的用户回复。
        intent=state.get("intent", Intent.UNKNOWN),  # 识别出的业务目的。
        request_type=state.get("request_type", AgentRequestType.UNCLEAR),  # 最终处理类别。
        # 业务执行、确认、证据与错误信息。
        tool_call=tool_call,  # 工具执行结果摘要，未执行时为空。
        pending_action=state.get("pending_action"),  # 等待用户确认的具体操作。
        decision_reason=state.get("decision_reason"),  # 本轮决策原因。
        error_category=state.get("error_category"),  # 对外统一错误大类。
        citations=state.get("citations", []),  # 知识回答的引用证据。
        failure_category=state.get("failure_category"),  # 更细的故障原因。
        degraded_components=state.get("degraded_components", []),  # 已降级的组件。
        recovery_action=state.get("recovery_action"),  # 恢复或降级处理方式。
        security_signal=state.get("security_signal"),  # 安全边界发现的信号。
        write_outcome_unknown=state.get("write_outcome_unknown", False),  # 写入结果是否仍无法确认。
        # 模式与展示信息；provider 顶层标签按模式生成，详细调用数据见 provider_metadata。
        execution_mode=execution_mode,  # 实际采用的模式，可能与请求模式不同。
        provider="OpenAI"
        if execution_mode == AgentExecutionMode.LIVE_PROPOSAL
        else "recorded_evidence",
        model=(provider_metadata.model if provider_metadata is not None else None),  # 实际记录的模型名。
        fallback_message=fallback_message,  # 实时模式配置回退说明。
        proposal=_proposal_from_state(state),  # 从状态整理的提案摘要，不是执行授权。
        provider_metadata=_provider_run_metadata(provider_metadata),  # 耗时、token 和费用等统计。
    )


def _is_openai_api_base_url(value: str) -> bool:
    """忽略大小写和末尾斜杠，检查是否为此实时入口允许的官方 API 地址；不发网络请求。"""
    return value.rstrip("/").casefold() == "https://api.openai.com/v1"


def _provider_run_metadata(value: object) -> ProviderRunMetadata | None:
    """保留已建模的调用统计，或校验带 provider 字段的字典；不具备基本结构则返回 None。"""
    # 对合法模型实例不重复转换。
    if isinstance(value, ProviderRunMetadata):
        return value
    # 字典必须包含字符串 provider；其余字段由 Pydantic 校验，失败时仍会抛异常。
    if not isinstance(value, dict):
        return None
    provider = value.get("provider")
    if not isinstance(provider, str):
        return None
    return ProviderRunMetadata.model_validate(value)


def _proposal_from_state(state: AgentState) -> AgentProposal | None:
    """生成供展示的受限提案摘要，只收集必要目标字段、引用和编译校验状态。

    不复制完整工具参数、原始提示词或推理内容；PASSED 不等于业务已执行成功。
    """
    intent = state.get("intent")
    semantic = state.get("semantic_decision")
    # 1. 没有意图和语义提案时，不凭空构造展示内容。
    if intent is None and semantic is None:
        return None
    extracted: dict[str, str | int | bool] = {}  # 只保存允许展示的目标类型与业务 ID。
    # 2. 从语义目标提取字段；不暴露整个 SemanticDecision 或工具参数字典。
    target = getattr(semantic, "target", None)
    if target is not None:
        target_type = getattr(target, "type", None)
        if isinstance(target_type, str):
            extracted["target_type"] = target_type
        for field in ("order_id", "ticket_id"):
            value = getattr(target, field, None)
            if isinstance(value, int):
                extracted[field] = value
    # 3. 根据编译结果生成标签：拒绝/需澄清为 REJECTED，无结果为 PENDING。
    compile_result = state.get("compile_result")
    compile_status = getattr(getattr(compile_result, "status", None), "value", None)
    if compile_status in {"compile_rejected", "clarification_required"}:
        validation = ProposalValidationStatus.REJECTED
    elif compile_status is not None:
        validation = ProposalValidationStatus.PASSED
    else:
        validation = ProposalValidationStatus.PENDING
    # 4. 只保留最多十个有效引用标识，不复制知识正文。
    citations = state.get("citations", [])
    references = [
        str(item.get("citation_id"))
        for item in citations
        if isinstance(item, dict) and item.get("citation_id")
    ][:10]
    # 5. 展示建议工具沿用程序选中的工具名，并非在这里调用 LLM 再提一次建议。
    action = state.get("selected_tool")
    suggested_action = action if isinstance(action, str) else None
    return AgentProposal(
        intent=(intent.value if isinstance(intent, Intent) else str(intent or "unknown")),  # 意图字符串。
        suggested_action=suggested_action,  # 当前选中的建议工具，可能尚未授权或执行。
        extracted_fields=extracted,  # 目标类型、订单或工单 ID。
        evidence_references=references,  # 相关知识引用标识。
        validation=validation,  # 编译阶段校验摘要，不代表完整执行状态。
    )
