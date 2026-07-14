# Agent成本优化与性能调优

> 本文面向大模型工程师、AI Infra工程师的面试准备与工程实践。文中所有企业内部信息均已脱敏，以"某互联网公司""某Agent平台""某客服Agent"等通用描述替代。代码示例以 Python 为主，可直接借鉴到生产环境。

---

## 一、Agent成本问题的本质

### 1.1 Agent vs 传统LLM调用的成本差异

传统的 LLM 调用是"一问一答"的单轮模式：用户发一个 prompt，模型返回一个 completion，一次调用结束。它的成本模型非常简单：

```
单次成本 = input_tokens × 输入单价 + output_tokens × 输出单价
```

而 Agent 是一个**多轮、带工具调用、带反思循环**的系统。一次用户请求，Agent 内部往往要经历：

1. 理解任务 → 调用 LLM 做规划（Planning）
2. 决定调用哪个工具 → 调用 LLM 生成 tool_call
3. 执行工具 → 工具结果回填到上下文
4. 观察结果 → 再次调用 LLM 判断下一步（ReAct 循环）
5. 反复 3~4 直到任务完成
6. 生成最终答复 → 调用 LLM 汇总

也就是说，一次 Agent 任务的成本不是一次 LLM 调用，而是：

```
Agent任务成本 = Σ(每一步的 LLM 调用成本) + 工具执行成本
```

更关键的是，Agent 的上下文是**累积增长**的。每一轮循环，之前所有的对话历史、工具调用记录、工具返回结果都会被塞进下一次请求的 input。假设一个 Agent 循环 10 步，每步上下文增加 2000 tokens，那么：

- 第 1 步 input：2000 tokens
- 第 2 步 input：4000 tokens
- ...
- 第 10 步 input：20000 tokens
- **累计 input：约 110000 tokens**

这就是 Agent 成本的"雪球效应"——上下文随着步数线性增长，总 token 消耗随步数**二次方增长**。

### 1.2 一次Agent任务可能产生数十次LLM调用

我们用一个真实的量级估算来说明问题。假设一个中等复杂度的编码 Agent 任务：

| 阶段 | LLM调用次数 | 平均input tokens | 平均output tokens |
|------|------------|-----------------|------------------|
| 任务理解与规划 | 1 | 8,000 | 500 |
| 工具调用循环（读文件/搜索/编辑） | 15 | 25,000 | 800 |
| 错误修复与重试 | 5 | 30,000 | 600 |
| 最终总结 | 1 | 35,000 | 1,000 |
| **合计** | **22** | **累计约 560,000** | **累计约 16,000** |

用某主流大模型的定价（假设 input $3/M tokens，output $15/M tokens）计算：

```
input 成本  = 560,000 / 1,000,000 × $3  = $1.68
output 成本 = 16,000 / 1,000,000 × $15  = $0.24
单次任务成本 ≈ $1.92
```

一个用户一天跑 50 个任务，一个月就是 `$1.92 × 50 × 30 ≈ $2880`。如果有 1 万活跃用户，月成本高达 **2880 万美元**。这就是为什么企业级 Agent 的成本会失控。

### 1.3 成本-质量-速度三难困境

Agent 系统面临一个经典的"不可能三角"：

```
          质量(Quality)
             /\
            /  \
           /    \
          /      \
    成本 ---------- 速度
   (Cost)          (Latency)
```

- 想要**高质量**：用最大的模型、更多的推理步数、更长的上下文 → 成本高、速度慢
- 想要**低成本**：用小模型、压缩上下文、减少调用 → 质量下降
- 想要**低延迟**：并行调用、投机解码、减少循环 → 可能牺牲质量或增加成本

工程实践中，我们不追求同时最优，而是**根据业务场景选择合适的平衡点**。例如：

- 客服 Agent：延迟敏感、质量要求中等 → 优先小模型 + 缓存
- 编码 Agent：质量敏感、延迟可容忍 → 大模型 + 精细上下文工程
- 数据分析 Agent：成本敏感、可离线 → 批量处理 + 模型路由

### 1.4 企业级Agent的成本失控案例

某互联网公司在早期上线一个内部知识问答 Agent 时，没有做任何成本控制，直接用最大模型处理所有请求。上线两周后发现：

1. **上下文无限增长**：多轮对话不做压缩，单个会话 input 达到 15 万 tokens，一句"你好"都要花几毛钱。
2. **无效重试**：工具调用失败后 Agent 无脑重试，一个失败任务能循环 50 多次。
3. **全部走大模型**：80% 的请求其实是"今天天气""公司地址"这类简单问题，却和复杂的代码分析用同一个大模型。
4. **无缓存**：相同的 system prompt（约 6000 tokens）在每次请求都重新计费。

结果第一个月账单超预算 8 倍。后续通过本文介绍的模型路由、Prompt Caching、Token Budget、上下文工程四板斧，把成本压到原来的 **35%**，质量几乎无损。

---

## 二、模型智能路由

### 2.1 为什么需要模型路由

核心洞察：**并非所有请求都需要最强的模型**。

一个典型的线上 Agent 平台，请求的难度分布往往符合长尾分布：

```
请求难度分布：
简单(闲聊/FAQ/格式化)   ████████████████████ 60%
中等(单步工具调用/摘要)   ██████████ 30%
复杂(多步推理/代码生成)   ███ 10%
```

如果 90% 的请求可以被小模型（成本约为大模型的 1/10~1/20）处理，只有 10% 需要大模型，那么理论上成本可以降低：

```
优化前：100% × 大模型成本 = 1.0
优化后：90% × 0.1 + 10% × 1.0 = 0.09 + 0.10 = 0.19
成本降低约 81%
```

即使考虑路由本身的开销和小模型的准确率损失，实际降本通常也能达到 **50%~70%**，这是最高性价比的优化手段之一。

### 2.2 路由策略

#### （1）基于规则的路由

最简单、延迟最低、零额外成本。根据关键词、请求长度、任务类型硬编码路由规则。

```python
def rule_based_router(request: str, task_type: str) -> str:
    """基于规则的模型路由，返回模型名称"""
    # 简单FAQ/闲聊，走小模型
    simple_keywords = ["你好", "谢谢", "怎么用", "在吗", "帮助"]
    if any(kw in request for kw in simple_keywords):
        return "small-model"

    # 明确的复杂任务，直接走大模型
    complex_types = {"code_generation", "multi_step_reasoning", "data_analysis"}
    if task_type in complex_types:
        return "large-model"

    # 超长上下文只能走支持长上下文的大模型
    if len(request) > 8000:
        return "large-model-long-context"

    # 默认中等模型
    return "medium-model"
```

优点：零延迟、零成本、可解释。缺点：规则维护成本高、覆盖不全、无法处理语义上的难度差异。

#### （2）基于ML的路由

训练一个轻量分类器（如 BERT-small、逻辑回归、GBDT），预测任务难度或应该走哪个模型。特征可以是：请求文本 embedding、历史相似请求的成功率、请求长度、是否包含代码等。

```python
class MLRouter:
    def __init__(self, classifier, embedder):
        self.classifier = classifier   # 预训练的难度分类器
        self.embedder = embedder        # 轻量 embedding 模型

    def route(self, request: str) -> str:
        emb = self.embedder.encode(request)
        # 分类器输出难度概率 [easy, medium, hard]
        proba = self.classifier.predict_proba([emb])[0]
        difficulty = ["small-model", "medium-model", "large-model"]
        return difficulty[proba.argmax()]
```

优点：能捕捉语义难度、准确率高。缺点：需要标注数据训练、有推理延迟（虽然很小）、需要持续迭代。

#### （3）基于LLM的路由

让一个小模型（或大模型的低成本模式）先判断"这个任务我能不能搞定"，或者直接输出应该用哪个模型。

```python
ROUTER_PROMPT = """你是一个任务难度评估器。判断以下任务的难度，只输出 easy / medium / hard 之一。
任务：{task}
难度："""

def llm_router(task: str, small_model_client) -> str:
    resp = small_model_client.complete(
        ROUTER_PROMPT.format(task=task),
        max_tokens=5,           # 只需要输出一个词，控制成本
        temperature=0,
    )
    level = resp.strip().lower()
    return {"easy": "small-model",
            "medium": "medium-model",
            "hard": "large-model"}.get(level, "medium-model")
```

优点：灵活、无需训练数据。缺点：路由本身要花一次 LLM 调用（虽然是小模型），增加延迟。

#### （4）级联路由（Cascading）

不预先判断，而是**先用小模型试，效果不达标再升级到大模型**。这是最实用的策略之一，因为它天然处理了"看起来简单但实际复杂"的边界情况。

```python
def cascading_router(task: str, small_client, large_client,
                     quality_check) -> tuple[str, str]:
    """级联路由：小模型先试，质量不达标升级大模型"""
    # 第一级：小模型
    small_resp = small_client.complete(task)
    if quality_check(task, small_resp):
        return small_resp, "small-model"

    # 第二级：大模型兜底
    large_resp = large_client.complete(task)
    return large_resp, "large-model"


def quality_check(task: str, resp: str) -> bool:
    """质量校验：可用规则、置信度、或另一个小模型判断"""
    if len(resp) < 10:              # 回答过短，可能是拒答/失败
        return False
    if "我不确定" in resp or "无法" in resp:
        return False
    # 也可用小模型打分：score = judge_model(task, resp) >= 0.8
    return True
```

级联的关键权衡：**升级率**。如果 90% 的任务小模型就能搞定，只有 10% 升级，那么额外成本很小；但如果升级率高，就变成"两次调用"反而更贵，需要监控并调整质量阈值。

#### （5）级联的成本模型

```
级联成本 = 小模型成本 + 升级率 × 大模型成本
```

假设小模型成本 0.1，大模型成本 1.0，升级率 15%：

```
级联成本 = 0.1 + 0.15 × 1.0 = 0.25  （相比全走大模型省 75%）
```

如果升级率上升到 60%：

```
级联成本 = 0.1 + 0.6 × 1.0 = 0.70  （只省 30%，且延迟翻倍）
```

所以级联策略必须配合**升级率监控告警**。

### 2.3 企业级路由网关实践

#### （1）某Agent平台的智能路由网关设计（脱敏）

某Agent平台在其 LLM 调用链路前置了一个**统一模型网关**，架构大致如下：

```
                ┌─────────────────────────────────┐
   Agent请求 ──▶ │      智能路由网关 (Gateway)       │
                │  ┌────────────┐  ┌─────────────┐ │
                │  │ 路由决策器  │  │ 供应商管理器 │ │
                │  │(规则+ML融合)│  │(多模型/多云) │ │
                │  └────────────┘  └─────────────┘ │
                │  ┌────────────┐  ┌─────────────┐ │
                │  │ 缓存层      │  │ 限流/预算    │ │
                │  └────────────┘  └─────────────┘ │
                └───────┬──────────────┬───────────┘
                        ▼              ▼
                  小模型集群       大模型集群 / 第三方API
```

网关承担的职责：

1. **路由决策**：融合规则路由（快速通道）和 ML 路由（语义难度），先跑规则，命中则直接路由；未命中再跑轻量分类器。
2. **供应商管理**：屏蔽底层是自建模型还是第三方 API，统一接口。支持按成本、延迟、可用性动态选择供应商。
3. **Fallback 链**：主模型不可用（超时/限流/报错）时，自动降级到备用模型。
4. **缓存**：请求级和 prefix 级缓存（见第三节）。
5. **预算与限流**：按业务线、按用户做 token 预算和 QPS 限流。

#### （2）模型供应商管理与fallback

生产环境的模型服务不可能 100% 可用，必须设计 fallback 链：

```python
class ModelGateway:
    def __init__(self, providers: list):
        # providers 按优先级排序：[主模型, 备模型1, 备模型2, ...]
        self.providers = providers

    async def complete(self, request, timeout=30):
        last_err = None
        for provider in self.providers:
            try:
                return await provider.complete(request, timeout=timeout)
            except (TimeoutError, RateLimitError, ServiceUnavailable) as e:
                last_err = e
                # 记录降级指标，触发告警
                metrics.incr(f"fallback.{provider.name}")
                continue
        raise AllProvidersFailed(last_err)
```

Fallback 的注意点：

- **降级要有质量兜底**：备用模型能力不能太弱，否则用户体验断崖式下跌。
- **降级要打点**：频繁降级说明主模型有问题，需告警。
- **避免雪崩**：主模型限流时，大量请求涌向备用模型可能把备用也打挂，需要配合熔断器（Circuit Breaker）。

#### （3）路由决策的延迟开销控制

路由本身不能太慢，否则得不偿失。控制手段：

- **规则优先**：能用规则命中的走快速通道（微秒级），不跑 ML。
- **分类器轻量化**：路由分类器用蒸馏后的小模型或传统 ML，延迟控制在 10ms 内。
- **异步预取**：对于级联策略，可以在小模型返回的同时**预测性地**并发预热大模型（投机执行），如果需要升级则已经在路上。
- **缓存路由决策**：相似请求的路由结果可缓存。

#### （4）A/B测试路由策略效果

上线新路由策略必须做 A/B 实验，核心指标：

| 指标 | 说明 |
|------|------|
| 平均成本/请求 | 核心降本指标 |
| 任务成功率 | 质量守护指标，不能降 |
| P50/P99 延迟 | 速度守护指标 |
| 升级率 | 级联策略专属 |
| 用户满意度/反馈 | 最终业务指标 |

实验设计上，用流量分桶（如 5% 实验组），跑够统计显著性，确认"成本降了、质量没降"再全量。

---

## 三、Prompt Caching工程实践

