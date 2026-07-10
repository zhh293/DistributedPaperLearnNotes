# LangChain 框架详解：API、参数、用法与实战 Demo

## 一、LangChain 概述

LangChain 是一个用于构建基于大语言模型（LLM）应用程序的开源框架。它的核心思想是将 LLM 的调用与外部数据源、工具、记忆模块等通过标准化的接口组合在一起，形成可复用的"链"（Chain）。

LangChain 的主要模块包括：

- **Models（模型层）**：对接各种 LLM 和 Chat Model
- **Prompts（提示词）**：模板化管理提示词
- **Chains（链）**：将多个组件串联成工作流
- **Memory（记忆）**：为对话/链提供上下文记忆
- **Agents（代理）**：让 LLM 自主决定调用哪些工具
- **Tools（工具）**：LLM 可以调用的外部能力
- **Document Loaders / Text Splitters / Vector Stores / Retrievers（RAG 相关）**：检索增强生成全套组件
- **Output Parsers（输出解析器）**：将 LLM 输出解析为结构化数据
- **Callbacks（回调）**：监控、日志、流式输出等

安装方式：

```bash
pip install langchain langchain-openai langchain-community langchain-core
```

---

## 二、Models（模型层）

### 2.1 ChatOpenAI —— 对接 OpenAI Chat 模型

这是最常用的模型类，用于调用 OpenAI 的 GPT 系列模型。

```python
from langchain_openai import ChatOpenAI

# 创建一个 ChatOpenAI 实例
llm = ChatOpenAI(
    model="gpt-4o",           # 模型名称，可选 gpt-3.5-turbo, gpt-4, gpt-4o 等
    temperature=0.7,          # 温度参数，0~2，越高越随机，越低越确定
    max_tokens=1024,          # 最大生成 token 数
    timeout=30,               # 请求超时时间（秒）
    max_retries=2,            # 失败重试次数
    api_key="sk-xxx",         # OpenAI API Key（也可通过环境变量 OPENAI_API_KEY 设置）
    base_url=None,            # 自定义 API 地址（用于代理或兼容接口）
    streaming=False,          # 是否启用流式输出
)
```

**参数详解：**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `model` | str | `"gpt-3.5-turbo"` | 使用的模型名称 |
| `temperature` | float | `0.7` | 控制输出随机性。0 表示几乎确定性输出，1 表示较高创造性 |
| `max_tokens` | int | None | 限制生成的最大 token 数量。None 表示不限制 |
| `timeout` | float | None | HTTP 请求超时时间（秒） |
| `max_retries` | int | `2` | API 调用失败时的最大重试次数 |
| `api_key` | str | None | API 密钥，优先级高于环境变量 |
| `base_url` | str | None | 自定义 API 端点 URL |
| `streaming` | bool | `False` | 是否启用流式传输 |
| `model_kwargs` | dict | `{}` | 传递给模型的额外参数（如 top_p, frequency_penalty 等） |
| `n` | int | `1` | 每次请求生成的回复数量 |

**Demo：基础调用**

```python
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, SystemMessage

# 初始化模型
llm = ChatOpenAI(model="gpt-4o", temperature=0)

# 方式1：直接传入消息列表
messages = [
    SystemMessage(content="你是一个有帮助的AI助手"),  # 系统消息，设定角色
    HumanMessage(content="用一句话解释什么是分布式系统"),  # 用户消息
]

# invoke() 是同步调用方法，返回 AIMessage 对象
response = llm.invoke(messages)
print(response.content)  # 输出 AI 的回复文本
print(response.response_metadata)  # 输出元数据（token 用量等）
```

**Demo：流式输出**

```python
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

# 启用 streaming
llm = ChatOpenAI(model="gpt-4o", temperature=0.5, streaming=True)

# stream() 方法返回一个生成器，逐 chunk 输出
for chunk in llm.stream([HumanMessage(content="写一首关于编程的短诗")]):
    print(chunk.content, end="", flush=True)  # 实时打印每个 chunk
```

**Demo：批量调用**

```python
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# batch() 方法接收消息列表的列表，并行处理多个请求
results = llm.batch([
    [HumanMessage(content="1+1等于几？")],
    [HumanMessage(content="地球的半径是多少？")],
    [HumanMessage(content="Python的创始人是谁？")],
])

# 返回 AIMessage 列表
for r in results:
    print(r.content)
```

**Demo：异步调用**

```python
import asyncio
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

llm = ChatOpenAI(model="gpt-4o", temperature=0)

async def main():
    # ainvoke() 是异步版本的 invoke
    response = await llm.ainvoke([HumanMessage(content="异步调用测试")])
    print(response.content)

    # astream() 是异步版本的 stream
    async for chunk in llm.astream([HumanMessage(content="异步流式测试")]):
        print(chunk.content, end="", flush=True)

asyncio.run(main())
```

---

### 2.2 其他常见模型类

```python
# ===== Anthropic Claude =====
from langchain_anthropic import ChatAnthropic
llm = ChatAnthropic(
    model="claude-3-5-sonnet-20241022",  # Claude 模型名称
    temperature=0,
    max_tokens=1024,
    anthropic_api_key="sk-ant-xxx",      # Anthropic API Key
)

# ===== 本地 Ollama 模型 =====
from langchain_community.llms import Ollama
llm = Ollama(
    model="llama3",                      # 本地模型名称
    base_url="http://localhost:11434",   # Ollama 服务地址
    temperature=0.7,
)

# ===== 通义千问（阿里云 DashScope） =====
from langchain_community.chat_models import ChatTongyi
llm = ChatTongyi(
    model="qwen-max",                   # 通义千问模型名
    dashscope_api_key="sk-xxx",         # DashScope API Key
)

# ===== 智谱 AI =====
from langchain_community.chat_models import ChatZhipuAI
llm = ChatZhipuAI(
    model="glm-4",                      # GLM 模型名
    api_key="xxx",                      # 智谱 API Key
)

# ===== Embeddings 模型（向量化） =====
from langchain_openai import OpenAIEmbeddings
embeddings = OpenAIEmbeddings(
    model="text-embedding-3-small",     # embedding 模型名
    dimensions=1536,                    # 向量维度（可选，部分模型支持自定义）
)

# 将文本转为向量
vector = embeddings.embed_query("Hello World")
print(f"向量维度: {len(vector)}")  # 1536

# 批量向量化
vectors = embeddings.embed_documents(["文本1", "文本2", "文本3"])
print(f"数量: {len(vectors)}, 维度: {len(vectors[0])}")
```

---

## 三、Messages（消息类型）

LangChain 定义了一套标准化的消息类型，用于与 Chat Model 交互：

