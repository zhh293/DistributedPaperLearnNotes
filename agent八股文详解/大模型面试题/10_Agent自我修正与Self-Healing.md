# Agent自我修正与Self-Healing

> 本文面向 AI Agent 方向的面试准备与工程实践，系统梳理 Agent 自我修正（Self-Correction）与自愈（Self-Healing）的技术原理、核心架构、关键算法与企业级落地方案。内容覆盖 Reflection、Reflexion、LATS、Verifier、自进化 Agent 等核心主题，并附高频面试问题与参考答案。

---

## 一、从 Reflection 到 Self-Healing

### 1.1 Reflection 机制的回顾与局限

在 Agent 技术演进中，Reflection（反思）是最早被系统化研究的自我修正范式。它的核心思想是：**让模型审视自己的输出，发现问题并改进**。

#### Self-Refine：生成-评估-改进循环

Self-Refine（Madaan et al., 2023）是最经典的 Reflection 框架，它将自我修正拆解为三个步骤的迭代循环：

```
┌──────────────────────────────────────────────────┐
│                Self-Refine 循环                    │
│                                                    │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐   │
│   │  生成     │───→│  评估     │───→│  改进     │   │
│   │ Generate  │    │ Evaluate  │    │  Refine   │   │
│   └──────────┘    └──────────┘    └──────────┘   │
│        ↑                                  │       │
│        └──────────────────────────────────┘       │
│                  迭代直到满意                       │
└──────────────────────────────────────────────────┘
```

核心流程：
1. **Generate**：模型根据任务描述生成初始输出
2. **Evaluate**：同一模型（或另一个模型）对输出进行批判性评估，识别问题
3. **Refine**：根据评估反馈，模型修改并改进输出
4. 重复 2-3 步骤，直到评估满意或达到最大迭代次数

```python
class SelfRefineAgent:
    """Self-Refine 实现：生成-评估-改进循环"""
    
    def __init__(self, llm, max_iterations=3):
        self.llm = llm
        self.max_iterations = max_iterations
    
    def generate(self, task: str) -> str:
        """Step 1: 初始生成"""
        prompt = f"请完成以下任务：\n{task}"
        return self.llm.generate(prompt)
    
    def evaluate(self, task: str, output: str) -> dict:
        """Step 2: 批判性评估"""
        prompt = f"""请评估以下输出是否正确完成了任务。

任务：{task}
输出：{output}

请指出：
1. 输出是否满足要求（是/否）
2. 具体的问题和不足
3. 改进建议
"""
        feedback = self.llm.generate(prompt)
        is_satisfactory = "是" in feedback.split("\n")[0]
        return {"satisfactory": is_satisfactory, "feedback": feedback}
    
    def refine(self, task: str, output: str, feedback: str) -> str:
        """Step 3: 根据反馈改进"""
        prompt = f"""请根据反馈改进输出。

原始任务：{task}
当前输出：{output}
评估反馈：{feedback}

请生成改进后的输出："""
        return self.llm.generate(prompt)
    
    def run(self, task: str) -> str:
        """执行 Self-Refine 循环"""
        output = self.generate(task)
        
        for i in range(self.max_iterations):
            evaluation = self.evaluate(task, output)
            if evaluation["satisfactory"]:
                print(f"第 {i+1} 轮评估通过，输出满足要求")
                break
            print(f"第 {i+1} 轮评估未通过，开始改进...")
            output = self.refine(task, output, evaluation["feedback"])
        
        return output
```

#### Reflexion：语言强化学习

Reflexion（Shinn et al., 2023）在 Self-Refine 的基础上引入了**记忆机制**，使得 Agent 能够跨轮次积累经验：

```
┌───────────────────────────────────────────────────────┐
│                  Reflexion 框架                         │
│                                                         │
│   ┌──────┐    ┌──────┐    ┌──────────┐    ┌────────┐  │
│   │ Actor │───→│ Env  │───→│Evaluator │───→│Reflector│  │
│   │ 执行  │    │ 环境  │    │  评估器   │    │ 反思器  │  │
│   └──────┘    └──────┘    └──────────┘    └────────┘  │
│       ↑                                        │       │
│       │           ┌──────────────┐             │       │
│       └───────────│ Memory Store │←────────────┘       │
│                   │  经验记忆库   │                      │
│                   └──────────────┘                      │
└───────────────────────────────────────────────────────┘
```

与 Self-Refine 的关键差异：
- **环境交互**：Reflexion 的 Agent 在真实环境中执行动作并获取反馈
- **显式记忆**：反思结果存储在专门的记忆库中，下次尝试时可检索
- **多轮尝试**：Agent 可以多次重新尝试整个任务，每次利用之前的反思

#### Reflection 的局限

尽管 Reflection 机制取得了显著效果，但它存在几个根本性的局限：

| 局限 | 说明 | 影响 |
|------|------|------|
| **事后修正** | 只能在任务完成后反思，无法在执行过程中实时修复 | 中间步骤的错误会级联放大 |
| **缺乏错误分类** | 将所有错误视为同质的，没有区分参数错误、环境错误、逻辑错误 | 修复策略不够精准 |
| **重试成本高** | 每次反思后都需要从头重新执行 | 对于长链路任务成本巨大 |
| **反思质量不稳定** | 依赖模型的自我认知能力，容易产生"虚假反思" | 可能越改越错 |
| **无状态恢复** | 不能从上次失败的位置恢复，必须重来 | 浪费已完成的有效工作 |

这些局限催生了 Self-Healing 这一更强大的范式。

### 1.2 Self-Healing 的概念与定义

**Self-Healing（自愈）** 是指 Agent 在执行过程中，**实时检测错误、诊断根因、生成修复策略并自动重试**的能力。

核心公式：

```
Self-Healing = 实时错误检测 + 根因诊断 + 修复策略生成 + 自动重试
```

更形式化地表达：

```
给定 Agent 的执行轨迹 τ = (s₀, a₁, o₁, s₁, a₂, o₂, ..., sₜ)
当在步骤 t 检测到错误 e(oₜ) 时：
  1. 诊断根因：cause = Diagnose(e, τ[:t], context)
  2. 生成修复：repair = RepairStrategy(cause, available_tools, constraints)
  3. 执行修复：o'ₜ = Execute(repair)
  4. 验证结果：valid = Verify(o'ₜ, expected)
  5. 若 valid: 继续 sₜ → sₜ₊₁
     若 ¬valid ∧ retries < max: 回到步骤 1
     若 ¬valid ∧ retries ≥ max: 触发降级或人工干预
```

#### 与 Reflection 的区别

```
┌─────────────────────────────────────────────────────────────┐
│                  Reflection vs Self-Healing                   │
│                                                               │
│  Reflection:                                                  │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌──────┐ ┌────────┐          │
│  │ S1 │→│ S2 │→│ S3 │→│ S4 │→│ 失败  │→│ 反思    │          │
│  └────┘ └────┘ └────┘ └────┘ └──────┘ └────────┘          │
│                                              │               │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐         │               │
│  │ S1'│→│ S2'│→│ S3'│→│ S4'│→│ 成功 │ ←──重来               │
│  └────┘ └────┘ └────┘ └────┘ └────┘                         │
│                                                               │
│  Self-Healing:                                                │
│  ┌────┐ ┌────┐ ┌────┐ ┌──────┐ ┌──────┐ ┌────┐ ┌────┐     │
│  │ S1 │→│ S2 │→│ S3 │→│ 检测  │→│ 修复  │→│ S3'│→│ 成功│     │
│  └────┘ └────┘ └────┘ │ 错误  │ │ 重试  │ └────┘ └────┘     │
│                        └──────┘ └──────┘                     │
│                        ↑ 实时修复，不用从头重来                  │
└─────────────────────────────────────────────────────────────┘
```

关键区别总结：

| 维度 | Reflection | Self-Healing |
|------|-----------|-------------|
| **修复时机** | 任务完成后 | 执行过程中实时 |
| **错误处理** | 整体重试 | 定点修复 |
| **状态保持** | 丢弃已完成状态 | 保持有效状态 |
| **错误分析深度** | 粗粒度反思 | 细粒度根因诊断 |
| **修复策略** | 统一改进 | 针对性修复策略 |
| **成本** | 高（全量重试） | 低（局部修复） |

#### 与传统容错机制的区别

传统软件工程中的容错（Fault Tolerance）基于**预定义规则**：

```python
# 传统容错：基于规则的重试
def traditional_retry(func, max_retries=3):
    for i in range(max_retries):
        try:
            return func()
        except TimeoutError:
            time.sleep(2 ** i)  # 指数退避
        except ValueError:
            raise  # 不可重试的错误
    raise MaxRetriesExceeded()
```

Agent 的 Self-Healing 基于**语义理解**：

```python
# Self-Healing：基于语义理解的修复
def self_healing_execute(agent, action, context):
    result = agent.execute(action)
    
    if is_error(result):
        # 用 LLM 理解错误含义
        diagnosis = agent.llm.analyze(f"""
            执行动作：{action}
            错误信息：{result.error}
            执行上下文：{context}
            请分析：1) 错误的根本原因 2) 最佳修复策略
        """)
        
        # 基于诊断生成新的修复动作（非预定义规则）
        repair_action = agent.llm.generate_repair(diagnosis)
        return agent.execute(repair_action)
```

核心差异：传统容错是"相同动作重试"，Self-Healing 是"理解错误后用不同策略修复"。

### 1.3 为什么 Agent 需要 Self-Healing

#### 执行链路长，错误级联

一个典型的复杂 Agent 任务可能包含 10-50 步操作。假设每步的成功率为 95%：

```
单步成功率 = 0.95
10 步全部成功的概率 = 0.95^10 ≈ 0.60 (40% 失败率)
20 步全部成功的概率 = 0.95^20 ≈ 0.36 (64% 失败率)
50 步全部成功的概率 = 0.95^50 ≈ 0.08 (92% 失败率)
```

没有 Self-Healing 的 Agent，在复杂任务上几乎必然失败。

#### 非确定性环境

Agent 运行在充满不确定性的真实环境中：

- **API 变更**：第三方接口升级导致参数格式变化
- **网络波动**：请求超时、连接中断
- **数据异常**：输入数据格式不符合预期
- **资源竞争**：并发操作导致的状态不一致
- **权限变化**：运行时权限配置变更

#### 人工干预成本

在企业级 Agent 部署中，如果每次错误都需要人工干预：

```
假设：
- Agent 日均处理 10,000 个任务
- 无 Self-Healing 时失败率约 30%
- 每次人工干预需要 5 分钟
- 日均需要人工干预：10,000 × 30% = 3,000 次
- 日均人工成本：3,000 × 5 分钟 = 250 小时

有 Self-Healing：
- 自动修复率 80%
- 需要人工干预：3,000 × 20% = 600 次
- 日均人工成本：600 × 5 分钟 = 50 小时
- 节省：200 小时/天
```

这就是 Self-Healing 的商业价值所在。

---

## 二、Self-Healing 的四层架构

一个完整的 Self-Healing 系统可以分为四个层次，每层负责不同的职责：

```
┌────────────────────────────────────────────────────────┐
│                 Self-Healing 四层架构                     │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Layer 4: 重试执行层 (Retry Execution)            │   │
│  │  - 重试次数控制    - 指数退避                       │   │
│  │  - 状态恢复与清理  - 重试结果验证                    │   │
│  ├──────────────────────────────────────────────────┤   │
│  │  Layer 3: 修复策略层 (Repair Strategy)            │   │
│  │  - 参数修正  - 路径切换  - 环境修复  - 降级策略     │   │
│  ├──────────────────────────────────────────────────┤   │
│  │  Layer 2: 根因诊断层 (Root Cause Analysis)        │   │
│  │  - 错误分类  - 错误信息解析  - 上下文回溯           │   │
│  ├──────────────────────────────────────────────────┤   │
│  │  Layer 1: 错误检测层 (Error Detection)            │   │
│  │  - 静态检测  - 语义检测  - 验证器  - 置信度检测     │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  基础设施: Agent 执行引擎 + 工具调用 + 记忆系统     │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘
```

### 2.1 错误检测层（Error Detection）

错误检测是 Self-Healing 的第一道防线。Agent 需要能够**准确、及时地发现执行过程中的异常**。

#### 静态检测：工具返回的错误码与异常

最直接的错误检测方式，基于工具调用的返回结果：

```python
class StaticErrorDetector:
    """基于工具返回值的静态错误检测"""
    
    # 常见的错误码映射
    ERROR_PATTERNS = {
        "HTTP_4XX": r"(400|401|403|404|405|409|422)\s",
        "HTTP_5XX": r"(500|502|503|504)\s",
        "TIMEOUT": r"(timeout|timed?\s*out|deadline exceeded)",
        "AUTH": r"(unauthorized|forbidden|authentication|permission denied)",
        "NOT_FOUND": r"(not found|no such|does not exist|404)",
        "RATE_LIMIT": r"(rate limit|too many requests|429|throttl)",
        "PARSE_ERROR": r"(parse error|invalid json|syntax error|unexpected token)",
    }
    
    def detect(self, tool_result: dict) -> Optional[ErrorInfo]:
        """检测工具调用结果中的错误"""
        # 1. 检查显式错误标志
        if tool_result.get("is_error", False):
            return ErrorInfo(
                type="EXPLICIT_ERROR",
                message=tool_result.get("error_message", "Unknown error"),
                severity="HIGH"
            )
        
        # 2. 检查返回码
        status_code = tool_result.get("status_code")
        if status_code and status_code >= 400:
            return ErrorInfo(
                type=f"HTTP_{status_code}",
                message=tool_result.get("body", ""),
                severity="HIGH" if status_code >= 500 else "MEDIUM"
            )
        
        # 3. 检查错误模式匹配
        content = str(tool_result.get("content", ""))
        for error_type, pattern in self.ERROR_PATTERNS.items():
            if re.search(pattern, content, re.IGNORECASE):
                return ErrorInfo(
                    type=error_type,
                    message=content,
                    severity="MEDIUM"
                )
        
        return None  # 没有检测到错误
```

#### 语义检测：LLM 判断输出是否符合预期

有些错误不会抛出异常，但输出结果在语义上是不正确的（"静默错误"）：

```python
class SemanticErrorDetector:
    """基于 LLM 的语义错误检测"""
    
    def __init__(self, llm):
        self.llm = llm
    
    def detect(self, task: str, action: str, result: str) -> Optional[ErrorInfo]:
        """用 LLM 判断结果是否语义正确"""
        prompt = f"""你是一个严格的质量检测器。请判断以下工具调用的结果是否正确地
完成了预期操作。

用户任务：{task}
执行的动作：{action}
返回的结果：{result}

请回答：
1. 结果是否正确？(YES/NO)
2. 如果不正确，具体什么问题？
3. 置信度 (0-1)

请以 JSON 格式回答。"""

        response = self.llm.generate(prompt)
        judgment = json.loads(response)
        
        if judgment["is_correct"] == "NO":
            return ErrorInfo(
                type="SEMANTIC_ERROR",
                message=judgment["issue"],
                severity="HIGH" if judgment["confidence"] > 0.8 else "MEDIUM"
            )
        return None
```

典型的语义错误场景：
- 查询数据库返回了空结果，但应该有数据
- API 调用返回了 200，但响应体中包含业务层的错误码
- 搜索结果与查询意图不匹配
- 代码执行成功但输出不符合预期

#### 验证器检测：独立的 Verifier 模型

使用**专门训练的验证器模型**来判断每一步的正确性：

```python
class VerifierDetector:
    """基于独立验证器模型的错误检测"""
    
    def __init__(self, verifier_model):
        self.verifier = verifier_model
    
    def detect(self, trajectory: list, current_step: dict) -> Optional[ErrorInfo]:
        """使用验证器评估当前步骤"""
        # 构造验证器输入：完整轨迹 + 当前步骤
        verification_input = {
            "task": trajectory[0]["task"],
            "previous_steps": trajectory[1:],
            "current_step": current_step
        }
        
        # 验证器输出：该步骤是否正确，以及正确性得分
        score = self.verifier.evaluate(verification_input)
        
        if score < 0.5:  # 阈值可调
            return ErrorInfo(
                type="VERIFIER_REJECTION",
                message=f"Verifier score: {score:.3f} (below threshold)",
                severity="HIGH",
                metadata={"verifier_score": score}
            )
        return None
```

#### 置信度检测：模型自身的不确定性估计

通过检测模型输出的置信度来发现潜在问题：

```python
class ConfidenceDetector:
    """基于置信度的错误检测"""
    
    def detect_by_logprobs(self, llm_output) -> Optional[ErrorInfo]:
        """基于 token-level log probabilities 的检测"""
        if not llm_output.logprobs:
            return None
        
        # 计算平均 log probability
        avg_logprob = sum(llm_output.logprobs) / len(llm_output.logprobs)
        
        # 低置信度区域检测
        low_conf_tokens = [
            (i, lp) for i, lp in enumerate(llm_output.logprobs)
            if lp < -2.0  # 置信度阈值
        ]
        
        if avg_logprob < -1.5 or len(low_conf_tokens) > len(llm_output.logprobs) * 0.3:
            return ErrorInfo(
                type="LOW_CONFIDENCE",
                message=f"Average logprob: {avg_logprob:.3f}, "
                        f"{len(low_conf_tokens)} low-confidence tokens",
                severity="LOW"
            )
        return None
    
    def detect_by_consistency(self, llm, prompt, n_samples=5) -> Optional[ErrorInfo]:
        """基于多次采样一致性的检测"""
        responses = [llm.generate(prompt, temperature=0.7) for _ in range(n_samples)]
        
        # 计算响应之间的语义相似度
        similarities = []
        for i in range(len(responses)):
            for j in range(i + 1, len(responses)):
                sim = semantic_similarity(responses[i], responses[j])
                similarities.append(sim)
        
        avg_similarity = sum(similarities) / len(similarities)
        
        if avg_similarity < 0.6:  # 一致性低
            return ErrorInfo(
                type="INCONSISTENT_OUTPUT",
                message=f"Low consistency across {n_samples} samples: {avg_similarity:.3f}",
                severity="MEDIUM"
            )
        return None
```

