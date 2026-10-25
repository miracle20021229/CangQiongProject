from enum import StrEnum
from typing import Annotated, Any, Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator
from app.memory.schemas import MemoryCandidate
from app.policies.models import PendingAction
from app.rag.schemas import Citation


# 用户意图：18 种业务意图，另有 UNKNOWN 作为兜底。
class Intent(StrEnum):
    """用户意图枚举，表示用户具体想办什么事。

    覆盖客户、订单、工单、政策和记忆等业务，供后续流程选择处理分支。
    无法识别时使用 UNKNOWN。
    """

    CUSTOMER_LOOKUP = "customer_lookup"  # 查询当前客户的信息
    ORDER_LOOKUP = "order_lookup"  # 查询指定订单的详情和状态
    ORDER_LIST = "order_list"  # 查询当前客户的订单列表
    TICKET_LOOKUP = "ticket_lookup"  # 查询指定工单（客服问题记录）
    TICKET_LIST = "ticket_list"  # 查询当前客户的工单列表
    TICKET_CREATE = "ticket_create"  # 创建客服工单，记录待处理的问题
    ORDER_CANCEL = "order_cancel"  # 申请取消订单
    REFUND_REQUEST = "refund_request"  # 申请订单退款
    HUMAN_ESCALATION = "human_escalation"  # 转交人工客服处理
    CAPABILITY_QUESTION = "capability_question"  # 询问客服助手能做什么
    REFUND_POLICY = "refund_policy"  # 咨询退款政策和规则
    CANCELLATION_POLICY = "cancellation_policy"  # 咨询取消订单的政策和规则
    SHIPPING_POLICY = "shipping_policy"  # 咨询发货、配送相关政策
    SUPPORT_FAQ = "support_faq"  # 咨询常见客服问题
    REFUND_ELIGIBILITY = "refund_eligibility"  # 结合订单情况和政策，判断能否退款
    CANCELLATION_EXPLANATION = "cancellation_explanation"  # 结合订单情况和政策，解释取消限制
    MEMORY_REMEMBER = "memory_remember"  # 请求记住用户指定的信息或偏好
    MEMORY_FORGET = "memory_forget"  # 请求删除指定的用户记忆
    UNKNOWN = "unknown"  # 无法识别用户意图时的兜底值


class AgentRequestType(StrEnum):
    """请求处理类型，表示完成需求需要采用哪类处理方式。

    区分信息咨询、业务读写、知识检索、记忆操作和转人工，
    与 Intent 配合描述本轮请求，供后续节点选择处理流程。
    """

    INFORMATIONAL = "informational"  # 信息咨询类请求
    READ_ACTION = "read_action"  # 查询业务数据的只读操作
    WRITE_ACTION = "write_action"  # 创建或修改业务数据的写操作
    ESCALATION = "escalation"  # 转人工客服请求
    UNCLEAR = "unclear"  # 请求类型尚不明确
    KNOWLEDGE_ONLY = "knowledge_only"  # 仅需检索知识并回答
    ACTION_ONLY = "action_only"  # 仅需执行业务操作
    KNOWLEDGE_AND_ACTION = "knowledge_and_action"  # 既需业务操作，也需知识检索
    MEMORY_ACTION = "memory_action"  # 保存或删除用户记忆的请求


class AgentExecutionMode(StrEnum):
    """Explicit playground modes; neither mode grants model output authority.

    显式指定的演示运行模式；任何模式都不会赋予模型输出执行权限。
    """

    RECORDED_REPLAY = "recorded_replay"  # 记录回放模式
    LIVE_PROPOSAL = "live_proposal"  # 实时调用模型生成提案的模式


class ProposalValidationStatus(StrEnum):
    """提案校验状态，说明提案是否已通过系统校验。

    用于展示等待校验、通过、拒绝或未记录状态；
    校验通过不代表业务操作已经执行成功。

    """

    PENDING = "pending"  # 等待校验
    PASSED = "passed"  # 校验通过
    REJECTED = "rejected"  # 校验未通过，提案被拒绝
    NOT_RECORDED = "not_recorded"  # 尚未记录校验结果


