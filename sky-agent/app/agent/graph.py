# 核心方法build_graph
"""组装客服 Agent 的 LangGraph 流程。

阅读顺序：build_graph 注册节点与边 → _after_* 决定分支 → nodes 执行业务。
AgentRuntime.run 调用编译后的图；节点返回状态增量，由图合并进 AgentState。
"""

from collections.abc import Callable
from typing import Any, cast
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.state import CompiledStateGraph
from sqlalchemy.orm import Session
from app.agent.decision_compiler import CompileStatus
from app.agent.llm.base import DecisionProposalProvider
from app.agent.nodes.check_pending import make_check_pending_node
from app.agent.nodes.compile_decision import make_compile_decision_node
from app.agent.nodes.confirmed import make_confirmed_execution_node
from app.agent.nodes.create_pending import make_create_pending_node
from app.agent.nodes.evaluate_policy import make_evaluate_policy_node
from app.agent.nodes.human import make_human_escalation_node
from app.agent.nodes.memory_action import make_memory_action_node
from app.agent.nodes.respond import respond
from app.agent.nodes.restore_pending import make_restore_pending_node
from app.agent.nodes.resume_workflow import make_resume_workflow_node
from app.agent.nodes.retrieve_knowledge import make_retrieve_node
from app.agent.nodes.retrieve_memory import make_retrieve_memory_node
from app.agent.nodes.revalidate import make_revalidate_node
from app.agent.nodes.select_tool import select_tool
from app.agent.nodes.understand_request import (
    make_security_boundary_node,
    make_understand_request_node,
)
from app.agent.nodes.validate_tool import validate_tool
from app.agent.nodes.workflow_lifecycle import (
    make_handle_workflow_interruption_node,
    make_restore_suspended_workflow_node,
)
from app.agent.schemas import AgentErrorCategory, AgentRequestType, Intent
from app.agent.state import AgentState
from app.memory.service import MemoryService
from app.observability.metrics import get_metrics
from app.observability.tracing import span
from app.policies.confirmation import Clock
from app.policies.engine import PolicyEngine
from app.policies.models import PolicyOutcome
from app.policies.repository import PolicyAuditRepository
from app.rag.answer_generator import GroundedAnswerGenerator
from app.rag.interfaces import KnowledgeRetriever
from app.resilience.config import ResilienceConfig
from app.resilience.control import ReliabilityController
from app.tools import registry
from app.ui.projection import record_node


# 条件边只返回下一节点名称，实际业务在对应节点中执行。
def _after_context(state: AgentState) -> str:
    """load_context 之后的路由：身份校验不通过就直接回复，通过则进入安全检查。

    load_context 发现缺少登录上下文、会话不属于当前租户/客户/操作者时会写入 error_category，
    此时不再往下走，直接由 respond 返回错误说明。
    """
    return "respond" if state.get("error_category") is not None else "security_boundary"


def _after_security_boundary(state: AgentState) -> str:
    """security_boundary 之后的路由：命中安全风险就拒答，否则继续加载用户记忆。

    security_boundary 识别到提示词注入、越权指令等风险时会写入 security_signal，
    这类请求不交给模型理解，直接由 respond 给出拒绝回复。
    """
    return "respond" if state.get("security_signal") is not None else "retrieve_memory"


