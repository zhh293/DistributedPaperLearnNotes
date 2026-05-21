# Claude Code 多 Agent 架构深度分析

## 一、架构总览

Claude Code 实现了一个**层次化、可扩展的多智能体系统**，其核心设计理念是：通过不同抽象层级的 Agent 组合（从轻量级的 fork 子代理到完整的远程会话），适配不同复杂度的任务。

整个系统采用统一的 Task 抽象追踪所有异步工作单元的生命周期，并结合 AsyncLocalStorage 实现同一进程内多 Agent 的上下文隔离。

```
┌─────────────────────────────────────────────────────────────────────┐
│                        User (CLI / SDK)                               │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────┐
│                       Main REPL Session                               │
│  ┌─────────────────┐  ┌────────────────┐  ┌─────────────────────┐  │
│  │  Coordinator    │  │   AgentTool    │  │  SendMessageTool    │  │
│  │  Mode           │  │ (spawn/fork)   │  │  (route messages)   │  │
│  └────────┬────────┘  └───────┬────────┘  └──────────┬──────────┘  │
└───────────┼────────────────────┼─────────────────────┼──────────────┘
            │                    │                     │
      ┌─────▼────────┐    ┌─────▼──────────────────┐  │
      │   Workers    │    │    Task Registry       │  │
      │  (async)     │    │  ┌──────────────────┐  │  │
      └──────────────┘    │  │ local_bash       │  │  │
                          │  │ local_agent      │  │  │
                          │  │ remote_agent     │  │  │
                          │  │ in_process_team  │  │  │
                          │  │ local_workflow   │  │  │
                          │  │ monitor_mcp      │  │  │
                          │  │ dream            │  │  │
                          │  └──────────────────┘  │  │
                          └────────────────────────┘  │
                                                      │
          ┌───────────────────────────────────────────▼────┐
          │             Communication Layer                  │
          │  ┌──────────────────────────────────────────┐  │
          │  │  File-based Mailbox (JSON + lockfile)     │  │
          │  │  ~/.claude/teams/{team}/inboxes/*.json    │  │
          │  ├──────────────────────────────────────────┤  │
          │  │  UDS (Unix Domain Socket) cross-session   │  │
          │  ├──────────────────────────────────────────┤  │
          │  │  Bridge (Remote Control via WebSocket)    │  │
          │  └──────────────────────────────────────────┘  │
          └────────────────────────────────────────────────┘
```

---

## 二、Task 类型系统

### 2.1 七种任务类型

系统使用类型化的 TaskType 联合类型定义了七种工作单元：

| TaskType | ID 前缀 | 用途 | 典型场景 |
|----------|---------|------|---------|
| `local_bash` | `b` | 后台 Shell 命令执行 | 长时间运行的编译、测试 |
| `local_agent` | `a` | 本地子代理（sync/async） | 研究任务、代码生成 |
| `remote_agent` | `r` | 远程会话代理（CCR） | 需要完整隔离的重型任务 |
| `in_process_teammate` | `t` | 进程内队友（swarm） | 团队协作模式 |
| `local_workflow` | `w` | 本地工作流编排 | 多步骤流程 |
| `monitor_mcp` | `m` | MCP 服务器监控 | 外部服务健康检查 |
| `dream` | `d` | 后台记忆整合 | AutoDream 记忆合并 |

### 2.2 Task 生命周期

```
pending ──→ running ──→ completed
                   ├──→ failed
                   └──→ killed
```

`isTerminalTaskStatus()` 判断终态，用于防止向已终止的 teammate 注入消息。

### 2.3 Task ID 生成

```typescript
function generateTaskId(type: TaskType): string {
  const prefix = TYPE_PREFIX_MAP[type]  // 如 't' for in_process_teammate
  const random = randomBytes(8)         // 36字符字母表编码
  return `${prefix}${random}`           // 如 "t0a3bx9k2"
}
```

约 2.8 万亿种组合，前缀标识类型。

### 2.4 TaskStateBase 公共字段

```typescript
type TaskStateBase = {
  id: string
  type: TaskType
  status: TaskStatus
  description: string
  toolUseId?: string        // 关联触发的 tool_use block
  startTime: number
  endTime?: number
  totalPausedMs?: number    // 因权限等待累积的暂停时间
  outputFile: string        // 磁盘输出文件路径
  outputOffset: number      // 当前读取偏移
  notified: boolean         // 是否已通知父级
}
```

---

## 三、Coordinator 模式（编排者模式）

### 3.1 启用方式

通过环境变量 `CLAUDE_CODE_COORDINATOR_MODE=1` 激活，feature gate `COORDINATOR_MODE` 控制编译时门控。与 Fork Subagent 实验互斥。

