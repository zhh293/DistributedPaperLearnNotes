# LangGraph 框架详解：API、参数、用法与实战 Demo

## 一、LangGraph 概述

LangGraph 是 LangChain 团队推出的一个用于构建有状态、多步骤 Agent 工作流的框架。它的核心思想是将 Agent 的执行过程建模为一个有向图（Graph），其中：

- **节点（Node）**：代表一个处理步骤（调用 LLM、执行工具、做判断等）
- **边（Edge）**：代表节点之间的流转关系（包括条件分支）
- **状态（State）**：在节点之间传递的共享数据

与 LangChain 的 AgentExecutor 相比，LangGraph 提供了更细粒度的控制：

- 支持循环（Agent 可以反复调用工具直到满足条件）
- 支持条件分支（根据状态决定下一步去哪）
- 支持人工介入（human-in-the-loop）
- 支持持久化检查点（中断后可恢复）
- 支持子图嵌套（图中嵌套图）
- 支持并行执行
- 支持流式输出

安装方式：

```bash
pip install langgraph langchain-openai langchain-core
```

---

## 二、核心概念详解

### 2.1 State（状态）—— 最重要的概念

State 是在图中各节点之间传递的共享数据结构。每个节点读取 State，处理后返回状态更新。

```python
from typing import TypedDict, Annotated, List
from langgraph.graph import add_messages
from langchain_core.messages import BaseMessage
import operator

# ===== 方式一：TypedDict 定义状态 =====
class AgentState(TypedDict):
    # Annotated[类型, reducer] 中的 reducer 定义状态更新策略
    messages: Annotated[List[BaseMessage], add_messages]  # 消息列表，追加模式
    current_step: str                                      # 当前步骤，覆盖模式
    iteration_count: int                                   # 迭代计数，覆盖模式
    logs: Annotated[List[str], operator.add]               # 日志列表，追加模式
```

**Reducer 规则：**

| Reducer | 行为 | 适用场景 |
|---------|------|----------|
| `add_messages` | 智能追加消息（处理ID重复） | 对话历史 |
| `operator.add` | 列表拼接 | 日志、中间结果 |
| 无 Reducer | 直接覆盖 | 普通变量 |
| 自定义函数 | 自定义合并逻辑 | 特殊需求 |

**Demo：自定义 Reducer**

```python
from typing import TypedDict, Annotated

# 自定义 reducer：取最大值
def keep_max(existing: int, new: int) -> int:
    return max(existing, new)

# 自定义 reducer：合并字典
def merge_dicts(existing: dict, new: dict) -> dict:
    return {**existing, **new}

class CustomState(TypedDict):
    max_score: Annotated[int, keep_max]       # 只保留最大值
    metadata: Annotated[dict, merge_dicts]    # 字典合并
    logs: Annotated[list, operator.add]       # 列表追加
    current: str                               # 直接覆盖
```

---

### 2.2 Node（节点）

节点是图中的处理单元，本质上是一个函数。

```python
# 节点函数的签名：
# 输入：State（完整状态）
# 输出：dict（只包含需要更新的字段）

def my_node(state: AgentState) -> dict:
    """一个典型的节点函数"""
    # 1. 从状态中读取数据
    messages = state["messages"]
    
    # 2. 执行处理逻辑
    # ... 做一些事情 ...
    
    # 3. 返回状态更新（只返回需要修改的字段）
    return {
        "messages": [new_message],   # 如果有 reducer，会按 reducer 规则合并
        "current_step": "done",       # 无 reducer，直接覆盖
    }
```

---

### 2.3 Edge（边）

```python
from langgraph.graph import StateGraph, START, END

# START: 特殊节点，表示图的入口
# END: 特殊节点，表示图的出口

graph = StateGraph(MyState)

# 普通边：A 完成后无条件到 B
graph.add_edge("node_a", "node_b")
graph.add_edge(START, "node_a")    # 入口
graph.add_edge("node_b", END)      # 出口

# 条件边：根据路由函数的返回值决定下一步
def router(state: MyState) -> str:
    if state["condition"]:
        return "node_b"
    return END

graph.add_conditional_edges("node_a", router, {"node_b": "node_b", END: END})
```

---

## 三、StateGraph API 详解

### 3.1 创建和编译图

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict

class State(TypedDict):
    value: str
    count: int

# ===== StateGraph(state_schema) =====
# 创建状态图构建器
# 参数：
#   state_schema: TypedDict 或 BaseModel 类，定义图的状态结构
graph_builder = StateGraph(State)

# ===== add_node(name, function) =====
# 添加节点到图中
# 参数：
#   name: str - 节点的唯一名称
#   function: Callable[[State], dict] - 节点处理函数
def my_node(state: State) -> dict:
    return {"value": "processed", "count": state["count"] + 1}

graph_builder.add_node("my_node", my_node)

# ===== add_edge(start, end) =====
# 添加普通边（无条件流转）
# 参数：
#   start: str - 起始节点名（或 START）
#   end: str - 目标节点名（或 END）
graph_builder.add_edge(START, "my_node")
graph_builder.add_edge("my_node", END)

