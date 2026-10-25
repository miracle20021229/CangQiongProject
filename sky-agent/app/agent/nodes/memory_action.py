"""处理用户的记住和忘记请求，将操作结果交给 respond 生成回复。"""

from collections.abc import Callable
from opentelemetry.trace import Span
from sqlalchemy.orm import Session
from app.agent.errors import (
    RuntimeErrorClassification,
    RuntimeFailureSource,
    classify_runtime_error,
)
from app.agent.schemas import AgentErrorCategory, Intent
from app.agent.state import AgentState
from app.memory.schemas import MemorySource
from app.memory.service import MemoryService
from app.observability.metrics import get_metrics
from app.observability.tracing import span
from app.resilience.errors import FailureCategory


def make_memory_action_node(
    service: MemoryService, session: Session
) -> Callable[[AgentState], AgentState]:
    """创建记忆操作节点，通过闭包提供记忆服务和数据库会话。"""

    def memory_action(state: AgentState) -> AgentState:
        """读取当前客户身份，执行记住或忘记操作，返回状态更新。"""
        # 1.读取执行身份，没有身份就拒绝操作
        context = state.get("execution_context")
        if context is None:
            return _failed(
                AgentErrorCategory.POLICY_DENIED,
                "Authenticated execution context is required for memory.",
            )
        # 2.记住分支：保存用户明确要求记住的信息
        intent = state.get("intent")
        if intent == Intent.MEMORY_REMEMBER:
            # 2.1.读取待保存的记忆，没有具体内容就拒绝
            candidate = state.get("memory_candidate")
            if candidate is None:
                return _failed(
                    AgentErrorCategory.INVALID_TOOL_ARGUMENTS,
                    "A specific memory was not provided.",
                )
            # 2.2.记录本次操作的追踪信息
            with span("memory.evaluate_candidate") as memory_span:
                memory_span.set_attribute("memory.operation", "remember")
                memory_span.set_attribute("memory.type", candidate.memory_type.value)
                try:
                    # 同步调用记忆服务，按当前客户和租户检查策略、去重并保存。
                    # 数据库提交在服务内部完成，来源标记为用户明确提出。
                    result = service.remember(
                        session,
                        context.effective_customer_id,
                        candidate,
                        source=MemorySource.USER_EXPLICIT,
                        tenant_id=context.tenant_id,
                    )
                except Exception as error:
                    classification = classify_runtime_error(
                        error, source=RuntimeFailureSource.MEMORY
                    )
                    # 服务调用异常时记录分类，返回失败状态。
                    _set_failure_telemetry(memory_span, "remember", classification)
                    return _memory_failure(classification)
                # 记录服务返回的结果，以及需要向后传递的安全信号。
                memory_span.set_attribute("memory.status", result.status)
                memory_span.set_attribute("memory.policy_outcome", result.status)
                if result.security_signal is not None:
                    memory_span.set_attribute("memory.security_signal", result.security_signal)
            # 2.3.保存成功或命中已有记忆时，补充追踪记录
            # 这里只记录事件，数据库操作已在 service.remember 中完成。
            if result.status in {"persisted", "deduplicated"}:
                with span(
                    "memory.persist",
                    attributes={
                        "memory.type": candidate.memory_type.value,
                        "memory.status": result.status,
                    },
                ):
                    pass
            # 2.4.统计保存成功、去重或拒绝的次数
            if result.status in {"persisted", "deduplicated"}:
                get_metrics().memory_writes_total.add(1, {"status": result.status})
            else:
                get_metrics().memory_rejections_total.add(
                    1, {"reason": result.reason or result.status}
                )
            # 2.5.返回操作结果，由图合并到状态，再交给 respond 回复
            # persisted 表示已保存，deduplicated 表示已有相同内容；其余结果标记为策略拒绝。
            return {
                "memory_operation_status": result.status,
                "memory_policy_outcome": result.status,
                "decision_reason": result.reason,
                "security_signal": result.security_signal,
                "memory_security_signal": result.security_signal,
                "last_error": None,
                "error_category": None
                if result.status in {"persisted", "deduplicated"}
                else AgentErrorCategory.POLICY_DENIED,
            }
        # 3.忘记分支：按记忆键移除当前客户的有效记忆
        if intent == Intent.MEMORY_FORGET:
            # 3.1.读取要忘记的记忆键，没有指定就拒绝
            key = state.get("memory_key")
            if not key:
                return _failed(
                    AgentErrorCategory.INVALID_TOOL_ARGUMENTS,
                    "Please specify which memory to forget.",
                )
            # 3.2.记录遗忘操作，并调用记忆服务
            with span(
                "memory.forget",
                attributes={"memory.operation": "forget", "memory.key": key},
            ) as memory_span:
                try:
                    # 同步查找当前客户、当前租户下的记忆，标记为已删除并提交。
                    result = service.forget(
                        session,
                        context.effective_customer_id,
                        key,
                        tenant_id=context.tenant_id,
                    )
                except Exception as error:
                    classification = classify_runtime_error(
                        error, source=RuntimeFailureSource.MEMORY
                    )
                    # 遗忘操作异常时，同样记录分类并返回失败状态。
                    _set_failure_telemetry(memory_span, "forget", classification)
                    return _memory_failure(classification)
                memory_span.set_attribute("memory.status", result.status)
                memory_span.set_attribute("memory.policy_outcome", result.status)
            # 3.3.遗忘成功时记录次数
            if result.status == "forgotten":
                get_metrics().memory_forgets_total.add(1, {"status": result.status})
            # 3.4.返回遗忘结果；forgotten 表示已遗忘，not_found 表示没有匹配记忆
            # 没找到不算错误，respond 会告诉用户没有匹配记录。
            return {
                "memory_operation_status": result.status,
                "memory_policy_outcome": result.status,
                "last_error": None if result.status == "forgotten" else result.reason,
                "error_category": None
                if result.status in {"forgotten", "not_found"}
                else AgentErrorCategory.POLICY_DENIED,
            }
        # 4.既不是记住也不是忘记，拒绝本次操作
        return _failed(AgentErrorCategory.POLICY_DENIED, "Memory operation was not recognized.")

    # 返回节点函数，运行时由图传入 AgentState 调用。
    return memory_action


def _memory_failure(classification: RuntimeErrorClassification) -> AgentState:
    """将记忆服务异常整理为失败状态，供后续回复使用。"""
    # 优先保留具体失败原因，没有时使用通用记忆失败分类。
    failure_category = classification.failure_category or FailureCategory.MEMORY_FAILURE.value
    return {
        "memory_operation_status": "failed",
        "memory_policy_outcome": "failed",
        "failure_category": failure_category,
        "recovery_action": "fail_safely",
        "error_category": classification.category,
        "last_error": "Persistent memory could not be updated.",
    }


def _set_failure_telemetry(
    memory_span: Span, operation: str, classification: RuntimeErrorClassification
) -> None:
    """把失败操作和错误分类写入追踪记录，方便排查问题。"""
    memory_span.set_attribute("memory.operation", operation)
    memory_span.set_attribute("memory.status", "failed")
    memory_span.set_attribute("memory.policy_outcome", "failed")
    memory_span.set_attribute("error.category", classification.category.value)
    if classification.failure_category is not None:
        memory_span.set_attribute("memory.failure_category", classification.failure_category)


def _failed(category: AgentErrorCategory, message: str) -> AgentState:
    """身份、参数或意图不符合要求时，返回拒绝状态和原因。"""
    return {
        "memory_operation_status": "rejected",
        "memory_policy_outcome": "reject",
        "last_error": message,
        "error_category": category,
    }
