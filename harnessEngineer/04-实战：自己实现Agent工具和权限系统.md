# 实战：自己实现 Agent 工具和权限系统

> 本文从"自己做 Agent"的视角，把工具注册、执行管线、权限决策讲清楚。
> 核心问题：工具怎么注册？执行前谁来决定"允许还是拒绝"？安全边界怎么画？

---

## 〇、先把层次理清：会话、对话、循环

和前文一样，三层嵌套关系不变。但工具和权限系统在每一层的角色不同：

```
┌═══════════════════════════════════════════════════════════════════┐
║  Session（会话）                                                   ║
║  ═══════════════                                                   ║
║  工具系统的角色：                                                   ║
║  • 注册所有可用工具（只做一次）                                      ║
║  • 加载权限规则（从配置文件）                                        ║
║  • 确定权限模式（default / bypass / auto）                          ║
║                                                                    ║
║  ┌───────────────────────────────────────────────────────────┐   ║
║  │  Turn（对话轮次）                                           │   ║
║  │  ═══════════════                                            │   ║
║  │  工具系统的角色：                                            │   ║
║  │  • 每轮可能有新工具上线（MCP 热加载）→ 动态更新工具池         │   ║
║  │  • 权限规则可能在对话中被用户修改（"永远允许 git *"）        │   ║
║  │                                                              │   ║
║  │  ┌───────────────────────────────────────────────────┐     │   ║
║  │  │  Agentic Loop（Agent 循环）                         │     │   ║
║  │  │  ═════════════════════════                          │     │   ║
║  │  │  工具系统的角色：                                    │     │   ║
║  │  │  • LLM 返回 tool_use → 进入执行管线                 │     │   ║
║  │  │  • 输入验证 → 权限决策 → 执行 → 结果处理             │     │   ║
║  │  │  • 每次 Loop 可能执行 0~N 个工具                     │     │   ║
║  │  │                                                      │     │   ║
║  │  │  Loop 1: LLM → tool_use[read_file] → 权限检查        │     │   ║
║  │  │          → 允许（只读） → 执行 → 返回结果            │     │   ║
║  │  │  Loop 2: LLM → tool_use[run_command("rm -rf")]       │     │   ║
║  │  │          → 权限检查 → ❌ 拒绝（危险命令）            │     │   ║
║  │  │  Loop 3: LLM → tool_use[write_file] → 权限检查       │     │   ║
║  │  │          → 询问用户 → 用户同意 → 执行                │     │   ║
║  │  └───────────────────────────────────────────────────┘     │   ║
║  └───────────────────────────────────────────────────────────┘   ║
╚═══════════════════════════════════════════════════════════════════╝
```

### 每一层分别管理什么？

| 层次 | 工具系统职责 | 权限系统职责 |
|------|------------|------------|
| Session（会话） | 注册工具定义、组装工具池 | 加载规则文件、确定模式 |
| Turn（对话轮次） | 热加载新工具、动态过滤 | 用户可在对话中修改规则 |
| Agentic Loop（循环） | 验证输入 → 执行 → 返回结果 | 每次工具调用前做权限决策 |

---

## 一、Session 层：工具注册与权限初始化

### 1.1 工具注册——"所有工具的唯一真相来源"

Session 开始时，系统需要知道"有哪些工具可用"。这不是散落在各处的配置，而是一个集中注册表：

```
用户执行 Agent
      │
      ▼
┌─────────────────────────────────────┐
│  Session 初始化                       │
│                                       │
│  1. 构建工具注册表                    │
│     ├─ 内置工具: read_file, write_file, bash, grep...
│     ├─ 条件工具: 仅 Windows → PowerShell
│     │           仅实验组 → CoordinatorTool
│     └─ 外部工具: MCP 服务器提供的工具
│                                       │
│  2. 组装工具池（去重、优先级）         │
│     内置工具 > 外部工具（同名则内置优先）│
│                                       │
│  3. 加载权限规则                      │
│     ├─ 企业策略（最高优先级）         │
│     ├─ 用户设置 (~/.claude/settings)  │
│     ├─ 项目设置 (.claude/settings)    │
│     └─ 会话临时规则（最低优先级）     │
│                                       │
│  4. 确定权限模式                      │
│     default / acceptEdits / bypass    │
│                                       │
└─────────────────────────────────────┘
```

### 1.2 工具的核心属性

每个工具不只是"名字+执行函数"。它还带有安全属性，这些属性决定了权限系统怎么对待它：

| 属性 | 含义 | 默认值（Fail-Closed） |
|------|------|------|
| `is_read_only` | 是否只读（不改任何东西） | `False`（假设会写） |
| `is_concurrency_safe` | 能否与其他工具并发执行 | `False`（假设不能并发） |
| `is_destructive` | 是否不可逆（如 rm -rf） | `False` |

**Fail-Closed 哲学**：如果开发者忘了设置这些属性，默认值是最保守的。新工具自动被当作"不可读只、不可并发、需要权限"——安全不依赖于开发者记得做正确的事。

### 1.3 代码实现