### 3.2 核心理念

Coordinator 是一个**只与用户对话的编排者**，它不直接使用文件操作工具，而是通过产生 worker 来完成实际工作：

```
┌──────────────────────────────────────────────────────────────────┐
│  Coordinator 可用工具（仅 4 个）                                   │
│                                                                    │
│  AgentTool       → 产生 worker                                    │
│  TaskStopTool    → 停止 worker                                    │
│  SendMessageTool → 继续已有 worker                                │
│  SyntheticOutput → 合成输出                                       │
└──────────────────────────────────────────────────────────────────┘
```

### 3.3 四阶段工作流

Coordinator 的系统提示词定义了结构化的工作流程：

```
┌────────────────┐     ┌────────────────┐
│  Phase 1       │     │  Phase 2       │
│  Research      │────→│  Synthesis     │
│  (并行研究)    │     │  (综合理解)    │
└────────────────┘     └───────┬────────┘
                               │
┌────────────────┐     ┌───────▼────────┐
│  Phase 4       │     │  Phase 3       │
│  Verification  │←────│  Implementation│
│  (独立验证)    │     │  (按规格实施)  │
└────────────────┘     └────────────────┘
```

**Phase 1: Research（研究）**
- 多个 Worker 并行调查代码库
- 只读任务，可以自由并发

**Phase 2: Synthesis（综合）**
- Coordinator 自己理解研究结果
- 编写具体的实现规格说明

**Phase 3: Implementation（实施）**
- Worker 按规格编码
- 按文件区域串行（避免冲突）

**Phase 4: Verification（验证）**
- 独立 Worker 测试验证
- 可与不同区域的实施并行

### 3.4 Worker 结果传递

Worker 完成后，结果通过 XML 格式注入到 Coordinator 的 user 消息中：

```xml
<task-notification>
  <task-id>a7x3m9p1</task-id>
  <status>completed</status>
  <summary>Implemented user authentication module</summary>
  <result>
    Created src/auth/login.ts with JWT validation...
    Modified src/routes/index.ts to add auth middleware...
  </result>
  <usage>input: 45000, output: 12000</usage>
</task-notification>
```

---

## 四、AgentTool — 子代理生成的核心

### 4.1 三种生成路径

`AgentTool.call()` 根据参数决定走哪条路径：

**路径 A — Teammate 产生**（team_name + name）：
```
→ spawnTeammate({ teamName, name, prompt, model, ... })
→ 创建持久化队友，加入团队
```

**路径 B — Fork 子代理**（无 subagent_type，Fork 实验开启）：
```
→ selectedAgent = FORK_AGENT
→ 继承父级全部上下文（系统提示 + 对话历史 + 工具集）
```

**路径 C — 常规子代理**（指定 subagent_type）：
```
→ selectedAgent = agents.find(a => a.agentType === effectiveType)
→ 使用预定义的 agent definition
```

### 4.2 同步 vs 异步执行

```typescript
const shouldRunAsync = (
  run_in_background === true ||
  selectedAgent.background === true ||
  isCoordinator ||               // coordinator 强制异步
  forceAsync ||                  // fork 实验强制异步
  assistantForceAsync            // assistant 模式强制异步
) && !isBackgroundTasksDisabled
```

- **同步执行**：阻塞等待 `runAgent()` 完成，收集所有消息后返回给调用方
- **异步执行**：`registerAsyncAgent()` → `void runAsyncAgentLifecycle()`，fire-and-forget

### 4.3 隔离模式

```typescript
type IsolationMode = 'none' | 'worktree' | 'remote'
```

| 模式 | 隔离级别 | 实现方式 |
|------|---------|---------|
| `none` | 无隔离 | 共享文件系统 |
| `worktree` | 文件系统隔离 | 创建 git worktree |
| `remote` | 完全隔离 | 委托到 CCR 远程容器 |

Worktree 隔离完成后检测是否有变更，无变更则自动清理 worktree。

---

## 五、Fork Subagent 机制

### 5.1 核心设计

Fork 子代理是最轻量级的多代理形式——它继承父级的**全部上下文**：

