# Agent 面试题（上）

---

## 问题1：什么是 Agent？与大模型有什么本质不同？

👔面试官：说说你理解的 AI Agent 是什么？

🙋‍♂️我：Agent 就是给大模型加了插件，比如 ChatGPT 的插件功能，让它能联网搜索、调用 API 啥的。

👔面试官：插件是 Agent？那 ChatGPT 开了搜索功能就是 Agent 了？你说的只是工具调用，跟 Agent 差远了。

🙋‍♂️我：哦，那 Agent 就是能调用工具的大模型，给它配几个工具函数，它就能做更多事了。

👔面试官：还是工具调用。Agent 最核心的是什么？你有没有提到「自主」两个字？

🙋‍♂️我：自主......就是它自己决定调哪个工具？

👔面试官：还不够。自主规划、多步执行、感知结果再调整，这才是 Agent 的闭环。你给它个目标，它自己把任务拆成多步，一步一步做，每步结果反馈回来再指导下一步，这和普通调工具有本质区别。

被问懵了吧，其实答好这道题，抓住一个核心词就行：「自主闭环」。

### 💡 简要回答

我理解 Agent 本质上是一个能自主完成目标的 AI 系统，跟传统 AI 最核心的区别在于「自主性」和「能行动」。传统 AI 是你问一个问题它回答一个问题，每次都是独立的，被动响应；而 Agent 有自己的规划能力，你给它一个复杂目标，它会自己把任务拆成多步，通过调工具、访问记忆、感知环境来一步步执行，直到完成。它不只是输出文字，而是真的能做事。

### 📝 详细解析

#### 普通大模型的局限性

要理解 Agent，得先说说普通大模型的局限性在哪。你直接调用 GPT 的 chat 接口，它本质上是个「问答机器」，你给它一个输入，它给你一个输出，然后就结束了。就算是多轮对话，它也只是在当前上下文里被动响应你，它不会主动去做任何事，也不知道自己上一步做了什么、下一步该做什么。

那普通大模型到底差在哪？最直观的一个问题是「知识被冻结」，模型的训练数据有截止日期，你问它今天的天气、最新的股价，它完全不知道。在「知识冻结」之上，还有一个更本质的问题：它「不能行动」。你让它帮你发邮件、帮你查数据库、帮你执行一段代码，它只能告诉你「你可以这样做」，但它自己做不到。而且更麻烦的是，它「没有持续状态」。每次调用之间它是完全失忆的。

