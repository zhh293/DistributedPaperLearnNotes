# Agent可观测性与调试

> 面向面试准备与工程实践的深度技术文档。本文所有企业内部信息均已脱敏，以「某互联网公司」「某Agent平台」等通用描述代替真实产品名称。

---

## 一、为什么Agent需要独立的可观测性体系

### 1.1 传统服务可观测性 vs Agent可观测性的本质区别

做了很多年后端的工程师，脑子里对可观测性的认知基本是这样一套：一个 HTTP 请求进来，经过网关、若干个 RPC 调用、几次数据库查询，然后返回。链路是**确定的、可预测的、无状态的**。你只要盯住 QPS、P99 延迟、错误率、机器资源这几个指标，基本就能掌控系统健康度。这就是经典的「黄金四指标」（Latency、Traffic、Errors、Saturation）。

Agent 系统把这套认知彻底打乱了。核心差异在于下面几点：

| 维度 | 传统微服务 | Agent 系统 |
|------|-----------|-----------|
| 执行路径 | 确定，代码写死 | 非确定，由 LLM 动态决策 |
| 单次请求内部 | 几次 RPC/DB 调用 | 多轮 LLM 调用 + 数十次工具执行 |
| 核心指标 | QPS、延迟、错误率 | Token 成本、工具成功率、决策质量 |
| 错误定义 | HTTP 5xx、异常栈 | 幻觉、死循环、参数错误（无异常栈也算错） |
| 可重现性 | 相同输入相同输出 | 相同输入可能走完全不同的路径 |
| 成本 | 相对固定（机器成本） | 强波动（Token 按量计费，单次可差百倍） |

一句话总结：**传统可观测性关心「系统是否在正常运行」，Agent 可观测性还要关心「Agent 是否在做正确的事，以及做这件事花了多少钱」。**

### 1.2 Agent 的"一次请求"可能包含多轮 LLM 调用和数十次工具执行

传统服务里，一次请求就是一次请求。但在 Agent 场景，一次用户请求（比如"帮我把这周的销售数据汇总成一份报告"）背后可能是这样的：

```
用户请求
 └─ Agent 决策 1：需要先查数据库
     └─ LLM 调用 1（决定调 query_db 工具）
     └─ 工具执行 1：query_db（耗时 800ms）
 └─ Agent 决策 2：数据不完整，还要查另一张表
     └─ LLM 调用 2
     └─ 工具执行 2：query_db（耗时 600ms）
 └─ Agent 决策 3：开始生成报告
     └─ LLM 调用 3（生成 Markdown，输出 3000 tokens）
     └─ 工具执行 3：write_file
 └─ Agent 决策 4：任务完成
     └─ LLM 调用 4（生成总结回复）
```

一次用户请求，展开成了 4 次 LLM 调用、3 次工具执行。如果是个复杂的 ReAct Agent，可能是几十次循环。**如果你只在最外层打一条日志"请求成功/失败"，你对内部发生了什么一无所知。** 出了问题根本没法排查——是第几轮 LLM 决策错了？哪个工具返回了脏数据？Token 是被哪一步烧掉的？

这就是为什么 Agent 必须有**细粒度的、结构化的、层级化的**可观测性，把每一步都记录下来。

### 1.3 非确定性导致相同输入可能走完全不同的执行路径

LLM 的采样过程本身带有随机性（temperature > 0 时尤为明显）。这意味着：

- 同一个用户问题，跑两次，Agent 可能第一次调了 3 个工具，第二次调了 5 个工具。
- 线上出现一个 bad case，你想在本地复现，结果怎么都复现不出来。

这带来两个可观测性上的硬需求：

1. **必须记录完整的执行快照**：包括当时的 prompt、模型返回的原始内容、工具的入参出参、随机种子（如果有）。因为你无法靠"重跑一遍"来复现问题。
2. **必须支持 Trace 回放**：把线上那次执行的完整链路存下来，可以离线一步步回看，而不是依赖复现。

### 1.4 成本是第一优先级指标

这是 Agent 可观测性里最反直觉、也最容易被后端工程师忽略的一点。传统服务里成本是运维关心的事，跟业务逻辑没关系。但 Agent 里，**成本是一等公民指标，甚至优先级高于延迟**。

原因很简单：

- LLM 按 Token 计费，一次复杂 Agent 任务可能消耗几万到几十万 Token。
- 不同任务的成本差异可以达到几十倍甚至上百倍。
- 一个写得不好的 Agent（比如陷入工具调用死循环），可能在几分钟内烧掉几百块的 API 费用。
- 输出 Token 通常比输入 Token 贵 3~5 倍，一个话痨 Agent 的成本会失控。

所以一个成熟的 Agent 平台，一定有一套完整的 **Token 经济账本**，能实时回答："这次会话花了多少钱？这个用户这个月花了多少钱？哪个 Agent 最烧钱？" 这部分我们在第六章详细展开。

---

## 二、Agent可观测性三大支柱

可观测性的经典三支柱是 Tracing、Metrics、Logging。在 Agent 场景下，这三者都要做针对性的重新设计。

### 2.1 Tracing（链路追踪）

#### 2.1.1 Trace 和 Span 概念在 Agent 场景的重新定义

先复习分布式追踪的基本概念（源自 Google Dapper 论文，后来标准化为 OpenTelemetry）：

- **Trace**：一次完整的请求链路，有唯一的 `trace_id`。
- **Span**：链路中的一个操作单元，有 `span_id`，可以有 `parent_span_id`，从而形成树形结构。
- **Span 属性**：记录这个操作的名称、开始/结束时间、标签（attributes）、事件（events）。

映射到 Agent 场景：

- **一个 Trace** = 用户的一次完整请求（从提问到最终得到答案）。
- **一个 Span** = 执行链路中的一个环节，可能是一次 LLM 调用、一次工具执行、一次子 Agent 委托。

关键点在于 Span 的**类型划分**。传统追踪里 Span 大多是 RPC 调用或 DB 查询，比较单一。Agent 里的 Span 类型更丰富，语义也更重要。

#### 2.1.2 Agent Span 类型

一个设计良好的 Agent tracing 体系，通常会定义这几类 Span：

| Span 类型 | 记录内容 | 关键属性 |
|-----------|---------|---------|
| `LLM_CALL` | 一次大模型推理 | model、input_tokens、output_tokens、temperature、prompt、completion、TTFT |
| `TOOL_EXECUTION` | 一次工具/函数调用 | tool_name、arguments、result、is_error、duration |
| `AGENT_DECISION` | Agent 的一次决策循环 | iteration、chosen_action、reasoning |
| `RETRIEVAL` | 知识检索（RAG） | query、top_k、retrieved_doc_ids、rerank_score、vector_db_latency |
| `SUB_AGENT` | 子 Agent 委托 | sub_agent_name、delegated_task |

OpenTelemetry 社区专门推出了 **Semantic Conventions for Generative AI**，规定了一批标准化的属性名，比如 `gen_ai.system`、`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`。用标准命名的好处是各家可观测性平台能直接识别和展示。

#### 2.1.3 分布式追踪在多 Agent 场景的应用

当系统演进到多 Agent 协作（比如一个 Orchestrator Agent 调度多个 Worker Agent），追踪就变成真正的分布式追踪问题。核心是 **context propagation（上下文传播）**：

- Orchestrator 发起时生成 `trace_id`。
- 调用某个 Worker Agent 时，把 `trace_id` 和当前 `span_id`（作为 Worker 侧的 `parent_span_id`）透传过去。
- Worker Agent 内部产生的所有 Span 都挂在这条 trace 上。

这样最终能拼出一棵完整的跨 Agent 调用树，哪怕这些 Agent 部署在不同的服务、不同的机器上。传播方式一般走 W3C Trace Context 标准（HTTP header 里的 `traceparent`）。

#### 2.1.4 OpenTelemetry 三信号集成

OpenTelemetry（简称 OTel）是目前可观测性领域的事实标准，它统一了 Metrics、Logs、Traces 三种信号。对 Agent 系统来说，用 OTel 的好处是：

- **厂商中立**：埋点一次，可以导出到任意后端（Jaeger、Prometheus、各类商业平台）。
- **三信号关联**：一条日志可以带上 `trace_id`，一个指标可以关联到具体的 span，排查问题时能互相跳转。

#### 2.1.5 代码示例：如何为 Agent 添加 tracing

下面是一个用 OpenTelemetry Python SDK 为 Agent 手动埋点的例子：

```python
from opentelemetry import trace
from opentelemetry.trace import Status, StatusCode

tracer = trace.get_tracer("my-agent")

def run_agent(user_query: str):
    # 最外层 Span = 一次完整请求 = 一个 Trace 的根
    with tracer.start_as_current_span("agent.request") as root_span:
        root_span.set_attribute("user.query", user_query)

        messages = [{"role": "user", "content": user_query}]
        for iteration in range(MAX_ITERATIONS):
            # 每一轮决策循环一个 Span
            with tracer.start_as_current_span("agent.iteration") as iter_span:
                iter_span.set_attribute("iteration", iteration)

                # LLM 调用 Span
                with tracer.start_as_current_span("llm.call") as llm_span:
                    llm_span.set_attribute("gen_ai.request.model", MODEL)
                    resp = call_llm(messages)
                    # 记录 token 用量 —— 这是 Agent 追踪最关键的属性之一
                    llm_span.set_attribute("gen_ai.usage.input_tokens", resp.usage.input_tokens)
                    llm_span.set_attribute("gen_ai.usage.output_tokens", resp.usage.output_tokens)

                if resp.tool_calls:
                    for tc in resp.tool_calls:
                        # 工具执行 Span
                        with tracer.start_as_current_span("tool.execution") as tool_span:
                            tool_span.set_attribute("tool.name", tc.name)
                            tool_span.set_attribute("tool.arguments", json.dumps(tc.arguments))
                            try:
                                result = execute_tool(tc)
                                tool_span.set_attribute("tool.result_size", len(str(result)))
                            except Exception as e:
                                # 工具失败要标记 Span 状态，方便后续按错误过滤
                                tool_span.set_status(Status(StatusCode.ERROR, str(e)))
                                tool_span.record_exception(e)
                                raise
                            messages.append(make_tool_result_message(tc, result))
                else:
                    # 没有工具调用，说明 Agent 认为任务完成
                    root_span.set_attribute("final_answer", resp.content)
                    return resp.content
```

实际项目里更推荐用**自动埋点库**（比如 `openllmetry`、`openinference`），它们会自动 patch 主流 LLM SDK 和 Agent 框架，几乎零侵入地生成上述 Span。

### 2.2 Metrics（指标监控）

Tracing 解决"某一次请求发生了什么"，Metrics 解决"整体系统的统计趋势"。

#### 2.2.1 Token 经济账本：UsageTracker 设计

Agent 平台的核心是一个 `UsageTracker`（用量追踪器），负责累计每次 LLM 调用的 Token 消耗。一个典型的设计：

```python
from dataclasses import dataclass, field
from threading import Lock

@dataclass
class TokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_creation_tokens: int = 0   # 写入 prompt cache 的 token
    cache_read_tokens: int = 0       # 命中 prompt cache 的 token

@dataclass
class UsageTracker:
    # 本次会话的累计用量
    session_usage: TokenUsage = field(default_factory=TokenUsage)
    # 每一轮的快照（用于 Gauge 语义）
    last_turn_usage: TokenUsage = field(default_factory=TokenUsage)
    _lock: Lock = field(default_factory=Lock)

    def record(self, usage: TokenUsage):
        with self._lock:
            # Counter 语义：历史累计只增不减
            self.session_usage.input_tokens += usage.input_tokens
            self.session_usage.output_tokens += usage.output_tokens
            self.session_usage.cache_creation_tokens += usage.cache_creation_tokens
            self.session_usage.cache_read_tokens += usage.cache_read_tokens
            # Gauge 语义：只保留本轮快照
            self.last_turn_usage = usage

    def cost(self, pricing) -> float:
        u = self.session_usage
        return (
            u.input_tokens * pricing.input
            + u.output_tokens * pricing.output
            + u.cache_creation_tokens * pricing.cache_write
            + u.cache_read_tokens * pricing.cache_read
        ) / 1_000_000  # 按每百万 token 计价
```

#### 2.2.2 Gauge vs Counter 语义（本次对话快照 vs 历史累计）

这是一个非常容易被搞混、但面试爱问的点：

- **Counter（计数器）**：单调递增，语义是"历史累计"。比如"这个会话从开始到现在一共消耗了 12 万 input tokens"。适合做成本账单。
- **Gauge（仪表盘）**：可增可减，语义是"当前时刻的快照"。比如"本轮对话消耗了 3000 tokens"、"当前上下文占用了 8 万 tokens"。适合看瞬时状态。

为什么这个区分重要？举个例子：如果你想在 UI 上给用户展示"你当前上下文还剩多少空间"，你得用 Gauge（当前快照）；如果你想给用户出账单，你得用 Counter（历史累计）。用错了语义，数据就完全错了。

Prometheus 里这两种是不同的 metric 类型，暴露和查询方式都不一样：
- Counter 通常配合 `rate()` 函数看增长速率。
- Gauge 直接看当前值或 `max_over_time()`。

#### 2.2.3 关键指标

一个 Agent 平台至少要监控这些指标：