def _after_pending(state: AgentState) -> str:
    """check_pending_action 之后的路由：先处理上一轮遗留的待办，再决定是否当作新请求理解。

    confirmation_status 由 check_pending_action 根据“是否有待确认操作”和“本轮用户回复”得出：
    - resume_suspended：用户要求恢复之前被挂起的流程 → restore_suspended_workflow
    - inspect_interruption：待确认期间用户说了别的事，需要模型判断是否换了话题 → understand_request
    - normal 且 workflow_active：上一轮在追问缺失信息（如订单号、退款原因）→ resume_workflow
    - normal：没有待办，按全新请求理解 → understand_request
    - confirmed：用户确认了待执行操作 → restore_pending_action 恢复当时保存的操作参数
    - no_pending / resume_unavailable：没有可确认或可恢复的内容 → respond 告知用户
    - rejected / expired：用户拒绝或确认已超时，操作作废 → respond
    - ambiguous：有待确认操作但回复既不是确认也不是拒绝 → respond 再次请用户明确表态
    - ownership_error：待确认操作不属于当前用户 → respond
    """
    # 优先处理上一轮的确认、恢复或补充信息，再判断是否识别新诉求。
    confirmation_status = state.get("confirmation_status") or "normal"
    if confirmation_status == "resume_suspended":
        return "restore_suspended_workflow"
    if confirmation_status == "inspect_interruption":
        return "understand_request"
    if confirmation_status == "normal" and state.get("workflow_active"):
        return "resume_workflow"
    return {
        "normal": "understand_request",
        "confirmed": "restore_pending_action",
        "no_pending": "respond",
        "resume_unavailable": "respond",
        "rejected": "respond",
        "expired": "respond",
        "ambiguous": "respond",
        "ownership_error": "respond",
    }.get(confirmation_status, "respond")


def _after_workflow_resume(state: AgentState) -> str:
    """resume_workflow 之后的路由：判断本轮回复是否补齐了上一轮追问的缺失字段。

    workflow_resume_status 由 resume_workflow 写入：
    - resumed：缺失字段已全部补齐，合并出完整决策 → compile_decision 重新核实
    - inspect_interruption：用户没回答追问而是说了别的事 → understand_request 交给模型判断
    - waiting_for_fields：仍缺字段（或只补了一部分）→ respond 继续追问
    - 其他（如 reset，追问状态已失效）→ understand_request 按新请求处理
    """
    status = state.get("workflow_resume_status")
    if status == "resumed":
        return "compile_decision"
    if status == "inspect_interruption":
        return "understand_request"
    if status == "waiting_for_fields":
        return "respond"
    return "understand_request"


def _after_understanding(state: AgentState) -> str:
    """understand_request（模型理解意图）之后的路由。

    - 模型调用失败或输出不合法（error_category 非空）→ respond 返回降级说明
    - 前面的节点标记了“流程中途插话”（workflow_interruption_pending）
      → handle_workflow_interruption 决定是继续原流程、挂起原流程还是用新请求替换
    - 正常情况 → compile_decision，把模型给出的语义提议核实成可执行参数
    """
    if state.get("error_category") is not None:
        return "respond"
    if state.get("workflow_interruption_pending"):
        return "handle_workflow_interruption"
    return "compile_decision"


def _after_workflow_interruption(state: AgentState) -> str:
    """handle_workflow_interruption 之后的路由：插话是否真的开启了一个新请求。

    - suspended：用户转去办另一件事，原流程被保存到 suspended_workflow，之后可以恢复
    - superseded：新请求直接取代原流程（如“别取消了，改成退款”），原流程作废
      以上两种都要处理新请求 → compile_decision
    - continued：意图没变或无法识别，视为仍在原流程中 → respond 继续等待确认或补充信息
    """
    return (
        "compile_decision"
        if state.get("workflow_interruption_status") in {"suspended", "superseded"}
        else "respond"
    )


def _after_pending_restore(state: AgentState) -> str:
    """restore_pending_action 之后的路由：已确认操作的参数能否恢复。

    用户确认后，restore_pending_action 从待确认记录里取回当时保存的意图和工具参数，
    成功时 compilation_resumed 为 True → compile_decision 重新核实一遍（不信任旧结果）；
    记录缺失或参数无法还原 → respond 返回错误。
    """
    return "compile_decision" if state.get("compilation_resumed") else "respond"