![](https://cdn.xiaolincoding.com//picgo/e431d8ce2b38c4628617a91b4b9b97db.png)

这三个局限一环扣一环：知识是死的，手脚是没有的，记忆也是断的。加在一起，意味着普通 LLM 只能做「一问一答」的事情，稍微复杂一点的、需要多步骤协作的任务，它就完全无能为力了。

![](https://cdn.xiaolincoding.com//picgo/image-20260305192546414.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

#### Agent 特别在哪？

Agent 就完全不一样了。它有一个核心的运作闭环：**感知 -> 规划 -> 行动 -> 再感知**。

![](https://cdn.xiaolincoding.com//picgo/4a13bf9723d1882518cb6716bfad078d.png)

你给它一个目标，比如「帮我调研竞品然后整理成报告」，它不是直接输出一段文字了事，而是先拆解任务，然后一步一步去执行，每一步的结果又反馈回来，指导下一步怎么做。

![](https://cdn.xiaolincoding.com//picgo/image-20260305202625429.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

**第一件：工具调用（Tool Use）**，这是让 Agent 从「说话」变成「做事」的关键。Agent 能调用外部工具，比如搜索引擎、代码执行器、数据库、API 等等。不是模型自己执行，而是模型「告诉你该调什么」，你的代码去真正执行，结果再反馈给模型。模型始终只是大脑，不是手脚。

```python
tools = [
    {
        "name": "get_weather",
        "description": "获取指定城市的当前天气",
        "parameters": {
            "type": "object",
            "properties": {
                "city": {"type": "string", "description": "城市名称"}
            },
            "required": ["city"]
        }
    },
    {
        "name": "send_email",
        "description": "发送邮件给指定收件人",
        "parameters": {
            "type": "object",
            "properties": {
                "to": {"type": "string"},
                "subject": {"type": "string"},
                "body": {"type": "string"}
            },
            "required": ["to", "subject", "body"]
        }
    }
]
# Agent 分两步真正执行：
# 第一步：调用 get_weather(city="北京") → 得到 "晴天 15°C"
# 第二步：调用 send_email(to="boss@company.com", subject="今日天气", body="北京今天晴天 15°C")
```

![](https://cdn.xiaolincoding.com//picgo/45666d3465d37176337f3c11313c524c.png)

**第二件：记忆机制**。Agent 系统通常会设计短期记忆和长期记忆两层。短期记忆就是当前任务执行过程中的中间状态；长期记忆则是跨任务的，比如用户的偏好、历史操作记录，通常用向量数据库来存储。

![](https://cdn.xiaolincoding.com//picgo/76ca50010773832fc1bdf95dabe799ac.png)

**第三件：多步推理和自我纠错**。Agent 在执行过程中如果某一步失败了，它不会直接崩掉，而是能感知到失败、分析原因、换一种方式重试。这种「边做边反思」的能力，让 Agent 在面对复杂、不确定的任务时，表现远比死板的自动化流程好得多。

![](https://cdn.xiaolincoding.com//picgo/2dacd3923d9a43e0d405660c3b13bc57.png)

#### 为什么 Agent 现在才爆发？

三个条件在最近几年同时成熟了：大模型的能力跨过了「能用」的门槛（GPT-4、Claude 3）；工具调用的标准化（OpenAI 2023年推出 Function Calling）；配套生态的完善（LangChain、LlamaIndex、向量数据库）。

#### Agent 生态的最新趋势

第一个是 Anthropic 在 2024 年底提出的 **MCP**（Model Context Protocol，模型上下文协议）。MCP 定义一套标准的 JSON-RPC 协议，工具提供方只要按这个标准暴露自己的能力（变成一个 MCP Server），任何支持 MCP 的 Agent 都能直接发现和调用这些工具。MCP 的架构分三层：Host（用户交互的 AI 应用）、Client（管理通信）、Server（暴露工具能力）。

![](https://cdn.xiaolincoding.com//picgo/be5ace5556ea0522c56cdab21893ffdb.png)

第二个是 Google 在 2025 年 4 月推出的 **A2A**（Agent2Agent，Agent 间通信协议）。A2A 的核心设计是 Agent Card 概念，每个 Agent 都有一张「名片」。MCP 管的是 Agent 和工具之间的连接，A2A 管的是 Agent 和 Agent 之间的通信。

![](https://cdn.xiaolincoding.com//picgo/bde0342b042e7e0492e596d3fdff588b.png)

### 🎯 面试总结

面试时答这道题，一定要点出三件事：一是 Agent 有自主规划能力；二是它能行动，通过工具调用跟外部世界真实交互；三是它有闭环，每步的结果会反馈回来指导下一步。另外还要提一句：模型本身只是「大脑」，工具的真正执行是你的代码，模型只负责决策。

---

## 问题2：Agent 的基本架构由哪些核心组件构成？

👔面试官：Agent 架构里有哪些核心组件？

🙋‍♂️我：有 LLM 和工具系统，LLM 是大脑，工具让它能联网搜索、执行代码这些。

👔面试官：就两个？一个 Agent 跑起来，任务执行到一半它怎么知道之前做了什么？

🙋‍♂️我：哦，还有记忆，就是把上下文存进去，让它记得之前的步骤。

👔面试官：记忆就是上下文吗？长任务上下文放不下怎么办？记忆还分哪几种你知道吗？

👔面试官：对，短期记忆放 context window，长期记忆用向量数据库存，两者不一样。还有一个组件你一直没提，复杂目标怎么拆解成步骤，靠谁？

### 💡 简要回答

Agent 的基本架构有四个核心组件：LLM、工具、记忆、规划模块。LLM 是整个系统的大脑，负责理解任务和做决策；工具让 Agent 能跟外部世界交互；记忆让 Agent 在任务执行过程中保持状态；规划模块负责把复杂目标拆解成可执行的步骤。

### 📝 详细解析

你可以把整个 Agent 系统类比成一家公司：**LLM 是老板**，所有决策都经过它拍板；**工具系统是外包执行团队**；**记忆系统是公司档案室**；**规划模块是项目经理**。

![](https://cdn.xiaolincoding.com//picgo/8454bdb306ae864b0d56333ced35241e.png)

#### LLM 核心

它是整个 Agent 的大脑。不过很多人忽略了 System Prompt（系统提示词），它决定了 Agent 的「人格」和行为准则。另一个重要问题是选哪个模型：推理能力、工具调用的稳定性、上下文窗口大小都要考虑。一个常见的工程做法是：用推理能力强的大模型做核心决策，用更快更便宜的小模型做简单任务。

![](https://cdn.xiaolincoding.com//picgo/94c4b632983b1be03ea96ad762cca615.png)

![](https://cdn.xiaolincoding.com//picgo/b6bc3a9b47c5014bf7601485a0300524.png)

#### 工具系统

这是 Agent 和外部世界交互的唯一入口。工具定义里没有执行逻辑，只有「名字、描述、参数说明」。整个分工很清晰：**模型负责「决定做什么」，程序负责「真正执行」**。工具描述的质量直接影响 Agent 的表现。

![](https://cdn.xiaolincoding.com//picgo/7cf48eef352ea705ad05b3ec974c756a.png)

![](https://cdn.xiaolincoding.com//picgo/8669b51e12285ab3f9eb49a2ab2b6464.png)

MCP（Model Context Protocol）定义了三类能力：Tools（会改变外部世界的操作）、Resources（只读的数据源）、Prompts（预定义的提示词模板）。

#### 记忆系统

分几个层次：**短期记忆**就是当前这轮对话的上下文，装在 context window 里；**长期记忆**通常用向量数据库来实现，包括语义记忆（事实性知识）、情景记忆（具体经历）、程序性记忆（怎么做事的经验）。

![](https://cdn.xiaolincoding.com//picgo/be1f702d1ab79bcd71e6910fe5d83425.png)

![](https://cdn.xiaolincoding.com//picgo/812d5b1692914e94b30088261cf31f14.png)

短期记忆最大的问题是上下文窗口有限，解决方案包括摘要压缩和滑动窗口。长期记忆的挑战在于「什么该存、什么不该存」以及记忆衰减（Memory Decay）机制。

#### 规划模块

规划模块的底层依赖 LLM 的推理能力，技术手段包括 **CoT**（Chain of Thought）和 **ToT**（Tree of Thoughts）。实际运作有两种主流模式：**Plan-and-Execute 模式**（先规划后执行）和 **ReAct 模式**（边执行边规划）。

![](https://cdn.xiaolincoding.com//picgo/8ff26036df8db359f4896aebf31cf62a.png)

![](https://cdn.xiaolincoding.com//picgo/b44e7a65f49be5adb3fa132ee81117f4.png)

```python
# Agent 运行的核心 loop（伪代码）
def agent_run(user_goal: str):
    plan = llm.plan(user_goal)
    memory = []
    for step in plan:
        action = llm.decide(
            step=step,
            history=memory,
            long_term=vector_db.search(step)
        )
        if action.type == "tool_call":
            result = tools.execute(action.tool_name, action.args)
            memory.append({"step": step, "result": result})
        elif action.type == "final_answer":
            return action.content
```

![](https://cdn.xiaolincoding.com//picgo/97dbb02b0164e6f14227891cdfb09b2d.png)

### 🎯 面试总结

答好这道题，能把四个组件和类比（LLM 是老板、工具是外包团队、记忆是档案室、规划是项目经理）结合起来说，会非常加分。记忆分两层：短期记忆放在 context window 里，长期记忆用向量数据库实现。工具系统的关键是「决策和执行分离」。

---

## 问题3：Workflow，Agent，Tools 这三个的概念和区别介绍一下？

👔面试官：Workflow、Agent、Tools 这三个概念说一下，区别是什么？

🙋‍♂️我：Tools 是工具函数，Agent 是能调工具的智能体，Workflow 是把多个 Agent 串起来的流程，三者是从小到大的关系。

👔面试官：Workflow 是「多个 Agent 串联」？Workflow 里的节点必须是 Agent 吗？LLM 能不能直接当节点？

👔面试官：对，Workflow 的节点可以是 LLM、Agent 或 Tools，关键不是节点是什么，而是谁来决定「下一步去哪」。

### 💡 简要回答

Tools 是最小的能力单元，就是封装好的可调用函数，本身没有任何决策能力。Agent 是一个完整的决策系统，自己判断什么时候调哪个 Tool。Workflow 是更上层的编排框架，每个节点做什么、按什么顺序流转都是开发者事先写死的。

三者最核心的区别就一句话：Tools 不做决策只执行，Agent 自己做决策，Workflow 是开发者替所有节点把决策提前写好。

### 📝 详细解析

**它们根本不是同一维度的东西，而是粒度不同、可以相互嵌套的三层结构。**

#### 第一层：Tools，最小的能力积木

Tools 本质上是一个「按特定格式暴露给 LLM 的函数」。**工具本身没有任何决策能力，它甚至不知道自己「应该」在什么时候被使用。**

![](https://cdn.xiaolincoding.com//picgo/14cf6adde94713f1ac5e1113f869e621.png)

好的工具设计原则：职责单一、描述要精确、错误信息要清晰、参数设计要简洁。

![](https://cdn.xiaolincoding.com//picgo/84e08ae12396e196ab66b56d2695a37f.png)

#### 第二层：Agent，拿着工具自己做决定的人

**Agent 就是那个「拿着工具、自己决定用哪个」的角色**。Agent 的运行方式是一个反复循环的过程：**想清楚（Thought）-> 行动（Action）-> 看结果（Observation）-> 再想清楚 -> 再行动......**

![](https://cdn.xiaolincoding.com//picgo/ddee6163c886b94b9194671c963cb0ce.png)

![](https://cdn.xiaolincoding.com//picgo/67d3ba55398f2eb3367b089e59137a20.png)

```python
import anthropic
client = anthropic.Anthropic()

def run_agent(user_goal: str):
    messages = [{"role": "user", "content": user_goal}]
    while True:
        response = client.messages.create(
            model="claude-opus-4-6",
            max_tokens=1024,
            tools=tools,
            messages=messages
        )
        if response.stop_reason == "end_turn":
            return response.content[0].text
        tool_use = next(b for b in response.content if b.type == "tool_use")
        tool_result = execute_tool(tool_use.name, tool_use.input)
        messages.append({"role": "assistant", "content": response.content})
        messages.append({
            "role": "user",
            "content": [{"type": "tool_result", "tool_use_id": tool_use.id, "content": tool_result}]
        })
```

![](https://cdn.xiaolincoding.com//picgo/0d1fd6e6f6c2f384122b6f91b8065a85.png)

Agent 的停止条件包括：LLM 主动判断任务完成、最大循环次数、总 token 预算上限、超时机制。**灵活性和不确定性是一对孪生兄弟。**

#### 第三层：Workflow，把所有人组织起来的总指挥

**Workflow 把整个执行流程的「骨架」写在代码里，LLM、Agent、Tools 都只是这个流程里的「节点」，整体走哪条路、下一步去哪里，全由开发者的代码决定。**

![](https://cdn.xiaolincoding.com//picgo/78c78d70e174c4a6a1d0bc0c5be68091.png)

```python
def run_customer_service_workflow(user_query: str) -> str:
    intent = classify_intent_with_llm(user_query)
    if intent == "product":
        docs = search_knowledge_base(user_query)
        answer = generate_answer_with_llm(user_query, docs)
        return answer
    elif intent == "refund":
        order_info = query_order_system(user_query)
        if order_info["eligible"]:
            process_refund(order_info["order_id"])
            return "退款已受理，预计 3 个工作日到账"
        else:
            return "很抱歉，该订单不满足退款条件"
    else:
        escalate_to_human_agent(user_query)
        return "已为您转接人工客服，请稍候"
```

![](https://cdn.xiaolincoding.com//picgo/1d82ec5750a7a4ba159e55402d8fc76b.png)

#### 三者怎么组合？Agentic Workflow 才是生产主流

目前生产环境里最主流的模式是**「Agentic Workflow」**：**用 Workflow 固定主流程的骨架，在需要灵活判断的节点嵌入 Agent，其余固定节点直接用 LLM 或 Tools。**

![](https://cdn.xiaolincoding.com//picgo/b24a03dc9ffb0682b8b9d7de0ac92742.png)

Anthropic 总结的常见 Workflow 编排模式：Prompt Chaining（提示链）、Routing（路由）、Parallelization（并行化）、Orchestrator-Workers（编排者-工人）、Evaluator-Optimizer（评估者-优化者）。

![](https://cdn.xiaolincoding.com//picgo/5a415fe5a2de528b91d97d186904d8aa.png)

|  维度  |    Tools     |     Agent     |     Workflow     |
|------|--------------|---------------|------------------|
| 决策能力 | 无（只执行，不决策）   | 有（LLM 自主动态决策） | 无（开发者在代码里写死）     |
| 执行方式 | 被动，等待被调用     | 主动，自主循环直到完成   | 按开发者定义的顺序执行      |
| 确定性  | 高（输入固定则输出固定） | 低（同输入可能走不同路径） | 高（行为完全可预测）       |
| 灵活性  | 只做一件事        | 高（能应对预料之外的情况） | 低（流程提前写死）        |
| 调试难度 | 容易（单一函数）     | 难（执行路径不确定）    | 容易（链路清晰）         |

### 🎯 面试总结

面试时答这道题，要抓住「谁做决策」这个核心角度。三者不是三选一的关系，而是可以相互嵌套的。生产环境里最主流的不是纯 Agent，而是 Agentic Workflow。

---

## 问题4：了解哪些其他的 Agent 设计范式？Agent 和 Workflow 的区别是什么？

### 💡 简要回答

Agent 和 Workflow 最核心的区别是「谁来决定下一步」。Workflow 是提前把流程写死的，确定性高、好控制；Agent 是让 LLM 自己决定下一步做什么，灵活但不可控。

常见的设计范式除了纯 Agent 之外，还有 ReAct、Plan-and-Execute、Reflection 这几种。实际工程里用得最多的是把两者混用——Agentic Workflow。

### 📝 详细解析

#### Workflow 和 Agent 的区别

**Workflow** 就是一个确定性的流程图，你提前定好每一步的逻辑，LLM 只是其中某个节点的执行工具，不负责决策流程本身。**Agent** 则相反，它把「下一步做什么」这个决策权交给了 LLM。

![](https://cdn.xiaolincoding.com//picgo/image-20260305203811055.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

```python
# Workflow 风格：流程固定
def workflow_answer_question(user_query: str):
    docs = vector_db.search(user_query, top_k=5)
    reranked = reranker.rank(user_query, docs)
    answer = llm.generate(user_query, context=reranked)
    return answer

# Agent 风格：流程不固定，LLM 动态决定每一步
def agent_answer_question(user_query: str):
    while True:
        action = llm.decide(user_query, history=memory)
        if action.type == "search":
            result = vector_db.search(action.query)
            memory.append(result)
        elif action.type == "calculate":
            result = calculator.run(action.expr)
            memory.append(result)
        elif action.type == "final_answer":
            return action.content
```

![](https://cdn.xiaolincoding.com//picgo/f83cfe1b689eab0436cef4e2b2c03e3e.png)

#### Agent 三种设计范式

**ReAct**（Reasoning + Acting）：Thought -> Action -> Observation 循环。每一步都是局部最优决策。

![](https://cdn.xiaolincoding.com//picgo/7a3b85349084240ff3fec3f2be32e6a7.png)

**Plan-and-Execute**：先让 LLM 输出完整步骤列表，然后逐步执行。关键机制是动态重规划——每执行完一步都会把结果反馈给规划器判断是否需要调整。

![](https://cdn.xiaolincoding.com//picgo/5dbbf29f9cf752e8fa4472362c528d79.png)

**Reflection（反思）**：在 Agent 完成一步或整个任务之后，再让 LLM 判断做得好不好。Reflexion 变体会生成「反思总结」存进记忆。在 HumanEval 上，GPT-4 直接做准确率约 80%，加 Reflexion 后提升到 91%。

![](https://cdn.xiaolincoding.com//picgo/ad2253ddc51df3f1d12b02335f8cfd9e.png)

选型核心看两个维度：任务复杂度和质量要求。实际项目里三种范式也不是互斥的，很多系统会混合使用。

![](https://cdn.xiaolincoding.com//picgo/1343589238336479d6a010bd66704024.png)

Anthropic 的原则：**能用 Workflow 解决的问题，就不要用 Agent。** 先从最简单的 Workflow 开始，只有当某个节点确实需要灵活决策时，才把那个节点升级成 Agent。

![](https://cdn.xiaolincoding.com//picgo/9b718f880372adbf11738041f8c45ba0.png)

### 🎯 面试总结

纯 Agent 模式在生产里用得很少，因为行为不确定、难以调试、成本容易失控。真正的工程答案是 Agentic Workflow。能主动说出「为什么纯 Agent 在生产里有局限」，是这道题拿高分的关键。

---

## 问题5：Agent 推理模式有哪些？ReAct 是啥？具体是怎么实现的？

### 💡 简要回答

最基础的是直接输出答案；CoT 是让 LLM 先把推理过程写出来再给答案；ReAct 是在 CoT 基础上加了「行动」，让 LLM 交替输出思考和工具调用，每次行动后再根据结果继续思考，形成一个循环。ReAct 是目前 Agent 用得最广的模式。

### 📝 详细解析

#### 什么是推理模式？

LLM 面临的根本困境：当它「一口气」预测答案时，中间的推导步骤都是隐式的，误差会在中间悄悄累积。「推理模式」存在的根本原因就是：通过不同的方式，让 LLM 把隐式的思考过程显式化出来，从而减少多步推理中的累积误差。

![](https://cdn.xiaolincoding.com//picgo/dd7373d8069bf6e0f7066ea3dea8b025.png)

#### CoT是什么？

CoT（Chain of Thought，思维链）：在 prompt 里加一句「让我们一步步思考」，LLM 就会先把推理步骤写出来再给答案。两种触发方式：Zero-shot CoT（直接加提示语）和 Few-shot CoT（给带推理过程的例子）。

![](https://cdn.xiaolincoding.com//picgo/15f430f186d3500892974b46afa51783.png)

但 CoT 有一个根本性的局限：**它是纯文字推理，没有办法和外部世界交互**。

#### ReAct 是什么？

ReAct 是 Reasoning and Acting 的缩写，核心思路是在 CoT 的推理链里，插入真实的「行动」。让 LLM 按照「思考 -> 行动 -> 观察」这个循环来推进任务。

![](https://cdn.xiaolincoding.com//picgo/image-20260305215844421.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

![](https://cdn.xiaolincoding.com//picgo/e822a4c6cffd06074bc211b000ac3513.png)

具体例子：

```
Thought: 这道题需要两家公司的实时市值数据，我得先查苹果的市值
Action: search
Action Input: 苹果公司 2024 年市值
Observation: 苹果公司 2024 年市值约为 3.5 万亿美元

Thought: 好，苹果的数字有了，再查谷歌的
Action: search
Action Input: 谷歌 2024 年市值
Observation: 谷歌 2024 年市值约为 2.1 万亿美元

Thought: 两个数字都有了，苹果 3.5 万亿，谷歌 2.1 万亿，苹果更高，差距是 1.4 万亿
Final Answer: 苹果公司 2024 年市值约 3.5 万亿美元，谷歌约 2.1 万亿美元，苹果更高，差距约 1.4 万亿美元
```

**ReAct 的实现原理**——这个循环不是 LLM 自己在转，而是由你的代码来驱动的：

```python
def react_agent(question: str, tools: dict, max_steps: int = 10):
    prompt = build_react_prompt(question, tools)
    history = []
    for _ in range(max_steps):
        response = llm.generate(prompt + "\n".join(history))
        if "Final Answer:" in response:
            return response.split("Final Answer:")[-1].strip()
        action, action_input = parse_action(response)
        if action in tools:
            observation = tools[action](action_input)
        else:
            observation = f"工具 {action} 不存在，请选择可用工具"
        history.append(response)
        history.append(f"Observation: {observation}")
    return "超过最大步数，任务未完成"
```

![](https://cdn.xiaolincoding.com//picgo/388e56899143a40fc8b3620b19ba1eb4.png)

ReAct 的两个实战坑：**循环漂移**（走着走着偏离目标）和**错误传播**（中间某步错误导致后续全部白费）。

![](https://cdn.xiaolincoding.com//picgo/c5050783219d14e9f0010326e1202f0d.png)

#### Plan-and-Execute：先规划再执行

Plan-and-Execute 把整个流程拆成「规划」和「执行」两个阶段。类比「先看地图再出发」vs ReAct 的「边走边问路」。

![](https://cdn.xiaolincoding.com//picgo/f4c09c16a0ff2ceacc79a31448d51ea9.png)

```python
def plan_and_execute(question: str, tools: dict):
    plan = llm.generate(f"请为以下任务制定分步执行计划：{question}")
    steps = parse_plan(plan)
    results = []
    for i, step in enumerate(steps):
        step_result = react_executor(task=step, tools=tools, previous_results=results)
        results.append(step_result)
        if need_replan(step, step_result, steps[i+1:]):
            remaining_steps = llm.generate(f"原计划：{steps}\n已完成到第{i+1}步，结果：{results}\n剩余步骤是否需要调整？")
            steps = steps[:i+1] + parse_plan(remaining_steps)
    return llm.generate(f"根据以下执行结果回答问题：{results}")
```

实际工程中常混合使用：规划阶段用强模型（GPT-4），执行阶段用便宜的小模型，可降低 70%-90% 的 LLM 调用成本。

![](https://cdn.xiaolincoding.com//picgo/4b47c29cd8d825a50959d1e5b5f2dbeb.png)

### 🎯 面试总结

核心点：第一，ReAct 的本质是「思考 -> 行动 -> 观察」的循环；第二，这个循环是由你的代码框架驱动的，模型每次只输出 Thought + Action，你的代码负责解析、执行工具、把 Observation 填回历史。主动提一下 ReAct 的两个实战局限和 Plan-and-Execute 如何解决漂移问题会很有深度。

---

## 问题6：ReAct、Plan-and-Execute、Reflection 三种范式有什么核心区别？实际项目中该如何选型？

### 💡 简要回答

ReAct 是边想边干，走一步看一步；Plan-and-Execute 是先想全再干，先定完整计划再分步执行；Reflection 不是独立的完整流程，而是给前两者加的「检查修正 buff」，用来提升输出质量。

选型看三个维度：任务复杂度、流程确定性、输出质量要求。

### 📝 详细解析

**设计范式**是「从头到尾按什么大逻辑跑」，**推理模式**是 Agent 在每一步「脑子里具体是怎么思考的」。设计范式是公司的管理制度，推理模式是员工的干活方法。

![](https://cdn.xiaolincoding.com//picgo/1df66f1f9d1bb4b060e378760b53716e.png)

#### 一、基础款：ReAct 单步迭代范式

ReAct 就像外卖骑手小哥，实时根据情况做决策，没有提前定死完整计划。和 Plan-and-Execute 相比没有「提前做完整规划」的环节；和 Reflection 相比循环里没有「专门的自我检查环节」。

![](https://cdn.xiaolincoding.com//picgo/d4e34a947785ab55eb644e7fa36700b9.png)

#### 二、复杂任务款：Plan-and-Execute 规划执行范式

把「规划推理」和「执行推理」完全拆开。实用技巧：「强模型规划、弱模型执行」的混合策略可以把总成本降低 70%-90%。

![](https://cdn.xiaolincoding.com//picgo/25072b50ca2c07f96fd1c61d3a1c1bba.png)

#### 三、质量增强款：Reflection 反思迭代范式

**Reflection 不是一套独立的完整流程，而是给 ReAct、Plan-and-Execute 加的「锦上添花的 buff」**。核心循环是「生成→评估→改进」的闭环。

![](https://cdn.xiaolincoding.com//picgo/7d9d85c6ed952843a0b6f74d801f861b.png)

#### 进阶：动态 Replan 和 Reflexion

**动态 Replan**：每个步骤执行完后把当前结果和剩余计划交给规划模块，判断是否需要调整。

**Reflexion**：不仅检查输出对不对，还会生成「反思总结」存进记忆，下次再遇到类似任务时带着教训重试。在 HumanEval 上把 GPT-4 的准确率从 80% 提升到 91%。

![](https://cdn.xiaolincoding.com//picgo/f5bbc0120f0d7b51e62d989ab6080755.png)

#### token 消耗对比

假设 5 步任务，每步 2000 token：ReAct 约 30000 token（线性增长）；Plan-and-Execute 约 14500 token（低一半多）；加 Reflection 在基础上再增加 30%-100%。

![](https://cdn.xiaolincoding.com//picgo/cc14fb3728b51d09e7b0d3d0523ea94e.png)

#### 选型指南

任务不复杂用 **ReAct**；任务很长容易跑偏用 **Plan-and-Execute**；输出要求高再叠加 **Reflection**；需要跨任务积累经验用 **Reflexion**。

![](https://cdn.xiaolincoding.com//picgo/6268dfb4bfdfe015fc74c224d99ff26f.png)

### 🎯 面试总结

先把 Reflection 的定位说清楚（不是独立流程，是质量增强 buff），再按维度对比三者核心区别。选型口诀：任务简单用 ReAct，流程长且复杂用 Plan-and-Execute，输出要求高再加 Reflection。顺带提「别过度工程化、够用就好」。

---

## 问题7：复杂任务怎么做的任务拆分？为什么要拆分？效果如何提升？

### 💡 简要回答

任务拆分的原因是 LLM 一次性处理太复杂的任务很容易出错，把大任务拆成小步骤，每步聚焦一件事，准确率会明显提升。拆分方式有静态拆分（提前写死步骤）和动态拆分（让 LLM 自己规划）。拆完后分析依赖关系并行执行，端到端延迟可降 40%-60%。

### 📝 详细解析

#### 为什么任务要拆分？

LLM 的工作台（context window）有大小限制。任务越大，中间状态越多，桌面就越乱：LLM 很难持续追踪「我现在在做哪个子目标」。

![](https://cdn.xiaolincoding.com//picgo/2d164a643eab31e9b65baa3e0412d2a8.png)

![](https://cdn.xiaolincoding.com//picgo/2858abbe048e9c645fba20da1075e98d.png)

#### 任务拆分两种思路

**静态拆分**：你提前把任务流程设计好，固定成确定的 Workflow。好处是行为完全可预测；坏处是灵活性低。

![](https://cdn.xiaolincoding.com//picgo/bd3a96438935cfbbaeaae7ff79e22269.png)

**动态拆分**：把「任务拆解」交给 LLM 来做（Plan-and-Execute 模式）。整个流程分三个阶段：规划（输出步骤列表）、执行（逐步执行）、汇总（整合产出）。

![](https://cdn.xiaolincoding.com//picgo/f28a8a2f3564f9330cf8f3e5a0abe52a.png)

![](https://cdn.xiaolincoding.com//picgo/image-20260305205319788.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

**并行优化**：识别步骤间依赖关系，把能并行的步骤并发跑。把依赖关系画成有向无环图（DAG），没有依赖的节点同时跑。

![](https://cdn.xiaolincoding.com//picgo/f3f31ec645ee9e0e9492140caec58a1c.png)

```python
import asyncio

async def execute_parallel_steps(independent_steps: list):
    tasks = [execute_step_async(step) for step in independent_steps]
    results = await asyncio.gather(*tasks)
    return results

# 依赖图：
# 步骤1 ──────────────┐
#                      ├──> 步骤3 ──┐
# 步骤2 ──────────────┘            ├──> 步骤4（最终输出）
#          └────────────────────── ┘
```

![](https://cdn.xiaolincoding.com//picgo/b2ff20224b4d4c85283b435086fc5455.png)

![](https://cdn.xiaolincoding.com//picgo/103a1a9f7a05bb3f148baeb38bf6c48c.png)

粒度把握：以「原子操作」为标准——这个步骤只做一件独立的事，边界清晰，做完有明确的输出。

#### 自适应拆分：做不好就继续拆

核心逻辑：先让执行器尝试完成当前任务，做得好就继续；做不好就交给规划器进一步拆成更小的子任务。整个过程就像一棵递归展开的任务树。

![](https://cdn.xiaolincoding.com//picgo/e59bd91e9e0cd8d1ee6e75ba51b93f50.png)

#### 执行中的 Replan 机制

每个步骤执行完后，把当前结果和剩余计划交给规划模块，判断后面的计划还合理吗。实践中的折中做法：设置触发条件，比如输出和预期差异很大时才启动 Replan。

#### 拆分结果的验证标准

三个条件：**完备性**（所有步骤加起来能覆盖原始任务全部要求）、**独立性**（每个步骤职责边界清晰）、**可验证性**（每个步骤执行完能用简单标准判断做对没有）。

![](https://cdn.xiaolincoding.com//picgo/35337d6ce220a5ceaec0328ddcb17577.png)

### 🎯 面试总结

答出三个层次：为什么拆（context window 有限，桌面太乱易出错）；怎么拆（静态拆分 vs 动态拆分）；拆完还要做什么（依赖分析 + 并行执行，降 40%-60% 延迟）。最后补一句「粒度把握很重要，以原子操作为标准」。

---

## 问题8：请你介绍一下 AI Agent 的记忆机制，并说明在实际开发中应该如何设计记忆模块？

### 💡 简要回答

Agent 需要记忆才能在多步任务中保持状态、跨任务积累知识。记忆机制分四层：感知记忆（当前输入的原始内容）、短期记忆（context window 里的对话历史）、长期记忆（存在外部数据库、语义检索召回）、实体记忆（结构化提取的关键事实）。

实际设计时要解决三个核心问题：存什么、怎么存、什么时候取出来用。

### 📝 详细解析

#### 没有记忆的 Agent 有多不好用

你今天告诉 Agent 你的代码风格偏好，明天重新打开对话，它完全不记得。记忆是 Agent 从「单次问答工具」变成「真正助手」的关键分水岭。

![](https://cdn.xiaolincoding.com//picgo/01_memory_divider.png)

#### 四种记忆类型（从最短暂到最持久）

![](https://cdn.xiaolincoding.com//picgo/02_memory_pyramid.png)

**第一层：感知记忆（Sensory Memory）** —— 当前这次调用的原始输入，生命周期只有一次调用。

**第二层：短期记忆（Short-term Memory）** —— context window 里的 messages 列表，任务结束就清空。类比「工作台」。

**第三层：长期记忆（Long-term Memory）** —— 存在外部数据库里，跨任务持久化。关键技术是向量数据库（语义检索）。细分为：
- **情节记忆**（Episodic Memory）：具体事件经历
- **语义记忆**（Semantic Memory）：从多次经历提炼的通用知识
- **程序记忆**（Procedural Memory）：操作流程 SOP

**第四层：实体记忆（Entity Memory）** —— 从对话中提炼出来的结构化事实，如「用户偏好 Python」「客户预算是 5 万」。

|  类型  |       载体       |     容量     | 生命周期 | 访问方式 |
|------|----------------|------------|------|------|
| 感知记忆 | 当次输入           | 极小         | 单次调用 | 即时访问 |
| 短期记忆 | context window | 受 token 限制 | 一次任务 | 直接读取 |
| 长期记忆 | 向量/关系数据库       | 无限         | 持久   | 语义检索 |
| 实体记忆 | 结构化存储          | 无限         | 持久   | 精确查询 |

#### 实际设计记忆模块的三个核心问题

**第一个：存什么？** 判断标准：「这条信息，下次任务开始时如果知道，会让 Agent 做得更好吗？」值得存的：用户偏好、关键结论和决策、外部知识。不值得存的：中间推理过程、工具返回的原始数据、闲聊。

![](https://cdn.xiaolincoding.com//picgo/74cc7ac9aeca708a9fd423a680d9f4cd.png)

**第二个：怎么存？** 语义内容用向量数据库；结构化偏好用关系数据库或 Key-Value 存储。混合存储是主流做法。

![](https://cdn.xiaolincoding.com//picgo/1d9f90056c682fea0e23cac92c3c99a9.png)

![](https://cdn.xiaolincoding.com//picgo/03_hybrid_storage.png)

**第三个：什么时候取出来用？** 两种策略：「主动检索」（任务开始前用任务描述检索相关记忆注入 system prompt）和「被动触发」（把「查记忆」封装成 Tool，让 Agent 按需调用）。

![](https://cdn.xiaolincoding.com//picgo/a60cb25904a06a04b5e122f4e937dbd5.png)

#### Context Window 管理

解决方案：**滑动窗口**（只保留最近 N 轮）、**摘要压缩**（用 LLM 把早期历史压缩成摘要）、**卸载到长期记忆**（不常用但重要的信息存到向量数据库）。

![](https://cdn.xiaolincoding.com//picgo/04_context_window_strategies.png)

开源框架：**Mem0**（独立记忆服务层，支持向量和图存储）、**Letta/MemGPT**（三层记忆架构：Core Memory、Recall Memory、Archival Memory，让 Agent 自己管理记忆）、**Zep/Graphiti**（时间感知，时序知识图谱管理记忆生命周期）。

#### 知识图谱：让记忆之间产生关联

用「实体 -> 关系 -> 实体」的三元组结构存储信息。查询时可以沿着关系链条做多跳推理，这是向量检索做不到的。通常和向量数据库配合使用。

![](https://cdn.xiaolincoding.com//picgo/05_knowledge_graph.png)

#### 记忆整合：从碎片到知识

关键环节：**去重**（合并语义相近的记忆）、**冲突消解**（保留时间更新的）、**抽象提炼**（把情节记忆转化为语义记忆——从多次经历中「蒸馏」出通用规律）。

![](https://cdn.xiaolincoding.com//picgo/06_episodic_to_semantic.png)

#### 完整记忆模块的配合方式

「读 -> 用 -> 写」三个阶段形成完整闭环：

**第一阶段：任务开始前，先「读」记忆** —— 从实体记忆取用户偏好，从长期记忆做语义检索拿相关背景，拼进 system prompt。

![](https://cdn.xiaolincoding.com//picgo/9c6b775f96b971e508e17be350e1cf03.png)

**第二阶段：任务执行中，持续「用」记忆** —— 短期记忆全程工作，按需发起长期记忆检索。

![](https://cdn.xiaolincoding.com//picgo/7b10235ccdffeb55de8fca51617806a1.png)

**第三阶段：任务结束后，主动「写」记忆** —— 新偏好更新实体记忆，有价值结论写入长期记忆，短期记忆清空。

![](https://cdn.xiaolincoding.com//picgo/45b1522579f9691ecb9594e7c31e8e51.png)

![](https://cdn.xiaolincoding.com//picgo/image-20260310210358364.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

![](https://cdn.xiaolincoding.com//picgo/07_read_use_write_loop.png)

### 🎯 面试总结

先把四层分类说清楚（感知/短期/长期/实体），再答三个工程核心问题（存什么、怎么存、什么时候取），最后用「读 -> 用 -> 写」三阶段闭环收尾。长期记忆细分为情节/语义/程序三种子类型，能说出来会很加分。