# ===== compile(**kwargs) =====
# 编译图，返回可执行的 CompiledGraph
# 参数：
#   checkpointer: BaseCheckpointSaver - 检查点存储器（持久化状态）
#   interrupt_before: List[str] - 在这些节点执行前中断
#   interrupt_after: List[str] - 在这些节点执行后中断
graph = graph_builder.compile()
```

---

### 3.2 条件边详解

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Literal

class State(TypedDict):
    query: str
    category: str
    result: str

def classify(state: State) -> dict:
    """分类节点"""
    query = state["query"]
    if "天气" in query:
        return {"category": "weather"}
    elif "计算" in query:
        return {"category": "math"}
    return {"category": "general"}

def handle_weather(state: State) -> dict:
    return {"result": f"天气查询: {state['query']}"}

def handle_math(state: State) -> dict:
    return {"result": f"数学计算: {state['query']}"}

def handle_general(state: State) -> dict:
    return {"result": f"通用回答: {state['query']}"}

# ===== add_conditional_edges(source, path, path_map) =====
# 添加条件边
# 参数：
#   source: str - 起始节点
#   path: Callable[[State], str] - 路由函数，返回目标节点名
#   path_map: dict (可选) - 路由返回值到节点名的映射
def route_query(state: State) -> Literal["weather", "math", "general"]:
    """路由函数"""
    return state["category"]

graph = StateGraph(State)
graph.add_node("classify", classify)
graph.add_node("weather", handle_weather)
graph.add_node("math", handle_math)
graph.add_node("general", handle_general)

graph.add_edge(START, "classify")

# 条件边：classify -> 根据返回值路由
graph.add_conditional_edges(
    "classify",       # 起始节点
    route_query,      # 路由函数
    {                 # 路由映射（可选）
        "weather": "weather",
        "math": "math",
        "general": "general",
    }
)

graph.add_edge("weather", END)
graph.add_edge("math", END)
graph.add_edge("general", END)

app = graph.compile()

# 测试
print(app.invoke({"query": "今天天气", "category": "", "result": ""})["result"])
# "天气查询: 今天天气"
print(app.invoke({"query": "计算1+1", "category": "", "result": ""})["result"])
# "数学计算: 计算1+1"
```

---

### 3.3 CompiledGraph 调用方法

```python
from langchain_core.messages import HumanMessage

# 编译后的图支持以下方法（与 LangChain Runnable 接口一致）

# ===== invoke(input, config) =====
# 同步执行，返回最终状态
result = graph.invoke(
    {"query": "你好", "category": "", "result": ""},  # 初始状态
    config={"configurable": {"thread_id": "t1"}},     # 配置项
)

# ===== stream(input, config, stream_mode) =====
# 流式执行
# stream_mode 可选值：
#   "updates" (默认): 每个节点执行后只返回变更部分
#   "values": 每个节点执行后返回完整状态
#   "messages": 流式返回 LLM token（需要节点使用 Chat Model）
for event in graph.stream(
    {"query": "你好", "category": "", "result": ""},
    stream_mode="updates",
):
    print(event)  # {节点名: 状态更新}

# ===== batch(inputs) =====
# 批量执行
results = graph.batch([
    {"query": "天气", "category": "", "result": ""},
    {"query": "计算", "category": "", "result": ""},
])

# ===== ainvoke / astream / abatch =====
# 异步版本
import asyncio

async def async_demo():
    result = await graph.ainvoke({"query": "你好", "category": "", "result": ""})
    
    async for event in graph.astream(
        {"query": "你好", "category": "", "result": ""},
        stream_mode="values",
    ):
        print(event)

asyncio.run(async_demo())

# ===== get_graph() =====
# 获取图结构（用于可视化）
print(graph.get_graph().draw_ascii())     # ASCII 格式
print(graph.get_graph().draw_mermaid())   # Mermaid 格式

# ===== get_state(config) / get_state_history(config) =====
# 获取状态（需要 checkpointer）
# state = graph.get_state(config)
# history = list(graph.get_state_history(config))

# ===== update_state(config, values, as_node) =====
# 手动更新状态（人工介入时使用）
# graph.update_state(config, {"result": "人工修改"}, as_node="classify")
```

---

## 四、完整示例：基础聊天机器人

```python
"""
最简单的 LangGraph 聊天机器人
图结构: START -> chatbot -> END
"""
from typing import TypedDict, Annotated, List
from langchain_core.messages import BaseMessage, HumanMessage, AIMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END, add_messages

# Step 1: 定义状态
class State(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]

# Step 2: 定义节点
def chatbot(state: State) -> dict:
    """调用 LLM 生成回复"""
    llm = ChatOpenAI(model="gpt-4o", temperature=0.7)
    response = llm.invoke(state["messages"])
    return {"messages": [response]}  # add_messages reducer 会追加

# Step 3: 构建图
builder = StateGraph(State)
builder.add_node("chatbot", chatbot)
builder.add_edge(START, "chatbot")
builder.add_edge("chatbot", END)

# Step 4: 编译
graph = builder.compile()

# Step 5: 使用
result = graph.invoke({"messages": [HumanMessage(content="你好")]})
print(result["messages"][-1].content)  # AI 的回复

# 流式输出
for event in graph.stream(
    {"messages": [HumanMessage(content="讲一个笑话")]},
    stream_mode="values",
):
    last_msg = event["messages"][-1]
    print(f"[{last_msg.type}] {last_msg.content}")
```

