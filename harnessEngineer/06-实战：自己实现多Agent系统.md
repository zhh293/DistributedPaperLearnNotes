# 实战：自己实现多 Agent 系统

> 本文从"自己做 Agent"的视角，把多 Agent 协作、任务分发、通信机制讲清楚。
> 核心问题：为什么需要多个 Agent？怎么分工？怎么通信？怎么保证隔离？

---

## 〇、先把层次理清：会话、对话、循环

三层嵌套关系不变，但多 Agent 在每层的角色不同：

```
┌═══════════════════════════════════════════════════════════════════┐
║  Session（会话）                                                   ║
║  ═══════════════                                                   ║
║  多 Agent 的角色：                                                 ║
║  • 主 Agent（Leader）在此运行                                      ║
║  • 可以产生子 Agent，子 Agent 有自己的循环但共享同一个 Session     ║
║  • 所有 Agent 的任务状态在此注册和追踪                             ║
║                                                                    ║
║  ┌───────────────────────────────────────────────────────────┐   ║
║  │  Turn（对话轮次）                                           │   ║
║  │  ═══════════════                                            │   ║
║  │  多 Agent 的角色：                                           │   ║
║  │  • 用户提出复杂任务 → Leader 决定是否需要分发给子 Agent      │   ║
║  │  • 子 Agent 的结果在 Turn 结束前收集回来                     │   ║
║  │                                                              │   ║
║  │  ┌───────────────────────────────────────────────────┐     │   ║
║  │  │  Agentic Loop（Agent 循环）                         │     │   ║
║  │  │  ═════════════════════════                          │     │   ║
║  │  │  多 Agent 的角色：                                   │     │   ║
║  │  │  • Leader 调用 spawn_agent 工具 → 产生子 Agent       │     │   ║
║  │  │  • 子 Agent 有自己独立的 Agentic Loop               │     │   ║
║  │  │  • 子 Agent 完成后，结果回传到 Leader 的 Loop       │     │   ║
║  │  │                                                      │     │   ║
║  │  │  Loop 1: LLM → "任务太大，我需要拆分"               │     │   ║
║  │  │  Loop 2: LLM → tool_use[spawn_agent("研究 API")]    │     │   ║
║  │  │          → 子 Agent A 开始工作（独立循环）           │     │   ║
║  │  │  Loop 3: LLM → tool_use[spawn_agent("写测试")]      │     │   ║
║  │  │          → 子 Agent B 开始工作（独立循环）           │     │   ║
║  │  │  Loop 4: Agent A 完成 → 结果回传                    │     │   ║
║  │  │  Loop 5: Agent B 完成 → 结果回传                    │     │   ║
║  │  │  Loop 6: LLM → "综合两个结果..." → 文本回复         │     │   ║
║  │  └───────────────────────────────────────────────────┘     │   ║
║  └───────────────────────────────────────────────────────────┘   ║
╚═══════════════════════════════════════════════════════════════════╝
```

### 每一层分别管理什么？

| 层次 | 单 Agent | 多 Agent 新增的职责 |
|------|---------|-------------------|
| Session（会话） | messages[]、system prompt | Task 注册表、Agent 名称注册表、子 Agent 生命周期 |
| Turn（对话轮次） | 用户输入→回复 | 决定是否拆分任务、收集子 Agent 结果 |
| Agentic Loop（循环） | LLM→工具→循环 | spawn_agent 工具、消息路由、结果注入 |

---

## 一、为什么需要多个 Agent

### 1.1 单 Agent 的瓶颈

单 Agent 系统（前两篇实战）有三个天然限制：

1. **顺序执行**：一次只能做一件事。"搜索 10 个文件"必须一个一个来。
2. **上下文窗口**：复杂任务产生的中间过程太多，一个窗口装不下。
3. **专注度**：让一个 Agent 同时做"研究代码结构"和"写实现代码"和"跑测试验证"，它经常顾此失彼。

### 1.2 多 Agent 的解决方案

```
单 Agent：                          多 Agent：

用户: "重构这个模块"                用户: "重构这个模块"
  │                                   │
  ▼                                   ▼
Agent:                             Leader Agent:
  读文件1                             "任务太大，拆分为3个子任务"
  读文件2                               │
  读文件3                               ├──► Agent A: 研究代码结构
  想方案                                │    （独立循环，独立上下文）
  改文件1                               ├──► Agent B: 写新实现
  改文件2                               │    （独立循环，独立上下文）
  改文件3                               └──► Agent C: 写测试
  跑测试                                     （独立循环，独立上下文）
  修 bug                                  │
  跑测试                                  ▼
  ...                              Leader: 汇总结果，验证，回复用户
 (一个上下文装不下)                 (每个子 Agent 只关注自己的部分)
```

---

## 二、Session 层：Task 注册表与 Agent 生命周期

### 2.1 Task 类型系统

多 Agent 系统需要一个统一的"工作单元"抽象来追踪所有异步任务：

```
┌─────────────────────────────────────┐
│  Task Registry（任务注册表）           │
│                                       │
│  ┌─────────────────────────────────┐ │
│  │ Task ID: a7x3m9p1               │ │
│  │ Type: local_agent                │ │
│  │ Status: running                  │ │
│  │ Description: "研究 API 结构"     │ │
│  │ Start Time: 1700000000           │ │
│  │ Output: (持续写入...)            │ │
│  └─────────────────────────────────┘ │
│                                       │
│  ┌─────────────────────────────────┐ │
│  │ Task ID: a9k2p5r8               │ │
│  │ Type: local_agent                │ │
│  │ Status: completed                │ │
│  │ Description: "写测试用例"        │ │
│  │ Result: "Created 5 test files..." │ │
│  └─────────────────────────────────┘ │
│                                       │
│  ┌─────────────────────────────────┐ │
│  │ Task ID: b3m1k7w2               │ │
│  │ Type: local_bash                 │ │
│  │ Status: running                  │ │
│  │ Description: "npm test"          │ │
│  └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

### 2.2 Task 生命周期

```
pending ──► running ──► completed
                   ├──► failed
                   └──► killed