```python
from langchain_core.messages import (
    SystemMessage,     # 系统消息：设定 AI 的角色和行为
    HumanMessage,      # 用户消息：用户输入
    AIMessage,         # AI 消息：模型的回复
    ToolMessage,       # 工具消息：工具调用的结果返回
)

# ===== SystemMessage =====
# 告诉 LLM 它应该扮演什么角色、遵循什么规则
sys_msg = SystemMessage(content="你是一个专业的Python教程作者，回答简洁准确")

# ===== HumanMessage =====
# 代表用户的输入
human_msg = HumanMessage(content="请解释装饰器")

# ===== AIMessage =====
# 代表模型的回复（通常由模型返回，也可以手动构造用于对话历史）
ai_msg = AIMessage(content="装饰器是Python中一种用于修改函数行为的语法糖...")

# ===== ToolMessage =====
# 工具调用后的返回结果（通常由框架自动构造）
tool_msg = ToolMessage(
    content="搜索结果：北京今天晴，25度",
    tool_call_id="call_abc123",     # 对应的工具调用 ID
)

# ===== 通用属性 =====
# 每种消息都有以下通用属性：
# - content: str | list        消息内容（文本或多模态内容列表）
# - additional_kwargs: dict    额外参数
# - response_metadata: dict    响应元数据（仅 AIMessage 有）
# - id: str                    消息唯一标识
# - type: str                  消息类型标识（"system"/"human"/"ai"/"tool"）
```

**Demo：多模态消息（发送图片给视觉模型）**

```python
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

# GPT-4o 支持图片输入
llm = ChatOpenAI(model="gpt-4o", temperature=0)

# content 为列表形式时可以包含文本和图片
message = HumanMessage(
    content=[
        {"type": "text", "text": "请描述这张图片的内容"},
        {
            "type": "image_url",
            "image_url": {
                "url": "https://example.com/photo.jpg",  # 图片 URL
                "detail": "high",  # 分辨率级别: "low"(快速) / "high"(精细) / "auto"
            },
        },
    ]
)

response = llm.invoke([message])
print(response.content)
```

**Demo：使用 Base64 图片**

```python
import base64
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage

# 读取本地图片并编码为 base64
with open("./image.png", "rb") as f:
    image_data = base64.standard_b64encode(f.read()).decode("utf-8")

message = HumanMessage(
    content=[
        {"type": "text", "text": "这是什么图片？"},
        {
            "type": "image_url",
            "image_url": {
                "url": f"data:image/png;base64,{image_data}",  # base64 格式
            },
        },
    ]
)

llm = ChatOpenAI(model="gpt-4o")
response = llm.invoke([message])
print(response.content)
```

---

## 四、Prompts（提示词模板）

### 4.1 ChatPromptTemplate —— 聊天提示词模板（最核心）

```python
from langchain_core.prompts import ChatPromptTemplate

# ===== 方式一：from_messages() —— 最常用 =====
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个{role}，请用{language}回答问题"),  # (角色, 内容模板)
    ("human", "{question}"),                             # {变量} 为占位符
])

# invoke() 填充变量，返回 ChatPromptValue
prompt_value = prompt.invoke({
    "role": "数据库专家",
    "language": "中文",
    "question": "什么是索引？"
})

# 查看格式化后的消息列表
print(prompt_value.to_messages())
# [SystemMessage(content='你是一个数据库专家，请用中文回答问题'),
#  HumanMessage(content='什么是索引？')]

# ===== 方式二：from_template() —— 快捷方式（仅 Human 消息）=====
simple_prompt = ChatPromptTemplate.from_template("解释一下{concept}")
# 等价于 from_messages([("human", "解释一下{concept}")])

# ===== 查看模板信息 =====
print(prompt.input_variables)  # ['role', 'language', 'question']
print(prompt.messages)         # 模板消息列表
```

**ChatPromptTemplate 核心方法：**

| 方法/属性 | 说明 |
|-----------|------|
| `from_messages(messages)` | 从消息模板列表创建。每个元素是 (role, template) 元组 |
| `from_template(template)` | 从单个模板字符串创建（仅 HumanMessage） |
| `invoke(input_dict)` | 填充所有变量，返回 ChatPromptValue |
| `format_messages(**kwargs)` | 填充变量，返回 List[BaseMessage] |
| `input_variables` | 属性，所有需要填充的变量名列表 |
| `partial(**kwargs)` | 部分填充变量，返回新模板 |
| `append(message)` | 追加一条消息模板 |

**Demo：partial 部分填充（适合复用）**

```python
from langchain_core.prompts import ChatPromptTemplate

# 一个有多个变量的模板
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是{company}的{role}"),
    ("human", "{question}"),
])

# partial 先固定一部分变量，返回新模板
# 适合一些变量是固定的、另一些变量需要动态填充的场景
partial_prompt = prompt.partial(company="美团", role="技术专家")

# 新模板只需要填充剩余变量
print(partial_prompt.input_variables)  # ['question']
result = partial_prompt.invoke({"question": "微服务架构有什么优势？"})
print(result.to_messages())
```

---

### 4.2 PromptTemplate —— 简单字符串模板

```python
from langchain_core.prompts import PromptTemplate

# ===== 方式一：from_template 自动推断变量 =====
template = PromptTemplate.from_template(
    "请将以下{source_lang}文本翻译成{target_lang}：\n{text}"
)
print(template.input_variables)  # ['source_lang', 'target_lang', 'text']

# ===== 方式二：显式声明变量 =====
template = PromptTemplate(
    input_variables=["name", "topic"],
    template="你好{name}，请给我讲讲{topic}的核心概念",
)

# format() 返回填充后的纯字符串
result = template.format(name="小明", topic="分布式系统")
print(result)  # "你好小明，请给我讲讲分布式系统的核心概念"

# invoke() 返回 StringPromptValue
result = template.invoke({"name": "小明", "topic": "Raft算法"})
print(result.to_string())
```

---

### 4.3 MessagesPlaceholder —— 动态消息占位符

用于在固定的模板中插入不定数量的消息（典型用途：对话历史）。

```python
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.messages import HumanMessage, AIMessage

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个有帮助的助手"),
    # MessagesPlaceholder 在这里展开为任意数量的消息
    MessagesPlaceholder(
        variable_name="chat_history",  # 变量名
        optional=True,                  # True: 如果未传入则忽略（不报错）
    ),
    ("human", "{input}"),
])

# 传入历史对话
result = prompt.invoke({
    "chat_history": [
        HumanMessage(content="我叫小明"),
        AIMessage(content="你好小明！有什么可以帮助你的？"),
        HumanMessage(content="我在学习Python"),
        AIMessage(content="很好！Python是一门优秀的语言。"),
    ],
    "input": "你还记得我在学什么吗？",
})

for msg in result.to_messages():
    print(f"[{msg.type}] {msg.content}")
```

---

### 4.4 FewShotChatMessagePromptTemplate —— 少样本提示