```python
from dataclasses import dataclass, field
from typing import Callable, Any
from enum import Enum


class PermissionMode(Enum):
    """权限模式"""
    DEFAULT = "default"              # 每次写操作都问用户
    ACCEPT_EDITS = "accept_edits"   # 文件编辑自动通过，shell 仍需确认
    BYPASS = "bypass"                # 跳过大部分权限（但 deny rule 不能绕过！）


@dataclass
class ToolDefinition:
    """工具定义——注册表中的一条记录"""
    name: str
    description: str
    input_schema: dict
    execute: Callable[[dict], str]
    
    # ★ 安全属性（默认值是 fail-closed 的）
    is_read_only: bool = False       # 默认假设会写
    is_concurrency_safe: bool = False  # 默认假设并发不安全
    is_destructive: bool = False     # 默认假设非破坏性
    
    # 权限匹配信息
    permission_key: str = ""         # 用于规则匹配的 key（默认 = name）
    
    def __post_init__(self):
        if not self.permission_key:
            self.permission_key = self.name


@dataclass
class PermissionRule:
    """一条权限规则"""
    tool_name: str          # 匹配的工具名（支持通配）
    content_pattern: str = ""  # 内容匹配模式（如 "prefix:git *"）
    behavior: str = "ask"   # allow / deny / ask
    source: str = "session" # 来源：enterprise > user > project > session


class ToolRegistry:
    """
    工具注册表——Session 级别。
    所有工具在此注册，权限规则在此加载。
    """
    
    def __init__(self, permission_mode: PermissionMode = PermissionMode.DEFAULT):
        self.tools: dict[str, ToolDefinition] = {}
        self.rules: list[PermissionRule] = []
        self.mode = permission_mode
        # 拒绝追踪（防止 AI 无限重试被拒绝的操作）
        self.consecutive_denials = 0
        self.total_denials = 0
    
    def register(self, tool: ToolDefinition):
        """注册一个工具（内置优先，同名外部工具被忽略）"""
        if tool.name not in self.tools:
            self.tools[tool.name] = tool
    
    def load_rules(self, rules_config: list[dict]):
        """
        从配置加载权限规则。
        优先级通过 source 字段区分：enterprise > user > project > session
        """
        for r in rules_config:
            self.rules.append(PermissionRule(
                tool_name=r["tool"],
                content_pattern=r.get("pattern", ""),
                behavior=r["behavior"],
                source=r.get("source", "session"),
            ))
    
    def get_tool(self, name: str) -> ToolDefinition | None:
        return self.tools.get(name)
    
    def get_all_definitions(self) -> list[dict]:
        """返回 API 需要的工具定义列表"""
        return [
            {
                "name": t.name,
                "description": t.description,
                "input_schema": t.input_schema,
            }
            for t in self.tools.values()
        ]
```

---

## 二、Turn 层：工具池的动态更新

### 2.1 为什么 Turn 层要关心工具

大部分情况下工具池在 Session 期间不变。但有两个场景需要在 Turn 级别更新：

1. **MCP 工具热加载**：外部服务器启动了新工具，或者旧服务器断开了
2. **Deny 规则过滤**：用户在对话中说"永远不允许 rm 命令"，后续 Turn 的工具池要实时反映这个规则

```
Turn N 开始
    │
    ├─ 检查 MCP 服务器状态 → 有新工具？合并进工具池
    │
    ├─ 检查规则变更 → 有 deny 规则匹配的工具？从池中移除
    │
    └─ 最终工具池 = 过滤后的工具定义列表 → 传给 API
```

### 2.2 代码实现

```python
class Agent:
    # ... __init__ 中创建 self.registry = ToolRegistry(...)
    
    def _get_active_tools(self) -> list[dict]:
        """
        Turn 级别：获取当前可用的工具列表。
        每次 Turn 调用 API 前都要调这个，确保工具池是最新的。
        """
        all_tools = self.registry.get_all_definitions()
        
        # 过滤掉被 deny 规则完全禁止的工具
        active_tools = []
        for tool_def in all_tools:
            # 如果有全工具级别的 deny 规则，直接排除
            if self._has_full_deny(tool_def["name"]):
                continue
            active_tools.append(tool_def)
        
        return active_tools
    
    def _has_full_deny(self, tool_name: str) -> bool:
        """检查是否有无条件 deny 规则完全禁止该工具"""
        for rule in self.registry.rules:
            if (rule.behavior == "deny" 
                and self._match_tool_name(rule.tool_name, tool_name)
                and not rule.content_pattern):  # 无内容条件 = 全工具禁止
                return True
        return False
    
    def _match_tool_name(self, pattern: str, name: str) -> bool:
        """工具名匹配（支持前缀通配）"""
        if pattern == name:
            return True
        if pattern.endswith("*"):
            return name.startswith(pattern[:-1])
        return False
```

---

## 三、Agentic Loop 层：工具执行管线

### 3.1 八阶段执行管线

这是整个系统最核心的部分。每次 LLM 返回一个 `tool_use` block，都要走完这个管线：

```
LLM 返回 tool_use
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│  Stage 1: 输入验证（Input Validation）                    │
│  ├─ 工具存在吗？                                         │
│  ├─ 参数类型正确吗？（schema 验证）                       │
│  └─ 业务逻辑合理吗？（如：路径不能是空字符串）            │
│                        ↓                                  │
│  Stage 2: 安全预处理（Input Preprocessing）               │
│  └─ 剥离可能被注入的字段、规范化路径                      │
│                        ↓                                  │
│  Stage 3: 前置钩子（Pre-Hooks）                          │
│  └─ 扩展点：可以修改输入、提前允许/拒绝                   │
│                        ↓                                  │
│  Stage 4: ★ 权限决策（Permission Decision）              │
│  └─ 核心算法：规则匹配 → 模式检查 → 安全检查 → 用户确认  │
│                        ↓                                  │
│  Stage 5: 执行（Execution）                              │
│  └─ tool.execute(validated_input)                         │
│                        ↓                                  │
│  Stage 6: 后置钩子（Post-Hooks）                         │
│  └─ 可以修改输出、添加额外上下文                          │
│                        ↓                                  │
│  Stage 7: 结果处理（Result Processing）                   │
│  └─ 过大的结果截断、持久化到磁盘                          │
│                        ↓                                  │
│  Stage 8: 返回（Return to Loop）                         │
│  └─ 结果 push 到 messages[]，继续下一次 Loop              │
└─────────────────────────────────────────────────────────┘
```