```

一旦进入终态（completed/failed/killed），就不能再向该 Agent 发消息。

### 2.3 代码实现

```python
import os
import json
import random
import string
import threading
from enum import Enum
from dataclasses import dataclass, field
from datetime import datetime
from typing import Callable, Optional
from anthropic import Anthropic

client = Anthropic()


class TaskType(Enum):
    LOCAL_AGENT = "local_agent"       # 本地子代理
    LOCAL_BASH = "local_bash"         # 后台 shell 命令
    IN_PROCESS_TEAMMATE = "teammate"  # 进程内队友


class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    KILLED = "killed"


def is_terminal(status: TaskStatus) -> bool:
    """终态判断——防止向已终止的 agent 注入消息"""
    return status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.KILLED)


@dataclass
class Task:
    """一个工作单元"""
    id: str
    type: TaskType
    status: TaskStatus
    description: str
    start_time: float
    end_time: Optional[float] = None
    result: Optional[str] = None
    error: Optional[str] = None
    
    # 运行时状态
    messages: list = field(default_factory=list)
    abort_event: threading.Event = field(default_factory=threading.Event)


TYPE_PREFIX = {
    TaskType.LOCAL_AGENT: "a",
    TaskType.LOCAL_BASH: "b",
    TaskType.IN_PROCESS_TEAMMATE: "t",
}


def generate_task_id(task_type: TaskType) -> str:
    """生成任务 ID：类型前缀 + 随机字符"""
    prefix = TYPE_PREFIX[task_type]
    random_part = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return f"{prefix}{random_part}"


class TaskRegistry:
    """
    全局任务注册表——Session 级别。
    所有子 Agent、后台命令都在此注册和追踪。
    """
    
    def __init__(self):
        self.tasks: dict[str, Task] = {}
        self._lock = threading.Lock()
    
    def register(self, task: Task):
        with self._lock:
            self.tasks[task.id] = task
    
    def get(self, task_id: str) -> Optional[Task]:
        return self.tasks.get(task_id)
    
    def update_status(self, task_id: str, status: TaskStatus, result: str = None):
        with self._lock:
            task = self.tasks.get(task_id)
            if task:
                task.status = status
                task.result = result
                if is_terminal(status):
                    task.end_time = datetime.now().timestamp()
    
    def get_running_tasks(self) -> list[Task]:
        return [t for t in self.tasks.values() if t.status == TaskStatus.RUNNING]
    
    def get_completed_tasks(self) -> list[Task]:
        return [t for t in self.tasks.values() if t.status == TaskStatus.COMPLETED]
```

---

## 三、子 Agent 的两种模式：同步 vs 异步

### 3.1 同步子 Agent（阻塞等待）

Leader 产生子 Agent 后**阻塞等待**它完成，拿到结果后继续自己的循环。

```
Leader Loop:
  → LLM: "我需要先了解代码结构"
  → tool_use: spawn_agent("研究 src/ 目录结构")
  → [阻塞] 子 Agent 开始运行...
      子 Agent Loop 1: read_file(src/index.ts)
      子 Agent Loop 2: read_file(src/utils.ts)
      子 Agent Loop 3: 输出摘要
  → [返回] 子 Agent 结果注入到 Leader 的 messages
  → Leader 继续下一轮 Loop
```

**优点**：简单、确定性强。
**缺点**：不能并行，Leader 被阻塞。

### 3.2 异步子 Agent（Fire-and-Forget）

Leader 产生子 Agent 后**立即继续**，子 Agent 在后台独立运行。结果通过通知机制回传。

```
Leader Loop:
  → LLM: "这个任务可以并行"
  → tool_use: spawn_agent("研究 API", async=true)
  → [立即返回] "已启动任务 a7x3m9p1"
  → tool_use: spawn_agent("写测试", async=true)
  → [立即返回] "已启动任务 a9k2p5r8"
  → LLM: "等待结果..."
  ... 
  → [通知注入] "任务 a7x3m9p1 已完成: ..."
  → [通知注入] "任务 a9k2p5r8 已完成: ..."
  → LLM: "两个都完成了，综合结果..."