def _after_selection(state: AgentState) -> str:
    """select_tool_or_response 之后的路由：按核实结果把请求分到四个分支之一。

    - 出错，或编译器判定缺少信息（CLARIFICATION_REQUIRED）→ respond 向用户追问
    - 记住/忘记某条信息 → memory_action
    - 纯咨询、意图不明、只需查知识：需要检索 → retrieve_knowledge，不需要 → respond 直接答
    - 其余都是要调用业务工具的请求（查订单、取消、退款、转人工等）→ validate_tool
    """
    # 缺信息先追问；记忆与知识问答分别进入自己的分支。
    if state.get("error_category") is not None:
        return "respond"
    compile_result = state.get("compile_result")
    if compile_result is not None and compile_result.status == CompileStatus.CLARIFICATION_REQUIRED:
        return "respond"
    if state.get("intent") in {Intent.MEMORY_REMEMBER, Intent.MEMORY_FORGET}:
        return "memory_action"
    if state.get("request_type") in {
        AgentRequestType.INFORMATIONAL,
        AgentRequestType.UNCLEAR,
        AgentRequestType.KNOWLEDGE_ONLY,
    }:
        if state.get("requires_retrieval"):
            return "retrieve_knowledge"
        return "respond"
    return "validate_tool"


def _after_validation(state: AgentState) -> str:
    """validate_tool（按工具的参数模型校验参数）之后的路由。

    - 工具不存在或参数不合法 → respond
    - 本轮是用户确认后的执行（confirmation_status == confirmed）→ policy_revalidation 重新校验
    - 首次发起的操作 → evaluate_policy 按风险等级决定如何执行
    """
    if state.get("error_category") is not None:
        return "respond"
    if state.get("confirmation_status") == "confirmed":
        # 用户确认后仍要重验，防止等待期间订单状态或执行条件发生变化。
        return "policy_revalidation"
    return "evaluate_policy"


def _after_policy(state: AgentState) -> str:
    """策略判定之后的路由（挂在 inspect_risk 上，evaluate_policy → inspect_risk 后执行）。

    根据 policy_decision.outcome 分流：
    - DENY（越权、未知工具等）或没有决策 → respond 拒绝
    - ALLOW（只读查询、低风险写入）→ execute_tool 直接执行
    - REQUIRE_CONFIRMATION（取消订单、退款等）→ create_pending_action 保存待办并请用户确认
    - 其余（REQUIRE_HUMAN）→ execute_human_escalation 转人工
    """
    # 策略出口：拒绝、直接执行、等待确认、转人工。
    if state.get("error_category") is not None:
        return "respond"
    decision = state.get("policy_decision")
    if decision is None or decision.outcome == PolicyOutcome.DENY:
        return "respond"
    if decision.outcome == PolicyOutcome.ALLOW:
        return "execute_tool"
    if decision.outcome == PolicyOutcome.REQUIRE_CONFIRMATION:
        return "create_pending_action"
    return "execute_human_escalation"


def _after_revalidation(state: AgentState) -> str:
    """policy_revalidation 之后的路由：确认后的二次校验是否仍然通过。

    用户确认前后可能隔了一段时间，订单状态或策略条件可能已变化；
    重验失败 → respond 说明原因，通过 → execute_confirmed_action 真正执行。
    """
    return "respond" if state.get("error_category") is not None else "execute_confirmed_action"


def _after_execution(state: AgentState) -> str:
    """execute_tool（策略直接放行的工具执行）之后的路由。

    - 执行失败 → respond
    - 请求还需要政策解释（requires_retrieval，如“这单能退吗”）→ retrieve_knowledge 补充知识
    - 否则 → respond 直接返回业务结果
    确认后执行的 execute_confirmed_action 不经过这里，执行完直接进入 respond。
    """
    # “业务数据 + 政策解释”先查业务，再检索知识；普通操作直接回复。
    if state.get("error_category") is not None:
        return "respond"
    return "retrieve_knowledge" if state.get("requires_retrieval") else "respond"