```
┌─────────────────────────────────────────────┐
│  Parent Agent                                │
│  ├── System Prompt (渲染后的字节级副本)       │
│  ├── Messages (完整对话历史)                  │
│  └── Tools (完全相同的工具集)                 │
│                                              │
│           fork                               │
│            ↓                                 │
│  ┌──────────────────────────────────────┐   │
│  │  Fork Child                           │   │
│  │  ├── 复用父级 system prompt            │   │
│  │  │   (字节级一致 → 最大化缓存命中)     │   │
│  │  ├── forkContextMessages              │   │
│  │  │   (父级 messages 的引用)            │   │
│  │  ├── useExactTools: true              │   │
│  │  │   (使用父级完全相同的工具集)         │   │
│  │  └── per-child 指令                    │   │
│  │      (具体任务描述)                    │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

### 5.2 消息构造

`buildForkedMessages()` 的精巧设计：
1. 保留父级 assistant message 的所有 `tool_use` block
2. 为每个 tool_use 生成占位 `tool_result`（满足 API 格式要求）
3. 最后附加 per-child 指令（具体任务描述）

### 5.3 递归保护

通过 `FORK_BOILERPLATE_TAG` 检测防止 fork child 再次 fork，避免无限递归：

```typescript
if (messages.some(m => containsTag(m, FORK_BOILERPLATE_TAG))) {
  throw new Error('Fork child cannot fork again')
}
```

### 5.4 缓存优化

Fork 的最大优势是 **prompt cache 复用**。因为子代理的 system prompt 与父级字节级相同，API 服务端可以直接命中缓存——这意味着 fork 子代理的首次请求成本极低（只需处理增量的 per-child 指令）。

---

## 六、Teammate 系统 — Swarm 架构

### 6.1 三种执行后端

| Backend | 环境 | 特点 |
|---------|------|------|
| `tmux` | 终端 | 独立进程，pane 可视化，tmux socket 通信 |
| `iterm2` | iTerm2 | 原生 split pane，it2 CLI 控制 |
| `in-process` | 任意 | 同进程 AsyncLocalStorage 隔离，共享 API client |

系统通过 `BackendDetectionResult` 动态检测可用后端。

### 6.2 In-Process Teammate 生命周期

```
spawnInProcessTeammate()
    │
    ├── generateTaskId('in_process_teammate')
    ├── createAbortController()  (独立，不绑定 leader)
    ├── createTeammateContext()  (agentId, teamName, color, parentSessionId)
    ├── registerTask(InProcessTeammateTaskState)
    └── startInProcessTeammate(config)
            │
            └── void runInProcessTeammate(config)  [fire-and-forget]
                    │
                    └── while (!aborted && !shouldExit) {
                            1. 创建 per-turn AbortController
                            2. 检查是否需要 compaction
                            3. runWithTeammateContext → runAgent()
                            4. 收集 messages，更新 task state
                            5. 标记 idle，发送 idle notification
                            6. waitForNextPromptOrShutdown()
                            7. new_message → 继续
                               shutdown_request → 传给模型
                               aborted → 退出
                        }