```python
from langchain_core.prompts import (
    ChatPromptTemplate,
    FewShotChatMessagePromptTemplate,
)

# 定义少样本示例
examples = [
    {"input": "happy", "output": "sad"},
    {"input": "tall", "output": "short"},
    {"input": "hot", "output": "cold"},
]

# 每个示例的格式模板
example_prompt = ChatPromptTemplate.from_messages([
    ("human", "{input}"),
    ("ai", "{output}"),
])

# 创建少样本模板
few_shot_prompt = FewShotChatMessagePromptTemplate(
    example_prompt=example_prompt,  # 单个示例的格式
    examples=examples,              # 所有示例
)

# 组装完整 prompt
final_prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个反义词生成器，用户给一个词，你回复它的反义词"),
    few_shot_prompt,                # 少样本示例在这里展开
    ("human", "{input}"),           # 实际输入
])

# 查看效果
result = final_prompt.invoke({"input": "bright"})
for msg in result.to_messages():
    print(f"[{msg.type}] {msg.content}")
# [system] 你是一个反义词生成器...
# [human] happy
# [ai] sad
# [human] tall
# [ai] short
# [human] hot
# [ai] cold
# [human] bright
```

---

## 五、Output Parsers（输出解析器）

### 5.1 StrOutputParser —— 字符串解析器

```python
from langchain_core.output_parsers import StrOutputParser

# 最简单的解析器：从 AIMessage 中提取 content 字符串
parser = StrOutputParser()

# 通常在 LCEL 链末尾使用
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate

llm = ChatOpenAI(model="gpt-4o", temperature=0)
prompt = ChatPromptTemplate.from_template("用一句话解释{concept}")

# 使用 | 管道操作符串联
chain = prompt | llm | parser

# invoke 返回纯字符串（而非 AIMessage 对象）
result = chain.invoke({"concept": "递归"})
print(result)       # "递归是指函数在其定义中调用自身..."
print(type(result)) # <class 'str'>
```

---

### 5.2 JsonOutputParser —— JSON 输出解析器

```python
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field

# 用 Pydantic 定义期望的输出结构
class BookInfo(BaseModel):
    title: str = Field(description="书名")
    author: str = Field(description="作者")
    year: int = Field(description="出版年份")
    summary: str = Field(description="一句话简介")

# 创建解析器
parser = JsonOutputParser(pydantic_object=BookInfo)

# get_format_instructions() 会生成告诉 LLM 输出格式的说明
# 将其嵌入 prompt 中引导 LLM 输出合格 JSON
prompt = ChatPromptTemplate.from_template(
    "请介绍一本关于{topic}的书。\n{format_instructions}"
)

llm = ChatOpenAI(model="gpt-4o", temperature=0)

chain = prompt | llm | parser

result = chain.invoke({
    "topic": "设计模式",
    "format_instructions": parser.get_format_instructions(),
})

print(result)       # dict: {'title': '...', 'author': '...', 'year': ..., 'summary': '...'}
print(type(result)) # <class 'dict'>
```

---

### 5.3 PydanticOutputParser —— 返回 Pydantic 对象

```python
from langchain_core.output_parsers import PydanticOutputParser
from pydantic import BaseModel, Field
from typing import List

# 定义结构（可以嵌套复杂类型）
class CodeReview(BaseModel):
    """代码审查结果"""
    file_name: str = Field(description="文件名")
    issues: List[str] = Field(description="发现的问题列表")
    score: int = Field(description="代码质量评分，1-10")
    suggestion: str = Field(description="改进建议")

# PydanticOutputParser 解析后返回 Pydantic 模型实例
parser = PydanticOutputParser(pydantic_object=CodeReview)

# 使用示例
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

prompt = ChatPromptTemplate.from_template(
    "审查以下代码：\n```\n{code}\n```\n{format_instructions}"
)
llm = ChatOpenAI(model="gpt-4o", temperature=0)
chain = prompt | llm | parser

result = chain.invoke({
    "code": "def add(a,b): return a+b",
    "format_instructions": parser.get_format_instructions(),
})

# 返回 Pydantic 对象，可直接访问属性
print(result.file_name)     # str
print(result.issues)        # List[str]
print(result.score)         # int
print(result.suggestion)    # str
```

---

### 5.4 with_structured_output() —— 结构化输出（推荐）

LangChain 推荐的结构化输出方式，直接绑定到模型上。

```python
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, Field
from typing import List, Optional

# 定义输出结构
class MovieRecommendation(BaseModel):
    """电影推荐结果"""
    title: str = Field(description="电影标题")
    genre: str = Field(description="类型")
    year: int = Field(description="上映年份")
    reason: str = Field(description="推荐理由")
    rating: float = Field(description="评分，满分10分")

# with_structured_output 绑定输出格式到模型
llm = ChatOpenAI(model="gpt-4o", temperature=0)
structured_llm = llm.with_structured_output(MovieRecommendation)

# 调用时直接返回 Pydantic 对象，无需额外 parser
result = structured_llm.invoke("推荐一部科幻电影")
print(f"电影：{result.title}")
print(f"类型：{result.genre}")
print(f"年份：{result.year}")
print(f"理由：{result.reason}")
print(f"评分：{result.rating}")
```

**with_structured_output 参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `schema` | BaseModel / dict | Pydantic 模型类或 JSON Schema 字典 |
| `method` | str | 实现方式：`"function_calling"`（默认）或 `"json_mode"` |
| `include_raw` | bool | 是否同时返回原始 AIMessage（默认 False） |

```python
# include_raw=True 时返回字典，包含原始响应和解析结果
structured_llm = llm.with_structured_output(MovieRecommendation, include_raw=True)
result = structured_llm.invoke("推荐一部科幻电影")
print(result["raw"])     # AIMessage 原始响应
print(result["parsed"])  # Pydantic 对象
```

---

### 5.5 CommaSeparatedListOutputParser —— 逗号分隔列表

```python
from langchain.output_parsers import CommaSeparatedListOutputParser

parser = CommaSeparatedListOutputParser()

# 获取格式说明
print(parser.get_format_instructions())
# "Your response should be a list of comma separated values, eg: `foo, bar, baz`"

# 解析
result = parser.parse("苹果, 香蕉, 橘子, 葡萄")
print(result)  # ['苹果', '香蕉', '橘子', '葡萄']
```

---

## 六、LCEL（LangChain Expression Language）—— 链的构建

LCEL 是 LangChain 的核心组合方式，通过 `|` 操作符将组件串联成链。

### 6.1 基础管道