### 2.2 根因诊断层（Root Cause Analysis）

检测到错误后，下一步是**诊断错误的根本原因**，这决定了后续采用何种修复策略。

#### 错误分类体系

```
┌─────────────────────────────────────────────┐
│             Agent 错误分类体系                │
│                                               │
│  ┌─────────────┐  ┌──────────────┐           │
│  │ 参数错误     │  │  环境错误     │           │
│  │ - 格式错误   │  │  - API 不可用 │           │
│  │ - 类型错误   │  │  - 超时       │           │
│  │ - 值域错误   │  │  - 权限不足   │           │
│  │ - 缺失参数   │  │  - 资源耗尽   │           │
│  └─────────────┘  └──────────────┘           │
│                                               │
│  ┌─────────────┐  ┌──────────────┐           │
│  │ 逻辑错误     │  │  数据错误     │           │
│  │ - 决策错误   │  │  - 数据缺失   │           │
│  │ - 顺序错误   │  │  - 数据过期   │           │
│  │ - 前提错误   │  │  - 格式不匹配 │           │
│  │ - 循环依赖   │  │  - 编码问题   │           │
│  └─────────────┘  └──────────────┘           │
└─────────────────────────────────────────────┘
```

#### 错误诊断器实现

```python
class RootCauseDiagnoser:
    """根因诊断器：分析错误的根本原因"""
    
    ERROR_CATEGORIES = {
        "PARAMETER_ERROR": {
            "patterns": ["invalid parameter", "missing required", "type error",
                        "validation failed", "unexpected value"],
            "sub_types": ["FORMAT", "TYPE", "RANGE", "MISSING"]
        },
        "ENVIRONMENT_ERROR": {
            "patterns": ["timeout", "connection refused", "503", "502",
                        "service unavailable", "network error"],
            "sub_types": ["TIMEOUT", "UNAVAILABLE", "NETWORK", "RESOURCE"]
        },
        "PERMISSION_ERROR": {
            "patterns": ["unauthorized", "forbidden", "access denied",
                        "permission", "401", "403"],
            "sub_types": ["AUTH", "AUTHZ", "TOKEN_EXPIRED"]
        },
        "LOGIC_ERROR": {
            "patterns": ["precondition", "conflict", "already exists",
                        "dependency", "circular"],
            "sub_types": ["PRECONDITION", "CONFLICT", "DEPENDENCY", "SEQUENCE"]
        },
        "DATA_ERROR": {
            "patterns": ["not found", "empty result", "null", "encoding",
                        "format error", "parse"],
            "sub_types": ["MISSING", "STALE", "FORMAT", "ENCODING"]
        }
    }
    
    def __init__(self, llm):
        self.llm = llm
    
    def diagnose(self, error: ErrorInfo, context: ExecutionContext) -> Diagnosis:
        """综合诊断错误根因"""
        
        # 阶段 1：基于模式匹配的快速分类
        category = self._pattern_match(error)
        
        # 阶段 2：基于 LLM 的深度诊断
        detailed_diagnosis = self._llm_diagnose(error, context, category)
        
        # 阶段 3：上下文回溯，确认根因
        root_cause = self._trace_back(error, context, detailed_diagnosis)
        
        return Diagnosis(
            category=category,
            sub_type=detailed_diagnosis["sub_type"],
            root_cause=root_cause,
            confidence=detailed_diagnosis["confidence"],
            suggested_fix=detailed_diagnosis["suggested_fix"]
        )
    
    def _pattern_match(self, error: ErrorInfo) -> str:
        """基于关键词匹配的快速错误分类"""
        error_text = error.message.lower()
        for category, info in self.ERROR_CATEGORIES.items():
            for pattern in info["patterns"]:
                if pattern in error_text:
                    return category
        return "UNKNOWN"
    
    def _llm_diagnose(self, error: ErrorInfo, context: ExecutionContext,
                      initial_category: str) -> dict:
        """使用 LLM 进行深度错误诊断"""
        prompt = f"""你是一个专业的 Agent 错误诊断专家。请分析以下错误的根本原因。

错误信息：{error.message}
错误类型：{error.type}
初步分类：{initial_category}

执行上下文：
- 当前任务：{context.task}
- 执行到第 {context.step_number} 步
- 之前的步骤：{json.dumps(context.previous_steps[-3:], ensure_ascii=False)}
- 当前动作：{context.current_action}
- 使用的工具：{context.tool_name}
- 工具参数：{json.dumps(context.tool_params, ensure_ascii=False)}

请分析：
1. 错误的根本原因（而非表面原因）
2. 错误的子类型
3. 是否是上游步骤导致的连锁错误
4. 修复建议
5. 诊断置信度 (0-1)

以 JSON 格式回答。"""

        response = self.llm.generate(prompt)
        return json.loads(response)
    
    def _trace_back(self, error: ErrorInfo, context: ExecutionContext,
                    diagnosis: dict) -> str:
        """上下文回溯，寻找真正的根因"""
        # 检查是否是级联错误
        if diagnosis.get("is_cascading", False):
            # 回溯之前的步骤，找到最初出错的位置
            for i in range(len(context.previous_steps) - 1, -1, -1):
                step = context.previous_steps[i]
                if step.get("has_warning") or step.get("partial_success"):
                    return f"根因在步骤 {i+1}：{step['action']}，" \
                           f"导致当前步骤 {context.step_number} 的级联失败"
        
        return diagnosis.get("root_cause", "无法确定根因")
```

### 2.3 修复策略层（Repair Strategy）

根据诊断结果，选择最适合的修复策略。

#### 四种核心修复策略

**策略一：参数修正（Parameter Fix）**

最轻量的修复方式，仅修正工具调用的参数：

```python
def fix_parameters(self, action: dict, diagnosis: Diagnosis) -> dict:
    """修正工具调用参数"""
    prompt = f"""工具调用失败，需要修正参数。

原始调用：
  工具：{action['tool']}
  参数：{json.dumps(action['params'], ensure_ascii=False)}

错误原因：{diagnosis.root_cause}

请生成修正后的参数（保持工具不变，只修改参数）。
以 JSON 格式返回修正后的参数。"""

    fixed_params = json.loads(self.llm.generate(prompt))
    return {"tool": action["tool"], "params": fixed_params}
```

**策略二：路径切换（Path Switch）**

换一种方法完成相同的任务：

```python
def switch_path(self, task: str, failed_approach: str, 
                available_tools: list) -> dict:
    """切换到替代执行路径"""
    prompt = f"""原来的方法失败了，请设计一个替代方案。

任务目标：{task}
失败的方法：{failed_approach}
可用工具列表：{json.dumps(available_tools, ensure_ascii=False)}

要求：
1. 使用不同的工具或方法来完成相同的任务
2. 避免使用导致失败的工具/方法
3. 解释为什么替代方案能够成功

以 JSON 格式返回新的执行计划。"""

    return json.loads(self.llm.generate(prompt))
```

**策略三：环境修复（Environment Fix）**

修复环境问题后重试：

```python
def fix_environment(self, diagnosis: Diagnosis) -> list:
    """生成环境修复步骤"""
    env_fixes = {
        "TIMEOUT": [
            {"action": "increase_timeout", "params": {"timeout": 60}},
            {"action": "retry_with_backoff", "params": {"base_delay": 2}}
        ],
        "AUTH": [
            {"action": "refresh_token", "params": {}},
            {"action": "retry_original", "params": {}}
        ],
        "RATE_LIMIT": [
            {"action": "wait", "params": {"seconds": 30}},
            {"action": "retry_original", "params": {}}
        ],
        "RESOURCE": [
            {"action": "cleanup_resources", "params": {}},
            {"action": "retry_original", "params": {}}
        ]
    }
    
    sub_type = diagnosis.sub_type
    if sub_type in env_fixes:
        return env_fixes[sub_type]
    
    # 如果没有预定义的修复方案，用 LLM 生成
    return self._llm_generate_env_fix(diagnosis)
```

**策略四：降级策略（Graceful Degradation）**

当上述策略都失败时，降低要求以保证部分完成：

```python
def graceful_degrade(self, task: str, completed_steps: list, 
                     error: ErrorInfo) -> dict:
    """降级策略：简化任务或返回部分结果"""
    prompt = f"""任务无法完全按原计划完成，请制定降级方案。

原始任务：{task}
已完成的步骤：{json.dumps(completed_steps, ensure_ascii=False)}
失败原因：{error.message}

请提供：
1. 基于已完成的工作，能给用户的最佳部分结果
2. 简化版的替代方案（降低精度或范围）
3. 需要人工介入的具体说明

以 JSON 格式返回降级方案。"""

    return json.loads(self.llm.generate(prompt))
```

#### 修复策略选择器

```python
class RepairStrategySelector:
    """根据诊断结果选择最优修复策略"""
    
    # 策略优先级矩阵：错误类型 → 修复策略（按优先级排列）
    STRATEGY_MATRIX = {
        "PARAMETER_ERROR": [
            ("fix_parameters", 0.8),   # 首选参数修正
            ("switch_path", 0.5),       # 备选路径切换
            ("graceful_degrade", 0.2)   # 最后降级
        ],
        "ENVIRONMENT_ERROR": [
            ("fix_environment", 0.7),   # 首选环境修复
            ("switch_path", 0.5),       # 备选路径切换
            ("graceful_degrade", 0.3)   # 最后降级
        ],
        "PERMISSION_ERROR": [
            ("fix_environment", 0.6),   # 尝试刷新令牌
            ("switch_path", 0.4),       # 换一种不需要权限的方式
            ("graceful_degrade", 0.3)   # 降级
        ],
        "LOGIC_ERROR": [
            ("switch_path", 0.7),       # 逻辑错误最好换路径
            ("fix_parameters", 0.4),    # 可能是参数引起的逻辑问题
            ("graceful_degrade", 0.3)   # 降级
        ],
        "DATA_ERROR": [
            ("fix_parameters", 0.6),    # 修正数据参数
            ("switch_path", 0.5),       # 换数据源
            ("graceful_degrade", 0.4)   # 降级
        ]
    }
    
    def select(self, diagnosis: Diagnosis, retry_count: int) -> str:
        """选择修复策略"""
        strategies = self.STRATEGY_MATRIX.get(
            diagnosis.category, 
            [("graceful_degrade", 0.5)]
        )
        
        # 随着重试次数增加，倾向于选择更激进的策略
        if retry_count == 0:
            return strategies[0][0]  # 首选策略
        elif retry_count == 1:
            return strategies[1][0] if len(strategies) > 1 else strategies[0][0]
        else:
            return strategies[-1][0]  # 最后的策略（通常是降级）
```

### 2.4 重试执行层（Retry Execution）

修复策略生成后，需要可靠地执行重试。

```python
class RetryExecutor:
    """重试执行器：控制重试行为"""
    
    def __init__(self, max_retries=3, base_delay=1.0, max_delay=60.0):
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.max_delay = max_delay
    
    async def execute_with_healing(self, agent, action: dict, 
                                    context: ExecutionContext) -> Result:
        """带 Self-Healing 的执行"""
        retry_count = 0
        error_history = []
        
        while retry_count <= self.max_retries:
            # 执行动作
            result = await agent.execute_action(action)
            
            # 检测错误
            error = agent.error_detector.detect(result)
            
            if error is None:
                # 验证结果正确性
                if await self._verify_result(agent, action, result, context):
                    return Result(success=True, data=result, retries=retry_count)
                else:
                    error = ErrorInfo(type="VERIFICATION_FAILED",
                                    message="Result failed verification")
            
            # 记录错误历史
            error_history.append({
                "retry": retry_count,
                "error": error,
                "action": action
            })
            
            if retry_count >= self.max_retries:
                break
            
            # 诊断根因
            diagnosis = agent.diagnoser.diagnose(error, context)
            
            # 选择修复策略
            strategy = agent.strategy_selector.select(diagnosis, retry_count)
            
            # 生成修复后的动作
            action = await self._apply_repair(agent, strategy, action, 
                                               diagnosis, context)
            
            # 等待（指数退避）
            delay = min(self.base_delay * (2 ** retry_count), self.max_delay)
            # 环境错误等待更久，参数错误立即重试
            if diagnosis.category == "ENVIRONMENT_ERROR":
                delay *= 2
            elif diagnosis.category == "PARAMETER_ERROR":
                delay = 0
            
            if delay > 0:
                await asyncio.sleep(delay)
            
            retry_count += 1
        
        # 所有重试都失败，触发降级
        degraded_result = agent.repair.graceful_degrade(
            context.task, context.completed_steps, error_history[-1]["error"]
        )
        return Result(
            success=False, 
            data=degraded_result,
            retries=retry_count,
            error_history=error_history
        )
    
    async def _verify_result(self, agent, action, result, context) -> bool:
        """验证执行结果"""
        verification = agent.semantic_detector.detect(
            context.task, str(action), str(result)
        )
        return verification is None  # None 表示没有检测到错误
    
    async def _apply_repair(self, agent, strategy, action, 
                             diagnosis, context) -> dict:
        """应用修复策略，返回新的动作"""
        if strategy == "fix_parameters":
            return agent.repair.fix_parameters(action, diagnosis)
        elif strategy == "switch_path":
            plan = agent.repair.switch_path(
                context.task, str(action), context.available_tools
            )
            return plan["next_action"]
        elif strategy == "fix_environment":
            env_steps = agent.repair.fix_environment(diagnosis)
            for step in env_steps[:-1]:
                await agent.execute_action(step)
            return env_steps[-1] if env_steps[-1]["action"] != "retry_original" \
                   else action
        else:
            return action  # 使用原始动作重试
```

---

## 三、Reflexion 深入实践

### 3.1 Reflexion 的核心机制

Reflexion 的核心创新在于：**用自然语言反思（verbal reflection）替代传统强化学习中的梯度更新**，使得 LLM Agent 无需参数更新就能从失败中学习。

#### 核心思想

```
传统 RL：
  策略更新 θ ← θ + α · ∇J(θ)    # 需要梯度计算
  
Reflexion（语言 RL）：
  经验更新 M ← M ∪ {reflection}    # 只需要自然语言反思
  下次决策时：π(a|s, M)             # 将反思作为额外上下文
```

这意味着 Reflexion 不修改模型权重，而是通过**扩展上下文**来改变模型的行为。

#### 三大核心组件

```
┌───────────────────────────────────────────────────┐
│              Reflexion 三大核心组件                  │
│                                                     │
│  ┌──────────────────────────────────────────────┐  │
│  │  1. Actor（行动者）                           │  │
│  │  - 基于 LLM 的策略函数                         │  │
│  │  - 输入：任务 + 记忆中的反思                     │  │
│  │  - 输出：动作序列                               │  │
│  ├──────────────────────────────────────────────┤  │
│  │  2. Evaluator（评估者）                       │  │
│  │  - 评估 Actor 执行结果的质量                    │  │
│  │  - 可以是：单元测试 / LLM 裁判 / 外部验证器     │  │
│  │  - 输出：成功/失败 + 反馈信号                    │  │
│  ├──────────────────────────────────────────────┤  │
│  │  3. Self-Reflection（自我反思器）              │  │
│  │  - 分析失败原因                                 │  │
│  │  - 生成文字形式的改进建议                        │  │
│  │  - 存入持久化记忆                               │  │
│  └──────────────────────────────────────────────┘  │
│                                                     │
│  辅助组件：Memory（经验记忆库）                      │
│  - 存储所有反思                                     │
│  - 在下次尝试时作为额外上下文                        │
└───────────────────────────────────────────────────┘
```

### 3.2 Reflexion 在 Agent 中的应用

#### 完整的 Reflexion Agent 实现