| 指标 | 类型 | 说明 |
|------|------|------|
| `input_tokens_total` | Counter | 累计输入 token |
| `output_tokens_total` | Counter | 累计输出 token |
| `cache_hit_rate` | Gauge | prompt cache 命中率 = cache_read / (cache_read + input) |
| `tool_call_count` | Counter | 工具调用次数（按 tool_name 分维度） |
| `tool_error_rate` | Gauge | 工具失败率 |
| `agent_iterations` | Histogram | 单次请求的决策循环次数分布 |
| `llm_latency` | Histogram | LLM 调用延迟分布（P50/P90/P99） |
| `ttft` | Histogram | Time To First Token，首 token 延迟 |
| `e2e_latency` | Histogram | 端到端延迟 |
| `cost_per_request` | Histogram | 单次请求成本分布 |

其中 **cache hit rate** 是省钱的关键指标——prompt cache 命中的 token 通常只要正常价格的 1/10，命中率高低直接影响成本。

#### 2.2.4 成本归因：按 Agent/用户/会话维度

指标必须打上足够的维度标签（label / dimension），才能做归因分析。典型维度：

- `agent_name`：哪个 Agent 花的钱
- `user_id`：哪个用户花的钱
- `session_id`：哪次会话花的钱
- `model`：哪个模型花的钱
- `org_id` / `team_id`：哪个组织/团队花的钱

注意维度的**基数（cardinality）问题**：`user_id`、`session_id` 这种高基数维度，如果直接打到 Prometheus 这类时序数据库上会导致指标爆炸。工程上一般的做法是：粗粒度维度（agent、model、team）放时序库做实时监控，细粒度维度（user、session）放数据仓库/OLAP（如 ClickHouse）做离线归因分析。

### 2.3 Logging（日志体系）

#### 2.3.1 结构化遥测事件

Agent 日志绝对不能是 `print("调用工具成功")` 这种非结构化文本。必须是**结构化的遥测事件（structured telemetry event）**，一般用 JSON：

```json
{
  "timestamp": "2024-06-16T10:30:00Z",
  "event_type": "tool_execution",
  "trace_id": "abc123",
  "span_id": "def456",
  "session_id": "sess-789",
  "agent_name": "data-analyst",
  "tool_name": "query_db",
  "arguments": {"sql": "SELECT ..."},
  "duration_ms": 823,
  "is_error": false,
  "input_tokens": 0,
  "output_tokens": 0
}
```

结构化日志的好处：可以直接被 ELK / Loki / ClickHouse 索引和查询，能按任意字段过滤聚合，比如"查出所有 duration_ms > 5000 的 tool_execution 事件"。

#### 2.3.2 事件总线设计模式

在 Agent 内部产生遥测数据、又要对接多个下游（存日志、发指标、更新 UI、审计）时，最优雅的解耦方式是 **事件总线（Event Bus）**：

```python
class TelemetryEventBus:
    def __init__(self):
        self._subscribers: dict[str, list] = defaultdict(list)

    def subscribe(self, event_type: str, handler):
        self._subscribers[event_type].append(handler)

    def emit(self, event_type: str, payload: dict):
        for handler in self._subscribers[event_type]:
            try:
                handler(payload)
            except Exception:
                # 遥测的失败绝不能影响主流程
                logger.warning("telemetry handler failed", exc_info=True)

# 使用
bus = TelemetryEventBus()
bus.subscribe("llm_call", lambda e: metrics.record_tokens(e))
bus.subscribe("llm_call", lambda e: logstore.write(e))
bus.subscribe("tool_execution", lambda e: audit_logger.log(e))

# Agent 内部只管发事件，不关心谁在消费
bus.emit("tool_execution", {"tool_name": "query_db", ...})
```

核心设计原则：**Agent 主逻辑只负责"发事件"，不关心谁消费、怎么消费**。这样加一个新的下游（比如新增一个实时告警）只需要 subscribe 一次，不用改主流程。同时遥测处理必须做异常隔离——遥测挂了不能拖垮 Agent 本身。

#### 2.3.3 日志分级与采样策略

Agent 日志量非常大（一次请求几十条），全量存储成本高。策略：

- **分级**：DEBUG（完整 prompt/completion，只在排查时开）、INFO（关键决策和工具调用）、WARN/ERROR（异常）。
- **采样**：正常成功的请求按比例采样（比如 1%），但**错误请求 100% 全采**（tail-based sampling，尾部采样）。这样既省成本又不漏关键 case。
- **敏感字段脱敏**：prompt 里可能含 PII（个人身份信息），入库前要做脱敏或字段级加密。

#### 2.3.4 安全审计日志

有别于普通遥测日志，安全审计日志是**合规刚需，不能采样，不能丢**。要记录：谁（user）、在什么时间、通过哪个 Agent、调用了什么工具、访问了什么数据、参数是什么。尤其是有"写"能力的工具（改数据库、发消息、调外部 API），审计日志是出了事故后追溯责任的唯一依据。审计日志通常单独存储（WORM，一次写入多次读取，不可篡改）。

---

## 三、Agent执行链路可视化

### 3.1 执行链路的树形结构

一个 Trace 由多个 Span 组成，通过 `parent_span_id` 形成一棵树。可视化通常是**火焰图 / 甘特图**的形式，横轴是时间，每一条是一个 Span，缩进表示父子关系：

```
agent.request                              ├──────────────────────────────────┤  12.4s
├─ agent.iteration [0]                     ├────────────┤                        4.2s
│  ├─ llm.call (决定查数据库)               ├────┤                                1.8s  in:1200 out:80
│  └─ tool.execution: query_db             │    ├───────┤                        2.3s
├─ agent.iteration [1]                     │            ├──────────┤             3.1s
│  ├─ llm.call (数据不全,再查)              │            ├───┤                    1.5s  in:2400 out:60
│  └─ tool.execution: query_db             │                ├──────┤             1.5s
└─ agent.iteration [2]                     │                       ├──────────┤  5.1s
   ├─ llm.call (生成报告)                   │                       ├─────────┤   4.8s  in:5600 out:3200
   └─ tool.execution: write_file           │                                 ├┤  0.2s
```

从这个图上，你一眼能看出：
- 总耗时 12.4s，其中最后一次 LLM 生成报告耗了 4.8s（因为输出 3200 tokens，output token 慢是常态）。
- 前两轮各查了一次数据库，说明第一次查询数据不完整——这可能是可优化点（能否一次查全？）。
- 每个 LLM 调用的 token 用量都标出来了，成本一目了然。

### 3.2 主流可观测性工具对比

#### LangSmith

LangChain 官方推出的 tracing 与评测平台。深度绑定 LangChain / LangGraph 生态，只要用了 LangChain，加个环境变量就能自动上报全链路 trace。优势是开箱即用、评测（evaluation）和 prompt 管理功能强；劣势是商业化 SaaS 为主、和 LangChain 强耦合、数据要出公司（有自托管版本但偏贵）。

#### Arize Phoenix

开源的 LLM 可观测性平台，基于 OpenTelemetry 和 OpenInference 语义规范。优势是完全开源、可本地跑、和框架无关（LangChain、LlamaIndex、原生 SDK 都支持）、内置 RAG 评测和 embedding 可视化；劣势是偏向单机/研发阶段调试，大规模生产部署要自己搞。

#### Langfuse

开源的 LLM 工程平台，是 LangSmith 的主流开源替代。优势是自托管友好、支持 trace / prompt 管理 / 评测 / 成本核算一整套、SDK 语言覆盖全、社区活跃；劣势是需要自己运维（Postgres + ClickHouse）。

#### 选型建议

| 场景 | 推荐 |
|------|------|
| 已重度使用 LangChain，想省事 | LangSmith |
| 研发调试、RAG 评测、要开源 | Arize Phoenix |
| 要自托管、数据不能出公司、要全套功能 | Langfuse |
| 大规模生产、已有 OTel 体系 | 基于 OpenTelemetry 自建 + 商业后端 |

### 3.3 企业级 Tracing 平台实践

某互联网公司的 Agent 平台没有直接用上述开源工具，而是基于 OpenTelemetry 语义自建了一套 Tracing 服务，主要考虑数据合规（trace 里含大量业务敏感 prompt，不能出内网）和超大规模（每天数十亿 span）。

#### 3.3.1 某Agent平台的 Tracing API 设计（脱敏）

对外暴露的埋点 API 设计得很克制，核心就三个动作：

```python
# 开启一个 trace（一次用户请求）
trace = tracer.start_trace(session_id="...", user_id="...", agent="...")

# 在 trace 下开一个 span（with 语句自动结束并计算耗时）
with trace.span(type=SpanType.LLM_CALL, name="planning") as span:
    span.set(model="...", input_tokens=..., output_tokens=...)
    span.set(prompt=..., completion=...)   # 大字段，走单独的对象存储

# span 支持嵌套，自动维护 parent_span_id
with trace.span(type=SpanType.TOOL, name="query_db") as span:
    ...
```

#### 3.3.2 Trace/Span 数据模型

```
Trace {
  trace_id        string      // 全局唯一
  session_id      string      // 归属会话
  user_id         string      // 归属用户
  agent_name      string
  start_time      timestamp
  end_time        timestamp
  total_tokens    int         // 汇总字段，避免每次都扫 span
  total_cost      decimal
  status          enum        // SUCCESS / ERROR / TIMEOUT
}

Span {
  span_id         string
  trace_id        string      // 外键
  parent_span_id  string      // 树形结构，根 span 为空
  type            enum        // LLM_CALL / TOOL / RETRIEVAL / SUB_AGENT
  name            string
  start_time      timestamp
  duration_ms     int
  attributes      json        // 小字段：model、token 数、tool_name 等
  payload_ref     string      // 大字段（prompt/completion）在对象存储的引用
  status          enum
}
```

设计要点：
- **大小字段分离**。prompt / completion 这种动辄几十 KB 的大字段，不进主库，只存一个对象存储的引用（`payload_ref`），主库只放能索引和聚合的小字段。这样主库轻量、查询快。
- **Trace 层预聚合**。`total_tokens`、`total_cost` 在 trace 结束时算好写进 trace 记录，避免每次展示都去扫全部 span。

#### 3.3.3 多维度聚合（Trace/Session/User）

数据是三层聚合的：

- **Trace 级**：单次请求的完整链路，用于单点排障。
- **Session 级**：一个会话包含多次 trace（多轮对话），聚合后能看"这个会话总共花了多少、走了多少轮"。
- **User 级**：一个用户的所有 session 聚合，用于成本归因和用量分析。

实现上，实时链路查询走 Trace/Span 主库（如 ClickHouse），User/Team 级的成本报表走离线数仓 T+1 计算。

#### 3.3.4 Tracing 过期策略与存储优化

trace 数据量巨大，必须有生命周期管理：

- **分级 TTL**：错误 trace 保留 30 天（排障要用），成功 trace 保留 7 天，大字段 payload 保留 3 天（最占空间）。
- **冷热分离**：近 24 小时的热数据放 ClickHouse SSD，历史数据压缩后转对象存储归档。
- **采样**：成功 trace 按比例采样存储，错误 trace 全存（尾部采样）。

---

## 四、Agent调试方法论

### 4.1 常见 Agent 故障模式

#### 4.1.1 工具调用循环（死循环）

最经典也最烧钱的故障。Agent 反复调用同一个工具，或者在几个工具间来回横跳，永远不收敛到"任务完成"。典型原因：
- 工具返回的结果模型看不懂，于是不断重试。
- 缺少"任务已完成"的明确终止条件。
- 两个工具互相依赖对方的输出，形成环。

防御手段：设 `max_iterations` 硬上限、检测连续相同的 tool call（相同 tool + 相同参数出现 N 次就熔断）、设单次请求成本上限。

#### 4.1.2 上下文窗口溢出

随着对话/工具结果不断堆积，context 越来越长，最终超过模型窗口上限，报错或者被截断导致模型"失忆"。表现为 Agent 突然忘了前面说过的话、行为异常。可观测性上要监控 `context_tokens` 这个 Gauge，接近上限时告警并触发压缩（summary）。

#### 4.1.3 幻觉导致的错误决策

模型编造了不存在的工具、编造了工具参数、或基于错误的"事实"做决策。这类错误**没有异常栈**，程序不会报错，但结果是错的——这是 Agent 调试最难的一类。只能靠事后评测（把执行结果和 ground truth 对比）和结构化的 trace 回看来发现。

#### 4.1.4 工具参数错误

模型生成的参数不符合 schema（类型错、缺必填、枚举值非法）。好的做法是在工具执行前做 schema 校验，校验失败把错误信息回喂给模型让它自己改（self-correction），并把这类事件计入 `tool_arg_error` 指标。

#### 4.1.5 延迟突增

端到端延迟突然飙升。可能是某个工具变慢、LLM 供应商抖动、或者 Agent 决策循环变多。靠 trace 的火焰图能快速定位是哪个 span 拖长了。

### 4.2 调试工具链

#### 4.2.1 日志分析：从海量日志中定位问题

结构化日志 + OLAP 查询是基本功。比如排查"为什么这个用户的请求特别慢"：

```sql
SELECT span_type, tool_name, avg(duration_ms), count(*)
FROM spans
WHERE trace_id = 'abc123'
GROUP BY span_type, tool_name
ORDER BY avg(duration_ms) DESC;
```

#### 4.2.2 Trace 回放：重现执行路径

因为 Agent 非确定性无法靠重跑复现，所以要把线上那次的完整快照（prompt、模型返回、工具入出参）存下来，支持在调试界面里一步步"回放"。高级一点的还能"从某一步 fork"——改一下 prompt 或参数，从中间某个 span 重新往下跑，看结果会不会变好。

#### 4.2.3 A/B 对比：对比成功和失败 case 的执行差异

把一个成功的 trace 和一个失败的 trace 并排放，diff 它们的执行路径：在哪一步开始分叉？失败的那次在第几轮做了什么不同的决策？这是定位"为什么有时候对有时候错"的利器。

#### 4.2.4 Token 消耗归因分析

