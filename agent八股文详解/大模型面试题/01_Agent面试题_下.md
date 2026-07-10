# Agent 面试题（下）

---

## 问题9：Agent 的长短期记忆系统怎么做的？记忆是怎么存的？粒度是多少？怎么用的？

👔面试官：你们项目里 Agent 的记忆系统是怎么做的，短期记忆和长期记忆分别存在哪？

🙋‍♂️我：短期记忆就是把对话历史存在内存里，长期记忆存数据库，需要的时候查出来用。

👔面试官：长期记忆「存数据库」？那你用的什么数据库，怎么查的，按关键词全文搜索吗？

🙋‍♂️我：也可以用关键词搜索，就是普通的字符串匹配，比如用 SQL 的 LIKE 查询......

👔面试官：你这样搜根本搜不到语义相关的内容。比如用户问的是「代码习惯」，历史里存的是「Python 风格偏好」，关键词不重叠，你怎么匹配？

🙋‍♂️我：那......我把粒度搞细一点，把每句话都拆开存，这样关键词覆盖更全。

👔面试官：拆得越细，检索噪音越大，一个完整的用户偏好被拆成四五条，检索时只命中其中两条，拿到的是碎片化信息，这才是问题所在。你知道长期记忆的正确存法是什么吗？

好，被追问到这里说明这道题的坑不少，咱来系统说一下 Agent 记忆系统的正确做法。

### 💡 简要回答

我理解记忆系统分两层。

短期记忆就是 context window 里的对话历史，存当前任务的中间状态，任务结束就清掉；长期记忆用向量数据库存，把信息 embedding 后写入，用的时候做语义检索拿回来注入 prompt。

粒度上我通常按「一次完整交互」或「一个关键事件」为单位存，太细碎检索噪音大，太粗糙又丢失细节，这个需要根据业务实际调整。

### 📝 详细解析

先假设一个没有记忆系统的 Agent，感受一下它会有多不堪用。

你今天找它说「帮我优化这段 Python 代码，风格要简洁一点，变量命名用英文」，它帮你优化好了。明天你又找它说「帮我写一个爬虫脚本」，它输出了一段用中文变量命名的代码，风格也很啰嗦。你很困惑，昨天不是刚说好了吗？对它来说，昨天的对话压根不存在。它不记得你的偏好，不记得你们达成过什么约定，每次对话都是全新的开始，就像每次见面都是第一次认识。

这个「失忆」问题，对单次问答来说不是大问题，但对一个要持续帮你工作的 Agent 来说，意味着它永远无法积累对你的了解，也无法在多次任务之间建立连贯性。

记忆系统就是为了解决这件事而存在的：让 Agent 既能在一次任务执行过程中保持状态，也能跨任务记住重要信息。实现上，记忆被拆分成两层，它们解决的问题不同，实现方式也完全不同。

