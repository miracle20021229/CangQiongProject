"""声明工具名称、用途、读写类型和风险等级。

策略引擎读取这里的风险信息；实际输入模型和执行函数在 agent/tool_catalog.py。
"""

from app.tools.base import OperationType, RiskLevel, ToolMetadata

TOOL_REGISTRY: dict[str, ToolMetadata] = {
    # 只读工具：查询客户、订单与工单，不改变业务状态。
    "get_customer": ToolMetadata(
        "get_customer", "Retrieve a customer by ID.", OperationType.READ, RiskLevel.READ_ONLY
    ),
    "get_customer_orders": ToolMetadata(
        "get_customer_orders",
        "List orders for a customer.",
        OperationType.READ,
        RiskLevel.READ_ONLY,
    ),
    "get_order": ToolMetadata(
        "get_order", "Retrieve an order by ID.", OperationType.READ, RiskLevel.READ_ONLY
    ),
    "get_customer_tickets": ToolMetadata(
        "get_customer_tickets",
        "List support tickets for a customer.",
        OperationType.READ,
        RiskLevel.READ_ONLY,
    ),
    "get_ticket": ToolMetadata(
        "get_ticket", "Retrieve a support ticket by ID.", OperationType.READ, RiskLevel.READ_ONLY
    ),
    # 低风险写入：通过策略后可自动创建工单。
    "create_support_ticket": ToolMetadata(
        "create_support_ticket", "Create a support ticket.", OperationType.WRITE, RiskLevel.LOW
    ),
    # 影响客户权益的写入：先确认，再校验，最后执行。
    "cancel_order": ToolMetadata(
        "cancel_order",
        "Cancel an eligible customer order.",
        OperationType.WRITE,
        RiskLevel.CONFIRMATION_REQUIRED,
    ),
    "request_refund": ToolMetadata(
        "request_refund",
        "Request a refund for a delivered order.",
        OperationType.WRITE,
        RiskLevel.CONFIRMATION_REQUIRED,
    ),
    # 转人工走专用节点，创建待人工处理记录。
    "escalate_to_human": ToolMetadata(
        "escalate_to_human",
        "Queue a case for human handling.",
        OperationType.WRITE,
        RiskLevel.HUMAN_HANDLING,
    ),
}


def list_tools() -> list[ToolMetadata]:
    return list(TOOL_REGISTRY.values())


def get_tool(name: str) -> ToolMetadata:
    return TOOL_REGISTRY[name]