### 3.1 Prompt Caching原理

#### （1）KV Cache vs Prompt Caching

这两个概念容易混淆，必须分清：

| 维度 | KV Cache | Prompt Caching |
|------|----------|----------------|
| 作用范围 | **单次请求内部**的自回归解码 | **跨请求**复用相同前缀 |
| 生命周期 | 一次 generation 结束即释放 | 分钟级~小时级 |
| 优化目标 | 加速 decode（避免重复计算历史 token 的 KV） | 降低成本+延迟（避免重复 prefill 相同前缀） |
| 是否计费差异 | 无独立计费 | 有 cache write / cache read 差异计费 |

简单说：KV Cache 是解码时的内部优化；Prompt Caching 是把已经算好的前缀 KV 存起来，下次请求命中相同前缀时直接复用，跳过 prefill。

#### （2）缓存命中条件：前缀一致性

Prompt Caching 的核心约束是：**必须是前缀完全一致（byte-level 或 token-level）**。

```
请求A: [System Prompt][Tools定义][对话历史1]
请求B: [System Prompt][Tools定义][对话历史2]
                ↑ 相同前缀部分可命中缓存 ↑
```

只要前缀有一个 token 不同，从那个 token 开始往后都无法命中。所以 Prompt 结构设计的黄金法则是：**把静态的、公共的内容放在最前面，把动态的、变化的内容放在最后面**。

#### （3）Cache Creation vs Cache Read的成本差异

以某主流大模型的缓存定价为例（示意）：

| 操作 | 相对价格 |
|------|---------|
| 普通 input | 1.0x |
| Cache Write（创建缓存） | 1.25x（比普通略贵） |
| Cache Read（命中缓存） | 0.1x（便宜 90%） |

关键洞察：

- 第一次请求要付 1.25x 的 cache write 成本（比不缓存还贵一点）。
- 后续命中只付 0.1x。
- 所以**只有当同一前缀被复用多次时，缓存才划算**。命中次数越多，越省钱。

盈亏平衡点估算：设前缀 token 数为 N，命中次数为 K。

```
不缓存总成本 = (K+1) × N × 1.0
缓存总成本   = N × 1.25 (首次write) + K × N × 0.1 (K次read)
             = N × (1.25 + 0.1K)

缓存更划算的条件：N × (1.25 + 0.1K) < (K+1) × N
即  1.25 + 0.1K < K + 1
即  0.25 < 0.9K
即  K > 0.28  →  只要复用超过 1 次就划算
```

结论：只要前缀会被复用哪怕 1 次以上，Prompt Caching 就是净赚的。

#### （4）Anthropic/OpenAI的Prompt Caching API对比

| 维度 | 方案A（显式缓存） | 方案B（自动缓存） |
|------|------------------|------------------|
| 触发方式 | 需在请求中显式标记 `cache_control` 断点 | 自动检测前缀，无需标记 |
| 缓存粒度 | 开发者可控（可设多个断点） | 系统自动决定 |
| 最小缓存长度 | 有最小 token 门槛（如 1024） | 有最小门槛 |
| 缓存有效期 | 分钟级，可续期 | 分钟级 |
| 定价 | write 略贵、read 便宜 | read 便宜、无额外 write 加价 |

工程建议：如果用显式缓存 API，在 System Prompt 末尾、Tools 定义末尾等**稳定边界**处打 cache 断点；如果是自动缓存，则通过 Prompt 结构设计来最大化前缀一致性。

### 3.2 Agent场景的缓存优化

#### （1）System Prompt的缓存设计：静态前缀 vs 动态后缀

Agent 的 System Prompt 通常很长（几千到上万 token），是缓存优化的重点。核心是**分离静态与动态部分**：

```
❌ 坏设计（动态内容穿插在中间，破坏前缀一致性）：
System: 你是一个助手。当前时间是 2024-06-16 14:32:05。你可以使用以下工具...

✅ 好设计（静态在前，动态在后）：
System: 你是一个助手。你可以使用以下工具...（长而稳定的部分）
        ---
        [动态上下文] 当前时间：2024-06-16 14:32:05
```

把 `当前时间`、`用户ID`、`会话变量` 这些每次都变的内容放到最后，前面的大段静态指令就能稳定命中缓存。

#### （2）工具定义的缓存策略

Tools 定义（JSON schema）往往也占几千 token，且相对稳定。策略：

- 把 Tools 定义放在 System Prompt 之后、对话历史之前，作为缓存前缀的一部分。
- **工具列表要稳定排序**：如果每次工具的顺序不同，前缀就不一致，缓存失效。要固定排序（如按名称字典序）。
- 动态工具（如根据用户权限增减工具）放在末尾，避免污染稳定前缀。

#### （3）上下文压缩与缓存的协同

这里有一个微妙的冲突：**上下文压缩会改变前缀，导致缓存失效**。

比如对话进行到第 20 轮时触发压缩，把前 15 轮总结成一段摘要，这时前缀完全变了，之前的缓存全部失效，需要重新 write。

协同策略：

- **压缩点对齐缓存边界**：不要频繁压缩，攒到一定 token 量再压缩一次，减少缓存重建频率。
- **压缩后立即重新预热缓存**：压缩产生新前缀后，第一次请求就是 cache write，后续复用。
- **分段缓存**：把上下文分成"已固化段"（不再变，长期缓存）和"活跃段"（频繁变，不缓存），压缩只作用于活跃段变成已固化段。

#### （4）缓存命中率优化：splitSysPromptPrefix策略

`splitSysPromptPrefix`（拆分系统提示前缀）是一种实战优化：把 System Prompt 拆成多个缓存段，在每个稳定边界打断点，使得即使后面部分变化，前面的段依然能命中。

```python
def build_cached_messages(static_system: str,
                          tools_def: str,
                          session_context: str,
                          history: list) -> list:
    """构造带缓存断点的消息，最大化前缀命中"""
    return [
        # 段1：最稳定的系统指令，长期缓存
        {"role": "system", "content": static_system,
         "cache_control": {"type": "ephemeral"}},        # 缓存断点1
        # 段2：工具定义，较稳定
        {"role": "system", "content": tools_def,
         "cache_control": {"type": "ephemeral"}},        # 缓存断点2
        # 段3：会话级上下文，一个会话内稳定
        {"role": "system", "content": session_context,
         "cache_control": {"type": "ephemeral"}},        # 缓存断点3
        # 段4：活跃对话历史，不缓存（每轮都变）
        *[{"role": m["role"], "content": m["content"]} for m in history],
    ]
```

这样即使会话上下文（段3）变化，段1和段2依然命中，最大化了缓存复用。

#### （5）代码示例：优化Prompt结构提高缓存命中

```python
class CacheOptimizedPromptBuilder:
    """缓存友好的 Prompt 构造器：静态前缀 + 动态后缀"""

    def __init__(self, static_system: str, tools: list):
        self.static_system = static_system
        # 工具按名称固定排序，保证前缀一致性
        self.tools = sorted(tools, key=lambda t: t["name"])

    def build(self, dynamic_ctx: dict, history: list) -> dict:
        # 静态前缀：系统指令 + 稳定工具定义（可长期缓存）
        static_prefix = self.static_system
        tools_json = json.dumps(self.tools, ensure_ascii=False, sort_keys=True)

        # 动态后缀：时间/用户/变量（每次都变，放最后）
        dynamic_suffix = (
            f"\n\n[运行时上下文]\n"
            f"当前时间：{dynamic_ctx.get('now')}\n"
            f"用户ID：{dynamic_ctx.get('user_id')}\n"
        )

        return {
            "system": [
                {"text": static_prefix, "cache_control": {"type": "ephemeral"}},
                {"text": tools_json, "cache_control": {"type": "ephemeral"}},
                {"text": dynamic_suffix},   # 动态部分不打缓存断点
            ],
            "messages": history,
        }
```

### 3.3 缓存失效与更新

#### （1）缓存失效场景

| 失效场景 | 原因 | 应对 |
|---------|------|------|
| 工具列表变更 | 增删工具改变前缀 | 工具变更做灰度，避免频繁改 |
| 系统配置更新 | System Prompt 改版 | 版本化 System Prompt，集中发布 |
| 上下文压缩 | 历史被总结，前缀变了 | 压缩点对齐、压缩后预热 |
| 缓存 TTL 过期 | 分钟级过期 | 高频请求自动续期；低频接受 miss |
| 动态内容穿插 | 时间/随机数写在前缀 | 全部挪到后缀 |

#### （2）缓存预热策略

对于可预测的高频前缀（如某业务线的固定 System Prompt），可以在流量高峰前主动发一次"预热请求"，把缓存 write 好，让后续真实流量直接命中。

```python
async def warmup_cache(gateway, prompt_templates: list):
    """缓存预热：高峰前主动写入热点前缀"""
    for tpl in prompt_templates:
        try:
            await gateway.complete(tpl, max_tokens=1)  # 只需触发 prefill
        except Exception as e:
            logging.warning(f"warmup failed: {e}")
```

#### （3）多租户场景的缓存隔离

多租户/多业务线共用一个 Agent 平台时，缓存要做隔离：

- **按租户分 namespace**：不同租户的前缀即使内容相同也不共享缓存，避免数据泄露风险。
- **公共前缀提取**：把平台级公共 System Prompt 抽成共享缓存段，租户私有部分放后缀单独缓存。
- **缓存配额**：给每个租户分配缓存容量上限，防止某个租户挤占全部缓存空间。

---

## 四、Token Budget分配策略

### 4.1 为什么需要Token预算

1. **防止单次任务消耗失控**：没有预算约束时，一个陷入死循环的 Agent 能烧掉几十美元。
2. **公平分配资源**：多用户共享算力时，防止个别用户挤占全部配额。
3. **成本可预测性**：给业务方一个可预期的成本上限，便于财务规划。

### 4.2 预算分配模型

#### （1）全局预算 vs 分步预算

- **全局预算**：整个 Agent 任务分配一个总 token 上限（如 200K）。简单，但可能前几步就烧光。
- **分步预算**：每一步工具调用/LLM 调用有独立上限。更精细，防止单步失控。

实践中两者结合：全局预算做硬顶，分步预算做软约束。

#### （2）动态预算调整

根据任务复杂度动态分配。简单任务给小预算，复杂任务给大预算：

```
任务预算 = base_budget × complexity_factor
complexity_factor: 简单=0.3, 中等=1.0, 复杂=3.0
```

#### （3）超预算处理策略

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| 降级 | 切换到小模型继续 | 质量可容忍 |
| 截断 | 压缩上下文后继续 | 上下文过长 |
| 中止 | 直接停止并返回已有结果 | 硬性成本红线 |
| 转人工 | 交给人工处理 | 高价值任务 |

### 4.3 代码实现

#### （1）Token预算管理器

```python
from dataclasses import dataclass, field

class BudgetExceeded(Exception):
    pass

@dataclass
class TokenBudgetManager:
    total_budget: int                 # 全局预算上限
    per_step_budget: int              # 单步预算上限
    consumed: int = 0                 # 已消耗
    step_history: list = field(default_factory=list)

    def check_and_reserve(self, estimated: int) -> None:
        """调用前检查预算，超限抛异常"""
        if estimated > self.per_step_budget:
            raise BudgetExceeded(
                f"单步预估 {estimated} 超过单步上限 {self.per_step_budget}")
        if self.consumed + estimated > self.total_budget:
            raise BudgetExceeded(
                f"累计 {self.consumed + estimated} 将超过全局预算 {self.total_budget}")

    def commit(self, actual: int) -> None:
        """调用后记录实际消耗"""
        self.consumed += actual
        self.step_history.append(actual)

    def remaining(self) -> int:
        return self.total_budget - self.consumed
```

#### （2）与Agent Loop的集成

```python
def agent_loop(task, budget: TokenBudgetManager, llm, tools, max_steps=30):
    context = init_context(task)
    for step in range(max_steps):
        estimated = estimate_tokens(context) + 1000   # 预估 input+output
        try:
            budget.check_and_reserve(estimated)
        except BudgetExceeded:
            # 超预算：先尝试压缩上下文降级，再重试一次
            context = compact_context(context)
            estimated = estimate_tokens(context) + 1000
            try:
                budget.check_and_reserve(estimated)
            except BudgetExceeded:
                return finalize(context, reason="budget_exceeded")

        resp = llm.complete(context)
        budget.commit(resp.usage.total_tokens)   # 记录真实消耗

        if resp.is_final:
            return finalize(context)
        context = apply_tool_call(context, resp, tools)
    return finalize(context, reason="max_steps")
```

#### （3）实时Token消耗估算

调用前需要预估 token 数以做预算检查。粗略估算可用字符数/4（英文）或字符数（中文近似），精确估算用 tokenizer：

```python
def estimate_tokens(text: str, tokenizer=None) -> int:
    if tokenizer:
        return len(tokenizer.encode(text))
    # 粗略估算：中英文混合，经验系数
    chinese = sum(1 for c in text if '\u4e00' <= c <= '\u9fff')
    other = len(text) - chinese
    return int(chinese * 1.0 + other / 4)
```

---

## 五、上下文工程优化

### 5.1 上下文窗口管理

#### （1）滑动窗口 vs 摘要压缩 vs 结构化提取

| 策略 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| 滑动窗口 | 只保留最近 N 轮 | 简单、快 | 丢失早期信息 |
| 摘要压缩 | 把历史总结成短摘要 | 保留全局信息 | 需额外 LLM 调用、有信息损失 |
| 结构化提取 | 抽取关键实体/状态存结构化 | 精准、可检索 | 需设计 schema |