class AgentProposal(BaseModel):
    """Bounded proposal projection; raw prompts, reasoning, and tool arguments are excluded.

    仅包含限定字段的提案视图；不包含原始提示词、推理过程和工具参数。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    intent: str  # 识别出的用户意图名称
    suggested_action: str | None = None  # 建议调用的业务工具名称
    extracted_fields: dict[str, str | int | bool] = Field(default_factory=dict)  # 提取的关键字段，如目标类型和订单 ID
    evidence_references: list[str] = Field(default_factory=list)  # 依据材料的引用标识列表
    validation: ProposalValidationStatus = ProposalValidationStatus.NOT_RECORDED  # 提案校验状态，默认未记录


class ProviderRunMetadata(BaseModel):
    """Safe provider metadata; token fields remain absent when the provider omits usage.

    可安全展示的模型服务元数据；服务未提供用量时，不填写 token 字段。
    """

    provider: str  # 模型服务提供方标识
    model: str | None = None  # 本次调用的模型名称
    latency_ms: float | None = Field(default=None, ge=0)  # 模型调用耗时，单位为毫秒
    input_tokens: int | None = Field(default=None, ge=0)  # 输入内容消耗的 token 数
    output_tokens: int | None = Field(default=None, ge=0)  # 输出内容消耗的 token 数
    cost_usd: float | None = Field(default=None, ge=0)  # 本次模型调用费用，单位为美元


class SemanticTarget(BaseModel):
    """A typed user-referent, never an executable tool argument bag.

    带类型的用户指代对象，不能直接作为可执行工具的参数集合。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    type: Literal["explicit_order", "latest_order", "explicit_ticket"]  # 目标类型：指定订单、最新订单或指定工单
    order_id: int | None = Field(default=None, gt=0)  # 指定订单的 ID，填写时必须为正整数
    ticket_id: int | None = Field(default=None, gt=0)  # 指定工单的 ID，填写时必须为正整数

    @model_validator(mode="after")
    def validate_reference_shape(self) -> "SemanticTarget":
        if self.type == "explicit_order" and self.order_id is None:
            raise ValueError("explicit_order requires order_id")
        if self.type == "explicit_ticket" and self.ticket_id is None:
            raise ValueError("explicit_ticket requires ticket_id")
        if self.type == "latest_order" and (
            self.order_id is not None or self.ticket_id is not None
        ):
            raise ValueError("latest_order cannot include an identifier")
        if self.type == "explicit_order" and self.ticket_id is not None:
            raise ValueError("explicit_order cannot include ticket_id")
        if self.type == "explicit_ticket" and self.order_id is not None:
            raise ValueError("explicit_ticket cannot include order_id")
        return self


class SemanticDecision(BaseModel):
    """Provider-facing semantic proposal for the semantic_decision_v2 contract.

    面向模型服务、遵循 semantic_decision_v2 协议的语义提案。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    intent: Intent  # 识别出的用户意图
    request_type: AgentRequestType = AgentRequestType.UNCLEAR  # 请求的处理类型，默认尚不明确
    target: SemanticTarget | None = None  # 用户指向的订单或工单对象
    reason: str = Field(default="", max_length=300)  # 业务处理原因，如退款理由或转人工原因
    category: str | None = Field(default=None, max_length=100)  # 创建客服工单时的问题类别
    description: str | None = Field(default=None, max_length=5000)  # 创建客服工单时的详细问题描述
    priority: str | None = Field(default=None, max_length=20)  # 转人工处理的优先级
    summary: str | None = Field(default=None, max_length=5000)  # 交给人工客服的问题摘要
    clarification_required: bool = False  # 是否需要向用户追问以补全信息
    requires_retrieval: bool = False  # 是否需要检索知识库
    knowledge_query: str | None = Field(default=None, max_length=500)  # 用于检索知识库的查询文本
    memory_candidate: MemoryCandidate | None = None  # 待评估是否保存的用户记忆
    memory_key: str | None = Field(default=None, max_length=64)  # 目标记忆的标识键


class ExplicitOrderTargetV3(BaseModel):
    """Complete explicit order reference exposed directly in the transport schema.

    在传输数据结构中直接声明的完整订单引用，包含明确的订单 ID。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    type: Literal["explicit_order"]  # 目标类型固定为指定订单
    order_id: int = Field(gt=0)  # 指定订单的 ID，必填且必须为正整数


