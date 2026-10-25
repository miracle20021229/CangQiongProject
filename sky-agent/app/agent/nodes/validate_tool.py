from pydantic import ValidationError as PydanticValidationError
from app.agent.schemas import AgentErrorCategory
from app.agent.state import AgentState
from app.agent.tool_catalog import get_agent_tool_definition


def validate_tool(state: AgentState) -> AgentState:
    """校验工具、可信客户上下文和输入参数，返回 AgentState 的增量更新。

    LangGraph 从 select_tool_or_response 分支进入本节点，同步完成校验，不执行业务工具。
    返回后由 _after_validation 选择回复、策略检查或用户确认后的策略重验分支。
    """
    # 1.读取编译器选定的工具；没有工具或已有错误时，不再校验，返回空字典保留状态。
    tool_name = state.get("selected_tool")
    if not tool_name or state.get("error_category") is not None:
        return {}
    # 2.按工具名查询目录，取得输入模型和执行定义；这里只使用输入模型。
    definition = get_agent_tool_definition(tool_name)
    # 2.1.工具未注册或缺少执行定义时，记录错误，后续由路由函数转 respond。
    if definition is None:
        return {
            "last_error": f"Tool {tool_name} is not registered.",  # 错误说明，供回复和追踪使用。
            "error_category": AgentErrorCategory.UNKNOWN_TOOL,  # 错误分类，供路由及回复判断。
            "target_validation_status": "rejected",  # 标记本次校验未通过。
        }
    # 3.读取服务端提供的执行上下文，取得可信客户身份。
    context = state.get("execution_context")
    # 3.1.缺少认证上下文时拒绝继续，避免仅凭工具参数中的客户 ID 处理请求。
    if context is None:
        return {
            "last_error": "Authenticated execution context is required.",
            "error_category": AgentErrorCategory.POLICY_DENIED,
            "target_validation_status": "rejected",  # 标记本次校验未通过。
        }
    # 4.复制工具参数后再处理，避免直接修改共享状态中原有的参数字典。
    raw_arguments = dict(state.get("tool_arguments", {}))
    requested_customer = raw_arguments.get("customer_id")
    # 4.1.参数声明的客户与可信上下文不一致时拒绝；这里尚未查询资源的实际归属。
    if requested_customer is not None and requested_customer != context.effective_customer_id:
        return {
            "last_error": "The selected resource does not belong to this customer.",
            "error_category": AgentErrorCategory.OWNERSHIP_VIOLATION,
            "target_validation_status": "rejected",  # 标记本次校验未通过。
        }
    # 4.2.输入模型包含 customer_id 时，用可信客户 ID 补齐或覆盖，身份由服务端确定。
    if "customer_id" in definition.input_model.model_fields:
        raw_arguments["customer_id"] = context.effective_customer_id
    # 5.同步调用该工具的 Pydantic 输入模型，检查必填字段、类型及模型定义的约束。
    try:
        arguments = definition.input_model.model_validate(raw_arguments)
    except PydanticValidationError:
        # 5.1.参数校验异常转换为状态中的错误，交给路由及回复节点处理。
        return {
            "last_error": "The selected tool arguments were invalid.",
            "error_category": AgentErrorCategory.INVALID_TOOL_ARGUMENTS,
            "target_validation_status": "rejected",  # 标记本次校验未通过。
        }
    # 6.把校验后的模型转换为适合 JSON 的字典，供后续策略检查和工具执行使用。
    arguments_data = arguments.model_dump(mode="json")
    # 7.返回规范化参数和校验通过标记；validated 不代表策略允许或业务执行成功。
    return {"tool_arguments": arguments_data, "target_validation_status": "validated"}
