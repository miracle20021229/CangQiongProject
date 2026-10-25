# 订单查询：两轮调试日志阅读记录

- 整理日期：2026-10-13（Asia/Singapore，UTC+08:00）。
- 日志显示开始时间：20:17；测试结果：`1 passed, 10 warnings in 3.01s`；退出码：`0`。
- 测试入口：[test_order_lookup_workflow_resumes_when_order_id_arrives](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/tests/test_agent.py:792)。
- 环境：项目 `.venv\Scripts\python.exe`，通过 PyCharm 的 pytest 调试运行。
- 材料：用户提供的控制台日志；末尾完整保留原文。本次整理没有重新运行测试。

## 1. 这次测试做了什么

同一会话运行两轮：第一轮用户说 `Can you check my order?`，没有提供订单号；第二轮只发送 `2`，系统继续上轮查询并执行 `get_order`。

日志记录了 **2 轮、20 次节点调用**：第一轮 8 次，第二轮 12 次。第二轮出现 `resume_workflow`，没有再次经过 `understand_request`。

测试使用 `FakeSemanticDecisionV3Provider`。测试通过，结合当前测试断言，可以确认这个测试中的第二轮工具名为 `get_order`、执行状态为 `executed`，两轮合计调用假 Provider 一次。这不等于验证了真实模型或线上服务。

> 日志只打印 `sorted(result.keys())`：它展示每个节点返回的状态更新字段名，没有展示字段值，也没有展示全部读取字段。下文中的具体值和原因标为“结合源码解释”，不能把它们当作日志原文。`error_category` 出现在列表中也不代表出错，其值可能是 `None`。10 条警告的详细内容未提供，无法据此判断来源。

## 2. 对照流程图看实际路径

日志名 `route_request` 对应整合图里的 `select_tool_or_response`，它不是另一个额外节点。

### 第一轮：缺少订单号，保存待补充流程并追问

```text
START
→ load_context
→ security_boundary
→ retrieve_memory
→ check_pending_action
→ understand_request
→ compile_decision
→ select_tool_or_response（日志名 route_request）
→ respond
→ END
```

| 顺序 | 日志节点 | 职责（结合当前源码） | 返回字段数 | 重点关注 |
|---|---|---|---:|---|
| 1 | `load_context` | 加载并核对执行身份，清理本轮临时状态 | 49 | execution_context、会话归属；本轮 selected_tool、semantic_decision 等被重置 |
| 2 | `security_boundary` | 安全边界检查 | 1 | security_signal |
| 3 | `retrieve_memory` | 获取可用记忆上下文 | 1 | memory_context |
| 4 | `check_pending_action` | 识别确认、拒绝等后续处理状态 | 1 | confirmation_status |
| 5 | `understand_request` | 通过假 Provider 获取语义决策 | 4 | semantic_decision、provider_metadata |
| 6 | `compile_decision` | 校验并编译决策，更新工具参数或待补充流程 | 27 | compile_result、selected_tool、tool_arguments、missing_required_fields、workflow_active |
| 7 | `route_request` | 图中 select_tool_or_response 对应的节点 | 2 | 第一轮清空工具参数；第二轮返回空增量 |
| 8 | `respond` | 形成回复并追加消息，更新工作流生命周期状态 | 3 | final_response、messages、workflow_state |

结合源码解释：

1. 假 Provider 给出查询订单意图，目标编号尚未提供。
2. 编译结果需要澄清，缺失字段为 `order_id`；节点保存原决策与待补充状态。
3. `select_tool_or_response` 清空工具名和参数；`_after_selection()` 读取 `compile_result.status`，遇到 `CLARIFICATION_REQUIRED` 就选择 `respond`。
4. `respond` 组织追问，本轮结束，没有经过工具校验和执行。

源码入口：[编译结果与待补充状态](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/nodes/compile_decision.py:112)；[实际选择下一节点的路由](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:117)。

### 第二轮：补充编号，恢复流程并执行查询

```text
START
→ load_context
→ security_boundary
→ retrieve_memory
→ check_pending_action
→ resume_workflow
→ compile_decision
→ select_tool_or_response（日志名 route_request）
→ validate_tool
→ evaluate_policy
→ inspect_risk
→ execute_tool
→ respond
→ END
```