```

**优点**：并行执行，效率高。
**缺点**：需要通知机制、状态管理更复杂。

### 3.3 代码实现

```python
class SubAgent:
    """
    子 Agent——由 Leader 产生。
    有自己独立的 messages[] 和 Agentic Loop。
    但共享 Leader 的 TaskRegistry。
    """
    
    def __init__(
        self, 
        task_id: str,
        prompt: str,
        parent_context: str = "",
        tools: list = None,
    ):
        self.task_id = task_id
        self.prompt = prompt
        
        # ★ 独立的 messages（不共享父级的历史）
        self.messages: list[dict] = []
        
        # 子 Agent 的系统提示（比 Leader 简单）
        self.system_prompt = (
            "你是一个专注的执行者。完成分配的具体任务，不要做额外的事。"
            "完成后输出简洁的结果摘要。"
        )
        
        # 如果父级传了上下文（如代码结构概述），注入为第一条消息
        if parent_context:
            self.messages.append({
                "role": "user",
                "content": f"<parent-context>\n{parent_context}\n</parent-context>"
            })
            self.messages.append({
                "role": "assistant",
                "content": "已了解上下文。"
            })
        
        # 注入具体任务
        self.messages.append({"role": "user", "content": prompt})
        
        # 工具集（可以比 Leader 少——子 Agent 不需要 spawn_agent）
        self.tools = tools or [
            {"name": "read_file", "description": "读取文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "写入文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
            {"name": "bash", "description": "执行命令",
             "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
        ]
    
    def run(self) -> str:
        """
        子 Agent 的 Agentic Loop。
        和单 Agent 的循环一样，但有最大轮次限制。
        """
        max_loops = 15  # 子 Agent 限制更严格
        
        for _ in range(max_loops):
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=4000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            if response.stop_reason == "end_turn":
                text = next(
                    (b.text for b in response.content if b.type == "text"), ""
                )
                return text  # 返回最终结果给 Leader
            
            # 工具调用 → 执行 → 继续循环
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = self._execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            
            self.messages.append({"role": "user", "content": tool_results})
        
        return "[子 Agent 超时] 未在限定循环内完成。"
    
    def _execute_tool(self, name: str, input: dict) -> str:
        if name == "read_file":
            try:
                return open(input["path"]).read()[:10000]
            except Exception as e:
                return f"Error: {e}"
        elif name == "write_file":
            os.makedirs(os.path.dirname(input["path"]) or ".", exist_ok=True)
            open(input["path"], "w").write(input["content"])
            return "OK"
        elif name == "bash":
            import subprocess
            r = subprocess.run(
                input["command"], shell=True, 
                capture_output=True, text=True, timeout=30
            )
            return (r.stdout + r.stderr)[:5000]
        return f"Unknown tool: {name}"
```

---

## 四、Fork 子 Agent——缓存优化的极致

### 4.1 核心理念

普通子 Agent 从空白开始，需要重新构建上下文。但 **Fork 子 Agent** 继承父级的**全部上下文**——system prompt + messages + tools 全部复用。

为什么这很重要？因为 **API Prompt Cache**。如果子 Agent 的 system prompt 和父级字节级一致，API 服务端可以直接命中缓存——子 Agent 的首次请求几乎零成本。

```
┌─────────────────────────────────────────────┐
│  Parent Agent                                │
│  system_prompt: "你是编程助手...（20K tokens）"│
│  messages: [100 条对话历史]                   │
│                                              │
│         fork（分叉）                          │
│            │                                 │
│     ┌──────┴──────────────────────────┐     │
│     │  Fork Child                      │     │
│     │  system_prompt: 字节级相同        │     │  ← Cache 命中！
│     │  messages: 父级的 messages        │     │  ← Cache 命中！
│     │  + 追加："你的具体任务是..."      │     │  ← 只有这部分是新的
│     └──────────────────────────────────┘     │
└─────────────────────────────────────────────┘

成本计算：
  普通子 Agent: 20K (system) + 任务描述 = 全部重新计算
  Fork 子 Agent: 0 (cache hit) + 任务描述 = 只算增量
```

### 4.2 Fork 的消息构造

Fork 子 Agent 需要把父级的 messages 原样复制，但有一个技术细节：如果父级的最后一条 assistant message 包含 tool_use，API 要求必须有对应的 tool_result。所以 Fork 需要生成占位 tool_result：

```python
def build_forked_messages(
    parent_messages: list[dict], 
    child_task: str
) -> list[dict]:
    """
    构造 Fork 子 Agent 的消息。
    复用父级全部历史 + 为未完成的 tool_use 生成占位结果 + 追加子任务。
    """
    forked = list(parent_messages)  # 浅拷贝
    
    # 检查最后一条 assistant 消息是否有 tool_use
    if forked and forked[-1].get("role") == "assistant":
        content = forked[-1].get("content", [])
        if isinstance(content, list):
            # 找到所有 tool_use block
            tool_uses = [b for b in content if isinstance(b, dict) and b.get("type") == "tool_use"]
            if tool_uses:
                # 生成占位 tool_result
                placeholder_results = [
                    {
                        "type": "tool_result",
                        "tool_use_id": tu["id"],
                        "content": "[此工具调用由父级 Agent 发起，结果由子 Agent 继续处理]",
                    }
                    for tu in tool_uses
                ]
                forked.append({"role": "user", "content": placeholder_results})
    
    # 追加子任务指令
    forked.append({
        "role": "user",
        "content": f"<fork-task>\n{child_task}\n</fork-task>",
    })
    
    return forked
```

### 4.3 防止无限递归

Fork 子 Agent 不能再次 Fork（否则会无限递归）。通过检测消息中是否已存在 fork 标记来防止：

```python
FORK_TAG = "<fork-task>"

def can_fork(messages: list[dict]) -> bool:
    """检查是否允许 Fork（已经是 Fork child 就不能再 Fork）"""
    for msg in messages:
        content = msg.get("content", "")
        if isinstance(content, str) and FORK_TAG in content:
            return False
    return True
```

---

## 五、Coordinator 模式——编排者不干活

### 5.1 核心理念

Coordinator（协调器）是一个特殊模式：Leader Agent **不自己使用文件操作工具**，只负责分析任务、分配工作、验证结果。所有实际工作都由 Worker 完成。

```
┌──────────────────────────────────────────────────┐
│  Coordinator（协调器）                              │
│                                                    │
│  可用工具（只有 3 个！）：                          │
│  • spawn_agent  → 产生 Worker                     │
│  • send_message → 给 Worker 发消息                │
│  • stop_agent   → 停止 Worker                    │
│                                                    │
│  不能用的工具：                                    │
│  ✗ read_file, write_file, bash, grep...           │
│  （这些只有 Worker 能用）                          │
│                                                    │
└──────────────────────────────────────────────────┘
```

### 5.2 四阶段工作流

```
┌────────────────┐     ┌────────────────┐
│  Phase 1       │     │  Phase 2       │
│  Research      │────►│  Synthesis     │
│  并行派多个     │     │  Coordinator   │
│  Worker 研究    │     │  自己综合理解   │
└────────────────┘     └───────┬────────┘
                               │
┌────────────────┐     ┌───────▼────────┐
│  Phase 4       │     │  Phase 3       │
│  Verification  │◄────│ Implementation │
│  独立 Worker   │     │  Worker 按     │
│  跑测试验证    │     │  规格实施      │
└────────────────┘     └────────────────┘
```

**Phase 1: Research（研究）**—— 多个 Worker 并行调查代码库（只读，可自由并发）。

**Phase 2: Synthesis（综合）**—— Coordinator 自己分析研究结果，编写实现规格。

**Phase 3: Implementation（实施）**—— Worker 按规格编码（按文件区域串行，避免冲突）。

**Phase 4: Verification（验证）**—— 独立 Worker 跑测试验证（可与其他区域的实施并行）。

### 5.3 代码实现

```python
class CoordinatorAgent:
    """
    协调器模式：不直接操作文件，只负责分配和协调。
    """
    
    def __init__(self):
        self.messages: list[dict] = []
        self.task_registry = TaskRegistry()
        self.pending_notifications: list[str] = []
        
        self.system_prompt = """你是一个任务协调器。你不直接操作文件或执行命令。
你的工作流程：
1. Research: 派 Worker 研究代码结构（并行）
2. Synthesis: 根据研究结果制定实施计划
3. Implementation: 派 Worker 按计划实施（按文件区域串行）
4. Verification: 派独立 Worker 验证结果

使用 spawn_agent 创建 Worker，使用 send_message 给 Worker 补充指令。"""
        
        # Coordinator 只有 3 个工具
        self.tools = [
            {
                "name": "spawn_agent",
                "description": "产生一个 Worker Agent 执行具体任务",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "task": {"type": "string", "description": "Worker 要执行的具体任务"},
                        "async": {"type": "boolean", "description": "是否异步执行", "default": True},
                    },
                    "required": ["task"],
                },
            },
            {
                "name": "send_message",
                "description": "给正在运行的 Worker 发送追加指令",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "task_id": {"type": "string"},
                        "message": {"type": "string"},
                    },
                    "required": ["task_id", "message"],
                },
            },
            {
                "name": "stop_agent",
                "description": "停止一个 Worker",
                "input_schema": {
                    "type": "object",
                    "properties": {"task_id": {"type": "string"}},
                    "required": ["task_id"],
                },
            },
        ]
    
    def handle_turn(self, user_input: str) -> str:
        """Coordinator 的 Turn 处理"""
        self.messages.append({"role": "user", "content": user_input})
        return self._run_coordinator_loop()
    
    def _run_coordinator_loop(self) -> str:
        """Coordinator 的 Agentic Loop"""
        
        for _ in range(30):
            # 注入已完成任务的通知
            self._inject_notifications()
            
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            if response.stop_reason == "end_turn":
                text = next((b.text for b in response.content if b.type == "text"), "")
                self.messages.append({"role": "assistant", "content": text})
                return text
            
            # 处理工具调用
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = self._handle_coordinator_tool(block.name, block.input, block.id)
                    tool_results.append(result)
            
            self.messages.append({"role": "user", "content": tool_results})
        
        return "[协调器超时]"
    
    def _handle_coordinator_tool(self, name: str, input: dict, tool_use_id: str) -> dict:
        """处理 Coordinator 的工具调用"""
        
        if name == "spawn_agent":
            task_id = generate_task_id(TaskType.LOCAL_AGENT)
            task = Task(
                id=task_id,
                type=TaskType.LOCAL_AGENT,
                status=TaskStatus.RUNNING,
                description=input["task"],
                start_time=datetime.now().timestamp(),
            )
            self.task_registry.register(task)
            
            is_async = input.get("async", True)
            
            if is_async:
                # 异步：后台线程运行子 Agent
                thread = threading.Thread(
                    target=self._run_worker_background,
                    args=(task_id, input["task"]),
                    daemon=True,
                )
                thread.start()
                return {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": f"Worker started (async). Task ID: {task_id}",
                }
            else:
                # 同步：阻塞等待
                result = self._run_worker_sync(input["task"])
                self.task_registry.update_status(task_id, TaskStatus.COMPLETED, result)
                return {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": f"Worker completed:\n{result}",
                }
        
        elif name == "stop_agent":
            task = self.task_registry.get(input["task_id"])
            if task and not is_terminal(task.status):
                task.abort_event.set()
                self.task_registry.update_status(input["task_id"], TaskStatus.KILLED)
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Agent {input['task_id']} stopped.",
            }
        
        elif name == "send_message":
            task = self.task_registry.get(input["task_id"])
            if task and task.status == TaskStatus.RUNNING:
                task.messages.append(input["message"])
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Message sent to {input['task_id']}.",
            }
        
        return {"type": "tool_result", "tool_use_id": tool_use_id, "content": "Unknown tool"}
    
    def _run_worker_background(self, task_id: str, prompt: str):
        """后台运行 Worker（异步模式）"""
        try:
            worker = SubAgent(task_id=task_id, prompt=prompt)
            result = worker.run()
            self.task_registry.update_status(task_id, TaskStatus.COMPLETED, result)
            # 通知 Coordinator
            self.pending_notifications.append(
                f"<task-notification>\n"
                f"  <task-id>{task_id}</task-id>\n"
                f"  <status>completed</status>\n"
                f"  <result>{result[:2000]}</result>\n"
                f"</task-notification>"
            )
        except Exception as e:
            self.task_registry.update_status(task_id, TaskStatus.FAILED, str(e))
            self.pending_notifications.append(
                f"<task-notification>\n"
                f"  <task-id>{task_id}</task-id>\n"
                f"  <status>failed</status>\n"
                f"  <error>{str(e)}</error>\n"
                f"</task-notification>"
            )
    
    def _run_worker_sync(self, prompt: str) -> str:
        """同步运行 Worker"""
        worker = SubAgent(task_id="sync", prompt=prompt)
        return worker.run()
    
    def _inject_notifications(self):
        """把已完成任务的通知注入到 Coordinator 的消息流"""
        if self.pending_notifications:
            notification_text = "\n".join(self.pending_notifications)
            self.messages.append({
                "role": "user",
                "content": notification_text,
            })
            self.pending_notifications.clear()