实践中常组合使用：近期用滑动窗口保原文，远期用摘要压缩，关键事实做结构化提取。

#### （2）工具结果的Token预算控制

工具返回（如读文件、搜索结果、API 响应）往往是 token 消耗大户。控制手段：

- **截断**：工具结果超过阈值就截断，只保留头尾。
- **摘要**：对超长结果先用小模型摘要再入上下文。
- **分页/引用**：结果存外部，上下文只放摘要+引用ID，需要时再取。

```python
def truncate_tool_result(result: str, max_tokens: int = 2000) -> str:
    tokens = estimate_tokens(result)
    if tokens <= max_tokens:
        return result
    # 保留头尾，中间省略
    head = result[: len(result) * max_tokens // tokens // 2]
    tail = result[- len(result) * max_tokens // tokens // 2:]
    return f"{head}\n\n...[已省略 {tokens - max_tokens} tokens]...\n\n{tail}"
```

#### （3）上下文压缩（Compaction）策略

Compaction 是把冗长历史"压实"的过程。触发条件通常是上下文达到窗口的某个比例（如 80%）。压缩时保留：

- 原始任务目标
- 关键决策与结论
- 未完成的待办
- 最近几轮原文

丢弃：中间的探索性对话、失败的尝试、冗余的工具输出。

#### （4）分层压缩Pipeline

```
原始上下文
   │
   ▼
[L1] 工具结果截断/摘要 ── 减少单条体积
   │
   ▼
[L2] 滑动窗口 ── 丢弃过旧的轮次
   │
   ▼
[L3] 历史摘要压缩 ── 把丢弃前的内容总结
   │
   ▼
[L4] 结构化状态提取 ── 关键事实存 KV，随时可查
   │
   ▼
精简上下文
```

### 5.2 减少不必要的LLM调用

#### （1）缓存工具调用结果

相同参数的工具调用（如查同一个文件、查同一个天气）可缓存结果，避免重复执行和重复入上下文。

```python
from functools import lru_cache
import hashlib, json

class ToolResultCache:
    def __init__(self, ttl=300):
        self.store = {}
        self.ttl = ttl

    def _key(self, tool_name, args):
        raw = tool_name + json.dumps(args, sort_keys=True)
        return hashlib.md5(raw.encode()).hexdigest()

    def get_or_call(self, tool_name, args, func):
        key = self._key(tool_name, args)
        now = time.time()
        if key in self.store:
            val, ts = self.store[key]
            if now - ts < self.ttl:
                return val   # 命中缓存，跳过真实调用
        val = func(**args)
        self.store[key] = (val, now)
        return val
```

#### （2）相似查询去重

用 embedding 相似度识别语义重复的查询，命中则复用历史答案。

#### （3）批量处理替代逐条处理

能一次处理多条就不要循环单条。比如要给 100 条数据打标签，不要循环调 100 次 LLM，而是一次 prompt 塞多条，让模型批量输出。

#### （4）规则前置：能用规则就不用LLM

最便宜的 LLM 调用是"不调用"。对于确定性的逻辑（格式校验、关键词匹配、简单分类），用规则/正则/传统 ML 兜底，只有规则搞不定才升级到 LLM。

---

## 六、Speculative Decoding与推理加速

### 6.1 Speculative Decoding原理

#### （1）小模型草拟 + 大模型验证

投机解码（Speculative Decoding）的核心思想：用一个小的"草稿模型"（draft model）快速生成多个候选 token，再用大模型**一次性并行验证**这些候选，接受能对齐的部分，拒绝并纠正不对齐的部分。

```
传统解码：大模型逐 token 生成，每个 token 一次前向 ── 慢
投机解码：
  1. draft 模型快速生成 k 个候选 token（便宜）
  2. 大模型并行验证这 k 个 token（一次前向验证多个）
  3. 接受匹配的前缀，第一个不匹配处纠正
  平均每次大模型前向能产出 >1 个 token ── 快
```

关键：**输出分布与纯大模型完全一致**（数学上可证明），所以是无损加速。

#### （2）对Agent延迟的优化效果

Agent 场景 decode 的 token 往往有大量"可预测"的部分（如 JSON 结构、固定格式的 tool_call、代码模板），draft 模型很容易猜中，接受率高，加速比可达 **2~3 倍**。对延迟敏感的交互式 Agent 收益明显。

#### （3）实现方案与框架支持

- **draft 模型选择**：同系列的小模型，或用 n-gram / 前缀匹配做无模型草拟（如 prompt lookup decoding）。
- **框架支持**：主流推理框架（如 vLLM、TensorRT-LLM）已内置投机解码支持。
- **Medusa / EAGLE**：在大模型上加多个预测头，无需独立 draft 模型。

### 6.2 其他推理加速技术

#### （1）Continuous Batching在Agent场景的应用

传统静态 batching 要等一个 batch 内所有请求都结束才能返回，长短请求混在一起时短请求被拖慢。Continuous Batching（连续批处理）在每个 decode step 动态换入换出请求，一个请求生成完立即返回、空槽立即补新请求，大幅提升吞吐。

Agent 场景请求长短差异极大（有的 1 步结束、有的循环 30 步），Continuous Batching 收益尤其明显。

#### （2）模型量化对Agent的影响

量化（INT8/INT4/FP8）降低显存和加速推理，但对 Agent 的影响需谨慎：

- Agent 依赖**精确的工具调用格式**和**多步推理连贯性**，激进量化可能导致 JSON 格式错误、推理跑偏。
- 建议：路由类小模型可激进量化；核心推理大模型用保守量化（如 FP8/W8A8）并做质量回归。

#### （3）vLLM/TensorRT-LLM的Agent场景优化

- **PagedAttention**（vLLM）：像操作系统分页一样管理 KV Cache，减少显存碎片，支持更大并发——对多会话 Agent 很关键。
- **Prefix Caching**：框架级前缀缓存，与 Prompt Caching 呼应，复用相同 System Prompt 的 KV。
- **Chunked Prefill**：把长 prompt 的 prefill 切块，与 decode 交错，降低长上下文 Agent 的首 token 延迟（TTFT）。

#### （4）多模态Agent的特殊加速考虑

- 图像/视频 token 数巨大，编码器（vision encoder）是瓶颈，可做 encoder 结果缓存。
- 图像 token 可做压缩/降采样，在质量可接受范围内减少 token 数。
- 视觉与语言模块可拆分部署、独立扩缩容。

---

## 七、Batch Processing在Agent场景的特殊性

### 7.1 Agent的批量处理挑战

1. **非确定性导致难以批量**：同一批任务走的路径可能完全不同，无法像传统 batch 那样对齐。
2. **不同任务路径不同**：任务A调 3 个工具，任务B调 10 个工具，步数不齐。
3. **工具调用的串行依赖**：第二步依赖第一步结果，无法提前批量。

### 7.2 批量优化策略

#### （1）同类任务批量推理

把处于"相同阶段、相同模型"的多个 Agent 的 LLM 调用聚到一起送给推理引擎，靠 Continuous Batching 提升吞吐。

#### （2）工具调用的批量聚合

同一步内 Agent 若要并行调多个独立工具（如同时查 3 个 API），批量并发执行而非串行。

#### （3）异步流式处理

用异步事件循环管理大量并发 Agent，每个 Agent 是一个协程，I/O（LLM 调用、工具调用）时让出，最大化并发利用率。

#### （4）代码示例：批量Agent任务处理器

```python
import asyncio

class BatchAgentProcessor:
    def __init__(self, gateway, max_concurrency=50):
        self.gateway = gateway
        self.sem = asyncio.Semaphore(max_concurrency)

    async def _run_one(self, task):
        async with self.sem:                # 限制并发，保护后端
            try:
                return await self._agent_loop(task)
            except Exception as e:
                return {"task_id": task["id"], "error": str(e)}

    async def _agent_loop(self, task):
        context = init_context(task)
        for _ in range(30):
            resp = await self.gateway.complete(context)   # 异步，I/O 时让出
            if resp.is_final:
                return {"task_id": task["id"], "result": resp.text}
            # 并行执行本步的多个工具调用
            tool_calls = resp.tool_calls
            results = await asyncio.gather(
                *[self._exec_tool(tc) for tc in tool_calls]
            )
            context = merge_results(context, results)
        return {"task_id": task["id"], "result": "max_steps"}

    async def _exec_tool(self, tc):
        return await run_tool_async(tc.name, tc.args)

    async def process_batch(self, tasks: list):
        return await asyncio.gather(*[self._run_one(t) for t in tasks])
```

---

## 八、企业级成本优化实践

### 8.1 成本监控体系

#### （1）按维度的Token消耗可视化

必须能按多维度切分 token 消耗，才能定位成本大头：

- 按**业务线/租户**：哪个业务最烧钱
- 按**模型**：大小模型消耗占比
- 按**用户**：是否有异常用户
- 按**任务类型**：哪类任务成本高
- 按 **input/output/cache**：cache 命中率如何

```python
def record_usage(trace_id, biz_line, model, usage):
    metrics.emit("llm.tokens", tags={
        "biz": biz_line, "model": model,
        "type": "input"}, value=usage.input_tokens)
    metrics.emit("llm.tokens", tags={
        "biz": biz_line, "model": model,
        "type": "output"}, value=usage.output_tokens)
    metrics.emit("llm.tokens", tags={
        "biz": biz_line, "model": model,
        "type": "cache_read"}, value=usage.cache_read_tokens)
    metrics.emit("llm.cost", tags={"biz": biz_line},
                 value=compute_cost(model, usage))
```

#### （2）成本异常检测与告警

- **突增告警**：某业务线 token 消耗环比突增（如 3 倍）触发告警。
- **死循环检测**：单任务步数/token 超阈值告警，可能是 Agent 陷入循环。
- **缓存命中率下降**：命中率跌破阈值（如 50%）告警，可能是 Prompt 结构被改坏。

#### （3）成本归因到业务线

每次 LLM 调用打上 `trace_id + biz_line + user_id` 标签，账单能精确归因到业务线，支持内部成本分摊和 ROI 核算。

### 8.2 成本优化案例

#### 案例一：某互联网公司通过模型路由降低60%成本（脱敏）

背景：某内部通用助手 Agent，此前全部请求走大模型。分析发现 65% 的请求是简单 FAQ/闲聊/格式化。

措施：上线"规则路由 + 级联"网关，简单请求走小模型，质量不达标才升级。

结果：整体成本降低约 **60%**，任务成功率仅下降 0.8%（在可接受范围），P50 延迟因小模型更快反而降低 30%。

#### 案例二：某客服Agent通过Prompt Caching降低40%成本（脱敏）

背景：某客服 Agent 的 System Prompt + 知识库前缀 + 工具定义约 12000 tokens，每次请求重复计费。

措施：重构 Prompt 结构，把 12000 tokens 静态前缀打上缓存断点，动态部分（用户信息、时间）挪到后缀；配合高峰前缓存预热。

结果：前缀缓存命中率达 **85%**，input 成本降低约 **40%**，首 token 延迟（TTFT）下降约 50%。

#### 案例三：某代码助手通过上下文工程降低50%成本（脱敏）

背景：某代码助手 Agent 上下文无限增长，长会话单次 input 超 15 万 tokens。

措施：引入分层压缩 Pipeline——工具结果截断、滑动窗口、历史摘要、结构化状态提取；工具结果缓存去重。

结果：长会话平均上下文体积下降约 **55%**，整体成本降低约 **50%**，且因上下文更精简，任务成功率略有提升。

### 8.3 成本优化的权衡

#### （1）成本 vs 质量：什么时候不能省

- **高价值/高风险任务**（金融、医疗、法律、生产代码）：宁可多花钱用大模型，不能为省钱牺牲正确性。
- **合规敏感任务**：错误代价远超模型成本。
- 原则：**先保质量红线，再谈降本**。

#### （2）成本 vs 延迟：延迟敏感场景的取舍

- 交互式场景（用户等待）：延迟优先，用小模型+缓存+投机解码。
- 离线批处理：成本优先，用 Batch API（通常有折扣）、错峰跑。

#### （3）成本 vs 可靠性：降级策略的安全边界

- 降级到小模型必须有质量下限，不能无限降级。
- Fallback 链要有熔断，避免雪崩。
- 关键任务禁用激进降级，宁可排队等主模型。

---

## 九、面试高频问题与参考答案

**Q1：为什么 Agent 的成本比普通 LLM 调用高一个数量级？**

答：因为 Agent 是多轮循环+工具调用+上下文累积的系统。一次任务可能触发数十次 LLM 调用，且上下文随步数线性增长，导致总 token 消耗随步数近似二次方增长（雪球效应）。核心公式：`任务成本 = Σ每步调用成本`，而每步的 input 都包含之前全部历史。

**Q2：模型路由的几种策略及各自优缺点？**

答：①规则路由（快、零成本、但覆盖不全）；②ML 路由（准、需训练数据）；③LLM 路由（灵活、但要额外一次调用）；④级联路由（小模型先试不行再升级，实用但要监控升级率）。生产上常规则+ML 融合、配合级联兜底。

**Q3：级联路由什么时候反而不划算？如何判断？**

答：级联成本 = 小模型成本 + 升级率×大模型成本。当升级率过高（如 >60%），相当于大部分请求付了两次费用，反而更贵且延迟翻倍。必须监控升级率，动态调整质量校验阈值，升级率过高时说明小模型不胜任该类任务，应调整路由规则。

**Q4：Prompt Caching 和 KV Cache 有什么区别？**