| 顺序 | 日志节点 | 职责（结合当前源码） | 返回字段数 | 重点关注 |
|---|---|---|---:|---|
| 1 | `load_context` | 加载并核对执行身份，清理本轮临时状态 | 49 | execution_context、会话归属；本轮 selected_tool、semantic_decision 等被重置 |
| 2 | `security_boundary` | 安全边界检查 | 1 | security_signal |
| 3 | `retrieve_memory` | 获取可用记忆上下文 | 1 | memory_context |
| 4 | `check_pending_action` | 识别确认、拒绝等后续处理状态 | 1 | confirmation_status |
| 5 | `resume_workflow` | 把本轮补充信息合入上一轮决策 | 8 | semantic_decision、collected_entities、missing_required_fields、workflow_resume_status |
| 6 | `compile_decision` | 校验并编译决策，更新工具参数或待补充流程 | 27 | compile_result、selected_tool、tool_arguments、missing_required_fields、workflow_active |
| 7 | `route_request` | 图中 select_tool_or_response 对应的节点 | 0 | 第一轮清空工具参数；第二轮返回空增量 |
| 8 | `validate_tool` | 校验工具及参数 | 2 | target_validation_status、tool_arguments |
| 9 | `evaluate_policy` | 评估执行策略 | 2 | action_id、policy_decision |
| 10 | `inspect_risk` | 当前实现返回空增量，后续路由读取已有策略结果 | 0 | 无返回字段 |
| 11 | `execute_tool` | 执行业务工具，整理执行结果或错误 | 4 | tool_execution_status、tool_result、error_category、last_error |
| 12 | `respond` | 形成回复并追加消息，更新工作流生命周期状态 | 3 | final_response、messages、workflow_state |

结合源码解释：

1. 第一轮保存的待补充字段和原决策继续存在；`load_context` 重置本轮临时结果，没有清空这条待补充流程。
2. `_after_pending()` 根据确认状态和 `workflow_active` 选择 `resume_workflow`。
3. `resume_workflow` 用正则解析最新消息 `2`，生成目标 `explicit_order`、编号 `order_id=2`，并更新原决策。
4. `compile_decision` 再次编译，得到查询工具及参数；后续依次完成工具校验、策略评估与执行。
5. 第二轮没有进入 `understand_request`，无需重新让假 Provider 判断意图。

源码入口：[后续消息分流](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:65)；[补号与恢复决策](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/nodes/resume_workflow.py:16)。

## 3. 重点字段如何变化（结合源码解释）

这张表供断点核对使用，具体值不是控制台直接打印的结果。

| 字段 | 第一轮 compile_decision 返回后 | 第二轮 resume_workflow 返回后 | 第二轮 compile_decision 返回后 |
|---|---|---|---|
| `semantic_decision.target` | 原决策无目标；编译节点本身不返回此字段 | `explicit_order`，`order_id=2` | 保留补全后的目标 |
| `missing_required_fields` | `["order_id"]` | `[]` | `[]` |
| `pending_workflow_decision` | 保存第一轮原决策 | 此节点不更新它，暂时保留 | `None` |
| `collected_entities` | 未收集到订单号 | 包含 `order_id=2` | 包含 `order_id=2` |
| `workflow_active` | `True` | `False` | `False` |
| `workflow_resume_status` | `None` | `"resumed"` | `None` |
| `selected_tool` | 无可执行工具 | 此节点不设置工具 | `"get_order"` |
| `tool_arguments` | 无可执行查询参数 | 此节点不设置工具参数 | 包含订单号 2 与当前客户范围 |

`workflow_active=False` 在这里表示收集信息的流程已补齐，不表示工具已经执行。`workflow_resume_status="resumed"` 也只是阶段性状态，后面的编译节点会将它重置为 `None`。

## 4. 空字段列表是什么意思

- 第二轮 `route_request` 返回 `[]`：节点实际返回空字典，表示无需更新状态；已经编译好的工具名和参数继续保留，随后由 `_after_selection()` 判断去向。
- `inspect_risk` 返回 `[]`：当前 `_inspect_risk()` 实现就是 `return {}`；后续 `_after_policy()` 使用此前产生的 `policy_decision` 分流。

因此，空更新不等于节点没执行、状态被清空或没有后续处理。节点返回的字典由图合并到状态中，未包含的普通字段继续保留；`messages` 等设置了 reducer 的字段按各自合并规则处理。