```python
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

# 定义组件
prompt = ChatPromptTemplate.from_template("将以下内容翻译成{language}：{text}")
llm = ChatOpenAI(model="gpt-4o", temperature=0)
parser = StrOutputParser()

# 使用 | 创建链
# 数据流：输入dict -> prompt格式化 -> llm生成 -> parser提取文本 -> 字符串输出
chain = prompt | llm | parser

# ===== invoke：同步调用 =====
result = chain.invoke({"language": "英语", "text": "你好世界"})
print(result)  # "Hello World"

# ===== stream：流式输出 =====
for chunk in chain.stream({"language": "日语", "text": "你好世界"}):
    print(chunk, end="", flush=True)

# ===== batch：批量调用（并行） =====
results = chain.batch([
    {"language": "法语", "text": "你好"},
    {"language": "德语", "text": "你好"},
    {"language": "韩语", "text": "你好"},
])
print(results)  # ['Bonjour', 'Hallo', '안녕하세요']

# ===== 异步版本 =====
import asyncio

async def async_demo():
    # ainvoke
    result = await chain.ainvoke({"language": "西班牙语", "text": "你好"})
    print(result)
    
    # astream
    async for chunk in chain.astream({"language": "意大利语", "text": "你好"}):
        print(chunk, end="")
    
    # abatch
    results = await chain.abatch([
        {"language": "葡萄牙语", "text": "你好"},
        {"language": "俄语", "text": "你好"},
    ])
    print(results)

asyncio.run(async_demo())
```

**LCEL Runnable 通用方法汇总：**

| 方法 | 说明 | 返回值 |
|------|------|--------|
| `invoke(input)` | 同步单次调用 | 单个输出 |
| `stream(input)` | 流式输出 | Iterator |
| `batch(inputs, config)` | 批量并行调用 | List |
| `ainvoke(input)` | 异步单次调用 | Awaitable |
| `astream(input)` | 异步流式输出 | AsyncIterator |
| `abatch(inputs)` | 异步批量调用 | Awaitable[List] |
| `with_retry(...)` | 添加重试逻辑 | 新 Runnable |
| `with_fallbacks([...])` | 添加降级链 | 新 Runnable |
| `with_config(config)` | 绑定配置 | 新 Runnable |
| `bind(**kwargs)` | 绑定固定参数 | 新 Runnable |

---

### 6.2 RunnablePassthrough —— 透传 / 数据穿越

```python
from langchain_core.runnables import RunnablePassthrough

# RunnablePassthrough 将输入原样传递到下游
# 它是 LCEL 中最常用的辅助工具之一

# ===== 基础用法：原样透传 =====
passthrough = RunnablePassthrough()
result = passthrough.invoke({"name": "小明", "age": 25})
print(result)  # {'name': '小明', 'age': 25}  原样返回

# ===== assign()：在原始输入上添加新字段 =====
from langchain_core.runnables import RunnablePassthrough
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# assign 会在原有输入字典基础上添加新的 key
chain = RunnablePassthrough.assign(
    word_count=lambda x: len(x["text"].split()),  # 添加字数统计
    upper_text=lambda x: x["text"].upper(),        # 添加大写版本
)

result = chain.invoke({"text": "hello world foo bar"})
print(result)
# {'text': 'hello world foo bar', 'word_count': 4, 'upper_text': 'HELLO WORLD FOO BAR'}
```

**RAG 中的典型用法：**

```python
from langchain_core.runnables import RunnablePassthrough
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from langchain_core.output_parsers import StrOutputParser

# 假设已有 retriever 和 format_docs 函数
# rag_chain = (
#     {"context": retriever | format_docs, "question": RunnablePassthrough()}
#     | rag_prompt
#     | llm
#     | StrOutputParser()
# )
# 
# 这里 RunnablePassthrough() 将用户的原始问题直接透传为 "question" 字段
# 而 "context" 字段则通过 retriever 检索并格式化
```

---

### 6.3 RunnableLambda —— 自定义函数包装

```python
from langchain_core.runnables import RunnableLambda

# RunnableLambda 将任意 Python 函数包装为 Runnable 组件
# 使其可以参与 LCEL 管道

def add_prefix(text: str) -> str:
    """给文本加前缀"""
    return f"[处理后] {text}"

def count_chars(text: str) -> dict:
    """统计字符"""
    return {"text": text, "length": len(text)}

# 方式一：显式包装
chain = RunnableLambda(add_prefix) | RunnableLambda(count_chars)
result = chain.invoke("Hello World")
print(result)  # {'text': '[处理后] Hello World', 'length': 16}

# 方式二：用装饰器 @chain
from langchain_core.runnables import chain as chain_decorator

@chain_decorator
def my_custom_chain(input_data: dict) -> str:
    """自定义处理逻辑"""
    name = input_data["name"]
    topic = input_data["topic"]
    return f"{name}想学习{topic}"

result = my_custom_chain.invoke({"name": "小明", "topic": "Raft算法"})
print(result)  # "小明想学习Raft算法"
```

---

### 6.4 RunnableParallel —— 并行执行多个分支

```python
from langchain_core.runnables import RunnableParallel
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from langchain_core.output_parsers import StrOutputParser

llm = ChatOpenAI(model="gpt-4o", temperature=0)
parser = StrOutputParser()

# RunnableParallel 对同一输入并行执行多个子链
# 每个子链独立运行，结果合并为字典
analysis_chain = RunnableParallel(
    summary=ChatPromptTemplate.from_template("一句话总结：{text}") | llm | parser,
    keywords=ChatPromptTemplate.from_template("提取3个关键词（逗号分隔）：{text}") | llm | parser,
    sentiment=ChatPromptTemplate.from_template("情感分析（正面/负面/中性）：{text}") | llm | parser,
    language=ChatPromptTemplate.from_template("检测语言：{text}") | llm | parser,
)

# 一次输入，并行得到四个维度的分析
result = analysis_chain.invoke({"text": "今天天气真好，我和朋友去公园散步了，心情很愉快"})

print(f"摘要: {result['summary']}")
print(f"关键词: {result['keywords']}")
print(f"情感: {result['sentiment']}")
print(f"语言: {result['language']}")
```

---

### 6.5 RunnableBranch —— 条件分支路由

```python
from langchain_core.runnables import RunnableBranch
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from langchain_core.output_parsers import StrOutputParser

llm = ChatOpenAI(model="gpt-4o", temperature=0)
parser = StrOutputParser()

# 针对不同主题使用不同 prompt 的链
math_chain = ChatPromptTemplate.from_template(
    "你是数学老师。请详细解答：{input}"
) | llm | parser

code_chain = ChatPromptTemplate.from_template(
    "你是编程导师。请给出代码示例并解释：{input}"
) | llm | parser

general_chain = ChatPromptTemplate.from_template(
    "请回答以下问题：{input}"
) | llm | parser

# RunnableBranch 根据条件选择分支
# 参数：若干 (条件函数, 对应链) 的元组，最后一个是默认分支
branch = RunnableBranch(
    (lambda x: "数学" in x.get("topic", ""), math_chain),
    (lambda x: "编程" in x.get("topic", "") or "代码" in x.get("topic", ""), code_chain),
    general_chain,  # 默认分支
)

# 测试
print(branch.invoke({"topic": "数学", "input": "什么是微积分？"}))
print(branch.invoke({"topic": "编程", "input": "什么是递归？"}))
print(branch.invoke({"topic": "历史", "input": "唐朝首都在哪？"}))
```

---

### 6.6 bind() —— 绑定固定参数