![](https://cdn.xiaolincoding.com//picgo/image-20260305220214313.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

#### 短期记忆

先说**短期记忆**，它就是你每次调 LLM 时传进去的 messages 列表。你可以把它想象成 LLM 当前的「工作台」，桌面上摆着当前任务的所有相关内容：用户的指令、LLM 自己的思考过程、工具调用的结果、每一步的中间状态。LLM 靠这张桌面知道「我在做什么、做到哪了、前面几步发现了什么」。

实现上非常直接，就是维护一个列表，每一步产生的内容都追加进去：

```python
class ShortTermMemory:
    def __init__(self):
        self.messages = []

    def add(self, role: str, content: str):
        self.messages.append({"role": role, "content": content})

    def get_context(self):
        return self.messages

    def clear(self):
        self.messages = []

# 一次任务执行的示例
memory = ShortTermMemory()
memory.add("user", "帮我分析这几家竞品的核心功能差异")
memory.add("assistant", "好的，我先搜索一下竞品 A 的信息")
memory.add("tool", "搜索结果：竞品 A 的核心功能是实时协作编辑...")
memory.add("assistant", "已拿到竞品 A 的信息，再搜竞品 B")

response = llm.chat(messages=memory.get_context())
```

这里有一个重要的点：每次调 LLM 时传的是完整的历史，而不只是最新一条消息。这就是短期记忆的本质，把整个任务状态带在身上。代价是 messages 会随着任务进展越来越长，context window 总有一天会装满，早期的内容就开始被截断，Agent 就开始「遗忘」。

![](https://cdn.xiaolincoding.com//picgo/01_short_term_workbench.png)

短期记忆在当前任务结束后就清空了，下次来了新任务，桌面是空的，什么都不记得了。要让 Agent 跨任务记住东西，就需要长期记忆。

不过在聊长期记忆之前，有一个进阶概念值得了解：**结构化工作记忆**（Structured Working Memory）。前面说的短期记忆是纯粹的消息列表，什么都往里塞，比较粗放。

结构化工作记忆的思路是，给这个「工作台」划出几个固定区域，比如一个区域专门放「当前任务目标」，一个区域放「已确认的中间结论」，一个区域放「待验证的假设」。每一步执行完之后，Agent 不只是把新消息追加到列表末尾，而是主动更新对应区域的内容，把过时的中间结论替换掉，把已验证的假设移到确认区。

这样做的好处是，即使对话很长，Agent 的工作台始终是结构清晰的，不会被一堆杂乱的历史消息淹没，LLM 每次读到的都是当前最准确的任务状态。

![](https://cdn.xiaolincoding.com//picgo/02_structured_working_memory.png)

#### 长期记忆

**长期记忆**的核心工具是向量数据库（Vector Database）加上 Embedding（向量化）。

Embedding 是把一段文字转化成一组数字的过程。这组数字通常有几百到几千个维度，它们共同捕捉了这段文字的「语义」。语义相近的文字，转化出来的数字向量在空间里也靠得很近。

举个类比：你把颜色编码成 RGB，红色是 (255, 0, 0)，橙色是 (255, 165, 0)，它们的数字距离很近，因为颜色本身就相近。深蓝色是 (0, 0, 139)，和红色的数字距离很远，颜色也相差很远。

![](https://cdn.xiaolincoding.com//picgo/image-20260305220643165.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

Embedding 对文字做的事是一样的：「苹果公司的产品策略」和「Apple 的产品线规划」，文字不同但语义相近，embedding 出来的向量在空间里距离就很近；「苹果公司」和「猫吃鱼」，语义毫不相关，向量距离就很远。

![](https://cdn.xiaolincoding.com//picgo/03_vector_space.png)

向量数据库，就是专门存这些数字向量的数据库。它最核心的能力是「相似度检索」：给你一个查询向量，找出数据库里和它距离最近的几条记录，也就是语义最相关的内容。

你可以用图书馆的索引卡来类比：找书时你不需要逐本翻阅内容，而是先查索引卡快速定位可能相关的书，再去书架上取原文。向量数据库里的 embedding 就是这些「语义索引卡」，再加上 HNSW / IVF 这类近似最近邻（ANN）索引结构做加速，检索效率极高。

![](https://cdn.xiaolincoding.com//picgo/04_library_ann_index.png)

再多说一层：长期记忆其实还可以按「类型」细分成三种。

第一种是**语义记忆**（Semantic Memory），存的是事实性知识，比如「用户是 Python 开发者」「项目预算上限 5 万」。

第二种是**情节记忆**（Episodic Memory），存的是具体事件的经历，比如「上周二用户让我写了一个爬虫，中间因为反爬策略改了三次方案」。

第三种是**程序记忆**（Procedural Memory），存的是「怎么做某件事」的方法论，比如「给这个用户写代码时，先确认风格偏好，再写主逻辑，最后加注释」。

```python
from openai import OpenAI
import chromadb

client = OpenAI()
db = chromadb.Client()
collection = db.get_or_create_collection("agent_memory")

def save_to_long_term(content: str, metadata: dict):
    embedding = client.embeddings.create(
        input=content, model="text-embedding-3-small"
    ).data[0].embedding
    collection.add(
        embeddings=[embedding],
        documents=[content],
        metadatas=[metadata],
        ids=[f"mem_{hash(content)}"]
    )

def retrieve_memory(query: str, top_k: int = 3) -> list[str]:
    query_embedding = client.embeddings.create(
        input=query, model="text-embedding-3-small"
    ).data[0].embedding
    results = collection.query(
        query_embeddings=[query_embedding], n_results=top_k
    )
    return results["documents"][0]
```

这里还有一个容易忽略的问题：**记忆衰减**。常见的做法是给每条记忆加一个「新鲜度权重」，检索排序时同时考虑语义相似度和时间新鲜度，越久远的记忆权重越低。

存长期记忆时，「一次存多少内容」这个粒度问题非常关键，直接影响后续检索的质量。

![](https://cdn.xiaolincoding.com//picgo/838523ce6814138017d0006d5cfdb692.png)

比较合理的粒度是按「一次完整交互」或「一个独立的知识点/事件」来存。

![](https://cdn.xiaolincoding.com//picgo/05_memory_granularity.png)

短期记忆在任务执行的整个过程中起作用，是那个时刻在变化的「工作台」；长期记忆在任务开始前和任务结束后起作用，是沉淀下来的「档案」。前者保证当前任务的连贯性，后者保证跨任务的积累。

![](https://cdn.xiaolincoding.com//picgo/06_short_long_memory_timeline.png)

### 🎯 面试总结

第一个雷是把长期记忆说成「存数据库靠关键词搜索」，长期记忆的核心是 Embedding + 向量数据库，靠语义相似度而不是字符串匹配来检索。

第二个雷是以为粒度越细越好，实际上粒度太细会导致记忆碎片化，合理粒度是「一次完整交互」或「一个独立知识点」。

第三个雷是搞不清两层记忆各自的作用时机，短期记忆是任务执行中的「工作台」，任务结束就清空；长期记忆是任务前检索注入、任务后写入沉淀。

---

## 问题10：什么是 Multi-Agent？

👔面试官：你了解 Multi-Agent 吗，说说它是什么，为什么要用？

🙋‍♂️我：Multi-Agent 就是多个 AI 一起工作，可以提高效率，一个人搞不定的事情多个人一起做。

👔面试官：你说「一个人搞不定」，具体是哪方面搞不定？技术上的根本限制是什么？

🙋‍♂️我：就是......任务太复杂，一个 Agent 处理不过来，容易出错。

👔面试官：「处理不过来」太模糊了。单个 Agent 有一个非常具体的硬限制，你知道是什么吗？不是「容易出错」，是结构性的上限。

🙋‍♂️我：哦，是 context window 的大小限制，装不下太多内容。

👔面试官：对，这是第一个。那除了 context 限制，还有一个更深层的问题，跟专业能力有关，你能说出来吗？

被追问到根因了，其实 Multi-Agent 的价值不只是「多几个 AI」，背后有两个很具体的工程问题驱动着它。

### 💡 简要回答

多智能体系统（Multi-Agent）就是多个 Agent 协作完成任务，每个 Agent 各有分工，有的负责搜索、有的负责写代码、有的负责做评审。

我理解单个 Agent 主要受两个限制：一是 context 窗口大小，复杂任务信息量一多就撑爆了；二是单点能力，什么都让一个 Agent 做，每件事都是泛才。

Multi-Agent 通过专业分工和并行执行，能处理更复杂、更长流程的任务，这是我在实际项目里选择多智能体方案的核心原因。

### 📝 详细解析

想象这样一个场景：你让 Agent 帮你完成「写一份完整的 AI 行业竞品分析报告」。它需要搜索十几家竞品、读懂每家的产品功能、梳理核心差异、整理对比数据、最后写结论。

光是搜索下来，每家竞品几百字，十家就是几千字的搜索结果，再加上来回确认的对话历史和中间推理，还没开始写结论，整个工作台就已经快撑满了。

这里说的「工作台」，就是 LLM 的 context window。这个工作台是有大小上限的，常见的模型限制从 12.8 万到 100 万个 token 不等，塞满了之后，早期的内容就会开始「掉落」。

![](https://cdn.xiaolincoding.com//picgo/01_single_agent_overload.png)

context 有上限，这是第一个硬限制。但更深的问题其实是「专业度」的问题。

让一个 Agent 既搜信息、又写代码、又做测试、又写文档，它在每一件事上都得兼顾，精力是分散的，就像一个人同时担任产品经理、程序员、测试工程师和文档工程师，每个角色都做得不够专注，互相干扰。

#### Multi-Agent 核心思路

Multi-Agent 的核心思路，就是「团队作战代替单打独斗」。

与其让一个 Agent 包揽所有事，不如把任务按职能拆开，每个 Agent 只负责一件事，专心做好自己那块，做完把结果传给下一个。

Multi-Agent 之间的协作方式主要有三种模式。第一种是**顺序流水线**（Sequential Pipeline），Agent A 做完把结果交给 Agent B，B 做完交给 Agent C。第二种是**并行扇出**（Fan-out），一个调度者把多个独立子任务同时分发给不同的 Worker Agent，它们各自并行执行，最后由调度者收集汇总。第三种是**辩论/评审模式**（Debate/Review），多个 Agent 对同一个问题各自给出方案，然后由一个裁判 Agent 或者它们互相评审来筛选最优解。

![](https://cdn.xiaolincoding.com//picgo/02_collaboration_patterns.png)

就像公司里的部门协作：产品经理负责需求梳理、开发负责写代码、测试负责验收，每个人专注自己的职责，信息传递清晰，哪个环节出了问题也好定位责任。Multi-Agent 系统就是把这套分工思想搬到 AI 里。

还是以「开发一个爬虫工具」为例，来感受一下两种做法的差距。

不用 Multi-Agent 的情况：一个 Agent 接到任务，同时在想需求文档、代码结构、测试策略，context 里塞满了各种信息，思路乱成一锅粥。

用了 Multi-Agent 的情况：

* 第一个 Agent 是「需求分析师」，它只做一件事，把用户需求转化成清晰的功能列表；
* 第二个 Agent 是「程序员」，拿到功能列表，专注写代码；
* 第三个 Agent 是「测试工程师」，拿到代码，专注写测试用例......

每个 Agent 的工作台都很干净，只有自己这块任务相关的内容，专业度也更高。

![](https://cdn.xiaolincoding.com//picgo/03_separate_workbenches.png)

更关键的是，需求分析这步结束之后，程序员 Agent 和测试 Agent 其实可以并行工作，测试框架的搭建不需要等代码写完，两件事同时进行，整体速度也快了。

目前业界已经有不少成熟的 Multi-Agent 框架可以直接用，比如 CrewAI、LangGraph 等。值得注意的是，微软在 2025 年推出了 Microsoft Agent Framework（MAF），它把微软原来两条并行的产品线——Semantic Kernel（企业级）和 AutoGen（多 Agent 编排）——合并到了一起。

![](https://cdn.xiaolincoding.com//picgo/04_framework_matrix.png)

Multi-Agent 系统的组织方式主要有两种：一种是中心化，由一个统一的调度者来分配任务、收集结果；另一种是去中心化，Agent 之间自行协商、直接通信。工程上用得更多的是中心化方案，因为调度逻辑清晰、责任归属明确、排查问题也容易。

![](https://cdn.xiaolincoding.com//picgo/05_centralized_vs_decentralized.png)

### 🎯 面试总结

面试官最想听到的是两个具体的技术驱动因素：第一是 context window 的硬上限，单个 Agent 处理复杂任务时信息量一旦超出窗口，就开始「遗忘」；第二是专业度问题，让一个 Agent 身兼数职，每件事都做得不够专注，分工之后每个 Agent 的 context 是干净的，只装自己那块的信息，专业能力也更强。

回答时还要提到并行执行这个好处，多个 Worker 同时跑，整体效率有实质提升。

---

## 问题11：说说 Single-Agent 和 Multi-Agent 的设计方案？

👔面试官：你实际项目里是怎么做技术选型的，什么时候用 Single-Agent，什么时候上 Multi-Agent？

🙋‍♂️我：任务简单就用 Single-Agent，任务复杂就用 Multi-Agent，多个 Agent 可以并行，速度更快。

👔面试官：「复杂」这个词太模糊，有没有更具体的判断标准？

🙋‍♂️我：就是......步骤多、需要调很多工具，这种就用 Multi-Agent 吧。

👔面试官：步骤多不一定要 Multi-Agent，Single-Agent 循环调工具也能搞定很多步骤的任务。你有没有想过，Multi-Agent 本身是有成本的，盲目引入会有什么问题？

🙋‍♂️我：那 Multi-Agent 的话，两个方案都行，中心化和去中心化看情况选，去中心化更灵活，感觉挺好的。

👔面试官：去中心化在工程实践里几乎没有人用，你知道为什么吗？灵活只是表面，背后藏着几个很实际的工程问题。

被追到这儿了，其实选型这件事有一套清晰的决策逻辑，不是凭感觉的。

### 💡 简要回答

Single-Agent 适合任务流程清晰、复杂度适中的场景，实现简单、好维护；Multi-Agent 适合需要专业分工、任务量大或者需要并行执行的复杂场景。

Multi-Agent 架构上主要有两种拓扑：中心化的 Orchestrator 模式，由一个主 Agent 统一调度各个 Worker；去中心化的 Peer-to-Peer 模式，Agent 之间直接通信。

我在工程里用中心化用得更多，因为好控制、好调试，出问题链路清晰。

### 📝 详细解析

#### Single-Agent

先把 Single-Agent 说清楚。它的本质是一个 LLM 加上一套工具，跑一个决策循环：LLM 判断下一步该做什么，调用工具执行，拿到结果，再判断，直到任务完成。

它最大的优势不只是「架构简单」，更核心的是「整条任务链路完全在你掌控之内」。

Single-Agent 真正开始力不从心，是在遇到这几类任务的时候：任务太长、信息量太大，context 撑爆；不同步骤需要完全不同的专业能力；任务中有多个独立子任务，理论上可以并行，但单 Agent 只能一个个来。

![](https://cdn.xiaolincoding.com//picgo/01_single_agent_thresholds.png)

#### Multi-Agent 的中心化方案

Multi-Agent 的中心化方案，核心是一个叫 Orchestrator 的特殊角色。它是整个系统里最特殊的那个 Agent，因为它不做任何具体工作，它只负责三件事：读懂用户的大目标、把它拆成一个个子任务；判断每个子任务该交给哪个 Worker Agent 去做；收集每个 Worker 的产出，把它们拼成最终答案。

Orchestrator 其实有几种变体。最基础的是**静态路由**（Static Router）；进阶一点的是**动态规划**（Dynamic Planner），Orchestrator 本身是一个 LLM，它会根据用户输入动态生成任务计划；最复杂的是**自适应编排**（Adaptive Orchestration），Orchestrator 不仅动态规划，还会根据 Worker 的执行结果实时调整后续计划。

![](https://cdn.xiaolincoding.com//picgo/02_orchestrator_variants.png)

Worker Agent 就是「执行者」。每个 Worker 只关注自己那块，它不需要知道整体任务是什么，不需要知道其他 Worker 在做什么，只需要拿到属于自己的那部分指令，做完返回结果，然后退出。

![](https://cdn.xiaolincoding.com//picgo/03_competitor_analysis_orchestrator.png)

#### 去中心化方案：为什么「听起来更灵活」却很少在工程上用

去中心化的思路是没有总调度，多个 Agent 通过共享的消息队列或状态空间自行协商、直接通信。

但实际工程里会遇到什么问题？没有人告诉各 Agent「你们各搜什么范围」，很可能做了重复工作；没有人告诉汇总 Agent「其他人什么时候算完了」；如果某个 Agent 中途出错了，没有中央调度者收到错误通知。

总结下来，去中心化系统里这几类问题会频繁出现：任务分配没有协调、执行顺序没有保证、失败没有感知、没有人来确认「任务整体完成了」。

![](https://cdn.xiaolincoding.com//picgo/04_decentralized_problems.png)

#### 怎么做选型决策？

先问第一个问题：你的任务，Single-Agent 能搞定吗？如果任务流程明确、不太长、不需要多种专业分工，Single-Agent 就够了。

如果任务确实超出了 Single-Agent 的边界，再问第二个问题：你能接受系统行为不可控的风险吗？生产环境里这个问题的答案几乎一定是「不能」，所以就用 Orchestrator 模式。

实际工程里有一个很实用的策略叫**渐进式演进**：先用 Single-Agent 把系统跑起来，当你发现某个环节确实成为瓶颈了，再把那个环节拆出来交给一个专门的 Worker Agent。

![](https://cdn.xiaolincoding.com//picgo/05_progressive_evolution.png)

另外值得关注的一个行业趋势是 **A2A**（Agent-to-Agent）协议，Google 在 2025 年 4 月提出的开放标准。A2A 在 2025 年 6 月被捐给了 Linux 基金会维护，IBM 的 Agent Communication Protocol（ACP）也已并入 A2A。

|     维度     | Single-Agent |         Multi-Agent（中心化）          |    Multi-Agent（去中心化）     |
|------------|--------------|-----------------------------------|--------------------------|
| 架构复杂度      | 低            | 中                                 | 高                        |
| Context 压力 | 全部压在一个 Agent | 各 Agent 独立管理，Orchestrator 只维护高层状态 | 各 Agent 独立管理，但需要额外共享协调状态 |
| 专业能力       | 泛才，什么都做      | 专才分工，各有专责                         | 专才分工，各有专责                |
| 并行能力       | 不支持          | 支持子任务并行                           | 支持并行                     |
| 可控性        | 高            | 高，Orchestrator 统管                 | 低，难以统一调度                 |
| 调试难度       | 容易           | 中，按调度链路追踪                         | 难，行为不可预测                 |
| 工程实用性      | 高            | 高                                 | 低，主要用于学术研究               |
| 适用场景       | 任务清晰、复杂度适中   | 需要分工或并行的复杂任务                      | 学术探索场景                   |

### 🎯 面试总结

第一，选型标准不能只说「任务复杂就用 Multi-Agent」，要说出具体的三类场景：context 要撑爆了、需要不同专业分工、有子任务可以并行。

第二，Multi-Agent 架构方案要主动提中心化和去中心化两种，而且要明确说出工程里几乎都选 Orchestrator 中心化模式。

第三，去中心化「听起来灵活」但要能说清楚它的实际问题：任务分配没协调、执行顺序没保证、失败没有感知。

---

## 问题12：Agent 记忆压缩通常有哪些方法？

👔面试官：你项目里 Agent 对话历史越来越长，context 快撑满了怎么办？有没有做记忆压缩？

🙋‍♂️我：有，我们做了滑动窗口，只保留最近几轮对话，太早的就丢掉。

👔面试官：那如果用户三天前确认的一个关键决策被你这么丢了，Agent 回头又提那个已经被否决的方案，怎么办？

🙋‍♂️我：那就把窗口调大一点，多保留一些对话。

👔面试官：窗口调大治标不治本，你知道滑动窗口的本质缺陷是什么吗？「硬截断」意味着什么？

🙋‍♂️我：嗯......那可以用摘要压缩，让 LLM 把历史总结一下，信息就保留下来了。摘要之后应该就够用了。

👔面试官：摘要是一种方案，但你说「够用了」——有没有考虑过，有些场景里摘要本身也会丢失关键细节？还有没有其他角度的压缩思路？

被问出这个问题说明面试官在考你方案的全貌，记忆压缩其实有四个不同维度的方法。

### 💡 简要回答

记忆压缩常见有四种方法：摘要压缩、滑动窗口、重要性过滤、结构化抽取。

摘要压缩是把长对话总结成简短摘要；滑动窗口是只保留最近 N 轮对话；重要性过滤是打分筛选，只留重要内容；结构化抽取是把关键信息抽成结构化数据存起来。

我在实际项目里最常用的是摘要压缩和滑动窗口，而且经常组合用，滑动窗口丢弃前先做一次摘要，尽量不丢重要信息。

### 📝 详细解析

LLM 每次生成回答，依赖的是「每次调用时传入的完整对话历史」。这个 messages 列表是有硬上限的，GPT-4o 是 128K token，Claude 家族默认是 200K token。超过上限就得截断，默认的截断策略是「从最老的对话开始丢」。

记忆压缩要解决的，就是「空间有限、成本有压力」这两件事。

![](https://cdn.xiaolincoding.com//picgo/fe1a5be1152a95bb41d736709951a375.png)

#### 第一种方法：滑动窗口

滑动窗口是最符合直觉的做法：超出就从最老的开始删，只保留最近 N 轮对话。

![](https://cdn.xiaolincoding.com//picgo/image-20260305211547706.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

好处是实现极其简单，不需要任何额外的 LLM 调用。坏处是「硬截断」，对话内容按时间一刀切。

![](https://cdn.xiaolincoding.com//picgo/01_sliding_window_truncation.png)

#### 第二种方法：摘要压缩

摘要压缩是对滑动窗口「硬截断」的改进。核心思路是：不直接丢弃即将超出窗口的历史，而是先让 LLM 把这段历史总结成一段精华摘要，用摘要替换原始对话，再继续往前。

![](https://cdn.xiaolincoding.com//picgo/image-20260305211636880.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

进阶一点的做法是**层级式摘要**（Hierarchical Summarization）。不是对所有旧历史做一次性摘要，而是分层处理：最近 10 轮保持原文，10 到 50 轮的历史压缩成一份「中期摘要」，50 轮之前的历史进一步压缩成更精炼的「长期摘要」。

![](https://cdn.xiaolincoding.com//picgo/02_hierarchical_summary.png)

**最常见的工程组合：滑动窗口 + 摘要**

![](https://cdn.xiaolincoding.com//picgo/image-20260305211659056.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

在实际工程里，这两种方法通常一起用。滑动窗口负责控制对话历史的总长度上限，摘要压缩负责在历史被丢弃之前做一次提炼。

#### 第三种方法：重要性过滤

重要性过滤换了一个角度，按内容的实际价值来决定去留：给每条对话记录打一个重要性分数，低于阈值的淘汰，高分的保留。

![](https://cdn.xiaolincoding.com//picgo/03_importance_filtering.png)

打分的方式有两种。一种是规则打分：包含「决定」「确认」「需求」等关键词的记录加分。另一种是让 LLM 来打分：逐条判断每条记录的重要程度。

还有一种更激进的思路叫**观察遮蔽**（Observation Masking）。它的做法不是删除低分内容，而是在构造 prompt 时选择性地「隐藏」某些历史条目。

另一个值得了解的概念是**主动压缩**（Proactive Compression）。Agent 在每一步执行完之后，主动判断哪些中间过程可以压缩。比如 Agent 调用了一个搜索工具，返回了 2000 token 的原始搜索结果，Agent 在读完之后立刻把搜索结果压缩成 200 token 的要点摘要。

#### 第四种方法：结构化抽取

结构化抽取的思路完全不同，它先问一个更本质的问题：我们真的需要保留对话文本本身吗？

很多场景里，真正有价值的不是对话文字，而是对话中传递的事实和状态。把这些信息主动提取出来，存成结构化字段，后续注入 prompt 时直接用这些字段，比传一大段对话文本要高效得多。

![](https://cdn.xiaolincoding.com//picgo/04_structured_extraction.png)

#### 四种方法的关系梳理

这四种方法不是互斥的：滑动窗口和摘要压缩解决的是「历史太长，怎么截」的问题；重要性过滤解决的是「内容不等价，怎么挑」的问题；结构化抽取解决的是「对话文本本身是不是最佳载体」的问题。

![](https://cdn.xiaolincoding.com//picgo/05_compression_matrix.png)

**Prompt Caching：在「计算层」的互补手段**

除了上面这些「信息层」的压缩策略，还有一个工程上值得了解的技术叫 Prompt Caching。如果 prompt 的前缀部分在多次请求之间是一样的，就把这部分的计算结果缓存起来，下次请求如果前缀匹配，直接复用缓存，不重新计算。

这和前面的记忆压缩是两个不同层次的优化。记忆压缩在「信息层」工作，决定哪些内容值得被保留在对话历史里；Prompt Caching 在「计算层」工作，对已经决定要带进去的内容减少重复计算的开销。两者是互补关系，不是替代。

![](https://cdn.xiaolincoding.com//picgo/06_prompt_caching_layers.png)

### 🎯 面试总结

回答时要覆盖四种方法，并且能说清楚它们解决的是不同维度的问题：滑动窗口和摘要压缩解决「历史太长怎么截」；重要性过滤解决「内容不等价怎么挑」；结构化抽取解决「对话文本是不是最佳载体」。

另外，Prompt Caching 要和记忆压缩区分清楚，它是「计算层」的优化，和「信息层」的压缩是互补关系，不是替代关系。

---

## 问题13：在工程实践中，为什么有时候选择「手搓」Agent，而不是直接用成熟框架？

👔面试官：你平时做 Agent 开发用什么框架？

🙋‍♂️我：主要用 LangChain，功能很全，上手快，工具注册、ReAct loop、记忆管理都帮你封装好了，开发效率高很多。

👔面试官：那如果线上出了 bug，你怎么排查？

🙋‍♂️我：看报错日志，根据 stack trace 往上追，一层层找原因。

👔面试官：LangChain 的 stack trace 动不动四五十层，你真的能靠这个快速定位吗？你有没有想过，框架的抽象层本身就是排查问题的障碍？

好，被问到这里，只知道「框架好用」是不够的。

### 💡 简要回答

我的感受是框架用起来快，但有几个实际痛点。

第一是抽象层太多，调试的时候不知道哪步出了问题，得一层层往下扒；第二是版本升级经常有破坏性变更，线上稳定性难保证；第三是框架的通用设计往往和具体业务需求有偏差，定制起来反而更费劲。

手搓的代码完全在自己掌控之内，可观测性好、出问题好排查，也更方便做性能优化。所以我现在的策略是核心逻辑手写，只在边缘功能上用框架的工具。

### 📝 详细解析

#### 痛点在什么时候开始出现？

框架的问题不是一开始就暴露的，而是随着项目推进，在不同阶段逐渐浮出来的。

探索期，框架真的很爽。第一个奇怪的 bug 出现之后，感觉就变了。代码只有五十行，但报错的 stack trace 有四十层。

![](https://cdn.xiaolincoding.com//picgo/01_debug_transparency.png)

版本升级踩坑，是另一个阶段的痛苦。LangChain 早期版本升级频率很高，breaking change 是常见的。

性能优化时发现了隐性开销。框架内部在每次调用时做了你根本不需要的事：序列化中间结果、触发一堆 callback、记录详细日志......

#### 手搓的本质优势：完全掌控

首先是链路透明、可观测性好。其次是精确裁剪、没有多余开销。第三是稳定可控、不受框架升级影响。

Anthropic 在官方的 Agent 构建指南里也明确提了类似的建议：不要一上来就用框架，先用最少的抽象把核心逻辑跑通。

![](https://cdn.xiaolincoding.com//picgo/02_rent_vs_build.png)

#### 同一个需求，框架写 vs 手搓写，差别在哪？

用 LangChain 框架来写：

```python
from langchain.agents import AgentExecutor, create_openai_tools_agent
agent = create_openai_tools_agent(llm, tools, prompt)
executor = AgentExecutor(agent=agent, tools=tools)
result = executor.invoke({"input": "帮我查一下今天的天气"})
```

手搓同样的功能：

```python
messages = [{"role": "system", "content": system_prompt}]
messages.append({"role": "user", "content": user_input})

for i in range(max_turns):
    response = client.chat.completions.create(
        model="gpt-4", messages=messages, tools=tool_schemas
    )
    msg = response.choices[0].message
    messages.append(msg)

    if not msg.tool_calls:
        break

    for tc in msg.tool_calls:
        result = execute_tool(tc.function.name, tc.function.arguments)
        messages.append({
            "role": "tool",
            "tool_call_id": tc.id,
            "content": result
        })
        logger.info(f"工具 {tc.function.name} 返回: {result}")
```

手搓版本里，消息列表怎么拼的、工具怎么选的、循环什么时候退出，每一个细节都摆在明面上。

#### 什么时候用框架，什么时候手搓？

![](https://cdn.xiaolincoding.com//picgo/03_project_stage_timeline.png)

框架适合的时机：POC 阶段快速验证 idea；团队刚接触 Agent 开发；周边工具依赖框架的生态。

手搓的时机：准备上生产，稳定性成为核心关切；流量开始上来，性能和成本变得敏感；业务逻辑高度定制。

**折中方案：核心手写，周边借用**

工具调用的循环、对话历史的管理、错误处理和重试、任务状态的维护，这些是 Agent 的「心脏」，必须手写。而 LangSmith 的 tracing、LlamaIndex 的文档解析、某个向量库的 Python 客户端，这些是「工具性」的周边功能，用外部工具节省时间完全值得。

![](https://cdn.xiaolincoding.com//picgo/04_core_vs_peripheral.png)

### 🎯 面试总结

第一，框架的价值是真实的，POC 阶段省时省力，不要一开口就否定框架。

第二，框架的痛点要说具体：抽象层太多导致排查困难、版本升级带来 breaking change、通用性设计产生隐性性能开销。

第三，手搓的核心价值是「完全掌控」。

第四，最容易被忽略的是折中方案：核心逻辑手写，周边工具性功能借用框架。

最后要记住一句：框架不是问题，「不理解就依赖」才是。

---

## 问题14：如何赋予 LLM 规划能力？

👔面试官：说说你是怎么给 LLM 加规划能力的？

🙋‍♂️我：规划能力主要靠 CoT，就是在 prompt 里加一句「请一步步思考」，让 LLM 把推理过程写出来，就有规划能力了。

👔面试官：CoT 就是规划能力的全部？你有没有想过 CoT 最大的问题是什么？

🙋‍♂️我：CoT 的问题......就是有时候推理链比较长，token 消耗多一些？

👔面试官：不对。CoT 是单条推理链，一旦一开始方向走错，后面全错，没有任何纠偏机制。ToT 就是为了解决这个问题才出来的，你知道 ToT 怎么做的吗？

被问到这里，才发现「加个 CoT 就是规划能力」这个认知太浅了。三种机制是层层递进的。

### 💡 简要回答

给 LLM 加规划能力主要靠这几种思路。

* CoT 是让 LLM 把推理步骤写出来，线性地一步步推导到答案；
* ToT 是让它同时探索多条推理路径，选最优的继续深入；
* GoT 是图结构推理，推理节点可以复用和合并，适合更复杂的任务。

工程上我用 CoT 最多，因为实现成本最低；ToT 效果更好但调用次数多，成本大概是 3 到 5 倍；GoT 目前还比较学术。

### 📝 详细解析

要理解为什么需要规划能力，先看 LLM 在没有任何规划机制时是怎么运作的。

普通的问答模式下，LLM 接到一个问题，就直接「一口气」生成答案，中间没有任何推理过程。

![](https://cdn.xiaolincoding.com//picgo/69bb980648f10fa386dc24636e792ea5.png)

背后的原因是 Transformer 的 next-token 预测机制，推理链越长、隐式的跳步越多，误差就越容易在中间某一步悄悄累积。

「规划能力」要解决的就是这个问题：把 LLM 隐式的推理过程显式化。

![](https://cdn.xiaolincoding.com//picgo/01_no_plan_vs_plan.png)

#### CoT：最简单的激活方式，加一句话就够了

CoT 的全称是 Chain of Thought（思维链），核心思路极其简单：在 prompt 里加一句「请一步步思考」，LLM 就会把推理过程逐步写出来。

本质是因为 LLM 的输出是顺序生成的，当它先输出推理步骤，这些推理内容会进入上下文，影响下一个 token 的生成。

CoT 有两种触发方式。

![](https://cdn.xiaolincoding.com//picgo/image-20260305213047557.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

* 第一种叫 Zero-shot CoT，就是直接在 prompt 末尾加「让我们一步步思考」；
* 第二种叫 Few-shot CoT，给几个带有完整推理过程的例子，让 LLM 模仿这种推理格式来回答新问题。

CoT 的局限很明显：它只有「一条推理路径」。如果一开始走错了方向，整条链就歪了。

![](https://cdn.xiaolincoding.com//picgo/image-20260305213105782.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

#### ToT：从「一条链」到「一棵树」，解决走错方向的问题

ToT 的全称是 Tree of Thoughts（思维树），核心改变是把「生成一条推理链」变成「同时探索多条推理路径，边探索边剪枝，最终选出最优路径」。

![](https://cdn.xiaolincoding.com//picgo/image-20260305213615714.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

ToT 的执行流程可以分三步来理解：生成多个候选思路 -> 评估每个思路的可行性 -> 选优继续深入、剪掉差的。

![](https://cdn.xiaolincoding.com//picgo/image-20260305213559790.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_south,size_35,type_aHloZWk,color_304ffe)

代价也很明显：典型设置（每层 3 条路径、搜 2-3 层）下成本通常是 CoT 的 3-5 倍；极端场景可能到 10 倍以上。

![](https://cdn.xiaolincoding.com//picgo/02_tot_tree_search.png)

#### GoT：从「树」到「图」，解决推理结果不能复用的问题

GoT 的全称是 Graph of Thoughts（思维图），是在 ToT 基础上再进一步的进化。

![](https://cdn.xiaolincoding.com//picgo/image-20260305213142940.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

ToT 虽然引入了多路径探索，但它是树形结构，不同分支之间完全独立。GoT 把推理结构换成了图，允许不同路径的中间结果合并、复用。

举个具体例子：如果任务是「分别研究竞品 A 和竞品 B，然后做综合对比分析」。ToT 里研究 A 和研究 B 是两条独立的路径；GoT 的图结构允许把两条路径的输出汇聚到「综合对比分析节点」。

GoT 目前主要还是学术研究场景，生产环境里极少见到真正用起来的。

![](https://cdn.xiaolincoding.com//picgo/03_tot_vs_got.png)

#### 三者的演进关系

CoT 解决了「要不要把推理显式化」的问题。ToT 解决了「走错方向怎么办」的问题。GoT 解决了「不同推理路径的中间结论能不能复用」的问题。

![](https://cdn.xiaolincoding.com//picgo/04_cot_tot_got_evolution.png)

#### 工程里真正常用的规划模式：Plan-and-Execute

这个模式的思路很直白：面对一个复杂任务，先让 LLM 制定一份完整的执行计划，把任务拆成若干步骤，然后一步一步执行，每完成一步就检查一下进度，必要时调整后续计划。

具体执行流程分三步：

* 第一步，Planner（规划器）接收用户任务，生成一份步骤清单；
* 第二步，Executor（执行器）按照清单一步步执行；
* 第三步，Re-planner（重新规划器）回顾当前进展，判断原来的计划还适不适用。

这个模式和 ReAct 是什么关系？ReAct 是一种让 LLM 在每一步都先「思考」再「行动」再「观察」的循环模式，它的特点是每步都是即时决策，没有提前规划。Plan-and-Execute 则是在 ReAct 的基础上加了一层全局规划。两者不是替代关系，而是经常搭配使用的。

![](https://cdn.xiaolincoding.com//picgo/05_plan_and_execute_loop.png)

### 🎯 面试总结

首先要说清楚为什么需要规划能力。然后要说三种机制的演进逻辑：CoT -> ToT -> GoT。最容易被忽略的考点是工程取舍：CoT 几乎零成本；ToT 典型调用次数是 CoT 的 3-5 倍；GoT 目前学术阶段。

---

## 问题15：讲讲 Agent 的反思机制？为什么要用反思？具体怎么实现？

👔面试官：Agent 的反思机制你了解吗？怎么实现的？

🙋‍♂️我：了解，就是让 LLM 对输出不满意的时候再重新生成一次，多试几次输出质量就会提升。

👔面试官：「再生成一次」和「反思后改进」是两回事。反思不是随机重试，你知道两者的本质区别在哪吗？

🙋‍♂️我：反思......就是让 LLM 看看自己输出有没有问题，然后再改一下？

👔面试官：说对了一半。关键是「评估」这一步要怎么设计。你直接让 LLM「看看有没有问题」，它往往会说「输出看起来不错」，什么都发现不了。评估 prompt 里有一个最重要的设计，你知道是什么吗？

被问到这里才意识到，反思机制是一个有完整设计的闭环，每个细节都有原因。

### 💡 简要回答

反思机制我的理解是：让 Agent 在完成一个步骤或整个任务后，自我评估输出质量，判断有没有问题，不达标就重试或调整策略。

用反思的原因是 LLM 第一次输出不一定是最优的，加一轮自我检查能显著提升质量，相当于人写完东西自己再看一遍。

代价是多至少一次 LLM 调用，token 消耗和延迟都会增加，所以我在工程里通常只在质量要求高的关键节点启用反思，不是每步都做。

### 📝 详细解析

![](https://cdn.xiaolincoding.com//picgo/501991ea98f75337d9d59ca382597a12.png)

LLM 第一次输出常见的毛病有这几类：逻辑跳跃、遗漏细节、事实错误、表达含糊。这些问题，如果给 LLM 一个「回头检查」的机会，它自己是有能力发现并修正的。

#### 核心循环：生成 -> 评估 -> 改进

反思机制的核心思路来自 Self-Refine 论文（Madaan 等人 2023 年提出），整个流程就是「生成 -> 评估 -> 改进」的循环。

![](https://cdn.xiaolincoding.com//picgo/image-20260305214059234.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

![](https://cdn.xiaolincoding.com//picgo/01_reflection_loop.png)

这个循环靠两个 prompt 来驱动。第一个负责评估：

```
任务：{task}
当前输出：{current_output}
请评估以上输出：
1. 有没有事实错误或逻辑问题？
2. 有没有遗漏重要内容？
3. 表达是否清晰准确？

如果输出已经足够好，回复「PASS」；
否则指出具体问题并给出改进建议。
```

这个评估 prompt 的设计有几个值得注意的地方。首先，它给出了明确的检查维度。其次，「PASS」机制是必须有的，这是给 LLM 一个「足够好就停」的出口。

如果评估结果不是 PASS，就把评估意见喂进第二个改进 prompt：

```
原始任务：{task}
当前输出：{current_output}
评估意见：{reflection}

请根据评估意见改进输出：
```

改进 prompt 同时传入了原始任务、原始输出、评估意见这三样东西，缺任何一个都会让改进变得盲目。

![](https://cdn.xiaolincoding.com//picgo/02_improvement_prompt_inputs.png)

#### 两个粒度：步骤级 vs 任务级

**步骤级反思**是在每个工具调用或推理步骤完成后立即检查。它的好处是错误早发现早纠正。代价是每一步都多一次 LLM 调用。

**任务级反思**是整个任务执行完之后做一次整体评估。好处是开销更小；而且从整体视角审视，能发现步骤级看不到的问题。

![](https://cdn.xiaolincoding.com//picgo/03_step_vs_task_reflection.png)

#### 多 Agent 互评：为什么「他人审视」比「自我检查」更好

专门设置一个独立的 Critic Agent，让它来审查执行 Agent 的输出。

单 Agent 自我反思时，评估者和生成者是同一个模型，它在生成输出时形成的一套「内部逻辑」，做评估时也会沿用这套逻辑，对自己输出的错误不够敏感，容易陷入「自洽」。

![](https://cdn.xiaolincoding.com//picgo/04_critic_agent_review.png)

#### 进阶：Reflexion 和 LATS

**Reflexion**（Shinn 等人 2023 年提出）的核心思路是：不仅让 Agent 反思当前输出的质量，还要让它把「失败经验」存下来，下次遇到类似任务时直接参考，避免重蹈覆辙。

**LATS**（Language Agent Tree Search，Zhou 等人 2024 年提出），它把反思和树搜索结合了起来。通过 MCTS 同时探索多条路径，每条路径执行之后都会做评估和反思。

还有一种思路叫**辩论式反思**，让多个 Agent 互相辩论。

#### 工程权衡：怎么用才合理？

什么场景值得开反思？输出质量要求高、错误代价大的关键节点。

什么场景不值得开？简单直接的任务，比如格式转换、简单问答。

最重要的是防死循环，必须设最大轮次，通常设 2-3 轮。

![](https://cdn.xiaolincoding.com//picgo/05_reflection_guardrails.png)

### 🎯 面试总结

首先要说清楚反思的闭环结构：两个 prompt 各司其职。

其次，评估 prompt 的两个关键设计要能说出来：给出具体检查维度，以及设置「PASS」出口。

第三，步骤级和任务级反思的区别。

第四，最容易被遗漏的工程要点是防死循环：必须硬性设置最大轮次（2-3轮）。

---

## 问题16：如何设计多 Agent 的协作与动态切换机制？

👔面试官：多 Agent 系统里，各个 Agent 之间怎么协作？

🙋‍♂️我：一个 Agent 做完之后把结果传给下一个 Agent，就像流水线一样，一步步往下走。

👔面试官：你说的是流水线，但「传结果」具体怎么传？消息传递和共享状态是两种不同的方案，适用场景也不一样，你区分得开吗？

🙋‍♂️我：消息传递就是 Agent 之间直接发消息，共享状态就是大家都能读写同一个变量......应该都差不多吧？

👔面试官：差很远。消息传递的核心优势是解耦，发送方不需要知道谁在接收；共享状态的优势是直接，前一步写进去后一步直接读。这两种选哪个，取决于 Agent 之间的依赖关系强不强。那动态切换呢，你是怎么做的？

🙋‍♂️我：动态切换就是让 LLM 判断下一步该调用哪个 Agent，每次根据当前情况动态决策，这样最灵活。

👔面试官：全靠 LLM 动态决策的问题是什么？每次路由都要多一次 LLM 调用，而且 LLM 偶尔会路由错，系统行为的可预测性就没了。你有没有想过，静态路由和动态路由应该怎么配合用？

被问到这里，才意识到协作和切换都是有设计取舍的。

### 💡 简要回答

协作靠两件事：消息传递和共享状态。消息传递是 Agent 完成自己的工作后把结果发出去，下一个 Agent 取用；共享状态是所有 Agent 共同读写一个状态对象，记录任务进展和中间结果。

动态切换靠 Orchestrator 来做，有两种方式：一种是静态路由，提前写好规则「任务类型 A 就找 Agent X」；另一种是让 LLM 动态决策，根据当前情况实时判断该把任务交给谁。

我的实践是两种混用，主流程用静态路由保证稳定，边缘情况才交给 LLM 动态判断。

### 📝 详细解析

工程实践中常见的协作模式大致分为三类：

* 第一类是流水线模式，Agent 之间按固定顺序依次执行；
* 第二类是层级模式，有一个 Orchestrator 负责分配任务、收集结果；
* 第三类是协商模式，多个 Agent 之间通过互相沟通、辩论来达成一致。

![](https://cdn.xiaolincoding.com//picgo/01_collaboration_modes.png)

#### 先说协作：Agent 之间怎么传递信息

* 第一种方式，像发邮件。这就是「消息传递」的思路，Agent 完成自己的工作后把结果发送到一个消息队列，下游的 Agent 订阅自己感兴趣的消息。最大的优点是解耦。

* 第二种方式，像共享白板。这就是「共享状态」的思路，所有 Agent 都读写同一个状态对象。LangGraph 就是用这个思路来设计的。

如果各 Agent 之间的依赖关系比较强，用共享状态更直接。如果你希望 Agent 之间尽量解耦，用消息传递更合适。

![](https://cdn.xiaolincoding.com//picgo/02_message_vs_shared_state.png)

#### 状态管理：多 Agent 共享状态的设计要点

首先是状态结构要分层，通常会把状态分成「全局状态」和「局部状态」两层。

其次是写入规则要明确。最简单也最可靠的做法是「只追加不覆盖」。

最后是错误状态的处理。如果某个 Agent 执行失败了，它的错误信息也应该写入状态。

![](https://cdn.xiaolincoding.com//picgo/03_shared_state_layers.png)

#### 再说切换：Orchestrator 怎么决定叫谁

路由有两种策略。

**静态路由**，就是提前把规则写死。效率高、可预测、好调试。但它覆盖不了你没预料到的情况。

**动态路由**，则是把「下一步找谁」的决策权交给 LLM 来做。优点是灵活，缺点是每次路由都要多一次 LLM 调用，而且 LLM 偶尔也会路由错。

![](https://cdn.xiaolincoding.com//picgo/04_static_vs_dynamic_routing.png)

![](https://cdn.xiaolincoding.com//picgo/image-20260305214923533.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

动态路由的代码示例：

```python
def dynamic_route(task_context: str, available_agents: list[str]) -> str:
    """让 LLM 根据当前上下文决定下一步调用哪个 Agent"""
    prompt = f"""当前任务状态：
{task_context}

可用的 Agent：
{chr(10).join(f'- {agent}' for agent in available_agents)}

请根据当前进展，判断下一步应该交给哪个 Agent 来执行。
只返回 Agent 名称，不需要解释。"""

    response = client.chat.completions.create(
        model="gpt-4",
        messages=[{"role": "user", "content": prompt}]
    )
    selected = response.choices[0].message.content.strip()
    return selected
```

#### Handoff 模式：Agent 之间的「接力棒」

除了由 Orchestrator 集中做路由决策，还有一种更去中心化的切换方式叫 Handoff（交接），这个模式在 OpenAI 的 Swarm 框架里被用来演示和推广。

Handoff 的思路是：让当前正在执行的 Agent 自己决定「我做完了，接下来应该把任务交给谁」。

好处是每个 Agent 对自己的任务边界最清楚。缺点是没有全局视角，可能形成死循环。所以用 Handoff 模式时，必须设计好每个 Agent 的职责边界，并且加上防循环的机制。

![](https://cdn.xiaolincoding.com//picgo/05_handoff_relay.png)

#### 工程上怎么用

实践中最稳健的做法是两种路由组合用：主流程用静态路由，把确定性的节点切换都写成规则；只在遇到没有匹配规则的边缘情况时，才交给 LLM 动态决策。

通信方式的选择同理：如果流程是一条相对清晰的流水线，就用共享状态；如果需要让多个 Agent 独立并行、互相不感知对方的存在，就用消息传递。

![](https://cdn.xiaolincoding.com//picgo/06_hybrid_routing.png)

### 🎯 面试总结

首先，协作机制要说出两种通信方式的本质区别：消息传递的核心是解耦；共享状态的核心是直接，LangGraph 就是这个思路。

其次，动态切换要说出静态路由和动态路由各自的优缺点。

最容易被忽略的点：说出「主流程静态路由保底，边缘情况才交给 LLM 动态决策」的混合策略。这个答法面试官一听就知道你真的做过多 Agent 系统。