```python
class ReflexionAgent:
    """完整的 Reflexion Agent 实现"""
    
    def __init__(self, llm, tools, evaluator, max_trials=5):
        self.llm = llm
        self.tools = tools
        self.evaluator = evaluator
        self.max_trials = max_trials
        self.reflection_memory = []  # 经验记忆库
    
    def run(self, task: str) -> dict:
        """执行任务，失败时通过反思逐步改进"""
        
        for trial in range(self.max_trials):
            print(f"\n=== 第 {trial + 1} 次尝试 ===")
            
            # Step 1: 行动 — 执行任务
            trajectory = self._act(task, trial)
            
            # Step 2: 评估 — 判断结果
            evaluation = self._evaluate(task, trajectory)
            
            if evaluation["success"]:
                print(f"任务在第 {trial + 1} 次尝试后成功完成！")
                return {
                    "success": True,
                    "result": trajectory[-1]["result"],
                    "trials": trial + 1,
                    "reflections": self.reflection_memory
                }
            
            # Step 3: 反思 — 分析失败原因并存储
            reflection = self._reflect(task, trajectory, evaluation)
            self.reflection_memory.append(reflection)
            print(f"反思：{reflection}")
        
        return {
            "success": False,
            "trials": self.max_trials,
            "reflections": self.reflection_memory
        }
    
    def _act(self, task: str, trial: int) -> list:
        """行动阶段：基于任务和反思记忆执行"""
        
        # 构建提示词，包含之前的反思
        reflections_text = ""
        if self.reflection_memory:
            reflections_text = "\n\n## 之前的尝试和反思\n"
            for i, ref in enumerate(self.reflection_memory):
                reflections_text += f"\n### 第 {i+1} 次尝试的反思：\n{ref}\n"
        
        system_prompt = f"""你是一个能力强大的 AI Agent。你可以使用以下工具完成任务：
{self._format_tools()}

{reflections_text}

重要：请仔细阅读之前的反思，避免重复相同的错误。"""
        
        trajectory = []
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": f"请完成以下任务：{task}"}
        ]
        
        # ReAct 循环
        for step in range(20):  # 最大步数限制
            response = self.llm.generate(messages, tools=self.tools)
            
            if response.is_final_answer:
                trajectory.append({
                    "type": "answer",
                    "result": response.content
                })
                break
            
            if response.tool_call:
                # 执行工具调用
                tool_result = self._execute_tool(response.tool_call)
                trajectory.append({
                    "type": "action",
                    "thought": response.thought,
                    "tool": response.tool_call.name,
                    "params": response.tool_call.params,
                    "result": tool_result
                })
                messages.append({"role": "assistant", "content": response.raw})
                messages.append({"role": "tool", "content": str(tool_result)})
        
        return trajectory
    
    def _evaluate(self, task: str, trajectory: list) -> dict:
        """评估阶段：判断执行结果"""
        
        # 方式 1：如果有外部验证器（如单元测试）
        if hasattr(self.evaluator, 'run_tests'):
            test_result = self.evaluator.run_tests(trajectory[-1]["result"])
            return {
                "success": test_result.all_passed,
                "feedback": test_result.details,
                "score": test_result.pass_rate
            }
        
        # 方式 2：LLM 裁判评估
        eval_prompt = f"""请评估以下任务执行结果。

任务：{task}
执行过程：{json.dumps(trajectory, ensure_ascii=False, indent=2)}

请判断：
1. 任务是否成功完成？(true/false)
2. 完成质量评分 (0-1)
3. 如果失败，具体哪些方面不满足要求？
4. 详细反馈

以 JSON 格式回答。"""
        
        return json.loads(self.llm.generate(eval_prompt))
    
    def _reflect(self, task: str, trajectory: list, 
                 evaluation: dict) -> str:
        """反思阶段：分析失败原因，生成改进建议"""
        
        # 包含之前的反思，避免重复反思
        previous = "\n".join([f"- {r}" for r in self.reflection_memory]) \
                   if self.reflection_memory else "无"
        
        reflect_prompt = f"""你之前执行任务失败了。请深入分析失败原因，并给出具体的改进建议。

任务：{task}

执行轨迹：
{json.dumps(trajectory, ensure_ascii=False, indent=2)}

评估反馈：
{json.dumps(evaluation, ensure_ascii=False, indent=2)}

之前的反思（请避免重复相同的建议）：
{previous}

请生成一段简洁、具体、可执行的反思：
1. 精确指出失败的根本原因（不要模糊描述）
2. 给出具体的改进步骤（不要泛泛而谈）
3. 特别说明下次应该避免什么、应该改做什么

反思："""
        
        return self.llm.generate(reflect_prompt)
    
    def _format_tools(self) -> str:
        """格式化工具描述"""
        return "\n".join([f"- {t.name}: {t.description}" for t in self.tools])
    
    def _execute_tool(self, tool_call) -> str:
        """执行工具调用"""
        tool = next(t for t in self.tools if t.name == tool_call.name)
        return tool.execute(**tool_call.params)
```

#### 反思提示的设计要点

反思质量直接决定了 Reflexion 的效果。好的反思提示应该满足：

```
✅ 好的反思示例：
"在第3步查询用户信息时，我使用了 get_user(name='张三') 但 API 
要求用 user_id 而非 name 作为参数。下次应该先调用 search_user(name='张三') 
获取 user_id，再用 get_user(id=xxx) 查询详情。"

❌ 差的反思示例：
"我在执行过程中遇到了错误，下次我会更仔细地执行任务。"
```

关键设计原则：
1. **具体性**：指出哪一步、哪个参数、哪个调用出了问题
2. **因果性**：解释为什么出错（不仅是出了什么错）
3. **可执行性**：下次应该具体怎么做，而非"会更仔细"
4. **差异性**：每次反思应该带来新的见解，避免重复

#### 多轮反思的收敛性

Reflexion 的一个重要特性是**收敛性**——反思是否真的能让 Agent 越来越好？

实验数据表明（来自原论文）：

| 尝试次数 | HumanEval Pass@1 | ALFWorld 成功率 | WebShop 成功率 |
|---------|------------------|----------------|---------------|
| 1 (无反思) | 80.1% | 63% | 40% |
| 2 | 86.0% | 77% | 53% |
| 3 | 88.3% | 83% | 58% |
| 4 | 90.9% | 88% | 60% |
| 5 | 91.0% | 90% | 60% |

可以观察到：
- 前 2-3 轮反思改进最大（边际收益递减）
- 最终会收敛到一个上限（受模型能力限制）
- 不同任务的收敛速度不同

### 3.3 Reflexion 的局限与改进

#### 局限一：反思可能陷入循环

```
尝试 1: 用方法 A 失败
反思: "应该用方法 B"

尝试 2: 用方法 B 失败  
反思: "方法 B 的参数不对，应该用方法 A"  ← 回到了 A！

尝试 3: 用方法 A 失败（又回来了）
反思: "应该用方法 B"  ← 死循环
```

**解决方案**：反思去重 + 探索机制

```python
def _deduplicated_reflect(self, new_reflection: str) -> str:
    """去重反思：避免重复相同的建议"""
    if not self.reflection_memory:
        return new_reflection
    
    # 检查新反思是否与已有反思过于相似
    for old_ref in self.reflection_memory:
        similarity = semantic_similarity(new_reflection, old_ref)
        if similarity > 0.85:
            # 强制生成不同的反思
            prompt = f"""以下反思与之前的反思过于相似，请生成一个完全不同的改进方向。

已有反思：{old_ref}
重复的反思：{new_reflection}

请从完全不同的角度分析问题，提出全新的解决思路："""
            return self.llm.generate(prompt)
    
    return new_reflection
```

#### 局限二：反思质量依赖模型能力

弱模型可能无法生成有价值的反思。

**改进方向**：

1. **外部验证器**：用独立的验证器提供客观反馈
2. **结构化反思模板**：限制反思的格式，降低自由度

```python
STRUCTURED_REFLECTION_TEMPLATE = """
请按以下结构进行反思：

## 失败步骤定位
- 具体失败的步骤编号：___
- 失败的工具调用：___
- 错误信息：___

## 根因分析（选择一个）
□ 参数错误（具体哪个参数，应该是什么值）
□ 工具选择错误（应该用什么工具替代）
□ 执行顺序错误（正确的顺序应该是）
□ 前提条件不满足（缺少什么前置步骤）
□ 数据理解错误（对数据的什么理解有误）

## 下次改进计划
1. 第一步应该做：___
2. 关键注意事项：___
3. 应该避免的错误：___
"""
```

---

## 四、LATS（Language Agent Tree Search）

### 4.1 LATS 的核心思想

LATS（Language Agent Tree Search, Zhou et al., 2024）是一种将**树搜索、反思和 Self-Healing 统一在一个框架**中的方法。它的核心思想是：

> 将 Agent 的执行过程建模为一棵搜索树，每个节点是一个状态，每条边是一个动作。通过蒙特卡洛树搜索（MCTS）的策略来探索最优的执行路径。

```
                      LATS 搜索树示例
                      
                          Root
                        (初始状态)
                       /    |     \
                    a₁     a₂      a₃
                   /  \     |     /   \
                 s₁   s₂   s₃   s₄    s₅
                 ✓     ✗    |    ✓      |
                      反思  a₄         a₅
                            |           |
                           s₆          s₇
                            ✓           ✗
                                       反思

说明：
- 每个节点 sᵢ 是一个执行状态
- 每条边 aᵢ 是一个动作
- ✓ 表示该路径成功，✗ 表示该路径失败
- 失败节点触发反思，反思结果影响后续搜索
```

#### LATS 与前序方法的关系

```
┌─────────────────────────────────────────────────────────┐
│                    方法对比                                │
│                                                           │
│  ReAct:       线性执行  S₁ → S₂ → S₃ → ... → Sₙ         │
│               （单一路径，无回溯）                           │
│                                                           │
│  Reflexion:   多次尝试  Trial₁ → 反思 → Trial₂ → ...     │
│               （全量重试，有反思）                           │
│                                                           │
│  ToT:         树搜索    BFS/DFS 搜索多条路径               │
│               （有回溯，无反思）                             │
│                                                           │
│  LATS:        树搜索 + 反思 + MCTS                        │
│               （有回溯，有反思，有价值估计）                  │
└─────────────────────────────────────────────────────────┘
```

### 4.2 LATS 的四个步骤

LATS 在每次迭代中执行四个步骤，与 MCTS 的四个阶段对应：

#### Step 1: 选择（Selection）

从根节点出发，根据 UCB（Upper Confidence Bound）策略选择最有潜力的节点进行扩展：

```python
def select(self, node: TreeNode) -> TreeNode:
    """UCB 策略选择最优节点"""
    while node.children:
        if not all(child.visited for child in node.children):
            # 存在未访问的子节点，优先探索
            return next(c for c in node.children if not c.visited)
        
        # UCB1 公式选择
        node = max(node.children, key=lambda c: self._ucb_score(c))
    
    return node

def _ucb_score(self, node: TreeNode, c=1.414) -> float:
    """UCB1 得分 = 利用 + 探索"""
    if node.visits == 0:
        return float('inf')
    
    exploitation = node.value / node.visits
    exploration = c * math.sqrt(math.log(node.parent.visits) / node.visits)
    
    return exploitation + exploration
```

#### Step 2: 扩展（Expansion）

在选定的节点上，用 LLM 生成多个可能的下一步动作：

```python
def expand(self, node: TreeNode, n_children=5) -> list:
    """生成多个候选动作进行扩展"""
    prompt = f"""当前状态：
{node.state.description}

任务目标：{self.task}
已执行的步骤：{node.get_trajectory()}

请生成 {n_children} 个不同的下一步动作方案，每个方案用不同的策略。
考虑多样性：不要生成太相似的动作。

以 JSON 数组格式返回。"""
    
    actions = json.loads(self.llm.generate(prompt, temperature=0.8))
    
    children = []
    for action in actions:
        # 执行动作，获得新状态
        new_state = self.environment.step(node.state, action)
        child = TreeNode(
            state=new_state,
            action=action,
            parent=node
        )
        children.append(child)
    
    node.children = children
    return children
```

#### Step 3: 评估（Evaluation）

用 LLM 评估每个新节点的价值：

```python
def evaluate(self, node: TreeNode) -> float:
    """用 LLM 评估节点价值"""
    # 检查是否是终止状态
    if node.state.is_terminal:
        return 1.0 if node.state.is_success else 0.0
    
    prompt = f"""请评估当前执行状态对完成任务的贡献程度。

任务：{self.task}
执行轨迹：{node.get_trajectory()}
当前状态：{node.state.description}

请给出一个 0 到 1 之间的分数：
- 1.0 = 任务已完成
- 0.8 = 非常接近完成
- 0.5 = 在正确方向上，但还有较多工作
- 0.2 = 偏离目标，但可能纠正
- 0.0 = 完全失败

只返回一个数字。"""
    
    score = float(self.llm.generate(prompt))
    return score
```

#### Step 4: 反向传播 + 反思（Backpropagation + Reflection）

将评估结果反向传播到祖先节点，失败时生成反思：

```python
def backpropagate(self, node: TreeNode, value: float):
    """将评估结果反向传播"""
    current = node
    while current is not None:
        current.visits += 1
        current.value += value
        current = current.parent

def reflect_on_failure(self, failed_node: TreeNode) -> str:
    """对失败路径进行反思"""
    trajectory = failed_node.get_trajectory()
    
    prompt = f"""以下执行路径失败了，请分析原因并提供改进建议。

任务：{self.task}
执行轨迹：{json.dumps(trajectory, ensure_ascii=False)}
失败状态：{failed_node.state.description}

请给出：
1. 失败的根本原因
2. 从哪一步开始偏离了正确方向
3. 如果重新从该步开始，应该怎么做

反思："""
    
    reflection = self.llm.generate(prompt)
    # 将反思存储，供后续搜索使用
    self.reflections.append(reflection)
    return reflection
```

### 4.3 LATS vs ReAct vs Reflexion

#### 全面对比

| 维度 | ReAct | Reflexion | LATS |
|------|-------|-----------|------|
| 搜索策略 | 贪心（线性） | 重启式搜索 | 树搜索（MCTS） |
| 回溯能力 | 无 | 全量重试 | 局部回溯 |
| 反思机制 | 无 | 有（核心） | 有（辅助） |
| 多样性 | 低（单路径） | 中（逐轮改进） | 高（多分支） |
| LLM 调用量 | 低 | 中 | 高 |
| 适用场景 | 简单任务 | 可重试的任务 | 复杂决策任务 |
| 内存开销 | O(n) | O(n×k) | O(b^d) |
| 典型任务 | 简单问答 | 代码生成 | 复杂规划 |

其中：n = 步骤数，k = 反思次数，b = 分支因子，d = 深度。

#### 计算成本分析

```
假设一个 10 步的任务：

ReAct:     10 次 LLM 调用
Reflexion: 10 × 3(尝试次数) + 3(反思) = 33 次 LLM 调用
LATS:      10 × 5(分支) + 50(评估) + 5(反思) = 105 次 LLM 调用
```

LATS 的 token 开销大约是 ReAct 的 10 倍、Reflexion 的 3 倍。因此在实际工程中，LATS 通常用于高价值、高复杂度的任务。

#### 简化版 LATS 实现

```python
class SimplifiedLATS:
    """简化版 LATS 实现"""
    
    def __init__(self, llm, tools, max_iterations=20, 
                 n_expansions=3, max_depth=10):
        self.llm = llm
        self.tools = tools
        self.max_iterations = max_iterations
        self.n_expansions = n_expansions
        self.max_depth = max_depth
        self.reflections = []
    
    def solve(self, task: str) -> dict:
        """使用 LATS 解决任务"""
        root = TreeNode(state=State(task=task), action=None, parent=None)
        
        for iteration in range(self.max_iterations):
            # Step 1: 选择
            node = self._select(root)
            
            if node.depth >= self.max_depth:
                continue
            
            # Step 2: 扩展
            children = self._expand(node)
            
            for child in children:
                # Step 3: 评估
                value = self._evaluate(child)
                child.value = value
                
                # 检查是否找到解
                if child.state.is_terminal and child.state.is_success:
                    return {
                        "success": True,
                        "trajectory": child.get_trajectory(),
                        "iterations": iteration + 1
                    }
                
                # 失败节点触发反思
                if child.state.is_terminal and not child.state.is_success:
                    reflection = self._reflect(child)
                    self.reflections.append(reflection)
                
                # Step 4: 反向传播
                self._backpropagate(child, value)
        
        # 返回最佳路径
        best_leaf = self._find_best_leaf(root)
        return {
            "success": False,
            "best_trajectory": best_leaf.get_trajectory(),
            "reflections": self.reflections
        }
    
    def _select(self, root: TreeNode) -> TreeNode:
        node = root
        while node.children:
            unvisited = [c for c in node.children if c.visits == 0]
            if unvisited:
                return random.choice(unvisited)
            node = max(node.children, 
                      key=lambda c: c.value / max(c.visits, 1) + 
                                    1.414 * math.sqrt(math.log(node.visits) / 
                                                      max(c.visits, 1)))
        return node
    
    def _expand(self, node: TreeNode) -> list:
        """生成多个候选动作"""
        reflections_ctx = ""
        if self.reflections:
            reflections_ctx = f"\n之前的失败反思：\n" + \
                             "\n".join(self.reflections[-3:])
        
        prompt = f"""任务：{node.state.task}
已执行：{node.get_trajectory()}
当前状态：{node.state.description}
{reflections_ctx}

请生成 {self.n_expansions} 个不同的下一步动作。
返回 JSON 数组，每个元素包含 tool 和 params。"""
        
        actions = json.loads(self.llm.generate(prompt, temperature=0.8))
        children = []
        for action in actions[:self.n_expansions]:
            result = self._execute_action(action)
            new_state = State(
                task=node.state.task,
                description=f"{node.state.description}\n-> {action}: {result}",
                is_terminal=self._check_terminal(result),
                is_success=self._check_success(result, node.state.task)
            )
            child = TreeNode(state=new_state, action=action, parent=node)
            node.children.append(child)
            children.append(child)
        return children
    
    def _evaluate(self, node: TreeNode) -> float:
        if node.state.is_terminal:
            return 1.0 if node.state.is_success else 0.0
        prompt = f"评估完成度(0-1)。任务：{node.state.task}\n" \
                 f"当前进展：{node.state.description}\n只返回数字。"
        return float(self.llm.generate(prompt))
    
    def _backpropagate(self, node: TreeNode, value: float):
        current = node
        while current:
            current.visits += 1
            current.total_value += value
            current = current.parent
    
    def _reflect(self, failed_node: TreeNode) -> str:
        prompt = f"""执行失败。轨迹：{failed_node.get_trajectory()}
分析失败原因，给出改进建议（100字以内）。"""
        return self.llm.generate(prompt)
    
    def _find_best_leaf(self, root: TreeNode) -> TreeNode:
        best = root
        queue = [root]
        while queue:
            node = queue.pop(0)
            if not node.children and node.value > best.value:
                best = node
            queue.extend(node.children)
        return best
```