```python
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# bind() 可以为后续调用绑定固定参数
# 典型场景：绑定 tools（工具）、stop（停止词）等

# 绑定停止词
llm_with_stop = llm.bind(stop=["\n"])  # 遇到换行符就停止

# 绑定工具（function calling）
from langchain_core.tools import tool

@tool
def multiply(a: int, b: int) -> int:
    """两个数相乘"""
    return a * b

# 将工具绑定到模型
llm_with_tools = llm.bind_tools([multiply])

# 调用时模型会自动决定是否使用工具
from langchain_core.messages import HumanMessage
response = llm_with_tools.invoke([HumanMessage(content="3乘以7等于多少？")])
print(response.tool_calls)  # [{'name': 'multiply', 'args': {'a': 3, 'b': 7}, 'id': '...'}]
```

---

## 七、Memory（记忆）

### 7.1 ConversationBufferMemory —— 全量缓冲记忆

保存完整对话历史，适合短对话。

```python
from langchain.memory import ConversationBufferMemory

# 参数说明：
# memory_key: str      - 在链上下文中的键名（默认 "history"）
# return_messages: bool - True 返回 Message 对象列表，False 返回格式化字符串
# input_key: str       - 输入键名（多输入时需要指定）
# output_key: str      - 输出键名（多输出时需要指定）
# human_prefix: str    - 人类消息前缀（默认 "Human"）
# ai_prefix: str       - AI 消息前缀（默认 "AI"）
memory = ConversationBufferMemory(
    memory_key="chat_history",
    return_messages=True,
)

# 保存对话
memory.save_context(
    {"input": "你好，我是小明"},          # 用户输入
    {"output": "你好小明！很高兴认识你"},   # AI 输出
)
memory.save_context(
    {"input": "我在学习分布式系统"},
    {"output": "分布式系统是个很好的方向！"},
)

# 加载记忆
variables = memory.load_memory_variables({})
print(variables["chat_history"])
# [HumanMessage(content='你好，我是小明'), AIMessage(content='你好小明！很高兴认识你'), ...]

# 清空记忆
memory.clear()
```

---

### 7.2 ConversationBufferWindowMemory —— 滑动窗口记忆

只保留最近 k 轮对话，防止 token 溢出。

```python
from langchain.memory import ConversationBufferWindowMemory

# k: 保留最近 k 轮（一轮 = Human + AI 各一条）
memory = ConversationBufferWindowMemory(
    k=3,                          # 只保留最近 3 轮
    memory_key="chat_history",
    return_messages=True,
)

# 保存 5 轮对话
for i in range(5):
    memory.save_context(
        {"input": f"第{i+1}轮用户说的话"},
        {"output": f"第{i+1}轮AI的回复"},
    )

# 只保留最近 3 轮（第3/4/5轮）
result = memory.load_memory_variables({})
messages = result["chat_history"]
print(f"消息数: {len(messages)}")  # 6 (3轮 × 2条/轮)
print(messages[0].content)         # "第3轮用户说的话"
```

---

### 7.3 ConversationSummaryMemory —— 摘要记忆

使用 LLM 将对话历史压缩为摘要，适合长对话。

```python
from langchain.memory import ConversationSummaryMemory
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(temperature=0)

# llm: 用于生成摘要的模型
# buffer: 初始摘要（可选）
memory = ConversationSummaryMemory(
    llm=llm,
    memory_key="chat_history",
    return_messages=True,
)

# 保存多轮对话后，记忆会被压缩为摘要
memory.save_context(
    {"input": "我在做一个电商网站，用 React 前端，Node.js 后端"},
    {"output": "React + Node.js 是流行的全栈组合，很适合电商场景"},
)
memory.save_context(
    {"input": "我遇到了跨域问题，CORS 报错"},
    {"output": "你可以在 Node.js 后端安装 cors 中间件来解决"},
)
memory.save_context(
    {"input": "解决了！现在想加购物车功能"},
    {"output": "购物车可以用 Redis 做缓存，或者直接用数据库存储"},
)

# 加载摘要
result = memory.load_memory_variables({})
print(result["chat_history"])  # 压缩后的摘要文本
```

---

### 7.4 ConversationTokenBufferMemory —— Token 限制记忆

按 token 数量限制记忆容量。

```python
from langchain.memory import ConversationTokenBufferMemory
from langchain_openai import ChatOpenAI

llm = ChatOpenAI()

# max_token_limit: 记忆中最多保留的 token 数
# 超出限制时从最早的消息开始丢弃
memory = ConversationTokenBufferMemory(
    llm=llm,                       # 用于 token 计数
    max_token_limit=300,           # 最多 300 个 token
    memory_key="chat_history",
    return_messages=True,
)
```

---

### 7.5 在 LCEL 链中使用 Memory

```python
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnablePassthrough
from langchain_core.chat_history import InMemoryChatMessageHistory
from langchain_core.runnables.history import RunnableWithMessageHistory

llm = ChatOpenAI(model="gpt-4o", temperature=0.7)

# 创建 prompt（带历史消息占位符）
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个友好的助手"),
    MessagesPlaceholder(variable_name="history"),
    ("human", "{input}"),
])

chain = prompt | llm | StrOutputParser()

# 创建会话存储（每个 session_id 独立存储）
store = {}

def get_session_history(session_id: str):
    if session_id not in store:
        store[session_id] = InMemoryChatMessageHistory()
    return store[session_id]

# RunnableWithMessageHistory 自动管理对话历史
chain_with_history = RunnableWithMessageHistory(
    chain,
    get_session_history,                # 获取历史的函数
    input_messages_key="input",         # 输入键名
    history_messages_key="history",     # 历史消息键名
)

# 使用时需要指定 session_id
config = {"configurable": {"session_id": "user_001"}}

# 多轮对话
print(chain_with_history.invoke({"input": "你好，我叫张三"}, config=config))
print(chain_with_history.invoke({"input": "我叫什么名字？"}, config=config))
# AI 会记住你叫张三
```

---

## 八、Tools & Agents（工具与代理）

### 8.1 定义 Tool（工具）