## 5. 下一次打断点看哪里

| 观察目标 | 断点位置 | 条件或关注字段 |
|---|---|---|
| 第一轮为何追问 | [graph.py](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:286) | 条件 `name == "compile_decision"`；观察编译结果、缺失字段与待补充决策 |
| 第二轮如何补号 | [graph.py](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:286) | 条件 `name == "resume_workflow"`；观察输入 `2` 与返回的新目标 |
| 只看第二轮的编译 | [graph.py](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:286) | 条件 `name == "compile_decision" and state.get("workflow_resume_status") == "resumed"` |
| 工具怎样进入业务函数 | [graph.py](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/graph.py:453) | 条件 `node_name == "execute_tool"`；关注工具定义、输入模型和执行上下文 |

第 286 行位于非工具分支。工具执行会经过 `_instrument_tool_node()`，要在第 453 行的 `result = node(state)` 步入。

按 F7 跟进当前查询的调用链：

```text
confirmed.py 的 execute_confirmed
→ execute_tool.py 的 execute_tool
→ run_with_retry(attempt, ...)
→ attempt() 中的 definition.execute(...)
→ tool_catalog._get_order
→ _scoped
→ tools/orders.py 的 get_order
```

入口：[执行包装](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/nodes/confirmed.py:15)；[业务工具执行](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/nodes/execute_tool.py:27)；[订单工具适配](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/tool_catalog.py:93)；[绑定客户范围](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/agent/tool_catalog.py:179)；[查询订单](D:/develop/CangQiongProject/资料/day01/agentic-customer-service-platform/app/tools/orders.py:42)。

`_scoped()` 校验参数中的客户 ID 与 `execution_context.effective_customer_id` 一致，并返回绑定当前客户的参数副本；业务查询继续核对订单归属与租户范围。

## 6. 看懂后的自测

1. 第一轮哪个字段让路由选择 `respond`？
2. 哪些状态保留了第一轮尚未完成的查询？
3. 第二轮的 `2` 由谁解析，如何补入原决策？
4. 为什么第二轮不用再次调用 Provider？
5. 第二轮 `route_request` 返回空字典，工具名为什么没有丢失？
6. `get_order` 最终使用的客户身份从哪里来，如何防止越权？

能结合断点回答这些问题后，再调试取消订单测试，对比补充字段与用户确认的区别。

## 附录：原始日志

以下原样保留用户提供的文本；换行统一为 Markdown 文件的 LF。