出现成本异常时，按 span 拆解 token：是哪一步的 input 特别大（可能是 context 塞太多）？还是哪一步 output 特别长（模型话痨）？定位后针对性优化。

### 4.3 在线 A/B 测试框架

#### 4.3.1 Agent 的 A/B 测试特殊性

传统 A/B 测两个版本的按钮颜色，指标单一（点击率）。Agent 的 A/B 测试要复杂得多：
- **多目标**：不仅看成功率，还要同时看成本、延迟、用户满意度，这几个指标往往互相冲突（更强的模型效果好但更贵更慢）。
- **评估难**："成功"本身不好定义，很多任务没有明确的对错，要靠人工标注或 LLM-as-a-judge。
- **非确定性**：同一个 case 跑多次结果可能不同，要多次采样求平均。

#### 4.3.2 流量分割策略

- 按 `user_id` 哈希分桶，保证同一用户始终落在同一组（体验一致）。
- 灰度放量：先 1% → 5% → 20% → 50%，每一步观察指标。
- 可以按 Agent、按模型、按 prompt 版本分别做实验。

#### 4.3.3 指标设计

| 指标 | 方向 | 说明 |
|------|------|------|
| 任务成功率 | 越高越好 | 核心质量指标 |
| 平均成本 | 越低越好 | 单次请求 token 成本 |
| P90 延迟 | 越低越好 | 用户体验 |
| 用户满意度 | 越高越好 | 点赞率 / 追问率 / 人工评分 |
| 平均迭代轮数 | 适中 | 太多可能是绕路 |

#### 4.3.4 统计显著性判断

不能看到 A 组成功率 82%、B 组 80% 就下结论。要做假设检验（比率类指标用卡方检验 / z-test），确认差异在统计上显著（通常 p < 0.05），并且样本量足够。因为 Agent 结果方差大，往往需要比传统 A/B 更大的样本量。

---

## 五、延迟分析与优化

### 5.1 Agent 延迟分布分析

Agent 的端到端延迟由几部分组成，要拆开分析：

#### 5.1.1 LLM 推理延迟（TTFT、TPOT）

- **TTFT（Time To First Token）**：从请求发出到收到第一个 token 的时间。主要受 prompt 长度（prefill 阶段）影响，prompt 越长 TTFT 越大。
- **TPOT（Time Per Output Token）**：生成每个后续 token 的平均时间（decode 阶段）。
- 总生成时间 ≈ TTFT + TPOT × 输出 token 数。所以**输出越长，延迟越高**，这也是为什么要控制 Agent 别话痨。

#### 5.1.2 工具调用延迟

工具本身的执行时间。查数据库、调外部 API、跑计算，各不相同。这部分往往是延迟大头，尤其是串行调用多个慢工具时。

#### 5.1.3 上下文组装延迟

每一轮开始前，要把历史消息、检索到的知识、工具结果拼成新的 prompt。如果涉及 RAG 检索（向量库查询 + rerank），这部分也有可观的延迟。

#### 5.1.4 端到端延迟分解

```
E2E 延迟 = Σ(每轮迭代)
         = Σ(上下文组装 + LLM 推理 + 工具执行)
```

通过 trace 火焰图能直观看到各部分占比，找到瓶颈。经验上：多轮 Agent 的延迟大头通常是"轮数 × 每轮 LLM 延迟"，减少轮数比优化单轮更有效。

### 5.2 延迟优化策略

#### 5.2.1 并行工具调用

如果一轮里模型要调多个互相独立的工具（比如同时查天气和查股价），不要串行，用 `asyncio.gather` 之类并行执行，延迟从"求和"变成"取最大"。现代模型 API 都支持一次返回多个 tool_calls，正是为并行设计的。

#### 5.2.2 Speculative Decoding 对 Agent 延迟的影响

投机解码用一个小模型先"猜"几个 token，大模型一次性验证，能显著降低 TPOT（decode 阶段延迟）。对输出较长的 Agent（比如生成长报告、写代码）收益明显。这是推理层的优化，Agent 层无感知但直接受益。

#### 5.2.3 Prompt Caching 降低首 Token 延迟

Agent 每轮的 prompt 前缀（system prompt、工具定义、历史对话）高度重复。Prompt Caching 把这些前缀的 KV cache 缓存起来，下一轮命中缓存就跳过 prefill，**TTFT 大幅下降，同时命中部分的 token 成本降到 1/10**。这是 Agent 场景性价比最高的优化，因为 Agent 天然多轮、前缀重复度极高。

#### 5.2.4 流式输出与用户感知优化

即使总时间没变，用流式输出（SSE / WebSocket）让 token 逐个吐出来，用户"感知延迟"会好很多——看到文字在动就不焦虑。对 Agent 还可以流式展示"正在调用 XX 工具"这样的中间状态，进一步改善体感。

---

## 六、Token成本监控与归因

### 6.1 Token 成本模型

#### 6.1.1 输入 Token vs 输出 Token 成本差异

几乎所有厂商，**输出 token 都比输入 token 贵，通常 3~5 倍**。原因是输出是自回归逐个生成的，计算成本高。所以成本优化的一个铁律：**能省输出就省输出**（别让模型输出冗长内容、别重复复述输入）。

#### 6.1.2 Cache Creation vs Cache Read 成本

Prompt Caching 有两种 token：
- **Cache Creation（写缓存）**：第一次把前缀写进缓存，通常比普通 input **贵一点**（约 1.25 倍）。
- **Cache Read（读缓存）**：后续命中缓存的 token，通常只要普通 input 的 **1/10**。

所以做成本核算时，这四类 token（input / output / cache_creation / cache_read）单价都不同，要分别乘不同价格，不能笼统算。

#### 6.1.3 多模型路由下的成本计算

成熟平台会做"模型路由"：简单任务用便宜小模型，复杂任务用贵的大模型。成本计算要**按每次调用实际用的模型的单价**来算，而不是全局一个价。UsageTracker 里必须记 `model` 字段。

### 6.2 成本归因实践

#### 6.2.1 按组织/团队/Agent/用户维度归因

每一笔 token 消耗都要带上完整的归因标签链：`org → team → agent → user → session`。这样才能出各个维度的成本报表，回答"哪个团队最烧钱、哪个 Agent 性价比最低"。

#### 6.2.2 成本异常检测与告警

- 对每个维度设基线，用统计方法（如 3-sigma、同比环比）检测突增。
- 单次请求成本超阈值立即告警（可能是死循环）。
- 单用户/单团队日成本超预算告警。

#### 6.2.3 预算管理与超限处理

给团队/用户设配额（quota）。超限的处理策略：软限制（告警但放行）、硬限制（拒绝服务）、降级（切到便宜模型）。要在网关层做拦截，不能等钱花完了才发现。

#### 6.2.4 代码示例：Token 用量追踪实现

```python
class CostAttributor:
    def __init__(self, pricing_table, sink):
        self.pricing = pricing_table      # {model: Pricing}
        self.sink = sink                  # 写入 OLAP / 时序库

    def record(self, *, model, usage, dims: dict):
        p = self.pricing[model]
        cost = (
            usage.input_tokens * p.input
            + usage.output_tokens * p.output
            + usage.cache_creation_tokens * p.cache_write
            + usage.cache_read_tokens * p.cache_read
        ) / 1_000_000

        event = {
            **dims,                       # org_id / team_id / agent / user_id / session_id
            "model": model,
            "input_tokens": usage.input_tokens,
            "output_tokens": usage.output_tokens,
            "cache_read_tokens": usage.cache_read_tokens,
            "cost": cost,
            "ts": time.time(),
        }
        self.sink.write(event)            # 落库供多维聚合
        # 实时预算检查
        if self.budget_exceeded(dims["team_id"]):
            raise BudgetExceededError(dims["team_id"])
        return cost
```

---

## 七、企业级可观测性最佳实践

### 7.1 某互联网公司 Agent 平台的三层可观测性设计（脱敏）

某互联网公司的 Agent 平台把可观测性分成三层，职责清晰：

```
┌─────────────────────────────────────────────────┐
│  第三层：分析层（离线，T+1）                        │
│  数仓 + OLAP，出成本报表、质量评测、A/B 分析          │
│  维度：org / team / agent / user，高基数、大范围      │
├─────────────────────────────────────────────────┤
│  第二层：监控层（准实时，秒~分钟级）                  │
│  时序库(Prometheus) + 告警，看指标趋势、异常检测       │
│  维度：agent / model / 低基数，全局聚合              │
├─────────────────────────────────────────────────┤
│  第一层：追踪层（实时）                              │
│  Trace/Span 全链路，单点排障、Trace 回放             │
│  ClickHouse 存 span，对象存储放大字段 payload        │
└─────────────────────────────────────────────────┘
            ↑ 统一由 Telemetry Event Bus 分发
         Agent 只发结构化事件，三层各取所需
```

核心思想：**一次埋点，三层消费**。Agent 内部只通过事件总线发结构化遥测事件，追踪层、监控层、分析层各自订阅所需事件，互不影响。

### 7.2 生产级监控告警体系

- **黄金指标告警**：错误率、P99 延迟、成本突增。
- **业务指标告警**：任务成功率下跌、工具失败率上升、cache 命中率骤降。
- **告警分级**：P0（服务不可用/成本失控）电话，P1（质量下降）IM，P2（趋势异常）日报。
- **告警要能下钻**：从告警一键跳到相关 trace，缩短 MTTR（平均修复时间）。

### 7.3 可观测性数据的隐私与安全

- **PII 脱敏**：prompt / completion 入库前扫描并脱敏手机号、身份证、银行卡等。
- **字段级加密**：敏感 payload 加密存储，按权限解密查看。
- **权限隔离**：trace 数据按 org/team 做行级权限，A 团队看不到 B 团队的会话内容。
- **审计不可篡改**：安全审计日志 WORM 存储。
- **数据不出内网**：这也是很多大厂自建而非用 SaaS 的根本原因。

### 7.4 给 Java 工程师的可观测性迁移指南

做惯了 Java 微服务可观测性的同学，迁移到 Agent 可观测性时的对照：

| Java 微服务经验 | Agent 场景的对应 / 变化 |
|----------------|----------------------|
| SkyWalking / Zipkin 做链路 | 概念一致，但 Span 类型换成 LLM/Tool，属性重点是 token |
| Micrometer + Prometheus | 沿用，但要新增 token / cost 类指标，注意 Gauge vs Counter |
| Logback 结构化日志 | 沿用结构化思路，但日志量大得多，要做尾部采样 |
| 关注 QPS / RT / 错误率 | 新增关注 Token 成本、工具成功率、决策质量 |
| 异常栈定位问题 | 很多错误没有异常栈（幻觉），要靠 trace 回看 + 评测 |
| 靠重跑复现 bug | 非确定性，无法重跑复现，必须存完整快照 |
| 成本是运维的事 | 成本是一等公民，要实时归因到用户/会话 |

一句话：**追踪和指标的技术栈基本能复用（OpenTelemetry 是通用的），但心智模型要升级——从"系统是否健康"升级到"Agent 是否在正确地、经济地做事"。**

---

## 八、面试高频问题与参考答案

**Q1：Agent 可观测性和传统微服务可观测性的本质区别是什么？**

三点核心区别：① 执行路径非确定，LLM 动态决策，相同输入可能走不同路径，导致无法靠重跑复现问题；② 粒度不同，一次请求内含多轮 LLM 调用和数十次工具执行，必须细粒度树形追踪；③ 成本是一等公民指标，Token 按量计费波动极大，成本监控优先级甚至高于延迟。技术栈（OTel）可复用，但心智模型要从"系统是否健康"升级到"Agent 是否在正确且经济地做事"。

**Q2：在 Agent 场景下，如何定义 Trace 和 Span？有哪些 Span 类型？**

Trace 是一次完整的用户请求（从提问到最终答案），有全局唯一 trace_id；Span 是链路中的一个操作单元，通过 parent_span_id 形成树。Agent 特有的 Span 类型：LLM_CALL（记录 model、input/output tokens、TTFT、prompt/completion）、TOOL_EXECUTION（tool_name、arguments、result、is_error）、AGENT_DECISION（迭代轮次、选择的动作）、RETRIEVAL（RAG 检索）、SUB_AGENT（子 Agent 委托）。OTel 的 GenAI Semantic Conventions 规定了标准属性名如 gen_ai.usage.input_tokens。

**Q3：Gauge 和 Counter 在 Agent 指标里分别怎么用？举例说明。**

Counter 单调递增表示历史累计，比如"本会话累计消耗 12 万 input tokens"，适合出账单，Prometheus 里配 rate() 看速率；Gauge 是当前快照可增可减，比如"本轮消耗 3000 tokens"、"当前 context 占用 8 万 tokens"，适合看瞬时状态和 UI 展示剩余空间。用错语义数据就全错——给用户展示上下文剩余空间必须用 Gauge，出账单必须用 Counter。

**Q4：Agent 常见的故障模式有哪些？怎么防御工具调用死循环？**

常见故障：工具调用死循环、上下文窗口溢出、幻觉导致错误决策（无异常栈最难查）、工具参数不符合 schema、延迟突增。防御死循环：设 max_iterations 硬上限、检测连续相同的 tool call（相同工具+相同参数出现 N 次熔断）、设单次请求成本上限、给模型明确的终止条件。

**Q5：为什么说成本是 Agent 可观测性的第一优先级？如何做成本归因？**

因为 LLM 按 token 计费，单次复杂任务可消耗几十万 token，不同任务成本差百倍，一个死循环 Agent 几分钟能烧掉几百块。成本归因做法：每笔 token 消耗打上完整标签链 org→team→agent→user→session，四类 token（input/output/cache_creation/cache_read）分别按不同单价计算，粗粒度维度进时序库做实时监控，高基数维度（user/session）进 OLAP 数仓做离线归因。