```

---

## 六、通信机制——Agent 之间怎么说话

### 6.1 三种通信方式

| 方式 | 适用场景 | 实现 | 延迟 |
|------|---------|------|------|
| 内存队列 | 同进程内的多个 Agent | `list.append()` + 锁 | 微秒 |
| 文件邮箱 | 跨进程的 Agent（如 tmux pane） | JSON 文件 + 文件锁 | 毫秒 |
| 通知注入 | 异步 Agent 完成后通知 Leader | 插入到 messages[] | 下一次 Loop |

### 6.2 文件邮箱实现

当 Agent 运行在不同进程（如每个 Agent 一个终端 pane）时，用文件系统作为通信通道：

```
~/.agent/teams/{team_name}/inboxes/
├── leader.json        ← Leader 的收件箱
├── worker_a.json      ← Worker A 的收件箱
└── worker_b.json      ← Worker B 的收件箱
```

```python
import fcntl
from pathlib import Path
from dataclasses import dataclass
from datetime import datetime


@dataclass
class Message:
    from_agent: str
    text: str
    timestamp: str
    read: bool = False


class FileMailbox:
    """
    基于文件的消息邮箱。
    跨进程安全（使用文件锁）。
    """
    
    def __init__(self, team_name: str, agent_name: str):
        self.inbox_dir = Path.home() / ".agent" / "teams" / team_name / "inboxes"
        self.inbox_dir.mkdir(parents=True, exist_ok=True)
        self.inbox_file = self.inbox_dir / f"{agent_name}.json"
        
        # 确保文件存在
        if not self.inbox_file.exists():
            self.inbox_file.write_text("[]")
    
    def send(self, to_agent: str, text: str, from_agent: str):
        """发送消息到另一个 Agent 的邮箱"""
        target_file = self.inbox_dir / f"{to_agent}.json"
        if not target_file.exists():
            target_file.write_text("[]")
        
        message = {
            "from": from_agent,
            "text": text,
            "timestamp": datetime.now().isoformat(),
            "read": False,
        }
        
        # 文件锁保证并发安全
        with open(target_file, "r+") as f:
            fcntl.flock(f, fcntl.LOCK_EX)  # 获取排他锁
            try:
                messages = json.loads(f.read() or "[]")
                messages.append(message)
                f.seek(0)
                f.truncate()
                f.write(json.dumps(messages, ensure_ascii=False, indent=2))
            finally:
                fcntl.flock(f, fcntl.LOCK_UN)  # 释放锁
    
    def receive(self) -> list[Message]:
        """读取未读消息"""
        with open(self.inbox_file, "r+") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            try:
                messages = json.loads(f.read() or "[]")
                unread = [m for m in messages if not m.get("read")]
                
                # 标记为已读
                for m in messages:
                    m["read"] = True
                f.seek(0)
                f.truncate()
                f.write(json.dumps(messages, ensure_ascii=False, indent=2))
            finally:
                fcntl.flock(f, fcntl.LOCK_UN)
        
        return [
            Message(from_agent=m["from"], text=m["text"], timestamp=m["timestamp"])
            for m in unread
        ]