class LatestOrderTargetV3(BaseModel):
    """Symbolic order reference; concrete resolution remains server-owned.

    符号化的订单引用；具体对应哪个订单仍由服务端解析。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    type: Literal["latest_order"]  # 目标类型固定为最新订单，由服务端查找


class ExplicitTicketTargetV3(BaseModel):
    """Complete explicit ticket reference exposed directly in the transport schema.

    在传输数据结构中直接声明的完整工单引用，包含明确的工单 ID。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    type: Literal["explicit_ticket"]  # 目标类型固定为指定工单
    ticket_id: int = Field(gt=0)  # 指定工单的 ID，必填且必须为正整数


SemanticTargetV3 = Annotated[
    ExplicitOrderTargetV3 | LatestOrderTargetV3 | ExplicitTicketTargetV3,
    Field(discriminator="type"),
]  # V3 目标联合类型，根据 type 字段选择具体结构


class SemanticDecisionV3(BaseModel):
    """Provider-facing semantic_decision_v3 proposal with transport-visible targets.

    面向模型服务的 semantic_decision_v3 提案；传输数据结构中明确声明目标对象。
    """

    model_config = ConfigDict(extra="forbid")  # 拒绝接收未声明的额外字段

    intent: Intent  # 识别出的用户意图
    request_type: AgentRequestType = AgentRequestType.UNCLEAR  # 请求的处理类型，默认尚不明确
    target: SemanticTargetV3 | None = None  # 用户指向的订单或工单对象
    reason: str = Field(default="", max_length=300)  # 业务处理原因，如退款理由或转人工原因
    category: str | None = Field(default=None, max_length=100)  # 创建客服工单时的问题类别
    description: str | None = Field(default=None, max_length=5000)  # 创建客服工单时的详细问题描述
    priority: str | None = Field(default=None, max_length=20)  # 转人工处理的优先级
    summary: str | None = Field(default=None, max_length=5000)  # 交给人工客服的问题摘要
    clarification_required: bool = False  # 是否需要向用户追问以补全信息
    requires_retrieval: bool = False  # 是否需要检索知识库
    knowledge_query: str | None = Field(default=None, max_length=500)  # 用于检索知识库的查询文本
    memory_candidate: MemoryCandidate | None = None  # 待评估是否保存的用户记忆
    memory_key: str | None = Field(default=None, max_length=64)  # 目标记忆的标识键


def normalize_semantic_decision(
    decision: SemanticDecision | SemanticDecisionV3,
) -> SemanticDecision:
    """Convert a valid semantic contract proposal into the existing internal representation.

    将符合语义协议的有效提案转换为现有的内部数据结构。
    """

    if isinstance(decision, SemanticDecision):
        return decision
    target: SemanticTarget | None = None
    if isinstance(decision.target, ExplicitOrderTargetV3):
        target = SemanticTarget(type="explicit_order", order_id=decision.target.order_id)
    elif isinstance(decision.target, LatestOrderTargetV3):
        target = SemanticTarget(type="latest_order")
    elif isinstance(decision.target, ExplicitTicketTargetV3):
        target = SemanticTarget(type="explicit_ticket", ticket_id=decision.target.ticket_id)
    payload = decision.model_dump(exclude={"target"})
    return SemanticDecision.model_validate({**payload, "target": target})


class AgentErrorCategory(StrEnum):
    """Agent 的统一错误分类。

    将资源、权限、业务状态、模型和工具等异常归为固定类型，
    便于生成回复、记录故障并决定后续处理方式。
    """

    RESOURCE_NOT_FOUND = "resource_not_found"  # 请求的业务资源不存在
    OWNERSHIP_VIOLATION = "ownership_violation"  # 资源不属于当前用户，禁止访问
    INVALID_STATE = "invalid_state"  # 当前业务状态不允许执行此操作
    DUPLICATE_ACTION = "duplicate_action"  # 重复提交相同操作
    INVALID_TOOL_ARGUMENTS = "invalid_tool_arguments"  # 工具参数不合法或不完整
    UNKNOWN_TOOL = "unknown_tool"  # 工具名称无法识别
    LLM_ERROR = "llm_error"  # 大模型调用或输出处理出错
    TOOL_ERROR = "tool_error"  # 业务工具执行出错
    DEPENDENCY_ERROR = "dependency_error"  # 依赖服务发生异常
    INTERNAL_ERROR = "internal_error"  # 系统内部发生异常
    UNKNOWN_WRITE_OUTCOME = "unknown_write_outcome"  # 无法确认写操作是否已生效
    POLICY_DENIED = "policy_denied"  # 业务策略拒绝执行操作
    RETRIEVAL_ERROR = "retrieval_error"  # 知识检索出错
    RERANKER_ERROR = "reranker_error"  # 检索结果的重排序过程出错
    CONFIRMATION_EXPIRED = "confirmation_expired"  # 待确认操作已过期
    DEPENDENCY_FAILURE = "dependency_failure"  # 依赖服务调用失败