```

### 6.3 消息优先级

In-process runner 轮询消息时的优先级（从高到低）：

1. **Shutdown 请求** — 防止被 peer 消息淹没
2. **Team Lead 消息** — Leader 代表用户意图
3. **Peer 消息** — FIFO 顺序
4. **Task List 未认领任务** — 最低优先级

### 6.4 扁平团队限制

团队结构严格扁平——只有 leader 可以创建队友，队友不能创建其他队友：

```typescript
if (isTeammate() && teamName && name) {
  throw new Error(
    'Teammates cannot spawn other teammates — the team roster is flat.'
  )
}
```

### 6.5 InProcessTeammateTaskState

```typescript
type InProcessTeammateTaskState = TaskStateBase & {
  type: 'in_process_teammate'
  identity: TeammateIdentity
  prompt: string
  model?: string
  abortController?: AbortController
  awaitingPlanApproval: boolean
  permissionMode: PermissionMode
  isIdle: boolean
  shutdownRequested: boolean
  pendingUserMessages: string[]
  messages: Message[]
  onIdleCallbacks: (() => void)[]
  currentWorkAbortController?: AbortController
  inProgressToolUseIDs?: Set<string>
}
```

---

## 七、上下文隔离机制

### 7.1 为什么需要隔离

当 agent 被后台化（Ctrl+B），多个 agent 可在同一进程中并发运行。AppState 是共享单例——如果不隔离，Agent A 的事件会错误归因到 Agent B。

### 7.2 双层 AsyncLocalStorage

系统使用两个独立的 AsyncLocalStorage 实例：

**TeammateContext**（`src/utils/teammateContext.ts`）：
- 运行时身份识别：agentId, agentName, teamName, color
- 生命周期管理：abortController
- 快速判断：`isInProcessTeammate()`

**AgentContext**（`src/utils/agentContext.ts`）：
- 分析归因：追踪 API 调用属于哪个 agent
- 两种子类型：
  - `SubagentContext` — Agent tool 产生的代理
  - `TeammateAgentContext` — Swarm 队友
- 支持 `invokingRequestId` 追踪调用链关系

### 7.3 进程级 vs 进程内识别

| 机制 | 适用场景 |
|------|---------|
| 环境变量 `CLAUDE_CODE_AGENT_ID` | tmux/iTerm2 进程级队友 |
| `dynamicTeamContext` | 运行时加入的进程级队友 |
| AsyncLocalStorage (TeammateContext) | 进程内队友 |

检查优先级：AsyncLocalStorage → dynamicTeamContext → env vars。

---

## 八、通信机制

### 8.1 File-Based Mailbox

路径：`~/.claude/teams/{team_name}/inboxes/{agent_name}.json`

**数据结构**：
```typescript
type TeammateMessage = {
  from: string       // 发送者名
  text: string       // 消息内容
  timestamp: string  // ISO 时间戳
  read: boolean      // 是否已读
  color?: string     // 发送者颜色
  summary?: string   // UI 预览摘要
}
```

**并发安全**：使用 `proper-lockfile` 库实现文件锁：
- 获取锁 → 读取最新状态 → 追加消息 → 写回 → 释放锁
- 支持重试（10 次，5-100ms 退避）

### 8.2 SendMessageTool 路由逻辑

SendMessageTool 是一个多路由器，按目标类型分派：

```
┌─────────────────────────────────────────────────────────────────┐
│  SendMessageTool.call(target, message)                           │
│                                                                   │
│  if target === "bridge:session-id"                               │
│    → Remote Control 跨机器（经 Anthropic WebSocket 中继）         │
│                                                                   │
│  if target === "uds:socket-path"                                 │
│    → Unix Domain Socket 跨会话                                   │
│                                                                   │
│  if target in agentNameRegistry                                  │
│    → 进程内 local_agent：                                        │
│      running → queuePendingMessage()                             │
│      stopped → resumeAgentBackground()（自动恢复！）             │
│                                                                   │
│  if target === "*"                                               │
│    → 广播到所有团队成员                                           │
│                                                                   │
│  else                                                            │
│    → 写入 file-based mailbox                                     │
└─────────────────────────────────────────────────────────────────┘
```

### 8.3 结构化消息协议

```typescript
// 关闭请求
{ type: 'shutdown_request', reason?: string }

// 关闭响应
{ type: 'shutdown_response', request_id: string, approve: boolean, reason?: string }

// Plan 审批响应
{ type: 'plan_approval_response', request_id: string, approve: boolean, feedback?: string }
```

### 8.4 Idle 通知

Teammate 完成当前轮次后发送 idle 通知：

```typescript
await sendIdleNotification(agentName, color, teamName, {
  idleReason: 'available' | 'interrupted' | 'failed',
  summary: getLastPeerDmSummary(allMessages),
})
```

---

## 九、Team 结构管理

### 9.1 TeamFile 结构

```typescript
type TeamFile = {
  name: string
  description?: string
  createdAt: number
  leadAgentId: string
  leadSessionId?: string
  members: Array<{
    agentId: string         // "name@team" 格式
    name: string
    agentType?: string
    model?: string
    prompt?: string
    color?: string
    planModeRequired?: boolean
    joinedAt: number
    tmuxPaneId: string
    cwd: string
    worktreePath?: string
    sessionId?: string
    subscriptions: string[]
    backendType?: BackendType
    isActive?: boolean
    mode?: PermissionMode
  }>
}
```

存储路径：`~/.claude/teams/{sanitized-team-name}/config.json`

### 9.2 权限桥接

`leaderPermissionBridge.ts` 实现了 leader 与 in-process teammate 之间的权限代理：

```
Teammate 需要权限 → permissionBridge → Leader 的 ToolUseConfirm UI → 用户决策 → 返回
```

REPL 启动时注册 `setToolUseConfirmQueue` 和 `setToolPermissionContext` 函数，teammate 通过这些函数将权限请求路由到 leader 的 UI。

### 9.3 关闭审批流程

```
Leader 发送 shutdown_request
    ↓
Teammate 接收消息
    ↓
Teammate 模型决定 approve/reject
    ↓