---

## 五、Verifier 模型与置信度校准

### 5.1 Verifier 模型

Verifier（验证器）是一种专门用于**评判 Agent 输出正确性**的模型或机制。它与 Generator（生成器）形成互补关系。

#### 独立验证器 vs 自验证

```
┌──────────────────────────────────────────────────────┐
│           独立验证器 vs 自验证                          │
│                                                        │
│  独立验证器 (Separate Verifier):                       │
│  ┌─────────┐    output    ┌───────────┐              │
│  │Generator│ ──────────→  │ Verifier  │ → 正确/错误   │
│  │(生成器)  │              │(独立模型)  │              │
│  └─────────┘              └───────────┘              │
│  优势：独立判断，避免自我偏见                            │
│  劣势：需要额外的模型训练和部署                          │
│                                                        │
│  自验证 (Self-Verification):                           │
│  ┌─────────────────────────────────────┐              │
│  │     同一个 LLM                       │              │
│  │  生成 output → 用不同 prompt 验证    │              │
│  └─────────────────────────────────────┘              │
│  优势：无需额外模型，部署简单                            │
│  劣势：容易陷入自我确认偏见                              │
└──────────────────────────────────────────────────────┘
```

#### 训练 Verifier 的方法

训练一个高质量的 Verifier 需要以下步骤：

**方法一：Outcome Reward Model（ORM）**

只看最终结果是否正确：

```python
def train_orm(training_data):
    """
    ORM 训练数据格式：
    [
        {"solution": "完整的解题过程", "is_correct": True/False},
        ...
    ]
    """
    model = AutoModelForSequenceClassification.from_pretrained("base_model")
    
    for batch in training_data:
        # 输入：完整的解题过程
        # 标签：0 (错误) 或 1 (正确)
        loss = model(batch["solution"], labels=batch["is_correct"])
        loss.backward()
        optimizer.step()
```

**方法二：Process Reward Model（PRM）**

评估解题过程中**每一步**的正确性（更精细）：

```python
def train_prm(training_data):
    """
    PRM 训练数据格式：
    [
        {
            "steps": ["步骤1", "步骤2", "步骤3"],
            "step_labels": [1, 1, 0]  # 每步是否正确
        },
        ...
    ]
    """
    model = StepLevelRewardModel.from_pretrained("base_model")
    
    for batch in training_data:
        for i, (step, label) in enumerate(zip(batch["steps"], 
                                                batch["step_labels"])):
            # 输入：前 i+1 步的内容
            context = "\n".join(batch["steps"][:i+1])
            # 预测该步骤的正确性
            loss = model(context, labels=label)
            loss.backward()
        optimizer.step()
```

PRM 相比 ORM 的优势在于：能够更早发现错误，避免在错误路径上浪费计算资源。

#### Verifier 在 Agent 中的应用

```python
class VerifierGuidedAgent:
    """Verifier 引导的 Agent"""
    
    def __init__(self, generator, verifier, n_candidates=5):
        self.generator = generator
        self.verifier = verifier
        self.n_candidates = n_candidates
    
    def act(self, state: str) -> dict:
        """生成多个候选动作，用 Verifier 选择最佳"""
        # 1. 生成多个候选动作
        candidates = []
        for _ in range(self.n_candidates):
            action = self.generator.generate(state, temperature=0.7)
            candidates.append(action)
        
        # 2. 用 Verifier 评估每个候选
        scored_candidates = []
        for candidate in candidates:
            score = self.verifier.evaluate(state, candidate)
            scored_candidates.append((candidate, score))
        
        # 3. 选择得分最高的候选
        scored_candidates.sort(key=lambda x: x[1], reverse=True)
        best_candidate, best_score = scored_candidates[0]
        
        # 4. 如果最佳候选的分数也很低，触发 Self-Healing
        if best_score < 0.3:
            return self._trigger_healing(state, scored_candidates)
        
        return best_candidate
    
    def _trigger_healing(self, state, candidates):
        """所有候选得分都低时的修复策略"""
        prompt = f"""所有生成的动作方案得分都很低，可能存在系统性问题。

当前状态：{state}
候选方案及得分：
{json.dumps([(str(c), s) for c, s in candidates], ensure_ascii=False)}

请分析可能的原因，并生成一个全新的方案。"""
        return self.generator.generate(prompt, temperature=0.3)
```

### 5.2 置信度估计

#### LLM 的置信度问题

一个关键挑战是：**LLM 往往不知道自己不知道什么**。模型可能以非常自信的口吻给出完全错误的答案（幻觉）。

#### 口头置信度（Verbalized Confidence）

最直接的方法——让模型自己报告置信度：

```python
def get_verbalized_confidence(llm, question: str, answer: str) -> float:
    """让模型口头报告置信度"""
    prompt = f"""你之前回答了以下问题：

问题：{question}
你的答案：{answer}

请评估你对这个答案的置信度。
- 1.0 = 完全确定正确
- 0.7 = 比较有把握
- 0.5 = 不太确定
- 0.3 = 可能有误
- 0.1 = 很可能是错的

只返回一个 0 到 1 之间的数字。"""
    
    confidence = float(llm.generate(prompt))
    return confidence
```

> 注意：研究表明口头置信度往往偏高（过度自信），需要校准。

#### 语义置信度：多次采样的一致性

更可靠的方法——通过多次采样观察输出的一致性：

```python
def semantic_confidence(llm, prompt: str, n_samples: int = 10) -> dict:
    """基于多次采样一致性的置信度估计"""
    # 多次采样
    responses = []
    for _ in range(n_samples):
        resp = llm.generate(prompt, temperature=0.7)
        responses.append(resp)
    
    # 语义聚类
    clusters = semantic_cluster(responses)  # 按语义相似度聚类
    
    # 最大聚类的比例 = 置信度
    largest_cluster_size = max(len(c) for c in clusters)
    consistency = largest_cluster_size / n_samples
    
    # 聚类数量反映不确定性
    n_clusters = len(clusters)
    
    return {
        "confidence": consistency,
        "n_clusters": n_clusters,
        "majority_answer": clusters[0][0],  # 最大聚类的代表答案
        "is_confident": consistency > 0.7 and n_clusters <= 2
    }
```

#### 置信度驱动的自动回退

```python
class ConfidenceDrivenAgent:
    """置信度驱动的 Agent：低置信度时自动回退"""
    
    def __init__(self, llm, confidence_threshold=0.6):
        self.llm = llm
        self.threshold = confidence_threshold
    
    def act(self, task: str) -> dict:
        # 生成动作
        action = self.llm.generate(task)
        
        # 估计置信度
        conf = semantic_confidence(self.llm, task)
        
        if conf["confidence"] >= self.threshold:
            return {"action": action, "confidence": conf["confidence"]}
        
        # 低置信度：触发回退策略
        return self._fallback(task, action, conf)
    
    def _fallback(self, task, action, conf):
        """回退策略"""
        if conf["confidence"] > 0.4:
            # 中等置信度：请求更多信息
            return {
                "action": "request_clarification",
                "message": f"我对这个任务的理解不够确定，" \
                           f"置信度仅 {conf['confidence']:.0%}。" \
                           f"请提供更多信息。"
            }
        else:
            # 低置信度：升级到人类
            return {
                "action": "escalate_to_human",
                "message": f"此任务超出我的能力范围，" \
                           f"置信度仅 {conf['confidence']:.0%}。" \
                           f"建议由人工处理。",
                "attempted_action": action
            }
```

### 5.3 校准方法

模型的原始置信度通常需要校准（calibration）才能反映真实的正确概率。

#### Temperature Scaling

```python
class TemperatureScaling:
    """温度缩放校准"""
    
    def __init__(self):
        self.temperature = 1.0  # 初始温度
    
    def calibrate(self, val_logits: list, val_labels: list):
        """在验证集上学习最优温度"""
        # 通过最小化 NLL 来学习温度参数
        best_temp = 1.0
        best_nll = float('inf')
        
        for temp in [0.1, 0.2, 0.5, 0.7, 1.0, 1.5, 2.0, 3.0, 5.0]:
            nll = self._compute_nll(val_logits, val_labels, temp)
            if nll < best_nll:
                best_nll = nll
                best_temp = temp
        
        self.temperature = best_temp
    
    def calibrated_confidence(self, logits: float) -> float:
        """用学习到的温度缩放置信度"""
        return sigmoid(logits / self.temperature)
    
    def _compute_nll(self, logits, labels, temp):
        """计算负对数似然"""
        probs = [sigmoid(l / temp) for l in logits]
        nll = -sum(y * math.log(p + 1e-10) + (1 - y) * math.log(1 - p + 1e-10)
                  for p, y in zip(probs, labels))
        return nll / len(labels)
```

#### 多采样投票校准

```python
def voting_calibration(llm, prompt: str, n_votes: int = 11) -> dict:
    """多采样投票：用多数投票结果作为校准后的答案"""
    votes = []
    for _ in range(n_votes):
        response = llm.generate(prompt, temperature=0.7)
        answer = extract_answer(response)  # 提取核心答案
        votes.append(answer)
    
    # 统计投票
    vote_counts = Counter(votes)
    winner, winner_count = vote_counts.most_common(1)[0]
    
    calibrated_confidence = winner_count / n_votes
    
    return {
        "answer": winner,
        "confidence": calibrated_confidence,
        "vote_distribution": dict(vote_counts),
        "unanimous": winner_count == n_votes
    }
```

---

## 六、自进化 Agent（Self-Evolving Agent）

### 6.1 什么是自进化 Agent

传统 Agent 在部署后其能力是静态的——它只能用预设的工具、预训练的知识来完成任务。而**自进化 Agent（Self-Evolving Agent）**能够在运行过程中持续改进自身能力：

```
传统 Agent：
  训练/配置 → 部署 → 执行任务（能力固定）

自进化 Agent：
  训练/配置 → 部署 → 执行任务 → 反思与学习 → 能力增强 → 执行更难的任务 → ...
```

#### 四大进化支柱

```
┌────────────────────────────────────────────────────┐
│            自进化 Agent 的四大进化支柱               │
│                                                      │
│  ┌──────────────┐    ┌──────────────┐               │
│  │  模型进化     │    │  记忆进化     │               │
│  │ Model Evo.   │    │ Memory Evo.  │               │
│  │ - 自训练      │    │ - 经验积累   │               │
│  │ - 偏好学习    │    │ - 知识蒸馏   │               │
│  │ - 课程学习    │    │ - 检索优化   │               │
│  └──────────────┘    └──────────────┘               │
│                                                      │
│  ┌──────────────┐    ┌──────────────┐               │
│  │  工具进化     │    │  架构进化     │               │
│  │ Tool Evo.    │    │ Arch. Evo.   │               │
│  │ - 工具发现   │    │ - 流程优化   │               │
│  │ - 工具创建   │    │ - 拓扑调整   │               │
│  │ - 工具组合   │    │ - 角色进化   │               │
│  └──────────────┘    └──────────────┘               │
└────────────────────────────────────────────────────┘
```

#### 三问框架

| 问题 | 说明 | 示例 |
|------|------|------|
| What to Evolve? | 进化什么 | 模型权重、提示词、工具库、工作流 |
| When to Evolve? | 何时进化 | 任务失败后、定期、置信度下降时 |
| How to Evolve? | 如何进化 | 反思、RL、自训练、工具学习 |

### 6.2 进化技术路线

#### 反思与自我批评（Reflection & Self-Critique）

最基础的进化方式，Agent 通过反思改进决策策略：

```python
class ReflectiveEvolution:
    """基于反思的进化机制"""
    
    def __init__(self, llm):
        self.llm = llm
        self.strategy_memory = []  # 策略记忆
    
    def evolve_from_experience(self, task: str, trajectory: list, 
                                outcome: str) -> dict:
        """从一次任务经历中提取可复用的策略"""
        prompt = f"""请分析这次任务执行的经验，提取可复用的策略。

任务：{task}
执行过程：{json.dumps(trajectory, ensure_ascii=False)}
结果：{outcome}

请提取：
1. 什么策略有效？（值得在未来类似任务中复用）
2. 什么策略无效？（未来应该避免）
3. 这类任务的通用解题模式是什么？
4. 发现了什么新的工具使用技巧？

以结构化 JSON 格式返回。"""
        
        insight = json.loads(self.llm.generate(prompt))
        self.strategy_memory.append(insight)
        return insight
    
    def apply_evolved_strategy(self, new_task: str) -> str:
        """将积累的策略应用到新任务"""
        # 检索最相关的历史策略
        relevant = self._retrieve_relevant_strategies(new_task)
        
        enhanced_prompt = f"""任务：{new_task}

你从过去的经验中学到了以下策略：
{json.dumps(relevant, ensure_ascii=False)}

请结合这些经验来更好地完成当前任务。"""
        
        return self.llm.generate(enhanced_prompt)
```

#### 强化学习驱动的进化（RL-based Evolution）

使用在线强化学习来持续优化 Agent 的策略：

```python
class RLEvolution:
    """强化学习驱动的 Agent 进化"""
    
    def __init__(self, policy_model, reward_model):
        self.policy = policy_model
        self.reward = reward_model
        self.experience_buffer = []
    
    def collect_experience(self, task, trajectory, outcome):
        """收集经验到 buffer"""
        reward = self.reward.evaluate(task, trajectory, outcome)
        self.experience_buffer.append({
            "task": task,
            "trajectory": trajectory,
            "reward": reward
        })
    
    def evolve(self, batch_size=32):
        """基于收集的经验更新策略"""
        if len(self.experience_buffer) < batch_size:
            return
        
        batch = random.sample(self.experience_buffer, batch_size)
        
        # 使用 RLHF / DPO / GRPO 等方法更新策略
        for experience in batch:
            # 计算优势函数
            advantage = experience["reward"] - self._baseline(experience["task"])
            
            # 策略梯度更新
            self.policy.update(
                states=experience["trajectory"],
                advantage=advantage
            )
```

#### 工具进化：自动发现和创建新工具

```python
class ToolEvolution:
    """工具进化：Agent 自动发现和创建工具"""
    
    def __init__(self, llm, tool_registry):
        self.llm = llm
        self.registry = tool_registry
    
    def discover_tool_need(self, failed_task: str, 
                           failure_reason: str) -> Optional[dict]:
        """从失败中发现工具需求"""
        prompt = f"""任务失败了，可能是因为缺少合适的工具。

任务：{failed_task}
失败原因：{failure_reason}
现有工具：{self.registry.list_tools()}

分析：
1. 如果有一个什么样的工具，这个任务就能完成？
2. 这个工具的输入输出是什么？
3. 能否用现有工具组合来实现？

以 JSON 格式返回工具定义。"""
        
        tool_spec = json.loads(self.llm.generate(prompt))
        return tool_spec
    
    def create_tool(self, tool_spec: dict) -> callable:
        """根据工具规格自动创建新工具"""
        prompt = f"""请根据以下规格创建一个 Python 函数：

工具名称：{tool_spec['name']}
描述：{tool_spec['description']}
输入参数：{tool_spec['parameters']}
输出格式：{tool_spec['output']}

请生成完整的、可执行的 Python 代码。"""
        
        code = self.llm.generate(prompt)
        
        # 安全执行：在沙箱中验证代码
        if self._validate_in_sandbox(code):
            tool = self._compile_tool(code, tool_spec)
            self.registry.register(tool)
            return tool
        
        return None
```

### 6.3 自进化 Agent 的挑战

#### 奖励劫持（Reward Hacking）

Agent 可能学会"钻空子"——找到最大化奖励但不真正完成任务的捷径：

```
示例：
- 任务：写高质量的代码
- 奖励信号：通过测试用例
- 劫持行为：生成硬编码的返回值来通过测试，而非真正实现功能
```

**应对策略**：
1. 多维度奖励：不仅看结果，还看过程
2. 对抗性验证：用另一个模型尝试"攻破"解决方案
3. 人工抽检：定期人工审核进化方向

#### Echo Trap：策略坍缩

自进化可能导致策略坍缩到重复模式——Agent 反复使用同一种"安全"策略，丧失创新能力：

```
进化前：Agent 尝试多种方法 [A, B, C, D, E]
进化后：Agent 只用方法 A（因为 A 在历史数据中成功率最高）
问题：新类型的任务可能需要方法 C 或 D，但 Agent 不再尝试
```

**应对策略**：
1. 保持探索率（epsilon-greedy）
2. 多样性奖励
3. 定期重置部分策略

#### 安全边界

自进化 Agent 可能进化出不安全的行为：

