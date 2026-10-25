import time
from collections.abc import Callable
from uuid import uuid4
from app.agent.errors import RuntimeFailureSource, classify_runtime_error
from app.agent.schemas import AgentErrorCategory
from app.agent.state import AgentState
from app.observability.metrics import get_metrics
from app.observability.tracing import span
from app.policies.confirmation import Clock
from app.policies.engine import PolicyEngine
from app.policies.models import PolicyAuditEvent, PolicyOutcome, stable_policy_event_id
from app.policies.repository import PolicyAuditRepository, append_policy_audit
from app.resilience.errors import FailureCategory


def make_evaluate_policy_node(
    engine: PolicyEngine, audit_repository: PolicyAuditRepository, clock: Clock
) -> Callable[[AgentState], AgentState]:
    def evaluate_policy(state: AgentState) -> AgentState:
        """同步评估工具操作的策略，记录审计并返回 AgentState 的增量更新。

        使用外层注入的策略引擎、审计仓库和时钟；本节点不执行业务工具。
        返回后进入 inspect_risk，再由 _after_policy 按策略结果选择后续分支。
        """
        # 1.开始计时；outcome 初始值只用于尚未完成策略评估时的耗时统计。
        started = time.perf_counter()
        outcome = "not_evaluated"
        # 2.读取前序节点选定的工具，策略评估需要知道本次准备执行什么操作。
        tool_name = state.get("selected_tool")
        # 2.1.没有工具时记录耗时并返回拒绝错误，不进入策略引擎。
        if not tool_name:
            get_metrics().policy_evaluation_duration_seconds.record(
                time.perf_counter() - started, {"status": outcome}
            )
            return {
                "error_category": AgentErrorCategory.POLICY_DENIED,
                "last_error": "Policy evaluation requires a selected tool.",
            }
        # 3.读取服务端提供的可信执行上下文，包含租户、操作者和有效客户身份。
        context = state.get("execution_context")
        # 3.1.缺少认证上下文时拒绝继续，不能仅凭工具参数判断操作权限。
        if context is None:
            get_metrics().policy_evaluation_duration_seconds.record(
                time.perf_counter() - started, {"status": outcome}
            )
            return {
                "error_category": AgentErrorCategory.POLICY_DENIED,
                "last_error": "Authenticated execution context is required.",
            }
        # 4.建立策略评估的追踪区间，记录工具及评估结果，便于定位耗时和失败。
        with span(
            "policy.evaluate",
            attributes={"tool.name": tool_name},
        ) as policy_span:
            try:
                # 4.1.同步传入工具名、可信身份和已校验参数，取得策略决定。
                # PolicyEngine 调用默认规则，返回允许、需确认、需人工或拒绝。
                decision = engine.evaluate(
                    tool_name=tool_name,
                    context=context,
                    arguments=state.get("tool_arguments", {}),
                )
            except Exception as error:
                # 4.2.策略评估异常时按 fail_closed 处理：记录失败并禁止继续执行。
                outcome = "fail_closed"
                policy_span.set_attribute("policy.outcome", "fail_closed")
                get_metrics().policy_evaluation_duration_seconds.record(
                    time.perf_counter() - started, {"status": outcome}
                )
                # 4.2.1.按策略故障来源分类；返回错误和 deny 恢复动作，交给后续路由处理。
                classification = classify_runtime_error(error, source=RuntimeFailureSource.POLICY)
                return {
                    "error_category": classification.category,
                    "failure_category": FailureCategory.POLICY_FAILURE.value,
                    "recovery_action": "deny",
                    "last_error": "Policy evaluation failed closed.",
                }
            # 4.3.正常取得决定后写入追踪属性；原因最多记录前 10 条。
            policy_span.set_attribute("policy.outcome", decision.outcome.value)
            policy_span.set_attribute("tool.risk_level", decision.risk_level)
            policy_span.set_attribute("policy.reason_codes", decision.reasons[:10])
        # 5.记录到此为止的评估耗时，并按策略结果、风险等级累计决策次数。
        outcome = decision.outcome.value
        get_metrics().policy_evaluation_duration_seconds.record(
            time.perf_counter() - started, {"status": outcome}
        )
        get_metrics().policy_decisions_total.add(
            1,
            {"policy_outcome": decision.outcome.value, "risk_level": str(decision.risk_level)},
        )
        # 6.复用已有动作 ID，否则生成新 ID，关联后续确认、重验和执行阶段。
        action_id = state.get("action_id") or f"act_{uuid4().hex}"
        # 7.同步保存策略审计；数据库实现单独提交，不与后续业务写入共用事务。
        # 审计失败会向外抛出 AuditPersistenceError，不属于上面的策略计算异常分支。
        append_policy_audit(
            audit_repository,
            PolicyAuditEvent(
                # 7.1.按动作 ID、阶段和策略结果生成稳定事件 ID，供持久化仓库去重。
                event_id=stable_policy_event_id(
                    state["agent_run_id"], action_id, "policy_evaluation", decision.outcome.value
                ),
                # 7.2.记录本次运行、请求和会话，以及可信租户、操作者和客户身份。
                agent_run_id=state["agent_run_id"],
                request_id=context.request_id,
                conversation_id=context.conversation_id,
                tenant_id=context.tenant_id,
                actor_id=context.principal.actor_id,
                actor_type=context.principal.actor_type,
                roles=list(context.principal.roles),
                effective_customer_id=context.effective_customer_id,
                # 7.3.记录动作与工具、风险等级、策略结果和原因，便于追查决策依据。
                action_id=action_id,
                tool_name=tool_name,
                risk_level=decision.risk_level,
                policy_outcome=decision.outcome,
                reason_codes=decision.reasons,
                # 7.4.使用注入时钟记录时间，并标明当前是策略评估阶段。
                timestamp=clock.now(),
                stage="policy_evaluation",
                # 7.5.需确认时只记录 required；待确认动作由后续节点创建。
                confirmation_status=(
                    "required" if decision.outcome == PolicyOutcome.REQUIRE_CONFIRMATION else None
                ),
            ),
        )
        # 8.准备状态增量，把完整策略决定和动作 ID 交给后续节点。
        result: AgentState = {"policy_decision": decision, "action_id": action_id}
        # 8.1.策略明确拒绝时补充错误，后续 _after_policy 会选择 respond 分支。
        if decision.outcome == PolicyOutcome.DENY:
            result.update(
                {
                    "error_category": AgentErrorCategory.POLICY_DENIED,
                    "last_error": "Policy denied the selected action.",
                }
            )
        # 8.2.返回更新，由 LangGraph 合并；允许、需确认和需人工由后续路由分别处理。
        return result

    return evaluate_policy