class StructuredDecision(BaseModel):
    """供 Agent 内部流程使用的结构化决策。

    汇总用户意图、工具名称与参数，以及检索和记忆需求，
    供后续节点继续校验、评估策略并选择执行或回复分支。
    """

    intent: Intent  # 识别出的用户意图
    request_type: AgentRequestType  # 请求的处理类型
    tool_name: str | None = None  # 准备调用的业务工具名称
    arguments: dict[str, Any] = Field(default_factory=dict)  # 传给业务工具的参数
    reason: str = Field(default="", max_length=300)  # 本次决策或业务操作的原因
    requires_retrieval: bool = False  # 是否需要检索知识库
    knowledge_query: str | None = Field(default=None, max_length=500)  # 用于检索知识库的查询文本
    memory_candidate: MemoryCandidate | None = None  # 待评估是否保存的用户记忆
    memory_key: str | None = Field(default=None, max_length=64)  # 目标记忆的标识键


class AgentToolCall(BaseModel):
    """单次业务工具调用的结果摘要。

    记录工具名称、执行状态和返回数据，作为 AgentResponse 的一部分返回。
    """

    name: str  # 本次调用的工具名称
    status: str  # 工具执行状态，如 executed（成功）或 failed（失败）
    result: dict[str, object] | None = None  # 工具返回的业务数据


class AgentResponse(BaseModel):
    """Agent 一轮处理结束后返回给调用方的响应。

    汇总用户回复、工具结果、待确认操作和引用依据，
    并附带错误、降级情况及模型调用信息。
    """

    conversation_id: str  # 会话 ID，用于关联多轮对话
    agent_run_id: str  # 本轮 Agent 运行的唯一 ID
    message: str  # 返回给用户的回复内容
    intent: Intent  # 识别出的用户意图
    request_type: AgentRequestType  # 本轮请求的处理类型
    tool_call: AgentToolCall | None = None  # 本轮工具调用及其结果
    pending_action: PendingAction | None = None  # 等待用户确认的操作信息
    decision_reason: str | None = None  # 本轮业务决策的原因
    security_signal: str | None = None  # 检测到的安全风险信号
    error_category: AgentErrorCategory | None = None  # 本轮发生的错误类型
    citations: list[Citation] = Field(default_factory=list)  # 回答所引用的知识来源
    failure_category: str | None = None  # 失败原因分类，用于异常与降级说明
    degraded_components: list[str] = Field(default_factory=list)  # 已降级处理的组件名称列表
    recovery_action: str | None = None  # 异常后的处理方式，如澄清或降级
    write_outcome_unknown: bool = False  # 是否无法确认写操作的最终结果
    execution_mode: AgentExecutionMode = AgentExecutionMode.RECORDED_REPLAY  # 本轮实际采用的运行模式
    provider: str = "recorded_evidence"  # 结果来源：模型服务或记录回放
    model: str | None = None  # 本轮使用的模型名称
    fallback_message: str | None = None  # 实时模型不可用时的回退说明
    proposal: AgentProposal | None = None  # 供展示的受限提案信息
    provider_metadata: ProviderRunMetadata | None = None  # 模型调用的耗时、用量和费用等元数据


class AgentChatRequest(BaseModel):
    """调用 Agent 对话接口时提交的请求数据。

    携带会话标识、客户 ID、用户消息和运行模式，
    并通过字段约束校验输入格式。
    """

    conversation_id: str = Field(min_length=1, max_length=200)  # 会话 ID，用于关联多轮对话
    customer_id: int = Field(gt=0)  # 当前客户 ID，必须为正整数
    message: str = Field(min_length=1, max_length=5000)  # 用户本次发送的消息
    execution_mode: AgentExecutionMode = AgentExecutionMode.RECORDED_REPLAY  # 请求采用的运行模式，默认记录回放
