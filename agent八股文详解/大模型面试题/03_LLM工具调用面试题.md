# LLM 工具调用面试题

---

## 问题1：什么是 Function Calling？原理是什么？

### 💡 简要回答

Function Calling 是这样一套机制：开发者用 JSON schema 把工具描述好传给模型，模型判断需要调工具的时候不输出自然语言，而是直接输出一段结构化的 tool_calls JSON，告诉你「我要调哪个函数、参数是什么」，你的代码拿到这段 JSON 去真正执行，把结果塞回对话，模型再生成最终答案。

整个流程本质上是两轮对话：第一轮模型说「我需要调这个工具」，你去执行，第二轮模型拿到执行结果说「答案是这个」。

最核心的设计是，模型全程只做决策，执行的事情一律由宿主代码完成，职责分得很清楚。

### 📝 详细解析

#### 背景，Function Calling 解决了什么问题

LLM 在没有 Function Calling 之前，想让模型帮你调工具，完全靠解析自然语言。模型输出「我需要查一下北京的天气」，你再写 if/else 判断它「说」的是要查天气，然后手动去调 API。这个做法极其脆弱，模型换个说法，你的 if/else 就失配了，也根本没办法标准化。

![](https://cdn.xiaolincoding.com//picgo/1772110696965-86ba8349-1109-4dca-a481-35ba1b5b8367.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe,t_50)

Function Calling 的出现把这件事固定下来了：模型不再「说」要调工具，而是直接输出一段结构化的 JSON，开发者按格式解析就行，准确率大幅提升，也有了统一标准可以对接。这套机制由 OpenAI 在 2023 年推出，现在 Claude、Gemini、Qwen 等主流模型都支持。

![](https://cdn.xiaolincoding.com//picgo/1776863691463-8f5c0591-aaf4-4d98-aa57-e2a12fe95fc9.png)

#### 三个角色，把 Function Calling 理解成一场任务委托

理解 Function Calling 的关键是搞清楚谁做什么。可以把这套流程理解成一场「任务委托」：

![](https://cdn.xiaolincoding.com//picgo/1776863825095-2d5698cc-940c-4f77-b2ea-70dd270b8707.png)

开发者是 HR，负责给每个工具写「职位说明书」，就是 JSON schema，告诉模型「我们有哪些工具、每个工具能做什么、需要哪些参数」。模型是经理，读完说明书之后决定「这个任务需要调哪个工具、参数填什么」，然后把指令下达出来。你写的代码是员工，真正去跑函数、访问网络、查数据库，把结果汇报回来。

关键点：模型全程只是在「下指令」，它不亲自执行任何代码，也没有直接访问网络的权限。执行的事一律由宿主程序代码完成，这个分工要想清楚。

#### 工具定义，schema 的每个字段都有含义

工具 schema 就是一份结构化的「工具说明书」，用 JSON 格式写，告诉模型这个工具叫什么、能做什么、需要哪些参数。

```python
tools = [
  {
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "查询指定城市的实时天气，包含气温、天气状况、风向风速，仅支持中国大陆城市",
      "parameters": {
        "type": "object",
        "properties": {
          "city": {
            "type": "string",
            "description": "城市名称，如「北京」「上海」，不要带省份前缀"
          },
          "unit": {
            "type": "string",
            "enum": ["celsius", "fahrenheit"],
            "description": "温度单位，默认用摄氏度"
          }
        },
        "required": ["city"]
      }
    }
  }
]
```

其中最关键的字段是 `description`。如果 description 写得含糊，模型会「瞎猜」，比如你只写「获取天气」，模型可能拿到一个带英文名的城市也照样调。模型在决定「要不要调这个工具、参数怎么填」的时候，能依赖的唯一依据就是这段描述。写得越清晰，模型的选择越准确。

![](https://cdn.xiaolincoding.com//picgo/1776864343500-856d21bc-0902-4c7e-b247-e2ff72f84527.png)

#### 完整的调用流程，两轮对话加中间执行

Function Calling 的运行时本质上是「两轮对话 + 中间执行」的闭环。

![](https://cdn.xiaolincoding.com//picgo/image-20260306140017160.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe)

第一轮，你把工具列表和用户的问题一起传给模型。模型读完之后，如果判断需要调工具，就不直接输出最终答案，而是输出一个 `finish_reason` 为 `"tool_calls"` 的响应，里面包含要调用的工具名和参数。

拿到这个信号之后，中间环节就交给你的代码了。你的代码解析 tool_calls 拿到函数名和参数，找到对应的函数跑一下，拿到执行结果。

然后进入第二轮，把工具执行结果以 `role: "tool"` 的消息塞回对话历史，再次调用模型。这次模型有了工具结果，有了充分信息，才给出最终的自然语言答案。

![](https://cdn.xiaolincoding.com//picgo/1776863915097-1b4550ed-01f5-4788-ad20-29b3506c06ff.png)

```python
import openai, json

client = openai.OpenAI()
messages = [{"role": "user", "content": "北京今天天气怎么样？"}]

# 第一轮：把工具定义和问题一起传给模型
response = client.chat.completions.create(
  model="gpt-4o",
  messages=messages,
  tools=tools,
  tool_choice="auto"
)
msg = response.choices[0].message

if msg.finish_reason == "tool_calls":
  tool_call = msg.tool_calls[0]
  func_args = json.loads(tool_call.function.arguments)

  # 中间执行：你的代码真正去跑函数
  result = f"{func_args['city']}今天晴，15°C，东北风 3 级"

  # 第二轮：把工具结果塞回对话，再问一次模型
  messages.append(msg)
  messages.append({
    "role": "tool",
    "tool_call_id": tool_call.id,
    "content": result
  })
  final = client.chat.completions.create(model="gpt-4o", messages=messages, tools=tools)
  print(final.choices[0].message.content)
```

#### 并行工具调用

当用户的问题需要多个工具才能回答时，模型可以在一次响应里同时输出多个 `tool_calls`。比如用户问「帮我查北京和上海的天气」，模型会一次返回两个调用请求。

![](https://cdn.xiaolincoding.com//picgo/1776864007592-4ffc3703-9e60-4f6a-ac4f-baaf04138b1b.png)

有了并行调用，你的代码可以同时执行这两个工具，拿到所有结果后一次性塞回对话，再调一次模型就拿到最终答案。整个过程从「两轮对话」压缩成了「一轮对话 + 并行执行」。

不过要注意，并行调用的前提是工具之间没有依赖关系。如果是「先查用户的订单号，再用订单号去查物流」，第二个调用依赖第一个的结果，只能串行。

![](https://cdn.xiaolincoding.com//picgo/1776864094005-778c8685-e95d-4fa8-ae9e-770a5f505a24.png)

### 🎯 面试总结

面试回答这道题，有几个点必须说到：工具定义用 JSON schema 描述，description 字段是模型判断是否调用的核心依据；运行时是「两轮对话 + 中间执行」的闭环流程；模型通过 finish_reason 为 tool_calls 来明确告知需要工具帮助；以及模型支持一次返回多个 tool_calls 实现并行调用。把这几个点讲清楚，再强调「模型决策、代码执行」的分工原则，这道题就稳了。

---

## 问题2：LLM 是如何学会调用外部工具的？

### 💡 简要回答

训练层面靠两个阶段：

- **SFT（监督微调）**：给模型喂大量「工具调用示范对话」，让它通过模仿学会「看到工具描述 -> 判断要不要调 -> 输出结构化 JSON 请求」这整套流程；
- **RLHF（基于人类反馈的强化学习）**：收集人类对「哪种回答更好」的判断，训练一个打分器，再用这个分数反复调整模型，让它学会什么时候不应该调工具。

运行层面，每次请求时，你的应用代码把工具描述传给模型，模型如果判断需要工具，就输出结构化的 `tool_calls` JSON；你的代码拿到这段 JSON 去真正执行，把结果塞回对话，模型再给出最终答案。

一句话总结：SFT 教会怎么调，RLHF 教会什么时候调。

### 📝 详细解析

#### 原始 LLM 的世界，为什么不会调工具

大语言模型在预训练阶段学的是给定前面的文字预测下一个 token，整个训练过程完全是在文本空间里进行的，模型从未见过「工具调用」这件事。所以哪怕你在 prompt 里写「你可以调用天气 API」，没经过专门训练的模型也只会生成一段自然语言描述，而不是输出一段可以被程序解析的 JSON 调用请求。

![](https://cdn.xiaolincoding.com//picgo/image-20260306140403734.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_35,type_aHloZWk,color_304ffe,t_50)

工具调用能力不是天生的，是后天「教」出来的。怎么教？靠两个阶段：**SFT 教会怎么调，RLHF 教会什么时候调**。

![](https://cdn.xiaolincoding.com//picgo/1776864293645-da1f7644-7230-4f14-8dfd-4923ed531d42.png)

#### 第一阶段：SFT，让模型「见过」工具调用

SFT 是 Supervised Fine-Tuning（监督微调）的缩写，核心思路：给模型看大量正确的示例，让它学会模仿。

一条完整的训练样本包含：System 消息（工具说明书）、User 消息（用户提问）、Assistant 的调用请求（结构化 JSON）、Tool 消息（工具返回数据）、Assistant 的最终回答。

![](https://cdn.xiaolincoding.com//picgo/1776864555021-ea4eef67-832c-43d2-955c-f11f72d083c4.png)

模型在几十万甚至上百万条这样的样本上反复训练，就学会了整套流程：识别工具定义、判断要不要调、输出格式规范的 JSON 请求。

#### SFT 的短板，会了，但不知道「该不该调」

SFT 让模型学会了「调工具」这个动作，但它不知道什么时候该调、什么时候不该调。因为 SFT 的训练样本里，「该调的场景」占了绝大多数，模型会过拟合这种「积极调用」的倾向。

![](https://cdn.xiaolincoding.com//picgo/1776864574204-50622124-0e9f-4879-966d-76f105346a2f.png)

#### 第二阶段：RLHF，用反馈建立边界感

RLHF 是 Reinforcement Learning from Human Feedback 的缩写。流程分四步：

![](https://cdn.xiaolincoding.com//picgo/image-20260309184728652.png?image_process=watermark,text_eGlhb2xpbm5vdGUuY29tQOWwj-ael-mdouivleeslOiusA,g_center,size_20,type_aHloZWk,color_304ffe)

1. 生成多样回答：对同一个问题，让模型生成几种不同的处理方式
2. 人类打分：标注员评判哪种回答更合理
3. 训练奖励模型：用打分数据训练一个专门负责打分的小模型
4. 用强化学习优化主模型：拿奖励模型的打分不断调整主模型参数

### 🎯 面试总结

面试回答这道题，核心是把两个训练阶段讲清楚：SFT 教会怎么调（通过大量示例学会输出结构化 JSON），RLHF 教会什么时候调（通过人类偏好反馈建立边界感）。同时要强调运行时的「模型决策、代码执行」分工原则。

---

## 问题3：大模型的 Function Call 能力是怎么训练出来的？

### 💡 简要回答

Function Call 的能力主要靠两个训练阶段：

第一个是 SFT，给模型喂大量「包含工具调用的完整对话样本」，让模型通过模仿学会整套流程。但光有 SFT 不够，模型可能学得过激，遇到什么问题都想调工具。

第二个阶段是 RLHF，通过人类标注「哪种回答更好」来训练奖励模型，再用强化学习调整主模型，让它学会「能直接回答的就直接回答，需要实时数据才去调工具」这个边界感。

### 📝 详细解析

#### 预训练阶段不涉及工具调用

预训练做的是语言建模：读大量文本，学「下一个词大概率是什么」。训练语料里有代码、有对话，但几乎没有「收到工具定义 → 输出结构化 JSON 调用 → 拼接结果 → 再答」这种格式化的工具交互数据。所以预训练结束后，模型遇到工具调用场景只会「聊」不会「做」。

#### SFT 阶段的训练数据格式

SFT 阶段使用的训练数据必须精确模拟真实 Function Calling 的完整流程。一条样本的结构是：

```
System: 你是一个助手，你可以使用以下工具 [工具schema列表]
User: 北京今天天气怎么样？
Assistant: {"tool_calls": [{"name": "get_weather", "arguments": {"city": "北京"}}]}
Tool: {"temperature": 15, "weather": "晴", "wind": "东北风3级"}
Assistant: 北京今天天气晴朗，气温15°C，东北风3级。
```

训练数据覆盖的场景需要尽可能全面：单工具调用、多工具并行调用、串行调用（第二次调用依赖第一次结果）、不需要调用工具直接回答的场景。

数据来源通常有两种：人工标注（质量高但成本高）和用更强模型自动生成再人工抽检（成本低、量大）。

#### RLHF 阶段解决「判断力」问题

SFT 之后模型会「过度热情」地调用工具。RLHF 通过以下步骤建立边界感：

1. 给模型同一个问题，让它产出多个候选答案（有的调了工具，有的没调）
2. 人类标注员判断哪个更好（比如「1+1=？」不调工具直接回答更好）
3. 训练奖励模型学习人类偏好
4. 用 PPO 等算法让主模型向高奖励方向优化

#### 训练数据质量的影响

训练数据质量直接决定 Function Call 能力的上限：

- description 写得不好 → 模型学会的判断依据就不准
- 参数格式不规范 → 模型输出的 JSON 也会有格式问题
- 场景覆盖不全 → 遇到训练没见过的工具组合就容易出错

### 🎯 面试总结

回答这道题要突出训练的分层设计：预训练不涉及工具调用能力；SFT 用结构化样本教会模型「怎么调」（输出规范 JSON）；RLHF 用人类反馈教会「什么时候调」（建立边界感）。数据质量和场景覆盖度是决定效果的关键因素。

---

## 问题4：Function Calling 和 Prompt 工程调用工具有什么区别？

### 💡 简要回答

Prompt 工程调用工具，本质是你在 system prompt 里写一段话说「如果用户问天气，请输出以下格式」，然后解析模型的自然语言输出。这是一种「软约束」，全靠模型配合，格式可能飘、字段可能丢、解析可能失败。

Function Calling 是模型原生支持的能力，工具定义在 API 的 tools 参数里传入，模型经过专门训练知道什么时候该输出 tool_calls 格式的 JSON。输出有明确的 finish_reason 信号，格式标准化、稳定可解析。

核心区别在三个层面：

- **格式保证**：Prompt 方式靠模型自觉，容易出格式错误；Function Calling 有结构化输出约束，格式几乎不会错
- **决策信号**：Prompt 方式你要自己判断模型是在聊天还是在调工具；Function Calling 通过 finish_reason="tool_calls" 给出明确信号
- **工具发现**：Prompt 方式工具描述是 prompt 的一部分，长了会挤占上下文窗口；Function Calling 的 tools 参数独立传入，不占 prompt token

### 📝 详细解析

#### Prompt 工程方式的工作原理

在 Function Calling 出现之前，开发者想让模型调工具，唯一的办法就是在 prompt 里写规则：

```
你是一个助手。当用户问天气相关问题时，请输出以下JSON格式：
{"action": "get_weather", "city": "城市名"}
不要输出其他内容。
```

然后你的代码拿到模型输出后，用正则或者 JSON.parse 去解析。问题在于：

1. 模型可能不严格遵守格式，比如前面加一句「好的，我来查一下」再跟 JSON
2. 模型可能漏掉字段或者类型不对
3. 模型在不该调的时候也可能输出这个格式
4. prompt 越长，遵循格式的概率越低

#### Function Calling 方式的工作原理

Function Calling 通过 API 的 `tools` 参数传入工具定义，模型经过专门训练，知道：

1. 什么情况下需要调工具（训练阶段通过 RLHF 学到的边界感）
2. 调工具时必须输出标准的 `tool_calls` 格式
3. 输出 tool_calls 时设置 `finish_reason="tool_calls"` 作为明确信号

#### 实际对比

| 维度 | Prompt 工程 | Function Calling |
|------|------------|-----------------|
| 格式稳定性 | 低，经常出错 | 高，几乎不出错 |
| 决策信号 | 无，需自行判断 | 有，finish_reason |
| 多工具管理 | 困难，prompt 越长越不稳定 | 原生支持，tools 列表 |
| 并行调用 | 几乎无法实现 | 原生支持多个 tool_calls |
| 适用场景 | 模型不支持 FC 时的兜底方案 | 生产环境首选 |

#### 什么时候还会用 Prompt 方式

虽然 Function Calling 更好，但 Prompt 方式并没有完全消失：

1. 用的模型不支持 Function Calling（比如一些开源小模型）
2. 工具逻辑非常简单，只有一两个工具，不值得配置完整的 tools schema
3. 需要更灵活的输出格式，比如 ReAct 格式的思维链 + 动作

### 🎯 面试总结

回答这道题要突出三个核心差异：格式保证（结构化输出 vs 自然语言解析）、决策信号（finish_reason vs 自行判断）、工具发现（独立参数 vs 占用 prompt）。然后说明 Prompt 方式作为兜底方案的适用场景，展示你对两种方式的全面理解。

---

## 问题5：Function Calling 中 tool_choice 参数有什么作用？

### 💡 简要回答

`tool_choice` 控制模型在一次请求中「要不要调工具」的行为策略，有几个值：

- `"auto"`：模型自己判断要不要调，最常用的默认值
- `"none"`：强制模型不调任何工具，只输出文本
- `"required"`：强制模型必须调用至少一个工具
- 指定具体工具名：强制模型调用你指定的那个工具

选哪个取决于你的场景对「确定性」的要求有多高。

### 📝 详细解析

#### auto 模式，让模型自己决定

这是最常见的设置。模型根据用户的问题和可用工具，自主判断需不需要调工具：

- 用户问「北京天气怎么样」→ 模型判断需要调 get_weather
- 用户问「你好」→ 模型判断不需要工具，直接回答

优点是灵活、通用，适合大多数对话场景。缺点是模型可能判断失误，该调的没调、不该调的调了。

#### none 模式，禁用工具调用

强制模型只输出文本，即使你传了 tools 参数也不会调用。适用场景：

- 你想让模型先做分析再决定调不调（分步处理）
- 用户明确在闲聊，你不想触发工具
- 调试时想看模型在不调工具的情况下会怎么回答

#### required 模式，强制调用

模型必须输出至少一个 tool_calls，不能选择直接回答。适用场景：

- 你确定这个请求一定需要工具（比如用户在特定流程中）
- 你不想让模型「偷懒」直接编造答案

#### 指定具体工具

```python
tool_choice = {"type": "function", "function": {"name": "get_weather"}}
```

强制模型调用你指定的工具，连「用哪个工具」都不让模型自己选了。适用场景：你已经确定用户意图，只是让模型帮你填参数。

#### 实际生产中的选择策略

大多数通用对话场景用 `auto`；涉及支付、下单等关键操作时，可以用 `required` 确保一定走工具验证；需要分步控制流程的场景（比如先让模型规划、再让模型执行），可以在不同步骤切换 none 和 auto。

### 🎯 面试总结

回答时把四种模式的语义讲清楚，然后给出具体场景说明什么时候用什么。核心是让面试官看到你理解 tool_choice 背后的设计意图：给开发者对工具调用行为的控制力度分级。

---

## 问题6：Function Calling 中如何实现串行工具调用？

### 💡 简要回答

串行工具调用指的是多个工具之间有依赖关系，后一个工具的参数需要前一个工具的返回结果。比如「先查用户的订单号，再用订单号查物流信息」。

实现方式是多轮对话循环：

1. 第一轮：模型输出第一个 tool_calls，你执行拿到结果
2. 把结果以 role="tool" 塞回对话，再调模型
3. 模型看到第一个工具结果后，输出第二个 tool_calls
4. 你执行第二个工具，结果再塞回去
5. 重复直到模型不再输出 tool_calls，给出最终答案

关键是你的代码要有一个循环，每轮检查 finish_reason 是否还是 tool_calls，如果是就继续执行并回传，直到模型认为信息足够了。

### 📝 详细解析

#### 为什么需要串行调用

很多真实场景的工具调用是有先后依赖的：

- 先搜索航班，拿到航班号，再查具体航班的座位情况
- 先查用户信息，拿到用户ID，再查该用户的历史订单
- 先调翻译工具把中文翻成英文，再用英文关键词去搜索英文论文

这些场景不能并行，因为第二步的输入取决于第一步的输出。

#### 代码实现

```python
import openai, json

client = openai.OpenAI()
messages = [{"role": "user", "content": "帮我查一下我最近一个订单的物流状态"}]

while True:
    response = client.chat.completions.create(
        model="gpt-4o",
        messages=messages,
        tools=tools,
        tool_choice="auto"
    )
    msg = response.choices[0].message
    messages.append(msg)

    if msg.tool_calls:
        for tool_call in msg.tool_calls:
            func_name = tool_call.function.name
            func_args = json.loads(tool_call.function.arguments)
            # 根据函数名调用对应工具
            result = execute_tool(func_name, func_args)
            messages.append({
                "role": "tool",
                "tool_call_id": tool_call.id,
                "content": json.dumps(result)
            })
    else:
        # 模型没有调用工具，给出了最终答案
        print(msg.content)
        break
```

#### 模型如何判断要串行

模型在 SFT 训练时见过大量串行调用的样本，它学会了：

- 当第二个工具需要的参数在当前上下文中不存在时，先调第一个工具获取
- 每次只输出当前能确定参数的 tool_calls
- 等拿到结果后再决定下一步

#### 串行调用的风险和优化

主要风险是调用链过长导致延迟高、token 消耗大。优化方式：

- 设置最大轮次上限，防止无限循环
- 工具设计时尽量减少不必要的依赖（能合并的接口合并）
- 每轮结果精简返回，不要把大量无关数据塞给模型

### 🎯 面试总结

回答这道题要把串行和并行的区别讲清楚（有没有依赖关系），然后描述实现机制（循环 + finish_reason 判断），最后提到风险控制（最大轮次、结果精简）。

---

## 问题7：Function Calling 调用不稳定（模型乱调、漏调、参数错）怎么办？

### 💡 简要回答

Function Calling 不稳定主要体现在三种症状：该调的工具没调（漏调）、不该调的工具调了（误调）、调了但参数填错了（参数错误）。

针对性解决方案：

**工具描述优化**是最关键的一步。description 写清楚工具的功能边界、适用场景、输入输出格式。参数的 description 加上类型约束、示例值、格式要求。

**减少工具数量**也很有效。传给模型的工具越多，选错的概率越高。可以做工具路由：先用一个轻量级分类判断用户意图属于哪个类别，再只把该类别的工具传给模型。

**参数校验 + 重试**是兜底手段。拿到 tool_calls 后先做 schema 验证，参数不合法就把错误信息塞回对话让模型重新生成。

**用 Structured Outputs**（如果模型支持）可以从根本上保证输出格式符合 schema。

### 📝 详细解析

#### 为什么会不稳定

Function Calling 不稳定的根源在于：模型的工具选择和参数填充本质上是「概率预测」，而不是确定性逻辑。

常见原因：

1. **工具描述不清晰**：模型不知道什么时候该用、什么时候不该用
2. **工具数量过多**：10个以上工具时，模型的选择准确率会明显下降
3. **参数描述缺乏约束**：没写格式要求、没给示例、没标注边界条件
4. **用户输入模糊**：用户的问题本身就不明确，模型不好判断

#### 解决方案一：优化工具描述

```python
# 差的描述
"description": "查天气"

# 好的描述
"description": "查询中国大陆指定城市的实时天气信息，包含气温(摄氏度)、天气状况、风向风速。仅支持查询当前时刻的天气，不支持历史天气和天气预报。输入必须是中文城市名，如'北京'、'上海'，不要带省份前缀。"
```

#### 解决方案二：工具路由 / 分组

不要把所有工具一次性全传给模型。根据用户意图先做分类，再传对应的工具子集：

```python
# 先做意图分类
intent = classify_intent(user_message)  # 返回 "weather" / "order" / "general"

# 根据意图选择工具子集
tool_groups = {
    "weather": [weather_tool],
    "order": [order_query_tool, logistics_tool],
    "general": []
}
tools_to_use = tool_groups.get(intent, [])
```

#### 解决方案三：参数校验 + 错误回传

```python
from jsonschema import validate, ValidationError

try:
    validate(instance=func_args, schema=tool_param_schema)
except ValidationError as e:
    # 参数不合法，告诉模型错误信息让它重新生成
    messages.append({
        "role": "tool",
        "tool_call_id": tool_call.id,
        "content": f"参数错误：{e.message}，请重新生成正确的参数"
    })
```

#### 解决方案四：temperature 和 top_p 调低

工具调用场景不需要「创造性」，把 temperature 调低（比如 0 或 0.1）可以让输出更确定。

### 🎯 面试总结

回答这道题的核心逻辑是：先说清楚不稳定的三种症状和根因（概率预测本质），然后按优先级给出解决方案（描述优化 > 工具路由 > 参数校验 > 模型参数调整），展示你有系统性的排查和解决思路。

---

## 问题8：MCP 是什么？为什么需要 MCP？

### 💡 简要回答

MCP（Model Context Protocol，模型上下文协议）是 Anthropic 在 2024 年底发布的一个开放标准，定义了应用程序（Host）如何向 LLM 提供工具和上下文。

可以把 MCP 理解成「AI 应用的 USB-C 接口」：在 MCP 出现之前，每个 AI 应用想对接一个新的外部工具（数据库、API、文件系统等），都要写一套专门的集成代码，M 个应用对接 N 个工具就是 M×N 的开发量。MCP 把这个问题变成了 M+N：工具开发者只要实现一次 MCP Server，任何支持 MCP 的 Host 应用都能直接使用。

MCP 相比 Function Calling 的核心升级：

- **标准化**：统一了工具发现、调用、结果返回的协议格式
- **动态性**：工具列表不是写死的，Server 可以动态注册新工具
- **双向通信**：支持 Server 主动推送通知给 Host
- **多种资源类型**：不只是工具，还包括 Resources（上下文数据）和 Prompts（提示词模板）

### 📝 详细解析

#### 为什么需要 MCP

在 MCP 出现之前，AI 应用对接外部工具面临几个痛点：

1. **集成碎片化**：每个工具的接入方式都不同，开发者需要为每个工具写专门的适配代码
2. **无法复用**：A 应用对接了 GitHub 工具，B 应用想用同一个工具还得重写一套
3. **工具发现困难**：模型能用哪些工具完全由开发者硬编码决定
4. **没有生态**：工具开发者没有动力为每个 AI 平台分别适配

MCP 的出现解决了这些问题，就像 USB 标准让所有外设都能插到任何电脑上一样。

#### MCP 的架构

MCP 的架构分三层：

- **Host**：面向用户的 AI 应用（如 Claude Desktop、Cursor、你自己的 chatbot）
- **Client**：Host 内部管理与 Server 通信的组件
- **Server**：暴露工具/资源的服务（如 GitHub Server、数据库 Server、文件系统 Server）

通信基于 JSON-RPC 2.0 协议，支持两种传输方式：

- **Stdio**：本地进程间通信，Server 作为子进程运行
- **SSE（Server-Sent Events）+ HTTP**：远程通信，Server 作为独立服务部署

#### MCP Server 提供的三种能力

1. **Tools（工具）**：模型可以调用的函数，类似 Function Calling 的 tools
2. **Resources（资源）**：上下文数据源，如文件内容、数据库记录、API 数据
3. **Prompts（提示词模板）**：预定义的提示词模板，可以带参数

#### MCP 和 Function Calling 的关系

MCP 不是替代 Function Calling，而是在它之上建立了一层标准化协议。Function Calling 是模型层面的能力（模型输出 tool_calls），MCP 是应用层面的协议（定义工具怎么发现、怎么注册、怎么调用）。一个 MCP Server 暴露的工具，最终还是通过 Function Calling 的方式传给模型使用。

### 🎯 面试总结

回答时要把 MCP 的定位讲清楚：它是应用层的标准协议，解决的是工具集成碎片化问题（M×N → M+N），不是替代 Function Calling。然后说清架构（Host/Client/Server）和核心价值（标准化、动态发现、生态共建），最后和 Function Calling 做一个层级区分。

---

## 问题9：MCP 的传输层协议有哪些？SSE 和 Streamable HTTP 有什么区别？

### 💡 简要回答

MCP 支持三种传输方式：

1. **Stdio**：本地进程间通信，Host 把 Server 作为子进程启动，通过标准输入输出交换 JSON-RPC 消息。最简单、延迟最低，但只能本地用。

2. **SSE + HTTP**（旧方案）：Server 用一个 SSE 端点（`/sse`）推送消息给 Client，Client 用另一个 HTTP 端点（`/messages`）发消息给 Server。两条通道，有状态。

3. **Streamable HTTP**（新方案，2025年取代SSE）：所有通信走单一 HTTP 端点（`/mcp`），Client 用 POST 发请求，Server 可以选择用普通 JSON 响应或 SSE 流式响应。无状态优先，按需升级为有状态。

SSE 和 Streamable HTTP 的核心区别：

| 维度 | SSE (旧) | Streamable HTTP (新) |
|------|----------|---------------------|
| 端点数量 | 两个（/sse + /messages） | 一个（/mcp） |
| 连接模型 | 必须长连接 | 无状态优先，可选长连接 |
| 部署复杂度 | 高（需维护长连接） | 低（标准 HTTP 基础设施即可） |
| 可扩展性 | 差（有状态难水平扩展） | 好（无状态可随意负载均衡） |

### 📝 详细解析

#### Stdio 传输

适用于本地场景，Host 直接 fork 出 Server 进程：

- Host → Server：往 Server 的 stdin 写 JSON-RPC 消息
- Server → Host：从 Server 的 stdout 读 JSON-RPC 消息
- 优点：零网络开销、零配置
- 局限：只能本地，无法远程部署

#### SSE + HTTP（旧方案的问题）

这是 MCP 最早的远程传输方案：

Client 先连上 Server 的 `/sse` 端点，建立一个 SSE 长连接，Server 通过这个连接推送消息（包括工具执行结果、通知等）。Client 要给 Server 发消息时（比如调用工具），走另一个 `/messages` HTTP POST 端点。

问题在于：

1. 两个端点增加了实现复杂度
2. SSE 是长连接，对基础设施要求高（负载均衡器需要支持长连接、连接断了要重连）
3. 有状态设计导致水平扩展困难（请求必须路由到同一个 Server 实例）
4. 在 serverless / edge 环境中几乎无法部署

#### Streamable HTTP（新方案）

2025 年 MCP 协议升级引入 Streamable HTTP，只有一个端点 `/mcp`：

- Client 发 POST 请求到 `/mcp`
- Server 响应可以是普通 JSON（一次性返回结果）
- 如果需要流式推送，Server 返回 `Content-Type: text/event-stream`，用 SSE 格式分块返回
- 通过可选的 `Mcp-Session-Id` header 实现会话管理

这种设计的优势：

1. 无状态优先：大多数请求可以用标准 HTTP 请求-响应模式处理
2. 按需流式：只在需要的时候才升级为 SSE 流
3. 部署简单：任何支持 HTTP 的基础设施都能用
4. 水平扩展友好：无状态请求可以随意路由到任何实例

### 🎯 面试总结

回答时先把三种传输方式的定位讲清楚（Stdio 本地、SSE+HTTP 旧远程方案、Streamable HTTP 新方案），然后重点对比 SSE 和 Streamable HTTP 的差异（双端点 vs 单端点、有状态 vs 无状态优先），最后说明演进原因（部署复杂度和可扩展性）。

---

## 问题10：MCP 协议有什么问题和局限性？

### 💡 简要回答

MCP 协议目前的主要问题和局限性：

1. **安全模型不完善**：协议本身没有内置认证鉴权机制，工具的权限控制完全依赖实现方自己做，容易出安全漏洞。

2. **工具描述依赖自然语言**：模型能不能正确选择和使用工具，完全取决于 description 写得好不好，没有形式化的语义约束。

3. **错误处理不够标准化**：工具执行失败时，返回的错误格式和语义没有统一规范，模型很难从错误中恢复。

4. **生态碎片化**：虽然协议是统一的，但各家 Server 的实现质量参差不齐，缺乏认证和质量保证机制。

5. **性能开销**：每次工具调用都是完整的 JSON-RPC 往返，对于高频调用场景延迟较高。

6. **缺乏工具组合和编排能力**：协议只定义了单个工具的调用，多工具的编排逻辑完全依赖模型或应用层自行实现。

### 📝 详细解析

#### 安全问题

MCP 协议层面几乎没有安全机制：

- 没有标准的认证流程（谁能连我的 Server？）
- 没有权限粒度控制（连上之后能调哪些工具？）
- 没有审计日志规范（调了什么怎么追溯？）
- 工具注入风险：恶意 Server 可以注册看起来正常但行为有害的工具

实际生产中，这些都需要开发者自己在实现层面加上，而不是协议保证的。

#### 工具描述的局限

模型对工具的理解完全基于 name 和 description 两个文本字段。这意味着：

- 两个功能相似的工具，如果 description 写得差不多，模型很容易选错
- 参数之间的约束关系（比如「如果 A 参数填了，B 参数就必须填」）很难用 JSON schema 表达完整
- 工具的副作用（调了之后会改变状态）无法在描述中形式化表达

#### 与 Agent 框架的集成困难

MCP 定义的是「单次工具调用」的协议，但现实中 Agent 需要的是「工具编排」：

- 条件分支：根据上一步结果决定下一步调哪个工具
- 循环：重复调用直到满足条件
- 回滚：某一步失败了要撤销前面的操作

这些编排逻辑在 MCP 协议里没有定义，完全由上层 Agent 框架自行实现，导致不同框架的行为不一致。

### 🎯 面试总结

回答这道题要展示你对 MCP 的批判性思考：它解决了工具集成标准化的问题，但在安全、语义精确性、错误处理、生态治理和复杂编排方面还有明显不足。这些问题不是说 MCP 不好，而是它作为一个年轻的协议还在演进中。

---

## 问题11：MCP 和 Function Calling 有什么区别和联系？

### 💡 简要回答

Function Calling 和 MCP 不是同一层的东西，它们的关系是「模型能力」和「应用协议」的关系。

**Function Calling** 是模型层的能力：经过训练的模型能够根据工具描述（JSON schema），决定要不要调工具、调哪个、参数填什么，然后输出结构化的 tool_calls JSON。这是模型「会不会做决策」的问题。

**MCP** 是应用层的协议：定义了 Host 应用如何发现工具、如何调用工具、如何获取结果的标准流程。这是工具「怎么接入、怎么管理」的问题。

一个典型的运行流程是：MCP Server 暴露工具列表 → Host 通过 MCP 协议获取工具列表 → Host 把工具描述传给模型 → 模型通过 Function Calling 输出 tool_calls → Host 通过 MCP 协议调用 Server 执行工具 → 结果返回给模型。

简单说：Function Calling 解决「模型怎么输出调用指令」，MCP 解决「工具怎么标准化接入和执行」。

### 📝 详细解析

#### 层级关系

```
┌─────────────────────────────────────────┐
│  用户层：用户通过 Host 应用交互           │
├─────────────────────────────────────────┤
│  应用层：MCP 协议（工具发现/注册/调用）    │
├─────────────────────────────────────────┤
│  模型层：Function Calling（决策/输出JSON） │
├─────────────────────────────────────────┤
│  工具层：实际的 API/数据库/文件系统        │
└─────────────────────────────────────────┘
```

#### 各自解决的问题

| 维度 | Function Calling | MCP |
|------|-----------------|-----|
| 解决什么 | 模型怎么表达「我要调工具」 | 工具怎么标准化注册和调用 |
| 谁定义的 | 模型提供商（OpenAI等） | 协议组织（Anthropic发起） |
| 标准化的是 | tool_calls 的输出格式 | 工具发现、调用、结果返回的完整流程 |
| 动态性 | 工具列表每次请求传入 | 工具可以动态注册/注销 |
| 生态效应 | 无（每次都要手动传工具） | 有（M+N 的复用效应） |

#### 没有 MCP 时的 Function Calling

没有 MCP，你依然可以用 Function Calling，只不过工具的接入全靠手写代码：

```python
# 手动定义工具schema
tools = [{"type": "function", "function": {...}}]
# 手动实现执行逻辑
if func_name == "get_weather":
    result = requests.get("https://weather-api.com/...")
```

这种方式在工具少的时候没问题，但工具多了之后维护成本爆炸。

#### 有 MCP 时的 Function Calling

有了 MCP，工具的发现和执行被标准化了：

```python
# 通过 MCP 协议自动获取可用工具列表
tools = mcp_client.list_tools()
# 模型做决策（这一步还是 Function Calling）
response = model.chat(messages=messages, tools=tools)
# 通过 MCP 协议执行工具
result = mcp_client.call_tool(tool_name, arguments)
```

### 🎯 面试总结

回答这道题的关键是把层级关系讲清楚：Function Calling 是模型层能力，MCP 是应用层协议，两者互补而非替代。然后用一个完整的运行流程把它们串起来，展示你理解整个系统是怎么协作的。

---

## 问题12：什么是 Agent？与大模型有什么区别？

### 💡 简要回答

Agent（智能体）是一个**以 LLM 为核心决策引擎，能自主规划、使用工具、执行任务的系统**。

大模型本身只是一个「输入文本 → 输出文本」的函数，它没有记忆、没有工具、没有自主行动能力。你问它一个问题，它回答完就结束了，下次再问它什么都不记得。

Agent 在大模型基础上加了几个关键组件：

- **规划能力**：把复杂任务拆解成多个步骤
- **工具使用**：通过 Function Calling / MCP 调用外部工具
- **记忆系统**：短期记忆（对话历史）+ 长期记忆（向量数据库）
- **自主循环**：不需要人每一步都参与，能自己跑完整个流程

一句话总结：大模型是大脑，Agent 是有手有脚有记忆的完整个体。

### 📝 详细解析

#### 大模型的本质局限

大模型的本质是一个概率语言模型：给定前文，预测下一个 token。它有几个天生的局限：

1. **无状态**：每次调用都是独立的，不记得上次说了什么（除非你手动把历史塞进 prompt）
2. **无法行动**：只能输出文本，不能发邮件、不能操作数据库、不能浏览网页
3. **知识截止**：训练数据有截止日期，之后的信息它不知道
4. **无法自主**：必须等你提问才能回答，不会主动做事

#### Agent 的核心架构

一个标准 Agent 的架构通常包含：

```
┌────────────────────────────────────────┐
│  Agent                                  │
│  ┌──────────────────────────────────┐  │
│  │  LLM（决策引擎）                   │  │
│  └──────────────────────────────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌───────┐  │
│  │ 规划模块  │ │ 工具模块  │ │ 记忆  │  │
│  └──────────┘ └──────────┘ └───────┘  │
│  ┌──────────────────────────────────┐  │
│  │  执行循环（Observe → Think → Act） │  │
│  └──────────────────────────────────┘  │
└────────────────────────────────────────┘
```

#### Agent 的工作循环

Agent 的核心是一个「观察 → 思考 → 行动」的循环：

1. **Observe**：接收环境信息（用户输入、工具结果、系统状态）
2. **Think**：LLM 分析当前状态，决定下一步做什么
3. **Act**：执行动作（调用工具、回复用户、更新状态）
4. 重复，直到任务完成

#### 对比表

| 维度 | 大模型 | Agent |
|------|--------|-------|
| 本质 | 语言模型（文本→文本） | 自主系统 |
| 记忆 | 无（靠 prompt 传入） | 有（短期+长期） |
| 工具 | 无（需 Function Calling 机制） | 内置工具调用能力 |
| 规划 | 单轮回答 | 多步规划执行 |
| 自主性 | 被动响应 | 主动行动 |
| 错误处理 | 无 | 可以自我纠错重试 |

### 🎯 面试总结

回答这道题要把大模型和 Agent 的边界画清楚：大模型是决策引擎，Agent 是在大模型基础上加了规划、工具、记忆和执行循环的完整系统。然后用「大脑 vs 完整个体」的类比让面试官秒懂。

---

## 问题13：什么是 A2A 协议？和 MCP 有什么关系？

### 💡 简要回答

A2A（Agent-to-Agent）是 Google 在 2025 年 4 月发布的一个开放协议，定义了**不同 Agent 之间如何发现彼此、协商能力、委派任务、交换结果**。

如果说 MCP 解决的是「一个 Agent 怎么使用工具」的问题（Agent ↔ Tool），那 A2A 解决的是「多个 Agent 之间怎么协作」的问题（Agent ↔ Agent）。

两者是互补关系：

- **MCP** 是垂直方向的——Agent 向下调用工具
- **A2A** 是水平方向的——Agent 之间平等协作

一个 Agent 内部可以用 MCP 连接自己的工具，同时通过 A2A 和其他 Agent 交互。

### 📝 详细解析

#### 为什么需要 A2A

现实中很多任务需要多个专业 Agent 协作完成：

- 招聘场景：简历筛选 Agent、面试安排 Agent、背调 Agent 各司其职
- 客服场景：前端接待 Agent 判断问题类型，转给退款 Agent 或技术支持 Agent
- 研发场景：需求分析 Agent 输出规格，编码 Agent 写代码，测试 Agent 做验证

没有标准协议时，这些 Agent 之间的通信全靠私有接口，换一个 Agent 就要重写对接逻辑。

#### A2A 的核心概念

1. **Agent Card**：每个 Agent 的「名片」，声明自己能做什么、接受什么输入、通过什么地址联系。类似 MCP Server 的工具描述，但粒度是整个 Agent。

2. **Task**：Agent 之间协作的基本单位。一个 Agent 可以向另一个 Agent 创建 Task，Task 有状态流转（submitted → working → completed/failed）。

3. **Message & Artifact**：Task 执行过程中，Agent 之间通过 Message 沟通，通过 Artifact 交换结果文件。

4. **Push Notification**：长时间任务执行完后，被调用的 Agent 可以主动通知调用方。

#### A2A 和 MCP 的对比

| 维度 | MCP | A2A |
|------|-----|-----|
| 关系类型 | Agent ↔ Tool（上下级） | Agent ↔ Agent（平等） |
| 解决什么 | 工具怎么接入 | Agent 怎么协作 |
| 被调用方 | 被动的工具（无自主性） | 主动的 Agent（有自主性） |
| 通信模式 | 同步为主 | 支持异步（长时间任务） |
| 发现机制 | MCP Server 的工具列表 | Agent Card（类似服务发现） |

#### 它们如何配合

```
┌────────────────────────────────────────────────────┐
│  Agent A                                            │
│  ├── MCP Client → [MCP Server: 数据库工具]          │
│  ├── MCP Client → [MCP Server: 文件系统工具]        │
│  └── A2A Client → [Agent B: 专业分析Agent]          │
│                       └── MCP Client → [其他工具]   │
└────────────────────────────────────────────────────┘
```

Agent A 内部用 MCP 调用自己的工具（数据库、文件系统），同时通过 A2A 把「数据分析」这个子任务委派给专业的 Agent B。Agent B 内部也可能用 MCP 调用它自己的工具。

### 🎯 面试总结

回答这道题要把 MCP 和 A2A 的关系理清：MCP 是垂直的（Agent 用工具），A2A 是水平的（Agent 间协作），两者互补。然后用实际场景（多 Agent 协作）说明为什么需要 A2A，最后画一个配合图展示它们在整体架构中的位置。

---

## 问题14：Agent 常见的工具调用方式有哪些（ReAct、Plan-and-Execute 等）？

### 💡 简要回答

Agent 调用工具的方式（也叫推理策略或行动范式）主要有以下几种：

1. **ReAct（Reasoning + Acting）**：每一步都经过「思考 → 行动 → 观察」的循环。模型先推理当前该做什么，再执行一个动作（调一次工具），观察结果后再推理下一步。逐步推进，步步为营。

2. **Plan-and-Execute**：先一次性规划出完整的步骤列表，然后按计划逐步执行。规划和执行分离，先想清楚再动手。

3. **Function Calling（直接调用）**：模型直接输出 tool_calls，没有显式的思维链。简单高效，适合单步或少量工具调用的场景。

4. **Reflexion（反思）**：在 ReAct 基础上加了自我反思环节，执行失败后模型会分析原因并调整策略重试。

### 📝 详细解析

#### ReAct 模式

ReAct 是最经典的 Agent 推理范式，核心是让模型在每一步都「先想再做」：

```
Thought: 用户想知道北京天气，我需要调用天气API
Action: get_weather(city="北京")
Observation: 晴，15°C，东北风3级
Thought: 我已经拿到了天气信息，可以回答用户了
Answer: 北京今天天气晴朗，气温15°C...
```

优点：

- 每一步决策都有推理过程，可解释性强
- 出错时容易定位是哪一步的推理出了问题
- 适合复杂、需要多步推理的任务

缺点：

- 每一步都要调用模型推理，延迟高
- token 消耗大（思维链本身占 token）
- 可能陷入循环（反复执行同一个动作）

#### Plan-and-Execute 模式

先规划后执行，把「想」和「做」分成两个阶段：

```
Plan:
1. 查询用户最近的订单号
2. 用订单号查询物流状态
3. 整理信息回复用户

Execute:
Step 1: get_recent_order(user_id="123") → order_id: "A456"
Step 2: get_logistics(order_id="A456") → 已送达
Step 3: 回复用户
```

优点：

- 全局视野，一开始就规划好整体路径
- 执行阶段可以并行处理无依赖的步骤
- 适合任务目标明确、步骤可预期的场景

缺点：

- 计划可能因为中间步骤的意外结果而失效
- 需要「重规划」机制来应对计划偏差
- 前期规划可能过于理想化

#### Function Calling 直接调用

最简单的方式，模型直接输出 tool_calls，没有显式的推理过程：

```python
response = model.chat(messages=messages, tools=tools)
# 模型直接输出：tool_calls: [{"name": "get_weather", "arguments": {"city": "北京"}}]
```

优点：延迟低、实现简单。缺点：缺乏可解释性、复杂任务容易出错。

#### 各模式的适用场景

| 模式 | 适用场景 | 典型应用 |
|------|---------|---------|
| ReAct | 探索性任务、步骤不确定 | 研究助手、问题排查 |
| Plan-and-Execute | 目标明确、步骤可预期 | 工作流自动化、批量操作 |
| Function Calling | 简单任务、单步调用 | 聊天机器人、简单问答 |
| Reflexion | 需要高准确率、允许重试 | 代码生成、数据分析 |

### 🎯 面试总结

回答时把几种模式的核心区别讲清楚：ReAct 是「边想边做」，Plan-and-Execute 是「先想后做」，Function Calling 是「直接做」，Reflexion 是「做完反思再做」。然后给出各自的适用场景，展示你能在实际项目中选择合适的范式。

---

## 问题15：SSE、WebSocket、WebRTC 的区别是什么？在 AI 应用中怎么选？

### 💡 简要回答

这三种都是实时通信技术，但设计目标和适用场景不同：

**SSE（Server-Sent Events）**：单向的，只有服务端能主动推送给客户端。基于 HTTP，实现简单，天然兼容已有基础设施。适合「服务器单向推流」的场景，比如 LLM 的流式输出。

**WebSocket**：双向的，客户端和服务端可以随时互发消息。需要先通过 HTTP 握手升级协议。适合需要频繁双向通信的场景，比如实时聊天、协同编辑。

**WebRTC**：点对点（P2P）的，主要用于音视频实时通信。可以绕过服务器直接传输，延迟极低。适合语音通话、视频会议等场景。

| 维度 | SSE | WebSocket | WebRTC |
|------|-----|-----------|--------|
| 方向 | 单向（服务端→客户端） | 双向 | 双向（P2P） |
| 协议基础 | HTTP | 升级自 HTTP | UDP（底层） |
| 延迟 | 中等 | 低 | 极低 |
| 复杂度 | 低 | 中 | 高 |
| 断线重连 | 内置 | 需自行实现 | 需自行实现 |

### 📝 详细解析

#### SSE 在 AI 应用中的角色

LLM 的流式输出天然适合 SSE：

- 模型逐 token 生成，服务端逐步推送给客户端
- 客户端只需要接收，不需要在传输过程中发消息给服务端
- 基于 HTTP，CDN、负载均衡器、代理都天然支持
- 断线自动重连是协议内置的

OpenAI、Claude 的流式 API 都用的 SSE。MCP 协议的旧传输方案也用了 SSE。

```javascript
const eventSource = new EventSource('/api/chat/stream');
eventSource.onmessage = (event) => {
    const token = JSON.parse(event.data);
    appendToUI(token.content);
};
```

#### WebSocket 在 AI 应用中的角色

当需要双向实时通信时选 WebSocket：

- 用户在 AI 对话中可能随时中断生成（需要客户端→服务端发取消信号）
- 多人协同使用同一个 Agent（需要广播状态变更）
- 实时协作编辑场景

```javascript
const ws = new WebSocket('wss://api.example.com/chat');
ws.onmessage = (event) => { /* 收到消息 */ };
ws.send(JSON.stringify({ action: 'cancel' })); // 随时可以发消息给服务端
```

#### WebRTC 在 AI 应用中的角色

语音 AI 助手、实时语音翻译等场景：

- 用户说话 → 音频流实时传给模型 → 模型回复的语音流实时播放
- 需要极低延迟（语音对话的体验要求 < 200ms）
- P2P 直连避免了服务器中转的延迟

OpenAI 的 Realtime API 就使用了 WebRTC。

#### 如何选择

```
需要服务端单向推流（如 LLM 流式输出）？ → SSE
需要双向实时通信（如聊天+中断）？ → WebSocket
需要音视频实时传输？ → WebRTC
```

### 🎯 面试总结

回答时先把三者的核心区别讲清楚（单向/双向/P2P），然后结合 AI 应用的具体场景说明选型逻辑（LLM 流式用 SSE、交互式 Agent 用 WebSocket、语音 AI 用 WebRTC），展示你能在实际架构设计中做出合理选择。

---

## 问题16：LLM Gateway（大模型网关）是什么？有什么作用？

### 💡 简要回答

LLM Gateway（大模型网关）是部署在应用和 LLM API 之间的一层中间件，统一管理所有 LLM 调用的流量。类似于微服务架构中的 API Gateway，但专门针对 LLM 场景做了优化。

核心作用：

1. **统一接入**：对上层应用暴露统一的 API 格式，底层可以对接 OpenAI、Claude、Gemini、私有模型等多个 Provider，切换模型对应用透明。

2. **流量管控**：限流、熔断、重试、负载均衡。LLM API 经常有 rate limit 和不稳定问题，网关层统一处理。

3. **可观测性**：记录每次调用的 token 数、延迟、成功率、成本，便于监控和计费。

4. **安全管控**：敏感信息过滤（PII 脱敏）、prompt 注入检测、权限控制。

5. **缓存和成本优化**：语义缓存（相似问题复用结果）、路由到成本更低的模型处理简单问题。

### 📝 详细解析

#### 为什么需要 LLM Gateway

当你的应用只用一个模型、调用量不大时，直接调 API 完全没问题。但随着规模增长，会遇到这些问题：

- 多个团队用不同模型，各自管理 API key，无法统一审计
- LLM API 有 rate limit，多个服务同时调经常互相抢额度
- 想换模型要改代码，每个服务都改一遍
- 没有统一的 token 消耗和成本统计
- 敏感数据直接发给第三方 LLM，合规风险

LLM Gateway 把这些横切关注点从业务代码中抽出来，集中在网关层处理。

#### 核心功能模块

**统一接口层**

```
应用A (用 OpenAI 格式) ──┐
                         ├─→ [LLM Gateway] ─→ OpenAI / Claude / 私有模型
应用B (用 Claude 格式) ──┘
```

网关对外暴露统一的 API 格式（通常兼容 OpenAI 格式），内部根据路由规则转发到不同的模型 Provider。应用只需要对接网关，不需要关心底层用的是哪个模型。

**智能路由**

根据请求特征路由到不同模型：

- 简单问题（闲聊、简单问答）→ 便宜的小模型
- 复杂问题（推理、代码生成）→ 能力强的大模型
- 特定领域问题 → 专业微调模型

**缓存**

语义缓存：不是精确匹配，而是用 embedding 计算相似度，相似问题可以复用之前的回答。能显著降低成本和延迟。

**可观测性**

记录每次请求的完整信息：模型、token 数（prompt + completion）、延迟、状态码、成本。便于：

- 按团队/项目分摊成本
- 监控模型服务质量
- 发现异常调用模式

**安全层**

- 输入检测：检测 prompt injection 攻击
- 输出过滤：防止模型输出敏感/有害内容
- PII 脱敏：在发给第三方 LLM 前脱敏个人信息，拿到回复后还原

#### 开源方案

目前常见的开源 LLM Gateway：

- **LiteLLM**：Python 生态，支持 100+ 模型 Provider 的统一接口
- **Portkey**：专注于 AI 网关场景，有可视化面板
- **Kong AI Gateway**：在传统 API 网关基础上扩展了 AI 场景支持

### 🎯 面试总结

回答这道题要把 LLM Gateway 的定位讲清楚（LLM 调用的统一中间层），然后按功能模块展开（统一接入、流量管控、可观测性、安全、缓存），最后说明适用场景（多模型、多团队、生产级部署），展示你对 LLM 应用架构的全局理解。