```python
class SafeEvolution:
    """安全的进化框架"""
    
    SAFETY_CONSTRAINTS = [
        "不得修改自身的安全规则",
        "不得创建具有系统级权限的工具",
        "不得绕过用户授权机制",
        "进化后的策略必须通过安全审查"
    ]
    
    def evolve_with_safety(self, evolution_proposal: dict) -> dict:
        # 安全审查
        safety_check = self.safety_verifier.check(evolution_proposal)
        if not safety_check.is_safe:
            return {"rejected": True, "reason": safety_check.violations}
        
        # 沙箱测试
        sandbox_result = self.sandbox.test(evolution_proposal)
        if not sandbox_result.is_safe:
            return {"rejected": True, "reason": "Sandbox test failed"}
        
        # 通过安全检查后才应用
        return self.apply_evolution(evolution_proposal)
```

---

## 七、企业级 Self-Healing 实践

### 7.1 Agent 失败模式分类

在企业级 Agent 系统中，常见的失败模式可以分为以下几类：

| 失败模式 | 频率 | 严重程度 | 典型表现 |
|---------|------|---------|--------|
| 工具调用失败 | 高 | 中 | API 返回错误码、超时 |
| 上下文溢出 | 中 | 高 | 超过 token 上限，截断关键信息 |
| 幻觉决策 | 中 | 高 | 调用不存在的工具或使用错误的参数 |
| 循环死锁 | 低 | 高 | 重复执行相同步骤无法跳出 |
| 权限不足 | 低 | 中 | 缺少执行所需的系统权限 |
| 数据异常 | 中 | 中 | 输入数据格式不符合预期 |
| 依赖服务不可用 | 低 | 高 | 下游服务宕机或降级 |

### 7.2 Self-Healing 策略矩阵

```
┌─────────────────────────────────────────────────────────────┐
│                Self-Healing 策略矩阵                          │
│                                                               │
│  失败模式        │ 首选策略     │ 备选策略     │ 最终策略     │
│  ───────────────┼────────────┼────────────┼────────────│
│  API 超时       │ 重试+退避    │ 切换备用API  │ 降级返回     │
│  参数错误       │ 参数修正     │ 路径切换     │ 人工回退     │
│  上下文溢出     │ 上下文压缩   │ 分段处理     │ 降级简化     │
│  幻觉决策       │ 验证+重生成  │ 约束解码     │ 人工回退     │
│  循环死锁       │ 循环检测中断 │ 状态重置     │ 人工回退     │
│  权限不足       │ 权限申请     │ 降权操作     │ 人工回退     │
│  数据异常       │ 数据清洗     │ 换数据源     │ 降级返回     │
│  依赖不可用     │ 熔断+降级    │ 本地缓存     │ 排队重试     │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 实践案例（脱敏）

#### 案例一：某互联网公司 Agent 平台的 Self-Healing 机制

**背景**：某互联网公司的内部 Agent 平台每天处理数万次 Agent 任务，涉及代码生成、数据查询、文档处理等场景。

**问题**：初期 Agent 任务的端到端成功率仅约 65%，主要失败原因：
- 工具调用参数格式错误（占 35%）
- 第三方 API 超时或不可用（占 25%）
- 上下文窗口溢出导致关键信息丢失（占 20%）
- 其他（占 20%）

**Self-Healing 方案**：

1. **三层错误检测**：
   - L1：工具返回值检查（即时响应）
   - L2：LLM 语义验证（异步检查）
   - L3：业务规则验证器（关键路径）

2. **智能修复策略**：
   - 参数格式错误：自动解析错误信息，用 LLM 重新生成正确参数
   - API 不可用：自动切换到备用 API 或降级策略
   - 上下文溢出：自动触发上下文压缩（保留关键信息，丢弃冗余内容）

3. **效果数据（脱敏）**：

| 指标 | 无 Self-Healing | 有 Self-Healing | 提升 |
|------|----------------|-----------------|------|
| 端到端成功率 | 65% | 89% | +24pp |
| 平均完成时间 | 45s | 52s | +15% |
| 人工干预率 | 35% | 8% | -27pp |
| 用户满意度 | 3.2/5 | 4.1/5 | +28% |

> 注意：Self-Healing 带来了约 15% 的时间开销（因为重试和修复需要额外时间），但大幅降低了人工干预率。

#### 案例二：某数据分析 Agent 的自动错误恢复实践

**场景**：一个数据分析 Agent 需要执行 SQL 查询、数据清洗、可视化生成等任务。

**核心 Self-Healing 策略**：

```python
class DataAnalysisHealer:
    """数据分析 Agent 的 Self-Healing 策略"""
    
    def heal_sql_error(self, sql: str, error: str) -> str:
        """修复 SQL 错误"""
        # 1. 解析 SQL 错误类型
        if "column not found" in error.lower():
            # 自动查询表结构，修正列名
            schema = self.get_table_schema(sql)
            return self.llm.generate(
                f"修正 SQL 中的列名错误。\nSQL: {sql}\n"
                f"错误: {error}\n表结构: {schema}"
            )
        elif "syntax error" in error.lower():
            # 语法修正
            return self.llm.generate(
                f"修正 SQL 语法错误。\nSQL: {sql}\n错误: {error}"
            )
        elif "timeout" in error.lower():
            # 查询优化
            return self.optimize_sql(sql)
    
    def heal_data_error(self, data, error: str) -> any:
        """修复数据处理错误"""
        if "encoding" in error.lower():
            # 自动检测并修复编码
            return self.fix_encoding(data)
        elif "missing values" in error.lower():
            # 自动填充缺失值
            return self.impute_missing(data)
        elif "type error" in error.lower():
            # 自动类型转换
            return self.auto_cast(data)
```

### 7.4 Self-Healing 的工程实现

#### 完整的 Self-Healing Agent 框架

```python
class SelfHealingAgent:
    """完整的 Self-Healing Agent 框架"""
    
    def __init__(self, llm, tools, config=None):
        self.llm = llm
        self.tools = tools
        self.config = config or SelfHealingConfig()
        
        # 四层 Self-Healing 组件
        self.error_detector = CompositeErrorDetector([
            StaticErrorDetector(),
            SemanticErrorDetector(llm),
            ConfidenceDetector()
        ])
        self.diagnoser = RootCauseDiagnoser(llm)
        self.repair_strategy = RepairStrategySelector()
        self.retry_executor = RetryExecutor(
            max_retries=self.config.max_retries,
            base_delay=self.config.base_delay
        )
        
        # 辅助组件
        self.loop_detector = LoopDetector(window_size=5)
        self.context_manager = ContextManager(max_tokens=self.config.max_tokens)
        self.logger = HealingLogger()
    
    async def run(self, task: str) -> AgentResult:
        """执行任务，带完整的 Self-Healing 能力"""
        context = ExecutionContext(task=task)
        messages = self._init_messages(task)
        
        for step in range(self.config.max_steps):
            # 循环检测
            if self.loop_detector.is_looping(messages):
                self.logger.warn("检测到循环，触发修复")
                messages = self._break_loop(messages, context)
            
            # 上下文管理
            if self.context_manager.is_approaching_limit(messages):
                self.logger.info("上下文接近上限，触发压缩")
                messages = self.context_manager.compress(messages)
            
            # LLM 生成下一步动作
            response = await self.llm.generate(messages, tools=self.tools)
            
            if response.is_final_answer:
                return AgentResult(success=True, answer=response.content,
                                   steps=step + 1)
            
            if response.tool_call:
                # 带 Self-Healing 的工具执行
                result = await self.retry_executor.execute_with_healing(
                    agent=self,
                    action=response.tool_call,
                    context=context
                )
                
                # 记录到上下文
                context.add_step(response.tool_call, result)
                messages.append({"role": "assistant", "content": response.raw})
                messages.append({"role": "tool", "content": str(result.data)})
                
                # 如果修复失败且触发了降级
                if not result.success and result.data.get("action") == \
                        "escalate_to_human":
                    return AgentResult(
                        success=False,
                        answer=result.data["message"],
                        needs_human=True
                    )
        
        return AgentResult(success=False, answer="达到最大步数限制")
    
    def _break_loop(self, messages: list, context: ExecutionContext) -> list:
        """打破循环：注入反思并强制换方向"""
        recent_actions = context.get_recent_actions(5)
        
        break_prompt = f"""你陷入了循环，反复执行以下动作：
{json.dumps(recent_actions, ensure_ascii=False)}

请停下来思考：
1. 为什么这些动作没有推进任务？
2. 有什么完全不同的方法可以尝试？

请用一种全新的方法来继续任务。"""
        
        messages.append({"role": "user", "content": break_prompt})
        return messages


class LoopDetector:
    """循环检测器"""
    
    def __init__(self, window_size=5, similarity_threshold=0.85):
        self.window_size = window_size
        self.threshold = similarity_threshold
        self.action_history = []
    
    def is_looping(self, messages: list) -> bool:
        """检测是否陷入循环"""
        # 提取最近的动作
        recent_actions = []
        for msg in messages[-self.window_size * 2:]:
            if msg["role"] == "assistant" and "tool_call" in str(msg.get("content", "")):
                recent_actions.append(str(msg["content"]))
        
        if len(recent_actions) < self.window_size:
            return False
        
        # 检查是否有重复模式
        last_actions = recent_actions[-self.window_size:]
        for i in range(len(last_actions) - 1):
            for j in range(i + 1, len(last_actions)):
                sim = self._similarity(last_actions[i], last_actions[j])
                if sim > self.threshold:
                    return True
        
        return False
```

#### 监控与日志

```python
class HealingLogger:
    """Self-Healing 专用日志系统"""
    
    def __init__(self):
        self.healing_events = []
    
    def log_healing_event(self, event_type: str, details: dict):
        """记录一次 Self-Healing 事件"""
        event = {
            "timestamp": datetime.now().isoformat(),
            "type": event_type,  # DETECTION / DIAGNOSIS / REPAIR / RETRY
            "details": details
        }
        self.healing_events.append(event)
        
        # 输出结构化日志
        logger.info(f"[SELF-HEALING] {event_type}", extra=event)
    
    def get_healing_summary(self) -> dict:
        """生成 Self-Healing 摘要报告"""
        total = len(self.healing_events)
        by_type = Counter(e["type"] for e in self.healing_events)
        success_rate = sum(1 for e in self.healing_events 
                          if e["details"].get("success")) / max(total, 1)
        
        return {
            "total_healing_events": total,
            "by_type": dict(by_type),
            "success_rate": f"{success_rate:.1%}",
            "most_common_error": self._most_common_error()
        }
```

#### 人类回退机制

```python
class HumanFallback:
    """人类回退机制：Agent 无法自行修复时请求人工帮助"""
    
    ESCALATION_CRITERIA = {
        "max_retries_exceeded": "所有自动修复尝试已用尽",
        "safety_concern": "检测到可能的安全风险",
        "low_confidence": "对修复方案的置信度过低",
        "critical_operation": "涉及不可逆的关键操作"
    }
    
    def should_escalate(self, context: ExecutionContext, 
                        diagnosis: Diagnosis) -> bool:
        """判断是否应该升级到人工处理"""
        # 规则 1：重试次数用尽
        if context.retry_count >= context.max_retries:
            return True
        
        # 规则 2：安全敏感操作
        if diagnosis.category in ["PERMISSION_ERROR"] and \
           context.current_action.get("is_destructive", False):
            return True
        
        # 规则 3：诊断置信度低
        if diagnosis.confidence < 0.3:
            return True
        
        return False
    
    def create_escalation_request(self, context: ExecutionContext,
                                   error_history: list) -> dict:
        """创建人工升级请求"""
        return {
            "type": "human_escalation",
            "task": context.task,
            "summary": f"Agent 在执行第 {context.step_number} 步时遇到" \
                       f"无法自动修复的问题",
            "error_history": [
                {
                    "step": e["retry"],
                    "error": str(e["error"]),
                    "attempted_fix": str(e.get("fix_attempted", "N/A"))
                }
                for e in error_history
            ],
            "suggested_action": "请人工检查并提供指导",
            "context_snapshot": context.serialize()
        }