def _load_context(state: AgentState) -> AgentState:
    # 身份来自后端执行上下文，不能由对话内容或模型结果指定。
    context = state.get("execution_context")
    if context is None:
        return {
            "intent": Intent.UNKNOWN,
            "request_type": AgentRequestType.UNCLEAR,
            "error_category": AgentErrorCategory.POLICY_DENIED,
            "last_error": "Authenticated execution context is required.",
            "selected_tool": None,
            "tool_result": None,
        }
    if state.get("conversation_id") != context.conversation_id:
        return {
            "intent": Intent.UNKNOWN,
            "request_type": AgentRequestType.UNCLEAR,
            "error_category": AgentErrorCategory.OWNERSHIP_VIOLATION,
            "last_error": "Execution context does not match request state.",
            "selected_tool": None,
            "tool_result": None,
        }
    # 恢复历史会话时，确认它仍属于当前租户、客户和操作者。
    existing_customer = state.get("conversation_customer_id")
    existing_tenant = state.get("conversation_tenant_id")
    existing_actor_id = state.get("conversation_actor_id")
    existing_actor_type = state.get("conversation_actor_type")
    principal = context.principal
    if (
        (existing_customer is not None and existing_customer != context.effective_customer_id)
        or (existing_tenant is not None and existing_tenant != context.tenant_id)
        or (existing_actor_id is not None and existing_actor_id != principal.actor_id)
        or (existing_actor_type is not None and existing_actor_type != principal.actor_type.value)
    ):
        return {
            "intent": Intent.UNKNOWN,
            "request_type": AgentRequestType.UNCLEAR,
            "error_category": AgentErrorCategory.OWNERSHIP_VIOLATION,
            "last_error": "Conversation belongs to a different execution context.",
            "selected_tool": None,
            "pending_action": state.get("pending_action"),
            "tool_result": None,
        }
    # 清空本轮临时结果；未返回的历史消息、待办等字段继续由图保留。
    return {
        "conversation_customer_id": context.effective_customer_id,
        "conversation_tenant_id": context.tenant_id,
        "conversation_actor_id": principal.actor_id,
        "conversation_actor_type": principal.actor_type.value,
        "agent_run_id": state["agent_run_id"],
        "retry_count": 0,
        "last_error": None,
        "error_category": None,
        "selected_tool": None,
        "tool_arguments": {},
        "semantic_decision": None,
        "compile_result": None,
        "grounding_status": "not_applicable",
        "grounding_reference_type": None,
        "grounding_trusted_source": None,
        "target_validation_status": "not_recorded",
        "tool_result": None,
        "retrieved_chunks": [],
        "retrieval_metadata": {},
        "answer_grounding": {},
        "knowledge_answer": None,
        "citations": [],
        "requires_retrieval": False,
        "knowledge_query": None,
        "policy_decision": None,
        "confirmation_status": None,
        "tool_execution_status": None,
        "memory_context": [],
        "memory_candidate": None,
        "memory_key": None,
        "memory_operation_status": None,
        "memory_policy_outcome": None,
        "memory_security_signal": None,
        "memory_summary_requested": False,
        "failure_category": None,
        "degraded_components": [],
        "recovery_action": None,
        "write_outcome_unknown": False,
        "replay_detected": False,
        "idempotency_outcome": None,
        "pending_action_restored": False,
        "restored_fields_count": 0,
        "compilation_resumed": False,
        "workflow_interruption_pending": False,
        "workflow_interruption_status": None,
        "interruption_intent": None,
        "workflow_resume_source": None,
        "workflow_transition": None,
        "workflow_interruption_type": None,
    }


def _inspect_risk(state: AgentState) -> AgentState:
    return {}


