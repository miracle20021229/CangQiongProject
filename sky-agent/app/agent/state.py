"""定义图节点之间传递的共享状态。

节点通常只返回变化的字段；除 messages 追加外，其余字段默认覆盖旧值。
阅读时区分：登录身份、模型提议、工具结果、知识证据和跨轮工作流。
"""

import operator
from typing import Annotated, Literal, TypedDict
from app.agent.decision_compiler import CompiledDecision
from app.agent.schemas import (
    AgentErrorCategory,
    AgentExecutionMode,
    AgentProposal,
    AgentRequestType,
    Intent,
    ProviderRunMetadata,
    SemanticDecision,
)
from app.core.context import ExecutionContext
from app.memory.schemas import MemoryCandidate
from app.policies.models import PendingAction, PolicyDecision


class ConversationMessage(TypedDict):
    role: Literal["user", "assistant"]  # 消息发送方：user 表示用户，assistant 表示助手。
    content: str  # 这条消息的文本内容。


WorkflowLifecycleState = Literal[
    "active",  # 任务处理中，可能仍需用户补充信息。
    "waiting_confirmation",  # 动作已准备好，等待用户确认后重新校验并执行。
    "suspended",  # 任务因插话暂时挂起，保留快照供之后恢复。
    "superseded",  # 任务已被新任务替换。
    "completed",  # 任务已结束。
    "cancelled",  # 任务已被取消。
]


class SuspendedWorkflowStatef(TypedDict, total=False):
    """Bounded workflow snapshot; it contains no prompts or hidden reasoning."""

    # 用户插话时保存原任务，之后可恢复；快照不包含模型提示词或隐藏推理。
    intent: Intent  # 被保存任务的业务意图，例如取消订单或申请退款。
    pending_action: PendingAction | None  # 被保存任务中等待用户确认的动作；没有待确认动作时为 None。
    pending_workflow_decision: SemanticDecision | None  # 尚待补齐信息的语义决策，恢复后继续收集参数。
    collected_entities: dict[str, str | int | bool]  # 挂起前已收集的业务信息，例如订单 ID、退款原因。
    tool_arguments: dict[str, object]  # 保存的工具调用参数，供恢复任务时重建执行上下文。
    missing_required_fields: list[str]  # 任务仍缺少的必填信息，用于恢复后继续追问。
    validation_context: dict[str, object]  # 保存的参数与目标校验上下文，供恢复时重新校验。
    policy_inputs: dict[str, object]  # 保存的策略判断输入，用于恢复后比对权限和执行条件。
    source_state: WorkflowLifecycleState  # 快照记录的任务状态，例如等待确认、处理中或已被替换。
    workflow_id: str  # 这份快照所属任务的标识，用于跨轮追踪同一任务。
    superseded_by: str | None  # 替换该任务的新任务 ID；未被替换时为 None。