├── approve → abort controller + 通知 leader → 退出循环
└── reject  → 继续工作，发送拒绝原因给 leader
```

这是一个**协商式关闭**——leader 请求关闭，但 teammate 可以拒绝（如果它正在做关键工作）。

---

## 十、DreamTask — 后台记忆整合

### 10.1 设计

DreamTask 是一个特殊的后台任务，负责记忆的自动整合：

- **Phase**: `starting` → `updating`（检测到 Edit/Write 工具调用时）
- **追踪**：`filesTouched`（工具调用涉及的文件）、`turns`（最近 30 轮对话）
- **整合锁**：mtime-based lock 防止多会话并发整合
- **Kill 处理**：回滚 consolidation lock 的 mtime，让下次会话可重试

### 10.2 触发条件

- 距上次整合 ≥ 24 小时
- 至少 5 个新会话

---

## 十一、远程代理 (Remote Agent)

### 11.1 工作流程

```
1. checkRemoteAgentEligibility()    前置条件检查
2. teleportToRemote()               打包当前上下文到远程会话
3. registerRemoteAgentTask()        本地注册跟踪
4. WebSocket 实时中继               消息和权限提示双向传递
```

### 11.2 适用场景

- 需要完全文件系统隔离的任务
- 长时间运行的重型计算
- 需要特殊环境（如 GPU）的任务

---

## 十二、关键设计模式

### 12.1 Fire-and-Forget 异步模式

```typescript
void runWithAgentContext(asyncAgentContext, () =>
  wrapWithCwd(() => runAsyncAgentLifecycle({ ... }))
)
```

异步代理通过 `void` 启动，不阻塞调用方。错误通过 `.catch()` 处理并更新 task state。

### 12.2 Registry 模式

- `AppState.tasks` — 全局任务注册表
- `AppState.agentNameRegistry` — name → agentId 映射（SendMessage 路由）
- `AppState.teamContext.teammates` — 活跃队友 UI 状态

### 12.3 Two-Level Abort 模式

```
lifecycleAbortController ─── 终结整个 teammate 生命周期
         │
         └── currentWorkAbortController ─── Escape 只停当前轮次
```

这允许用户通过 Escape 中断当前操作，但 teammate 仍然活着等待下一个任务。

### 12.4 统一 Agent 执行内核

所有类型的 agent 最终都调用同一个 `runAgent()` 函数。差异通过参数注入：

```typescript
runAgent({
  agentDefinition,          // 系统提示词和工具配置
  forkContextMessages,      // 继承的上下文（fork 模式）
  isAsync,                  // 是否异步
  canShowPermissionPrompts, // 是否可弹权限对话框
  override: {
    systemPrompt,           // 覆盖系统提示词
    abortController,        // 独立中断控制
  }
})
```

### 12.5 Auto-Compaction

长时间运行的 Teammate 自动检测 token 使用量：

```
token 数超阈值 → compactConversation()
              → 重置 content replacement state
              → 重置 microcompact state
              → 继续运行
```

---

## 十三、通信模式对比

| 模式 | 适用场景 | 延迟 | 可靠性 | 隔离级别 |
|------|---------|------|--------|---------|
| AsyncLocalStorage + queue | 进程内 teammate | 微秒 | 最高 | 逻辑隔离 |
| File-based mailbox | 跨进程 teammate (tmux/iTerm2) | 毫秒 | 高（有 lockfile） | 进程隔离 |
| UDS (Unix Domain Socket) | 跨会话通信 | 毫秒 | 中 | 进程隔离 |
| WebSocket bridge | 跨机器远程控制 | 10-100ms | 中（网络依赖） | 完全隔离 |

---

## 十四、设计理念总结

1. **统一任务抽象**：7 种 TaskType 覆盖从 shell 命令到远程会话的所有异步工作单元。无论多简单或复杂的任务，都用同一套 Task 生命周期管理。

2. **渐进式隔离**：从零隔离（共享进程）到 worktree（文件系统隔离）到 remote（完全隔离），开发者可以按需选择隔离级别。

3. **缓存为王（Fork 模式）**：Fork 子代理继承父级的字节级相同的 system prompt，最大化 API prompt cache 命中。这使得产生子代理的成本极低。

4. **协商式控制**：Shutdown 不是强制的——leader 请求，teammate 可以协商。这比粗暴的 kill 更安全（不会中断正在写入的文件）。

5. **扁平团队**：只有 leader 可以创建 teammate，teammate 不能创建 teammate。这简化了责任链和权限管理。

6. **混合通信**：进程内用 queue + AsyncLocalStorage（最快），跨进程用 file-based mailbox（最可靠），跨机器用 WebSocket（最灵活）。

7. **优雅的生命周期**：Two-Level Abort 让用户可以中断操作但不杀死 agent，Auto-Compaction 让长时间运行的 agent 不会因为上下文溢出而死亡。

8. **可观测性**：AgentContext 的 `invokingRequestId` 链允许追踪完整的调用链——从用户请求到 coordinator 到 worker 到 sub-agent，每一步都有关联 ID。