答：KV Cache 是单次请求内自回归解码时缓存历史 token 的 K/V，避免重复计算，生命周期是一次生成；Prompt Caching 是跨请求缓存相同前缀的 prefill 结果，生命周期分钟级，有独立的 write/read 计费。前者是解码内部优化，后者是跨请求降本。

**Q5：Prompt Caching 命中的核心条件是什么？如何设计 Prompt 最大化命中率？**

答：核心条件是**前缀 token 完全一致**，一个 token 不同则从该处起全部 miss。设计原则：静态内容（系统指令、工具定义）放最前面并固定排序，动态内容（时间、用户ID、随机数）放最后面；在稳定边界打缓存断点（splitSysPromptPrefix）；避免上下文压缩频繁破坏前缀。

**Q6：Cache Write 比普通 input 还贵，为什么还要用缓存？**

答：Write 约 1.25x，但 Read 只要 0.1x。盈亏平衡点是复用超过约 0.28 次，即只要前缀被复用哪怕 1 次以上就净赚。Agent 的 System Prompt/工具定义在会话内会被反复复用几十次，缓存收益巨大。

**Q7：Token Budget 如何设计？超预算怎么办？**

答：全局预算做硬顶 + 分步预算做软约束，按任务复杂度动态调整（complexity_factor）。超预算处理有四种：降级（切小模型）、截断（压缩上下文）、中止（返回已有结果）、转人工。调用前用 tokenizer 预估并 reserve，调用后 commit 真实消耗。

**Q8：Agent 上下文无限增长怎么办？有哪些压缩策略？**

答：分层压缩 Pipeline：①工具结果截断/摘要；②滑动窗口丢弃过旧轮次；③历史摘要压缩；④结构化状态提取（关键事实存 KV）。同时缓存工具结果去重、规则前置减少调用。注意压缩会破坏缓存前缀，要对齐压缩点并压缩后预热。

**Q9：Speculative Decoding 为什么能加速且无损？在 Agent 场景效果如何？**

答：小 draft 模型快速草拟 k 个候选 token，大模型一次前向并行验证，接受对齐前缀、在首个不匹配处纠正。数学上可证明输出分布与纯大模型一致，故无损。Agent 输出含大量可预测结构（JSON、tool_call、代码模板），draft 接受率高，加速比可达 2~3 倍。

**Q10：设计一个企业级 Agent 成本监控体系需要哪些要素？**

答：①多维度 token/成本埋点（按业务线、模型、用户、任务类型、input/output/cache 切分）；②异常告警（消耗突增、死循环步数超限、缓存命中率下降）；③成本归因（trace_id+biz_line 标签，支持成本分摊）；④A/B 实验框架（新策略上线守护成本降、质量不降）。

**Q11：Continuous Batching 为什么特别适合 Agent 场景？**

答：Agent 请求长短差异极大（1 步到 30 步不等），静态 batching 会被最长请求拖慢。Continuous Batching 在每个 decode step 动态换入换出请求，完成即返回、空槽即补新请求，大幅提升吞吐和 GPU 利用率。

**Q12：多租户 Agent 平台的缓存如何隔离？**

答：按租户分 namespace，不同租户即使前缀内容相同也不共享（防数据泄露）；平台级公共 System Prompt 抽成共享缓存段，租户私有部分放后缀单独缓存；给每租户设缓存配额，防止挤占。

---

## 十、总结

### 10.1 Agent成本优化的核心原则

1. **不是所有请求都值得最强模型**——模型路由是性价比最高的降本手段。
2. **静态前缀要缓存**——Prompt Caching 让重复的 System Prompt/工具定义几乎免费。
3. **上下文要精简**——分层压缩控制雪球效应，工具结果要预算约束。
4. **消耗要有上限**——Token Budget 防止单任务失控。
5. **能不调用就不调用**——规则前置、结果缓存、批量处理减少 LLM 调用次数。
6. **先守质量红线，再谈降本**——高价值任务不能为省钱牺牲正确性。
7. **一切可度量**——没有监控就没有优化，成本必须可视化、可归因、可告警。

### 10.2 成本优化的天花板在哪里

成本优化不是无止境的，天花板由几个因素决定：

- **任务本身的复杂度**：真正复杂的任务（多步推理、长代码）必须消耗足够 token，压无可压。
- **质量红线**：降本以不损害核心质量为前提，越过红线的"优化"是伪优化。
- **模型能力边界**：小模型能替代大模型的比例，取决于小模型本身的能力上限，会随模型进步而上移。
- **边际收益递减**：前 60% 的降本可能靠路由+缓存轻松拿到，后 20% 需要精细上下文工程和大量工程投入，ROI 递减。

**最终结论**：Agent 成本优化的本质是"用合适的资源做合适的事"——在成本、质量、速度的三难困境中，根据业务场景找到最优平衡点，通过模型路由、Prompt Caching、Token Budget、上下文工程、推理加速这五大支柱的组合拳，在保住质量红线的前提下把成本压到合理区间。工程上追求的不是极致省钱，而是**成本可控、可预测、可持续**。

---

## 附录：知识融合——构建企业级Agent成本优化与性能调优系统

> 本章将前文所有成本优化和性能调优知识整合为一个完整的、可落地的企业级系统。从系统目标出发，自顶向下地给出分层架构、每层的详细设计与代码示例、端到端的数据流、决策框架、企业治理规范以及演进路线。所有企业内部信息均已脱敏，以"某互联网公司""某Agent平台"等通用描述替代。

---

### 一、系统目标与设计原则

#### 1.1 核心目标

企业级 Agent 成本优化系统的核心目标可以用一句话概括：

> **在保证 Agent 输出质量不低于业务红线的前提下，最小化 Agent 运行的综合成本（Token 费用 + 推理资源 + 工程维护成本），并使成本可预测、可归因、可持续。**

具体拆解为四个子目标：

1. **降本**：通过模型路由、缓存复用、上下文压缩等手段，将单次 Agent 任务的平均 Token 消耗降低 40%~70%。
2. **控量**：通过 Token Budget 和预算分配器，防止单个任务、单个用户、单个业务线的成本失控。
3. **保质**：所有优化必须守住质量红线，关键任务（如代码生成、数据分析）的正确率不因降本而下降。
4. **可观测**：每一分钱的 Token 消耗都可追溯到具体的用户、任务、Agent、模型和步骤。

#### 1.2 四大设计原则

**原则一：成本可观测（Observability First）**

没有度量就没有优化。系统的第一层能力是让成本"看得见"：
- 每次 LLM 调用必须记录 input_tokens、output_tokens、cache_hit_tokens、model_name、latency。
- 成本可按多维度切片：业务线 → Agent 类型 → 用户 → 单次任务 → 单步调用。
- 异常消耗必须能在分钟级被告警捕获。

**原则二：模型可路由（Model Routing）**

不是所有请求都值得用最贵的模型：
- 简单任务（分类、提取、格式转换）路由到轻量模型，复杂任务（推理、创作、代码）路由到旗舰模型。
- 路由决策本身不能成为性能瓶颈。
- 路由策略可在线 A/B 测试、可回滚。

**原则三：上下文可压缩（Context Engineering）**

Agent 成本的雪球效应来自上下文累积：
- 每一轮的历史信息必须经过压缩、摘要、结构化提取。
- 工具返回结果必须有 Token 预算，超出预算的结果要截断或摘要。
- Prompt 结构设计要对缓存友好——静态前缀在前，动态内容在后。

**原则四：预算可控制（Budget Control）**

成本必须有"刹车"：
- 全局 Token 预算按业务线、团队、用户逐级分配。
- 单个 Agent 任务有最大 Token 消耗上限。
- 超预算时自动触发降级策略（切小模型、截断上下文、转人工）。

#### 1.3 成本-质量-速度三难困境的权衡框架

在 Agent 场景中，成本（Cost）、质量（Quality）、速度（Latency）三者天然存在矛盾：

```
                    Quality
                      ▲
                     / \
                    /   \
                   /     \
                  / 理想区 \
                 /         \
                /           \
    Cost ◄─────────────────────► Speed
```

- **追求质量**：用旗舰模型、长上下文、多轮反思 → 成本高、速度慢。
- **追求速度**：用小模型、短上下文、减少循环 → 质量可能下降。
- **追求低成本**：用缓存、路由到小模型、压缩上下文 → 要同时守住质量和速度。

权衡框架的核心思想：**根据任务的业务价值决定资源投入**。

| 任务类型 | 业务价值 | 成本投入策略 | 典型场景 |
|---------|---------|------------|---------|
| 关键决策类 | 极高 | 不惜成本保质量 | 代码审查、安全分析、交易决策 |
| 生产力工具类 | 高 | 适度优化，守住质量 | 代码补全、文档生成、数据分析 |
| 信息查询类 | 中 | 积极优化，缓存优先 | FAQ问答、知识检索 |
| 批量处理类 | 中低 | 大力优化，批量+路由 | 数据标注、分类、格式转换 |

---

### 二、整体架构总览

#### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          用户请求 / Agent 任务入口                           │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   Layer 1: 监控分析层 (Observability)                  │  │
│  │  成本埋点 │ 多维看板 │ 异常告警 │ 优化建议引擎 │ ROI 分析               │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   Layer 2: 预算控制层 (Budget Control)                 │  │
│  │  全局预算分配 │ 分步预算 │ Token 消耗追踪 │ 超预算降级                    │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   Layer 3: 上下文工程层 (Context Engineering)          │  │
│  │  上下文压缩 │ 工具结果预算 │ Prompt 结构优化 │ 历史摘要                   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   Layer 4: 缓存优化层 (Cache Optimization)            │  │
│  │  Prompt Caching │ 工具结果缓存 │ 语义缓存 │ 缓存预热与失效                │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                   Layer 5: 模型路由层 (Model Routing)                  │  │
│  │  路由决策引擎 │ 模型供应商管理 │ Fallback │ A/B 测试                      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│              LLM Provider (OpenAI / Anthropic / 自部署 vLLM 等)             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2.2 各层职责一句话概括

| 层级 | 职责 |
|------|------|
| 监控分析层 | 让每一分钱的 Token 消耗看得见、查得到、算得清。 |
| 预算控制层 | 给每个任务、每个团队、每个用户设定成本上限，超限自动降级。 |
| 上下文工程层 | 控制上下文的雪球效应，用最少的 Token 承载最多的信息。 |
| 缓存优化层 | 让重复的计算不重复付费，通过精确/语义缓存复用 LLM 结果。 |
| 模型路由层 | 把合适的请求交给合适的模型，简单任务不浪费算力。 |

---

### 三、各层详细设计

#### 3.1 模型路由层

##### 3.1.1 路由决策引擎

路由决策引擎负责在 Agent 每一步 LLM 调用前，决定将这次调用发送给哪个模型。路由策略从简单到复杂分为三个级别：

**Level 1：基于规则的路由**

最直观、最可控。根据请求的显式特征做路由判断：

```python
class RuleBasedRouter:
    """基于规则的模型路由器"""
    
    def __init__(self, config: dict):
        self.rules = config.get("rules", [])
    
    def route(self, request: dict) -> str:
        task_type = request.get("task_type", "")
        token_estimate = request.get("estimated_tokens", 0)
        priority = request.get("priority", "normal")
        
        # 规则1：高优先级任务 → 旗舰模型
        if priority == "critical":
            return "gpt-4o"
        
        # 规则2：简单分类/提取任务 → 轻量模型
        if task_type in ("classification", "extraction", "format_conversion"):
            return "gpt-4o-mini"
        
        # 规则3：代码生成/推理任务 → 旗舰模型
        if task_type in ("code_generation", "reasoning", "planning"):
            return "gpt-4o"
        
        # 规则4：输入超长 → 成本敏感，用轻量模型 + 上下文压缩
        if token_estimate > 50000:
            return "gpt-4o-mini"
        
        # 默认
        return "gpt-4o"
```

**Level 2：基于 ML 分类器的路由**

训练一个轻量分类器（如 DistilBERT），输入用户 query 的 embedding，输出路由到哪个模型：

```python
class MLRouter:
    """基于机器学习的模型路由器"""
    
    def __init__(self, classifier_path: str):
        self.classifier = self._load_classifier(classifier_path)
        self.embedding_model = SentenceTransformer("all-MiniLM-L6-v2")
    
    def _load_classifier(self, path: str):
        import joblib
        return joblib.load(path)
    
    def route(self, request: dict) -> str:
        query = request.get("user_query", "")
        embedding = self.embedding_model.encode([query])
        
        # 分类器输出：0=轻量模型，1=标准模型，2=旗舰模型
        prediction = self.classifier.predict(embedding)[0]
        confidence = max(self.classifier.predict_proba(embedding)[0])
        
        model_map = {0: "gpt-4o-mini", 1: "gpt-4o", 2: "o1-preview"}
        
        # 置信度不足时保守选择更强模型
        if confidence < 0.7:
            prediction = min(prediction + 1, 2)
        
        return model_map[prediction]
```

**Level 3：基于 LLM 的元路由**

用一个轻量 LLM 来判断任务复杂度，然后决定路由。这种方式最灵活但有额外调用成本：

```python
class LLMMetaRouter:
    """使用轻量LLM做路由决策"""
    
    ROUTING_PROMPT = """你是一个任务复杂度判断器。根据以下用户请求，判断任务复杂度。
    
输出JSON格式：{"complexity": "simple|medium|complex", "reason": "一句话原因"}

用户请求：{query}"""
    
    async def route(self, request: dict) -> str:
        query = request.get("user_query", "")
        
        # 用最轻量的模型做路由决策（成本极低）
        response = await llm_call(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": self.ROUTING_PROMPT.format(query=query)}],
            max_tokens=100
        )
        
        result = json.loads(response)
        complexity_to_model = {
            "simple": "gpt-4o-mini",
            "medium": "gpt-4o",
            "complex": "o1-preview"
        }
        return complexity_to_model.get(result["complexity"], "gpt-4o")
```