---

## 五、完整示例：带工具的 ReAct Agent

```python
"""
带工具调用的 ReAct Agent
图结构: START -> agent -> (条件) -> tools -> agent -> ... -> END
这是一个循环图：agent 反复调用工具直到不需要
"""
from typing import TypedDict, Annotated, List
from langchain_core.messages import BaseMessage, HumanMessage, ToolMessage
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langgraph.graph import StateGraph, START, END, add_messages
from langgraph.prebuilt import ToolNode

# === 定义状态 ===
class AgentState(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]

# === 定义工具 ===
@tool
def search(query: str) -> str:
    """搜索互联网获取信息。

    Args:
        query: 搜索关键词
    """
    if "天气" in query:
        return "北京今天晴，25度，适合户外活动"
    return f"关于 '{query}' 的搜索结果：这是相关信息..."

@tool
def calculator(expression: str) -> str:
    """计算数学表达式。

    Args:
        expression: 数学表达式，如 '2+3*4'
    """
    try:
        return str(eval(expression))
    except Exception as e:
        return f"计算错误: {e}"

tools = [search, calculator]

# === 定义节点 ===
# 将工具绑定到 LLM（让 LLM 知道可以用哪些工具）
llm = ChatOpenAI(model="gpt-4o", temperature=0)
llm_with_tools = llm.bind_tools(tools)

def agent_node(state: AgentState) -> dict:
    """Agent 节点：调用 LLM 决定是回答还是使用工具"""
    response = llm_with_tools.invoke(state["messages"])
    return {"messages": [response]}

# ToolNode 是 LangGraph 预置的工具执行节点
# 它自动从 AI 消息的 tool_calls 中提取调用信息并执行
tool_node = ToolNode(tools)

# === 定义路由 ===
def should_continue(state: AgentState) -> str:
    """判断 Agent 是否需要继续调用工具"""
    last_message = state["messages"][-1]
    # AI 消息中有 tool_calls -> 继续去工具节点
    if last_message.tool_calls:
        return "tools"
    # 没有 tool_calls -> 结束
    return END

# === 构建图 ===
workflow = StateGraph(AgentState)
workflow.add_node("agent", agent_node)
workflow.add_node("tools", tool_node)

workflow.add_edge(START, "agent")                # 入口 -> agent
workflow.add_conditional_edges(                  # agent 条件分支
    "agent",
    should_continue,
    {"tools": "tools", END: END},
)
workflow.add_edge("tools", "agent")             # tools -> agent（循环！）

# === 编译 ===
graph = workflow.compile()

# === 执行 ===
# 普通对话（不需要工具）
result = graph.invoke({"messages": [HumanMessage(content="你好")]})
print(f"回复: {result['messages'][-1].content}")

# 需要工具的查询
result = graph.invoke({"messages": [HumanMessage(content="北京天气怎么样？")]})
print(f"回复: {result['messages'][-1].content}")

# 复合查询（多工具）
result = graph.invoke({
    "messages": [HumanMessage(content="查一下北京天气，然后算 25*1.8+32")]
})
print(f"回复: {result['messages'][-1].content}")

# 流式查看执行过程
print("\n=== 流式执行 ===")
for event in graph.stream(
    {"messages": [HumanMessage(content="帮我搜索 LangGraph")]},
    stream_mode="updates",
):
    for node_name, output in event.items():
        print(f"[{node_name}] ", end="")
        if "messages" in output:
            for msg in output["messages"]:
                content = msg.content if msg.content else f"(tool_calls: {msg.tool_calls})"
                print(content[:80])
```

---

## 六、Checkpointer（检查点/持久化）

检查点让图可以保存执行状态，实现多轮对话、中断恢复等功能。

### 6.1 MemorySaver

```python
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import StateGraph, START, END, add_messages
from typing import TypedDict, Annotated, List
from langchain_core.messages import BaseMessage, HumanMessage
from langchain_openai import ChatOpenAI

class State(TypedDict):
    messages: Annotated[List[BaseMessage], add_messages]

llm = ChatOpenAI(model="gpt-4o", temperature=0)

def chatbot(state: State) -> dict:
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

builder = StateGraph(State)
builder.add_node("chatbot", chatbot)
builder.add_edge(START, "chatbot")
builder.add_edge("chatbot", END)

# MemorySaver: 内存中保存检查点（进程内有效）
memory = MemorySaver()
graph = builder.compile(checkpointer=memory)

# 使用时必须传入 thread_id 来区分不同的会话
config = {"configurable": {"thread_id": "user_001"}}

# 第一轮
result1 = graph.invoke(
    {"messages": [HumanMessage(content="我叫小明")]},
    config=config,
)
print(result1["messages"][-1].content)

# 第二轮（同一 thread_id，自动恢复历史）
result2 = graph.invoke(
    {"messages": [HumanMessage(content="我叫什么？")]},
    config=config,
)
print(result2["messages"][-1].content)  # 会记住你叫小明

# 不同 thread_id 是独立会话
config2 = {"configurable": {"thread_id": "user_002"}}
result3 = graph.invoke(
    {"messages": [HumanMessage(content="我叫什么？")]},
    config=config2,
)
print(result3["messages"][-1].content)  # 不知道（独立会话）

# === 查看状态 ===
snapshot = graph.get_state(config)
print(f"消息数: {len(snapshot.values['messages'])}")
print(f"下一步: {snapshot.next}")  # () 表示已完成

# === 查看历史 ===
for state_snapshot in graph.get_state_history(config):
    print(f"Step: {state_snapshot.metadata.get('step', '?')}")
```