**Q6：Prompt Caching 为什么对 Agent 延迟和成本收益特别大？**

因为 Agent 天然多轮，每轮 prompt 前缀（system prompt、工具定义、历史对话）高度重复。Caching 把前缀 KV cache 缓存，命中后跳过 prefill 阶段，TTFT 大幅下降；同时命中部分的 token 成本降到普通 input 的 1/10。注意 cache creation 略贵（约 1.25 倍），cache read 才便宜，要监控 cache hit rate 这个指标。

**Q7：Agent 的 A/B 测试和传统 A/B 测试有什么不同？**

① 多目标：要同时权衡成功率、成本、延迟、满意度，这些指标常互相冲突；② 评估难："成功"难定义，很多任务无明确对错，要靠人工标注或 LLM-as-a-judge；③ 非确定性：同一 case 多次结果不同，要多次采样求平均，方差大，需要比传统更大的样本量才能达到统计显著（p<0.05）。流量按 user_id 哈希分桶保证体验一致，灰度放量。

**Q8：一个 Agent 请求延迟很高，你怎么排查？**

先看 trace 火焰图定位瓶颈 span。延迟拆成：上下文组装（含 RAG 检索）+ LLM 推理（TTFT + TPOT×输出token）+ 工具执行，逐轮累加。常见大头是"轮数 × 每轮 LLM 延迟"，减少轮数比优化单轮更有效。优化手段：并行独立工具调用（gather）、prompt caching 降 TTFT、控制输出长度降 TPOT 总量、投机解码、流式输出改善感知延迟。

**Q9：为什么 Agent 的可观测性要用事件总线（Event Bus）设计？**

因为一份遥测数据要对接多个下游（存日志、发指标、更新 UI、审计），直接耦合会让主逻辑越来越臃肿。事件总线让 Agent 主逻辑只负责"发结构化事件"，各下游 subscribe 自己关心的事件，新增消费者零侵入。同时遥测处理必须做异常隔离——遥测挂了绝不能拖垮 Agent 主流程。这也是"一次埋点、三层消费"（追踪/监控/分析）架构的基础。

**Q10：Agent 是非确定性的，无法靠重跑复现 bug，怎么调试？**

核心是"存快照 + 可回放"：把线上那次执行的完整快照（prompt、模型原始返回、工具入出参、模型/参数）持久化，支持在调试界面一步步回放；高级能力是"从中间某步 fork 重跑"，改 prompt 或参数看结果变化。此外用 A/B 对比 diff 成功 trace 和失败 trace 的执行分叉点，用离线评测（结果对比 ground truth 或 LLM-as-judge）发现无异常栈的幻觉类错误。错误 trace 要 100% 全采（尾部采样）保证不漏。

---

## 九、总结

### 9.1 Agent 可观测性的核心原则

1. **细粒度树形追踪**：一次请求 = 一个 Trace，内部每次 LLM 调用/工具执行都是 Span，形成可视化的执行树。
2. **成本是一等公民**：Token 经济账本贯穿始终，多维度实时归因。
3. **区分 Gauge 与 Counter**：快照用 Gauge，累计用 Counter，别用错。
4. **一次埋点、多层消费**：事件总线解耦，追踪/监控/分析三层各取所需。
5. **为非确定性而设计**：存完整快照支持回放，错误全采，靠评测发现无异常栈的错误。
6. **安全与隐私内建**：PII 脱敏、审计不可篡改、数据权限隔离。

### 9.2 未来趋势

- **标准化**：OpenTelemetry GenAI Semantic Conventions 逐步成熟，跨平台互通。
- **评测左移**：可观测性和自动评测（eval）融合，线上 trace 直接喂给评测流水线持续监控质量。
- **Agent 自省**：Agent 用可观测性数据自我诊断、自我优化（发现自己死循环、成本超标并调整策略）。
- **多模态追踪**：随着 Agent 处理图像/音频/视频，追踪要扩展到多模态 token 和延迟。
- **成本智能路由**：结合实时成本可观测性做动态模型选择，在质量和成本间自动最优权衡。

可观测性不是 Agent 系统的附属品，而是让 Agent 从"能跑的 demo"走向"可信赖的生产系统"的地基。没有可观测性，你既不知道 Agent 有没有做对事，也不知道它花了多少钱——而这两点，恰恰是 Agent 工程化最关键的两个问题。

---

## 附录：知识融合——构建企业级Agent全链路可观测性平台

> 本附录将前文所有可观测性知识融合为一个完整的企业级 Agent 可观测性平台方案，从上到下、不跳步地描述系统目标、架构设计、各层实现、数据流转、调试工作流、部署实践以及演进路线。所有案例已脱敏，以"某互联网公司"、"某Agent平台"等通用描述代替真实产品名称。

---

### 一、系统目标与设计原则

#### 1.1 核心目标：让 Agent 系统从"黑盒"变为"白盒"

Agent 系统最大的工程挑战在于它的**非确定性**和**多步骤嵌套**。传统服务出了问题，你看一眼异常栈就知道挂在哪了；Agent 出了问题，你甚至不知道它"错了"——因为它返回了一个看起来合理、但实际上是幻觉的答案，或者它成功完成了任务，但中间绕了 15 轮、烧了 50 万 Token。

可观测性平台的核心目标是：

1. **看得见**：每一次 Agent 执行的完整链路——每轮决策、每次 LLM 调用、每个工具执行——全部结构化记录，可以像回放录像一样逐步查看。
2. **算得清**：每一次执行花了多少 Token、多少钱、多少时间，能按用户/Agent/模型/工具等多维度拆解归因。
3. **管得住**：延迟飙升、成本突增、工具异常、死循环等问题能自动检测并告警，而不是等用户投诉才发现。
4. **调得动**：发现问题后能快速回放现场、对比成功/失败 case、定位根因，而不是猜来猜去。

#### 1.2 四大设计原则

**原则一：全链路追踪（End-to-End Tracing）**

一次用户请求从进入 Agent 到最终返回，中间经历的每一步都必须被纳入同一条 Trace。这条 Trace 是一棵树：根节点是用户请求，子节点是每一轮 Agent 循环，叶节点是具体的 LLM 调用和工具执行。没有全链路追踪，你只能看到一堆散落的日志，根本拼不出完整的故事。

**原则二：成本优先（Cost-First）**

在传统 APM 里，成本是个"nice to have"的维度——服务器按月付费，单次请求的边际成本约等于零。但 Agent 系统的核心成本是 Token 消耗，按量计费，单次请求的成本差异可达百倍。因此可观测性平台必须把 Token 经济作为**一等公民**，像对待延迟一样对待成本。

**原则三：多维聚合（Multi-Dimensional Aggregation）**

同一份遥测数据，不同角色需要不同的视角。平台开发者看整体系统健康度，Agent 开发者看具体 Agent 的决策质量，业务方看成本和效果。因此数据必须支持按多维度（用户/Agent/模型/工具/时间段/租户）灵活聚合。

**原则四：实时告警（Real-Time Alerting）**

Agent 系统的成本是实时产生的。一个写坏了的 prompt 如果导致 Agent 进入死循环，每分钟可能烧掉几百块。所以告警必须是实时的，不能等 T+1 跑批才发现。

#### 1.3 与传统 APM 系统的核心区别

| 维度 | 传统 APM（如 Prometheus + Jaeger） | Agent 可观测性平台 |
|------|-----------------------------------|-------------------|
| Trace 结构 | 固定深度，通常 3-5 层 | 动态深度，可能 1-50 层，由 LLM 决策决定 |
| 核心指标 | QPS、P99 延迟、错误率、CPU/内存 | Token 消耗、成本金额、决策正确率、工具成功率 |
| 错误定义 | HTTP 5xx、异常栈 | 幻觉、死循环、参数错误（可能无异常栈） |
| 成本模型 | 固定（机器按月付费） | 变动（Token 按量计费，单次差异百倍） |
| 可重现性 | 确定性，相同输入相同输出 | 非确定性，相同输入可能走完全不同路径 |
| 调试方式 | 看日志、看异常栈 | 回放 Trace、A/B 对比、评测打分 |

传统 APM 是 Agent 可观测性平台的基础设施底座，但远远不够。Agent 可观测性需要在 APM 之上叠加**语义层**——理解 Agent 的"意图"而不仅仅是"行为"。

---

### 二、整体架构总览

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         可视化展示层 (Visualization)                         │
│  ┌──────────┐ ┌──────────────┐ ┌──────────────┐ ┌───────────┐ ┌──────────┐ │
│  │ 执行链路  │ │ Token 经济   │ │  延迟分布    │ │ Agent 对比 │ │ 异常 Trace│ │
│  │ 火焰图   │ │   看板       │ │    看板      │ │   看板     │ │   高亮   │ │
│  └──────────┘ └──────────────┘ └──────────────┘ └───────────┘ └──────────┘ │
├──────────────────────────────────────────────────────────────────────────────┤
│                      告警与自动化层 (Alerting & Automation)                   │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐ │
│  │ 告警规则引擎  │ │ 告警分级路由  │ │  自动诊断引擎    │ │  自愈执行器    │ │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └────────────────┘ │
├──────────────────────────────────────────────────────────────────────────────┤
│                         分析引擎层 (Analytics Engine)                        │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐ │
│  │  延迟分析     │ │ 成本归因     │ │   异常检测       │ │  Trace 聚合   │ │
│  │   引擎       │ │   引擎       │ │    引擎          │ │    引擎       │ │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └────────────────┘ │
├──────────────────────────────────────────────────────────────────────────────┤
│                         数据存储层 (Storage)                                 │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐ │
│  │ Trace 存储   │ │ Metrics 存储  │ │   Log 存储       │ │  快照存储      │ │
│  │ (Span 树)    │ │ (时序数据库)  │ │  (结构化索引)    │ │ (回放用)      │ │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └────────────────┘ │
├──────────────────────────────────────────────────────────────────────────────┤
│                       数据采集层 (Data Collection)                           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐ │
│  │ Agent SDK    │ │ 三信号采集    │ │   事件总线       │ │  采样策略      │ │
│  │   埋点       │ │ Trace/Metric  │ │  (Event Bus)     │ │   控制器      │ │
│  │              │ │   /Log       │ │                  │ │               │ │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └────────────────┘ │
├──────────────────────────────────────────────────────────────────────────────┤
│                        Agent 运行时 (Agent Runtime)                          │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐ ┌────────────────┐ │
│  │  LLM 调用    │ │  工具执行     │ │   记忆系统       │ │  编排引擎      │ │
│  └──────────────┘ └──────────────┘ └──────────────────┘ └────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
```

各层职责一句话概括：

- **数据采集层**：在 Agent 运行时的关键路径上植入探针，将执行细节转化为结构化的遥测事件。
- **数据存储层**：将采集到的 Traces、Metrics、Logs 持久化存储，支持高效查询和生命周期管理。
- **分析引擎层**：对原始遥测数据进行实时和离线分析，产出延迟瓶颈、成本归因、异常检测等高层结论。
- **可视化展示层**：将分析结果以直观的图表和看板呈现给不同角色的用户。
- **告警与自动化层**：基于分析结果自动触发告警，并在可能的情况下执行自动诊断和修复。

---

### 三、各层详细设计

#### 3.1 数据采集层

数据采集层是整个可观测性平台的根基。它的设计原则是：**对 Agent 主逻辑零侵入（或极低侵入），采集失败绝不影响 Agent 正常执行。**

##### 3.1.1 Agent SDK 埋点：如何在 Agent 代码中植入 Tracing

最理想的方式是通过装饰器/中间件模式自动埋点，让 Agent 开发者不需要手动在每个函数里写 tracing 代码。核心思路：

- 在 Agent 编排引擎的关键接缝（LLM 调用前后、工具执行前后、每轮循环开始结束）自动创建 Span。
- 每个 Span 携带结构化属性：`span_type`（llm / tool / agent_step）、`model_name`、`token_usage`、`tool_name`、`tool_args`、`tool_result`、`error_info` 等。
- 通过 Context 机制自动维护 Span 的父子关系，形成树形结构。

##### 3.1.2 三信号采集：Traces / Metrics / Logs 的统一采集

遵循 OpenTelemetry 的三信号模型，但针对 Agent 场景做了语义扩展：

- **Traces**：Agent 执行的完整链路树，核心数据。每个 Span 记录一次 LLM 调用或工具执行的全部上下文。
- **Metrics**：从 Traces 中衍生的聚合指标，如 Token 消耗速率、工具调用成功率、平均每轮延迟等。使用 Counter（累计型，如总 Token 消耗）和 Gauge（快照型，如当前活跃 Agent 数）。
- **Logs**：Agent 运行时的非结构化或半结构化日志，作为 Traces 的补充，用于记录 Span 内部的详细过程。

三种信号通过 Trace ID 和 Span ID 关联，实现从一条告警指标追溯到具体 Trace、再追溯到详细日志的完整链路。

##### 3.1.3 事件总线设计：结构化遥测事件的发布订阅

Agent 主逻辑只负责往事件总线上发射结构化事件，不直接调用任何存储或分析接口。下游的多个消费者各自订阅感兴趣的事件：

- **Trace 消费者**：将事件组装成 Span 树，写入 Trace 存储。
- **Metrics 消费者**：从事件中提取指标数据点，写入时序数据库。
- **Log 消费者**：将事件的详细字段写入日志索引。
- **审计消费者**：将涉及敏感操作的事件写入不可篡改的审计日志。
- **实时看板消费者**：将关键指标推送到 WebSocket，驱动前端看板实时刷新。

事件总线的关键设计决策：
- **异步非阻塞**：事件发射必须是异步的，不能让遥测拖慢 Agent 主流程。
- **异常隔离**：任何一个消费者的故障都不能影响其他消费者和 Agent 主流程。
- **背压控制**：当下游处理不过来时，可以丢弃低优先级事件，但不能阻塞生产者。

##### 3.1.4 代码示例：采集器的 Python 实现

```python
import time
import uuid
import asyncio
from typing import Any, Dict, List, Optional, Callable
from dataclasses import dataclass, field
from enum import Enum
from contextlib import asynccontextmanager