##### 3.1.2 模型供应商管理

企业级系统需要同时对接多个模型供应商，并具备健康检查和自动 Fallback 的能力：

```python
class ModelProviderManager:
    """模型供应商管理器"""
    
    def __init__(self, providers_config: list[dict]):
        self.providers = {}
        for cfg in providers_config:
            self.providers[cfg["name"]] = ProviderClient(
                name=cfg["name"],
                api_key=cfg["api_key"],
                base_url=cfg["base_url"],
                models=cfg["models"],
                rate_limit=cfg.get("rate_limit", 1000),
                timeout=cfg.get("timeout", 30),
            )
        self.health_status: dict[str, bool] = {name: True for name in self.providers}
        self.fallback_chain: dict[str, list[str]] = {}  # model -> fallback models
    
    def register_fallback(self, model: str, fallbacks: list[str]):
        """注册模型的fallback链"""
        self.fallback_chain[model] = fallbacks
    
    async def call(self, model: str, messages: list, **kwargs) -> dict:
        """调用模型，自动fallback"""
        models_to_try = [model] + self.fallback_chain.get(model, [])
        
        last_error = None
        for m in models_to_try:
            provider = self._find_provider_for_model(m)
            if not provider or not self.health_status.get(provider.name, False):
                continue
            try:
                result = await provider.chat_completion(m, messages, **kwargs)
                return result
            except RateLimitError:
                logger.warning(f"Rate limited on {provider.name}/{m}, trying next")
                last_error = f"Rate limited: {provider.name}/{m}"
                continue
            except TimeoutError:
                logger.warning(f"Timeout on {provider.name}/{m}, trying next")
                self._mark_unhealthy(provider.name)
                last_error = f"Timeout: {provider.name}/{m}"
                continue
            except Exception as e:
                logger.error(f"Error on {provider.name}/{m}: {e}")
                last_error = str(e)
                continue
        
        raise AllProvidersFailedError(f"All models failed. Last error: {last_error}")
    
    def _find_provider_for_model(self, model: str):
        for provider in self.providers.values():
            if model in provider.models:
                return provider
        return None
    
    def _mark_unhealthy(self, provider_name: str):
        self.health_status[provider_name] = False
        # 启动后台健康检查任务，恢复后自动标记 healthy
        asyncio.create_task(self._health_check_loop(provider_name))
    
    async def _health_check_loop(self, provider_name: str):
        """后台健康检查，每30秒探测一次"""
        while not self.health_status[provider_name]:
            await asyncio.sleep(30)
            try:
                await self.providers[provider_name].ping()
                self.health_status[provider_name] = True
                logger.info(f"Provider {provider_name} recovered")
            except Exception:
                pass
```

##### 3.1.3 路由策略 A/B 测试框架

路由策略的效果需要通过 A/B 测试验证。框架核心是将一定比例的流量路由到实验组策略：

```python
class RoutingABTest:
    """路由策略A/B测试"""
    
    def __init__(self, experiment_config: dict):
        self.experiment_id = experiment_config["id"]
        self.control_router = experiment_config["control_router"]
        self.experiment_router = experiment_config["experiment_router"]
        self.traffic_ratio = experiment_config.get("traffic_ratio", 0.1)  # 10%实验流量
        self.metrics_collector = MetricsCollector()
    
    def route(self, request: dict) -> tuple[str, str]:
        """返回 (model_name, experiment_group)"""
        # 根据用户ID做确定性分桶，保证同一用户始终在同一组
        user_id = request.get("user_id", "")
        bucket = hash(user_id + self.experiment_id) % 100
        
        if bucket < self.traffic_ratio * 100:
            group = "experiment"
            model = self.experiment_router.route(request)
        else:
            group = "control"
            model = self.control_router.route(request)
        
        self.metrics_collector.record(
            experiment_id=self.experiment_id,
            group=group,
            model=model,
            request_id=request.get("request_id"),
        )
        return model, group
    
    def get_results(self) -> dict:
        """获取实验结果：成本对比、质量对比、延迟对比"""
        control_metrics = self.metrics_collector.aggregate(self.experiment_id, "control")
        experiment_metrics = self.metrics_collector.aggregate(self.experiment_id, "experiment")
        return {
            "cost_reduction": 1 - experiment_metrics["avg_cost"] / control_metrics["avg_cost"],
            "quality_delta": experiment_metrics["avg_quality"] - control_metrics["avg_quality"],
            "latency_delta": experiment_metrics["avg_latency"] - control_metrics["avg_latency"],
            "sample_size": {
                "control": control_metrics["count"],
                "experiment": experiment_metrics["count"]
            }
        }
```

#### 3.2 缓存优化层

##### 3.2.1 Prompt Caching 管理器

Prompt Caching 的关键在于缓存键设计和命中率监控。其核心思想是：将 Prompt 的**静态前缀**（System Prompt + 工具定义）与**动态后缀**（用户消息 + 历史）分离，静态前缀命中缓存可省约 90% 的输入 Token 成本。

```python
class PromptCacheManager:
    """Prompt缓存管理器"""
    
    def __init__(self, config: dict):
        self.cache_stats = {"hits": 0, "misses": 0, "saved_tokens": 0}
        self.prefix_registry: dict[str, dict] = {}  # agent_type -> prefix_info
    
    def register_prefix(self, agent_type: str, system_prompt: str, tool_definitions: list):
        """注册Agent的静态前缀，供缓存复用"""
        prefix_content = self._build_prefix(system_prompt, tool_definitions)
        prefix_hash = hashlib.sha256(prefix_content.encode()).hexdigest()
        token_count = self._count_tokens(prefix_content)
        
        self.prefix_registry[agent_type] = {
            "content": prefix_content,
            "hash": prefix_hash,
            "token_count": token_count,
            "registered_at": time.time()
        }
        logger.info(f"Registered prefix for {agent_type}: {token_count} tokens, hash={prefix_hash[:12]}")
    
    def build_cache_friendly_messages(self, agent_type: str, dynamic_messages: list) -> list:
        """构建缓存友好的消息结构：静态前缀 + 动态后缀"""
        prefix_info = self.prefix_registry.get(agent_type)
        if not prefix_info:
            return dynamic_messages
        
        # 静态前缀标记为可缓存
        cached_system_msg = {
            "role": "system",
            "content": prefix_info["content"],
            "cache_control": {"type": "ephemeral"}  # Anthropic格式
        }
        
        return [cached_system_msg] + dynamic_messages
    
    def record_cache_result(self, response_usage: dict):
        """记录缓存命中情况"""
        cached_tokens = response_usage.get("cache_creation_input_tokens", 0) + \
                         response_usage.get("cache_read_input_tokens", 0)
        
        if response_usage.get("cache_read_input_tokens", 0) > 0:
            self.cache_stats["hits"] += 1
            self.cache_stats["saved_tokens"] += response_usage["cache_read_input_tokens"]
        else:
            self.cache_stats["misses"] += 1
    
    @property
    def hit_rate(self) -> float:
        total = self.cache_stats["hits"] + self.cache_stats["misses"]
        return self.cache_stats["hits"] / total if total > 0 else 0.0
    
    def get_stats_report(self) -> dict:
        return {
            "hit_rate": f"{self.hit_rate:.1%}",
            "total_saved_tokens": self.cache_stats["saved_tokens"],
            "estimated_cost_saved": self.cache_stats["saved_tokens"] * 0.0000025  # 估算
        }
```

##### 3.2.2 工具结果缓存

Agent 在运行过程中，同一工具可能被重复调用（比如多次读取同一文件、多次查询同一接口）。工具结果缓存可以跳过重复执行：

```python
class ToolResultCache:
    """工具结果缓存"""
    
    def __init__(self, max_size: int = 1000, default_ttl: int = 300):
        self.cache: dict[str, dict] = {}
        self.max_size = max_size
        self.default_ttl = default_ttl
        self.stats = {"hits": 0, "misses": 0}
    
    def _make_key(self, tool_name: str, arguments: dict) -> str:
        """生成缓存键：工具名 + 参数的确定性哈希"""
        args_str = json.dumps(arguments, sort_keys=True)
        return hashlib.sha256(f"{tool_name}:{args_str}".encode()).hexdigest()
    
    def get(self, tool_name: str, arguments: dict) -> Optional[dict]:
        """尝试从缓存获取工具结果"""
        key = self._make_key(tool_name, arguments)
        entry = self.cache.get(key)
        
        if entry and time.time() - entry["timestamp"] < entry["ttl"]:
            self.stats["hits"] += 1
            return entry["result"]
        
        if entry:
            del self.cache[key]  # 过期清理
        
        self.stats["misses"] += 1
        return None
    
    def put(self, tool_name: str, arguments: dict, result: dict, ttl: Optional[int] = None):
        """缓存工具结果"""
        if len(self.cache) >= self.max_size:
            self._evict_oldest()
        
        key = self._make_key(tool_name, arguments)
        self.cache[key] = {
            "result": result,
            "timestamp": time.time(),
            "ttl": ttl or self._get_tool_ttl(tool_name),
            "tool_name": tool_name,
        }
    
    def _get_tool_ttl(self, tool_name: str) -> int:
        """不同工具有不同的缓存过期时间"""
        tool_ttl_map = {
            "read_file": 60,         # 文件内容短时间内不变
            "search_code": 60,       # 代码搜索结果短时间内不变
            "web_search": 300,       # 网页搜索结果缓存5分钟
            "get_weather": 600,      # 天气信息缓存10分钟
            "database_query": 30,    # 数据库查询短缓存
        }
        return tool_ttl_map.get(tool_name, self.default_ttl)
    
    def _evict_oldest(self):
        """驱逐最旧的缓存条目"""
        oldest_key = min(self.cache, key=lambda k: self.cache[k]["timestamp"])
        del self.cache[oldest_key]
```

##### 3.2.3 语义缓存

精确缓存要求参数完全一致，但用户的提问往往有语义相似但表述不同的情况。语义缓存通过 Embedding 相似度匹配来复用结果：

```python
class SemanticCache:
    """基于语义相似度的缓存"""
    
    def __init__(self, embedding_model: str = "text-embedding-3-small",
                 similarity_threshold: float = 0.95,
                 max_entries: int = 5000):
        self.embedding_model = embedding_model
        self.similarity_threshold = similarity_threshold
        self.max_entries = max_entries
        self.entries: list[dict] = []  # [{embedding, query, response, timestamp}]
    
    async def get(self, query: str) -> Optional[str]:
        """语义匹配查询缓存"""
        if not self.entries:
            return None
        
        query_embedding = await self._get_embedding(query)
        
        best_match = None
        best_similarity = 0.0
        
        for entry in self.entries:
            similarity = self._cosine_similarity(query_embedding, entry["embedding"])
            if similarity > best_similarity:
                best_similarity = similarity
                best_match = entry
        
        if best_match and best_similarity >= self.similarity_threshold:
            logger.info(f"Semantic cache hit: similarity={best_similarity:.4f}")
            return best_match["response"]
        
        return None
    
    async def put(self, query: str, response: str):
        """存入语义缓存"""
        if len(self.entries) >= self.max_entries:
            # 移除最旧的10%条目
            self.entries = sorted(self.entries, key=lambda e: e["timestamp"])
            self.entries = self.entries[len(self.entries) // 10:]
        
        embedding = await self._get_embedding(query)
        self.entries.append({
            "embedding": embedding,
            "query": query,
            "response": response,
            "timestamp": time.time()
        })
    
    def _cosine_similarity(self, a: list[float], b: list[float]) -> float:
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = sum(x * x for x in a) ** 0.5
        norm_b = sum(x * x for x in b) ** 0.5
        return dot / (norm_a * norm_b) if norm_a and norm_b else 0.0
    
    async def _get_embedding(self, text: str) -> list[float]:
        result = await embedding_call(model=self.embedding_model, input=text)
        return result["data"][0]["embedding"]
```

##### 3.2.4 缓存预热与失效策略

```python
class CacheWarmupManager:
    """缓存预热管理器"""
    
    async def warmup_prompt_cache(self, agent_types: list[str], cache_manager: PromptCacheManager):
        """在服务启动时，预热高频Agent的Prompt缓存"""
        for agent_type in agent_types:
            prefix_info = cache_manager.prefix_registry.get(agent_type)
            if prefix_info and prefix_info["token_count"] >= 1024:
                # 发一个dummy请求触发缓存写入
                await llm_call(
                    model="gpt-4o",
                    messages=cache_manager.build_cache_friendly_messages(agent_type, [
                        {"role": "user", "content": "ping"}
                    ]),
                    max_tokens=1
                )
                logger.info(f"Cache warmed up for {agent_type}")
    
    def should_invalidate(self, cache_entry: dict, event: dict) -> bool:
        """判断缓存是否应该失效"""
        # 工具定义变更 → 清除对应Agent的Prompt缓存
        if event["type"] == "tool_definition_changed":
            return cache_entry.get("agent_type") == event["agent_type"]
        # 底层数据变更 → 清除工具结果缓存
        if event["type"] == "data_changed":
            return cache_entry.get("tool_name") in event.get("affected_tools", [])
        return False
```

#### 3.3 上下文工程层

##### 3.3.1 上下文压缩 Pipeline

上下文压缩是对抗 Agent 成本雪球效应的核心手段。采用三级压缩管线：