---

### 6.2 SqliteSaver（持久化到磁盘）

```python
from langgraph.checkpoint.sqlite import SqliteSaver
import sqlite3

# SQLite 持久化：重启程序后状态仍在
conn = sqlite3.connect("./langgraph_checkpoints.db", check_same_thread=False)
checkpointer = SqliteSaver(conn)

graph = builder.compile(checkpointer=checkpointer)
# 用法与 MemorySaver 完全一致
```

---

### 6.3 PostgresSaver（生产环境推荐）

```python
# 生产环境推荐使用 PostgreSQL
# from langgraph.checkpoint.postgres import PostgresSaver
# 
# DB_URI = "postgresql://user:pass@localhost:5432/langgraph"
# with PostgresSaver.from_conn_string(DB_URI) as checkpointer:
#     checkpointer.setup()  # 首次运行创建表
#     graph = builder.compile(checkpointer=checkpointer)
```

---

## 七、Human-in-the-Loop（人工介入）

### 7.1 interrupt_before —— 执行前中断

```python
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.memory import MemorySaver
from typing import TypedDict

class State(TypedDict):
    request: str
    approved: bool
    result: str

def prepare(state: State) -> dict:
    """准备操作"""
    return {"request": f"准备执行: {state['request']}"}

def execute(state: State) -> dict:
    """执行操作（敏感操作，需要人工确认）"""
    return {"result": f"已执行: {state['request']}"}

builder = StateGraph(State)
builder.add_node("prepare", prepare)
builder.add_node("execute", execute)
builder.add_edge(START, "prepare")
builder.add_edge("prepare", "execute")
builder.add_edge("execute", END)

checkpointer = MemorySaver()

# interrupt_before=["execute"] 表示 execute 执行前暂停
graph = builder.compile(
    checkpointer=checkpointer,
    interrupt_before=["execute"],
)

config = {"configurable": {"thread_id": "approval_001"}}

# 第一次调用：prepare 执行完毕，execute 前暂停
result = graph.invoke(
    {"request": "删除生产数据库", "approved": False, "result": ""},
    config=config,
)
# 此时 prepare 已执行，execute 待执行

# 查看状态
snapshot = graph.get_state(config)
print(f"下一步: {snapshot.next}")  # ('execute',)

# 人工审批后继续
graph.update_state(config, {"approved": True})  # 人工修改状态

# 传入 None 表示从中断处继续执行
final = graph.invoke(None, config=config)
print(final["result"])  # "已执行: 准备执行: 删除生产数据库"
```

---

### 7.2 update_state —— 手动修改状态

```python
# update_state(config, values, as_node) 参数：
# config: dict - 包含 thread_id 的配置
# values: dict - 要更新的状态字段
# as_node: str (可选) - 以哪个节点的身份更新（影响后续路由判断）

# 应用场景：
# 1. 人工审批：修改 approved 字段
# 2. 纠错：修改 LLM 的错误输出
# 3. 注入信息：添加人工提供的数据

graph.update_state(
    config,
    values={"approved": True, "result": "人工修改的结果"},
    as_node="prepare",  # 假装这个更新来自 prepare 节点
)
```

---

## 八、循环与迭代

LangGraph 最强大的特性之一：支持循环。

### 8.1 计数器循环

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Annotated, Literal
import operator

class LoopState(TypedDict):
    counter: int
    max_iter: int
    logs: Annotated[list, operator.add]

def increment(state: LoopState) -> dict:
    """递增"""
    new_val = state["counter"] + 1
    return {"counter": new_val, "logs": [f"count={new_val}"]}

def check_loop(state: LoopState) -> Literal["increment", "__end__"]:
    """判断是否继续"""
    if state["counter"] < state["max_iter"]:
        return "increment"
    return END  # "__end__"

graph = StateGraph(LoopState)
graph.add_node("increment", increment)
graph.add_edge(START, "increment")
graph.add_conditional_edges("increment", check_loop)

app = graph.compile()
result = app.invoke({"counter": 0, "max_iter": 5, "logs": []})
print(result["counter"])  # 5
print(result["logs"])     # ['count=1', 'count=2', ..., 'count=5']
```

---

### 8.2 Agent 循环（Think-Act-Observe）

```python
"""
Agent 的经典循环模式：
1. LLM 思考 -> 决定是否调用工具
2. 是 -> 调用工具 -> 返回结果 -> 回到步骤1
3. 否 -> 直接输出最终答案 -> 结束
"""
from langgraph.graph import StateGraph, START, END, MessagesState
from langgraph.prebuilt import ToolNode, tools_condition
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain_core.messages import HumanMessage