```

---

## 七、上下文隔离——同一进程内多 Agent 不互相干扰

### 7.1 为什么需要隔离

当多个 Agent 在同一进程运行时（如 in-process teammate），它们共享全局状态。如果不隔离，Agent A 的工具执行可能错误归因到 Agent B。

### 7.2 用 threading.local() 实现隔离

```python
import threading

# 全局的上下文存储（每个线程独立）
_agent_context = threading.local()


def set_current_agent(agent_id: str, agent_name: str):
    """设置当前线程的 Agent 身份"""
    _agent_context.agent_id = agent_id
    _agent_context.agent_name = agent_name


def get_current_agent() -> tuple[str, str]:
    """获取当前线程的 Agent 身份"""
    return (
        getattr(_agent_context, "agent_id", "unknown"),
        getattr(_agent_context, "agent_name", "unknown"),
    )


def run_with_context(agent_id: str, agent_name: str, func, *args):
    """
    在指定的 Agent 上下文中运行函数。
    确保该函数（及其调用的所有工具）都知道"自己属于哪个 Agent"。
    """
    set_current_agent(agent_id, agent_name)
    try:
        return func(*args)
    finally:
        set_current_agent("unknown", "unknown")
```

### 7.3 Two-Level Abort——中断操作不杀死 Agent

```
lifecycleAbort ─── 终结整个 Agent 生命周期
     │
     └── currentWorkAbort ─── 只停止当前轮次的工作