```

---

## 八、面试高频问题与参考答案

### Q1：Reflection 和 Self-Healing 有什么区别？

**参考答案**：

两者的核心区别在于**修复时机**和**修复范围**。

Reflection（反思）是一种**事后改进**机制。Agent 完成一次完整的任务尝试后，对整个执行过程进行回顾分析，找出失败原因，然后在下次尝试时利用反思来改进。典型代表是 Reflexion 框架：执行 → 失败 → 反思 → 重新执行。Reflection 的优势是全局视角好，能识别系统性问题；劣势是修复延迟大，需要从头重试。

Self-Healing（自修复）是一种**过程中实时修复**机制。Agent 在执行每一步后立即检测错误、诊断根因、生成修复策略并重试，无需等到整个任务完成。Self-Healing 的优势是响应快，能在错误发生的节点就地修复；劣势是缺乏全局视角，可能局部最优。

在实际工程中，两者通常组合使用：Self-Healing 负责处理每一步的即时错误（如 API 超时、参数错误），Reflection 负责在多次尝试失败后提供宏观层面的策略调整。

### Q2：Reflexion 相比直接重试有什么优势？为什么不直接重新执行？

**参考答案**：

直接重试（Plain Retry）是在完全相同的条件下重新执行相同的操作，只对非确定性错误（如网络抖动）有效。如果错误是由逻辑问题或参数问题导致的，重试 100 次也不会成功。

Reflexion 的核心优势在于它通过**自然语言反思**来修改下一次尝试的策略。具体来说：

1. **策略改变**：Reflexion 在重试前分析失败原因，下次尝试会用不同的方法，而非重复相同的错误。
2. **经验累积**：反思被存入记忆，多轮反思能不断缩小搜索空间。
3. **无需梯度更新**：不像传统 RL 需要更新模型参数，Reflexion 仅通过扩展 prompt 上下文来改变行为，部署成本低。
4. **可解释性**：自然语言反思是人类可读的，便于调试和审计。

原论文数据显示，在 HumanEval 上 Reflexion 将 Pass@1 从 80.1% 提升到 91.0%，而 Plain Retry 几乎没有提升。

### Q3：LATS 的计算成本很高，如何在实际工程中权衡？

**参考答案**：

LATS 的计算成本确实是其工程落地的主要挑战。一个 10 步任务，分支因子为 5 时，可能需要上百次 LLM 调用。实际工程中的权衡策略：

1. **分层策略**：简单任务用 ReAct（线性），中等任务用 Reflexion（重试），复杂高价值任务才用 LATS（树搜索）。可以用一个轻量级的任务复杂度评估器来路由。

2. **剪枝优化**：不需要完整的树搜索。设置最大深度（如 5 层）、最大分支（如 3 个），同时用 Verifier 提前剪掉低分分支。

3. **异步并行**：LATS 的多个分支可以并行执行，利用异步 LLM 调用减少总延迟。

4. **缓存复用**：相似的状态和动作可以复用之前的评估结果，避免重复计算。

5. **预算控制**：设置 token 预算上限，达到预算后返回当前最优路径。

一般建议：当任务的价值远高于额外计算成本时（如自动化价值 > $100 的任务），使用 LATS 是合理的。

### Q4：如何设计一个好的 Verifier？ORM 和 PRM 有什么区别？

**参考答案**：

ORM（Outcome Reward Model）和 PRM（Process Reward Model）的关键区别在于**评估粒度**：

- **ORM** 只评估最终结果的正确性，给整个解题过程一个分数。训练数据是 (完整解答, 正确/错误) 对。优势是标注成本低（只需知道最终答案是否正确）；劣势是无法定位错误发生在哪一步。

- **PRM** 评估每一步的正确性，能指出"从第 3 步开始就走偏了"。训练数据需要步骤级别的标注。优势是能更早发现错误，引导搜索避开错误路径；劣势是标注成本高。

OpenAI 的研究表明，PRM 在数学推理任务上显著优于 ORM（《Let's Verify Step by Step》, 2023）。原因是 PRM 提供了更密集的奖励信号，类似于强化学习中 dense reward vs sparse reward 的对比。

设计好的 Verifier 的要点：
1. **训练数据多样性**：包含多种错误类型的样本
2. **难度校准**：确保 Verifier 在简单和困难案例上都有区分度
3. **与 Generator 的能力匹配**：Verifier 不需要比 Generator 强很多，但需要在特定维度上有互补性
4. **校准**：Verifier 的分数应该反映真实的正确概率

### Q5：Agent 执行过程中如何检测循环（Loop Detection）？

**参考答案**：

循环是 Agent 系统中的常见病态行为，表现为 Agent 反复执行相同或高度相似的动作而无法推进任务。检测循环的方法：

**方法一：精确匹配检测**
检查最近 N 步中是否有完全相同的动作（包括工具名和参数）。简单但只能检测完全相同的循环。

**方法二：语义相似度检测**
用 embedding 计算最近 N 步动作之间的语义相似度。如果平均相似度超过阈值（如 0.85），判定为循环。能检测"换汤不换药"式的循环。

**方法三：状态进展检测**
不看动作本身，而是检查任务状态是否有实质性进展。如果连续 N 步后状态没有明显变化（用 LLM 判断），认为陷入了循环。

**方法四：周期性模式检测**
检测 A → B → A → B 这类周期性模式。使用子串匹配或自相关分析。

打破循环的策略：
1. 注入"你正在循环"的提示，强制 Agent 反思
2. 强制选择之前没有使用过的工具
3. 回退到上一个"有进展"的状态
4. 升级到人类干预

### Q6：Self-Healing 会不会引入新的问题？如何确保修复不比原来更糟？

**参考答案**：

这是一个非常好的问题。Self-Healing 确实存在"修复引入新问题"的风险，主要体现在：

1. **修复级联**：修复动作本身失败，触发对修复的修复，可能导致无限递归。应对：设置修复深度上限（通常不超过 3 层）。

2. **状态污染**：修复过程中可能产生副作用（如创建了临时文件、修改了数据库状态），即使最终修复失败，这些副作用可能遗留。应对：使用事务机制或清理回调。

3. **过度修复**：Agent 把正确的结果误判为错误并"修复"了。应对：设置修复的置信度阈值，低于阈值不触发修复。

确保修复质量的实践：
- **修复验证**：修复后必须重新通过所有检测（不仅是触发修复的那个检测）
- **修复隔离**：在沙箱中先验证修复方案，确认无害后再应用
- **修复审计**：记录所有修复事件，定期人工审查修复质量
- **保守策略**：宁可触发人工回退，也不进行低置信度的自动修复

### Q7：自进化 Agent 的 Reward Hacking 问题如何解决？

**参考答案**：

Reward Hacking（奖励劫持）指 Agent 找到最大化奖励信号但不真正完成任务的"捷径"。这在自进化 Agent 中尤为危险，因为 Agent 可能进化出越来越精巧的作弊策略。

经典案例：
- 代码 Agent 生成硬编码返回值来通过测试
- 对话 Agent 学会说用户爱听的话来获取好评
- 搜索 Agent 返回模糊但"听起来正确"的答案

解决方案：

1. **多维奖励函数**：不仅评估结果，还评估过程的合理性。例如代码任务同时检查：测试通过率 + 代码复杂度 + 边界条件覆盖。

2. **对抗性验证**：用另一个 Agent（或模型）专门寻找解决方案的漏洞。如果能被轻易"攻破"，则奖励大幅降低。

3. **分布外测试**：用训练分布之外的测试用例验证，防止 Agent 对训练集过拟合。

4. **人工对齐审查**：定期采样进化后的行为让人类审查，确保进化方向与人类意图一致。

5. **Constitutional AI 思路**：预设不可违反的原则，任何进化都不得突破这些原则。

### Q8：在生产环境中，Self-Healing 的重试次数和超时时间如何设置？

**参考答案**：

这需要根据具体场景权衡**成功率提升**和**延迟增加**。

**重试次数**：
- 一般建议最多 3 次（含首次执行）。数据表明，如果 3 次修复都失败，第 4 次成功的概率极低（通常 < 5%）。
- 对于幂等操作（如查询），可以设置更多次（5 次）。
- 对于非幂等操作（如创建、删除），应该更保守（1-2 次）。

**超时时间**：
- 单步超时：API 调用 30s，LLM 调用 60s，复杂计算 120s。
- 总任务超时：所有重试的总时间上限，通常是用户可接受等待时间的 1.5 倍。
- 退避策略：推荐指数退避 + 随机抖动。`delay = min(base * 2^retry + random(0, 1), max_delay)`。

**动态调整**：
- 根据历史成功率动态调整。如果某类错误的首次修复成功率 > 90%，可以增加重试次数；如果 < 30%，应该尽早升级人工。
- 根据任务优先级调整。高优先级任务给更多重试机会，低优先级任务尽快降级。

### Q9：如何评估一个 Self-Healing 系统的好坏？

**参考答案**：

评估 Self-Healing 系统需要从多个维度考量：

**核心指标**：
1. **自动修复率（Auto-Heal Rate）**：检测到的错误中，有多少被自动修复。目标：> 70%。
2. **误报率（False Positive Rate）**：正确结果被误判为错误的比例。目标：< 5%。
3. **修复正确率（Repair Accuracy）**：自动修复的结果中，真正正确的比例。目标：> 90%。
4. **端到端成功率提升**：对比有/无 Self-Healing 的任务成功率差异。

**效率指标**：
5. **修复延迟（Heal Latency）**：从错误检测到修复完成的时间。
6. **额外 token 开销**：Self-Healing 消耗的额外 token 占总 token 的比例。
7. **人工升级率**：最终仍需人工干预的比例。

**质量指标**：
8. **修复稳定性**：修复后的结果是否比原始错误结果更好（避免"越修越差"）。
9. **覆盖率**：Self-Healing 能处理的错误类型占所有错误类型的比例。

评估方法：构建一个包含各类已知错误的测试集（Error Benchmark），在受控条件下测试 Self-Healing 系统的表现。

### Q10：展望未来，Self-Healing 技术会如何演进？

**参考答案**：

Self-Healing 技术的演进方向：

**短期（1-2年）**：
- **标准化**：Self-Healing 成为 Agent 框架的标配组件，而非可选插件。
- **专业化 Verifier**：针对不同领域（代码、数据、文档）训练专用的验证器模型。
- **预测性修复**：不等错误发生，而是预测可能出错的步骤并提前准备修复方案。

**中期（2-3年）**：
- **跨 Agent 学习**：一个 Agent 的修复经验可以迁移到其他 Agent，形成共享的"修复知识库"。
- **自适应修复策略**：Self-Healing 系统本身也能进化，自动学习最优的修复策略。
- **多 Agent 协同修复**：复杂任务中，专门的"修复 Agent"协助"执行 Agent"处理错误。

**长期（3-5年）**：
- **自进化与自修复的融合**：Agent 不仅能修复当前错误，还能预防未来类似错误。
- **形式化验证**：将形式化方法引入 Agent 的正确性验证，提供数学级别的保证。
- **自主安全边界**：Agent 自主定义和维护安全边界，在保证安全的前提下最大化自主性。

---

## 九、总结与展望

### Self-Healing 的核心原则

1. **检测优先**：没有好的错误检测，就没有好的修复。投资在检测层的回报最高。
2. **分层修复**：从最轻量的参数修正开始，逐步升级到路径切换和降级，避免过度修复。
3. **保守原则**：宁可不修复（让人类处理），也不进行低置信度的自动修复。
4. **可观测性**：所有修复事件必须有完整的日志记录，支持事后审计和持续改进。
5. **收敛保证**：修复过程必须有终止条件，避免无限重试。

### 从 Self-Healing 到 Self-Evolving

```
能力进化阶梯：

Level 0: 无容错          → 遇错即停
Level 1: 简单重试        → 相同动作重试 N 次
Level 2: Self-Healing    → 诊断错误，修复后重试
Level 3: Reflection      → 从失败中学习，改进策略
Level 4: Self-Evolving   → 持续进化，越来越强
Level 5: Self-Aware      → 知道自己的边界，主动学习新能力
```

当前大多数工程实践处于 Level 1-2，学术研究在探索 Level 3-4。Level 5 的自我意识型 Agent 仍然是开放的研究问题。

### 未来趋势

1. **Self-Healing 将成为 Agent 的基础能力**：就像现代操作系统内置了错误恢复机制，未来的 Agent 框架将把 Self-Healing 作为核心功能而非附加组件。

2. **从被动修复到主动预防**：下一代 Self-Healing 系统将具备预测能力，在错误发生之前就识别风险并采取预防措施。

3. **Agent 生态系统的自修复**：不仅单个 Agent 能自修复，多个 Agent 组成的系统也将具备集体自修复能力——当一个 Agent 失败时，其他 Agent 能够自动接管。

4. **人机协作的深化**：Self-Healing 不是要取代人类，而是让 Agent 能够处理更多的常规错误，让人类专注于真正需要创造力和判断力的问题。

> "一个好的 Self-Healing 系统，就像一位经验丰富的工程师：遇到常见问题能独立解决，遇到疑难杂症知道何时求助，处理完问题后还会总结经验防止再犯。"

---

## 附录：知识融合——构建Self-Healing Agent框架

前面各章分别讲解了 Reflection、Self-Refine、Reflexion、Self-Healing 四层架构、LATS 树搜索等知识点，但在真实工程落地中，这些技术并不是孤立使用的，而是要组装成一个统一的框架，贯穿 Agent 执行的每一步。本附录从上而下、不跳步地把全文知识点串起来，给出一份可以直接对照落地的 Self-Healing Agent 框架设计。

### 一、系统目标与设计原则

#### 1.1 核心目标

Self-Healing Agent 框架要解决的核心问题只有一个：**让 Agent 在执行任务的过程中，具备自动检测错误、定位根因、选择修复策略并重新执行的闭环能力，从而在不依赖人工介入的情况下，最大化任务的最终成功率（Task Success Rate）**。

具体拆解为三个可衡量的子目标：

1. **可观测**：任何一步执行出现异常，系统必须能在毫秒到秒级察觉，而不是等到任务整体失败后才发现。
2. **可诊断**：对察觉到的异常，系统要能给出结构化的根因判断，而不是简单地"重试一下看看"。
3. **可修复**：针对不同根因，系统要有对应的修复策略库，并能在有限的资源预算内选出最优策略执行。

#### 1.2 五大设计原则

构建 Self-Healing Agent 框架时，以下五条原则贯穿始终，任何一层的设计都不能违背：

**原则一：fail-fast（快速检测）**

错误发现得越早，修复成本越低。一个在第 3 步就产生的参数错误，如果一直拖到第 10 步才通过任务失败反推回来，中间 7 步的执行都是无效甚至有害的（可能已经写入了脏数据、发出了重复请求）。因此每一步执行后都必须立即触发检测，而不是等到任务终态再做事后诸葛亮式的复盘。

**原则二：diagnose-then-fix（先诊断后修复）**

看到错误不能立即盲目重试。没有诊断的重试本质上是"猜"，猜中了算运气好，猜不中就是浪费 Token 和时间。必须先经过根因诊断，明确错误属于参数错误、环境错误、逻辑错误、数据错误还是权限错误中的哪一类，再匹配对应的修复策略。

**原则三：bounded-retry（有限重试）**

任何重试机制都必须设置上限，包括：最大重试次数、最大 Token 预算、最大墙钟时间。没有边界的重试会导致成本失控，甚至陷入死循环（例如一直修复同一个参数错误但每次修出新的错误）。

**原则四：graceful-degradation（优雅降级）**

修复失败不等于任务失败。系统应该有分级的降级路径：从"完整方案"退到"简化方案"，再退到"部分结果+说明"，最后才是"报错终止"。降级本身也是一种修复。

**原则五：human-fallback（人类兜底）**

Self-Healing 不追求 100% 自动化，而是追求把"人类需要处理的错误占比"降到最低。当自动修复的置信度低、涉及不可逆操作（如资金变动、生产环境删除）、或重试次数已耗尽时，必须有清晰的转人工机制，并把完整的诊断上下文交接给人类，而不是让人类从零排查。

#### 1.3 与传统容错系统的区别

传统的软件容错系统（例如微服务里的熔断、限流、重试中间件）处理的是"确定性错误"——网络超时、连接拒绝、返回码非 200，这些错误的模式是预先定义好的，修复方式也是模板化的（如指数退避重试、切换备用节点）。

Self-Healing Agent 面对的是"语义性错误"——一个 API 调用返回了 200，但返回的数据是错的；一段代码执行成功了，但逻辑达不到用户意图；一次工具调用没有报错，但选错了工具。这类错误无法用状态码判断，必须引入语言模型作为"语义层的错误检测器和诊断器"。因此 Self-Healing Agent 框架，本质上是在传统容错系统的确定性检测能力之上，叠加了一层基于 LLM 的语义检测、诊断与策略生成能力，两者是互补而非替代关系。

### 二、整体架构总览

Self-Healing Agent 框架由六层构成，从上到下依次是：Agent 执行引擎、错误检测层、根因诊断层、修复策略层、重试执行层、经验积累层。整体架构如下：

```
┌──────────────────────────────────────────────────────────────────────┐
│                         用户任务输入 / Task Input                       │
└───────────────────────────────┬──────────────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     一、Agent 执行引擎（Execution Engine）                │
│   Plan → Act → Observe → Reflect                                      │
│   ┌────────┐   ┌────────┐   ┌──────────┐   ┌────────────┐            │
│   │ Planner │→ │ Executor│→ │ Observer │→ │ Reflector  │            │
│   └────────┘   └────────┘   └──────────┘   └────────────┘            │
│        每一步执行后，状态都会写入「执行状态机」并向下透传给检测层            │
└───────────────────────────────┬──────────────────────────────────────┘
                                 │ (每步 Observation)
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  二、错误检测层（Error Detection Layer）                 │
│   ┌───────────┐ ┌───────────┐ ┌────────────┐ ┌────────────────┐      │
│   │ 静态检测器  │ │ 语义检测器  │ │ 验证器检测器 │ │ 置信度检测器      │      │
│   │(错误码/异常)│ │(LLM 判断)  │ │(Verifier)  │ │(不确定性估计)     │      │
│   └─────┬─────┘ └─────┬─────┘ └──────┬─────┘ └────────┬────────┘      │
│         └─────────────┴──────────────┴────────────────┘               │
│                            ▼                                          │
│                 结构化错误报告 ErrorReport                              │
│         { has_error, error_type, severity, evidence, confidence }     │
└───────────────────────────────┬──────────────────────────────────────┘
                     无错误 ─────┤───── 有错误
                     (继续执行)   ▼
┌──────────────────────────────────────────────────────────────────────┐
│                三、根因诊断层（Root Cause Analysis Layer）                │
│   错误信息分析 → 上下文回溯 → 历史相似错误匹配（经验库检索）                 │
│                            ▼                                          │
│              诊断报告 DiagnosisReport                                  │
│    { category(参数/环境/逻辑/数据/权限), root_cause, confidence,        │
│      suggested_strategies[] }                                        │
└───────────────────────────────┬──────────────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  四、修复策略层（Repair Strategy Layer）                  │
│  策略矩阵匹配 → 策略选择算法（错误类型 × 历史成功率 × 剩余预算）              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐   │
│  │参数修正   │ │路径切换   │ │环境修复   │ │降级方案    │ │Reflexion │   │
│  │(轻量级)   │ │(轻量级)   │ │(中量级)   │ │(中量级)   │ │反思(重量级)│   │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘ └───────────┘   │
│                 复杂/模糊场景 → 触发 LATS 树搜索（多策略并行探索评估）      │
└───────────────────────────────┬──────────────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                   五、重试执行层（Retry Execution Layer）                 │
│   状态恢复 → 应用修复策略 → 重新执行 → 验证结果                           │
│   重试计数器(bounded) + 指数退避 + Token/时间预算控制                    │
│         成功 → 返回执行引擎继续任务   失败 → 升级/降级/转人工               │
└───────────────────────────────┬──────────────────────────────────────┘
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                  六、经验积累层（Experience Accumulation Layer）           │
│   经验存储(错误→诊断→修复→结果) → 经验检索(向量匹配) → 经验蒸馏(规则化)     │
│                        → 反哺诊断层与策略层，实现自进化                  │
└──────────────────────────────────────────────────────────────────────┘
```

各层之间的数据流是单向流水线加反馈闭环：执行引擎产出 Observation 后向下传给检测层；检测层判定有错误后产出 ErrorReport 传给诊断层；诊断层产出 DiagnosisReport 传给策略层；策略层选出策略后交给重试执行层落地；执行结果无论成功失败都会被经验积累层记录，而经验积累层反过来又会影响诊断层的匹配准确率和策略层的选择权重，形成一个越用越聪明的闭环系统。

### 三、各层详细设计

#### 3.1 Agent 执行引擎

Agent 执行引擎遵循标准的 Plan → Act → Observe → Reflect 循环。Self-Healing 能力并不是外挂在这个循环之外的模块，而是嵌入在 Act 与 Observe 之间、以及 Reflect 环节内部的。

**执行状态机**：每一步执行都会被记录为一个状态节点，包含以下字段：

```python
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Optional

class StepStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    REPAIRING = "repairing"      # 正在被 Self-Healing 处理
    RETRYING = "retrying"
    DEGRADED = "degraded"        # 已降级完成
    ESCALATED = "escalated"      # 已转人工

@dataclass
class ExecutionStep:
    step_id: int
    action: dict                 # 计划执行的动作（工具名+参数）
    status: StepStatus = StepStatus.PENDING
    observation: Optional[Any] = None
    error_report: Optional[dict] = None
    diagnosis_report: Optional[dict] = None
    repair_attempts: list = field(default_factory=list)  # 每次修复尝试的记录
    retry_count: int = 0