@tool
def multiply(a: int, b: int) -> int:
    """两个整数相乘"""
    return a * b

tools = [multiply]
llm = ChatOpenAI(model="gpt-4o").bind_tools(tools)

def call_llm(state: MessagesState) -> dict:
    response = llm.invoke(state["messages"])
    return {"messages": [response]}

# ToolNode 自动执行工具调用
tool_node = ToolNode(tools)

# tools_condition 是预构建的路由函数：
# 有 tool_calls -> 返回 "tools"
# 无 tool_calls -> 返回 END
graph = StateGraph(MessagesState)
graph.add_node("llm", call_llm)
graph.add_node("tools", tool_node)

graph.add_edge(START, "llm")
graph.add_conditional_edges("llm", tools_condition)  # 预构建路由
graph.add_edge("tools", "llm")  # 循环回 LLM

app = graph.compile()
result = app.invoke({"messages": [HumanMessage(content="3乘7等于多少？")]})
print(result["messages"][-1].content)
```

---

## 九、并行执行

### 9.1 Fan-out / Fan-in（扇出/扇入）

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Annotated
import operator

class ParallelState(TypedDict):
    input: str
    results: Annotated[list, operator.add]

def branch_a(state: ParallelState) -> dict:
    return {"results": [f"A处理: {state['input']}"]}

def branch_b(state: ParallelState) -> dict:
    return {"results": [f"B处理: {state['input']}"]}

def branch_c(state: ParallelState) -> dict:
    return {"results": [f"C处理: {state['input']}"]}

def merge(state: ParallelState) -> dict:
    return {"results": [f"合并了{len(state['results'])}个结果"]}

graph = StateGraph(ParallelState)
graph.add_node("a", branch_a)
graph.add_node("b", branch_b)
graph.add_node("c", branch_c)
graph.add_node("merge", merge)

# Fan-out: 从 START 同时到多个节点（并行执行）
graph.add_edge(START, "a")
graph.add_edge(START, "b")
graph.add_edge(START, "c")

# Fan-in: 多个节点汇聚到一个（等全部完成）
graph.add_edge("a", "merge")
graph.add_edge("b", "merge")
graph.add_edge("c", "merge")
graph.add_edge("merge", END)

app = graph.compile()
result = app.invoke({"input": "测试", "results": []})
print(result["results"])
# ['A处理: 测试', 'B处理: 测试', 'C处理: 测试', '合并了3个结果']
```

---

### 9.2 Send API —— 动态并行（Map-Reduce）

```python
from langgraph.graph import StateGraph, START, END, Send
from typing import TypedDict, Annotated
import operator

class MapState(TypedDict):
    topics: list       # 输入主题列表
    results: Annotated[list, operator.add]  # 结果收集

class WorkerInput(TypedDict):
    topic: str         # 单个工作项

def distribute(state: MapState):
    """动态分发：根据 topics 列表生成多个并行任务"""
    # Send(节点名, 发送给该节点的状态) 
    # 每个 Send 都会创建一个独立的并行执行
    return [Send("worker", {"topic": t}) for t in state["topics"]]

def worker(state: WorkerInput) -> dict:
    """处理单个主题"""
    return {"results": [f"已研究: {state['topic']}"]}

graph = StateGraph(MapState)
graph.add_node("worker", worker)

# 从 START 使用条件边动态分发
graph.add_conditional_edges(START, distribute, ["worker"])
graph.add_edge("worker", END)

app = graph.compile()
result = app.invoke({"topics": ["Python", "Rust", "Go", "Java"], "results": []})
print(result["results"])
# ['已研究: Python', '已研究: Rust', '已研究: Go', '已研究: Java']
```

---

## 十、子图（Subgraph）

将复杂逻辑封装为独立图，在父图中作为节点使用。

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict

# === 子图定义 ===
class SubState(TypedDict):
    text: str

def to_upper(state: SubState) -> dict:
    return {"text": state["text"].upper()}

def add_brackets(state: SubState) -> dict:
    return {"text": f"[{state['text']}]"}

sub_builder = StateGraph(SubState)
sub_builder.add_node("upper", to_upper)
sub_builder.add_node("bracket", add_brackets)
sub_builder.add_edge(START, "upper")
sub_builder.add_edge("upper", "bracket")
sub_builder.add_edge("bracket", END)
sub_graph = sub_builder.compile()

# === 主图使用子图 ===
class MainState(TypedDict):
    text: str
    final_result: str

def prepare(state: MainState) -> dict:
    return {"text": state["text"].strip()}

def finalize(state: MainState) -> dict:
    return {"final_result": f"完成: {state['text']}"}

main_builder = StateGraph(MainState)
main_builder.add_node("prepare", prepare)
main_builder.add_node("process", sub_graph)    # 编译后的子图直接当节点用
main_builder.add_node("finalize", finalize)

main_builder.add_edge(START, "prepare")
main_builder.add_edge("prepare", "process")
main_builder.add_edge("process", "finalize")
main_builder.add_edge("finalize", END)