# 以下是监控包装；首次阅读可先跳到 build_graph 看业务连接关系。
def _instrument_node(
    name: str, node: Callable[[AgentState], AgentState]
) -> Callable[[AgentState], AgentState]:
    def instrumented(state: AgentState) -> AgentState:
        import time

        started = time.perf_counter()
        with span(
            f"agent.{name}", attributes={"agent.node": name, "node.name": name}
        ) as active_span:
            try:
                if name in {"execute_tool", "escalate"}:
                    result = _instrument_tool_node(name, node, state)
                else:
                    result = node(state)
                error = result.get("error_category")
                active_span.set_attribute("node.status", "error" if error else "ok")
                if error is not None:
                    active_span.set_attribute("error.category", error.value)
                selected_tool = result.get("selected_tool")
                if isinstance(selected_tool, str):
                    active_span.set_attribute("tool.selected", selected_tool)
                recovery = result.get("recovery_action")
                if isinstance(recovery, str):
                    active_span.set_attribute("recovery.action", recovery)
                    if recovery in {"degraded", "continue_without_memory"}:
                        get_metrics().degraded_requests_total.add(1, {"component": name})
                trace_metadata: dict[str, str | int | float | bool] = {}
                if name == "check_pending_action":
                    previous_action = state.get("pending_action")
                    confirmation_status = result.get("confirmation_status")
                    trace_metadata = {
                        "previous_pending_action": (
                            previous_action.tool_name if previous_action is not None else "none"
                        ),
                        "confirmation_detected": confirmation_status in {"confirmed", "rejected"},
                        "confirmation_result": (
                            "approved"
                            if confirmation_status == "confirmed"
                            else str(confirmation_status or "not_recorded")
                        ),
                    }
                elif name == "execute_tool":
                    execution_status = result.get("tool_execution_status") or state.get(
                        "tool_execution_status"
                    )
                    trace_metadata = {
                        "tool_execution": str(execution_status or "not_recorded"),
                    }
                elif name in {"security_boundary", "understand_request"} and result.get(
                    "security_signal"
                ):
                    security_signal = str(result["security_signal"])
                    trace_metadata = {
                        "security_signal": security_signal,
                        "decision": "deny",
                        "reason": security_signal,
                        "execution": "not_attempted",
                        "authority": "not_granted",
                    }
                elif name == "restore_pending_action":
                    trace_metadata = {
                        "pending_action_restored": bool(
                            result.get("pending_action_restored", False)
                        ),
                        "restored_fields_count": int(result.get("restored_fields_count", 0)),
                        "compilation_resumed": bool(result.get("compilation_resumed", False)),
                    }
                elif name == "memory_action" and result.get("security_signal"):
                    trace_metadata = {
                        "security_signal": str(result["security_signal"]),
                        "decision": "deny",
                        "reason": str(
                            result.get("decision_reason")
                            or result.get("memory_policy_outcome")
                            or "memory_write_rejected"
                        ),
                        "execution": "not_attempted",
                        "authority": "not_granted",
                    }
                elif name == "handle_workflow_interruption":
                    previous_intent = result.get("previous_workflow_intent") or state.get(
                        "previous_workflow_intent"
                    )
                    interruption_intent = result.get("interruption_intent") or state.get(
                        "interruption_intent"
                    )
                    trace_metadata = {
                        "workflow_state": str(
                            result.get("workflow_interruption_status") or "not_changed"
                        ),
                        "workflow_transition": str(
                            result.get("workflow_transition") or "not_recorded"
                        ),
                        "previous_workflow": str(
                            result.get("previous_workflow_id") or "not_recorded"
                        ),
                        "new_workflow": str(result.get("workflow_id") or "not_recorded"),
                        "previous_workflow_intent": str(
                            getattr(previous_intent, "value", previous_intent or "not_recorded")
                        ),
                        "interruption_intent": str(
                            getattr(
                                interruption_intent,
                                "value",
                                interruption_intent or "not_recorded",
                            )
                        ),
                        "interruption_type": str(
                            result.get("workflow_interruption_type") or "not_recorded"
                        ),
                        "superseded_by": str(result.get("superseded_by") or "not_applicable"),
                    }
                elif name == "restore_suspended_workflow":
                    snapshot = state.get("suspended_workflow")
                    previous_intent = snapshot.get("intent") if snapshot is not None else None
                    trace_metadata = {
                        "workflow_state": "resumed",
                        "workflow_transition": str(
                            result.get("workflow_transition") or "suspended_to_resumed"
                        ),
                        "previous_workflow": str(
                            result.get("previous_workflow_id") or "not_recorded"
                        ),
                        "new_workflow": str(result.get("workflow_id") or "not_recorded"),
                        "previous_workflow_intent": str(
                            getattr(previous_intent, "value", previous_intent or "not_recorded")
                        ),
                        "resume_source": str(
                            result.get("workflow_resume_source") or "not_recorded"
                        ),
                        "interruption_type": str(
                            result.get("workflow_interruption_type") or "explicit_resume"
                        ),
                        "superseded_by": "not_applicable",
                        "restored_fields_count": int(result.get("restored_fields_count", 0)),
                    }
                elif name == "compile_decision" and state.get("compilation_resumed"):
                    trace_metadata = {"compilation_resumed": True}
                elif name == "policy_revalidate":
                    for key in (
                        "original_policy_inputs_hash",
                        "restored_policy_inputs_hash",
                        "policy_input_diff",
                        "original_pending_policy_inputs",
                        "restored_policy_inputs",
                        "original_policy_inputs_normalized",
                        "restored_policy_inputs_normalized",
                        "policy_revalidation_stage",
                        "policy_revalidation_result",
                    ):
                        value = result.get(key)
                        if isinstance(value, (str, int, float, bool)):
                            trace_metadata[key] = value
                record_node(name, "error" if error else "ok", started, trace_metadata)
                return result
            except Exception:
                record_node(name, "error", started)
                raise

    return instrumented