```text
D:\develop\CangQiongProject\资料\day01\agentic-customer-service-platform\.venv\Scripts\python.exe -X pycache_prefix=C:\Users\Administrator\AppData\Local\JetBrains\PyCharm2026.1\cpython-cache "D:/develop/JetBrains/PyCharm 2026.1/plugins/python-ce/helpers/pydev/pydevd.py" --multiprocess --qt-support=auto --client 127.0.0.1 --port 62448 --file "D:/develop/JetBrains/PyCharm 2026.1/plugins/python-ce/helpers/pycharm/_jb_pytest_runner.py" --target tests/test_agent.py::test_order_lookup_workflow_resumes_when_order_id_arrives 
Testing started at 20:17 ...
已连接到 pydev 调试器(内部版本号 261.22158.340)Launching pytest with arguments tests/test_agent.py::test_order_lookup_workflow_resumes_when_order_id_arrives --no-header --no-summary -q in D:\develop\CangQiongProject\资料\day01\agentic-customer-service-platform

============================= test session starts =============================
collecting ... collected 1 item

tests/test_agent.py::test_order_lookup_workflow_resumes_when_order_id_arrives 

======================= 1 passed, 10 warnings in 3.01s ========================
PASSED [100%]【load_context】写入字段: ['agent_run_id', 'answer_grounding', 'citations', 'compilation_resumed', 'compile_result', 'confirmation_status', 'conversation_actor_id', 'conversation_actor_type', 'conversation_customer_id', 'conversation_tenant_id', 'degraded_components', 'error_category', 'failure_category', 'grounding_reference_type', 'grounding_status', 'grounding_trusted_source', 'idempotency_outcome', 'interruption_intent', 'knowledge_answer', 'knowledge_query', 'last_error', 'memory_candidate', 'memory_context', 'memory_key', 'memory_operation_status', 'memory_policy_outcome', 'memory_security_signal', 'memory_summary_requested', 'pending_action_restored', 'policy_decision', 'recovery_action', 'replay_detected', 'requires_retrieval', 'restored_fields_count', 'retrieval_metadata', 'retrieved_chunks', 'retry_count', 'selected_tool', 'semantic_decision', 'target_validation_status', 'tool_arguments', 'tool_execution_status', 'tool_result', 'workflow_interruption_pending', 'workflow_interruption_status', 'workflow_interruption_type', 'workflow_resume_source', 'workflow_transition', 'write_outcome_unknown']
【security_boundary】写入字段: ['security_signal']
【retrieve_memory】写入字段: ['memory_context']
【check_pending_action】写入字段: ['confirmation_status']
【understand_request】写入字段: ['error_category', 'last_error', 'provider_metadata', 'semantic_decision']
【compile_decision】写入字段: ['collected_entities', 'compile_result', 'decision_reason', 'error_category', 'grounding_reference_type', 'grounding_status', 'grounding_trusted_source', 'intent', 'knowledge_query', 'last_error', 'memory_candidate', 'memory_key', 'missing_required_fields', 'pending_workflow_decision', 'previous_intent', 'request_type', 'requires_retrieval', 'selected_tool', 'target_validation_status', 'tool_arguments', 'workflow_active', 'workflow_id', 'workflow_policy_inputs', 'workflow_resume_status', 'workflow_state', 'workflow_tool_arguments', 'workflow_validation_context']
【route_request】写入字段: ['selected_tool', 'tool_arguments']
【respond】写入字段: ['final_response', 'messages', 'workflow_state']
【load_context】写入字段: ['agent_run_id', 'answer_grounding', 'citations', 'compilation_resumed', 'compile_result', 'confirmation_status', 'conversation_actor_id', 'conversation_actor_type', 'conversation_customer_id', 'conversation_tenant_id', 'degraded_components', 'error_category', 'failure_category', 'grounding_reference_type', 'grounding_status', 'grounding_trusted_source', 'idempotency_outcome', 'interruption_intent', 'knowledge_answer', 'knowledge_query', 'last_error', 'memory_candidate', 'memory_context', 'memory_key', 'memory_operation_status', 'memory_policy_outcome', 'memory_security_signal', 'memory_summary_requested', 'pending_action_restored', 'policy_decision', 'recovery_action', 'replay_detected', 'requires_retrieval', 'restored_fields_count', 'retrieval_metadata', 'retrieved_chunks', 'retry_count', 'selected_tool', 'semantic_decision', 'target_validation_status', 'tool_arguments', 'tool_execution_status', 'tool_result', 'workflow_interruption_pending', 'workflow_interruption_status', 'workflow_interruption_type', 'workflow_resume_source', 'workflow_transition', 'write_outcome_unknown']
【security_boundary】写入字段: ['security_signal']
【retrieve_memory】写入字段: ['memory_context']
【check_pending_action】写入字段: ['confirmation_status']
【resume_workflow】写入字段: ['collected_entities', 'missing_required_fields', 'previous_intent', 'semantic_decision', 'workflow_active', 'workflow_interruption_pending', 'workflow_resume_status', 'workflow_state']
【compile_decision】写入字段: ['collected_entities', 'compile_result', 'decision_reason', 'error_category', 'grounding_reference_type', 'grounding_status', 'grounding_trusted_source', 'intent', 'knowledge_query', 'last_error', 'memory_candidate', 'memory_key', 'missing_required_fields', 'pending_workflow_decision', 'previous_intent', 'request_type', 'requires_retrieval', 'selected_tool', 'target_validation_status', 'tool_arguments', 'workflow_active', 'workflow_id', 'workflow_policy_inputs', 'workflow_resume_status', 'workflow_state', 'workflow_tool_arguments', 'workflow_validation_context']
【route_request】写入字段: []
【validate_tool】写入字段: ['target_validation_status', 'tool_arguments']
【evaluate_policy】写入字段: ['action_id', 'policy_decision']
【inspect_risk】写入字段: []
【execute_tool】写入字段: ['error_category', 'last_error', 'tool_execution_status', 'tool_result']
【respond】写入字段: ['final_response', 'messages', 'workflow_state']

进程已结束，退出代码为 0
```
