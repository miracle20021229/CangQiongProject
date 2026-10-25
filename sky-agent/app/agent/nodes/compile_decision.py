import time
from collections.abc import Callable
from sqlalchemy.orm import Session
from app.agent.decision_compiler import (
    BusinessTargetResolver,
    CompileStatus,
    DecisionCompiler,
)
from app.agent.schemas import AgentErrorCategory, SemanticDecision
from app.agent.semantic_grounding import (
    GroundingStatus,
    SemanticGrounding,
    validate_semantic_grounding,
)
from app.agent.state import AgentState
from app.agent.target_admissibility import assess_target_admissibility
from app.observability.metrics import get_metrics
from app.observability.tracing import span


def make_compile_decision_node(
    session: Session, decision_contract_version: str = "semantic_decision_v2"
) -> Callable[[AgentState], AgentState]:
    # 创建编译器并注入数据库会话，供后续解析“最新订单”等业务目标。
    compiler = DecisionCompiler(BusinessTargetResolver(session))

    def compile_decision(state: AgentState) -> AgentState:
        """接收语义决策，校验并编译，再把结果整理成 AgentState 的更新字段"""
        # 1.开始计时并读取语义决策，status 仅用于编译耗时的分类统计
        started = time.perf_counter()
        status = "skipped"
        decision = state.get("semantic_decision")
        # 1.1.没有语义决策时记录 skipped，返回空字典表示不更新状态
        if decision is None:
            get_metrics().decision_compile_duration_seconds.record(
                time.perf_counter() - started, {"status": status}
            )
            return {}
        # 2.检查决策类型，准备工作流 ID、用户文本和待处理操作的恢复标记
        assert isinstance(decision, SemanticDecision)
        workflow_id = state.get("workflow_id") or f"workflow:{state['agent_run_id']}"
        # 拼接所有轮次的用户消息，因为订单号等信息可能在之前的轮次给出。
        user_message = _conversation_user_message(state)
        restored_action = bool(state.get("pending_action_restored"))
        # 3.恢复操作时重建已保存的目标依据，否则校验决策中的目标是否出自用户原文，防止模型臆造订单号
        grounding = (
            _restored_grounding(state, decision)
            if restored_action
            else validate_semantic_grounding(decision, user_message)
        )
        # 3.1.结合意图判断目标能否继续编译，例如取消订单不能只指定“最新订单”
        admissibility = assess_target_admissibility(decision.intent, decision.target, grounding)
        # 4.同步编译语义决策，得到工具与参数、澄清要求、无动作或拒绝结果
        # 执行上下文提供当前客户和租户；此处不调用模型，也不执行业务工具。
        result = compiler.compile(
            decision,
            state["execution_context"],
            grounding=grounding,
            user_message=user_message,
            # 4.1.仅恢复操作且保存的依据状态为 not_applicable 时，启用编译器恢复分支
            restored_action=restored_action
            and state.get("grounding_status") == GroundingStatus.NOT_APPLICABLE.value,
        )
        # 5.编译完成后记录追踪属性，包括协议、意图、目标校验结果和选定工具
        # span 内部为空，只用于写入属性，不统计编译耗时；耗时由 5.1 的指标记录。
        with span(
            "decision.compile",
            attributes={
                "decision.contract.version": decision_contract_version,
                "semantic.intent": decision.intent.value,
                "semantic.grounding.status": grounding.status.value,
                "semantic.grounding.reference_type": grounding.reference_type or "none",
                "semantic.target_admissibility": admissibility.value,
                "compiler.status": result.status.value,
                "compiled.tool": result.selected_tool or "none",
            },
        ):
            pass
        # 5.1.按拒绝或接受记录总耗时；accepted 包括需要澄清，不代表业务执行成功
        status = "rejected" if result.status == CompileStatus.COMPILE_REJECTED else "accepted"
        get_metrics().decision_compile_duration_seconds.record(
            time.perf_counter() - started, {"status": status}
        )
        # 6.编译被拒绝时，清空工具和待补充信息，返回错误及工作流状态更新
        if result.status == CompileStatus.COMPILE_REJECTED:
            return {
                "intent": result.intent,
                "request_type": result.request_type,
                # 清空工具名和参数，确保后续节点没有可执行的工具。
                "selected_tool": None,
                "tool_arguments": {},
                "error_category": AgentErrorCategory.INVALID_TOOL_ARGUMENTS,
                "last_error": "The semantic request could not be compiled safely.",
                "decision_reason": result.reason,
                "compile_result": result,
                "grounding_status": grounding.status.value,
                "grounding_reference_type": grounding.reference_type,
                "grounding_trusted_source": grounding.trusted_source,
                "target_validation_status": admissibility.value,
                "previous_intent": decision.intent,
                "pending_workflow_decision": None,
                "missing_required_fields": [],
                "collected_entities": _collected_entities(decision),
                "workflow_active": False,
                "workflow_resume_status": None,
                # 保留已有挂起状态，否则结束当前流程；completed 不表示业务成功。
                "workflow_state": (
                    "suspended" if state.get("suspended_workflow") is not None else "completed"
                ),
                "workflow_id": workflow_id,
            }
        # 7.只有需要澄清且存在缺失字段时，才保持工作流活跃以继续收集信息
        workflow_is_active = bool(
            result.status == CompileStatus.CLARIFICATION_REQUIRED and result.missing_required_fields
        )
        # 7.1.整理目标校验信息，跨轮任务挂起和恢复时使用
        validation_context: dict[str, object] = {
            "grounding_status": grounding.status.value,
            "grounding_reference_type": grounding.reference_type,
            "grounding_trusted_source": grounding.trusted_source,
            "target_validation_status": admissibility.value,
            "decision_reason": result.reason,
        }
        # 8.返回状态增量，由图框架合并到 AgentState，供后续节点使用
        return {
            # 8.1.保存编译后的意图、请求类型、工具名和参数
            "intent": result.intent,
            "request_type": result.request_type,
            "selected_tool": result.selected_tool,
            "tool_arguments": result.tool_arguments,
            # 8.2.传递知识检索与记忆处理信息，具体操作由后续节点完成
            "requires_retrieval": result.requires_retrieval,
            "knowledge_query": result.knowledge_query,
            "memory_candidate": result.memory_candidate,
            "memory_key": result.memory_key,
            # 8.3.保存完整编译结果，以及目标依据和适用性校验结果
            "decision_reason": result.reason,
            "compile_result": result,
            "grounding_status": grounding.status.value,
            "grounding_reference_type": grounding.reference_type,
            "grounding_trusted_source": grounding.trusted_source,
            "target_validation_status": admissibility.value,
            # 8.4.需要澄清时保留当前决策，记录缺失字段和已收集信息以便下一轮继续
            "previous_intent": decision.intent,
            "pending_workflow_decision": decision
            if result.status == CompileStatus.CLARIFICATION_REQUIRED
            else None,
            "missing_required_fields": result.missing_required_fields,
            "collected_entities": _collected_entities(decision),
            "workflow_active": workflow_is_active,
            "workflow_resume_status": None,
            # 有挂起流程时优先标记 suspended，否则按是否仍需收集信息选择 active 或 completed。
            "workflow_state": (
                "suspended"
                if state.get("suspended_workflow") is not None
                else "active"
                if workflow_is_active
                else "completed"
            ),
            "workflow_id": workflow_id,
            # 8.5.保存工作流参数和校验上下文，重置策略输入并清除错误信息
            "workflow_tool_arguments": dict(result.tool_arguments),
            "workflow_validation_context": validation_context,
            "workflow_policy_inputs": {},
            "error_category": None,
            "last_error": None,
        }

    # 返回节点函数，由图运行时传入 AgentState 后执行。
    return compile_decision