```
原始上下文 → [滑动窗口截断] → [历史摘要] → [结构化提取] → 压缩上下文
```

```python
class ContextCompressionPipeline:
    """三级上下文压缩管线"""
    
    def __init__(self, config: dict):
        self.max_context_tokens = config.get("max_context_tokens", 32000)
        self.window_size = config.get("window_size", 10)  # 保留最近N轮
        self.summary_threshold = config.get("summary_threshold", 20000)  # 超过此值触发摘要
    
    async def compress(self, messages: list[dict], current_tokens: int) -> list[dict]:
        """三级压缩"""
        # Level 1: 滑动窗口 —— 只保留最近N轮对话
        if len(messages) > self.window_size * 2:
            system_msgs = [m for m in messages if m["role"] == "system"]
            recent_msgs = messages[-self.window_size * 2:]
            old_msgs = messages[len(system_msgs):-self.window_size * 2]
            
            # Level 2: 对窗口外的旧消息做摘要
            if old_msgs:
                summary = await self._summarize(old_msgs)
                summary_msg = {"role": "system", "content": f"[历史摘要] {summary}"}
                messages = system_msgs + [summary_msg] + recent_msgs
        
        # Level 3: 如果仍然超限，结构化提取关键信息
        current_tokens = self._count_tokens(messages)
        if current_tokens > self.max_context_tokens:
            messages = await self._structural_extract(messages)
        
        return messages
    
    async def _summarize(self, messages: list[dict]) -> str:
        """用LLM对历史消息做摘要"""
        summary_prompt = """请将以下对话历史压缩为简洁摘要，保留：
1. 用户的原始意图
2. 已经完成的步骤和关键结果
3. 遇到的错误和解决方案
4. 当前的状态和待办事项

对话历史：
{history}

输出简洁摘要（不超过500字）："""
        
        history_text = "\n".join([f"{m['role']}: {m['content'][:200]}" for m in messages])
        response = await llm_call(
            model="gpt-4o-mini",  # 用小模型做摘要，降低成本
            messages=[{"role": "user", "content": summary_prompt.format(history=history_text)}],
            max_tokens=800
        )
        return response
    
    async def _structural_extract(self, messages: list[dict]) -> list[dict]:
        """结构化提取：从长消息中提取关键信息"""
        compressed = []
        for msg in messages:
            if msg["role"] == "system":
                compressed.append(msg)
                continue
            
            content = msg.get("content", "")
            # 工具结果超过2000字符时截断并提取关键信息
            if msg["role"] == "tool" and len(content) > 2000:
                content = content[:1500] + "\n...[已截断，共" + str(len(content)) + "字符]"
            
            compressed.append({**msg, "content": content})
        
        return compressed
    
    def _count_tokens(self, messages: list[dict]) -> int:
        return sum(len(m.get("content", "")) // 3 for m in messages)  # 粗略估算
```

##### 3.3.2 工具结果 Token 预算控制

工具返回的结果（如文件内容、搜索结果）往往非常长，是上下文膨胀的主要来源。必须对工具结果做 Token 预算：

```python
class ToolResultBudget:
    """工具结果Token预算控制"""
    
    # 每种工具的默认Token预算
    DEFAULT_BUDGETS = {
        "read_file": 3000,
        "search_code": 2000,
        "web_search": 1500,
        "database_query": 2000,
        "list_directory": 1000,
    }
    
    def truncate_result(self, tool_name: str, result: str, budget: Optional[int] = None) -> str:
        """将工具结果截断到Token预算内"""
        max_tokens = budget or self.DEFAULT_BUDGETS.get(tool_name, 2000)
        max_chars = max_tokens * 3  # 粗略估算：1 token ≈ 3 字符
        
        if len(result) <= max_chars:
            return result
        
        # 保留头部和尾部，中间截断
        head_size = int(max_chars * 0.7)
        tail_size = int(max_chars * 0.2)
        truncated = (
            result[:head_size]
            + f"\n\n... [中间内容已省略，原始长度 {len(result)} 字符] ...\n\n"
            + result[-tail_size:]
        )
        return truncated
```

##### 3.3.3 上下文组装优化：缓存友好的 Prompt 结构

Prompt 的组装顺序直接影响缓存命中率。核心原则：**越静态的内容越靠前，越动态的内容越靠后**。

```python
class CacheFriendlyPromptBuilder:
    """缓存友好的Prompt构建器"""
    
    def build(self, agent_config: dict, session_state: dict) -> list[dict]:
        """构建消息列表，按稳定性从高到低排列"""
        messages = []
        
        # 第1层（最稳定）：全局 System Prompt —— 几乎不变，可被缓存
        messages.append({
            "role": "system",
            "content": agent_config["system_prompt"],
            "cache_control": {"type": "ephemeral"}
        })
        
        # 第2层（高稳定性）：工具定义 —— Agent 类型确定后不变
        if agent_config.get("tools"):
            tools_desc = self._format_tools(agent_config["tools"])
            messages.append({
                "role": "system",
                "content": f"可用工具：\n{tools_desc}",
                "cache_control": {"type": "ephemeral"}
            })
        
        # 第3层（中稳定性）：长期记忆/知识库 —— 会话级别不变
        if session_state.get("memory"):
            messages.append({
                "role": "system",
                "content": f"相关记忆：\n{session_state['memory']}"
            })
        
        # 第4层（低稳定性）：对话历史 —— 每轮都变
        messages.extend(session_state.get("history", []))
        
        # 第5层（最不稳定）：当前用户消息
        messages.append({
            "role": "user",
            "content": session_state["current_query"]
        })
        
        return messages
    
    def _format_tools(self, tools: list[dict]) -> str:
        return "\n".join([f"- {t['name']}: {t['description']}" for t in tools])
```

#### 3.4 预算控制层

##### 3.4.1 Token 预算分配器

预算分配遵循"全局 → 业务线 → 用户 → 任务"的逐级分配模型：

```python
class TokenBudgetAllocator:
    """Token预算分配器：全局预算 → 分步预算"""
    
    def __init__(self, global_config: dict):
        self.global_daily_budget = global_config["daily_token_budget"]  # 全局日预算
        self.biz_line_quotas = global_config["biz_line_quotas"]  # 业务线配额
        self.usage_tracker = UsageTracker()
    
    def allocate_task_budget(self, biz_line: str, task_type: str, 
                            estimated_steps: int) -> "TaskBudget":
        """为一个Agent任务分配Token预算"""
        # 检查业务线剩余配额
        biz_remaining = self._get_biz_remaining(biz_line)
        if biz_remaining <= 0:
            raise BudgetExhaustedError(f"业务线 {biz_line} 今日预算已用完")
        
        # 根据任务类型决定基础预算
        base_budgets = {
            "simple_qa": 10000,
            "code_generation": 100000,
            "data_analysis": 80000,
            "document_writing": 50000,
        }
        base = base_budgets.get(task_type, 50000)
        
        # 不超过业务线剩余配额
        task_budget = min(base, biz_remaining)
        
        # 按步骤预分配
        per_step_budget = task_budget // max(estimated_steps, 1)
        
        return TaskBudget(
            total=task_budget,
            per_step=per_step_budget,
            remaining=task_budget,
            biz_line=biz_line,
        )
    
    def _get_biz_remaining(self, biz_line: str) -> int:
        quota = self.biz_line_quotas.get(biz_line, self.global_daily_budget // 10)
        used = self.usage_tracker.get_today_usage(biz_line)
        return quota - used


class TaskBudget:
    """单个Agent任务的预算管理"""
    
    def __init__(self, total: int, per_step: int, remaining: int, biz_line: str):
        self.total = total
        self.per_step = per_step
        self.remaining = remaining
        self.biz_line = biz_line
        self.steps_consumed: list[dict] = []
    
    def consume(self, step_name: str, input_tokens: int, output_tokens: int) -> bool:
        """消耗预算，返回是否仍在预算内"""
        total_consumed = input_tokens + output_tokens
        self.remaining -= total_consumed
        self.steps_consumed.append({
            "step": step_name,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "remaining": self.remaining,
            "timestamp": time.time()
        })
        return self.remaining > 0
    
    @property
    def utilization(self) -> float:
        return 1.0 - (self.remaining / self.total)
    
    @property
    def is_over_budget(self) -> bool:
        return self.remaining <= 0
```

##### 3.4.2 实时 Token 消耗追踪

```python
class RealtimeTokenTracker:
    """实时Token消耗追踪器"""
    
    def __init__(self, redis_client=None):
        self.redis = redis_client
        self.local_buffer: list[dict] = []
    
    async def record(self, event: dict):
        """记录一次LLM调用的Token消耗"""
        record = {
            "trace_id": event["trace_id"],
            "task_id": event["task_id"],
            "biz_line": event["biz_line"],
            "user_id": event["user_id"],
            "agent_type": event["agent_type"],
            "step_index": event["step_index"],
            "model": event["model"],
            "input_tokens": event["input_tokens"],
            "output_tokens": event["output_tokens"],
            "cache_read_tokens": event.get("cache_read_tokens", 0),
            "cache_write_tokens": event.get("cache_write_tokens", 0),
            "cost_usd": self._calculate_cost(event),
            "latency_ms": event.get("latency_ms", 0),
            "timestamp": time.time(),
        }
        
        self.local_buffer.append(record)
        
        # 实时更新Redis计数器（用于预算检查）
        if self.redis:
            today = datetime.now().strftime("%Y%m%d")
            pipe = self.redis.pipeline()
            pipe.incr(f"token_usage:{today}:global", record["input_tokens"] + record["output_tokens"])
            pipe.incr(f"token_usage:{today}:biz:{record['biz_line']}", record["input_tokens"] + record["output_tokens"])
            pipe.incr(f"token_usage:{today}:user:{record['user_id']}", record["input_tokens"] + record["output_tokens"])
            await pipe.execute()
        
        # 批量刷新到持久化存储
        if len(self.local_buffer) >= 100:
            await self._flush_to_storage()
    
    def _calculate_cost(self, event: dict) -> float:
        """根据模型和Token数计算成本"""
        pricing = {
            "gpt-4o": {"input": 2.50, "output": 10.00, "cached_input": 1.25},
            "gpt-4o-mini": {"input": 0.15, "output": 0.60, "cached_input": 0.075},
            "o1-preview": {"input": 15.00, "output": 60.00, "cached_input": 7.50},
            "claude-3.5-sonnet": {"input": 3.00, "output": 15.00, "cached_input": 0.30},
        }
        model_price = pricing.get(event["model"], pricing["gpt-4o"])
        
        regular_input = event["input_tokens"] - event.get("cache_read_tokens", 0)
        cached_input = event.get("cache_read_tokens", 0)
        
        cost = (
            regular_input * model_price["input"] / 1_000_000
            + cached_input * model_price["cached_input"] / 1_000_000
            + event["output_tokens"] * model_price["output"] / 1_000_000
        )
        return round(cost, 6)
    
    async def _flush_to_storage(self):
        """批量写入持久化存储（如ClickHouse、BigQuery等）"""
        if self.local_buffer:
            await analytics_db.batch_insert("token_usage", self.local_buffer)
            self.local_buffer.clear()
```

##### 3.4.3 超预算处理策略

当 Token 消耗接近或超过预算时，系统需要自动降级：

```python
class OverBudgetHandler:
    """超预算处理策略"""
    
    async def handle(self, budget: TaskBudget, agent_context: dict) -> dict:
        """根据预算剩余情况决定降级策略"""
        utilization = budget.utilization
        
        # 预警阶段（80%预算已用）：切换到小模型
        if 0.8 <= utilization < 1.0:
            return {
                "action": "downgrade_model",
                "model": "gpt-4o-mini",
                "reason": f"预算已使用 {utilization:.0%}，切换至轻量模型",
                "compress_context": True
            }
        
        # 超预算（100%已用）：尝试极限压缩后继续
        if 1.0 <= utilization < 1.2:
            compressed = await self._emergency_compress(agent_context)
            return {
                "action": "compress_and_continue",
                "compressed_context": compressed,
                "model": "gpt-4o-mini",
                "reason": "预算已超出，执行紧急上下文压缩并用最小模型完成",
                "max_additional_tokens": budget.total * 0.1  # 额外给10%容忍
            }
        
        # 严重超预算（120%以上）：强制终止，转人工
        if utilization >= 1.2:
            return {
                "action": "terminate_and_escalate",
                "reason": "预算严重超出，任务终止",
                "partial_result": agent_context.get("last_result"),
                "escalate_to": "human_review"
            }
        
        # 正常范围
        return {"action": "continue"}
    
    async def _emergency_compress(self, context: dict) -> list[dict]:
        """紧急压缩：只保留系统提示 + 最近3轮 + 任务目标"""
        messages = context.get("messages", [])
        system_msgs = [m for m in messages if m["role"] == "system"][:1]
        recent = messages[-6:]  # 最近3轮
        return system_msgs + recent
```

##### 3.4.4 预算管理器集成到 Agent Loop