```python
from langchain_core.tools import tool, StructuredTool, ToolException
from pydantic import BaseModel, Field

# ===== 方式一：@tool 装饰器（最简单） =====
@tool
def search_web(query: str) -> str:
    """搜索互联网获取信息。当需要查找最新资讯或实时数据时使用。
    
    Args:
        query: 搜索关键词
    """
    # 这里是模拟实现
    return f"搜索结果：关于 '{query}' 的信息..."

@tool
def calculator(expression: str) -> str:
    """计算数学表达式。支持加减乘除、括号、乘方。
    
    Args:
        expression: 数学表达式字符串，如 '2 + 3 * 4'
    """
    try:
        result = eval(expression)
        return f"计算结果: {result}"
    except Exception as e:
        raise ToolException(f"计算失败: {e}")

# 查看工具信息
print(f"名称: {search_web.name}")            # "search_web"
print(f"描述: {search_web.description}")      # docstring 的第一行
print(f"参数: {search_web.args}")             # {'query': {'title': 'Query', 'type': 'string'}}

# ===== 方式二：StructuredTool（多参数、自定义 schema） =====
class WeatherInput(BaseModel):
    """天气查询参数"""
    city: str = Field(description="城市名称")
    unit: str = Field(default="celsius", description="温度单位: celsius 或 fahrenheit")

def get_weather(city: str, unit: str = "celsius") -> str:
    """查询天气"""
    return f"{city}天气：晴，25°{'C' if unit == 'celsius' else 'F'}"

weather_tool = StructuredTool.from_function(
    func=get_weather,                   # 底层函数
    name="get_weather",                 # 工具名
    description="查询城市天气",          # 描述（LLM 据此判断何时使用）
    args_schema=WeatherInput,           # 参数 schema
    return_direct=False,                # True: 直接返回结果，不再让 LLM 润色
    handle_tool_error=True,             # 自动捕获异常并返回错误信息
)

# ===== 方式三：继承 BaseTool（完全自定义） =====
from langchain_core.tools import BaseTool
from typing import Optional, Type

class DatabaseQueryTool(BaseTool):
    name: str = "database_query"
    description: str = "执行SQL查询"
    args_schema: Type[BaseModel] = None  # 可指定输入 schema
    
    def _run(self, query: str) -> str:
        """同步执行"""
        # 实际数据库查询逻辑
        return f"查询结果: {query}"
    
    async def _arun(self, query: str) -> str:
        """异步执行"""
        return f"异步查询结果: {query}"
```

---

### 8.2 创建 Agent（代理）

```python
from langchain_openai import ChatOpenAI
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# 准备工具
tools = [search_web, calculator, weather_tool]

# Agent Prompt（必须包含 agent_scratchpad 占位符）
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个有用的助手，可以使用工具帮助用户。回答使用中文。"),
    MessagesPlaceholder(variable_name="chat_history", optional=True),
    ("human", "{input}"),
    MessagesPlaceholder(variable_name="agent_scratchpad"),  # Agent 思考/工具调用过程
])

# 创建 Agent（推荐 create_tool_calling_agent）
agent = create_tool_calling_agent(
    llm=llm,       # 语言模型
    tools=tools,   # 可用工具列表
    prompt=prompt, # 提示词模板
)

# AgentExecutor 驱动 Agent 的 think-act-observe 循环
# 参数说明：
# agent: Agent 实例
# tools: 工具列表（需与创建 agent 时一致）
# verbose: bool - 打印思考过程
# max_iterations: int - 最大循环次数（默认15，防无限循环）
# max_execution_time: float - 最大执行时间（秒）
# handle_parsing_errors: bool/str - 处理解析错误
# return_intermediate_steps: bool - 是否返回中间步骤
# early_stopping_method: str - "force"(强制停止) 或 "generate"(让LLM总结)
executor = AgentExecutor(
    agent=agent,
    tools=tools,
    verbose=True,                      # 打印详细执行过程
    max_iterations=10,                 # 最多 10 轮工具调用
    handle_parsing_errors=True,        # 自动处理解析错误
    return_intermediate_steps=True,    # 返回中间步骤
)

# 执行 Agent
result = executor.invoke({"input": "北京今天天气怎么样？顺便帮我算一下 123 * 456"})
print(result["output"])                # 最终回答
print(f"步骤数: {len(result['intermediate_steps'])}")
```

---

### 8.3 ReAct Agent（Reasoning + Acting）

```python
from langchain.agents import create_react_agent, AgentExecutor
from langchain import hub
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# ReAct 模式：Thought -> Action -> Observation 循环
# 从 Hub 拉取标准 ReAct prompt
prompt = hub.pull("hwchase17/react")

# 创建 ReAct Agent
react_agent = create_react_agent(llm=llm, tools=tools, prompt=prompt)

executor = AgentExecutor(
    agent=react_agent,
    tools=tools,
    verbose=True,
    handle_parsing_errors=True,
    max_iterations=8,
)

# ReAct 会显式打印思考过程
result = executor.invoke({"input": "帮我查一下今天上海的天气，然后算一下温度的华氏值"})
print(result["output"])
```

---

### 8.4 带记忆的 Agent

```python
from langchain_openai import ChatOpenAI
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain.memory import ConversationBufferMemory

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# 带 chat_history 的 prompt
prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个有帮助的助手"),
    MessagesPlaceholder(variable_name="chat_history"),   # 对话历史
    ("human", "{input}"),
    MessagesPlaceholder(variable_name="agent_scratchpad"),
])

# 创建记忆（注意 memory_key 要和 prompt 中的变量名一致）
memory = ConversationBufferMemory(
    memory_key="chat_history",
    return_messages=True,
)

agent = create_tool_calling_agent(llm=llm, tools=tools, prompt=prompt)

executor = AgentExecutor(
    agent=agent,
    tools=tools,
    memory=memory,     # 绑定记忆
    verbose=True,
)

# 多轮对话，Agent 会记住上下文
print(executor.invoke({"input": "我叫小明"})["output"])
print(executor.invoke({"input": "我叫什么？"})["output"])  # 会记住
```

---

## 九、Retrievers & RAG（检索增强生成）

### 9.1 Document 与 DocumentLoader

```python
from langchain_core.documents import Document

# Document 是 LangChain 中文档的基本单位
doc = Document(
    page_content="这是文档内容...",     # 文本内容
    metadata={                         # 元数据（来源、页码等）
        "source": "example.pdf",
        "page": 1,
        "author": "张三",
    },
)

# ===== 常用 DocumentLoader =====
from langchain_community.document_loaders import (
    TextLoader,          # 纯文本文件
    PyPDFLoader,         # PDF 文件
    Docx2txtLoader,      # Word 文档
    CSVLoader,           # CSV 文件
    WebBaseLoader,       # 网页内容
    DirectoryLoader,     # 整个目录
)

# 加载文本文件
loader = TextLoader("./data.txt", encoding="utf-8")
docs = loader.load()  # 返回 List[Document]

# 加载 PDF（每页一个 Document）
pdf_loader = PyPDFLoader("./paper.pdf")
pdf_docs = pdf_loader.load()
print(f"PDF 共 {len(pdf_docs)} 页")
print(pdf_docs[0].metadata)  # {'source': './paper.pdf', 'page': 0}

# 加载网页
web_loader = WebBaseLoader("https://docs.python.org/3/tutorial/")
web_docs = web_loader.load()

# 加载整个目录
dir_loader = DirectoryLoader(
    "./docs/",                   # 目录路径
    glob="**/*.md",              # 文件匹配模式
    loader_cls=TextLoader,       # 使用的加载器类
    loader_kwargs={"encoding": "utf-8"},
)
all_docs = dir_loader.load()
```

---