def _latest_user_message(state: AgentState) -> str:
    """倒序查找最后一条用户消息"""
    for message in reversed(state.get("messages", [])):
        if message["role"] == "user":
            return message["content"]
    return ""


def _conversation_user_message(state: AgentState) -> str:
    """拼接所有用户消息"""
    return " ".join(
        message["content"] for message in state.get("messages", []) if message["role"] == "user"
    )


def _collected_entities(decision: SemanticDecision) -> dict[str, str | int | bool]:
    """从语义决策提取 order_id、ticket_id、reason，记录已收集的信息"""
    entities: dict[str, str | int | bool] = {}
    target = decision.target
    if target is not None:
        if target.order_id is not None:
            entities["order_id"] = target.order_id
        if target.ticket_id is not None:
            entities["ticket_id"] = target.ticket_id
    if decision.reason:
        entities["reason"] = decision.reason
    return entities


def _restored_grounding(state: AgentState, decision: SemanticDecision) -> SemanticGrounding:
    """恢复待处理操作时，从保存的状态重建目标依据校验结果，并处理不合法字段值"""
    status_value = state.get("grounding_status", GroundingStatus.NOT_APPLICABLE.value)
    try:
        status = GroundingStatus(status_value)
    except ValueError:
        status = GroundingStatus.INVALID
    reference_type = state.get("grounding_reference_type")
    if reference_type not in {"explicit_order", "explicit_ticket", "latest_order", None}:
        reference_type = None
    trusted_source = state.get("grounding_trusted_source")
    if trusted_source != "current_user_message":
        trusted_source = None
    if decision.target is None:
        reference_type = None
    return SemanticGrounding(
        status=status,
        reference_type=reference_type,
        trusted_source=trusted_source,
    )