def _instrument_tool_node(
    node_name: str, node: Callable[[AgentState], AgentState], state: AgentState
) -> AgentState:
    import time

    tool_name = state.get("selected_tool") or "unknown"
    metadata = registry.TOOL_REGISTRY.get(tool_name)
    attributes: dict[str, str | int] = {"tool.name": tool_name}
    if metadata is not None:
        attributes.update(
            {
                "tool.operation_type": metadata.operation_type.value,
                "tool.risk_level": metadata.risk_level.value,
            }
        )
    started = time.perf_counter()
    status = "failed"
    with span("tool.execute", attributes=attributes) as tool_span:
        result = node(state)
        result_status = result.get("tool_execution_status")
        status = result_status if isinstance(result_status, str) else "not_executed"
        tool_span.set_attribute("tool.status", status)
        error_category = result.get("error_category")
        if error_category is not None:
            tool_span.set_attribute("error.category", error_category.value)
        metric_labels = {"tool_name": tool_name, "status": status}
        get_metrics().tool_calls_total.add(1, metric_labels)
        if status == "failed":
            get_metrics().tool_errors_total.add(
                1,
                {
                    "tool_name": tool_name,
                    "error_category": str(
                        getattr(error_category, "value", error_category or "unknown")
                    ),
                },
            )
        get_metrics().tool_call_duration_seconds.record(
            time.perf_counter() - started, {"tool_name": tool_name, "status": status}
        )
        if node_name == "escalate" and status == "executed":
            priority = state.get("tool_arguments", {}).get("priority", "unknown")
            tool_span.set_attribute("escalation.priority", str(priority))
            tool_span.set_attribute("escalation.reason_code", "customer_service_request")
            get_metrics().escalations_total.add(1, {"priority": str(priority)})
            tool_span.add_event("escalation.created")
        return result