```

用户按 Escape → 只中断当前工具执行，Agent 仍然活着等下一个任务。
用户按 Ctrl+C → 终结 Agent 生命周期。

```python
class TeammateRunner:
    """进程内队友的运行器"""
    
    def __init__(self, agent_id: str, prompt: str):
        self.agent_id = agent_id
        self.prompt = prompt
        
        # Two-Level Abort
        self.lifecycle_abort = threading.Event()    # 整个生命周期
        self.current_work_abort = threading.Event()  # 当前轮次
        
        self.messages: list[dict] = []
        self.is_idle = False
        self.pending_messages: list[str] = []  # 其他 Agent 发来的消息
    
    def run(self):
        """队友的主循环——持续运行直到被 abort"""
        
        # 初始任务
        self.messages.append({"role": "user", "content": self.prompt})
        
        while not self.lifecycle_abort.is_set():
            # 重置当前轮次的 abort
            self.current_work_abort.clear()
            
            # 执行一轮
            self._run_one_turn()
            
            # 标记空闲
            self.is_idle = True
            
            # 等待新消息或关闭信号
            while not self.lifecycle_abort.is_set():
                if self.pending_messages:
                    msg = self.pending_messages.pop(0)
                    self.messages.append({"role": "user", "content": msg})
                    self.is_idle = False
                    break
                threading.Event().wait(0.1)  # 轮询间隔
    
    def _run_one_turn(self):
        """执行一轮 Agentic Loop"""
        for _ in range(15):
            if self.current_work_abort.is_set():
                break
            
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=4000,
                system="你是团队成员。完成分配的任务。",
                messages=self.messages,
                tools=[...],  # 工具定义
            )
            
            if response.stop_reason == "end_turn":
                text = next((b.text for b in response.content if b.type == "text"), "")
                self.messages.append({"role": "assistant", "content": text})
                return
            
            # 工具调用处理...（同前）
    
    def interrupt_current(self):
        """中断当前工作（不杀死 Agent）"""
        self.current_work_abort.set()
    
    def shutdown(self):
        """关闭 Agent"""
        self.lifecycle_abort.set()
```

---

## 八、完整代码：把所有层组装起来

```python
"""
完整的多 Agent 系统实现。
Leader Agent 可以产生子 Agent（同步/异步），
通过 Task Registry 追踪状态，通过通知机制收集结果。
"""
import os
import json
import random
import string
import threading
from enum import Enum
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from anthropic import Anthropic

client = Anthropic()


# ═══════════════════════════════════════════════════════════════
# Task 系统
# ═══════════════════════════════════════════════════════════════

class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    KILLED = "killed"


def is_terminal(status: TaskStatus) -> bool:
    return status in (TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.KILLED)


@dataclass
class Task:
    id: str
    description: str
    status: TaskStatus
    start_time: float
    result: Optional[str] = None
    abort_event: threading.Event = field(default_factory=threading.Event)


def gen_id() -> str:
    return "a" + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))


class TaskRegistry:
    def __init__(self):
        self.tasks: dict[str, Task] = {}
        self._lock = threading.Lock()
    
    def register(self, task: Task):
        with self._lock:
            self.tasks[task.id] = task
    
    def complete(self, task_id: str, result: str):
        with self._lock:
            t = self.tasks.get(task_id)
            if t:
                t.status = TaskStatus.COMPLETED
                t.result = result
    
    def fail(self, task_id: str, error: str):
        with self._lock:
            t = self.tasks.get(task_id)
            if t:
                t.status = TaskStatus.FAILED
                t.result = f"ERROR: {error}"


# ═══════════════════════════════════════════════════════════════
# 子 Agent
# ═══════════════════════════════════════════════════════════════