### 3.2 核心权限算法

Stage 4 是整个系统最复杂的部分。它的决策逻辑是一个优先级管线：

```
工具调用请求进入
      │
      ▼
┌─────────────────────────────────────────────────────┐
│  Step 1: Deny 规则（无条件拒绝，任何模式都不能绕过）  │
│  ├─ 全工具 deny: Bash → 匹配 → 立即 DENY            │
│  └─ 内容 deny: Bash(rm -rf *) → 匹配 → 立即 DENY   │
│                     ↓ (未匹配)                       │
│  Step 2: Safety Check（安全检查，bypass 也不能绕过）  │
│  └─ 操作目标是 .git/ .ssh/ /etc/ ？                  │
│     → 是 → 必须询问用户（无论什么模式）              │
│                     ↓ (未触发)                        │
│  Step 3: Ask 规则                                    │
│  └─ 匹配 → 返回 ASK                                  │
│                     ↓ (未匹配)                        │
│  Step 4: 模式检查                                    │
│  ├─ bypass → ALLOW                                   │
│  ├─ accept_edits + 是文件操作 → ALLOW                │
│  └─ default → 继续到 Step 5                          │
│                     ↓                                │
│  Step 5: Allow 规则                                  │
│  └─ 匹配 → ALLOW                                    │
│                     ↓ (未匹配)                        │
│  Step 6: 工具自身属性                                │
│  └─ is_read_only → ALLOW（只读操作不需要确认）       │
│                     ↓ (非只读)                        │
│  Step 7: 默认 → ASK（询问用户）                      │
│                                                      │
└─────────────────────────────────────────────────────┘
```

**最重要的设计原则**：
- **Deny 规则是绝对的**——`bypass` 模式、钩子的 `allow`、任何东西都不能覆盖 deny 规则
- **Safety Check 是绝对的**——`.git/`、`.ssh/` 等路径永远需要确认
- **只有在 deny 和 safety 都通过后，模式才起作用**

### 3.3 代码实现

```python
import fnmatch
from enum import Enum


class Decision(Enum):
    ALLOW = "allow"
    DENY = "deny"
    ASK = "ask"


# 受保护路径——无论什么模式都不能静默修改
SAFETY_CHECK_PATHS = [
    ".git/", ".git\\",
    ".ssh/", ".ssh\\",
    ".claude/",
    ".env",
    "/etc/",
]


class PermissionEngine:
    """
    权限引擎——Agentic Loop 中每次工具调用前运行。
    实现 fail-closed 的分步决策管线。
    """
    
    def __init__(self, registry: ToolRegistry):
        self.registry = registry
    
    def check(self, tool_name: str, tool_input: dict) -> Decision:
        """
        核心权限决策算法。
        返回 ALLOW / DENY / ASK。
        """
        tool = self.registry.get_tool(tool_name)
        if tool is None:
            return Decision.DENY  # 未知工具 → 拒绝
        
        # ═══ Step 1: Deny 规则（最高优先级，不可覆盖）═══
        deny_rule = self._find_matching_rule(tool_name, tool_input, "deny")
        if deny_rule:
            return Decision.DENY
        
        # ═══ Step 2: Safety Check（bypass 也不能绕过）═══
        if self._is_safety_sensitive(tool_name, tool_input):
            return Decision.ASK
        
        # ═══ Step 3: Ask 规则 ═══
        ask_rule = self._find_matching_rule(tool_name, tool_input, "ask")
        if ask_rule:
            return Decision.ASK
        
        # ═══ Step 4: 模式检查 ═══
        if self.registry.mode == PermissionMode.BYPASS:
            return Decision.ALLOW
        
        if (self.registry.mode == PermissionMode.ACCEPT_EDITS 
            and self._is_file_operation(tool_name)):
            return Decision.ALLOW
        
        # ═══ Step 5: Allow 规则 ═══
        allow_rule = self._find_matching_rule(tool_name, tool_input, "allow")
        if allow_rule:
            return Decision.ALLOW
        
        # ═══ Step 6: 工具自身属性 ═══
        if tool.is_read_only:
            return Decision.ALLOW
        
        # ═══ Step 7: 默认 → ASK ═══
        return Decision.ASK
    
    def _find_matching_rule(
        self, tool_name: str, tool_input: dict, behavior: str
    ) -> PermissionRule | None:
        """查找匹配的权限规则（按优先级排序）"""
        
        # 优先级顺序
        SOURCE_PRIORITY = ["enterprise", "user", "project", "session"]
        
        # 按优先级从高到低遍历
        for source in SOURCE_PRIORITY:
            for rule in self.registry.rules:
                if rule.source != source:
                    continue
                if rule.behavior != behavior:
                    continue
                if not self._match_tool_name(rule.tool_name, tool_name):
                    continue
                # 如果有内容模式，检查内容是否匹配
                if rule.content_pattern:
                    if not self._match_content(rule.content_pattern, tool_input):
                        continue
                return rule  # 找到匹配规则
        
        return None
    
    def _match_tool_name(self, pattern: str, name: str) -> bool:
        """工具名匹配"""
        if pattern == name:
            return True
        # 通配符匹配：如 "mcp__*" 匹配所有 MCP 工具
        return fnmatch.fnmatch(name, pattern)
    
    def _match_content(self, pattern: str, tool_input: dict) -> bool:
        """
        内容模式匹配。
        支持两种语法：
        - prefix:git *  → 匹配以 "git " 开头的命令
        - rm -rf *      → 通配符匹配
        """
        # 提取要匹配的文本（command 字段或 path 字段）
        text = tool_input.get("command", "") or tool_input.get("path", "")
        
        if pattern.startswith("prefix:"):
            prefix = pattern[7:]  # 去掉 "prefix:"
            if prefix.endswith("*"):
                return text.startswith(prefix[:-1])
            return text.startswith(prefix)
        
        # 通配符匹配
        return fnmatch.fnmatch(text, pattern)
    
    def _is_safety_sensitive(self, tool_name: str, tool_input: dict) -> bool:
        """安全检查：操作目标是否涉及受保护路径"""
        # 获取操作路径
        path = tool_input.get("path", "") or tool_input.get("command", "")
        
        for protected in SAFETY_CHECK_PATHS:
            if protected in path:
                return True
        return False
    
    def _is_file_operation(self, tool_name: str) -> bool:
        """判断是否是文件操作（accept_edits 模式自动通过）"""
        return tool_name in ("write_file", "edit_file", "read_file")
```

