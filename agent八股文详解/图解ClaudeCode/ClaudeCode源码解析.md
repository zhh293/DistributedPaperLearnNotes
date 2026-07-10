# Claude Code 源码解析

> 来源：小林面试笔记 - 图解 Claude Code 系列
> 网站：https://www.xiaolinnote.com/claudecode/

---

## 文章五：Claude Code 源码详解：51 万行泄漏代码里的架构设计

原创 公众号@小林coding | 大约 47 分钟 约 14159 字

---

大家好，我是小林。

Claude Code 源码泄漏这个瓜，大家都吃了吧？堂堂业界最强编程 Agent，就因为 npm 打包时配置手抖，不小心把 `.map` 文件一起传了上去，结果 51 万行核心代码直接全网裸奔。

![](https://cdn.xiaolincoding.com//picgo/1775113330476-e2fc09aa-1cbd-4a6c-9421-4ca6f4c4f55e.png)

先说清楚，这些泄漏的是 Claude Code 客户端的源码，并不是 Claude Opus 大模型的源码。

这 51 万行的 Claude Code 源码，简直就是 Harness Engineering 的最佳教科书。啃完你会发现，人家 80% 的代码根本不是在搞什么黑科技让 AI 更聪明，而是在死磕「可靠性」。

### 一、Claude Code 是什么

Claude Code 是 Anthropic 官方推出的编程 Agent 工具。你可以把它理解成一个能直接在你的终端里干活的 AI 程序员，它不是一个聊天窗口，而是真正能读你的代码、改你的文件、跑你的命令、帮你管理 Git 的那种。

![](https://cdn.xiaolincoding.com//picgo/1775113671887-b896f657-9188-4ebf-939a-313c1e9426ee.png)

**Agent 的核心是一个「感知-决策-行动」的自主循环**。你给它一个目标（比如「帮我修复这个 bug」），它会自己决定先读哪个文件、再跑什么命令、然后改哪行代码，整个过程可能循环几十轮，直到任务完成。

![](https://cdn.xiaolincoding.com//picgo/1775129756829-d6273113-33b6-4a9b-b5f2-c45bfe1f33aa.png)

### 二、架构设计

Claude Code 采用了一个**四层分层架构**：

![](https://cdn.xiaolincoding.com//picgo/1775144142084-ec84ad05-2d1c-4863-bf22-1e4517b06eaf.png)

- **引擎层**：Agent 的「大脑」，负责思考和调度。关键设计原则是**不包含任何业务逻辑**。只做三件事：协调（拼消息发给大模型）、分发（找到对应工具并执行）、决策（继续循环还是结束对话）。
- **工具层**：Agent 的全部「能力」，40 多个工具。每个工具遵循统一规范，强制定义三个安全属性：只读还是会改东西？是否具有破坏性？能不能和其他工具同时执行？
- **服务层**：所有层共享的「基础设施」。包括调大模型 API、上下文压缩、MCP 协议。
- **安全与治理层**：像一张安全网罩在所有层上面。权限系统、Hook 系统、Bash 安全模块。

### 三、Agent 工作模式

#### 什么是 ReAct

ReAct（Reasoning + Acting）是 2022 年提出的 Agent 范式，核心思路是把每一步拆成三个阶段：Thought → Action → Observation。

![](https://cdn.xiaolincoding.com//picgo/1775113743895-ae0db081-8da5-414d-8fe3-79bdb0b9d765.png)

但 ReAct 有几个问题：Token 浪费、应用层代码太复杂、是为「弱模型」设计的。

#### Tool-Use Loop

Claude Code 没有采用 ReAct，而是用了一个更简洁的模式——**Tool-Use Loop**：

![](https://cdn.xiaolincoding.com//picgo/1775113773385-76a9359b-1c69-42e1-93c2-c1b8a352e540.png)

核心就是一个 `while(true)` 循环，**没有 Thought 步骤**。模型在内部完成推理（通过 Extended Thinking），然后直接返回两种结果之一：

- `tool_use`：「我要用某个工具」，应用层执行工具，把结果拼入消息列表，继续循环
- `end_turn`：「我说完了」，跳出循环，把最终结果返回给用户

```tsx
async function* queryLoop(params: QueryParams): AsyncGenerator<StreamEvent | Message, Terminal> {
  let state: State = { messages, toolUseContext, turnCount: 1, ... }

  while (true) {
    // 步骤 1：压缩上下文（五步从轻到重）
    // 步骤 2：调用大模型 API，流式接收
    for await (const event of streamAPI(params)) {
      yield event // 流式输出每个 token
    }
    // 步骤 3：分析模型返回
    if (response.stopReason === 'end_turn') break // 完成了，跳出循环

    // 步骤 4：执行工具调用（并发/串行编排）
    const toolResults = await executeToolCalls(toolUseMessages)

    // 步骤 5：更新 state，继续循环
    state = { ...state, messages: updatedMessages, turnCount: turnCount + 1 }
    continue
  }
}
```

#### 为什么比 ReAct 更好

| 维度 | ReAct | Tool-Use Loop |
|------|-------|---------------|
| 推理方式 | 显式 Thought 文本 | 模型内部 Extended Thinking |
| 工具调用 | 解析文本提取 Action | API 原生 tool_use |
| 终止判断 | 检测「Final Answer」 | API 原生 end_turn |
| Token 开销 | 每轮要输出 Thought | 无额外开销 |
| 编排复杂度 | 高 | 低（只需要 if/else） |
| 适合场景 | 弱模型 + 简单工具 | 强模型 + 复杂工具集 |

#### Plan Mode

Plan Mode 是一个两阶段工作流：先规划、再执行。通过 `EnterPlanMode` 和 `ExitPlanMode` 两个工具实现：

1. 模型自主进入或用户手动触发
2. 只读探索 + 设计方案（权限降为只读）
3. 用户审批后实施（权限恢复）

### 四、System Prompt 的构造

Claude Code 的 System Prompt 是**动态组装**的，由十几个 Section 拼接而成。

#### 角色定义与安全红线

```plain
你是一个交互式代理（interactive agent），帮助用户完成软件工程任务。
重要：你绝对不能为用户生成或猜测 URL，除非你确信这些 URL 是为了帮助用户完成编程任务。
```

#### 行为准则

- 不要对你没有阅读过的代码提出修改建议
- 不要在用户要求之外添加功能、重构代码或进行"改进"
- 如果某个方案失败了，先诊断原因再决定是否换方案

#### 操作安全

用**可逆性**和**影响范围**两个维度来判断风险：
- 低风险（可逆、只影响本地）：直接放行
- 高风险（不可逆、影响他人）：必须确认

#### 工具使用指南

```plain
当有专用工具可用时，不要用 Bash 来执行命令：
- 读取文件用 Read 工具，而不是 cat
- 编辑文件用 Edit 工具，而不是 sed
- 搜索文件用 Glob 工具，而不是 find
- 搜索内容用 Grep 工具，而不是 grep
```

原因是**可审查性**和**安全性**。

#### Git 安全协议

- 绝不修改 git config
- 绝不执行破坏性 git 命令（push --force、reset --hard），除非用户明确要求
- 绝不跳过 hooks（--no-verify）
- 始终创建新的 commit，而不是用 --amend 修改

#### 输出风格约束

```plain
直奔重点。先尝试最简单的方案。要极度简洁。
工具调用之间的文字不超过 25 个词。最终回复不超过 100 个词。
```

#### 环境信息注入与三级缓存

System Prompt 被分成三段：
- **静态段**（角色定义、行为准则）：永远不变，命中 prompt cache
- **半静态段**（工具列表、CLAUDE.md）：偶尔变化
- **动态段**（环境信息、时间戳）：每次都变

### 五、记忆系统

#### 记什么：四类型分类

- **UserPreference**：用户偏好（如「我喜欢 4 空格缩进」）
- **ProjectConvention**：项目约定（如「API 用 RESTful 风格」）
- **ToolUsagePattern**：工具使用模式（如「测试用 vitest 不用 jest」）
- **LessonLearned**：经验教训（如「这个项目的 CI 很慢，先本地跑测试」）

#### 不记什么：排除清单

- 代码里 grep 一下就能找到的事实
- 一次性的对话上下文
- 用户没有明确表达的推测

#### 怎么存：索引 + 独立文件

每条记忆是一个独立的 markdown 文件，存在 `.claude/memory/` 目录下。索引文件（MEMORY.md）常驻 system prompt，记录每条记忆的标题和路径。

#### 怎么召回：Sonnet 当秘书

不用向量数据库，而是用 Sonnet（廉价模型）做选择题：给它索引列表 + 当前问题，让它选出最相关的 top-5 条记忆，再把这 5 条的完整内容加载进上下文。

#### 性能优化：并行预取

在用户输入的同时，后台就开始用 Sonnet 预测可能需要的记忆，等主循环真正需要时直接用缓存结果。

### 六、上下文窗口管理

#### 压缩五步走

从轻到重，逐步升级：

**第 1 步：大结果存磁盘**。工具返回超过阈值（如 30KB），把完整结果写到临时文件，上下文里只留一句「结果已保存到 /tmp/xxx，需要时再读」。

**第 2 步：砍掉远古消息**。对话超过一定轮数，把最早的几轮直接删掉（保留 system prompt 和最近的消息）。

**第 3 步：裁剪老的工具输出**。保留工具调用的「骨架」（调了什么工具、传了什么参数），但把返回结果截断或替换为摘要。

**第 4 步：读时投影**。不修改原始消息，而是在发送给 API 前做一次「投影」：把大段代码块替换为行号范围标记，模型需要看细节时再用 Read 工具去读。

**第 5 步：全量摘要**。当前面四步都不够时，用模型对整个对话做一次摘要，生成一段精炼的「到目前为止发生了什么」，替换掉所有历史消息。

---

## 文章六：Claude Code 主循环 Query 详解：一轮对话是怎么跑起来的？

原创 公众号@小林coding | 大约 40 分钟 约 12000 字

---

大家好，我是小林。

上一篇我们从宏观视角看了 Claude Code 的四层架构。这一篇我们把镜头拉近，聚焦到最核心的那个函数：**query()**——也就是 Claude Code 的主循环。

### 一、query() 的整体流程

一轮对话从用户按下回车开始，到 Claude 给出最终回复结束，中间经历的完整流程：

1. **用户输入** → 进入 query() 函数
2. **构建 System Prompt**（动态组装十几个 Section）
3. **上下文压缩检查**（五步从轻到重）
4. **调用 Claude API**（流式接收）
5. **解析响应**：
   - 如果是 `end_turn` → 返回结果，结束
   - 如果是 `tool_use` → 执行工具，把结果追加到消息列表
6. **回到步骤 3**，继续循环

### 二、消息列表的结构

Claude Code 维护一个消息列表（messages array），结构如下：

```typescript
type Message = {
  role: 'user' | 'assistant'
  content: ContentBlock[]
}

type ContentBlock =
  | { type: 'text', text: string }
  | { type: 'tool_use', id: string, name: string, input: object }
  | { type: 'tool_result', tool_use_id: string, content: string }
```

每次循环：
- 模型返回 `tool_use` → 追加一条 assistant 消息
- 执行工具得到结果 → 追加一条 user 消息（包含 tool_result）
- 再次调用 API 时，把整个消息列表发过去

### 三、工具执行的编排

Claude Code 支持**并发执行**多个工具调用。模型可以在一次响应中返回多个 `tool_use`，Claude Code 会判断哪些可以并行、哪些必须串行：

- **可并行**：多个 Read 操作、多个 Grep 搜索
- **必须串行**：Write 操作（可能有依赖关系）、Bash 命令（可能有副作用）

### 四、流式输出

Claude Code 使用 Server-Sent Events (SSE) 接收 API 响应，实现逐 token 输出：

- 用户能实时看到 Claude 的思考过程
- 如果 Claude 开始调用工具，UI 会实时显示工具名称和参数
- 工具执行结果也会实时展示

### 五、错误处理与重试

- API 调用失败：指数退避重试（最多 3 次）
- 工具执行失败：把错误信息作为 tool_result 返回给模型，让模型自己决定怎么处理
- 上下文溢出：触发压缩流程，然后重试

### 六、Turn 计数与安全阀

Claude Code 维护一个 `turnCount` 计数器，防止无限循环：
- 每次工具调用算一个 turn
- 超过阈值（默认约 200 turns）会强制停止并提示用户

---

## 文章七：Claude Code 上下文管理详解：Compact 压缩机制怎么实现？

原创 公众号@小林coding | 大约 35 分钟 约 10500 字

---

大家好，我是小林。

这篇文章我们来深入拆解 Claude Code 的上下文压缩机制——也就是 `/compact` 命令背后的实现原理。

### 一、为什么需要压缩？

Claude Code 的上下文窗口是有限的（200K token）。一次复杂的编程任务可能涉及：
- 读取几十个文件（每个几百行）
- 执行十几条命令（每条有输出）
- 来回对话几十轮

这些内容加起来很容易超过 200K。如果不做压缩，要么报错，要么被迫丢弃重要信息。

### 二、五步压缩策略（从轻到重）

Claude Code 的压缩不是一步到位的，而是**分五步逐步升级**，每一步都比上一步更激进：

#### 第 1 步：大结果存磁盘（Spill to Disk）

当工具返回的结果超过阈值（约 30KB），不把完整内容留在上下文里，而是：
1. 把完整结果写到临时文件（如 `/tmp/claude-result-xxx.txt`）
2. 上下文里只留一句话：「结果已保存到 /tmp/claude-result-xxx.txt，共 1234 行。如需查看请用 Read 工具读取。」

这样一个 30KB 的工具输出变成了不到 100 字节的指针。

#### 第 2 步：砍掉远古消息（Drop Old Messages）

对话超过一定轮数后，把最早的几轮消息直接删掉。保留策略：
- System prompt 永远保留
- 最近 N 轮消息保留（N 根据剩余空间动态计算）
- 被删除的消息会留一个占位符：「[前 15 轮对话已省略]」

#### 第 3 步：裁剪老的工具输出（Trim Tool Results）

保留工具调用的「骨架」，但把返回结果截断：
- 保留：调了什么工具、传了什么参数
- 截断：返回结果只保留前 N 行或摘要

例如，一个 Read 工具读了 500 行代码，压缩后变成：「Read(src/index.ts) → [500 行，前 10 行如下：...]」

#### 第 4 步：读时投影（Read-time Projection）

不修改原始消息，而是在发送给 API 前做一次「投影」变换：
- 大段代码块 → 替换为行号范围标记
- 重复出现的文件内容 → 只保留最新版本
- 模型需要看细节时再用 Read 工具去读

#### 第 5 步：全量摘要（Full Summarization）

当前面四步都不够时，用模型对整个对话做一次摘要：

```typescript
const summary = await summarize({
  messages: allMessages,
  instruction: "请总结到目前为止的对话，保留所有关键决策、文件修改、未完成的任务。"
})
```

生成一段精炼的「到目前为止发生了什么」，替换掉所有历史消息。这是最激进的压缩，会丢失细节，但能腾出大量空间。

### 三、/compact 命令的实现

用户手动输入 `/compact` 时，会直接触发第 5 步（全量摘要）。你也可以带参数：

```plain
/compact 请重点保留关于数据库迁移的讨论
```

这个参数会作为摘要指令的一部分，让模型在压缩时重点保留你关心的内容。

### 四、自动压缩触发

Claude Code 不只是等你手动 `/compact`，它会在每次调用 API 前自动检查上下文大小：
- 如果接近 80% 容量 → 触发第 1-3 步
- 如果接近 90% 容量 → 触发第 4 步
- 如果超过 95% 容量 → 触发第 5 步

### 五、压缩的代价

压缩不是免费的：
- 第 5 步需要额外调用一次 API（用 Sonnet 做摘要，比 Opus 便宜）
- 摘要会丢失细节，模型可能忘记之前讨论过的某些内容
- 所以 Claude Code 尽量用轻量级的前几步，只有万不得已才用全量摘要

---

## 文章八：Claude Code 代码检索详解：为什么用 grep 而不用 RAG？

原创 公众号@小林coding | 大约 35 分钟 约 10500 字

---

大家好，我是小林。

这篇文章我们来聊一个很多人好奇的问题：Claude Code 在面对一个大型代码库时，是怎么找到相关代码的？

答案可能出乎你的意料：**它用的是 grep，而不是 RAG（检索增强生成）**。

### 一、为什么不用 RAG？

RAG 的标准流程是：把代码切成 chunk → 转成 embedding 向量 → 存入向量数据库 → 用户提问时做相似度检索 → 把相关 chunk 塞进上下文。

听起来很合理，但在代码检索场景下有几个致命问题：

#### 问题一：代码的语义和文本相似度不一致

你搜「数据库连接池配置」，RAG 可能给你返回一段注释里提到「连接池」的无关代码，而真正的配置文件因为变量名是 `poolConfig` 而被漏掉。

代码的语义高度依赖上下文（import 关系、类型定义、调用链），单纯的文本相似度根本不够。

#### 问题二：chunk 切分破坏代码结构

代码不像自然语言文档那样可以按段落切分。一个函数可能跨越 100 行，中间切一刀就失去了完整语义。按文件切又太粗，一个 2000 行的文件里你可能只需要其中 10 行。

#### 问题三：索引维护成本高

代码库是活的，每次 git pull 都可能改几十个文件。RAG 需要实时更新索引，否则搜到的是过时代码。对于一个活跃的项目，索引维护的成本比搜索本身还高。

#### 问题四：精确匹配需求

编程场景下，很多搜索是精确匹配：「找到所有调用 `getUserById` 的地方」「找到 `DATABASE_URL` 的定义」。这种需求用 grep 一行命令就搞定，RAG 反而可能漏掉。

### 二、Claude Code 的代码检索工具

Claude Code 提供了两个核心检索工具：

#### Grep 工具

底层用的是 ripgrep（rg），速度极快：

```typescript
// 工具定义
{
  name: 'Grep',
  description: '在文件中搜索文本模式',
  input: {
    pattern: string,      // 正则表达式
    path?: string,        // 搜索路径
    include?: string,     // 文件类型过滤
    caseSensitive?: boolean
  }
}
```

ripgrep 在百万行代码库上的搜索速度通常在毫秒级，比任何向量数据库都快。

#### Glob 工具

用于按文件名模式查找文件：

```typescript
{
  name: 'Glob',
  description: '按模式查找文件路径',
  input: {
    pattern: string  // 如 "**/*.test.ts"
  }
}
```

### 三、模型如何使用这些工具

Claude Code 的模型会根据任务自主决定搜索策略：

1. **先用 Glob 定位文件**：「找到所有 API handler 文件」→ `Glob("src/api/**/*.ts")`
2. **再用 Grep 精确搜索**：「找到 getUserById 的定义」→ `Grep("function getUserById|const getUserById")`
3. **最后用 Read 读取上下文**：找到目标后，读取完整文件理解上下文

这种「先粗后细」的搜索策略，比 RAG 的「一次性召回」更灵活、更精确。

### 四、为什么这种方案更好？

| 维度 | RAG | Grep + Glob |
|------|-----|-------------|
| 精确匹配 | 弱（依赖 embedding 相似度） | 强（正则精确匹配） |
| 速度 | 毫秒级（但需要预建索引） | 毫秒级（无需预建索引） |
| 维护成本 | 高（需要实时更新索引） | 零（直接搜源文件） |
| 代码结构感知 | 弱（chunk 切分破坏结构） | 强（搜到后读完整文件） |
| 部署复杂度 | 高（需要向量数据库） | 零（只需要 ripgrep） |
| 可解释性 | 低（为什么召回这段？） | 高（因为匹配了这个模式） |

### 五、这对我们的启示

1. **不要迷信 RAG**：在精确匹配需求为主的场景（代码搜索、日志分析），传统搜索工具可能比 RAG 更好
2. **工具组合比单一方案强**：Glob + Grep + Read 的组合，比一个 RAG 系统更灵活
3. **让模型自己决定搜索策略**：不要预设搜索流程，让模型根据任务自主选择工具

---

## 文章九：Claude Code 记忆机制详解：为什么不用向量数据库？

原创 公众号@小林coding | 大约 36 分钟 约 10669 字

---

大家好，我是小林。

agent 的记忆机制，如今已经是个不折不扣的面试热点。只要你简历上挂着一个 agent 项目，面试官大概率会追着问一句：「你这个 agent 的记忆机制，到底是怎么做的？」

![](https://cdn.xiaolincoding.com//picgo/01-interview-memory-question-250f0192.png)

而大多数人能端出来的，往往只有一个标准答案：「上向量数据库，把对话存成 embedding，每次新会话做相似度检索。」这答案不能算错，但只要你知道 Claude Code 偏偏不这么做，就会发现它平平无奇。

### 一、先聊聊「LLM 其实没记忆」

#### LLM 的「金鱼记忆」是怎么回事

**LLM 本身根本「记不住」任何东西**，它是彻头彻尾**无状态**的。每次你按下回车，对它来说都是「从头看一遍」：把系统提示词、所有历史对话、当前问题，全部塞进去，然后输出一个回复。

![](https://cdn.xiaolincoding.com//picgo/02-llm-stateless-call-cdbf3ef1.png)

你以为它记得，其实是因为你的客户端**偷偷把历史消息又一起发了过去**。

#### agent 真正缺的是哪种记忆

agent 想记住的东西，跟「历史对话」其实不是一回事：

- 用户画像：你是谁、擅长什么、知识水平如何
- 行为偏好：你不喜欢什么，喜欢什么
- 项目动态：当前项目要干啥、有什么截止日期
- 外部指针：去哪查什么信息

![](https://cdn.xiaolincoding.com//picgo/04-four-memory-needs-bb94d6c4.png)

### 二、业界主流的记忆方案为什么不够看？

#### 方案一：滑动窗口 Memory

把最近 N 轮对话原样保留，超过 N 轮的就丢掉。**关键信息和无关信息混在一起被丢**，是滑动窗口的硬伤。

#### 方案二：对话摘要 Memory

定期把旧对话用 LLM 总结一下，把摘要塞回上下文。**重要的细节被压糊**，是摘要 Memory 的硬伤。

#### 方案三：向量检索 Memory

把每条记忆转成 embedding 向量，存进向量数据库，每次新对话做相似度检索。问题：
- 相似不等于相关
- 召回不稳定
- 维护成本高
- 用户没法看（768 维浮点数人脑读不懂）

#### 方案四：分层存储 Memory

MemGPT 的方案，把记忆分成 core/recall/archival 三层。学术上漂亮，但工程上落地复杂。

#### 这四类方案的共同病根

1. 自由文本无约束
2. 不区分类型
3. 没有老化机制
4. 重检索、轻写入

![](https://cdn.xiaolincoding.com//picgo/12-memory-schemes-table-a4fe44c1.png)

### 三、Claude Code 的两层记忆架构鸟瞰

Claude Code 没用向量数据库，没用 embedding，没用任何复杂的存储引擎。它用的是**磁盘上的 markdown 文件**。

两条独立的线，并行工作：

![](https://cdn.xiaolincoding.com//picgo/13-two-layer-memory-architecture-433f1444.png)

- **静态层是 CLAUDE.md 体系**：「声明式指令」，你写好放那里，agent 启动时全量加载
- **动态层是自动记忆系统**：「学习式偏好」，agent 在互动中自动写成记忆文件存到磁盘

### 四、静态层：CLAUDE.md 的六个层级

按加载顺序从低到高：

![](https://cdn.xiaolincoding.com//picgo/38-six-level-overview-31376dfd.png)

- **Managed**：系统级路径，只有管理员能改。公司级强制策略
- **User**：用户家目录下，全局偏好
- **Project**：项目根目录的 CLAUDE.md，签入 git 让团队共享
- **Local**：CLAUDE.local.md，不签入 git
- **Auto**：自动记忆目录，Claude Code 自动写入的偏好
- **Team**：团队共享的 AI 学到的偏好

六层之间是**叠加关系**不是覆盖关系。

#### @include：让 CLAUDE.md 互相引用

写一行 `@~/company/security-rules.md`，加载时自动把那个文件的内容读进来拼上。

#### 条件规则：编辑 .tsx 才加载前端规范

`.claude/rules/` 目录下支持 path-scoped rules：

```markdown
---
name: 前端规范
paths: ["**/*.tsx", "**/*.jsx"]
---
# 前端规范
...（规则正文）
```

#### 截断双保险：防长行索引炸弹

```ts
export const MAX_ENTRYPOINT_LINES = 200
export const MAX_ENTRYPOINT_BYTES = 25_000
```

两个限制任意一个先触发，就截断。

### 五、动态层：自动记忆系统的完整闭环

#### 为什么还需要动态记忆

CLAUDE.md 得你主动写。理想状态是：**Claude 在跟你聊天的过程中，自动把它学到的东西记下来**。

![](https://cdn.xiaolincoding.com//picgo/20-dynamic-memory-loop-5af235d0.png)

#### 四种类型

- **UserPreference**：用户偏好
- **ProjectConvention**：项目约定
- **ToolUsagePattern**：工具使用模式
- **LessonLearned**：经验教训

#### 该存什么 vs 不该存什么

该存：用户明确表达的偏好、反复出现的模式、项目特有的约定
不该存：代码里 grep 一下就能找到的、一次性的对话上下文、推测

#### 存储设计：单文件 + 索引

每条记忆是一个独立 markdown 文件。索引文件（MEMORY.md）常驻 system prompt。

#### 写入：Extract Memories 代理

对话结束时，用一个轻量级代理分析对话，提取值得记住的信息，写成记忆文件。

#### 检索：用 Sonnet 选 top-5

不用向量数据库。给 Sonnet 索引列表 + 当前问题，让它选出最相关的 top-5 条记忆。

#### 注入：system-reminder 包裹 + 老化警告

记忆注入时带时间戳，超过 30 天的记忆会标注「此记忆可能已过时，请验证后再使用」。

### 六、几个值得借鉴的设计选择

1. **结构化优于自由文本**：四种类型分类，而不是一锅炖
2. **索引常驻 + 内容按需**：索引占用少量 token 常驻，完整内容按需加载
3. **廉价模型做选择题**：用 Sonnet 而不是 Opus 做记忆检索，省钱
4. **时间感知 + 主动验证**：记忆带时间戳，过期的主动提醒验证

### 七、这道面试题该怎么答？

面试官问「你的 agent 记忆机制怎么做的」，你可以这样答：

> 我参考了 Claude Code 的两层记忆架构。静态层用 CLAUDE.md 分六级管理声明式规则，动态层用结构化 markdown 文件存储四类记忆（偏好/约定/模式/教训）。检索不用向量数据库，而是用廉价模型对索引做选择题，选出 top-5 注入上下文。这样做的好处是：零部署成本、用户可读可编辑、天然支持老化机制。

---

## 文章十：Claude Code 多 Agent 详解：SubAgent 实现机制怎么做？

原创 公众号@小林coding | 大约 40 分钟 约 12130 字

---

大家好，我是小林。

最近不少朋友跟我反馈，说 AI Agent 岗的面试越来越多，十有八九都要问 Multi-Agent。

Claude Code 里跟「多 agent」沾边的代码其实有三套不同的机制：**常规 Subagent、Fork Subagent、Coordinator 协调者模式**。

![](https://cdn.xiaolincoding.com//picgo/01-cover-multi-agent.png)

### 一、先搞明白 Multi-Agent 到底是个啥

#### 为什么一个 agent 不够用？

单 agent 面对复杂任务的三个麻烦：
1. **上下文会爆炸**：调研、实现、评审三个阶段的内容全塞一个上下文里
2. **职责混乱**：既当研究员又当程序员又当评审员
3. **没法并发**：一次只能做一件事

![](https://cdn.xiaolincoding.com//picgo/02-single-agent-overload.png)

#### Multi-Agent 的三种常见形态

![](https://cdn.xiaolincoding.com//picgo/03-three-patterns-comparison.png)

1. **父子型**：主 agent 派 subagent 出去搞定子问题
2. **平级协作型**：几个 agent 职责对等，通过共享状态协作
3. **主从型（Coordinator-Worker）**：协调者不干活，只负责派 worker、收结果

Claude Code 中：**常规 Subagent** 对应父子型，**Coordinator 模式** 对应主从型，**Fork Subagent** 是父子型的特殊优化版本。

![](https://cdn.xiaolincoding.com//picgo/04-claude-mechanism-mapping.png)

#### subagent 在 Claude Code 里到底长啥样？

主 agent 通过一个叫 **Agent** 的工具，把任务交给一个内置 subagent（如 Explore）去跑。Explore 带着精简的工具池（只有只读工具），带着独立的上下文，跑完把结果打包回来。

![](https://cdn.xiaolincoding.com//picgo/05-agent-tool-dispatch-explore.png)

### 二、Subagent 的隔离机制

#### 第一维度：给子 agent 发一个定制工具箱

三道准入门：

![](https://cdn.xiaolincoding.com//picgo/07-three-permission-gates.png)

1. **全局黑名单**：禁止派新 subagent、禁止问用户问题、禁止切换规划模式
2. **自定义 agent 加严**：用户写的 agent 比内置的多一层限制
3. **后台异步 agent 走白名单**：默认不准用，明确列出来的才能用

```typescript
export function filterToolsForAgent({ tools, isBuiltIn, isAsync }): Tools {
  return tools.filter(tool => {
    if (ALL_AGENT_DISALLOWED_TOOLS.has(tool.name)) return false
    if (!isBuiltIn && CUSTOM_AGENT_DISALLOWED_TOOLS.has(tool.name)) return false
    if (isAsync && !ASYNC_AGENT_ALLOWED_TOOLS.has(tool.name)) return false
    return true
  })
}
```

#### 第二维度：搭一个隔离的运行环境

四个关键决策：

![](https://cdn.xiaolincoding.com//picgo/09-four-context-decisions.png)

1. **「读文件的缓存」要复制一份给子 agent**：防止子 agent 污染父 agent 的文件视图
2. **「改全局状态」对子 agent 直接关闭**：设为空操作
3. **「注册后台任务」这条通路保留**：防止孤儿进程
4. **给每个 subagent 发独立 ID、深度 +1**：防止嵌套失控

```typescript
export function createSubagentContext(parentContext, overrides): ToolUseContext {
  return {
    readFileState: cloneFileStateCache(parentContext.readFileState),
    setAppState: () => {},
    setAppStateForTasks: parentContext.setAppStateForTasks,
    agentId: overrides?.agentId ?? createAgentId(),
    queryTracking: {
      chainId: randomUUID(),
      depth: (parentContext.queryTracking?.depth ?? -1) + 1,
    },
  }
}
```

### 三、父子 Agent 是怎么通信的

#### 默认形态：派出去，跑完把结果交回来

父 agent 对正在跑的子 agent 是「只能等」的，没法中途塞新指令。

**auto-background 机制**：如果 subagent 跑超过 2 分钟还没完，自动转到后台，让父 agent 继续干别的。

完成通知用 XML 格式，伪装成用户消息：

```xml
<task-notification>
  <task-id>agent-a1b</task-id>
  <status>completed</status>
  <summary>Agent "Investigate auth bug" completed</summary>
  <result>Found null pointer in src/auth/validate.ts:42...</result>
</task-notification>
```

![](https://cdn.xiaolincoding.com//picgo/15-xml-as-user-message.png)

#### 团队（agent-teams）模式：父子之间真正双向对讲

开启后升级成完整的**双向消息驱动**。每个 subagent 有「信箱」，父 agent 往信箱里扔字条，subagent 也能回话。

### 四、Fork Subagent：省钱又省延迟的隐藏大招

#### subagent 的隐藏成本

常规 subagent 每次启动都要重新构建 system prompt、重新发送给 API，前面的 prompt cache 全部浪费。

#### Fork 的核心思路：派一个「字节级相同」的分身

Fork subagent 直接复制父 agent 的完整上下文（包括 system prompt 和对话历史），只在末尾追加新任务。这样 API 层面的 prompt cache 可以完美命中，省下大量 token 费用和延迟。

#### 什么时候用 Fork，什么时候用常规 subagent？

- **Fork**：子任务和父任务高度相关，需要父的上下文
- **常规**：子任务独立，不需要父的历史

### 五、Coordinator 模式：真正的多 Agent 并行协作

#### 核心设计：主 agent 退化成「纯协调者」

开启 Coordinator 模式后，主 agent 不再自己写代码，只负责：
1. 理解用户需求
2. 拆分任务
3. 派 worker
4. 收集结果
5. 合成最终回复

#### 三大内部工具

- **spawn_worker**：创建新 worker
- **continue_worker**：给已有 worker 追加指令
- **get_worker_status**：查看 worker 状态

#### 并行才是真本事

Coordinator 可以同时派出多个 worker 并行工作：
- Worker A：改前端组件
- Worker B：改后端 API
- Worker C：写测试

三个 worker 同时跑，互不干扰，最后协调者合成结果。

#### 跟常规 subagent 对比

| 维度 | 常规 Subagent | Coordinator 模式 |
|------|-------------|----------------|
| 主 agent 角色 | 自己也干活 | 纯协调，不干活 |
| 并行能力 | 一次一个 | 多个 worker 并行 |
| 通信方式 | 单向（子→父） | 双向（协调者↔worker） |
| 适合场景 | 简单子任务 | 复杂多模块任务 |

### 六、5 条 Multi-Agent 设计原则

1. **上下文隔离要按字段粒度做**：不是全隔离或不隔离，而是每个状态单独决策
2. **通信走消息，不走函数调用**：消息驱动比 RPC 更灵活，支持异步和双向
3. **工具权限要分级管控**：按 agent 类型做细粒度的权限控制
4. **缓存友好是一种架构能力**：Fork subagent 的设计让 prompt cache 命中率最大化
5. **并行优先 + 协调者合成**：能并行的任务一定要并行，最后由协调者合成