app = main_builder.compile()
result = app.invoke({"text": "  hello world  ", "final_result": ""})
print(result["text"])          # "[HELLO WORLD]"
print(result["final_result"])  # "完成: [HELLO WORLD]"
```

---

## 十一、流式执行详解

### 11.1 stream_mode 三种模式

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Annotated
import operator

class State(TypedDict):
    value: str
    steps: Annotated[list, operator.add]

def step_a(state: State) -> dict:
    return {"value": "A", "steps": ["a"]}

def step_b(state: State) -> dict:
    return {"value": "B", "steps": ["b"]}

graph = StateGraph(State)
graph.add_node("a", step_a)
graph.add_node("b", step_b)
graph.add_edge(START, "a")
graph.add_edge("a", "b")
graph.add_edge("b", END)

app = graph.compile()

# === "updates" 模式（默认）: 每步只返回变更 ===
print("--- updates ---")
for chunk in app.stream({"value": "", "steps": []}, stream_mode="updates"):
    print(chunk)
# {'a': {'value': 'A', 'steps': ['a']}}
# {'b': {'value': 'B', 'steps': ['b']}}

# === "values" 模式: 每步返回完整状态快照 ===
print("--- values ---")
for chunk in app.stream({"value": "", "steps": []}, stream_mode="values"):
    print(chunk)
# {'value': 'A', 'steps': ['a']}         (step_a 后的完整状态)
# {'value': 'B', 'steps': ['a', 'b']}    (step_b 后的完整状态)

# === "messages" 模式: LLM token 级流式（聊天场景） ===
# 需要节点中使用 ChatModel
# for msg_chunk, metadata in app.stream(input, stream_mode="messages"):
#     if msg_chunk.content:
#         print(msg_chunk.content, end="")
```

---

### 11.2 astream_events（异步事件流）

```python
import asyncio
from langchain_core.messages import HumanMessage

async def stream_demo():
    """astream_events 提供最细粒度的事件流"""
    async for event in app.astream_events(
        {"messages": [HumanMessage(content="你好")]},
        version="v2",
    ):
        kind = event["event"]
        
        if kind == "on_chat_model_stream":
            # LLM 每个 token
            content = event["data"]["chunk"].content
            if content:
                print(content, end="", flush=True)
        
        elif kind == "on_tool_start":
            print(f"\n[Tool Start] {event['name']}")
        
        elif kind == "on_tool_end":
            print(f"\n[Tool End] {event['data'].get('output', '')[:50]}")

# asyncio.run(stream_demo())
```

---

## 十二、预构建组件（langgraph.prebuilt）

### 12.1 create_react_agent

```python
from langgraph.prebuilt import create_react_agent
from langgraph.checkpoint.memory import MemorySaver
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain_core.messages import HumanMessage

@tool
def search(query: str) -> str:
    """搜索信息"""
    return f"搜索结果: {query}"

@tool
def calc(expr: str) -> str:
    """数学计算"""
    return str(eval(expr))

llm = ChatOpenAI(model="gpt-4o", temperature=0)
memory = MemorySaver()

# create_react_agent 参数：
# model: BaseChatModel - 模型
# tools: list - 工具列表
# state_modifier: str/SystemMessage/Callable - 系统提示
# checkpointer: BaseCheckpointSaver - 持久化
# interrupt_before: list - 中断点
# interrupt_after: list - 中断点
agent = create_react_agent(
    model=llm,
    tools=[search, calc],
    state_modifier="你是智能助手，用中文回答。",
    checkpointer=memory,
)

# 多轮对话
config = {"configurable": {"thread_id": "demo_001"}}
r1 = agent.invoke({"messages": [HumanMessage(content="我叫小明")]}, config=config)
r2 = agent.invoke({"messages": [HumanMessage(content="我是谁？")]}, config=config)
print(r2["messages"][-1].content)  # 记住你叫小明
```

---

### 12.2 ToolNode

```python
from langgraph.prebuilt import ToolNode
from langchain_core.tools import tool

@tool
def add(a: int, b: int) -> int:
    """加法"""
    return a + b

@tool
def sub(a: int, b: int) -> int:
    """减法"""
    return a - b

# ToolNode 自动执行 AI 消息中的 tool_calls
# 参数：
#   tools: list - 工具列表
#   handle_tool_errors: bool - 是否捕获工具错误（默认 True）
tool_node = ToolNode(
    tools=[add, sub],
    handle_tool_errors=True,  # 工具出错时返回错误信息而非抛异常
)
```

---

### 12.3 tools_condition

```python
from langgraph.prebuilt import tools_condition

# tools_condition 是预构建的路由函数
# 检查最后一条 AI 消息是否有 tool_calls:
#   有 -> 返回 "tools"
#   无 -> 返回 "__end__" (END)

# 用法：
# graph.add_conditional_edges("agent", tools_condition)
# 等价于自己写：
# def my_condition(state):
#     if state["messages"][-1].tool_calls:
#         return "tools"
#     return END
```

---

### 12.4 MessagesState