---

## 四、并发控制：哪些工具能同时跑

### 4.1 为什么需要并发控制

LLM 一次可以返回多个 tool_use block。比如："同时读取 5 个文件"。如果每个都串行执行，太慢了。但如果 LLM 同时返回 "读文件" 和 "写文件"，就不能并发——写可能依赖读的结果。

### 4.2 分区策略

```
LLM 返回多个 tool_use:
[read_file A, read_file B, read_file C, write_file X, read_file D, bash "deploy"]

按 is_concurrency_safe 分区：

批次 1（并发）: [read_file A, read_file B, read_file C]  ← 都是只读，并发安全
批次 2（串行）: [write_file X]                           ← 写操作，必须串行
批次 3（并发）: [read_file D]                            ← 只读
批次 4（串行）: [bash "deploy"]                          ← shell，必须串行
```

### 4.3 代码实现

```python
import asyncio
from typing import List


def partition_tool_calls(
    tool_calls: list[dict], registry: ToolRegistry
) -> list[list[dict]]:
    """
    将多个工具调用分区为串行/并发批次。
    连续的 concurrency_safe 工具合并为一个并发批。
    """
    batches = []
    current_concurrent_batch = []
    
    for call in tool_calls:
        tool = registry.get_tool(call["name"])
        
        if tool and tool.is_concurrency_safe:
            # 可以并发 → 加入当前并发批
            current_concurrent_batch.append(call)
        else:
            # 不能并发 → 先提交之前的并发批，再单独成批
            if current_concurrent_batch:
                batches.append(current_concurrent_batch)
                current_concurrent_batch = []
            batches.append([call])  # 串行批（只有一个元素）
    
    # 别忘了最后的并发批
    if current_concurrent_batch:
        batches.append(current_concurrent_batch)
    
    return batches


async def execute_batches(
    batches: list[list[dict]], 
    registry: ToolRegistry,
    permission_engine: PermissionEngine,
) -> list[dict]:
    """执行分区后的批次"""
    all_results = []
    
    for batch in batches:
        if len(batch) == 1:
            # 串行执行
            result = await execute_single_tool(
                batch[0], registry, permission_engine
            )
            all_results.append(result)
        else:
            # 并发执行
            tasks = [
                execute_single_tool(call, registry, permission_engine)
                for call in batch
            ]
            results = await asyncio.gather(*tasks)
            all_results.extend(results)
    
    return all_results


async def execute_single_tool(
    call: dict, registry: ToolRegistry, permission_engine: PermissionEngine
) -> dict:
    """单个工具的完整执行管线"""
    tool_name = call["name"]
    tool_input = call["input"]
    tool_use_id = call["id"]
    
    # Stage 1: 输入验证
    tool = registry.get_tool(tool_name)
    if tool is None:
        return {"tool_use_id": tool_use_id, "content": f"Error: Unknown tool '{tool_name}'"}
    
    # Stage 4: 权限决策
    decision = permission_engine.check(tool_name, tool_input)
    
    if decision == Decision.DENY:
        # 记录拒绝（用于拒绝追踪）
        registry.consecutive_denials += 1
        registry.total_denials += 1
        return {
            "tool_use_id": tool_use_id, 
            "content": f"Permission denied: tool '{tool_name}' is not allowed for this operation.",
            "is_error": True,
        }
    
    if decision == Decision.ASK:
        # 询问用户（同步阻塞）
        user_approved = await ask_user_permission(tool_name, tool_input)
        if not user_approved:
            registry.consecutive_denials += 1
            registry.total_denials += 1
            return {
                "tool_use_id": tool_use_id,
                "content": "Permission denied by user.",
                "is_error": True,
            }
    
    # 权限通过 → 重置连续拒绝计数
    registry.consecutive_denials = 0
    
    # Stage 5: 执行
    try:
        result = tool.execute(tool_input)
    except Exception as e:
        return {"tool_use_id": tool_use_id, "content": f"Error: {e}", "is_error": True}
    
    # Stage 7: 结果处理（截断过大的结果）
    if len(result) > 50000:
        result = result[:20000] + "\n...[truncated]...\n" + result[-5000:]
    
    return {"tool_use_id": tool_use_id, "content": result}


async def ask_user_permission(tool_name: str, tool_input: dict) -> bool:
    """询问用户是否允许执行"""
    print(f"\n⚠️  Agent 想要执行: {tool_name}")
    print(f"   参数: {tool_input}")
    response = input("   允许? (y/n/always): ").strip().lower()
    return response in ("y", "yes", "always")
```

---

## 五、拒绝追踪：防止 AI 死循环

### 5.1 问题场景