class SpanKind(Enum):
    AGENT_REQUEST = "agent_request"     # 顶层用户请求
    AGENT_STEP = "agent_step"           # Agent 一轮循环
    LLM_CALL = "llm_call"              # 一次 LLM 调用
    TOOL_EXECUTION = "tool_execution"   # 一次工具执行
    RETRIEVAL = "retrieval"             # RAG 检索


@dataclass
class TokenUsage:
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    estimated_cost_usd: float = 0.0


@dataclass
class Span:
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    kind: SpanKind
    name: str
    start_time: float
    end_time: Optional[float] = None
    status: str = "ok"
    attributes: Dict[str, Any] = field(default_factory=dict)
    token_usage: Optional[TokenUsage] = None
    error: Optional[str] = None
    events: List[Dict[str, Any]] = field(default_factory=list)


class TelemetryEventBus:
    """遥测事件总线：解耦采集与消费，异常隔离。"""

    def __init__(self):
        self._subscribers: Dict[str, List[Callable]] = {}
        self._queue: asyncio.Queue = asyncio.Queue(maxsize=10000)
        self._running = False

    def subscribe(self, event_type: str, handler: Callable):
        if event_type not in self._subscribers:
            self._subscribers[event_type] = []
        self._subscribers[event_type].append(handler)

    async def publish(self, event_type: str, payload: Dict[str, Any]):
        """非阻塞发布——队列满则丢弃并记录，绝不阻塞生产者。"""
        try:
            self._queue.put_nowait({
                "type": event_type,
                "payload": payload,
                "timestamp": time.time()
            })
        except asyncio.QueueFull:
            # 背压场景：丢弃事件但不影响主流程
            pass

    async def _consume_loop(self):
        """后台消费循环，逐个分发事件到订阅者。"""
        self._running = True
        while self._running:
            try:
                event = await asyncio.wait_for(
                    self._queue.get(), timeout=1.0
                )
                handlers = self._subscribers.get(event["type"], [])
                for handler in handlers:
                    try:
                        await handler(event["payload"])
                    except Exception:
                        # 异常隔离：单个消费者异常不影响其他消费者
                        pass
            except asyncio.TimeoutError:
                continue

    async def start(self):
        asyncio.create_task(self._consume_loop())

    async def stop(self):
        self._running = False


class AgentTracer:
    """Agent 追踪器：自动管理 Span 生命周期，维护父子关系。"""

    def __init__(self, event_bus: TelemetryEventBus):
        self._event_bus = event_bus
        self._active_spans: Dict[str, Span] = {}
        self._span_stack: List[str] = []  # 当前上下文的 Span 栈

    def _current_span_id(self) -> Optional[str]:
        return self._span_stack[-1] if self._span_stack else None

    @asynccontextmanager
    async def start_span(
        self,
        name: str,
        kind: SpanKind,
        trace_id: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None
    ):
        """上下文管理器：自动开始和结束 Span，自动维护父子关系。"""
        span_id = str(uuid.uuid4())[:16]
        parent_span_id = self._current_span_id()

        if trace_id is None:
            # 如果没有指定 trace_id，从父 Span 继承；没有父则新建
            if parent_span_id and parent_span_id in self._active_spans:
                trace_id = self._active_spans[parent_span_id].trace_id
            else:
                trace_id = str(uuid.uuid4())[:32]

        span = Span(
            trace_id=trace_id,
            span_id=span_id,
            parent_span_id=parent_span_id,
            kind=kind,
            name=name,
            start_time=time.time(),
            attributes=attributes or {}
        )

        self._active_spans[span_id] = span
        self._span_stack.append(span_id)

        # 发射 Span 开始事件
        await self._event_bus.publish("span_start", {
            "trace_id": trace_id,
            "span_id": span_id,
            "parent_span_id": parent_span_id,
            "kind": kind.value,
            "name": name,
            "start_time": span.start_time,
            "attributes": span.attributes
        })

        try:
            yield span
            span.status = "ok"
        except Exception as e:
            span.status = "error"
            span.error = str(e)
            raise
        finally:
            span.end_time = time.time()
            self._span_stack.pop()

            # 发射 Span 结束事件
            await self._event_bus.publish("span_end", {
                "trace_id": span.trace_id,
                "span_id": span.span_id,
                "parent_span_id": span.parent_span_id,
                "kind": span.kind.value,
                "name": span.name,
                "start_time": span.start_time,
                "end_time": span.end_time,
                "duration_ms": (span.end_time - span.start_time) * 1000,
                "status": span.status,
                "error": span.error,
                "attributes": span.attributes,
                "token_usage": (
                    vars(span.token_usage) if span.token_usage else None
                ),
                "events": span.events
            })

            del self._active_spans[span_id]


# ---- 使用示例 ----

async def run_agent_with_tracing():
    """演示如何在 Agent 中使用追踪器。"""
    bus = TelemetryEventBus()
    tracer = AgentTracer(bus)

    # 注册一个简单的消费者：打印每个完成的 Span
    async def log_span(payload):
        print(
            f"[Span] {payload['kind']:20s} | "
            f"{payload['name']:30s} | "
            f"{payload['duration_ms']:8.1f}ms | "
            f"{payload['status']}"
        )
    bus.subscribe("span_end", log_span)
    await bus.start()

    # 模拟 Agent 执行
    async with tracer.start_span(
        "user_request", SpanKind.AGENT_REQUEST,
        attributes={"user_query": "帮我汇总本周销售数据"}
    ) as request_span:

        # 第一轮：Agent 决定查数据库
        async with tracer.start_span(
            "step_1", SpanKind.AGENT_STEP
        ) as step1:

            # LLM 决策
            async with tracer.start_span(
                "llm_decide_tool", SpanKind.LLM_CALL,
                attributes={"model": "gpt-4o"}
            ) as llm_span:
                llm_span.token_usage = TokenUsage(
                    prompt_tokens=1200,
                    completion_tokens=85,
                    total_tokens=1285,
                    estimated_cost_usd=0.02
                )
                await asyncio.sleep(0.5)  # 模拟 LLM 延迟

            # 工具执行
            async with tracer.start_span(
                "query_sales_db", SpanKind.TOOL_EXECUTION,
                attributes={
                    "tool_name": "query_db",
                    "tool_args": {"sql": "SELECT ..."}
                }
            ) as tool_span:
                await asyncio.sleep(0.3)  # 模拟工具执行
                tool_span.attributes["rows_returned"] = 156

        # 第二轮：生成报告
        async with tracer.start_span(
            "step_2", SpanKind.AGENT_STEP
        ) as step2:
            async with tracer.start_span(
                "llm_generate_report", SpanKind.LLM_CALL,
                attributes={"model": "gpt-4o"}
            ) as llm_span:
                llm_span.token_usage = TokenUsage(
                    prompt_tokens=3500,
                    completion_tokens=2000,
                    total_tokens=5500,
                    estimated_cost_usd=0.10
                )
                await asyncio.sleep(1.2)  # 模拟 LLM 延迟
```

上面这段代码展示了三个关键设计：
1. **Span 栈**：通过 `_span_stack` 自动维护父子关系，开发者不需要手动传递 parent_span_id。
2. **上下文管理器**：`async with tracer.start_span(...)` 自动处理 Span 的生命周期，包括异常捕获。
3. **事件总线解耦**：Tracer 只负责发射事件，不关心事件被谁消费、怎么存储。

---

#### 3.2 数据存储层

存储层要解决的核心问题是：Agent 遥测数据量大、结构多样、查询模式复杂。一次复杂的 Agent 请求可能产生上百个 Span，每个 Span 携带几 KB 到几十 KB 的上下文（包括完整的 prompt 和 LLM 输出）。按每日百万次请求计算，原始 Trace 数据量轻松过 TB。

##### 3.2.1 Trace 存储：Span 树形数据的存储方案

Trace 的查询模式主要有两种：
- **按 trace_id 查单条 Trace**：查看某次请求的完整执行链路。需要快速拉出一棵完整的 Span 树。
- **按条件搜索 Trace**：比如"过去 1 小时内所有失败的 Trace"、"Token 消耗超过 10000 的 Trace"。

推荐的存储方案：
- **主存储**：使用列式存储（如 ClickHouse），每个 Span 一行，trace_id 作为分区键。列式存储天然适合"从海量 Span 中按条件筛选 Trace"的查询模式。
- **详情存储**：对于 Span 中的大字段（完整 prompt、LLM 输出、工具入出参），存储在对象存储（如 S3 / MinIO）中，Span 记录只保留引用指针。这样列式存储的行宽可控，查询性能有保障。

##### 3.2.2 Metrics 存储：时序数据库选型

Agent 指标的核心特征是**维度多、粒度细**。典型的维度组合：`(agent_id, model_name, tool_name, user_id, tenant_id, time_bucket)`。

推荐方案：
- **实时指标**：使用 Prometheus 或 VictoriaMetrics，采集间隔 15 秒，保留 15 天。用于实时告警和近期趋势分析。
- **历史指标**：降采样后写入 ClickHouse 的聚合表，保留 1 年以上。用于成本趋势分析和容量规划。

关键指标列表：

| 指标名 | 类型 | 说明 |
|--------|------|------|
| `agent_llm_tokens_total` | Counter | LLM Token 累计消耗（按 prompt/completion 分） |
| `agent_llm_cost_usd_total` | Counter | LLM 调用累计金额 |
| `agent_llm_duration_seconds` | Histogram | LLM 调用延迟分布 |
| `agent_tool_calls_total` | Counter | 工具调用次数（按 tool_name、status 分） |
| `agent_tool_duration_seconds` | Histogram | 工具执行延迟分布 |
| `agent_steps_per_request` | Histogram | 每次请求的 Agent 循环轮数分布 |
| `agent_request_duration_seconds` | Histogram | 端到端请求延迟分布 |
| `agent_active_requests` | Gauge | 当前正在执行的 Agent 请求数 |

##### 3.2.3 Log 存储：结构化日志的索引与检索

日志作为 Trace 的补充，用于记录 Span 内部的详细过程信息。关键设计：

- **结构化**：所有日志必须是结构化 JSON，至少包含 `timestamp`、`level`、`trace_id`、`span_id`、`message`、`attributes` 字段。
- **关联**：通过 `trace_id` 和 `span_id` 与 Trace 关联，实现从 Trace 视图点击跳转到相关日志。
- **索引**：使用 Elasticsearch 或 Loki 作为日志存储，对 `trace_id`、`span_id`、`level`、`agent_id` 建索引，支持快速检索。
- **脱敏**：日志写入前经过 PII 脱敏管道，去除用户个人信息。

##### 3.2.4 数据生命周期管理：热 / 温 / 冷分层存储，过期策略

Agent 遥测数据的访问模式有明显的时间衰减：最近 1 小时的数据被频繁查看（排查当前问题），过去 7 天的偶尔被查看（分析趋势），更早的数据很少被访问（合规审计用途）。

分层策略：

| 层级 | 时间范围 | 存储介质 | 数据粒度 | 查询延迟 |
|------|---------|---------|---------|---------|
| 热层 | 0-24 小时 | SSD + 内存缓存 | 全量明细 | < 100ms |
| 温层 | 1-30 天 | HDD / 普通云盘 | 全量明细（大字段压缩） | < 1s |
| 冷层 | 30-365 天 | 对象存储 | 聚合摘要 + 采样明细 | < 10s |
| 归档 | > 365 天 | 低成本归档存储 | 仅审计必需字段 | 按需恢复 |

错误 Trace 和异常 Trace 的保留策略特殊处理：**错误 Trace 100% 全采全保留，保留时间至少 90 天**，因为错误 case 是最有调试价值的数据。

##### 3.2.5 代码示例：数据模型定义

```python
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from datetime import datetime
from enum import Enum


class TraceStatus(Enum):
    OK = "ok"
    ERROR = "error"
    TIMEOUT = "timeout"
    LOOP_DETECTED = "loop_detected"


@dataclass
class SpanRecord:
    """写入存储的 Span 记录——从原始 Span 事件转化而来。"""
    trace_id: str
    span_id: str
    parent_span_id: Optional[str]
    kind: str                       # agent_request / agent_step / llm_call / tool_execution
    name: str
    start_time: datetime
    end_time: datetime
    duration_ms: float
    status: str                     # ok / error
    error_message: Optional[str]

    # Agent 语义字段
    model_name: Optional[str]       # LLM 模型名
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    estimated_cost_usd: float = 0.0
    tool_name: Optional[str] = None
    tool_args_ref: Optional[str] = None    # 对象存储中的引用 key
    tool_result_ref: Optional[str] = None  # 对象存储中的引用 key
    prompt_ref: Optional[str] = None       # 对象存储中的引用 key
    completion_ref: Optional[str] = None   # 对象存储中的引用 key

    # 多维度标签
    agent_id: str = ""
    agent_version: str = ""
    user_id: str = ""
    tenant_id: str = ""
    environment: str = "production"