```python
from langgraph.graph import MessagesState

# MessagesState 是预定义的状态类型，等价于：
# class MessagesState(TypedDict):
#     messages: Annotated[list[BaseMessage], add_messages]
#
# add_messages reducer 的行为：
# 1. 正常追加新消息
# 2. 如果新消息 id 与已有消息相同，则更新
# 3. 可以用 RemoveMessage 删除指定消息

# 适合大多数对话场景，直接用即可
from langgraph.graph import StateGraph, START, END

graph = StateGraph(MessagesState)
# ... 后续正常添加节点和边
```

---

## 十三、多 Agent 协作

### 13.1 Supervisor 模式（主管调度）

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Annotated, Literal
import operator
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, BaseMessage

class MultiAgentState(TypedDict):
    messages: Annotated[list[BaseMessage], operator.add]
    next_agent: str
    final_answer: str

llm = ChatOpenAI(model="gpt-4o", temperature=0)

def supervisor(state: MultiAgentState) -> dict:
    """Supervisor: 分析任务并分配给合适的 Agent"""
    query = state["messages"][-1].content
    if "代码" in query or "编程" in query:
        return {"next_agent": "coder"}
    elif "写作" in query or "文章" in query:
        return {"next_agent": "writer"}
    return {"next_agent": "researcher"}

def coder(state: MultiAgentState) -> dict:
    """编程专家"""
    resp = llm.invoke([
        {"role": "system", "content": "你是编程专家"},
        {"role": "user", "content": state["messages"][-1].content},
    ])
    return {"final_answer": resp.content}

def writer(state: MultiAgentState) -> dict:
    """写作专家"""
    resp = llm.invoke([
        {"role": "system", "content": "你是写作专家"},
        {"role": "user", "content": state["messages"][-1].content},
    ])
    return {"final_answer": resp.content}

def researcher(state: MultiAgentState) -> dict:
    """研究专家"""
    resp = llm.invoke([
        {"role": "system", "content": "你是研究专家"},
        {"role": "user", "content": state["messages"][-1].content},
    ])
    return {"final_answer": resp.content}

def route(state: MultiAgentState) -> Literal["coder", "writer", "researcher"]:
    return state["next_agent"]

graph = StateGraph(MultiAgentState)
graph.add_node("supervisor", supervisor)
graph.add_node("coder", coder)
graph.add_node("writer", writer)
graph.add_node("researcher", researcher)

graph.add_edge(START, "supervisor")
graph.add_conditional_edges("supervisor", route)
graph.add_edge("coder", END)
graph.add_edge("writer", END)
graph.add_edge("researcher", END)

app = graph.compile()

result = app.invoke({
    "messages": [HumanMessage(content="帮我写一段Python排序代码")],
    "next_agent": "",
    "final_answer": "",
})
print(result["final_answer"])
```

---

## 十四、错误处理与重试

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict, Literal

class RetryState(TypedDict):
    data: str
    result: str
    error: str
    attempts: int

def risky_op(state: RetryState) -> dict:
    """可能失败的操作"""
    attempts = state["attempts"] + 1
    try:
        if attempts < 3:  # 前两次模拟失败
            raise ValueError(f"第{attempts}次尝试失败")
        return {"result": "成功!", "error": "", "attempts": attempts}
    except Exception as e:
        return {"error": str(e), "attempts": attempts}

def check(state: RetryState) -> Literal["retry", "success", "give_up"]:
    """决定重试、成功还是放弃"""
    if not state["error"]:
        return "success"
    if state["attempts"] >= 5:
        return "give_up"
    return "retry"

def success_handler(state: RetryState) -> dict:
    return {"result": f"最终成功（第{state['attempts']}次）"}

def failure_handler(state: RetryState) -> dict:
    return {"result": f"放弃: {state['error']}"}

graph = StateGraph(RetryState)
graph.add_node("operation", risky_op)
graph.add_node("success", success_handler)
graph.add_node("give_up", failure_handler)

graph.add_edge(START, "operation")
graph.add_conditional_edges("operation", check, {
    "retry": "operation",   # 循环重试
    "success": "success",
    "give_up": "give_up",
})
graph.add_edge("success", END)
graph.add_edge("give_up", END)

app = graph.compile()
result = app.invoke({"data": "test", "result": "", "error": "", "attempts": 0})
print(result["result"])  # "最终成功（第3次）"
```

---

## 十五、图的可视化

```python
from langgraph.graph import StateGraph, START, END
from typing import TypedDict

class State(TypedDict):
    x: str

def a(state: State) -> dict:
    return {"x": "a"}

def b(state: State) -> dict:
    return {"x": "b"}

graph = StateGraph(State)
graph.add_node("a", a)
graph.add_node("b", b)
graph.add_edge(START, "a")
graph.add_edge("a", "b")
graph.add_edge("b", END)

app = graph.compile()

# === ASCII 文本可视化 ===
print(app.get_graph().draw_ascii())
# 输出类似:
#   +-----------+
#   | __start__ |
#   +-----------+
#         |
#         v
#     +-------+
#     |   a   |
#     +-------+
#         |
#         v
#     +-------+
#     |   b   |
#     +-------+
#         |
#         v
#   +---------+
#   | __end__ |
#   +---------+

# === Mermaid 格式（可在 Markdown 中渲染） ===
mermaid_code = app.get_graph().draw_mermaid()
print(mermaid_code)
# graph TD;
#     __start__ --> a;
#     a --> b;
#     b --> __end__;

# === 导出 PNG ===
# 需要安装: pip install pygraphviz
# 或使用 Mermaid 在线渲染
# app.get_graph().draw_mermaid_png(output_file_path="my_graph.png")
```