class AgentState(TypedDict, total=False):
    # 可信身份与会话归属：由服务端填充，模型无权决定。
    execution_context: ExecutionContext  # 服务端提供的本轮执行上下文，包含可信身份、租户和客户范围。
    conversation_id: str  # 当前会话 ID，用于关联多轮消息和会话状态。
    conversation_tenant_id: str  # 已绑定会话的租户 ID，用于检查租户归属是否一致。
    conversation_customer_id: int  # 已绑定会话的客户 ID，用于防止跨客户复用会话。
    conversation_actor_id: str  # 已绑定会话的操作者 ID，用于检查请求者是否一致。
    conversation_actor_type: str  # 已绑定会话的操作者类型，例如客户或客服人员。
    # operator.add 是合并规则：新消息追加到历史，而非替换整段对话。
    messages: Annotated[list[ConversationMessage], operator.add]  # 用户与助手的消息列表；节点返回的新消息会追加到现有列表。
    # 模型提议与编译结果：意图尚不等于可以执行的业务操作。
    intent: Intent  # 当前识别或恢复的业务意图，决定本轮要处理什么需求。
    request_type: AgentRequestType  # 请求的处理类别，例如业务查询、业务写入、知识问答或记忆操作。
    semantic_decision: SemanticDecision | None  # 模型提议或规则构造的语义决策，需经编译和校验后才能执行。
    compile_result: CompiledDecision | None  # 服务端编译语义决策的结果，包含工具、参数或需要追问的信息。
    grounding_status: str  # 业务目标是否有可信依据的校验状态，用于识别无依据的目标引用。
    grounding_reference_type: str | None  # 目标引用类型，例如指定订单、指定工单或最近一笔订单。
    grounding_trusted_source: str | None  # 目标引用的可信来源，例如当前用户消息；无可信来源时为 None。
    target_validation_status: str  # 业务意图与目标引用是否满足执行要求的校验结果。
    # 工具执行与确认：保存参数、结果，以及等待下一轮确认的动作。
    selected_tool: str | None  # 本轮选中的业务工具名称；尚未选择或无需工具时为 None。
    tool_arguments: dict[str, object]  # 准备交给工具的参数，执行前还需做类型、归属和策略校验。
    tool_result: dict[str, object] | None  # 工具执行后转换为字典的业务结果，供回复或后续知识检索使用。
    pending_action: PendingAction | None  # 等待用户确认的动作，保存执行参数、策略输入和有效期等信息。
    policy_decision: PolicyDecision | None  # 策略引擎给出的处理决定，例如允许、拒绝、要求确认或转人工。
    confirmation_status: str | None  # 本轮对待确认动作的处理状态，例如已确认、拒绝、过期或含糊。
    action_id: str | None  # 本次业务动作的标识，用作写操作幂等键，避免同一动作重复生效。
    # RAG 分支：查询、命中文档、引用及回答依据。
    requires_retrieval: bool  # 本轮是否需要检索知识库，适用于政策问答或业务结果解释。
    knowledge_query: str | None  # 提交给知识检索器的查询文本；无需检索时可为 None。
    retrieved_chunks: list[dict[str, object]]  # 知识库检索命中的文档片段及其相关信息。
    retrieval_metadata: dict[str, object]  # 检索过程的元数据，用于记录检索来源、结果和诊断信息。
    answer_grounding: dict[str, object]  # 回答与检索证据的对应及校验信息，用于判断回答是否有依据。
    knowledge_answer: str | None  # 知识检索与回答生成流程产出的文本，交给回复节点组织输出。
    citations: list[dict[str, object]]  # 回答引用的来源信息，用于说明政策或知识结论的依据。
    # 本轮结果与错误：respond 据此生成最终回复。
    retry_count: int  # 状态中记录的重试计数，供相关节点读取或更新。
    last_error: str | None  # 最近一次处理失败的错误说明；当前没有错误时为 None。
    error_category: AgentErrorCategory | None  # 面向 Agent 流程的错误分类，用于选择错误处理和回复分支。
    decision_reason: str | None  # 本轮决策或处理结果的原因标识，用于解释流程选择。
    final_response: str  # 回复节点生成的最终文本，作为本轮向用户返回的内容。
    agent_run_id: str  # 本次图执行的唯一标识；同一会话每次运行都会生成新的 ID。
    tool_execution_status: str | None  # 工具的执行状态，例如成功或失败，供回复和运行记录使用。
    # 用户记忆：检索出的上下文与本轮新增/删除请求分开存放。
    memory_context: list[dict[str, object]]  # 本轮检索出的用户记忆，作为理解需求和回复时的参考上下文。
    memory_candidate: MemoryCandidate | None  # 准备写入长期记忆的信息候选，需要先经过记忆策略检查。
    memory_key: str | None  # 待操作记忆的键，例如删除某条记忆时用于定位目标。
    memory_operation_status: str | None  # 本轮记忆操作的处理状态，用于组织操作结果或失败回复。
    memory_policy_outcome: str | None  # 记忆策略检查的结果，说明记忆操作是否被允许或拒绝。
    failure_category: str | None  # 依赖调用或可靠性故障的分类，用于选择降级处理和提示。
    degraded_components: list[str]  # 本轮已降级的组件名称列表，例如不可用的记忆服务。
    recovery_action: str | None  # 故障发生后的处理方式，例如忽略记忆继续、结束处理或禁止重放。
    # 写入结果不明时不能盲目重试，需结合幂等记录判断是否已经生效。
    write_outcome_unknown: bool  # 写操作是否结果不明；为 True 时需核查幂等记录，避免直接重试。
    replay_detected: bool  # 是否检测到已处理动作的重复提交或确认重放。
    idempotency_outcome: str | None  # 幂等检查的处理结果，例如已阻止重复执行。
    execution_mode: AgentExecutionMode  # 本轮运行模式，例如记录回放或实时调用模型生成提议。
    provider: str  # 本轮实际使用的模型或决策提供方名称。
    model: str | None  # 本轮使用的模型名称；提供方未提供时可为 None。
    fallback_message: str | None  # 运行模式无法按原计划执行时，用于说明回退情况的提示。
    proposal: AgentProposal | None  # 面向展示的受限提议摘要，包含意图、建议动作和校验状态等信息。
    provider_metadata: ProviderRunMetadata | None  # 提供方的运行信息，例如模型、耗时、用量和可用的费用数据。
    security_signal: str | None  # 本轮发现的安全风险标识，用于阻断处理或生成安全提示。
    memory_security_signal: str | None  # 记忆处理过程中发现的安全风险标识。
    memory_summary_requested: bool  # 用户是否要求查看已记住的信息，用于进入记忆摘要回复分支。
    # 跨轮任务：缺失字段用于追问，挂起快照用于插话后恢复。
    previous_intent: Intent | None  # 此前保存的业务意图，供补充信息或判断任务切换时参考。
    pending_workflow_decision: SemanticDecision | None  # 因缺少信息而暂存的语义决策，等待后续消息补齐后重新编译。
    missing_required_fields: list[str]  # 当前任务缺少的必填字段，决定下一轮需要向用户追问什么。
    collected_entities: dict[str, str | int | bool]  # 当前任务跨轮收集的业务信息，例如订单 ID、原因或描述。
    workflow_active: bool  # 是否存在仍需继续处理的任务，供下一轮决定是否进入恢复流程。
    workflow_resume_status: str | None  # 任务续办的状态，例如已恢复、仍缺信息或需要检查插话。
    workflow_state: WorkflowLifecycleState  # 当前任务的生命周期状态，例如处理中、等待确认、挂起或已完成。
    workflow_interruption_pending: bool  # 是否发现可能打断当前任务的新需求，等待插话处理节点判断。
    workflow_interruption_status: str | None  # 插话处理的结果，例如原任务已挂起、已被替换或已恢复。
    previous_workflow_intent: Intent | None  # 发生任务切换前，原任务正在处理的业务意图。
    interruption_intent: Intent | None  # 触发本次插话或任务切换的新业务意图。
    workflow_resume_source: str | None  # 触发任务恢复的来源，例如用户明确要求继续之前的任务。
    suspended_workflow: SuspendedWorkflowState | None  # 暂时挂起、之后仍可恢复的原任务快照。
    superseded_workflow: SuspendedWorkflowState | None  # 已被新任务替换的旧任务快照，用于记录任务切换情况。
    workflow_id: str | None  # 当前任务 ID，用于跨轮关联同一业务任务；尚未建立时可为 None。
    previous_workflow_id: str | None  # 任务切换前的任务 ID，用于关联前后两个任务。
    superseded_by: str | None  # 替换旧任务的新任务 ID，用于记录任务之间的替代关系。
    workflow_transition: str | None  # 本次生命周期变化的标识，例如从挂起状态恢复。
    workflow_interruption_type: str | None  # 任务打断或恢复的类型，用于区分插话、替换和显式恢复等情形。
    workflow_tool_arguments: dict[str, object]  # 为跨轮任务保留的工具参数，挂起时会复制到任务快照。
    workflow_validation_context: dict[str, object]  # 为跨轮任务保留的校验上下文，供挂起和恢复时使用。
    workflow_policy_inputs: dict[str, object]  # 为跨轮任务保留的策略输入，供恢复后重新检查执行条件。
    pending_action_restored: bool  # 是否已从待确认动作中成功恢复执行所需的状态。
    restored_fields_count: int  # 本次恢复的有效字段数量，用于记录恢复过程的完整程度。
    compilation_resumed: bool  # 恢复出的状态是否可以重新进入语义决策编译流程。
    # 确认后比对恢复前后的策略输入，检查待执行动作是否发生变化。
    original_policy_inputs_hash: str  # 原待确认动作的策略输入哈希，用作恢复后的比对基准。
    restored_policy_inputs_hash: str  # 恢复后重新构造的策略输入哈希，用于检测输入是否变化。
    policy_input_diff: str  # 原始与恢复后策略输入的差异 JSON，只记录变化的字段路径。
    original_pending_policy_inputs: str  # 原待确认动作的策略输入序列化文本，用于比对和运行追踪。
    restored_policy_inputs: str  # 恢复后重新构造的策略输入序列化文本。
    original_policy_inputs_normalized: str  # 原始策略输入的规范化文本，用于稳定计算哈希和比较。
    restored_policy_inputs_normalized: str  # 恢复后策略输入的规范化文本，用于与原始输入比较。
    policy_revalidation_stage: str  # 用户确认后重新校验所处的阶段，例如输入比对或业务状态检查。
    policy_revalidation_result: str  # 重新校验的结果，例如允许执行、输入不一致或策略拒绝。
