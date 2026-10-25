from collections.abc import Callable
from sqlalchemy.orm import Session
from app.agent.errors import RuntimeFailureSource, classify_runtime_error
from app.agent.nodes.common import serialise_result
from app.agent.nodes.execution_audit import record_execution_event
from app.agent.schemas import AgentErrorCategory
from app.agent.state import AgentState
from app.agent.tool_catalog import get_agent_tool_definition
from app.policies.confirmation import Clock
from app.policies.repository import PolicyAuditRepository
from app.resilience.config import ResilienceConfig
from app.resilience.control import ReliabilityController
from app.resilience.errors import UnknownWriteOutcomeError
from app.resilience.retry import run_with_retry
from app.services.idempotency import IdempotencyScope, commit_business_write
from app.tools.base import ToolError


def make_human_escalation_node(
    session: Session,
    audit_repository: PolicyAuditRepository | None = None,
    clock: Clock | None = None,
    resilience_config: ResilienceConfig | None = None,
    reliability_controller: ReliabilityController | None = None,
) -> Callable[[AgentState], AgentState]:
    def execute_human_escalation(state: AgentState) -> AgentState:
        """同步执行人工转交工具，创建待人工处理记录并返回 AgentState 的增量更新。

        使用外层注入的数据库、审计与可靠性依赖；当前实现登记排队任务，不接通真人会话。
        节点完成后由图进入 respond，向用户反馈成功、失败或写入结果不明。
        """
        # 1.只允许 escalate_to_human 工具进入人工路径，其他工具返回策略拒绝错误。
        if state.get("selected_tool") != "escalate_to_human":
            return {
                "error_category": AgentErrorCategory.POLICY_DENIED,
                "last_error": "Only the registered escalation tool may use the human path.",
                "tool_execution_status": "failed",
            }
        # 2.从工具目录取得输入模型和执行适配函数，供下方校验参数及调用业务工具。
        definition = get_agent_tool_definition("escalate_to_human")
        # 2.1.工具未注册或缺少执行定义时返回失败，避免执行未知工具。
        if definition is None:
            return {
                "error_category": AgentErrorCategory.UNKNOWN_TOOL,
                "last_error": "Escalation tool is not registered.",
                "tool_execution_status": "failed",
            }
        # 3.读取服务端提供的可信执行上下文，取得操作者、租户和有效客户身份。
        context = state.get("execution_context")
        # 3.1.缺少认证上下文时拒绝执行，不能只相信模型或工具参数中的身份。
        if context is None:
            return {
                "error_category": AgentErrorCategory.POLICY_DENIED,
                "last_error": "Authenticated execution context is required.",
                "tool_execution_status": "failed",
            }
        # 4.初始化执行审计标记；仅成功记录 attempted 事件后才置为 True。
        execution_started = False
        try:
            # 5.再次按工具输入模型校验参数，得到包含原因、优先级、摘要等字段的对象。
            arguments = definition.input_model.model_validate(state.get("tool_arguments", {}))
            requested_customer = getattr(arguments, "customer_id", None)
            # 5.1.参数中的客户必须与可信上下文一致；资源实际归属由底层工具继续检查。
            if requested_customer != context.effective_customer_id:
                return {
                    "error_category": AgentErrorCategory.OWNERSHIP_VIOLATION,
                    "last_error": "Tool customer scope conflicts with execution context.",
                    "tool_execution_status": "failed",
                }
            # 5.2.读取动作 ID 作为幂等键；缺失时拒绝写入，防止失去重复请求识别依据。
            action_id = state.get("action_id")
            if not action_id:
                return {
                    "error_category": AgentErrorCategory.POLICY_DENIED,
                    "last_error": "Business write is missing its idempotency key.",
                    "tool_execution_status": "failed",
                }
            # 6.审计仓库和时钟均可用时，先记录 attempted，再标记已进入执行审计阶段。
            if audit_repository is not None and clock is not None:
                record_execution_event(state, audit_repository, clock, status="attempted")
                execution_started = True

            # 7.定义单次执行函数，把业务调用和事务提交交给下方可靠性封装同步调用。
            def attempt() -> object:
                try:
                    # 7.1.传递会话、可信上下文、已校验参数和幂等范围，调用执行适配函数。
                    # 底层工具创建 QUEUED 人工处理记录；相同幂等范围及参数的重复请求复用原结果。
                    result = definition.execute(
                        session,
                        context,
                        arguments,
                        # 幂等范围绑定操作者、动作 ID 和租户，避免不同身份共用同一动作标识。
                        IdempotencyScope(
                            actor_id=context.principal.actor_id,
                            key=action_id,
                            tenant_id=context.tenant_id,
                        ),
                    )
                    # 7.2.提交业务记录及幂等回执；提交时连接中断等情况会标记为结果不明。
                    commit_business_write(session, "escalate_to_human")
                    return result
                # 7.3.单次执行或提交异常时回滚当前事务，并向外抛出以统一分类处理。
                except Exception:
                    session.rollback()
                    raise

            # 8.通过可靠性封装同步调用 attempt，使用配置及控制器实施依赖保护。
            # 标记为 write；按当前实现，写操作及结果不明的操作不会自动重放。
            result = run_with_retry(
                attempt,
                dependency="tool",
                operation_type="write",
                config=resilience_config,
                controller=reliability_controller,
                service_identity="tool:escalate_to_human",
            )
        # 9.写入结果不明时清理会话事务，并在执行审计已开始时记录 unknown 事件。
        # 回滚不能证明服务端没有提交，因此返回 no_replay，禁止盲目重复创建任务。
        except UnknownWriteOutcomeError:
            session.rollback()
            if execution_started and audit_repository is not None and clock is not None:
                record_execution_event(
                    state,
                    audit_repository,
                    clock,
                    status="unknown",
                    failure_category=AgentErrorCategory.UNKNOWN_WRITE_OUTCOME.value,
                )
            return {
                "error_category": AgentErrorCategory.UNKNOWN_WRITE_OUTCOME,
                "last_error": "The escalation outcome could not be confirmed.",
                "failure_category": "tool_timeout",
                "recovery_action": "no_replay",
                # 明确标记结果不明，供后续回复及恢复逻辑区分普通失败。
                "write_outcome_unknown": True,
                "tool_execution_status": "failed",
            }
        # 10.处理资源不存在、归属不符等工具错误：分类、回滚，按条件记录失败审计。
        # 返回工具错误说明和失败分类，由 respond 生成用户回复。
        except ToolError as error:
            classification = classify_runtime_error(error, source=RuntimeFailureSource.TOOL)
            session.rollback()
            if execution_started and audit_repository is not None and clock is not None:
                record_execution_event(
                    state,
                    audit_repository,
                    clock,
                    status="failure",
                    failure_category=classification.category.value,
                )
            return {
                "error_category": classification.category,
                "last_error": str(error),
                "failure_category": classification.failure_category,
                "tool_execution_status": "failed",
            }
        # 11.其余异常统一分类；按 execution_started 标记回滚及记录失败审计。
        # 返回固定失败说明，不把任意底层异常文本直接交给用户。
        except Exception as error:
            classification = classify_runtime_error(error, source=RuntimeFailureSource.TOOL)
            if execution_started:
                session.rollback()
            if execution_started and audit_repository is not None and clock is not None:
                record_execution_event(
                    state,
                    audit_repository,
                    clock,
                    status="failure",
                    failure_category=classification.category.value,
                )
            return {
                "error_category": classification.category,
                "last_error": "The human escalation path could not be completed.",
                "failure_category": classification.failure_category,
                "tool_execution_status": "failed",
            }
        # 12.业务提交成功后，审计依赖可用时单独记录 success 事件。
        if audit_repository is not None and clock is not None:
            # 12.1.成功审计放在上述执行异常处理之外，避免将已提交业务误判为可重放失败。
            # Keep post-commit audit failure outside the execution exception handlers so it
            # cannot be mistaken for a safely replayable business failure.
            record_execution_event(state, audit_repository, clock, status="success")
        return {
            # 13.序列化业务结果并返回状态更新；executed 仅表示人工处理记录创建成功。
            # 同时清除错误字段，随后由 LangGraph 合并更新并进入 respond。
            "tool_result": serialise_result(result),
            "tool_execution_status": "executed",
            "error_category": None,
            "last_error": None,
        }

    return execute_human_escalation