@dataclass
class TraceRecord:
    """Trace 级别的聚合记录——从所有 Span 聚合而来。"""
    trace_id: str
    start_time: datetime
    end_time: datetime
    total_duration_ms: float
    status: TraceStatus
    span_count: int
    llm_call_count: int
    tool_call_count: int
    agent_step_count: int

    # Token 经济
    total_prompt_tokens: int = 0
    total_completion_tokens: int = 0
    total_tokens: int = 0
    total_cost_usd: float = 0.0

    # 维度
    agent_id: str = ""
    user_id: str = ""
    tenant_id: str = ""

    # 根 Span 的用户输入摘要（脱敏后）
    user_query_summary: str = ""

    # 错误信息（如有）
    error_spans: List[str] = field(default_factory=list)


@dataclass
class MetricDataPoint:
    """时序指标数据点。"""
    metric_name: str
    value: float
    timestamp: datetime
    metric_type: str       # counter / gauge / histogram
    labels: Dict[str, str] = field(default_factory=dict)


@dataclass
class TraceSnapshot:
    """用于调试回放的完整 Trace 快照——包含全量上下文。"""
    trace_id: str
    captured_at: datetime
    spans: List[Dict[str, Any]]          # 完整 Span 树（含原始 prompt 和 completion）
    model_config: Dict[str, Any]         # 模型参数（temperature、top_p 等）
    tool_definitions: List[Dict[str, Any]]  # 工具定义
    system_prompt: str                    # 系统 prompt
    replay_compatible: bool = True        # 是否支持回放
```

---

#### 3.3 分析引擎层

分析引擎层是可观测性平台的"大脑"，负责从原始遥测数据中提炼出可操作的洞察（actionable insights）。没有分析引擎，你有的只是数据；有了分析引擎，你才有信息和知识。

##### 3.3.1 延迟分析引擎：P50 / P90 / P99 分位数计算，瓶颈定位

Agent 的端到端延迟通常由以下部分组成：

```
总延迟 = Σ (每轮: 上下文组装延迟 + LLM 推理延迟 + 工具执行延迟)
       = Σ (每轮: RAG检索时间 + (TTFT + TPOT × 输出token数) + 工具IO时间)
```

延迟分析引擎的核心能力：

1. **分位数计算**：对每个环节计算 P50、P90、P99 延迟，识别长尾问题。
2. **瓶颈定位**：将一个 Trace 的总延迟拆解为各环节占比，自动识别占比最大的瓶颈环节。
3. **趋势分析**：对比不同时间段的延迟分布变化，发现性能退化。
4. **轮数影响分析**：分析 Agent 循环轮数与总延迟的关系——在很多场景下，减少一轮比优化单轮延迟更有效。

##### 3.3.2 成本归因引擎：Token 消耗按多维度归因

成本归因是 Agent 可观测性中最独特的能力之一。传统服务的成本是按机器分摊的，维度单一；Agent 的 Token 成本可以按多个维度拆解：

- **按模型归因**：GPT-4o 花了多少、Claude 花了多少、用了哪些小模型。
- **按 Agent 归因**：哪个 Agent 最烧钱，是否可以优化。
- **按用户归因**：哪些用户的请求成本异常高。
- **按环节归因**：Token 花在了决策（短输出）还是内容生成（长输出）上。
- **按 prompt / completion 拆分**：输入 Token 多说明上下文太长，输出 Token 多说明生成内容太长。

##### 3.3.3 异常检测引擎：基于统计和 ML 的异常检测

Agent 的异常不仅仅是"报错"，还包括很多"看起来正常但实际有问题"的情况：

- **死循环检测**：Agent 循环轮数超过阈值（如 > 15 轮），但没有报错。
- **成本异常**：单次请求的 Token 消耗超过历史 P99 的 3 倍。
- **延迟异常**：单次请求延迟超过历史 P99 的 2 倍。
- **工具重复调用**：同一个工具被连续调用多次，入参相同（可能是 LLM 陷入重复）。
- **工具失败率飙升**：某个工具的失败率在 5 分钟窗口内从 1% 跳到 20%。

异常检测方法分两层：
- **静态规则**：简单阈值和比率，适合明确的异常定义（如死循环 > 15 轮）。
- **统计检测**：基于滑动窗口的均值 ± N 倍标准差（Z-Score），适合发现成本和延迟的突变。更高级的可以用 Isolation Forest 或 Prophet 时序模型做长周期异常检测。

##### 3.3.4 Trace 聚合引擎：跨 Trace 的模式挖掘

单条 Trace 告诉你"这次发生了什么"，Trace 聚合告诉你"总体上在发生什么"。核心能力：

- **执行路径聚类**：将相似的 Trace（相同的工具调用序列）归为一组，发现 Agent 的典型行为模式。
- **成功 vs 失败对比**：对比成功 Trace 和失败 Trace 的执行路径差异，找到失败的关键分叉点。
- **Token 分布分析**：统计 Token 消耗的分布，识别是否存在离群值。
- **工具依赖分析**：统计工具被调用的频率和组合模式，优化工具集配置。

##### 3.3.5 代码示例：成本归因算法

```python
from typing import Dict, List, Tuple
from dataclasses import dataclass
from collections import defaultdict


# 模型定价表（每 1K Token 的 USD 价格）
MODEL_PRICING = {
    "gpt-4o": {"prompt": 0.005, "completion": 0.015},
    "gpt-4o-mini": {"prompt": 0.00015, "completion": 0.0006},
    "claude-3-5-sonnet": {"prompt": 0.003, "completion": 0.015},
    "claude-3-5-haiku": {"prompt": 0.0008, "completion": 0.004},
}


@dataclass
class CostAttribution:
    """多维度成本归因结果。"""
    total_cost_usd: float
    by_model: Dict[str, float]        # 按模型归因
    by_agent: Dict[str, float]        # 按 Agent 归因
    by_step_type: Dict[str, float]    # 按环节归因（决策 / 检索 / 生成）
    by_token_type: Dict[str, float]   # 按 prompt / completion 归因
    by_user: Dict[str, float]         # 按用户归因


def calculate_span_cost(span: dict) -> float:
    """计算单个 LLM Span 的成本。"""
    model = span.get("model_name", "unknown")
    pricing = MODEL_PRICING.get(model)
    if not pricing:
        return 0.0

    prompt_cost = (span.get("prompt_tokens", 0) / 1000) * pricing["prompt"]
    completion_cost = (
        (span.get("completion_tokens", 0) / 1000) * pricing["completion"]
    )
    return prompt_cost + completion_cost


def attribute_costs(
    spans: List[dict],
    trace_metadata: dict
) -> CostAttribution:
    """对一组 Span 进行多维度成本归因。

    算法逻辑：
    1. 遍历所有 LLM 类型的 Span
    2. 计算每个 Span 的成本
    3. 按模型、Agent、环节类型、Token 类型、用户五个维度分别累加
    """
    by_model = defaultdict(float)
    by_agent = defaultdict(float)
    by_step_type = defaultdict(float)
    by_token_type = defaultdict(float)
    by_user = defaultdict(float)
    total = 0.0

    for span in spans:
        if span.get("kind") != "llm_call":
            continue

        cost = calculate_span_cost(span)
        total += cost

        # 维度 1：按模型
        model = span.get("model_name", "unknown")
        by_model[model] += cost

        # 维度 2：按 Agent
        agent_id = span.get("agent_id", trace_metadata.get("agent_id", "unknown"))
        by_agent[agent_id] += cost

        # 维度 3：按环节类型
        step_type = _classify_step_type(span)
        by_step_type[step_type] += cost

        # 维度 4：按 Token 类型
        pricing = MODEL_PRICING.get(model, {"prompt": 0, "completion": 0})
        prompt_cost = (span.get("prompt_tokens", 0) / 1000) * pricing["prompt"]
        completion_cost = cost - prompt_cost
        by_token_type["prompt"] += prompt_cost
        by_token_type["completion"] += completion_cost

        # 维度 5：按用户
        user_id = trace_metadata.get("user_id", "unknown")
        by_user[user_id] += cost

    return CostAttribution(
        total_cost_usd=total,
        by_model=dict(by_model),
        by_agent=dict(by_agent),
        by_step_type=dict(by_step_type),
        by_token_type=dict(by_token_type),
        by_user=dict(by_user)
    )


def _classify_step_type(span: dict) -> str:
    """根据 Span 的上下文推断它属于哪个环节。"""
    name = span.get("name", "").lower()
    if "decide" in name or "plan" in name or "route" in name:
        return "decision"      # 决策环节：选工具、做计划
    elif "retrieve" in name or "search" in name or "rag" in name:
        return "retrieval"     # 检索环节：RAG 检索增强
    elif "generate" in name or "write" in name or "summarize" in name:
        return "generation"    # 生成环节：内容生成
    else:
        return "other"


def generate_cost_report(attribution: CostAttribution) -> str:
    """生成可读的成本归因报告。"""
    lines = [
        f"=== 成本归因报告 ===",
        f"总成本: ${attribution.total_cost_usd:.4f}",
        "",
        "-- 按模型 --",
    ]
    for model, cost in sorted(
        attribution.by_model.items(), key=lambda x: -x[1]
    ):
        pct = (cost / attribution.total_cost_usd * 100) if attribution.total_cost_usd > 0 else 0
        lines.append(f"  {model}: ${cost:.4f} ({pct:.1f}%)")

    lines.append("\n-- 按环节 --")
    for step, cost in sorted(
        attribution.by_step_type.items(), key=lambda x: -x[1]
    ):
        pct = (cost / attribution.total_cost_usd * 100) if attribution.total_cost_usd > 0 else 0
        lines.append(f"  {step}: ${cost:.4f} ({pct:.1f}%)")

    lines.append("\n-- 按 Token 类型 --")
    for ttype, cost in sorted(
        attribution.by_token_type.items(), key=lambda x: -x[1]
    ):
        pct = (cost / attribution.total_cost_usd * 100) if attribution.total_cost_usd > 0 else 0
        lines.append(f"  {ttype}: ${cost:.4f} ({pct:.1f}%)")

    return "\n".join(lines)
```

---

#### 3.4 可视化展示层

可视化是让数据"说话"的最后一环。好的可视化设计能让工程师在 5 秒内判断"系统是否正常"，在 30 秒内定位到"哪里出了问题"。

##### 3.4.1 执行链路火焰图：Span 树的可视化

这是 Agent 可观测性最核心的可视化组件。火焰图将一棵 Span 树展示为嵌套的时间条：

```
 ┌─ user_request ──────────────────────────────────────────────────────────┐
 │ ┌─ step_1 ──────────────────────────┐ ┌─ step_2 ─────────────────────┐ │
 │ │ ┌ llm_decide ──┐ ┌ query_db ───┐ │ │ ┌ llm_generate_report ────┐ │ │
 │ │ │  500ms       │ │  300ms      │ │ │ │       1200ms             │ │ │
 │ │ │  1285 tok    │ │             │ │ │ │       5500 tok           │ │ │
 │ │ │  $0.02       │ │             │ │ │ │       $0.10              │ │ │
 │ │ └──────────────┘ └─────────────┘ │ │ └──────────────────────────┘ │ │
 │ └───────────────────────────────────┘ └──────────────────────────────┘ │
 └─────────────────────────────────────────────────────────────────────────┘
   0ms                     800ms                                    2000ms