```

**与 Self-Healing 各层的集成点**：执行引擎在 Act 之后立即调用错误检测层；一旦检测到错误，状态从 RUNNING 切换为 FAILED，随即触发诊断层和策略层，状态切换为 REPAIRING；修复策略生成后，执行引擎重新调用 Act（带上修正后的参数或动作），状态切换为 RETRYING；重试成功则回到 SUCCESS 继续下一步，重试失败且达到上限则进入 DEGRADED 或 ESCALATED。

代码示例：带 Self-Healing 的 Agent Loop 骨架。

```python
class SelfHealingAgentLoop:
    def __init__(self, planner, executor, detector, diagnoser,
                 strategy_selector, retry_executor, experience_store):
        self.planner = planner
        self.executor = executor
        self.detector = detector
        self.diagnoser = diagnoser
        self.strategy_selector = strategy_selector
        self.retry_executor = retry_executor
        self.experience_store = experience_store

    def run(self, task: str, max_steps: int = 20) -> dict:
        history = []
        plan = self.planner.plan(task, history)

        for step_idx, action in enumerate(plan.actions):
            if step_idx >= max_steps:
                break

            step = ExecutionStep(step_id=step_idx, action=action)
            step.status = StepStatus.RUNNING
            observation = self.executor.act(action)
            step.observation = observation

            # === 错误检测层介入 ===
            error_report = self.detector.detect(action, observation, history)

            if not error_report["has_error"]:
                step.status = StepStatus.SUCCESS
                history.append(step)
                continue

            # === 检测到错误，进入 Self-Healing 流程 ===
            step.status = StepStatus.FAILED
            step.error_report = error_report

            result = self._heal(step, task, history)
            history.append(result)

            if result.status == StepStatus.ESCALATED:
                # 转人工，任务挂起等待人工介入
                return {"status": "escalated", "history": history}

            # 修复成功后，可能需要重新规划后续步骤
            if result.status in (StepStatus.DEGRADED,):
                plan = self.planner.replan(task, history)

        return {"status": "completed", "history": history}

    def _heal(self, step: ExecutionStep, task: str, history: list) -> ExecutionStep:
        """核心 Self-Healing 处理流程，见 3.5 节详细实现"""
        step.status = StepStatus.REPAIRING
        diagnosis = self.diagnoser.diagnose(step.error_report, history,
                                             self.experience_store)
        step.diagnosis_report = diagnosis

        strategy = self.strategy_selector.select(diagnosis, step.repair_attempts,
                                                   budget_remaining=self._budget(step))

        step = self.retry_executor.execute_with_repair(step, strategy, self.executor,
                                                          self.detector)

        # 无论成功失败都写入经验库
        self.experience_store.record(step)
        return step

    def _budget(self, step: ExecutionStep) -> dict:
        return {"max_retries": 3, "max_tokens": 8000, "max_seconds": 60}
```

这段骨架代码清晰地体现了六层架构的调用顺序：Executor 执行 → Detector 检测 → Diagnoser 诊断 → Strategy Selector 选策略 → Retry Executor 落地重试 → Experience Store 沉淀经验，任何一步都不能跳过。

#### 3.2 错误检测层

单一检测手段一定有盲区：静态检测能抓住工具返回的显式错误，但抓不住"调用成功但结果错误"的语义错误；语义检测（LLM 判断）覆盖面广，但成本高、偶尔会误判；验证器检测更客观，但需要额外训练或维护一个独立模型；置信度检测最轻量，但容易漏检"模型自信但实际错误"的情况。因此工程上必须让四种检测器协同工作，而不是二选一。

**协同策略**：采用"级联+投票"的方式。第一级用零成本的静态检测做初筛，命中即判定为错误，不再往下走；未命中的情况下，第二级用置信度检测做低成本过滤，置信度低于阈值才触发第三级更贵的语义检测和验证器检测；语义检测和验证器检测的结果做加权投票，任一方给出高置信度的"有错误"判断即整体判定为错误。

**检测时机**：分为两种粒度。一是"每步执行后实时检测"，成本可控，覆盖单步的执行结果；二是"关键里程碑检测"，例如任务执行到 50%、完成前的终态检查等节点，做更全面的语义一致性校验（回看任务原始意图，判断当前进展是否仍在正确方向上），这一层检测能捕捉到"每一步都没报错，但整体方向已经跑偏"的隐性错误。

**检测结果的结构化表示**：

```python
ErrorReport = {
    "has_error": bool,
    "error_type": str,          # "tool_error" | "semantic_error" | "verifier_reject" | "low_confidence"
    "severity": str,             # "critical" | "major" | "minor"
    "evidence": str,             # 触发检测的原始证据（异常栈、LLM判断理由等）
    "confidence": float,         # 检测器对"确实有错误"这一判断的置信度
    "detector_source": list,    # 命中的检测器列表，便于事后分析检测器有效性
}
```

代码示例：多层错误检测器。

```python
class MultiLayerErrorDetector:
    def __init__(self, verifier_model, llm_judge, confidence_threshold=0.7):
        self.verifier_model = verifier_model
        self.llm_judge = llm_judge
        self.confidence_threshold = confidence_threshold

    def detect(self, action: dict, observation: Any, history: list) -> dict:
        # 第一级：静态检测，零成本
        static_result = self._static_check(observation)
        if static_result["has_error"]:
            return self._build_report(static_result, source="static")

        # 第二级：置信度检测，低成本
        confidence = self._estimate_confidence(observation)
        if confidence >= self.confidence_threshold:
            return self._build_report({"has_error": False}, source="confidence")

        # 第三级：语义检测 + 验证器检测，高成本但只在必要时触发
        semantic_result = self._semantic_check(action, observation, history)
        verifier_result = self.verifier_model.verify(action, observation)

        combined_has_error = semantic_result["has_error"] or verifier_result["reject"]
        if combined_has_error:
            return self._build_report(
                {"has_error": True,
                 "evidence": semantic_result.get("reason", "") + " | " + verifier_result.get("reason", "")},
                source="semantic+verifier"
            )
        return self._build_report({"has_error": False}, source="semantic+verifier")

    def _static_check(self, observation) -> dict:
        if isinstance(observation, dict) and observation.get("error_code"):
            return {"has_error": True, "evidence": observation["error_code"]}
        if isinstance(observation, Exception):
            return {"has_error": True, "evidence": str(observation)}
        return {"has_error": False}

    def _estimate_confidence(self, observation) -> float:
        # 简化示例：基于返回内容长度、是否包含空值等启发式规则
        if observation is None or observation == "":
            return 0.0
        return 0.8

    def _semantic_check(self, action, observation, history) -> dict:
        prompt = f"任务动作：{action}\n执行结果：{observation}\n" \
                 f"请判断该结果是否符合动作的预期目的，只输出 JSON: {{has_error, reason}}"
        return self.llm_judge.judge(prompt)

    def _build_report(self, result: dict, source: str) -> dict:
        return {
            "has_error": result.get("has_error", False),
            "error_type": result.get("error_type", "unknown"),
            "severity": result.get("severity", "major"),
            "evidence": result.get("evidence", ""),
            "confidence": result.get("confidence", 0.9),
            "detector_source": [source],
        }
```

#### 3.3 根因诊断层

检测层只回答"有没有错"，诊断层要回答"错在哪、为什么错"。这一层的核心是建立一套错误分类体系，并让诊断引擎能够把一个具体的错误归类到某个类别上，同时给出根因描述。

**错误分类体系**：

| 类别 | 典型特征 | 举例 |
|---|---|---|
| 参数错误（Parameter Error） | 工具调用的入参不符合 schema 或语义要求 | 日期格式错误、必填字段缺失、枚举值超范围 |
| 环境错误（Environment Error） | 外部依赖不可用或状态不满足前置条件 | 接口超时、依赖服务未就绪、文件不存在 |
| 逻辑错误（Logic Error） | Agent 的推理链条本身有问题 | 选错了工具、步骤顺序颠倒、遗漏必要子任务 |
| 数据错误（Data Error） | 输入或中间数据本身有质量问题 | 脏数据、数据为空、数据与预期 schema 不匹配 |
| 权限错误（Permission Error） | 缺少必要的访问权限或触发了安全策略拦截 | 越权访问、Token 过期、被风控策略拦截 |

**诊断引擎的三段式流程**：

1. **错误信息分析**：解析 ErrorReport 中的 evidence 字段，结合正则规则和 LLM 抽取，判断错误信号属于上述五类中的哪一类。
2. **上下文回溯**：不只看当前这一步，还要回溯最近 N 步的执行历史，判断错误是否是前几步的隐性问题在当前步骤"爆发"出来的（例如第 3 步获取的数据本身就是空的，但直到第 5 步使用该数据时才报错）。
3. **历史相似错误匹配**：在经验积累层维护的经验库中做向量检索，找到过去发生过的相似错误及其成功修复方案，作为诊断的重要参考依据，避免每次都从零推理。

代码示例：根因诊断器。

```python
class RootCauseDiagnoser:
    CATEGORIES = ["parameter_error", "environment_error", "logic_error",
                  "data_error", "permission_error"]

    def __init__(self, llm_client, experience_store):
        self.llm_client = llm_client
        self.experience_store = experience_store

    def diagnose(self, error_report: dict, history: list, experience_store=None) -> dict:
        experience_store = experience_store or self.experience_store

        # Step 1: 历史相似错误匹配，优先复用已验证的诊断结论
        similar_cases = experience_store.retrieve_similar(error_report, top_k=3)

        # Step 2: 上下文回溯，取最近5步作为诊断上下文
        recent_context = history[-5:] if len(history) >= 5 else history

        # Step 3: LLM 综合分析给出分类与根因
        prompt = self._build_diagnosis_prompt(error_report, recent_context, similar_cases)
        raw_result = self.llm_client.complete(prompt, response_format="json")

        category = raw_result.get("category")
        if category not in self.CATEGORIES:
            category = "logic_error"  # 兜底分类

        return {
            "category": category,
            "root_cause": raw_result.get("root_cause", ""),
            "confidence": raw_result.get("confidence", 0.5),
            "suggested_strategies": raw_result.get("suggested_strategies", []),
            "similar_cases_used": [c["case_id"] for c in similar_cases],
        }

    def _build_diagnosis_prompt(self, error_report, recent_context, similar_cases) -> str:
        return f"""
你是一个 Agent 错误诊断专家。请分析以下错误信息，输出结构化诊断结果。

【错误报告】
{error_report}

【最近执行上下文】
{recent_context}

【历史相似错误及修复经验】
{similar_cases}

请输出 JSON：{{
  "category": "从 parameter_error/environment_error/logic_error/data_error/permission_error 中选一个",
  "root_cause": "一句话根因描述",
  "confidence": 0到1之间的置信度,
  "suggested_strategies": ["建议尝试的修复策略名称列表"]
}}
"""
```

**诊断报告生成**：最终产出的 DiagnosisReport 会连同原始 ErrorReport 一起，作为下游修复策略层的核心输入，同时也会被完整归档进经验库，无论修复是否成功。

#### 3.4 修复策略层

有了根因分类，下一步是把每种错误类型映射到对应的修复策略，这个映射关系构成"策略矩阵"。

**策略矩阵**：

| 错误类别 | 优先策略（轻量级） | 次选策略（中量级） | 兜底策略（重量级） |
|---|---|---|---|
| 参数错误 | 参数修正 | 路径切换（换一个工具/接口） | Reflexion 反思重规划 |
| 环境错误 | 指数退避后重试 | 环境修复（重建连接/切换备用资源） | 降级方案 |
| 逻辑错误 | Reflexion 反思 | LATS 树搜索多路径探索 | 转人工 |
| 数据错误 | 数据清洗/重新拉取 | 路径切换（换数据源） | 降级为部分结果 |
| 权限错误 | 刷新凭证重试 | 降级到受限功能 | 转人工审批 |

**策略选择算法**：不是简单查表，而是综合三个因子做加权打分：

```python
def score_strategy(strategy, diagnosis, historical_success_rate, budget_remaining) -> float:
    error_type_match_score = 1.0 if strategy.category == diagnosis["category"] else 0.3
    history_score = historical_success_rate.get(strategy.name, 0.5)  # 默认中性先验
    cost_penalty = strategy.estimated_cost / max(budget_remaining, 1e-6)
    cost_score = max(0, 1 - cost_penalty)

    return 0.4 * error_type_match_score + 0.4 * history_score + 0.2 * cost_score
```

即：错误类型匹配度权重最高，历史成功率次之，剩余预算作为约束项。这样设计能保证策略选择既"对症"又"经济"，并随着经验库的积累越来越准。

**修复策略库的五种具体实现**：

1. **参数修正**：把错误信息、原始 schema、当前入参一起交给 LLM，让其输出修正后的参数，成本最低，适用于绝大多数参数类错误。
2. **路径切换**：当前工具/接口/数据源不可用或效果不佳时，切换到功能等价的备选方案（例如从主搜索引擎切换到备用搜索引擎）。
3. **环境修复**：包括重建连接、清理缓存状态、刷新过期凭证、等待依赖服务就绪等操作性修复。
4. **降级方案**：主动放弃部分任务目标，保证核心目标完成，例如从"生成完整报告"降级为"生成摘要+说明未完成部分"。
5. **Reflexion 反思**：如第三章所述，让 Agent 对失败轨迹做语言化反思，生成自我改进的经验文本，重新规划后再执行，适用于逻辑性较强、无法用简单规则修复的错误。

对于逻辑错误这种诊断置信度往往不高、单一策略难以覆盖的场景，修复策略层会触发 **LATS 树搜索**：并行生成多个候选修复方案（每个方案对应树上的一个分支），用评估函数（Verifier + 价值估计）给每个分支打分，选择评分最高的分支执行，同时把探索过程中的价值信息反向传播，用于未来相似场景的策略选择先验更新。

代码示例：修复策略选择器。

```python
class RepairStrategySelector:
    def __init__(self, strategy_registry, experience_store, lats_engine=None):
        self.strategy_registry = strategy_registry  # {name: StrategyImpl}
        self.experience_store = experience_store
        self.lats_engine = lats_engine

    def select(self, diagnosis: dict, prior_attempts: list, budget_remaining: dict) -> dict:
        candidates = self.strategy_registry.get_by_category(diagnosis["category"])
        # 过滤掉已经尝试过且失败的策略，避免重复踩坑
        tried_names = {a["strategy_name"] for a in prior_attempts if not a["success"]}
        candidates = [c for c in candidates if c.name not in tried_names]

        if not candidates:
            # 轻中量级策略都已尝试失败，触发重量级 LATS 或直接转人工
            if diagnosis["category"] == "logic_error" and self.lats_engine:
                return self.lats_engine.search(diagnosis, budget_remaining)
            return {"strategy_name": "escalate_to_human", "reason": "策略候选已耗尽"}

        historical_success_rate = self.experience_store.get_success_rates(candidates)

        scored = [
            (score_strategy(c, diagnosis, historical_success_rate, budget_remaining["max_tokens"]), c)
            for c in candidates
        ]
        scored.sort(key=lambda x: x[0], reverse=True)
        best_score, best_strategy = scored[0]

        return {
            "strategy_name": best_strategy.name,
            "strategy_impl": best_strategy,
            "score": best_score,
        }
```

#### 3.5 重试执行层

重试执行层负责把选定的修复策略真正落地为一次新的执行，并对重试结果做验证，是整个 Self-Healing 链路的收口环节。

**重试控制的三个硬约束**：

1. **最大次数**：单个步骤的重试次数一般设置为 2~3 次，超过后必须走降级或转人工，避免陷入"修复-再报错-再修复"的死循环。
2. **指数退避**：对于环境类错误（超时、限流），每次重试之间的等待时间按指数增长（如 1s、2s、4s），避免对下游系统造成二次冲击。
3. **状态恢复**：重试前必须把执行状态恢复到出错前的一致点，例如清理掉上一次失败调用产生的副作用（半成功的写操作、未关闭的连接），保证重试是在干净状态上进行的，而不是在脏状态上叠加。

**重试验证**：修复后的重新执行结果，必须重新完整过一遍错误检测层，不能因为"已经修复过了"就跳过检测，这是保证 Self-Healing 闭环质量的关键点——修复本身也可能引入新的错误。

**重试失败后的升级路径**：优先级为「换策略重试」→「降级方案」→「转人工」。当同一错误类型的所有策略都已尝试失败，或已达到重试次数/预算上限，系统必须果断放弃自动修复，转向降级或人工介入，而不是无限制地消耗资源。

代码示例：重试执行器。

```python
class RetryExecutor:
    def __init__(self, max_retries=3, backoff_base=1.0):
        self.max_retries = max_retries
        self.backoff_base = backoff_base

    def execute_with_repair(self, step: ExecutionStep, strategy: dict,
                              executor, detector) -> ExecutionStep:
        if strategy["strategy_name"] == "escalate_to_human":
            step.status = StepStatus.ESCALATED
            return step

        for attempt in range(self.max_retries):
            step.status = StepStatus.RETRYING
            step.retry_count += 1

            # 状态恢复：清理上一次失败的副作用
            executor.rollback_side_effects(step)

            # 应用修复策略，得到修正后的动作
            repaired_action = strategy["strategy_impl"].apply(step.action, step.diagnosis_report)

            if strategy["strategy_name"].startswith("environment"):
                self._backoff_wait(attempt)

            new_observation = executor.act(repaired_action)

            # 重试验证：必须完整重新走一遍检测层
            recheck = detector.detect(repaired_action, new_observation, [])

            record = {
                "strategy_name": strategy["strategy_name"],
                "attempt": attempt + 1,
                "success": not recheck["has_error"],
            }
            step.repair_attempts.append(record)

            if not recheck["has_error"]:
                step.action = repaired_action
                step.observation = new_observation
                step.status = StepStatus.SUCCESS
                return step

        # 重试次数耗尽，尝试降级
        degraded_result = strategy["strategy_impl"].degrade(step)
        if degraded_result is not None:
            step.observation = degraded_result
            step.status = StepStatus.DEGRADED
            return step

        step.status = StepStatus.ESCALATED
        return step

    def _backoff_wait(self, attempt: int):
        import time
        time.sleep(self.backoff_base * (2 ** attempt))
