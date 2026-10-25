"""依据工具风险和登录身份，确定操作的执行条件。

PolicyEngine 调用这里，图节点再按结果选择执行、确认、拒绝或人工处理。
本模块只做决策，不调用工具或修改业务数据。
"""

from app.core.context import ExecutionContext
from app.policies.models import PolicyDecision, PolicyOutcome
from app.tools import registry


def evaluate_default_policy(
    tool_name: str,
    context: ExecutionContext,
    arguments: dict[str, object],
) -> PolicyDecision:
    # 第一层：工具必须已注册，且客户身份有效。
    metadata = registry.TOOL_REGISTRY.get(tool_name)
    if metadata is None:
        return PolicyDecision(
            outcome=PolicyOutcome.DENY,
            tool_name=tool_name,
            risk_level=-1,
            reasons=["unknown_tool"],
            required_conditions=[],
        )
    customer_id = context.effective_customer_id
    if customer_id <= 0:
        return PolicyDecision(
            outcome=PolicyOutcome.DENY,
            tool_name=tool_name,
            risk_level=int(metadata.risk_level),
            reasons=["known_customer_required"],
            required_conditions=["authenticated_customer"],
        )
    # 第二层：工具参数中的客户不能越过当前登录身份的范围。
    requested_customer = arguments.get("customer_id")
    if requested_customer is not None and requested_customer != customer_id:
        return PolicyDecision(
            outcome=PolicyOutcome.DENY,
            tool_name=tool_name,
            risk_level=int(metadata.risk_level),
            reasons=["ownership_required"],
            required_conditions=["customer_ownership"],
        )
    risk_level = int(metadata.risk_level)
    if risk_level <= 1:
        # 只读查询和低风险写入（如建工单）可直接进入执行节点。
        return PolicyDecision(
            outcome=PolicyOutcome.ALLOW,
            tool_name=tool_name,
            risk_level=risk_level,
            reasons=["risk_policy_allows_automatic_execution"],
            required_conditions=[],
        )
    if risk_level == 2:
        # 退款、取消订单等操作先等待明确确认，再重验执行条件。
        return PolicyDecision(
            outcome=PolicyOutcome.REQUIRE_CONFIRMATION,
            tool_name=tool_name,
            risk_level=risk_level,
            reasons=["customer_impacting_write"],
            required_conditions=["explicit_confirmation", "pre_execution_revalidation"],
        )
    # 更高风险交给专用人工处理路径。
    return PolicyDecision(
        outcome=PolicyOutcome.REQUIRE_HUMAN,
        tool_name=tool_name,
        risk_level=risk_level,
        reasons=["human_controlled_action"],
        required_conditions=["dedicated_human_path"],
    )