AI 想执行 `rm -rf /`，被拒绝了。但它不死心，换个写法再试：`bash -c "rm -rf /"`。又被拒绝。再试……

如果没有拒绝追踪，AI 会陷入无限循环。

### 5.2 安全阀机制

```
连续拒绝 = 0

Loop 1: bash "rm -rf /" → DENY → 连续拒绝 = 1
Loop 2: bash "rm -rf /*" → DENY → 连续拒绝 = 2
Loop 3: write_file("/usr/bin/...") → DENY → 连续拒绝 = 3
                                                    │
                                                    ▼
                                    ★ 达到阈值！注入警告消息：
                                    "你已被连续拒绝 3 次。
                                     请换一种方式完成任务，
                                     或告诉用户你无法完成。"

如果继续拒绝 → 达到硬阈值 → 强制终止 Agent 循环
```

### 5.3 代码实现

```python
CONSECUTIVE_DENIAL_WARNING = 3
CONSECUTIVE_DENIAL_ABORT = 5
TOTAL_DENIAL_ABORT = 10


class DenialTracker:
    """拒绝追踪器"""
    
    def __init__(self):
        self.consecutive = 0
        self.total = 0
    
    def record_denial(self):
        self.consecutive += 1
        self.total += 1
    
    def record_success(self):
        self.consecutive = 0  # 一次成功就重置连续计数
    
    def should_warn(self) -> bool:
        return self.consecutive >= CONSECUTIVE_DENIAL_WARNING
    
    def should_abort(self) -> bool:
        return (
            self.consecutive >= CONSECUTIVE_DENIAL_ABORT
            or self.total >= TOTAL_DENIAL_ABORT
        )
    
    def get_warning_message(self) -> str:
        return (
            f"你已被连续拒绝 {self.consecutive} 次操作。"
            f"请换一种安全的方式完成任务，或告诉用户你无法完成这个请求。"
        )
```

---

## 六、Bash 的特殊处理：为什么 Shell 命令最危险

### 6.1 为什么 Bash 需要特殊对待

文件操作（read/write）的影响范围是明确的——你知道它操作哪个文件。但 shell 命令可以做**任何事**：安装软件、发网络请求、修改系统配置、删除整个磁盘。所以 Bash 有独立的权限逻辑：

```
Bash 命令进入
      │
      ▼
┌─────────────────────────────────────────┐
│  1. 解析命令 AST（提取真实操作）          │
│     "git commit && rm -rf /"            │
│     → 拆分为: ["git commit", "rm -rf /"] │
│                                          │
│  2. 每个子命令独立做权限检查              │
│     "git commit" → prefix:git * → ALLOW  │
│     "rm -rf /" → 危险模式检测 → DENY     │
│                                          │
│  3. 任一子命令被 DENY → 整个命令 DENY    │
│                                          │
│  4. 危险模式检测：                        │
│     rm -rf, chmod 777, curl | bash,      │
│     dd if=, mkfs, :(){ :|:& };:         │
│     → 无论什么规则/模式，都至少 ASK       │
└─────────────────────────────────────────┘
```

### 6.2 代码实现

```python
import shlex

# 危险命令模式
DANGEROUS_PATTERNS = [
    "rm -rf /",
    "rm -rf ~",
    "rm -rf *",
    "chmod 777",
    "curl * | bash",
    "curl * | sh",
    "wget * | bash",
    "dd if=",
    "mkfs",
    "> /dev/sd",
    ":(){ :|:& };:",
    "fork bomb",
]


class BashPermissionChecker:
    """Bash 专用的权限检查器"""
    
    def check_command(self, command: str) -> Decision:
        """
        Bash 命令的权限检查。
        1. 拆分复合命令
        2. 每个子命令独立检查
        3. 最严格的结果为最终结果
        """
        # 拆分复合命令（&& || ; |）
        sub_commands = self._split_compound(command)
        
        worst_decision = Decision.ALLOW
        
        for sub_cmd in sub_commands:
            decision = self._check_single(sub_cmd)
            # 取最严格的决策
            if decision == Decision.DENY:
                return Decision.DENY
            if decision == Decision.ASK:
                worst_decision = Decision.ASK
        
        return worst_decision
    
    def _split_compound(self, command: str) -> list[str]:
        """拆分复合命令"""
        # 简单实现：按 && || ; | 分割
        import re
        parts = re.split(r'\s*(?:&&|\|\||;|\|)\s*', command)
        return [p.strip() for p in parts if p.strip()]
    
    def _check_single(self, command: str) -> Decision:
        """检查单个子命令"""
        cmd_lower = command.lower().strip()
        
        # 检查危险模式
        for pattern in DANGEROUS_PATTERNS:
            if pattern in cmd_lower:
                return Decision.DENY
        
        # 检查是否修改系统文件
        if any(path in command for path in ["/etc/", "/usr/bin/", "/System/"]):
            return Decision.ASK
        
        return Decision.ALLOW  # 交给通用权限系统继续判断
```

---

## 七、完整代码：把所有层组装起来