### 9.2 TextSplitter —— 文本分割器

```python
from langchain.text_splitter import (
    RecursiveCharacterTextSplitter,
    CharacterTextSplitter,
    TokenTextSplitter,
    MarkdownHeaderTextSplitter,
)

# ===== RecursiveCharacterTextSplitter（最常用） =====
# 递归地按分隔符优先级列表分割，尽量保持语义完整性
splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,        # 每块最大字符数
    chunk_overlap=50,      # 相邻块重叠字符数（保证上下文连贯）
    separators=[           # 分隔符优先级（从高到低尝试）
        "\n\n",            # 优先按段落分
        "\n",              # 其次按行分
        "。", "！", "？",   # 再按句号分（中文友好）
        ".", "!", "?",     # 英文句号
        " ",               # 空格
        "",                # 最后按字符分
    ],
    length_function=len,   # 长度计算函数
    is_separator_regex=False,  # 分隔符是否为正则
)

text = "这是一段很长的文本..." * 100
chunks = splitter.split_text(text)
print(f"分割为 {len(chunks)} 块")
print(f"第一块长度: {len(chunks[0])}")

# 也可以直接分割 Document 列表
doc_chunks = splitter.split_documents(docs)

# ===== MarkdownHeaderTextSplitter（按 Markdown 标题分割） =====
md_splitter = MarkdownHeaderTextSplitter(
    headers_to_split_on=[
        ("#", "h1"),           # 一级标题
        ("##", "h2"),          # 二级标题
        ("###", "h3"),         # 三级标题
    ]
)
md_text = """
# 第一章
内容1...

## 1.1 节
内容1.1...

## 1.2 节
内容1.2...

# 第二章
内容2...
"""
md_chunks = md_splitter.split_text(md_text)
for chunk in md_chunks:
    print(f"内容: {chunk.page_content[:30]}...")
    print(f"元数据: {chunk.metadata}")
```

---

### 9.3 VectorStore —— 向量存储

```python
from langchain_community.vectorstores import FAISS, Chroma
from langchain_openai import OpenAIEmbeddings
from langchain_core.documents import Document

# Embedding 模型
embeddings = OpenAIEmbeddings(model="text-embedding-3-small")

# 准备文档
docs = [
    Document(page_content="LangChain 是一个 AI 应用开发框架", metadata={"id": 1}),
    Document(page_content="FAISS 是 Facebook 开源的向量检索库", metadata={"id": 2}),
    Document(page_content="Python 是最流行的编程语言之一", metadata={"id": 3}),
    Document(page_content="Raft 是一种分布式共识算法", metadata={"id": 4}),
    Document(page_content="向量数据库用于存储和检索 embedding 向量", metadata={"id": 5}),
]

# ===== FAISS 向量存储 =====
# from_documents: 从文档列表创建向量存储
vectorstore = FAISS.from_documents(
    documents=docs,
    embedding=embeddings,
)

# 相似度搜索
results = vectorstore.similarity_search(
    query="什么是向量数据库",    # 查询文本
    k=3,                        # 返回 top-k 个结果
)
for doc in results:
    print(f"[相关度排序] {doc.page_content}")

# 带分数的搜索
results_with_score = vectorstore.similarity_search_with_score(
    query="分布式算法",
    k=2,
)
for doc, score in results_with_score:
    print(f"[分数: {score:.4f}] {doc.page_content}")

# 保存/加载向量存储
vectorstore.save_local("./faiss_index")                  # 保存到本地
loaded_vs = FAISS.load_local("./faiss_index", embeddings, allow_dangerous_deserialization=True)

# ===== 转为 Retriever =====
retriever = vectorstore.as_retriever(
    search_type="similarity",      # 搜索类型
    search_kwargs={"k": 3},        # 搜索参数
)

# search_type 可选值：
# - "similarity": 纯余弦相似度
# - "mmr": 最大边际相关性（兼顾相关性和多样性）
# - "similarity_score_threshold": 按相似度阈值过滤

# MMR 检索器（减少冗余）
mmr_retriever = vectorstore.as_retriever(
    search_type="mmr",
    search_kwargs={
        "k": 4,                    # 最终返回数量
        "fetch_k": 20,             # 初始候选数量
        "lambda_mult": 0.5,        # 多样性参数（0=最多样，1=最相关）
    },
)
```

---

### 9.4 完整 RAG 流水线

```python
"""
完整 RAG 流水线：加载 -> 分割 -> 向量化 -> 检索 -> 生成
"""
from langchain_community.document_loaders import TextLoader
from langchain.text_splitter import RecursiveCharacterTextSplitter
from langchain_community.vectorstores import FAISS
from langchain_openai import OpenAIEmbeddings, ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnablePassthrough

# === Step 1: 加载文档 ===
loader = TextLoader("./knowledge_base.txt", encoding="utf-8")
docs = loader.load()

# === Step 2: 分割文本 ===
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
chunks = splitter.split_documents(docs)
print(f"共 {len(chunks)} 个文本块")

# === Step 3: 创建向量存储 ===
embeddings = OpenAIEmbeddings()
vectorstore = FAISS.from_documents(chunks, embeddings)
retriever = vectorstore.as_retriever(search_kwargs={"k": 3})

# === Step 4: 构建 RAG 链 ===
llm = ChatOpenAI(model="gpt-4o", temperature=0)

rag_prompt = ChatPromptTemplate.from_template("""
基于以下参考资料回答问题。如果资料中没有相关信息，请诚实地说"根据已有资料无法回答"。

参考资料：
{context}

问题：{question}

回答：""")

def format_docs(docs):
    """将 Document 列表格式化为字符串"""
    return "\n\n---\n\n".join(doc.page_content for doc in docs)

# 完整 RAG 链
rag_chain = (
    {
        "context": retriever | format_docs,       # 检索 + 格式化
        "question": RunnablePassthrough(),        # 原始问题透传
    }
    | rag_prompt                                  # 填充 prompt
    | llm                                         # 生成回答
    | StrOutputParser()                           # 提取文本
)

# === Step 5: 问答 ===
answer = rag_chain.invoke("什么是 LangChain？")
print(answer)
```

---

## 十、Callbacks（回调机制）