```python
class BudgetAwareAgentLoop:
    """集成了预算控制的Agent主循环"""
    
    def __init__(self, agent_config: dict, budget_allocator: TokenBudgetAllocator,
                 context_pipeline: ContextCompressionPipeline,
                 cache_manager: PromptCacheManager,
                 router: RuleBasedRouter,
                 over_budget_handler: OverBudgetHandler):
        self.config = agent_config
        self.budget_allocator = budget_allocator
        self.context_pipeline = context_pipeline
        self.cache_manager = cache_manager
        self.router = router
        self.over_budget_handler = over_budget_handler
        self.token_tracker = RealtimeTokenTracker()
    
    async def run(self, task: dict) -> dict:
        """带预算控制的Agent执行循环"""
        # 1. 分配预算
        budget = self.budget_allocator.allocate_task_budget(
            biz_line=task["biz_line"],
            task_type=task["task_type"],
            estimated_steps=task.get("estimated_steps", 10)
        )
        
        messages = self.cache_manager.build_cache_friendly_messages(
            agent_type=self.config["agent_type"],
            dynamic_messages=[{"role": "user", "content": task["query"]}]
        )
        
        step = 0
        max_steps = 30
        
        while step < max_steps:
            step += 1
            
            # 2. 预算检查
            budget_decision = await self.over_budget_handler.handle(budget, {"messages": messages})
            if budget_decision["action"] == "terminate_and_escalate":
                return {
                    "status": "terminated",
                    "reason": budget_decision["reason"],
                    "partial_result": budget_decision.get("partial_result"),
                    "budget_usage": budget.steps_consumed
                }
            
            # 3. 上下文压缩
            current_tokens = sum(len(m.get("content", "")) // 3 for m in messages)
            if current_tokens > self.context_pipeline.summary_threshold:
                messages = await self.context_pipeline.compress(messages, current_tokens)
            
            # 4. 模型路由
            if budget_decision.get("model"):
                model = budget_decision["model"]
            else:
                model = self.router.route(task)
            
            # 5. 调用LLM
            response = await llm_call(model=model, messages=messages)
            
            # 6. 记录消耗
            budget.consume(
                step_name=f"step_{step}",
                input_tokens=response["usage"]["input_tokens"],
                output_tokens=response["usage"]["output_tokens"]
            )
            await self.token_tracker.record({
                "trace_id": task["trace_id"],
                "task_id": task["task_id"],
                "biz_line": task["biz_line"],
                "user_id": task["user_id"],
                "agent_type": self.config["agent_type"],
                "step_index": step,
                "model": model,
                **response["usage"]
            })
            
            # 7. 检查是否完成
            if response.get("finish_reason") == "stop" and not response.get("tool_calls"):
                return {
                    "status": "completed",
                    "result": response["content"],
                    "budget_usage": budget.steps_consumed,
                    "total_cost": sum(s.get("cost_usd", 0) for s in budget.steps_consumed)
                }
            
            # 8. 处理工具调用（省略具体实现）
            if response.get("tool_calls"):
                tool_results = await self._execute_tools(response["tool_calls"])
                messages.append({"role": "assistant", "content": response["content"], 
                                "tool_calls": response["tool_calls"]})
                for tr in tool_results:
                    messages.append({"role": "tool", "content": tr["result"], 
                                    "tool_call_id": tr["id"]})
        
        return {"status": "max_steps_reached", "budget_usage": budget.steps_consumed}
```

#### 3.5 监控分析层

##### 3.5.1 成本看板：多维度成本归因

成本看板需要支持从全局到单步的任意粒度下钻：

```
全局日/周/月成本 → 按业务线拆分 → 按Agent类型拆分 → 按用户拆分 → 单任务详情 → 每步Token明细
```

```python
class CostDashboard:
    """成本看板数据服务"""
    
    def __init__(self, analytics_db):
        self.db = analytics_db
    
    async def get_overview(self, date_range: tuple[str, str]) -> dict:
        """全局成本概览"""
        sql = """
        SELECT 
            SUM(cost_usd) as total_cost,
            SUM(input_tokens) as total_input_tokens,
            SUM(output_tokens) as total_output_tokens,
            SUM(cache_read_tokens) as total_cached_tokens,
            COUNT(DISTINCT task_id) as total_tasks,
            COUNT(DISTINCT user_id) as active_users,
            AVG(cost_usd) as avg_cost_per_call,
            SUM(cache_read_tokens) / NULLIF(SUM(input_tokens), 0) as cache_hit_ratio
        FROM token_usage
        WHERE timestamp BETWEEN %(start)s AND %(end)s
        """
        return await self.db.query(sql, {"start": date_range[0], "end": date_range[1]})
    
    async def get_cost_by_dimension(self, dimension: str, date_range: tuple[str, str]) -> list[dict]:
        """按维度查看成本分布"""
        valid_dimensions = {"biz_line", "agent_type", "model", "user_id"}
        if dimension not in valid_dimensions:
            raise ValueError(f"Invalid dimension: {dimension}")
        
        sql = f"""
        SELECT 
            {dimension},
            SUM(cost_usd) as total_cost,
            SUM(input_tokens + output_tokens) as total_tokens,
            COUNT(DISTINCT task_id) as task_count,
            AVG(cost_usd) as avg_cost_per_call
        FROM token_usage
        WHERE timestamp BETWEEN %(start)s AND %(end)s
        GROUP BY {dimension}
        ORDER BY total_cost DESC
        LIMIT 50
        """
        return await self.db.query(sql, {"start": date_range[0], "end": date_range[1]})
    
    async def get_task_detail(self, task_id: str) -> list[dict]:
        """查看单个任务的每步Token消耗明细"""
        sql = """
        SELECT step_index, model, input_tokens, output_tokens,
               cache_read_tokens, cost_usd, latency_ms
        FROM token_usage
        WHERE task_id = %(task_id)s
        ORDER BY step_index
        """
        return await self.db.query(sql, {"task_id": task_id})
```

##### 3.5.2 成本异常检测与告警

```python
class CostAnomalyDetector:
    """成本异常检测器"""
    
    def __init__(self, alert_service):
        self.alert_service = alert_service
        self.thresholds = {
            "single_task_max_cost": 5.0,      # 单任务超过5美元告警
            "single_task_max_steps": 30,       # 单任务超过30步告警
            "hourly_cost_spike_ratio": 3.0,    # 时成本突增3倍告警
            "cache_hit_rate_min": 0.3,         # 缓存命中率低于30%告警
            "daily_budget_usage_warn": 0.8,    # 日预算使用80%时预警
        }
    
    async def check_single_task(self, task_metrics: dict):
        """检查单个任务是否异常"""
        # 单任务成本过高
        if task_metrics["total_cost"] > self.thresholds["single_task_max_cost"]:
            await self.alert_service.send(
                level="warning",
                title="单任务成本异常",
                detail=f"任务 {task_metrics['task_id']} 成本达到 ${task_metrics['total_cost']:.2f}，"
                       f"执行了 {task_metrics['total_steps']} 步。"
                       f"用户：{task_metrics['user_id']}，业务线：{task_metrics['biz_line']}"
            )
        
        # 疑似死循环
        if task_metrics["total_steps"] > self.thresholds["single_task_max_steps"]:
            await self.alert_service.send(
                level="critical",
                title="Agent疑似死循环",
                detail=f"任务 {task_metrics['task_id']} 已执行 {task_metrics['total_steps']} 步，"
                       f"可能陷入死循环。已自动终止。"
            )
    
    async def check_hourly_trend(self):
        """检查小时级成本趋势"""
        current_hour_cost = await self._get_current_hour_cost()
        avg_hour_cost = await self._get_avg_hour_cost(days=7)
        
        if avg_hour_cost > 0 and current_hour_cost / avg_hour_cost > self.thresholds["hourly_cost_spike_ratio"]:
            await self.alert_service.send(
                level="warning",
                title="小时成本突增",
                detail=f"当前小时成本 ${current_hour_cost:.2f}，"
                       f"近7天均值 ${avg_hour_cost:.2f}，"
                       f"突增 {current_hour_cost / avg_hour_cost:.1f} 倍"
            )
```

##### 3.5.3 成本优化建议引擎

系统自动分析消耗模式，给出优化建议：

```python
class CostOptimizationAdvisor:
    """成本优化建议引擎：自动识别优化机会"""
    
    async def analyze(self, date_range: tuple[str, str]) -> list[dict]:
        """分析并生成优化建议"""
        suggestions = []
        
        # 建议1：检查是否有大量简单任务在用旗舰模型
        simple_on_flagship = await self._check_simple_tasks_on_flagship(date_range)
        if simple_on_flagship["waste_ratio"] > 0.2:
            suggestions.append({
                "type": "model_routing",
                "priority": "high",
                "title": "简单任务过度使用旗舰模型",
                "detail": f"过去 {date_range} 内，{simple_on_flagship['waste_ratio']:.0%} 的简单任务"
                          f"（分类/提取/格式转换）使用了旗舰模型，"
                          f"预计切换到轻量模型可节省 ${simple_on_flagship['potential_saving']:.2f}",
                "action": "启用模型路由，将 task_type 为 classification/extraction 的任务路由到 gpt-4o-mini"
            })
        
        # 建议2：检查Prompt Caching命中率
        cache_stats = await self._check_cache_hit_rate(date_range)
        if cache_stats["hit_rate"] < 0.5:
            suggestions.append({
                "type": "caching",
                "priority": "medium",
                "title": "Prompt Caching命中率过低",
                "detail": f"Prompt缓存命中率仅 {cache_stats['hit_rate']:.0%}，"
                          f"建议检查Prompt结构是否缓存友好（静态前缀是否稳定）",
                "action": "检查System Prompt是否包含动态内容（如时间戳），将动态部分移至用户消息"
            })
        
        # 建议3：检查平均上下文长度
        context_stats = await self._check_context_length(date_range)
        if context_stats["avg_input_tokens"] > 20000:
            suggestions.append({
                "type": "context_engineering",
                "priority": "high",
                "title": "平均上下文过长",
                "detail": f"平均输入Token数为 {context_stats['avg_input_tokens']:,}，"
                          f"建议启用上下文压缩Pipeline",
                "action": "启用滑动窗口（window_size=10）+ 历史摘要（threshold=15000）"
            })
        
        # 建议4：检查重复工具调用
        tool_stats = await self._check_duplicate_tool_calls(date_range)
        if tool_stats["duplicate_ratio"] > 0.1:
            suggestions.append({
                "type": "tool_caching",
                "priority": "medium",
                "title": "存在大量重复工具调用",
                "detail": f"{tool_stats['duplicate_ratio']:.0%} 的工具调用是重复的（相同工具+相同参数），"
                          f"启用工具结果缓存可减少 {tool_stats['potential_saving_tokens']:,} tokens",
                "action": "启用 ToolResultCache，针对 read_file/search_code 设置60秒缓存TTL"
            })
        
        return sorted(suggestions, key=lambda s: {"high": 0, "medium": 1, "low": 2}[s["priority"]])
```

##### 3.5.4 ROI 分析

成本优化不能只看省了多少钱，还要看 Agent 创造了多少价值：

```python
class AgentROIAnalyzer:
    """Agent ROI分析器"""
    
    async def calculate_roi(self, biz_line: str, date_range: tuple[str, str]) -> dict:
        """计算Agent的投入产出比"""
        # 投入：Token成本 + 基础设施成本 + 工程维护成本
        token_cost = await self._get_token_cost(biz_line, date_range)
        infra_cost = await self._get_infra_cost(biz_line, date_range)  # GPU、带宽等
        
        # 产出：需要业务方定义
        # 编码Agent → 节省的开发人时
        # 客服Agent → 减少的人工客服量
        # 数据分析Agent → 加速的决策时间
        business_value = await self._get_business_value(biz_line, date_range)
        
        total_cost = token_cost + infra_cost
        roi = (business_value - total_cost) / total_cost if total_cost > 0 else 0
        
        return {
            "biz_line": biz_line,
            "period": date_range,
            "total_cost": total_cost,
            "token_cost": token_cost,
            "infra_cost": infra_cost,
            "business_value": business_value,
            "roi": f"{roi:.1%}",
            "cost_per_task": total_cost / max(await self._get_task_count(biz_line, date_range), 1),
            "value_per_task": business_value / max(await self._get_task_count(biz_line, date_range), 1)
        }
```

---

### 四、核心数据流：一次Agent请求的成本优化全链路

以下逐步描述一个用户请求从进入系统到最终完成的完整成本优化链路。

#### 步骤 1：请求入口 — 成本元信息注入

用户发起请求时，系统为其注入成本追踪所需的元信息：

```
请求进入 → 生成 trace_id → 识别 biz_line / user_id / agent_type → 创建成本追踪上下文
```

**成本决策点**：无直接成本，但此步的标签决定了后续所有成本的归因维度。

#### 步骤 2：预算分配 — 确定本次任务的 Token 上限

```
查询业务线剩余配额 → 根据任务类型分配基础预算 → 按预估步数拆分分步预算
```

**成本决策点**：预算分配直接决定了本次任务的成本天花板。配额不足时直接拒绝或排队。

#### 步骤 3：模型路由 — 选择性价比最优的模型

```
提取请求特征（task_type, priority, token_estimate）→ 路由决策引擎判定 → 选定模型
```

**成本决策点**：路由到 gpt-4o-mini vs gpt-4o，单次调用成本可相差 10~20 倍。

#### 步骤 4：缓存查询 — 尝试跳过 LLM 调用

```
语义缓存查询（是否有相似问题的历史回答）→ 命中则直接返回，跳过后续所有步骤
```

**成本决策点**：缓存命中 = 成本为零（仅 Embedding 计算的微小成本）。

#### 步骤 5：Prompt 组装 — 缓存友好的结构

```
静态前缀（System Prompt + 工具定义）→ 中期记忆 → 压缩后的历史 → 当前消息
```

**成本决策点**：静态前缀命中 Prompt Caching 可节省约 90% 的前缀输入 Token 成本。

#### 步骤 6：上下文压缩 — 控制输入 Token 数

```
检查当前上下文Token数 → 超阈值则触发压缩Pipeline → 滑动窗口/摘要/结构化提取
```