```python
"""
完整的 Agent 工具和权限系统实现。
三层结构：Session → Turn → Agentic Loop。
每次工具调用都经过完整的权限管线。
"""
import os
import json
import fnmatch
import asyncio
import threading
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Any
from anthropic import Anthropic

client = Anthropic()


# ═══════════════════════════════════════════════════════════════
# 基础类型定义
# ═══════════════════════════════════════════════════════════════

class PermissionMode(Enum):
    DEFAULT = "default"
    ACCEPT_EDITS = "accept_edits"
    BYPASS = "bypass"


class Decision(Enum):
    ALLOW = "allow"
    DENY = "deny"
    ASK = "ask"


@dataclass
class ToolDefinition:
    name: str
    description: str
    input_schema: dict
    execute: Callable[[dict], str]
    is_read_only: bool = False
    is_concurrency_safe: bool = False
    is_destructive: bool = False


@dataclass
class PermissionRule:
    tool_name: str
    content_pattern: str = ""
    behavior: str = "ask"
    source: str = "session"


SAFETY_CHECK_PATHS = [".git/", ".ssh/", ".claude/", ".env", "/etc/"]

DANGEROUS_BASH_PATTERNS = [
    "rm -rf /", "rm -rf ~", "rm -rf *", "chmod 777",
    "curl * | bash", "dd if=", "mkfs", "> /dev/sd",
]


# ═══════════════════════════════════════════════════════════════
# 权限引擎
# ═══════════════════════════════════════════════════════════════

class PermissionEngine:
    """权限决策引擎"""
    
    def __init__(self, tools: dict, rules: list, mode: PermissionMode):
        self.tools = tools
        self.rules = rules
        self.mode = mode
        self.denial_consecutive = 0
        self.denial_total = 0
    
    def check(self, tool_name: str, tool_input: dict) -> Decision:
        """核心权限决策——分步管线"""
        
        tool = self.tools.get(tool_name)
        if not tool:
            return Decision.DENY
        
        # Step 1: Deny 规则（不可覆盖）
        if self._match_rule(tool_name, tool_input, "deny"):
            return Decision.DENY
        
        # Step 2: Safety Check（bypass 也不能绕过）
        path = tool_input.get("path", "") or tool_input.get("command", "")
        if any(p in path for p in SAFETY_CHECK_PATHS):
            return Decision.ASK
        
        # Step 3: Bash 危险模式检测
        if tool_name == "bash":
            cmd = tool_input.get("command", "").lower()
            if any(p in cmd for p in DANGEROUS_BASH_PATTERNS):
                return Decision.DENY
        
        # Step 4: Ask 规则
        if self._match_rule(tool_name, tool_input, "ask"):
            return Decision.ASK
        
        # Step 5: 模式检查
        if self.mode == PermissionMode.BYPASS:
            return Decision.ALLOW
        if self.mode == PermissionMode.ACCEPT_EDITS and tool_name in (
            "write_file", "edit_file", "read_file"
        ):
            return Decision.ALLOW
        
        # Step 6: Allow 规则
        if self._match_rule(tool_name, tool_input, "allow"):
            return Decision.ALLOW
        
        # Step 7: 只读工具自动通过
        if tool.is_read_only:
            return Decision.ALLOW
        
        # Step 8: 默认 → ASK
        return Decision.ASK
    
    def record_denial(self):
        self.denial_consecutive += 1
        self.denial_total += 1
    
    def record_success(self):
        self.denial_consecutive = 0
    
    def should_abort(self) -> bool:
        return self.denial_consecutive >= 5 or self.denial_total >= 10
    
    def _match_rule(self, tool_name: str, tool_input: dict, behavior: str) -> bool:
        for rule in self.rules:
            if rule.behavior != behavior:
                continue
            if not fnmatch.fnmatch(tool_name, rule.tool_name):
                continue
            if rule.content_pattern:
                text = tool_input.get("command", "") or tool_input.get("path", "")
                if rule.content_pattern.startswith("prefix:"):
                    prefix = rule.content_pattern[7:].rstrip("*").rstrip()
                    if not text.startswith(prefix):
                        continue
                elif not fnmatch.fnmatch(text, rule.content_pattern):
                    continue
            return True
        return False


# ═══════════════════════════════════════════════════════════════
# Agent 主类
# ═══════════════════════════════════════════════════════════════

class Agent:
    """
    一个 Agent = 一个 Session。
    工具和权限在 Session 初始化时注册和加载。
    """
    
    def __init__(self, permission_mode: PermissionMode = PermissionMode.DEFAULT):
        """=== Session 初始化 ==="""
        
        # ★ 对话历史
        self.messages: list[dict] = []
        
        # 注册工具
        self.tools: dict[str, ToolDefinition] = {}
        self._register_builtin_tools()
        
        # 加载权限规则
        self.rules = self._load_permission_rules()
        
        # 创建权限引擎
        self.permission_engine = PermissionEngine(
            self.tools, self.rules, permission_mode
        )
        
        # 系统提示
        self.system_prompt = self._build_system_prompt()
    
    # ─────────────────────────────────────────────────────
    # Session 层：工具注册
    # ─────────────────────────────────────────────────────
    
    def _register_builtin_tools(self):
        """注册内置工具（Session 只做一次）"""
        
        self.tools["read_file"] = ToolDefinition(
            name="read_file",
            description="读取文件内容",
            input_schema={
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": ["path"],
            },
            execute=self._exec_read_file,
            is_read_only=True,          # ★ 只读
            is_concurrency_safe=True,    # ★ 可并发
        )
        
        self.tools["write_file"] = ToolDefinition(
            name="write_file",
            description="写入文件",
            input_schema={
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                },
                "required": ["path", "content"],
            },
            execute=self._exec_write_file,
            is_read_only=False,
            is_concurrency_safe=False,   # ★ 写操作不可并发
        )
        
        self.tools["bash"] = ToolDefinition(
            name="bash",
            description="执行 shell 命令",
            input_schema={
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"],
            },
            execute=self._exec_bash,
            is_read_only=False,
            is_concurrency_safe=False,
            is_destructive=True,         # ★ 可能不可逆
        )
        
        self.tools["grep"] = ToolDefinition(
            name="grep",
            description="搜索文件内容",
            input_schema={
                "type": "object",
                "properties": {
                    "pattern": {"type": "string"},
                    "path": {"type": "string"},
                },
                "required": ["pattern"],
            },
            execute=self._exec_grep,
            is_read_only=True,
            is_concurrency_safe=True,
        )
    
    def _load_permission_rules(self) -> list[PermissionRule]:
        """加载权限规则（从配置文件）"""
        rules = []
        
        # 示例：项目级别的规则
        # 实际实现中会从 .claude/settings.json 读取
        default_rules = [
            # 允许所有 git 命令
            {"tool": "bash", "pattern": "prefix:git ", "behavior": "allow", "source": "project"},
            # 禁止危险操作
            {"tool": "bash", "pattern": "rm -rf /", "behavior": "deny", "source": "enterprise"},
        ]
        
        for r in default_rules:
            rules.append(PermissionRule(
                tool_name=r["tool"],
                content_pattern=r.get("pattern", ""),
                behavior=r["behavior"],
                source=r.get("source", "session"),
            ))
        
        return rules
    
    # ─────────────────────────────────────────────────────
    # Turn 层：处理一次用户交互
    # ─────────────────────────────────────────────────────
    
    def handle_turn(self, user_input: str) -> str:
        """Turn 层：用户输入 → 完整回复"""
        
        self.messages.append({"role": "user", "content": user_input})
        final_response = self._run_agentic_loop()
        return final_response
    
    # ─────────────────────────────────────────────────────
    # Agentic Loop 层：多次 LLM 调用 + 工具执行
    # ─────────────────────────────────────────────────────
    
    def _run_agentic_loop(self) -> str:
        """Agentic Loop：调用 LLM → 工具执行 → 循环"""
        
        max_loops = 20
        
        for _ in range(max_loops):
            # 检查拒绝追踪
            if self.permission_engine.should_abort():
                abort_msg = "已达到拒绝上限，终止当前任务。"
                self.messages.append({"role": "assistant", "content": abort_msg})
                return abort_msg
            
            # 调用 LLM
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self._get_tool_definitions(),
            )
            
            # 纯文本回复 → Turn 结束
            if response.stop_reason == "end_turn":
                text = next(
                    (b.text for b in response.content if b.type == "text"), ""
                )
                self.messages.append({"role": "assistant", "content": text})
                return text
            
            # 有工具调用 → 走执行管线
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            # 执行每个工具调用（走完整权限管线）
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = self._execute_with_permission(
                        block.name, block.input, block.id
                    )
                    tool_results.append(result)
            
            self.messages.append({"role": "user", "content": tool_results})
        
        return "[错误] 循环次数超过上限。"
    
    def _execute_with_permission(
        self, tool_name: str, tool_input: dict, tool_use_id: str
    ) -> dict:
        """
        ★ 核心：带权限检查的工具执行管线。
        每次工具调用都经过这里。
        """
        
        # Stage 1: 输入验证
        tool = self.tools.get(tool_name)
        if not tool:
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Error: Unknown tool '{tool_name}'",
                "is_error": True,
            }
        
        # Stage 4: ★ 权限决策
        decision = self.permission_engine.check(tool_name, tool_input)
        
        if decision == Decision.DENY:
            self.permission_engine.record_denial()
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Permission DENIED for {tool_name}. This operation is not allowed.",
                "is_error": True,
            }
        
        if decision == Decision.ASK:
            # 询问用户
            print(f"\n⚠️  Agent 想要执行: {tool_name}({tool_input})")
            user_response = input("   允许? (y/n): ").strip().lower()
            if user_response not in ("y", "yes"):
                self.permission_engine.record_denial()
                return {
                    "type": "tool_result",
                    "tool_use_id": tool_use_id,
                    "content": "Permission denied by user.",
                    "is_error": True,
                }
        
        # 权限通过
        self.permission_engine.record_success()
        
        # Stage 5: 执行
        try:
            result = tool.execute(tool_input)
        except Exception as e:
            return {
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": f"Execution error: {e}",
                "is_error": True,
            }
        
        # Stage 7: 结果处理（截断过大结果）
        if len(result) > 50000:
            result = result[:20000] + "\n...[truncated]...\n" + result[-5000:]
        
        return {
            "type": "tool_result",
            "tool_use_id": tool_use_id,
            "content": result,
        }
    
    # ─────────────────────────────────────────────────────
    # 工具执行函数
    # ─────────────────────────────────────────────────────
    
    def _exec_read_file(self, input: dict) -> str:
        path = input["path"]
        return open(path).read()
    
    def _exec_write_file(self, input: dict) -> str:
        path = input["path"]
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
        open(path, "w").write(input["content"])
        return f"Successfully wrote to {path}"
    
    def _exec_bash(self, input: dict) -> str:
        import subprocess
        r = subprocess.run(
            input["command"], shell=True, 
            capture_output=True, text=True, timeout=30
        )
        return (r.stdout + r.stderr)[:10000]
    
    def _exec_grep(self, input: dict) -> str:
        import subprocess
        cmd = f"grep -rn '{input['pattern']}' {input.get('path', '.')}"
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
        return r.stdout[:10000]
    
    # ─────────────────────────────────────────────────────
    # 辅助方法
    # ─────────────────────────────────────────────────────
    
    def _get_tool_definitions(self) -> list[dict]:
        """返回 API 需要的工具列表"""
        return [
            {"name": t.name, "description": t.description, "input_schema": t.input_schema}
            for t in self.tools.values()
        ]
    
    def _build_system_prompt(self) -> str:
        return "你是一个编程助手。可以读写文件、执行命令、搜索代码。遵循安全原则。"


# ═══════════════════════════════════════════════════════════════
# 主入口
# ═══════════════════════════════════════════════════════════════

def main():
    # 可通过命令行参数选择权限模式
    import sys
    mode = PermissionMode.DEFAULT
    if "--bypass" in sys.argv:
        mode = PermissionMode.BYPASS
    elif "--accept-edits" in sys.argv:
        mode = PermissionMode.ACCEPT_EDITS
    
    agent = Agent(permission_mode=mode)
    print(f"Agent 已启动 (权限模式: {mode.value})")
    print("输入 exit 退出。\n")
    
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

## 八、完整时序图（权限决策重点标注）

```
时间  ║ 层次       ║ 用户             ║ Agent                      ║ 权限系统
═════╬═══════════╬═════════════════╬═══════════════════════════╬══════════════════
     ║           ║                 ║                           ║