```

每个时间条上标注：名称、耗时、Token 消耗、成本。异常 Span 用红色高亮。点击任意 Span 可以展开详情：完整的 prompt、LLM 输出、工具入出参。

##### 3.4.2 Token 经济看板：实时成本监控

Token 经济看板分为三个区域：

1. **实时概览**（页面顶部大数字）：
   - 今日总 Token 消耗 / 今日总成本 / 较昨日同期变化百分比
   - 当前小时 Token 消耗速率 / 是否超过预算阈值

2. **趋势图**（中间区域）：
   - 按小时的 Token 消耗趋势线（prompt vs completion 分开展示）
   - 按天的成本趋势柱状图（按模型分色）
   - 叠加预算线，一眼看出是否超支

3. **归因分析**（底部区域）：
   - 按模型的成本饼图
   - 按 Agent 的成本 Top 10 排行
   - 按用户的成本 Top 10 排行（用于发现异常用户）
   - 按环节的成本拆解（决策/检索/生成）

##### 3.4.3 延迟分布看板：各环节延迟分解

延迟看板的核心是**堆叠面积图**，将总延迟拆解为各环节的贡献：

- X 轴：时间
- Y 轴：延迟（ms）
- 面积层：LLM 推理延迟（通常最大）、工具执行延迟、上下文组装延迟、Agent 框架开销

另外配合**延迟分位数趋势图**（P50、P90、P99 三条线），快速发现延迟退化。

##### 3.4.4 Agent 对比看板：不同 Agent / 模型的效果对比

用于在多个 Agent 版本或多个模型之间做对比决策：

| 指标 | Agent v1.2 + GPT-4o | Agent v1.2 + Claude-3.5 | Agent v1.3 + GPT-4o |
|------|---------------------|------------------------|---------------------|
| 平均延迟 | 3.2s | 2.8s | 2.1s |
| P99 延迟 | 12.5s | 9.8s | 7.2s |
| 平均 Token | 4500 | 3800 | 3200 |
| 平均成本 | $0.12 | $0.09 | $0.08 |
| 成功率 | 94.2% | 95.1% | 96.8% |
| 平均轮数 | 3.5 | 3.2 | 2.8 |

这种对比看板对于模型选型和 Agent 迭代优化非常有价值。

##### 3.4.5 异常 Trace 高亮：自动标记异常执行路径

在 Trace 列表页面中，平台自动对以下类型的 Trace 做视觉标记：

- **红色标记**：执行出错（工具异常、LLM 返回格式错误等）
- **橙色标记**：疑似异常（Token 消耗 > P99、延迟 > P99、轮数 > 阈值）
- **黄色标记**：值得关注（工具重复调用、部分步骤失败但整体成功）

标记由异常检测引擎自动打标，工程师打开 Trace 列表就能一眼看到哪些需要关注，而不是从海量正常 Trace 中大海捞针。

---

#### 3.5 告警与自动化层

告警是可观测性的最后一道防线。Agent 系统的成本是实时产生的，一个死循环可能在几分钟内烧掉大量预算。因此告警必须实时、精准、可操作。

##### 3.5.1 告警规则引擎：基于阈值和异常检测

告警规则分为两类：

**静态规则**（人工定义的固定阈值）：
- 单次请求 Token 消耗 > 50000 → 告警
- 单次请求延迟 > 60 秒 → 告警
- Agent 循环轮数 > 15 → 告警（可能死循环）
- 工具失败率 5 分钟窗口 > 10% → 告警

**动态规则**（基于统计的自适应阈值）：
- 过去 1 小时的平均 Token 消耗超过过去 7 天同时段均值的 3 倍 → 告警
- P99 延迟较前一天同时段上升 50% 以上 → 告警
- 某个 Agent 的错误率从基线 2% 突增到 8% → 告警

##### 3.5.2 告警分级与路由

告警分为四个等级，不同等级走不同的通知渠道：

| 等级 | 定义 | 通知方式 | 响应 SLA |
|------|------|---------|---------|
| P0 - 严重 | 系统不可用或成本失控 | 电话 + 即时消息 + 邮件 | 15 分钟内响应 |
| P1 - 高危 | 核心功能受损或成本显著异常 | 即时消息 + 邮件 | 30 分钟内响应 |
| P2 - 中等 | 性能退化或非核心异常 | 即时消息 | 4 小时内响应 |
| P3 - 低危 | 潜在问题或轻微偏差 | 邮件汇总（每日） | 下一个工作日 |

路由规则：告警按 Agent 归属路由到对应的开发团队，按租户归属抄送给对应的业务方。

##### 3.5.3 自动诊断：触发告警后自动分析根因

光告警不够，还要告诉工程师"为什么"。自动诊断引擎在告警触发后执行以下步骤：

1. **关联 Trace**：找到触发告警的具体 Trace，提取完整执行链路。
2. **瓶颈定位**：在 Trace 中找到耗时或 Token 消耗最大的 Span。
3. **对比基线**：将异常 Trace 与历史正常 Trace 对比，找出差异。
4. **已知模式匹配**：在已知问题库中匹配——比如"某个工具返回超大响应导致上下文爆炸"是已知的常见根因。
5. **生成诊断报告**：将分析结果汇总成一段可读的文字，随告警一起推送。

##### 3.5.4 代码示例：告警规则配置

```python
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional
from enum import Enum
from datetime import timedelta


class AlertSeverity(Enum):
    P0_CRITICAL = "P0"
    P1_HIGH = "P1"
    P2_MEDIUM = "P2"
    P3_LOW = "P3"


class AlertConditionType(Enum):
    THRESHOLD = "threshold"               # 静态阈值
    RATE_OF_CHANGE = "rate_of_change"      # 变化率
    ANOMALY_ZSCORE = "anomaly_zscore"      # Z-Score 异常检测
    PATTERN_MATCH = "pattern_match"        # 模式匹配（如死循环检测）


@dataclass
class AlertRule:
    """告警规则定义。"""
    rule_id: str
    name: str
    description: str
    severity: AlertSeverity
    condition_type: AlertConditionType

    # 条件参数
    metric_name: str                  # 监控的指标名
    threshold: Optional[float] = None  # 静态阈值
    window: timedelta = timedelta(minutes=5)  # 检测窗口
    zscore_threshold: float = 3.0      # Z-Score 阈值（用于异常检测）
    baseline_window: timedelta = timedelta(days=7)  # 基线窗口

    # 过滤条件
    filters: Dict[str, str] = field(default_factory=dict)

    # 通知配置
    notification_channels: List[str] = field(default_factory=list)
    cooldown: timedelta = timedelta(minutes=15)  # 冷却时间，防止告警风暴

    # 自动诊断
    auto_diagnose: bool = True         # 是否触发自动诊断


# ---- 预定义的告警规则集 ----

BUILTIN_ALERT_RULES = [
    AlertRule(
        rule_id="agent_loop_detected",
        name="Agent 死循环检测",
        description="Agent 单次请求循环轮数超过阈值，可能陷入死循环",
        severity=AlertSeverity.P1_HIGH,
        condition_type=AlertConditionType.THRESHOLD,
        metric_name="agent_steps_per_request",
        threshold=15,
        notification_channels=["im", "email"],
        auto_diagnose=True,
    ),
    AlertRule(
        rule_id="token_cost_spike",
        name="Token 成本突增",
        description="过去 5 分钟的 Token 消耗速率超过 7 天基线的 3 倍",
        severity=AlertSeverity.P1_HIGH,
        condition_type=AlertConditionType.ANOMALY_ZSCORE,
        metric_name="agent_llm_tokens_total",
        zscore_threshold=3.0,
        window=timedelta(minutes=5),
        baseline_window=timedelta(days=7),
        notification_channels=["im", "email"],
        auto_diagnose=True,
    ),
    AlertRule(
        rule_id="tool_failure_rate",
        name="工具失败率飙升",
        description="某工具在 5 分钟窗口内失败率超过 10%",
        severity=AlertSeverity.P2_MEDIUM,
        condition_type=AlertConditionType.THRESHOLD,
        metric_name="agent_tool_error_rate",
        threshold=0.10,
        window=timedelta(minutes=5),
        notification_channels=["im"],
        auto_diagnose=True,
    ),
    AlertRule(
        rule_id="single_request_cost",
        name="单次请求成本过高",
        description="单次 Agent 请求成本超过 $1.00",
        severity=AlertSeverity.P2_MEDIUM,
        condition_type=AlertConditionType.THRESHOLD,
        metric_name="agent_request_cost_usd",
        threshold=1.00,
        notification_channels=["im"],
        auto_diagnose=True,
    ),
    AlertRule(
        rule_id="latency_degradation",
        name="延迟退化",
        description="P99 延迟较前一天同时段上升 50% 以上",
        severity=AlertSeverity.P2_MEDIUM,
        condition_type=AlertConditionType.RATE_OF_CHANGE,
        metric_name="agent_request_duration_seconds_p99",
        threshold=0.50,  # 50% 变化率
        window=timedelta(hours=1),
        baseline_window=timedelta(days=1),
        notification_channels=["im"],
        auto_diagnose=True,
    ),
]
```

---

### 四、核心数据流：一次 Agent 请求的观测全链路

下面以一次完整的 Agent 请求为例，步骤化地描述遥测数据从产生到展示的全链路流转。

#### 4.1 正常场景下的数据流转

**Step 1：用户请求进入 Agent 运行时**

Agent 编排引擎接收到用户请求"帮我汇总本周销售数据"。SDK 自动创建根 Span（kind=agent_request），生成全局唯一的 trace_id，将其注入请求上下文。事件总线发射 `span_start` 事件。

**Step 2：Agent 第一轮循环——决策调用工具**

编排引擎进入第一轮循环，创建子 Span（kind=agent_step）。在这轮循环中：
- 创建 LLM Span（kind=llm_call），记录模型名、prompt 内容摘要、开始时间。
- LLM 返回后，Span 记录 completion 内容摘要、Token 消耗（prompt_tokens=1200, completion_tokens=85）、延迟（TTFT=120ms, 总耗时=500ms）。
- 解析 LLM 输出，识别出需要调用 query_db 工具。
- 创建工具 Span（kind=tool_execution），记录工具名、入参，执行工具，记录出参和耗时（300ms）。
- 本轮循环的 agent_step Span 结束。

每个 Span 结束时，事件总线发射 `span_end` 事件。

**Step 3：Agent 后续轮次直至完成**

编排引擎继续循环，每轮重复 Step 2 的过程。最终 Agent 判断任务完成，根 Span 结束，发射最终的 `span_end` 事件。

**Step 4：事件总线分发到多个消费者**

事件总线将所有 `span_start` 和 `span_end` 事件分发给五个消费者：

- **Trace 消费者**：将所有 Span 按 trace_id 聚合，组装成 Span 树，写入 ClickHouse。同时计算 Trace 级别的聚合指标（总 Token、总成本、总延迟、轮数），写入 TraceRecord 表。
- **Metrics 消费者**：从每个 Span 中提取指标数据点——Token 消耗增量写入 Counter，延迟写入 Histogram——推送到 Prometheus。
- **Log 消费者**：将 Span 的详细属性写入 Elasticsearch，建立 trace_id 和 span_id 索引。
- **审计消费者**：如果 Span 涉及敏感工具调用（如写文件、发消息），将脱敏后的操作记录写入不可篡改的审计日志。
- **实时看板消费者**：将 Token 消耗和延迟的实时数据推送到 WebSocket，驱动前端看板更新。

**Step 5：分析引擎处理**

分析引擎持续消费存储层的数据：
- 延迟分析引擎计算最近 5 分钟的 P50/P90/P99 延迟。
- 成本归因引擎按模型、Agent、用户维度聚合 Token 成本。
- 异常检测引擎检查是否有指标超过阈值或偏离基线。

**Step 6：可视化展示**

前端看板实时刷新：
- 执行链路火焰图展示这次请求的完整 Span 树。
- Token 经济看板的数字更新。
- 如果这次请求被标记为异常，在 Trace 列表中高亮显示。

#### 4.2 异常场景下的自动诊断流程

假设 Agent 陷入了死循环——LLM 反复决定调用同一个工具，入参相同，结果也相同，但 Agent 判断任务未完成，继续循环。

**Step A：异常检测引擎触发告警**

当 agent_step 的轮数达到 15 轮时，异常检测引擎匹配到 `agent_loop_detected` 规则，触发 P1 告警。

**Step B：自动诊断引擎介入**

诊断引擎执行以下分析：
1. 提取该 Trace 的完整 Span 树，发现从第 5 轮开始，LLM 每轮都调用 `query_db`，入参完全相同。
2. 检查工具返回：query_db 每次都返回相同结果。
3. 检查 LLM 输出：LLM 每轮都说"查询结果不完整，需要重新查询"——这是一个幻觉，数据实际上是完整的。
4. 匹配已知模式库：命中"LLM 幻觉导致死循环"模式。

**Step C：诊断报告推送**

告警消息随附诊断报告：

```
[P1 告警] Agent 死循环检测
Trace ID: abc123def456
轮数: 15（阈值: 15）
当前 Token 消耗: 45000（已烧 $1.20）

