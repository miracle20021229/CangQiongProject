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
from app.resilience.errors import ResilienceError, RetryExhaustedError, UnknownWriteOutcomeError
from app.resilience.retry import run_with_retry
from app.services.idempotency import IdempotencyScope, commit_business_write
from app.tools import registry
from app.tools.base import ToolError


def make_execute_tool_node(
    session: Session,
    resilience_config: ResilienceConfig | None = None,
    audit_repository: PolicyAuditRepository | None = None,
    clock: Clock | None = None,
    reliability_controller: ReliabilityController | None = None,
) -> Callable[[AgentState], AgentState]:
    """注入数据库会话、重试配置和审计依赖，构造同步执行工具的节点函数。

    confirmed.py 的 make_confirmed_execution_node 调用本工厂，并在包装节点中执行返回函数。
    """
    def execute_tool(state: AgentState) -> AgentState:
        """校验工具与客户范围，调用真实业务工具，返回 AgentState 的增量更新。

        使用外层注入的依赖；正常返回后由包装节点处理确认状态，再由图路由决定检索或回复。
        """
        # 1.读取前序节点选定的工具；没有工具时返回失败状态。
        tool_name = state["selected_tool"]
        if tool_name is None:
            return {
                "last_error": "No tool was selected.",
                "error_category": AgentErrorCategory.INVALID_TOOL_ARGUMENTS,
                "tool_execution_status": "failed",
            }
        # 2.按工具名查找参数模型和执行函数；未注册的工具不能执行。
        definition = get_agent_tool_definition(tool_name)
        if definition is None:
            return {
                "last_error": f"Tool {tool_name} is not registered.",
                "error_category": AgentErrorCategory.UNKNOWN_TOOL,
                "tool_execution_status": "failed",
            }
        # 3.读取服务端提供的可信执行上下文，缺少认证身份时拒绝执行。
        context = state.get("execution_context")
        if context is None:
            return {
                "last_error": "Authenticated execution context is required.",
                "error_category": AgentErrorCategory.POLICY_DENIED,
                "tool_execution_status": "failed",
            }
        # 4.读取操作类型，供后续判断是否需要幂等、提交事务以及是否允许重试。
        operation_type = registry.get_tool(tool_name).operation_type.value
        # 标记 attempted 审计是否已成功记录，不代表业务工具已经调用或写入成功。
        execution_started = False
        try:
            # 5.执行前再次校验参数字典，转换为该工具要求的 Pydantic 输入对象。
            arguments = definition.input_model.model_validate(state.get("tool_arguments", {}))
            # 5.1.参数中的客户必须与可信上下文一致，避免跨客户执行操作。
            requested_customer = getattr(arguments, "customer_id", None)
            if requested_customer != context.effective_customer_id:
                return {
                    "last_error": "Tool customer scope conflicts with execution context.",
                    "error_category": AgentErrorCategory.OWNERSHIP_VIOLATION,
                    "tool_execution_status": "failed",
                }
            # 6.写操作准备幂等身份；只读操作传入 None。
            idempotency = None
            if operation_type == "write":
                # 6.1.复用前序生成的动作 ID 作为幂等键，缺失时禁止业务写入。
                action_id = state.get("action_id")
                if not action_id:
                    return {
                        "last_error": "Business write is missing its idempotency key.",
                        "error_category": AgentErrorCategory.POLICY_DENIED,
                        "tool_execution_status": "failed",
                    }
                # 6.2.组合操作者、动作 ID 和租户，交给底层业务工具识别重复请求。
                idempotency = IdempotencyScope(
                    actor_id=context.principal.actor_id,
                    key=action_id,
                    tenant_id=context.tenant_id,
                )
                # 6.3.注入审计依赖时，先同步记录执行尝试；审计失败会阻止工具调用。
                # 数据库审计单独提交，不与后面的业务写入组成同一事务。
                if audit_repository is not None and clock is not None:
                    record_execution_event(
                        state,
                        audit_repository,
                        clock,
                        status="attempted",
                    )
                    execution_started = True

            # 7.封装一次执行过程，交给 run_with_retry 同步调用。
            def attempt() -> object:
                """执行一次真实工具调用；写操作提交事务，异常时回滚并继续抛出。"""
                try:
                    # 7.1.经 tool_catalog 适配函数调用 app/tools，传入会话、身份、参数和幂等范围。
                    result = definition.execute(session, context, arguments, idempotency)
                    # 7.2.工具返回后提交业务写事务；提交超时或连接异常会标记为写入结果未知。
                    if operation_type == "write":
                        commit_business_write(session, tool_name)
                    return result
                except Exception:
                    # 7.3.本次执行异常时清理事务，再交给重试器或外层异常分支处理。
                    session.rollback()
                    raise

            # 8.按配置执行可靠性控制；可重试的只读故障允许有限重试，写操作不自动重放。
            # service_identity 按工具区分熔断、并发隔离等状态；这里等待执行结果返回。
            try:
                result = run_with_retry(
                    attempt,
                    dependency="tool",
                    operation_type=operation_type,
                    config=resilience_config,
                    controller=reliability_controller,
                    service_identity=f"tool:{tool_name}",
                )
            except UnknownWriteOutcomeError:
                # 9.1.写入结果无法确认时记录 unknown，返回 no_replay，禁止按失败操作重放。
                # rollback 只能清理本地事务，不能据此断定数据库没有完成提交。
                if execution_started and audit_repository is not None and clock is not None:
                    record_execution_event(
                        state,
                        audit_repository,
                        clock,
                        status="unknown",
                        failure_category=AgentErrorCategory.UNKNOWN_WRITE_OUTCOME.value,
                    )
                return {
                    "last_error": "The write outcome could not be confirmed.",
                    "error_category": AgentErrorCategory.UNKNOWN_WRITE_OUTCOME,
                    "failure_category": "tool_timeout",
                    "recovery_action": "no_replay",
                    "write_outcome_unknown": True,
                    "tool_execution_status": "failed",
                }
            except RetryExhaustedError as error:
                # 9.2.重试次数或时间预算耗尽：分类并记录失败，返回安全终止状态。
                classification = classify_runtime_error(error, source=RuntimeFailureSource.TOOL)
                if execution_started and audit_repository is not None and clock is not None:
                    record_execution_event(
                        state,
                        audit_repository,
                        clock,
                        status="failure",
                        failure_category=classification.category.value,
                    )
                return {
                    "last_error": "The selected dependency could not be reached reliably.",
                    "error_category": classification.category,
                    "failure_category": error.category.value,
                    "recovery_action": "fail_safely",
                    "tool_execution_status": "failed",
                }
            except ResilienceError as error:
                # 9.3.处理熔断、并发隔离等可靠性错误；错误细分原因由 error.category 提供。
                classification = classify_runtime_error(error, source=RuntimeFailureSource.TOOL)
                if execution_started and audit_repository is not None and clock is not None:
                    record_execution_event(
                        state,
                        audit_repository,
                        clock,
                        status="failure",
                        failure_category=classification.category.value,
                    )
                return {
                    "last_error": "The selected dependency could not be reached reliably.",
                    "error_category": classification.category,
                    "failure_category": error.category.value,
                    "recovery_action": "fail_safely",
                    "tool_execution_status": "failed",
                }
            except ToolError as error:
                # 9.4.处理工具明确抛出的业务错误，回滚并保留面向调用方的错误说明。
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
                    "last_error": str(error),
                    "error_category": classification.category,
                    "failure_category": classification.failure_category,
                    "tool_execution_status": "failed",
                }
            except Exception as error:
                # 9.5.其余执行异常统一分类、回滚和记录失败，返回通用错误说明。
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
                    "last_error": "The selected tool could not be executed.",
                    "error_category": classification.category,
                    "failure_category": classification.failure_category,
                    "tool_execution_status": "failed",
                }
            # 10.业务写入已提交后记录成功审计；审计失败继续向外抛出。
            # 此处放在执行异常处理之外，避免把已提交的写入转换成可重放的业务失败。
            if operation_type == "write" and audit_repository is not None and clock is not None:
                # This is intentionally outside the execution exception handlers. If the
                # committed write's success evidence cannot be stored, surface that operational
                # failure without converting it into a replayable business failure.
                record_execution_event(state, audit_repository, clock, status="success")
            # 11.把业务结果转为可保存的字典，标记执行成功并清除旧错误。
            # 返回的是状态增量，由 LangGraph 合并到共享状态，供后续检索或回复使用。
            return {
                "tool_result": serialise_result(result),
                "tool_execution_status": "executed",
                "last_error": None,
                "error_category": None,
            }
        except Exception:
            # 12.参数校验、审计等未被内层转换的异常继续向上传递。
            # 已记录执行尝试时额外回滚；已提交的业务写入不会因此撤销。
            if execution_started:
                session.rollback()
            raise

    # 返回闭包供 confirmed.py 包装调用，构造节点时不会立即执行业务。
    return execute_tool