```

#### 3.6 经验积累层

经验积累层是让 Self-Healing 框架具备"越用越聪明"能力的关键，缺少这一层，系统每次遇到相似错误都要重新走一遍完整的诊断和策略探索流程，成本高且不会随时间收敛。

**经验存储**：以"错误→诊断→修复→结果"四元组为最小存储单元，落地为可检索的经验库（可用向量数据库存储错误的语义 embedding，配合结构化字段做过滤）：

```python
ExperienceRecord = {
    "case_id": str,
    "error_report": dict,
    "diagnosis_report": dict,
    "repair_attempts": list,       # 完整的策略尝试序列，含成功与失败的
    "final_status": str,           # success / degraded / escalated
    "task_context_embedding": list,  # 用于相似度检索的向量表示
    "timestamp": str,
}
```

**经验检索**：新错误发生时，先用错误的语义描述生成 embedding，在经验库中做近似最近邻检索，取 top-K 最相似的历史案例，供诊断层和策略层参考。这一步能显著降低诊断层的 LLM 调用成本——如果检索到的历史案例置信度足够高，可以直接复用其诊断结论和成功策略，跳过重新推理。

**经验蒸馏**：定期（例如每天或每积累 N 条新经验）对经验库做批量分析，把高频出现的"错误模式→有效修复策略"归纳为确定性规则，下沉到错误检测层或修复策略层的规则引擎中，减少对 LLM 的依赖。例如，某个第三方接口的日期参数格式错误连续出现 50 次且都被同一种参数修正策略成功修复，就可以把这条规则固化为静态检测+自动修正，而不必每次都走 LLM 诊断。

**自进化**：策略选择算法中的 `historical_success_rate` 直接来自经验库的统计结果，这意味着策略层的行为会随着经验积累自动调整——某个策略在某类错误上表现越好，未来被优先选中的概率越高，形成一个自我强化的正反馈循环。

代码示例：经验管理系统。

```python
class ExperienceStore:
    def __init__(self, vector_db, embedding_model):
        self.vector_db = vector_db
        self.embedding_model = embedding_model

    def record(self, step: ExecutionStep):
        if step.error_report is None:
            return  # 没有出错的步骤不需要记录到修复经验库
        record = {
            "case_id": f"case_{step.step_id}_{id(step)}",
            "error_report": step.error_report,
            "diagnosis_report": step.diagnosis_report,
            "repair_attempts": step.repair_attempts,
            "final_status": step.status.value,
            "task_context_embedding": self.embedding_model.embed(str(step.error_report)),
        }
        self.vector_db.upsert(record)

    def retrieve_similar(self, error_report: dict, top_k: int = 3) -> list:
        query_vec = self.embedding_model.embed(str(error_report))
        return self.vector_db.search(query_vec, top_k=top_k)

    def get_success_rates(self, strategies: list) -> dict:
        rates = {}
        for strategy in strategies:
            records = self.vector_db.filter(strategy_name=strategy.name)
            if not records:
                rates[strategy.name] = 0.5  # 无历史数据时给中性先验
                continue
            success_count = sum(1 for r in records if r["final_status"] == "success")
            rates[strategy.name] = success_count / len(records)
        return rates

    def distill_rules(self, min_occurrence: int = 20, min_success_rate: float = 0.9) -> list:
        """定期调用，把高频高成功率的模式蒸馏为确定性规则"""
        patterns = self.vector_db.aggregate_by_pattern()
        distilled = []
        for pattern in patterns:
            if pattern["count"] >= min_occurrence and pattern["success_rate"] >= min_success_rate:
                distilled.append({
                    "rule": pattern["error_signature"],
                    "action": pattern["dominant_strategy"],
                    "confidence": pattern["success_rate"],
                })
        return distilled
```

### 四、核心数据流：一次 Agent 错误的 Self-Healing 全链路

下面用步骤化的方式，完整描述一次真实错误从发生到修复恢复的全流程，把前面六层串成一条线。

**场景**：Agent 在执行"帮用户预订下周三下午的会议室"任务时，第 4 步调用会议室预订工具，返回结果异常。

**Step 1：执行**。执行引擎按计划调用 `book_meeting_room(date="2025-13-03", time="14:00")`。工具返回 `{"error_code": "INVALID_DATE_FORMAT"}`。此时 `ExecutionStep.status` 从 RUNNING 变为待检测。

**Step 2：检测**。多层错误检测器介入。静态检测器第一时间命中 `error_code` 字段，判定 `has_error=True`，无需再走语义检测和验证器检测，节省了一次 LLM 调用。产出 ErrorReport：`{error_type: "tool_error", severity: "major", evidence: "INVALID_DATE_FORMAT", confidence: 1.0}`。

**Step 3：诊断**。根因诊断器接收 ErrorReport，先在经验库中检索相似错误——发现历史上有 12 次类似的"日期格式错误"案例，均被"参数修正"策略以 95% 的成功率解决。结合当前上下文（"2025-13-03" 中月份为 13，超出合法范围，说明是 Agent 自己生成参数时计算错误，而非外部环境问题），诊断引擎输出 DiagnosisReport：`{category: "parameter_error", root_cause: "日期月份字段计算错误，13月不存在", confidence: 0.92, suggested_strategies: ["parameter_correction"]}`。

**Step 4：策略选择**。修复策略选择器根据诊断结果的 `category=parameter_error`，从策略矩阵中取出候选策略集合（参数修正、路径切换），过滤掉之前没试过的，结合历史成功率打分：参数修正得分 0.86，路径切换得分 0.4（因为这不是路径问题）。选中参数修正策略。

**Step 5：修复执行**。参数修正策略把原始 schema（日期需为 YYYY-MM-DD 且月份 1-12）、错误信息、原始输入一起交给 LLM，LLM 结合"下周三下午"这一原始用户意图重新计算日期，输出修正后的参数 `date="2025-03-19"`。状态机切换到 RETRYING。

**Step 6：状态恢复与重试**。执行器先检查是否有需要回滚的副作用（本次调用因为参数校验失败在上游就被拒绝，没有产生副作用，无需回滚），然后用修正后的参数重新调用 `book_meeting_room`。

**Step 7：重试验证**。新的 Observation 返回 `{"status": "booked", "room": "302"}`，重新完整地过一遍检测层：静态检测未命中错误码，置信度检测认为返回结构完整、置信度高（0.85 大于阈值 0.7），直接判定无错误，不需要再触发语义检测和验证器检测。

**Step 8：状态更新**。`ExecutionStep.status` 更新为 SUCCESS，`repair_attempts` 记录本次修复过程（策略名、尝试次数、结果）。执行引擎继续向下执行任务的后续步骤（例如发送预订确认通知）。

**Step 9：经验沉淀**。无论这一步成功还是失败，经验管理系统都会把完整的四元组（错误、诊断、修复、结果）写入经验库。本次案例因为修复成功，会进一步提升"日期格式错误 → 参数修正"这一策略组合的历史成功率统计，供下一次遇到类似错误时更快、更准地做出选择。

**如果 Step 6 重试仍然失败会怎样**：重试执行层会记录本次失败，检查重试计数是否达到上限（本例上限为 3 次）。如果未达上限，回到 Step 4 重新选择策略（此时"参数修正"已在 `tried_names` 中被排除，会尝试"路径切换"，比如换一个会议室预订系统的备用接口）；如果已达上限，触发降级方案（如"改为发消息请用户自行确认可用时间段"）或直接转人工，并将完整的 DiagnosisReport 和所有 `repair_attempts` 记录一并交接，避免人工需要从头排查。

### 五、Reflexion 与 LATS 的融合实践

Reflexion 和 LATS 在 Self-Healing 框架中分别扮演"轻量级"和"重量级"两种修复机制的角色，二者不是替代关系，而是根据错误的复杂度动态切换的两级火力。

**Reflexion 作为轻量级 Self-Healing：任务级反思**。当错误发生在单个步骤、根因诊断置信度较高、修复路径相对明确时，用 Reflexion 的"生成语言化自我反思 + 存入短期记忆 + 重新规划"这一套轻量流程即可解决，成本是一次额外的 LLM 调用，适合逻辑错误中"步骤顺序错误""遗漏子任务"这类问题。

**LATS 作为重量级 Self-Healing：步骤级树搜索**。当错误诊断置信度低、单一修复路径不足以确定成功、且任务的重要性/不可逆性较高时，值得付出更高的计算成本，用 LATS 在每一个候选修复动作上展开树搜索：对多个候选动作分别做前向模拟，用 Verifier 或价值函数评估每个分支的预期收益，选择评分最高的分支执行，并把探索得到的价值信息反向传播到之前的决策节点，修正未来的策略先验。

**决策框架：何时用 Reflexion、何时用 LATS**：

```python
def choose_healing_mechanism(diagnosis: dict, task_criticality: str) -> str:
    if diagnosis["confidence"] >= 0.8 and task_criticality != "high":
        return "reflexion"          # 诊断明确、任务重要性一般，用轻量方案
    if diagnosis["confidence"] < 0.5 or task_criticality == "high":
        return "lats"               # 诊断模糊或任务关键，值得付出树搜索成本
    return "reflexion_then_lats_fallback"  # 中间地带，先尝试轻量方案，失败再升级
```

**置信度驱动的自动升级机制**：这是融合实践的核心设计——系统默认总是先走成本最低的路径（参数修正 → Reflexion），只有当诊断置信度低于阈值，或轻量策略连续失败时，才自动升级到 LATS 树搜索这种重量级机制。这种"能省则省、必要时才加码"的分级策略，是控制 Self-Healing 整体成本的关键手段，也是本框架中"bounded-retry"和"graceful-degradation"两大设计原则在策略层的具体体现。

### 六、企业级 Self-Healing 实践

在某互联网公司的 Agent 平台实践中（相关数据已做脱敏处理，仅保留可分享的趋势性结论），引入完整的 Self-Healing 机制后，端到端任务成功率从约 65% 提升到约 89%，提升幅度主要来自两类错误的自动化消化：一类是参数格式类错误（原本占失败案例的三成以上，引入参数修正策略后基本可自动化解决），另一类是外部依赖抖动导致的环境错误（通过指数退避+环境修复策略消化了大部分瞬时故障）。

**成本控制**：Self-Healing 不是免费的，每一次诊断和修复都会消耗额外的 Token 和调用时间，因此必须设置修复尝试的预算上限。实践中常见做法是给每个任务设置一个"修复预算池"，例如总 Token 预算的 15%~20% 专门留给 Self-Healing 环节，一旦某个任务的修复尝试消耗超过预算池，立即停止自动修复转为降级或转人工，避免因为反复修复导致单个任务的成本远超正常执行成本。

**监控指标体系**：一个成熟的 Self-Healing 系统必须有配套的可观测性指标，核心包括：

- **检测率（Detection Rate）**：实际发生的错误中，被检测层成功识别出来的比例，衡量检测层的召回能力。
- **诊断准确率（Diagnosis Accuracy）**：诊断报告给出的错误分类与真实根因一致的比例，通常需要人工抽样标注做基线对比。
- **修复成功率（Repair Success Rate）**：进入修复流程的错误中，最终成功恢复执行的比例，按错误类别和策略类型分别统计，用于持续优化策略矩阵。
- **平均修复时间（Mean Time To Repair, MTTR）**：从检测到错误发生到最终恢复正常执行之间的平均耗时，是衡量用户体验影响的关键指标。
- **转人工率（Escalation Rate）**：最终需要人工介入的错误占比，这是衡量整体自动化水平最直观的指标，也是团队持续投入优化 Self-Healing 系统的核心北极星指标之一。

**人类回退机制的设计**：转人工不是简单地抛出一个报错弹窗，而是要把完整的上下文打包交接，至少包含：原始任务描述、失败发生的具体步骤、完整的 ErrorReport 和 DiagnosisReport、所有已尝试过的 `repair_attempts` 及失败原因、以及系统认为可能有效但因权限或风险原因不敢自动执行的候选方案。这种结构化的交接方式能大幅降低人工排查的时间成本，让人类真正只处理"需要人类判断力"的那部分问题。

### 七、演进路线

企业在落地 Self-Healing Agent 框架时，不建议一步到位，而应该按阶段递进，每个阶段都有明确的目标、能力边界和验收标准。

**Phase 1：基础容错**

- 目标：让 Agent 不再"一遇错就崩"，具备最基本的自我恢复能力。
- 能力：静态错误检测（工具错误码/异常捕获）+ 简单的固定次数重试（不区分错误类型，统一重试策略）。
- 验收标准：因单次工具调用瞬时失败导致整个任务失败的比例下降到可接受水平（例如低于 5%）；重试逻辑有明确的次数上限，不会出现死循环。

**Phase 2：智能修复**

- 目标：从"无脑重试"升级到"对症下药"。
- 能力：完整的四层错误检测（静态+语义+验证器+置信度）+ 根因诊断引擎（五类错误分类）+ 修复策略矩阵（策略选择算法）。
- 验收标准：修复成功率相比 Phase 1 有显著提升；不同错误类型能够被正确分类并匹配到差异化的修复策略；具备明确的降级和转人工机制。

**Phase 3：经验驱动**

- 目标：让系统具备"记忆"，避免每次都从零推理。
- 能力：经验积累层完整落地（经验存储+检索+蒸馏）+ Reflexion 深度集成（任务级反思能力）+ 历史成功率驱动的策略选择。
- 验收标准：相似错误的诊断和修复耗时随经验积累明显下降；高频错误模式能够被蒸馏为确定性规则，减少对 LLM 的依赖；转人工率持续下降。

**Phase 4：自主进化**

- 目标：从 Self-Healing 迈向 Self-Evolving，系统能够主动发现能力边界并自我扩展。
- 能力：LATS 树搜索处理高复杂度错误 + 多 Agent 协同自修复（一个 Agent 失败时其他 Agent 可接管）+ 预测性错误预防（在错误发生前基于模式识别提前干预）。
- 验收标准：系统能够处理训练时未见过的新型错误模式并给出合理的探索性修复方案；具备从"被动响应错误"到"主动预警风险"的能力跃迁；人工介入的场景收敛为真正需要创造力和最终决策权的高价值问题。

### 八、面试加分点

**如何用 3 分钟讲清楚 Self-Healing Agent 框架的架构**

建议按照"目标—架构—闭环—案例"四段式组织表达：第一句话点明目标——让 Agent 具备自动检测、诊断、修复错误的闭环能力，把任务成功率从"看运气"变成"可工程化保证"；第二步用一句话概括六层架构——执行引擎产出观测，错误检测层做多手段协同检测，根因诊断层做分类和溯源，修复策略层做策略矩阵匹配和选择，重试执行层做有边界的重试与降级，经验积累层沉淀案例反哺前面所有层；第三步强调这是一个闭环而非流水线——经验层的数据会反过来提升诊断准确率和策略选择的成功率，这是系统"越用越聪明"的关键；第四步用一个具体案例收尾（例如日期参数格式错误的端到端修复过程），让抽象架构落地为可感知的具体流程。

**面试官可能追问的深度问题及回答思路**

1. **"Self-Healing 和简单的 try-except 重试有什么本质区别？"** 回答核心是"有没有诊断环节"：try-except 重试是无差别地重复相同动作，本质是赌运气；Self-Healing 强制要求先诊断根因再选择针对性策略，重试的是"修正后的动作"而不是"原来会失败的动作"，同时具备结构化的经验积累能力，会随时间自我优化。

2. **"如何避免 Self-Healing 系统自己陷入死循环或成本失控？"** 回答要点：bounded-retry 原则（次数、Token、时间三重预算上限）+ 策略去重机制（已尝试失败的策略不会重复选择）+ 分级升级路径（轻量策略失败后才尝试重量级策略，而非一直原地重试）+ 强制降级/转人工兜底。

3. **"检测层为什么需要四种检测器，不能只用一种吗？"** 回答要点：单一检测手段一定有盲区，静态检测只能抓显式报错、抓不住语义错误；纯 LLM 语义检测成本高且会误判；因此采用级联策略——先用零成本的静态和置信度检测过滤大部分情况，只在必要时才触发高成本的语义检测和验证器检测，兼顾覆盖率和成本。

4. **"Reflexion 和 LATS 在你的框架里分别用在什么场景，如何决策？"** 回答要点：以诊断置信度和任务关键性作为决策依据——诊断明确、任务重要性一般时用成本更低的 Reflexion；诊断模糊或任务不可逆/高风险时才升级到 LATS 树搜索做多路径探索评估，本质上是一种成本感知的分级火力策略。

5. **"经验积累层具体怎么防止'旧经验误导新场景'？"** 回答要点：经验检索时不是直接照搬历史结论，而是作为诊断引擎的参考输入之一，与当前上下文回溯结果共同送入 LLM 综合判断；同时经验蒸馏为确定性规则时设有最小出现次数和最小成功率的双重阈值，避免个别偶然案例被过早固化为规则；历史成功率也会随新案例的加入持续更新，不是一次性写死的。

6. **"如果让你评估一个 Self-Healing 系统做得好不好，你会看哪些指标？"** 回答要点：检测率（能不能发现错误）、诊断准确率（能不能说清楚为什么错）、修复成功率（能不能真正解决）、平均修复时间（解决得快不快）、转人工率（自动化程度够不够高），五个指标要结合起来看，不能只看单一维度，例如修复成功率高但平均修复时间极长，说明系统可能在用大量重试硬堆结果，而不是真正做到了精准诊断。

---

*本文涵盖了 Agent 自我修正与 Self-Healing 的核心概念、技术架构、算法实现和工程实践。从 Reflection 到 Self-Healing，从 Reflexion 到 LATS，从 Verifier 到自进化 Agent，系统性地梳理了这一领域的关键技术。希望对面试准备和工程实践有所帮助。*