T0   ║ Session   ║ 启动 Agent      ║ 注册工具（4个）            ║ 加载规则
     ║ 初始化    ║                 ║ 组装工具池                 ║ 设置模式=default
     ║           ║                 ║                           ║
─────╬───────────╬─────────────────╬───────────────────────────╬──────────────────
     ║           ║                 ║                           ║
T1   ║ Turn 1    ║ "读一下main.py" ║ messages.push(user)       ║
     ║           ║                 ║                           ║
     ║ Loop 1    ║                 ║ → API → tool_use:read     ║
     ║           ║                 ║ ★ 权限检查 ──────────────►║ read_file
     ║           ║                 ║                           ║ is_read_only=True
     ║           ║                 ║ ◄─── ALLOW ──────────────║ → 自动通过
     ║           ║                 ║ 执行: 读文件              ║
     ║           ║                 ║ push results              ║
     ║           ║                 ║                           ║
     ║ Loop 2    ║                 ║ → API → 文本回复          ║
     ║           ║ ◄────────────  ║ "文件内容是..."           ║
     ║           ║                 ║                           ║
─────╬───────────╬─────────────────╬───────────────────────────╬──────────────────
     ║           ║                 ║                           ║
T2   ║ Turn 2    ║ "跑 git status" ║ messages.push(user)       ║
     ║           ║                 ║                           ║
     ║ Loop 1    ║                 ║ → API → tool_use:bash     ║
     ║           ║                 ║   command="git status"    ║
     ║           ║                 ║ ★ 权限检查 ──────────────►║ bash
     ║           ║                 ║                           ║ 1. deny rule? → No
     ║           ║                 ║                           ║ 2. safety? → No
     ║           ║                 ║                           ║ 3. allow rule? 
     ║           ║                 ║                           ║    "prefix:git " ✓
     ║           ║                 ║ ◄─── ALLOW ──────────────║ → 规则允许
     ║           ║                 ║ 执行: git status          ║
     ║           ║                 ║                           ║