**成本决策点**：将 50K tokens 压缩到 20K tokens，直接节省 60% 的输入成本。

#### 步骤 7：LLM 调用 — 实际产生成本的环节

```
发送请求到选定模型 → 等待响应 → 解析 usage 中的 Token 消耗
```

**成本决策点**：实际成本产生点。记录 input_tokens、output_tokens、cache_read_tokens。

#### 步骤 8：工具执行 — 工具结果的 Token 预算

```
解析 tool_calls → 查询工具结果缓存 → 缓存未命中则执行工具 → 截断结果到Token预算 → 存入缓存
```

**成本决策点**：工具结果截断避免下一轮上下文膨胀；缓存复用避免重复执行。

#### 步骤 9：循环判断 — 是否继续

```
检查是否完成 → 检查预算剩余 → 决定继续/降级/终止
```

**成本决策点**：预算不足时降级到小模型或终止任务，防止成本失控。

#### 步骤 10：成本归因 — 记录到多维分析存储

```
汇总本次任务所有步骤的Token消耗 → 计算总成本 → 写入分析数据库 → 更新看板
```

**优化前后成本对比示例**：

| 指标 | 优化前 | 优化后 | 节省 |
|-----|-------|-------|------|
| 平均步数 | 15 步 | 12 步（工具缓存减少重复）| 20% |
| 平均 input tokens/步 | 25,000 | 12,000（上下文压缩）| 52% |
| 平均模型成本/步 | $0.08 | $0.03（模型路由）| 62% |
| Prompt 缓存命中率 | 0% | 75% | — |
| 单任务总成本 | $1.20 | $0.35 | **71%** |

---

### 五、成本优化决策框架

不同场景适合不同的优化策略。以下是选择指南：

#### 5.1 何时用模型路由

- **适用场景**：高频简单任务占比超过 30%。
- **典型任务**：意图分类、实体提取、格式转换、简单问答。
- **预期收益**：单任务成本降低 60%~90%。
- **注意事项**：必须有质量监控，确保小模型的输出满足业务要求。

#### 5.2 何时用 Prompt Caching

- **适用场景**：同类型 Agent 的 System Prompt + 工具定义 > 1024 tokens 且频繁被调用。
- **典型任务**：所有 Agent 调用（几乎所有场景都值得做）。
- **预期收益**：静态前缀部分节省约 90% 成本。
- **注意事项**：Prompt 结构必须按稳定性排列（静态在前、动态在后）。

#### 5.3 何时用上下文压缩

- **适用场景**：多轮对话（>5轮）、多步工具调用（>5步）。
- **典型任务**：复杂编码、数据分析、多轮交互式问答。
- **预期收益**：输入 Token 减少 40%~60%。
- **注意事项**：压缩可能丢失细节，关键信息需要保护。

#### 5.4 何时用 Speculative Decoding

- **适用场景**：延迟敏感且输出包含大量可预测结构。
- **典型任务**：Agent 的 tool_call JSON 输出、代码补全、模板化输出。
- **预期收益**：推理延迟降低 50%~70%（不降低成本，但提升吞吐）。
- **注意事项**：需要部署 draft model，增加基础设施复杂度。

#### 5.5 决策树

```
                     收到Agent优化需求
                            │
                    ┌───────┴───────┐
                    │ 主要痛点是什么？│
                    └───────┬───────┘
                ┌───────────┼───────────┐
                ▼           ▼           ▼
           成本太高     延迟太高     质量不够
                │           │           │
        ┌───────┴──┐    ┌───┴───┐   ┌──┴──────┐
        ▼          ▼    ▼       ▼   ▼         ▼
    高频简单   长上下文  输出慢   吞吐低  小模型不行  结果不稳
    任务多?    问题?                              
        │          │    │       │   │         │
        ▼          ▼    ▼       ▼   ▼         ▼
    模型路由   上下文  Spec.  Cont.  升级模型  多次采样
    +缓存     压缩   Decoding Batching +路由   +投票
              +Prompt                  微调
              Caching
```

---

### 六、企业级成本治理

#### 6.1 成本预算与配额管理

企业级 Agent 平台的成本管理不能只靠技术手段，还需要组织层面的制度设计：

**三级配额模型**：

```
公司总预算（月度）
  └── 事业部配额（按业务价值分配）
        └── 团队配额（按团队规模和使用频率分配）
              └── 个人配额（可选，防止个人滥用）
```

**配额申请与调整流程**：
1. 每月初根据上月实际使用量 + 业务增长预期，自动生成配额建议。
2. 超过基础配额需要业务负责人审批。
3. 临时突发需求可申请短期配额提升（如大促期间）。
4. 持续低使用率的配额自动回收。

#### 6.2 按业务线的成本归属

成本归属的核心挑战是"公共资源如何分摊"：

- **直接成本**：Agent 调用的 Token 费用，通过 biz_line 标签直接归属。
- **共享成本**：推理集群、缓存系统、监控平台等基础设施成本，按使用量比例分摊。
- **平台成本**：Agent 平台团队的研发成本，按业务线数量均摊或按调用量加权分摊。

某互联网公司的实践：每月生成各业务线的"Agent 成本账单"，包含直接 Token 成本、分摊的基础设施成本、成本趋势对比、以及系统自动生成的优化建议。

#### 6.3 成本优化文化：让每个开发者关注成本

技术手段能降的成本有上限，真正的长期降本来自"成本意识"的建立：

1. **成本可视化**：在每个 Agent 的开发调试面板上显示实时 Token 消耗和预估成本。
2. **成本 Code Review**：上线新 Agent 或修改 Prompt 时，Review 清单中加入成本评估项。
3. **成本排行榜**：定期公布各团队/Agent的成本效率排名（成本/成功任务数），形成良性竞争。
4. **最佳实践分享**：将成功的成本优化案例（如"某Agent通过Prompt重构降本40%"）在团队内分享。

#### 6.4 成本优化的红线：什么时候不能省

并非所有成本都应该优化。以下场景必须优先保证质量，不可为了省钱而降级：

1. **安全相关任务**：代码安全审查、漏洞检测、权限验证 — 必须用最强模型。
2. **财务/法务决策**：涉及金钱、合同、法律的任务 — 不能因为路由到小模型而出错。
3. **用户体验关键路径**：用户直接感知的核心功能 — 延迟和质量优先于成本。
4. **低频高价值任务**：虽然频率低但每次都很重要（如系统架构设计）— 不值得为了省几美元冒质量风险。

判断原则：**如果一个任务出错的代价远高于省下的 Token 成本，就不要优化它。**

---

### 七、演进路线

Agent 成本优化系统不可能一步到位，建议分三个阶段逐步建设：

#### Phase 1：成本可观测（第 1~2 个月）

**目标**：让成本"看得见"。

**核心建设**：
1. 在 LLM 调用层加入 Token 消耗埋点（input_tokens、output_tokens、cache_read_tokens、model、latency）。
2. 建设多维成本看板：按业务线/Agent类型/用户/模型维度展示。
3. 设置基础告警：单任务成本超限、死循环步数超限、日消耗突增。
4. 建立成本基线：统计各业务线的 P50/P90/P99 成本和 Token 消耗。

**验收标准**：
- 每次 LLM 调用的成本可追溯到具体的业务线、用户、任务。
- 成本异常能在 5 分钟内被告警捕获。
- 产出第一份"Agent 成本月报"，各业务线负责人可查看自己的成本。

**预期成本降低**：0%（本阶段不降本，但为后续优化提供数据基础）。

#### Phase 2：路由优化（第 3~4 个月）

**目标**：实现"大小模型分流"和"缓存复用"。

**核心建设**：
1. 上线基于规则的模型路由器：简单任务路由到轻量模型，复杂任务路由到旗舰模型。
2. 启用 Prompt Caching：重构 Prompt 结构为缓存友好格式，静态前缀在前、动态内容在后。
3. 上线工具结果缓存：对 read_file、search_code 等幂等工具启用短时缓存。
4. 建设路由 A/B 测试框架：新路由策略先在 10% 流量上验证，确认质量不降再全量。

**验收标准**：
- 模型路由覆盖率 > 80%（即 80% 的请求经过路由决策）。
- Prompt Caching 命中率 > 60%。
- 工具结果缓存命中率 > 20%。
- 质量指标（任务成功率、用户满意度）无显著下降（p-value > 0.05）。

**预期成本降低**：30%~50%。

#### Phase 3：全局优化（第 5~6 个月）

**目标**：实现精细化的上下文工程、预算控制和推理加速。

**核心建设**：
1. 上线上下文压缩 Pipeline：滑动窗口 + 历史摘要 + 工具结果 Token 预算。
2. 上线 Token Budget 系统：全局预算 → 业务线配额 → 任务预算 → 分步预算。
3. 上线超预算自动降级：80% 预警切小模型、100% 紧急压缩、120% 终止转人工。
4. 评估 Speculative Decoding / Continuous Batching 等推理加速方案（如果自部署模型）。
5. 上线成本优化建议引擎：自动分析消耗模式，生成优化建议。

**验收标准**：
- 平均输入 Token 数较 Phase 1 基线下降 40% 以上。
- 单任务成本超限率 < 1%。
- 成本优化建议引擎每周自动生成可执行建议 >= 3 条。
- 整体成本较 Phase 1 基线下降 50%~70%。

**预期成本降低**：在 Phase 2 基础上再降 20%~30%，累计降低 50%~70%。

---

### 八、面试加分点

#### 8.1 如何用 3 分钟讲清楚 Agent 成本优化系统的架构

**推荐话术**：

> Agent 的成本问题本质是"上下文雪球效应"——每轮循环的上下文累积增长，导致总 Token 消耗呈二次方增长。我们构建了一套五层的成本优化体系来解决这个问题。
>
> **第一层是模型路由**：不是所有请求都需要最强模型。通过规则/ML分类器，把简单任务路由到轻量模型，单此一项就能降本40%。
>
> **第二层是缓存优化**：包括 Prompt Caching（静态前缀命中缓存省90%输入成本）、工具结果缓存（重复工具调用直接复用）、语义缓存（相似查询复用历史结果）。
>
> **第三层是上下文工程**：通过滑动窗口 + 历史摘要 + 工具结果Token预算，控制上下文的雪球效应，平均压缩40%~60%。
>
> **第四层是预算控制**：全局预算按业务线、团队逐级分配，单任务有Token上限，超预算自动降级（切小模型 → 紧急压缩 → 转人工）。
>
> **第五层是监控分析**：多维成本看板、异常告警、自动生成优化建议。
>
> 这套系统帮我们实现了整体降本约 60%，同时核心任务质量没有下降。

#### 8.2 面试官可能追问的深度问题及回答思路

**Q：模型路由的分类器怎么训练？训练数据从哪来？**

A：初期用规则兜底，同时对线上流量做埋点标注——记录每次请求用了哪个模型、输出质量如何。用人工+LLM辅助的方式标注约5000条"任务-最优模型"配对数据，训练一个轻量DistilBERT分类器。上线后持续收集反馈，定期重训。关键是分类器的置信度不足时要保守回退到更强模型，宁可多花钱不可降质量。

**Q：Prompt Caching 命中率上不去怎么办？**

A：检查三个常见原因：(1) System Prompt 里包含动态内容（如当前时间、用户名），导致每次前缀不同——解决方案是把动态内容移到用户消息中；(2) 工具定义频繁变更——固定工具定义版本，变更时统一刷新缓存；(3) 调用频率不够导致缓存过期——设置缓存预热机制，定期发探活请求保持缓存。

**Q：上下文压缩会不会导致 Agent 丢失关键信息？**

A：确实存在这个风险。我们的做法是：(1) 关键信息保护——错误信息、用户原始意图、已确认的关键决策不参与压缩；(2) 压缩后校验——用小模型快速检查压缩后的上下文是否保留了当前任务所需的关键信息；(3) 分级压缩——先做温和的滑动窗口，只有在 Token 仍然超限时才做激进的摘要压缩；(4) 质量监控——对比压缩前后的任务成功率，如果有显著下降立即回滚。

**Q：Token Budget 设多少合适？设太低怕影响任务完成率，设太高等于没设。**

A：我们的做法是数据驱动。先不设限地跑一段时间，统计各类型任务的 P50/P90/P99 Token 消耗。然后将 Budget 设在 P95——覆盖绝大部分正常任务，只拦截异常的5%。同时配合降级策略而非硬截断——80%预警切小模型、100%紧急压缩而非直接终止，保证用户体验。上线后持续监控"因预算不足导致任务失败"的比例，动态调整阈值。

**Q：成本优化和质量如何平衡？怎么证明优化没有降质量？**

A：核心是建立质量评估体系和 A/B 测试框架。每次上线新的优化策略，都在 10% 的流量上 A/B 测试，对比实验组和对照组的：(1) 任务成功率；(2) 用户满意度评分；(3) 工具调用准确率；(4) 平均完成步数。只有当成本显著下降且质量指标无统计显著差异时才全量推开。同时设定质量红线——如果某项指标下降超过 2 个百分点，自动回滚。

**Q：自部署模型 vs 调用 API，成本怎么比较？**

A：取决于调用量。API 按量付费，边际成本恒定；自部署有固定的 GPU 成本但边际成本趋近于零。粗略计算：当日均调用量超过一定阈值（取决于模型大小和 GPU 类型），自部署的单位成本更低。但自部署还有隐性成本：运维团队、模型版本管理、推理框架调优。某互联网公司的经验是——核心高频 Agent 用自部署（用 vLLM 部署开源模型），长尾低频 Agent 用 API，两者配合达到最优成本结构。