---

## 十六、完整实战：支持多轮对话的智能 Agent

```python
"""
完整实战：一个生产级别的对话 Agent
特性：
- 多轮对话记忆
- 多工具支持
- 流式输出
- 持久化检查点
- 优雅的错误处理
"""
from langgraph.graph import StateGraph, START, END, MessagesState
from langgraph.prebuilt import ToolNode, tools_condition
from langgraph.checkpoint.memory import MemorySaver
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain_core.messages import HumanMessage, SystemMessage

# === 工具定义 ===
@tool
def web_search(query: str) -> str:
    """搜索互联网获取信息

    Args:
        query: 搜索关键词
    """
    return f"搜索结果: 关于'{query}'的最新信息..."

@tool
def math_calc(expression: str) -> str:
    """计算数学表达式

    Args:
        expression: 数学表达式如 '2+3*4'
    """
    try:
        return f"计算结果: {eval(expression)}"
    except Exception as e:
        return f"计算错误: {e}"

@tool
def get_time() -> str:
    """获取当前时间"""
    from datetime import datetime
    return f"当前时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"

tools = [web_search, math_calc, get_time]

# === 模型配置 ===
llm = ChatOpenAI(model="gpt-4o", temperature=0)
llm_with_tools = llm.bind_tools(tools)

# === 节点定义 ===
SYSTEM_PROMPT = "你是一个智能助手，使用中文回答用户的问题。你可以使用工具来获取信息。"

def call_model(state: MessagesState) -> dict:
    """调用 LLM 节点"""
    messages = [SystemMessage(content=SYSTEM_PROMPT)] + state["messages"]
    response = llm_with_tools.invoke(messages)
    return {"messages": [response]}

tool_node = ToolNode(tools, handle_tool_errors=True)

# === 构建图 ===
builder = StateGraph(MessagesState)
builder.add_node("model", call_model)
builder.add_node("tools", tool_node)

builder.add_edge(START, "model")
builder.add_conditional_edges("model", tools_condition)
builder.add_edge("tools", "model")

# === 编译（带持久化） ===
checkpointer = MemorySaver()
agent = builder.compile(checkpointer=checkpointer)

# === 使用函数 ===
def chat(message: str, thread_id: str = "default") -> str:
    """发送消息并获取回复"""
    config = {"configurable": {"thread_id": thread_id}}
    result = agent.invoke(
        {"messages": [HumanMessage(content=message)]},
        config=config,
    )
    return result["messages"][-1].content

def chat_stream(message: str, thread_id: str = "default"):
    """流式发送消息"""
    config = {"configurable": {"thread_id": thread_id}}
    for chunk, metadata in agent.stream(
        {"messages": [HumanMessage(content=message)]},
        config=config,
        stream_mode="messages",
    ):
        if chunk.content:
            print(chunk.content, end="", flush=True)
    print()  # 换行

# === 演示 ===
if __name__ == "__main__":
    thread = "demo_session"
    
    # 多轮对话
    print("Q: 你好")
    print(f"A: {chat('你好，我叫张三', thread)}")
    print()
    
    print("Q: 帮我算 2 的 10 次方")
    print(f"A: {chat('帮我算 2 的 10 次方', thread)}")
    print()
    
    print("Q: 我叫什么？")
    print(f"A: {chat('我叫什么名字？', thread)}")
    print()
    
    print("Q: 现在几点？（流式输出）")
    print("A: ", end="")
    chat_stream("现在几点了？", thread)
```

---

## 十七、LangGraph vs LangChain AgentExecutor 对比

| 维度 | AgentExecutor (旧) | LangGraph (新) |
|------|-------------------|----------------|
| 执行流程 | 固定循环 | 自定义图 |
| 循环控制 | max_iterations | 条件边 + 自定义逻辑 |
| 状态管理 | 隐式 (Memory) | 显式 (State + Reducer) |
| 人工介入 | 不支持 | interrupt_before/after |
| 持久化 | 不内置 | Checkpointer |
| 并行 | 不支持 | Fan-out + Send |
| 子流程 | 嵌套链 | Subgraph |
| 流式输出 | 基础 stream | 多模式 stream |
| 可视化 | 无 | draw_ascii/mermaid |
| 适用场景 | 简单 Agent | 复杂工作流 |

**迁移建议：**
- 简单的单工具 Agent -> AgentExecutor 或 create_react_agent 都可以
- 需要人工审批、复杂路由、多 Agent 协作 -> 使用 LangGraph
- 新项目 -> 推荐直接使用 LangGraph

---

本文档覆盖了 LangGraph 的核心概念、API 和实战模式。官方文档：https://langchain-ai.github.io/langgraph/ 。LangGraph 的核心范式（StateGraph + Node + Edge + Checkpointer）是稳定的，具体 API 可能随版本迭代有微调，建议开发时对照最新文档。