自动诊断结论：
- 根因：LLM 幻觉，错误判断 query_db 返回结果"不完整"
- 分叉点：第 5 轮（前 4 轮正常）
- 匹配模式：「LLM 幻觉导致死循环」
- 建议：在 Agent 编排层增加重复工具调用检测（相同工具+相同入参连续 3 次自动终止）
```

**Step D：可选的自动止损**

如果配置了自动止损策略，平台可以在检测到死循环时直接终止 Agent 执行，返回降级响应，防止持续烧 Token。

---

### 五、调试工作流

#### 5.1 日常调试 SOP：从告警到定位到修复

一个成熟的 Agent 可观测性平台应该让工程师形成标准化的调试习惯。以下是推荐的 SOP：

**第一步：看告警，定性质**

收到告警后，先看告警类型和严重程度。如果附带了自动诊断报告，直接阅读诊断结论。

**第二步：看 Trace 火焰图，定位置**

打开告警关联的 Trace，在火焰图上快速扫描：
- 哪个 Span 耗时最长（延迟问题）？
- 哪个 Span Token 消耗最多（成本问题）？
- 哪个 Span 标红（错误问题）？
- 是否有重复的 Span 模式（死循环问题）？

**第三步：看 Span 详情，定原因**

点击异常 Span，查看详情：
- 如果是 LLM Span：看 prompt 是否合理，LLM 输出是否正确解析。
- 如果是工具 Span：看入参是否正确，出参是否符合预期，是否超时。
- 对比正常 Trace 中同位置的 Span，找差异。

**第四步：修复 + 验证**

根据定位到的原因修复代码或 prompt，然后用 Trace 回放功能验证修复效果。

#### 5.2 Trace 回放：重现执行路径

Trace 回放是 Agent 调试的"杀手锏"。由于 Agent 的非确定性，你无法通过简单地重新运行同样的输入来复现问题。但如果你完整保存了执行快照（每轮的 prompt、LLM 原始返回、工具入出参、模型参数），就可以在调试界面中逐步回放：

1. **全量回放**：按时间顺序逐步展示每轮的 prompt → LLM 输出 → 工具调用 → 结果，工程师可以像看录像一样逐帧查看。
2. **中间步 fork 重跑**：选择某一轮，修改 prompt 或模型参数，从这一步开始重新执行。用于验证"如果这里的 prompt 改成 XXX，结果会不会更好"。
3. **快照 diff**：两个快照并排展示差异，高亮不同之处。

回放功能的前提是采集层保存了完整快照。对于错误 Trace，必须 100% 保存完整快照；对于正常 Trace，可以采样保存（如 10%）。

#### 5.3 A/B 对比分析：成功与失败 case 的差异定位

当一个 Agent 在大部分时候工作正常、偶尔出错时，最有效的调试方法是 A/B 对比：

1. 从 Trace 列表中选一条失败 Trace 和一条成功 Trace（尽量选用户输入相似的）。
2. 并排展示两条 Trace 的火焰图。
3. 系统自动对比并高亮差异：
   - 执行路径分叉点：两条 Trace 从第几轮开始走了不同的路径。
   - 工具调用差异：失败的 Trace 调用了哪些不同的工具，或者同一个工具的入参有何不同。
   - LLM 输出差异：在分叉点上，LLM 的输出有何不同。
   - 上下文差异：分叉点时的上下文（包括前几轮的执行结果）有何不同。

这种 diff 视图能帮助工程师快速缩小排查范围，从"这个 Agent 有时候出错"精确到"当工具 X 返回 Y 这种格式时，LLM 会解析错误"。

#### 5.4 Token 消耗归因分析实践

某互联网公司的实际案例（已脱敏）：某个 Agent 的日均 Token 成本突然从 $200 涨到 $800。通过可观测性平台的调试流程：

1. **成本看板定位时间点**：成本从某天下午开始飙升。
2. **按 Agent 版本归因**：发现是当天发布的 v2.3 版本导致，v2.2 版本成本正常。
3. **按环节归因**：成本增长全部来自 prompt_tokens（输入 Token），completion_tokens 变化不大。
4. **对比 v2.2 和 v2.3 的 Trace**：发现 v2.3 在 system prompt 中新增了一段很长的工具使用说明（约 2000 Token），并且在每轮循环中都完整发送。
5. **根因**：新增的工具说明导致每轮 prompt 增加 2000 Token，一个典型请求 5 轮就多消耗 10000 Token。
6. **修复**：将工具说明改为按需注入（只在 LLM 表示需要使用工具时才注入），成本回落到 $250/天。

这个案例展示了成本归因引擎的价值：它能帮你从"成本涨了"精确定位到"哪个版本的哪个环节的哪种 Token 类型涨了"，然后对症下药。

---

### 六、企业级部署实践

#### 6.1 多租户隔离：不同团队的数据隔离与共享

在企业内部，多个团队可能使用同一个 Agent 平台。可观测性数据需要做租户隔离：

- **数据隔离**：每个租户只能看到自己的 Agent 数据。存储层通过 tenant_id 字段做数据隔离，查询层通过权限控制确保跨租户数据不泄漏。
- **聚合共享**：平台管理员可以看到全局聚合数据（总 Token 消耗、系统级别延迟分布），但不能看到具体租户的 Trace 详情。
- **成本分摊**：Token 成本按 tenant_id 归因后，可以对接企业内部的成本分摊系统，让各团队为自己的 Agent 使用量买单。

#### 6.2 性能开销控制：可观测性本身不能成为性能瓶颈

可观测性系统的铁律：**观测者不能影响被观测对象。** 具体措施：

- **异步采集**：所有遥测数据通过异步非阻塞方式采集，不增加 Agent 请求的关键路径延迟。
- **采样控制**：对于高流量场景，正常 Trace 可以采样（如 10% 采样率），错误 Trace 100% 全采。采样策略可动态调整。
- **大字段外置**：完整的 prompt 和 LLM 输出存储在对象存储而非主 Trace 表中，避免 Span 记录过大影响查询性能。
- **背压保护**：当采集管道过载时，丢弃低优先级事件（如详细日志），保留高优先级事件（如 Span 开始/结束、Token 消耗），绝不阻塞 Agent 主流程。
- **开销预算**：可观测性引入的额外延迟不超过 Agent 请求总延迟的 1%，额外内存不超过 Agent 进程内存的 5%。

#### 6.3 数据隐私：敏感信息的脱敏与合规

Agent 的遥测数据中可能包含用户的敏感信息（用户输入、查询结果等）。必须在采集阶段就做好脱敏：

- **PII 检测与脱敏**：在遥测事件写入存储之前，经过 PII 脱敏管道，自动检测并脱敏手机号、身份证号、银行卡号等敏感信息。
- **分级存储**：脱敏前的原始数据（用于调试回放）存储在加密的、权限严格控制的快照存储中，只有授权的调试人员可以访问。脱敏后的数据用于分析和看板展示。
- **审计日志**：所有对原始遥测数据的访问都记录审计日志，支持事后追溯。
- **合规对齐**：数据保留期限对齐企业的数据安全政策和法规要求（如 GDPR 的"被遗忘权"）。

#### 6.4 与现有监控系统的集成

Agent 可观测性平台不是替代现有的 APM / 监控系统，而是在其上扩展 Agent 语义层。集成方式：

- **与 Prometheus 集成**：Agent 指标以标准 Prometheus Exporter 方式暴露，可以无缝接入现有的 Prometheus + Grafana 告警体系。
- **与 Jaeger / Zipkin 集成**：Agent Trace 符合 OpenTelemetry 标准，可以导出到现有的分布式追踪系统。但 Agent 特有的语义字段（Token 消耗、模型名等）需要在 Agent 专用的 UI 中展示。
- **与日志系统集成**：Agent 结构化日志可以接入 ELK / Loki 等现有日志平台，通过 trace_id 与 Agent Trace 关联。
- **与成本管理系统集成**：Token 成本数据可以对接企业的 FinOps 平台，纳入统一的云成本管理。

---

### 七、演进路线

构建企业级 Agent 可观测性平台不可能一蹴而就，建议分三个阶段循序渐进。

#### Phase 1：基础可观测性（日志 + 基础 Metrics）

**目标**：让 Agent 系统"可查"——出了问题至少有日志可以翻。

**核心能力**：
- 结构化日志：每次 LLM 调用和工具执行都输出结构化日志，包含 trace_id、耗时、Token 消耗、状态。
- 基础指标：总 Token 消耗（Counter）、总请求数（Counter）、活跃请求数（Gauge）、请求延迟（Histogram），推送到 Prometheus。
- 基础看板：Grafana 上搭几个基础面板，展示 Token 消耗趋势、请求量趋势、错误率。
- 静态告警：Token 消耗超阈值、错误率超阈值时触发告警。

**验收标准**：
- 能通过 trace_id 在日志系统中检索一次请求的所有相关日志。
- 能在看板上看到过去 24 小时的 Token 消耗趋势和错误率。
- Token 消耗异常时能在 15 分钟内收到告警。

**预计投入**：1-2 人周。

#### Phase 2：全链路 Tracing（Span 树 + 火焰图）

**目标**：让 Agent 系统"可视"——能像看 X 光片一样看到每次请求的内部结构。

**核心能力**：
- Span 树：每次请求生成完整的 Span 树，记录每一轮 Agent 循环、每次 LLM 调用、每次工具执行的详细信息。
- 火焰图：专用 UI 展示 Span 树的火焰图，支持点击查看 Span 详情（prompt、输出、工具参数等）。
- 事件总线：将遥测数据采集与消费解耦，支持多个下游消费者。
- 完整快照：对错误 Trace 100% 保存完整快照，支持基础的回放功能。
- Token 经济看板：多维度成本归因看板上线。
- 异常 Trace 标记：自动标记延迟、成本、轮数异常的 Trace。

**验收标准**：
- 打开任意 Trace 能看到完整的火焰图，每个 Span 能展开详情。
- 能在成本看板上按模型、Agent、用户维度查看 Token 成本分布。
- 错误 Trace 能完整回放。
- 异常 Trace 在列表中自动高亮。

**预计投入**：3-4 人周。

#### Phase 3：智能分析（异常检测 + 自动根因分析）

**目标**：让 Agent 系统"可控"——不仅能看到问题，还能自动发现问题并辅助诊断。

**核心能力**：
- 动态异常检测：基于统计方法（Z-Score）和机器学习（Isolation Forest）的自动异常检测，替代纯静态阈值。
- 自动根因分析：告警触发后自动执行诊断流程（关联 Trace → 瓶颈定位 → 基线对比 → 模式匹配），生成诊断报告。
- A/B 对比分析：支持两条 Trace 的并排 diff，自动高亮差异和分叉点。
- Trace 聚合分析：跨 Trace 的模式挖掘，发现 Agent 的典型行为模式和常见失败模式。
- 中间步 fork 重跑：从 Trace 回放的某一步修改参数重新执行，验证修复方案。
- 评测集成：线上 Trace 自动喂给评测流水线（LLM-as-Judge），持续监控 Agent 的决策质量。

**验收标准**：
- 成本突增能在 5 分钟内自动检测并告警，附带初步诊断报告。
- 死循环能在触发后自动诊断出"重复调用同一工具"的模式。
- 两条 Trace 的 diff 能在 3 秒内生成。
- Agent 决策质量评分每天自动产出报告。

**预计投入**：4-6 人周。

---

### 八、面试加分点

#### 8.1 如何用 3 分钟讲清楚 Agent 可观测性平台的架构

面试中如果被问到"你们怎么做 Agent 可观测性"，推荐按以下结构回答：

**第一句话——定义问题（15 秒）**：
"Agent 和传统服务最大的区别是执行路径非确定、成本按 Token 按量计费、错误可能没有异常栈。所以传统 APM 不够用，需要专门的可观测性体系。"

**第二段——架构概述（45 秒）**：
"我们的架构分五层：采集层通过 SDK 自动埋点，用事件总线解耦；存储层分 Trace 存储、时序指标和日志三类；分析层做延迟瓶颈定位、成本多维归因和异常检测；展示层核心是执行链路火焰图和 Token 经济看板；告警层做实时检测加自动诊断。"

**第三段——核心设计决策（60 秒）**：
"几个关键设计：一是 Span 树结构，一次请求是一棵树，每轮 Agent 循环、LLM 调用、工具执行都是节点，支持火焰图可视化。二是 Token 成本作为一等公民，像对待延迟一样对待成本，按模型、Agent、用户、环节多维归因。三是事件总线解耦采集和消费，遥测挂了不影响主流程。四是错误 Trace 100% 全采全保留，支持完整回放和 A/B 对比。"

**第四段——实际效果（30 秒）**：
"实际用下来，成本异常从之前要到第二天看报表才发现，变成了 5 分钟内自动告警。调试效率大幅提升，从之前翻日志猜问题变成了看火焰图直接定位到具体 Span。"

#### 8.2 面试官可能追问的深度问题及回答思路

**Q1：你怎么处理 Agent 遥测数据的量级问题？一天多少数据？**

回答思路：先算一笔账——假设日均 100 万次 Agent 请求，平均每次产生 10 个 Span，每个 Span 约 2KB（不含大字段），则 Span 数据约 20GB/天。大字段（prompt、completion）存对象存储，按需加载。通过热/温/冷分层存储控制成本，热层保留 24 小时在 SSD，30 天内降到 HDD，更早的降采样后归档。错误 Trace 单独保留 90 天。

**Q2：采样率怎么定？会不会漏掉重要信息？**

回答思路：采用尾部采样（tail-based sampling）而非头部采样。头部采样在请求开始时决定是否采集，可能漏掉后来出错的请求。尾部采样在请求完成后根据结果决定是否保留：错误 Trace 100% 保留，延迟/成本异常 Trace 100% 保留，正常 Trace 按比例采样（如 10%）。这样保证了所有有调试价值的 Trace 不会丢失。

**Q3：怎么评估 Agent 的"决策质量"？这个没法用传统指标衡量吧？**

回答思路：是的，传统指标（延迟、错误率）只能衡量"有没有跑通"，不能衡量"跑得好不好"。评估决策质量需要引入评测（eval）机制：一是用 LLM-as-Judge 对 Agent 的最终输出打分（相关性、准确性、完整性）；二是检查工具调用的合理性（调用了不必要的工具、遗漏了必要的工具）；三是检查轮数效率（同样的任务，好的 Agent 应该用更少的轮数完成）。这些评测可以线上采样运行，也可以离线对历史 Trace 批量运行。

**Q4：可观测性系统本身挂了怎么办？**

回答思路：核心原则是"遥测故障不影响业务"。具体措施：事件总线做异常隔离，任何消费者挂了不影响其他消费者和 Agent 主流程；采集 SDK 内部 catch 所有异常，只降级（丢弃事件）不崩溃；存储层做高可用（ClickHouse 集群、Prometheus 联邦）；可观测性系统自身也要有自己的监控（meta-monitoring），用独立的轻量监控检查可观测性平台的健康度。

**Q5：你提到 Token 成本是一等公民，能不能举一个通过成本优化实际省钱的例子？**

回答思路：讲一个具体案例（如前文 5.4 节的 Token 消耗归因分析实践），重点突出：问题发现（成本看板告警）→ 归因定位（多维度拆解到具体版本的具体环节）→ 根因分析（v2.3 的 system prompt 过长）→ 优化方案（按需注入工具说明）→ 效果验证（成本从 $800/天回落到 $250/天）。面试官想听的是闭环的问题解决过程，而不是泛泛而谈。

**Q6：OpenTelemetry 的 GenAI Semantic Conventions 了解吗？你们有用吗？**

回答思路：OpenTelemetry 正在制定 GenAI 语义约定（目前还在 experimental 阶段），定义了 LLM 调用的标准 Span 属性（如 `gen_ai.system`、`gen_ai.request.model`、`gen_ai.usage.prompt_tokens` 等）。我们的设计与其对齐，方便未来无缝接入 OTel 生态。但 OTel GenAI Conventions 目前还不够完善，缺少 Agent 级别的语义（如 agent_step、tool_execution），所以我们在其基础上做了扩展。

---

> **本附录的核心观点**：Agent 可观测性不是锦上添花，而是生产级 Agent 系统的基础设施。它回答两个最关键的问题——"Agent 有没有做对事"和"做这件事花了多少钱"。从基础日志起步，到全链路 Tracing，再到智能分析和自动诊断，循序渐进地构建，最终让 Agent 系统从黑盒变成白盒，从不可控变成可控。