class Worker:
    """子 Agent：独立的 messages、独立的 Agentic Loop"""
    
    def __init__(self, prompt: str, parent_system_prompt: str = None):
        self.messages: list[dict] = [{"role": "user", "content": prompt}]
        self.system_prompt = parent_system_prompt or (
            "你是一个专注的执行者。完成分配的任务后，输出简洁的结果摘要。"
        )
        self.tools = [
            {"name": "read_file", "description": "读取文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "写入文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
            {"name": "bash", "description": "执行命令",
             "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
        ]
    
    def run(self) -> str:
        for _ in range(15):
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=4000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            if response.stop_reason == "end_turn":
                return next((b.text for b in response.content if b.type == "text"), "")
            
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            results = []
            for block in response.content:
                if block.type == "tool_use":
                    r = self._exec(block.name, block.input)
                    results.append({"type": "tool_result", "tool_use_id": block.id, "content": r})
            
            self.messages.append({"role": "user", "content": results})
        
        return "[Worker 超时]"
    
    def _exec(self, name: str, inp: dict) -> str:
        if name == "read_file":
            try: return open(inp["path"]).read()[:10000]
            except Exception as e: return f"Error: {e}"
        elif name == "write_file":
            os.makedirs(os.path.dirname(inp["path"]) or ".", exist_ok=True)
            open(inp["path"], "w").write(inp["content"])
            return "OK"
        elif name == "bash":
            import subprocess
            r = subprocess.run(inp["command"], shell=True, capture_output=True, text=True, timeout=30)
            return (r.stdout + r.stderr)[:5000]
        return "Unknown"


# ═══════════════════════════════════════════════════════════════
# Leader Agent（主 Agent）
# ═══════════════════════════════════════════════════════════════

class LeaderAgent:
    """
    Leader Agent = 一个 Session。
    它能产生子 Agent、追踪任务、收集结果。
    """
    
    def __init__(self):
        self.messages: list[dict] = []
        self.task_registry = TaskRegistry()
        self.notifications: list[str] = []
        self._lock = threading.Lock()
        
        self.system_prompt = """你是一个编程助手。对于复杂任务，你可以产生子 Agent 并行工作。

使用策略：
- 简单任务：自己直接做（read_file, write_file, bash）
- 复杂任务：用 spawn_agent 产生 Worker
- 并行研究：spawn 多个异步 Worker
- 需要结果后才能继续：用同步 spawn"""
        
        self.tools = [
            {"name": "read_file", "description": "读取文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "写入文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
            {"name": "bash", "description": "执行命令",
             "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
            {"name": "spawn_agent", "description": "产生子 Agent 执行任务",
             "input_schema": {"type": "object", "properties": {
                 "task": {"type": "string", "description": "子 Agent 要执行的具体任务"},
                 "run_async": {"type": "boolean", "description": "是否异步", "default": False},
             }, "required": ["task"]}},
        ]
    
    def handle_turn(self, user_input: str) -> str:
        """Turn 层"""
        self.messages.append({"role": "user", "content": user_input})
        return self._run_leader_loop()
    
    def _run_leader_loop(self) -> str:
        """Leader 的 Agentic Loop"""
        
        for _ in range(25):
            # 注入子 Agent 完成通知
            self._inject_notifications()
            
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            if response.stop_reason == "end_turn":
                text = next((b.text for b in response.content if b.type == "text"), "")
                self.messages.append({"role": "assistant", "content": text})
                return text
            
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            results = []
            for block in response.content:
                if block.type == "tool_use":
                    r = self._execute_tool(block.name, block.input, block.id)
                    results.append(r)
            
            self.messages.append({"role": "user", "content": results})
        
        return "[Leader 循环超时]"
    
    def _execute_tool(self, name: str, inp: dict, tool_use_id: str) -> dict:
        """执行工具（包含 spawn_agent）"""
        
        if name == "spawn_agent":
            return self._spawn_agent(inp, tool_use_id)
        
        # 普通工具直接执行
        if name == "read_file":
            try:
                content = open(inp["path"]).read()[:15000]
            except Exception as e:
                content = f"Error: {e}"
        elif name == "write_file":
            os.makedirs(os.path.dirname(inp["path"]) or ".", exist_ok=True)
            open(inp["path"], "w").write(inp["content"])
            content = "OK"
        elif name == "bash":
            import subprocess
            r = subprocess.run(inp["command"], shell=True, capture_output=True, text=True, timeout=30)
            content = (r.stdout + r.stderr)[:10000]
        else:
            content = f"Unknown tool: {name}"
        
        return {"type": "tool_result", "tool_use_id": tool_use_id, "content": content}
    
    def _spawn_agent(self, inp: dict, tool_use_id: str) -> dict:
        """产生子 Agent"""
        task_id = gen_id()
        task = Task(
            id=task_id,
            description=inp["task"],
            status=TaskStatus.RUNNING,
            start_time=datetime.now().timestamp(),
        )
        self.task_registry.register(task)
        
        run_async = inp.get("run_async", False)
        
        if run_async:
            # ★ 异步：后台运行，立即返回
            thread = threading.Thread(
                target=self._worker_thread,
                args=(task_id, inp["task"]),
                daemon=True,
            )
            thread.start()
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"已启动异步 Worker (ID: {task_id})。完成后会通知你。",
            }
        else:
            # ★ 同步：阻塞等待结果
            worker = Worker(prompt=inp["task"])
            result = worker.run()
            self.task_registry.complete(task_id, result)
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Worker 完成:\n{result}",
            }
    
    def _worker_thread(self, task_id: str, prompt: str):
        """后台 Worker 线程"""
        try:
            worker = Worker(prompt=prompt)
            result = worker.run()
            self.task_registry.complete(task_id, result)
            with self._lock:
                self.notifications.append(
                    f"<task-completed id='{task_id}'>\n{result[:2000]}\n</task-completed>"
                )
        except Exception as e:
            self.task_registry.fail(task_id, str(e))
            with self._lock:
                self.notifications.append(
                    f"<task-failed id='{task_id}'>\n{str(e)}\n</task-failed>"
                )
    
    def _inject_notifications(self):
        """把子 Agent 完成通知注入到 Leader 的消息流"""
        with self._lock:
            if self.notifications:
                text = "\n".join(self.notifications)
                self.messages.append({"role": "user", "content": text})
                self.notifications.clear()


# ═══════════════════════════════════════════════════════════════
# 主入口
# ═══════════════════════════════════════════════════════════════

def main():
    agent = LeaderAgent()
    print("多 Agent 系统已启动。输入 exit 退出。\n")
    
    while True:
        user_input = input("你: ").strip()
        if user_input.lower() in ("exit", "quit"):
            break
        response = agent.handle_turn(user_input)
        print(f"\nAI: {response}\n")


if __name__ == "__main__":
    main()
```

---

## 九、完整时序图（多 Agent 协作）

```
时间  ║ 层次       ║ 用户              ║ Leader Agent              ║ Worker A       ║ Worker B
═════╬═══════════╬══════════════════╬══════════════════════════╬═══════════════╬══════════════
     ║           ║                  ║                          ║               ║
T0   ║ Session   ║ 启动             ║ 初始化                    ║               ║
     ║           ║                  ║ TaskRegistry = {}         ║               ║
     ║           ║                  ║                          ║               ║
─────╬───────────╬──────────────────╬──────────────────────────╬───────────────╬──────────────
     ║           ║                  ║                          ║               ║
T1   ║ Turn 1    ║ "重构 auth 模块" ║ messages.push(user)      ║               ║
     ║           ║                  ║                          ║               ║
     ║ Loop 1    ║                  ║ → API                    ║               ║
     ║           ║                  ║ ← "需要先研究,再实施"    ║               ║
     ║           ║                  ║                          ║               ║
     ║ Loop 2    ║                  ║ → API                    ║               ║
     ║           ║                  ║ ← spawn_agent            ║               ║
     ║           ║                  ║   ("研究 auth/ 结构",    ║               ║
     ║           ║                  ║    async=true)           ║               ║
     ║           ║                  ║ 注册 Task a7x3m9        ║               ║
     ║           ║                  ║ 启动后台线程 ───────────►║ 开始工作      ║
     ║           ║                  ║ ← "已启动 a7x3m9"       ║ Loop 1: read  ║
     ║           ║                  ║                          ║ Loop 2: read  ║
     ║ Loop 3    ║                  ║ → API                    ║ Loop 3: 输出  ║
     ║           ║                  ║ ← spawn_agent            ║ ★ 完成!       ║
     ║           ║                  ║   ("研究 tests/ 结构",   ║               ║
     ║           ║                  ║    async=true)           ║               ║
     ║           ║                  ║ 注册 Task a9k2p5        ║               ║
     ║           ║                  ║ 启动后台线程 ────────────║──────────────►║ 开始工作
     ║           ║                  ║ ← "已启动 a9k2p5"       ║               ║ Loop 1: read
     ║           ║                  ║                          ║               ║ Loop 2: 输出
     ║           ║                  ║                          ║               ║ ★ 完成!
     ║           ║                  ║                          ║               ║
     ║ (通知)    ║                  ║ ◄─── 通知: a7x3m9 完成  ║               ║
     ║ (通知)    ║                  ║ ◄─── 通知: a9k2p5 完成  ║               ║
     ║           ║                  ║ 注入通知到 messages      ║               ║
     ║           ║                  ║                          ║               ║
     ║ Loop 4    ║                  ║ → API(含两个结果)        ║               ║
     ║           ║                  ║ ← "根据研究,开始实施..." ║               ║
     ║           ║                  ║ ← spawn_agent            ║               ║
     ║           ║                  ║   ("按规格重构 auth/",   ║               ║
     ║           ║                  ║    async=false)          ║               ║
     ║           ║                  ║ [阻塞等待] ─────────────►║ 开始实施      ║
     ║           ║                  ║                          ║ Loop 1-5:     ║
     ║           ║                  ║                          ║ 改文件...     ║
     ║           ║                  ║                          ║ ★ 完成!       ║
     ║           ║                  ║ ◄─── 结果返回            ║               ║
     ║           ║                  ║                          ║               ║
     ║ Loop 5    ║                  ║ → API                    ║               ║
     ║           ║                  ║ ← "重构完成,修改了..."   ║               ║
     ║           ║ ◄────────────── ║ 返回最终回复              ║               ║
     ║           ║                  ║                          ║               ║
═════╬═══════════╬══════════════════╬══════════════════════════╬═══════════════╬══════════════
```

---

## 十、总结

**为什么多 Agent**：单 Agent 在复杂任务中面临顺序执行、上下文溢出、专注度不足三个瓶颈。多 Agent 通过分工协作解决这些问题。

**三层结构中多 Agent 的角色**：

| 层次 | 单 Agent 做什么 | 多 Agent 新增什么 |
|------|---------------|-----------------|
| Session | messages[]、system prompt | Task Registry、Agent 生命周期 |
| Turn | 用户输入→回复 | 拆分任务、收集子 Agent 结果 |
| Agentic Loop | LLM→工具→循环 | spawn_agent 工具、通知注入 |

**两种子 Agent 模式**：

| 模式 | 行为 | 适用场景 |
|------|------|---------|
| 同步 | 阻塞等待结果 | 需要结果才能继续的任务 |
| 异步 | Fire-and-Forget | 可并行的独立任务 |

**通信方式**：

| 方式 | 适用场景 | 延迟 |
|------|---------|------|
| 通知注入 | 异步 Agent 完成 → Leader | 下一次 Loop |
| 内存队列 | 同进程 Agent | 微秒 |
| 文件邮箱 | 跨进程 Agent | 毫秒 |

**核心设计原则**：

1. **统一任务抽象**——所有异步工作都用 Task 追踪
2. **扁平团队**——只有 Leader 能创建 Worker，Worker 不能创建 Worker
3. **渐进式隔离**——从共享进程到独立进程到远程容器，按需选择
4. **协商式关闭**——Leader 请求关闭，Worker 可以拒绝（如果正在做关键操作）
5. **缓存为王**——Fork 模式复用父级 system prompt，最大化 API 缓存命中