def build_graph(
    provider: DecisionProposalProvider,
    session: Session,
    checkpointer: BaseCheckpointSaver[str],
    *,
    policy_engine: PolicyEngine,
    clock: Clock,
    ttl_seconds: int,
    audit_repository: PolicyAuditRepository,
    knowledge_retriever: KnowledgeRetriever,
    grounded_generator: GroundedAnswerGenerator,
    memory_service: MemoryService,
    resilience_config: ResilienceConfig,
    reliability_controller: ReliabilityController,
    decision_contract_version: str = "semantic_decision_v3",
) -> CompiledStateGraph[AgentState, None, AgentState, AgentState]:
    """注入依赖并构建流程图，尚不执行用户请求。

    节点工厂绑定数据库、模型等依赖；条件边读取状态决定下一步。
    checkpointer 保存跨轮状态，真正运行图的入口在 runtime.py。
    """
    graph: StateGraph[AgentState, None, AgentState, AgentState] = StateGraph(AgentState)
    # 1. 加载身份与会话，处理安全边界、历史记忆和未完成任务。
    graph.add_node("load_context", cast(Any, _instrument_node("load_context", _load_context)))
    graph.add_node(
        "security_boundary",
        cast(Any, _instrument_node("security_boundary", make_security_boundary_node())),
    )
    graph.add_node(
        "check_pending_action",
        cast(
            Any,
            _instrument_node(
                "check_pending_action",
                make_check_pending_node(clock, ttl_seconds, audit_repository),
            ),
        ),
    )
    graph.add_node(
        "resume_workflow",
        cast(Any, _instrument_node("resume_workflow", make_resume_workflow_node())),
    )
    graph.add_node(
        "restore_pending_action",
        cast(Any, _instrument_node("restore_pending_action", make_restore_pending_node())),
    )
    graph.add_node(
        "handle_workflow_interruption",
        cast(
            Any,
            _instrument_node(
                "handle_workflow_interruption",
                make_handle_workflow_interruption_node(),
            ),
        ),
    )
    graph.add_node(
        "restore_suspended_workflow",
        cast(
            Any,
            _instrument_node(
                "restore_suspended_workflow",
                make_restore_suspended_workflow_node(),
            ),
        ),
    )
    graph.add_node(
        "retrieve_memory",
        cast(
            Any,
            _instrument_node(
                "retrieve_memory",
                make_retrieve_memory_node(
                    memory_service, session, resilience_config, reliability_controller
                ),
            ),
        ),
    )
    # 2. 模型提出语义意图，编译器把它转换成具体工具参数或追问。
    graph.add_node(
        "understand_request",
        cast(
            Any,
            _instrument_node(
                "understand_request",
                make_understand_request_node(
                    provider,
                    resilience_config,
                    decision_contract_version,
                    reliability_controller,
                ),
            ),
        ),
    )
    graph.add_node(
        "select_tool_or_response", cast(Any, _instrument_node("route_request", select_tool))
    )
    graph.add_node(
        "compile_decision",
        cast(
            Any,
            _instrument_node(
                "compile_decision",
                make_compile_decision_node(session, decision_contract_version),
            ),
        ),
    )
    graph.add_node(
        "memory_action",
        cast(
            Any,
            _instrument_node("memory_action", make_memory_action_node(memory_service, session)),
        ),
    )
    # 3. 校验参数与策略；有副作用的操作可能需要确认、重验或人工处理。
    graph.add_node("validate_tool", cast(Any, _instrument_node("validate_tool", validate_tool)))
    graph.add_node(
        "evaluate_policy",
        cast(
            Any,
            _instrument_node(
                "evaluate_policy", make_evaluate_policy_node(policy_engine, audit_repository, clock)
            ),
        ),
    )
    graph.add_node("inspect_risk", cast(Any, _instrument_node("inspect_risk", _inspect_risk)))
    graph.add_node(
        "create_pending_action",
        cast(
            Any,
            _instrument_node("create_pending_action", make_create_pending_node(clock)),
        ),
    )
    graph.add_node(
        "policy_revalidation",
        cast(
            Any,
            _instrument_node(
                "policy_revalidate",
                make_revalidate_node(session, audit_repository, clock, policy_engine),
            ),
        ),
    )
    graph.add_node(
        "execute_tool",
        cast(
            Any,
            _instrument_node(
                "execute_tool",
                make_confirmed_execution_node(
                    session,
                    resilience_config,
                    audit_repository,
                    clock,
                    reliability_controller,
                ),
            ),
        ),
    )
    graph.add_node(
        "execute_confirmed_action",
        cast(
            Any,
            _instrument_node(
                "execute_tool",
                make_confirmed_execution_node(
                    session,
                    resilience_config,
                    audit_repository,
                    clock,
                    reliability_controller,
                ),
            ),
        ),
    )
    graph.add_node(
        "execute_human_escalation",
        cast(
            Any,
            _instrument_node(
                "escalate",
                make_human_escalation_node(
                    session,
                    audit_repository,
                    clock,
                    resilience_config,
                    reliability_controller,
                ),
            ),
        ),
    )
    # 4. 知识分支检索证据；各分支最后统一交给 respond 组织回复。
    graph.add_node(
        "retrieve_knowledge",
        cast(
            Any,
            _instrument_node(
                "retrieve_knowledge",
                make_retrieve_node(
                    knowledge_retriever,
                    grounded_generator,
                    resilience_config,
                    reliability_controller,
                ),
            ),
        ),
    )
    graph.add_node("respond", cast(Any, _instrument_node("respond", respond)))
    # 上面注册节点，下面才定义执行顺序和条件分支。
    graph.add_edge(START, "load_context")
    graph.add_conditional_edges(
        "load_context",
        _after_context,
        {"security_boundary": "security_boundary", "respond": "respond"},
    )
    graph.add_conditional_edges(
        "security_boundary",
        _after_security_boundary,
        {"retrieve_memory": "retrieve_memory", "respond": "respond"},
    )
    graph.add_edge("retrieve_memory", "check_pending_action")
    graph.add_conditional_edges(
        "check_pending_action",
        _after_pending,
        {
            "understand_request": "understand_request",
            "restore_pending_action": "restore_pending_action",
            "restore_suspended_workflow": "restore_suspended_workflow",
            "resume_workflow": "resume_workflow",
            "respond": "respond",
        },
    )
    graph.add_conditional_edges(
        "restore_pending_action",
        _after_pending_restore,
        {"compile_decision": "compile_decision", "respond": "respond"},
    )
    graph.add_conditional_edges(
        "resume_workflow",
        _after_workflow_resume,
        {
            "compile_decision": "compile_decision",
            "understand_request": "understand_request",
            "respond": "respond",
        },
    )
    graph.add_edge("restore_suspended_workflow", "respond")
    graph.add_conditional_edges(
        "understand_request",
        _after_understanding,
        {
            "handle_workflow_interruption": "handle_workflow_interruption",
            "compile_decision": "compile_decision",
            "respond": "respond",
        },
    )
    graph.add_conditional_edges(
        "handle_workflow_interruption",
        _after_workflow_interruption,
        {"compile_decision": "compile_decision", "respond": "respond"},
    )
    graph.add_edge("compile_decision", "select_tool_or_response")
    graph.add_conditional_edges(
        "select_tool_or_response",
        _after_selection,
        {
            "validate_tool": "validate_tool",
            "memory_action": "memory_action",
            "retrieve_knowledge": "retrieve_knowledge",
            "respond": "respond",
        },
    )
    graph.add_conditional_edges(
        "validate_tool",
        _after_validation,
        {
            "evaluate_policy": "evaluate_policy",
            "policy_revalidation": "policy_revalidation",
            "respond": "respond",
        },
    )
    graph.add_edge("evaluate_policy", "inspect_risk")
    graph.add_conditional_edges(
        "inspect_risk",
        _after_policy,
        {
            "execute_tool": "execute_tool",
            "create_pending_action": "create_pending_action",
            "execute_human_escalation": "execute_human_escalation",
            "respond": "respond",
        },
    )
    graph.add_conditional_edges(
        "policy_revalidation",
        _after_revalidation,
        {"execute_confirmed_action": "execute_confirmed_action", "respond": "respond"},
    )
    graph.add_conditional_edges(
        "execute_tool",
        _after_execution,
        {"retrieve_knowledge": "retrieve_knowledge", "respond": "respond"},
    )
    graph.add_edge("create_pending_action", "respond")
    graph.add_edge("memory_action", "respond")
    graph.add_edge("execute_confirmed_action", "respond")
    graph.add_edge("execute_human_escalation", "respond")
    graph.add_edge("retrieve_knowledge", "respond")
    graph.add_edge("respond", END)
    return graph.compile(checkpointer=checkpointer)