```python
from langchain.callbacks.base import BaseCallbackHandler
from langchain.callbacks import get_openai_callback
from langchain_openai import ChatOpenAI
import time

# ===== 自定义回调处理器 =====
class TimingCallback(BaseCallbackHandler):
    """记录耗时和 token 使用的回调"""
    
    def on_llm_start(self, serialized, prompts, **kwargs):
        """LLM 调用开始"""
        self.start = time.time()
        print(f"🚀 开始调用 LLM...")
    
    def on_llm_end(self, response, **kwargs):
        """LLM 调用结束"""
        elapsed = time.time() - self.start
        print(f"✅ 完成，耗时 {elapsed:.2f}s")
    
    def on_llm_error(self, error, **kwargs):
        """LLM 调用出错"""
        print(f"❌ 错误: {error}")
    
    def on_chain_start(self, serialized, inputs, **kwargs):
        """Chain 开始"""
        print(f"⛓️ Chain 开始执行")
    
    def on_chain_end(self, outputs, **kwargs):
        """Chain 结束"""
        print(f"⛓️ Chain 执行完成")
    
    def on_tool_start(self, serialized, input_str, **kwargs):
        """Tool 开始"""
        print(f"🔧 调用工具: {serialized.get('name', '?')}")
    
    def on_tool_end(self, output, **kwargs):
        """Tool 结束"""
        print(f"🔧 工具返回: {output[:100]}")

# 使用方式一：绑定到 LLM
llm = ChatOpenAI(callbacks=[TimingCallback()])

# 使用方式二：在调用时传入
# result = chain.invoke(input, config={"callbacks": [TimingCallback()]})

# ===== Token 使用和费用追踪 =====
llm = ChatOpenAI(model="gpt-4o")

with get_openai_callback() as cb:
    result1 = llm.invoke("什么是人工智能？")
    result2 = llm.invoke("什么是深度学习？")
    
    print(f"总 Token: {cb.total_tokens}")
    print(f"Prompt Token: {cb.prompt_tokens}")
    print(f"Completion Token: {cb.completion_tokens}")
    print(f"总费用: ${cb.total_cost:.6f}")
    print(f"成功调用次数: {cb.successful_requests}")
```

---

## 十一、实用技巧与高级用法

### 11.1 重试与容错

```python
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser

# LLM 内置重试
llm = ChatOpenAI(max_retries=3, request_timeout=30)

# 链级别的重试
prompt = ChatPromptTemplate.from_template("{input}")
chain = prompt | llm | StrOutputParser()

chain_with_retry = chain.with_retry(
    stop_after_attempt=3,           # 最多 3 次
    wait_exponential_jitter=True,   # 指数退避 + 随机抖动
)
```

---

### 11.2 Fallback（降级备选）

```python
from langchain_openai import ChatOpenAI

# 主模型
primary = ChatOpenAI(model="gpt-4o", temperature=0)
# 备用模型
fallback = ChatOpenAI(model="gpt-3.5-turbo", temperature=0)

# 主模型失败时自动切换到备用模型
llm_with_fallback = primary.with_fallbacks([fallback])

# 在链中使用
chain = prompt | llm_with_fallback | StrOutputParser()
```

---

### 11.3 缓存

```python
from langchain.globals import set_llm_cache
from langchain_community.cache import InMemoryCache, SQLiteCache

# 内存缓存（进程级别）
set_llm_cache(InMemoryCache())

# SQLite 持久化缓存
set_llm_cache(SQLiteCache(database_path=".langchain_cache.db"))

# 设置后，相同输入不会重复调用 API，直接返回缓存结果
```

---

### 11.4 配置与运行时参数

```python
from langchain_openai import ChatOpenAI
from langchain_core.prompts import ChatPromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import ConfigurableField

llm = ChatOpenAI(model="gpt-4o", temperature=0)

# 使 temperature 可在运行时配置
configurable_llm = llm.configurable_fields(
    temperature=ConfigurableField(
        id="llm_temperature",
        name="Temperature",
        description="控制输出随机性",
    )
)

chain = ChatPromptTemplate.from_template("{input}") | configurable_llm | StrOutputParser()

# 运行时通过 config 覆盖参数
result = chain.invoke(
    {"input": "写一首诗"},
    config={"configurable": {"llm_temperature": 0.9}},  # 运行时设为 0.9
)
```

---

### 11.5 调试与可观测性

```python
from langchain.globals import set_debug, set_verbose

# 开启全局 debug（打印所有组件的输入输出）
set_debug(True)

# 开启全局 verbose（打印关键步骤）
set_verbose(True)

# 查看链的结构
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI
from langchain_core.output_parsers import StrOutputParser

chain = ChatPromptTemplate.from_template("{x}") | ChatOpenAI() | StrOutputParser()

# 打印链的结构图
chain.get_graph().print_ascii()

# 获取链的输入/输出 schema
print(chain.input_schema.schema())
print(chain.output_schema.schema())
```

---

## 十二、完整实战示例：多功能对话 Agent

```python
"""
一个完整的多功能 Agent 示例：
- 支持多轮对话记忆
- 集成搜索、计算、天气工具
- 带回调监控
- 支持流式输出
"""
from langchain_openai import ChatOpenAI
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.tools import tool
from langchain.memory import ConversationBufferWindowMemory
from langchain.callbacks.base import BaseCallbackHandler

# === 定义工具 ===
@tool
def web_search(query: str) -> str:
    """搜索互联网获取最新信息"""
    return f"[搜索结果] 关于'{query}'：这是模拟的搜索结果..."

@tool
def math_calculator(expression: str) -> str:
    """计算数学表达式，支持 +, -, *, /, **, ()"""
    try:
        return f"结果: {eval(expression)}"
    except:
        return "表达式无效"

@tool
def get_current_weather(city: str) -> str:
    """查询城市天气"""
    # 模拟
    import random
    temp = random.randint(15, 35)
    return f"{city}: 晴, {temp}°C, 湿度60%"

# === 回调 ===
class StreamCallback(BaseCallbackHandler):
    def on_llm_new_token(self, token: str, **kwargs):
        print(token, end="", flush=True)

# === 组装 Agent ===
llm = ChatOpenAI(
    model="gpt-4o",
    temperature=0.3,
    streaming=True,
    callbacks=[StreamCallback()],
)

tools = [web_search, math_calculator, get_current_weather]

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个全能助手。使用中文回答。当前日期是2025年。"),
    MessagesPlaceholder("chat_history"),
    ("human", "{input}"),
    MessagesPlaceholder("agent_scratchpad"),
])

memory = ConversationBufferWindowMemory(
    k=5,
    memory_key="chat_history",
    return_messages=True,
)

agent = create_tool_calling_agent(llm=llm, tools=tools, prompt=prompt)

executor = AgentExecutor(
    agent=agent,
    tools=tools,
    memory=memory,
    verbose=True,
    max_iterations=8,
    handle_parsing_errors=True,
)

# === 对话循环 ===
if __name__ == "__main__":
    print("=== 多功能 Agent 已启动 ===")
    print("输入 'quit' 退出\n")
    
    while True:
        user_input = input("\n你: ")
        if user_input.lower() in ["quit", "exit", "q"]:
            break
        
        result = executor.invoke({"input": user_input})
        print(f"\nAI: {result['output']}")
```

---

本文档覆盖了 LangChain 框架的核心模块、常用 API、参数说明和实战 Demo。建议结合官方文档 https://python.langchain.com/docs/ 一起使用。LangChain 的版本迭代较快，部分旧 API（如 LLMChain、ConversationChain）正逐步被 LCEL 方式取代，新项目推荐优先使用 LCEL 管道语法。