─────╬───────────╬─────────────────╬───────────────────────────╬──────────────────
     ║           ║                 ║                           ║
T3   ║ Turn 3    ║ "部署到生产"    ║ messages.push(user)       ║
     ║           ║                 ║                           ║
     ║ Loop 1    ║                 ║ → API → tool_use:bash     ║
     ║           ║                 ║   command="rm -rf /tmp && ║
     ║           ║                 ║    deploy.sh"             ║
     ║           ║                 ║ ★ 权限检查 ──────────────►║ bash
     ║           ║                 ║                           ║ 拆分子命令:
     ║           ║                 ║                           ║  "rm -rf /tmp" 
     ║           ║                 ║                           ║  → 危险模式！
     ║           ║                 ║ ◄─── DENY ───────────────║ 
     ║           ║                 ║ 返回错误给 LLM            ║ consecutive=1
     ║           ║                 ║                           ║
     ║ Loop 2    ║                 ║ → API → tool_use:bash     ║
     ║           ║                 ║   command="deploy.sh"     ║
     ║           ║                 ║ ★ 权限检查 ──────────────►║ bash
     ║           ║                 ║                           ║ 1. deny? No
     ║           ║                 ║                           ║ 2. safety? No
     ║           ║                 ║                           ║ 3. allow? No match
     ║           ║                 ║                           ║ 4. mode=default
     ║           ║                 ║                           ║ 5. not read_only
     ║           ║                 ║ ◄─── ASK ────────────────║ → 需要用户确认
     ║           ║                 ║                           ║
     ║           ║ "允许? (y/n)"   ║ 等待用户输入...           ║
     ║           ║ → y             ║                           ║ consecutive=0 ✓
     ║           ║                 ║ 执行: deploy.sh           ║
     ║           ║                 ║                           ║
═════╬═══════════╬═════════════════╬═══════════════════════════╬══════════════════
```

---

## 九、总结

**工具系统的核心设计**：工具是有安全属性的结构体，不只是"名字+函数"。默认值是 fail-closed 的——忘了设置就是最保守的行为。

**权限系统的核心设计**：分步管线，优先级清晰。Deny 规则和 Safety Check 是不可覆盖的"铁律"，模式只在通过这些检查后才起作用。

**每一层的职责**：

| 层次 | 做什么 | 什么时候 |
|------|--------|---------|
| Session | 注册工具、加载规则、确定模式 | 启动时做一次 |
| Turn | 动态过滤工具池、响应规则变更 | 每次用户输入时 |
| Agentic Loop | 输入验证 → 权限决策 → 执行 → 结果处理 | 每次工具调用时 |

**权限决策的铁律**：

1. Deny 规则 > 一切（bypass 模式也不能绕过）
2. Safety Check > 一切（.git/ .ssh/ 永远要确认）
3. 模式只是"在安全约束内的便利开关"
4. 未知工具 = 拒绝，未设置属性 = 最保守
