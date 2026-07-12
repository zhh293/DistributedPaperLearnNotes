# 实战：自己实现 Agent 记忆和上下文管理系统

> 本文从"自己做 Agent"的视角，把整套记忆 + 上下文管理讲清楚。
> 核心问题：历史记录在哪里？什么时候保存？如何实现？

---

## 〇、先把层次理清：会话、对话、循环

这三个概念是嵌套关系，很多人搞混了就会觉得整个系统"驴头不照马嘴"。先把它们定义清楚：

```
┌═══════════════════════════════════════════════════════════════════┐
║  Session（会话）                                                   ║
║  ═══════════════                                                   ║
║  定义：用户打开终端执行 `claude` 命令到关闭终端。                    ║
║  生命周期：分钟到数小时。                                           ║
║  数据：一个 messages[] 数组，从空开始，持续增长。                    ║
║                                                                    ║
║  ┌───────────────────────────────────────────────────────────┐   ║
║  │  Turn（对话轮次）                                           │   ║
║  │  ═══════════════                                            │   ║
║  │  定义：用户提一个问题/需求，到 Agent 完整回复。               │   ║
║  │  一个 Session 中有多个 Turn。                                │   ║
║  │                                                              │   ║
║  │  ┌───────────────────────────────────────────────────┐     │   ║
║  │  │  Agentic Loop（Agent 循环）                         │     │   ║
║  │  │  ═════════════════════════                          │     │   ║
║  │  │  定义：一次 Turn 内部，Agent 多次调用 LLM。          │     │   ║
║  │  │  每次调用可能触发工具，工具结果再喂回去继续调用。     │     │   ║
║  │  │  直到 Agent 决定"完成了"，Turn 结束。               │     │   ║
║  │  │                                                      │     │   ║
║  │  │  Loop 1: 用户问题 → LLM → 决定调工具 read_file      │     │   ║
║  │  │  Loop 2: 工具结果 → LLM → 决定调工具 write_file     │     │   ║
║  │  │  Loop 3: 工具结果 → LLM → 决定输出最终文本回复       │     │   ║
║  │  │  （Turn 结束）                                       │     │   ║
║  │  └───────────────────────────────────────────────────┘     │   ║
║  └───────────────────────────────────────────────────────────┘   ║
║                                                                    ║
║  Turn 1: "帮我看看 main.py" → [3次循环] → 回复                    ║
║  Turn 2: "把那个函数改一下" → [5次循环] → 回复                     ║
║  Turn 3: "记住：以后用 TypeScript" → [1次循环] → 回复              ║
║  ...                                                               ║
║  Session 结束                                                      ║
╚═══════════════════════════════════════════════════════════════════╝
```

### 每一层分别管理什么？

| 层次 | 管理的数据 | 保存位置 | 生命周期 |
|------|-----------|---------|---------|
| Session（会话） | messages[] 完整数组 | 内存中（可选持久化到 session.json） | 打开→关闭终端 |
| Turn（对话轮次） | 一次用户输入 + Agent 的完整回复链 | 作为 messages[] 的一段 | 用户提问→Agent 回完 |
| Agentic Loop（循环） | 一次 LLM API 调用 + 可能的工具执行 | 每次调用的结果都 push 到 messages[] | 一次 API round-trip |

### 历史记录的核心事实

**messages[] 就是历史记录，它一直在内存里，不存在"加载历史"这回事。**

每次 Agentic Loop 调用 API 时，会把 **整个 messages[]** 传过去。LLM 是无状态的，它能"记住上文"完全是因为每次调用时你都把完整历史递给了它。

---

## 一、Session 层：初始化与销毁

### 1.1 Session 开始时做了什么

```
用户执行 `claude` 命令
        │
        ▼
┌─────────────────────────────────────┐
│  Session 初始化（只做一次）            │
│                                       │
│  1. messages = []                     │  ← 空数组
│  2. 组装 System Prompt                │  ← 身份 + 工具说明 + 行为规则
│  3. 加载项目规则（CLAUDE.md）          │  ← 静态文件，memoize
│  4. 加载相关记忆（memory/*.md）        │  ← AI 选择最相关的 5 条
│  5. 注入上下文为第一条 user message    │  ← 规则 + 记忆拼成一条消息
│                                       │
│  此时 messages = [                    │
│    {role: "user", content: "规则+记忆"}│
│    {role: "assistant", content: "OK"} │  ← 占位
│  ]                                    │
└─────────────────────────────────────┘
        │
        ▼
    等待用户第一次输入（进入 Turn 1）
```

### 1.2 Session 结束时做了什么

```
用户关闭终端 / Ctrl+C
        │
        ▼
┌─────────────────────────────────────┐
│  Session 销毁                         │
│                                       │
│  1. messages[] 随进程消亡             │  ← 短期记忆消失
│  2. [可选] 写 session transcript     │  ← 给 AutoDream 整合用
│  3. memory/*.md 文件继续留在磁盘      │  ← 长期记忆留存
│                                       │
│  ★ 下次新 Session：                   │
│    messages = [] 从头来               │
│    但 memory 文件会被重新加载         │
│    所以 Agent "还记得"用户偏好         │
└─────────────────────────────────────┘
```

### 1.3 代码实现

```python
class Agent:
    """Session 级别的 Agent"""
    
    def __init__(self, project_dir: str):
        """Session 开始：初始化所有状态"""
        
        # ★ 核心数据结构：对话历史
        self.messages: list[dict] = []
        
        # 组装 System Prompt（整个 Session 只做一次）
        self.system_prompt = self._build_system_prompt(project_dir)
        
        # 加载记忆并注入为第一条消息
        memory_content = self._load_relevant_memories(project_dir)
        if memory_content:
            self.messages.append({
                "role": "user", 
                "content": f"<context>\n{memory_content}\n</context>"
            })
            self.messages.append({
                "role": "assistant",
                "content": "我已了解项目上下文和之前的记忆。"
            })
    
    def _build_system_prompt(self, project_dir: str) -> str:
        """整个 Session 只计算一次（memoize）"""
        return """你是一个编程助手。
## 工具
- read_file: 读文件
- write_file: 写文件
- run_command: 跑命令
## 规则
- 改代码前先读
- 遵循项目已有风格"""
    
    def _load_relevant_memories(self, project_dir: str) -> str:
        """加载跨 Session 保留的长期记忆"""
        memory_dir = os.path.expanduser(
            f"~/.agent/projects/{project_dir.replace('/', '_')}/memory/"
        )
        # ... 读取 memory/*.md 文件
        # ... AI 选择最相关的 5 条
        return "- 用户偏好 TypeScript\n- 项目使用 ESM"
```

---

## 二、Turn 层：一次完整的用户交互

### 2.1 Turn 的完整流程

一个 Turn = 用户提一个问题 + Agent 完整回答（可能经过多次工具调用）。

```
用户输入: "帮我重构 src/utils.ts"
        │
        ▼
┌─────────────────────────────────────────────────┐
│  Turn 开始                                        │
│                                                   │
│  Step 1: 用户消息入历史                            │
│    messages.push({role:"user", content:"帮我..."}) │
│                                                   │
│  Step 2: 压缩检查（Token 超限？）                  │
│    ├─ 没超 → 跳过                                 │
│    └─ 超了 → 执行压缩管道（详见第四节）            │
│                                                   │
│  Step 3: 进入 Agentic Loop（详见第三节）           │
│    多次调用 LLM + 工具，直到 Agent 输出最终回复     │
│                                                   │
│  Step 4: [后台] 记忆提取                           │
│    forked agent 分析这次 Turn 的内容               │
│    如果有值得记住的 → 写入 memory/*.md             │
│                                                   │
│  Step 5: [可选] 持久化 session 到磁盘              │
│                                                   │
│  Turn 结束，等待用户下一次输入                      │
└─────────────────────────────────────────────────┘
```

### 2.2 代码实现

```python
class Agent:
    # ... __init__ 同上 ...
    
    def handle_turn(self, user_input: str) -> str:
        """
        处理一个 Turn（一次用户提问到完整回复）。
        一个 Session 中会调用多次 handle_turn()。
        """
        
        # === Step 1: 用户消息入历史 ===
        self.messages.append({"role": "user", "content": user_input})
        
        # === Step 2: 压缩检查 ===
        self._compact_if_needed()
        
        # === Step 3: Agentic Loop（核心！） ===
        final_response = self._run_agentic_loop()
        
        # === Step 4: 后台记忆提取 ===
        threading.Thread(
            target=self._extract_memory_background,
            args=(user_input, final_response),
            daemon=True
        ).start()
        
        # === Step 5: 持久化 session ===
        self._save_session_to_disk()
        
        return final_response
```

### 2.3 Turn 与 messages[] 的关系

```
Session 开始后 messages[] 的增长过程：

messages = [
  {user: "<context>记忆...</context>"},     ← Session 初始化时注入
  {assistant: "我已了解上下文"},             ← Session 初始化时注入
  
  // ─── Turn 1 ───
  {user: "帮我看看 main.py"},               ← Turn 1 开始
  {assistant: [tool_use: read_file]},        ← Loop 1
  {user: [tool_result: 文件内容]},           ← Loop 1 工具结果
  {assistant: "这个文件有3个问题..."},       ← Loop 2（最终回复，Turn 1 结束）
  
  // ─── Turn 2 ───
  {user: "把第一个问题修了"},                ← Turn 2 开始
  {assistant: [tool_use: read_file]},        ← Loop 1
  {user: [tool_result: ...]},                ← Loop 1 工具结果
  {assistant: [tool_use: write_file]},       ← Loop 2
  {user: [tool_result: "写入成功"]},         ← Loop 2 工具结果
  {assistant: "已修复，主要改了..."},        ← Loop 3（最终回复，Turn 2 结束）
  
  // ─── Turn 3 ───
  ...
]
```

**每次调用 API 时，这整个数组都传过去。** 这就是"历史记录"——它从来没有被"保存到别的地方再加载"，它一直就在这个数组里。

---

## 三、Agentic Loop 层：一次 Turn 内部的多次 LLM 调用

### 3.1 为什么需要循环？

用户说"帮我重构 utils.ts"，Agent 不可能一步完成。它需要：
1. 先读文件看看现在什么样
2. 想好怎么改
3. 写入新内容
4. 可能还要跑测试验证

每一步都是一次 LLM 调用 → 拿到工具指令 → 执行工具 → 把结果再喂回去 → 再调用 LLM。循环直到 LLM 决定"不调工具了，直接输出文本回复"，此时循环结束、Turn 结束。

### 3.2 循环流程

```
进入 Agentic Loop
        │
        ▼
┌───────────────────────────────────────────┐
│  while True:                               │
│                                            │
│    1. 调用 LLM API                         │
│       入参: system_prompt + messages[]      │
│       出参: response                        │
│                                            │
│    2. 检查 response 类型                    │
│       ├─ 纯文本 → Turn 完成，break         │
│       └─ 包含 tool_use → 继续              │
│                                            │
│    3. 把 assistant response push 到 messages│
│       messages.push({                      │
│         role: "assistant",                 │
│         content: [tool_use块]              │
│       })                                   │
│                                            │
│    4. 执行工具，拿到结果                    │
│                                            │
│    5. 把工具结果 push 到 messages            │
│       messages.push({                      │
│         role: "user",                      │
│         content: [tool_result块]           │
│       })                                   │
│                                            │
│    6. 回到步骤 1（下一轮循环）              │
│                                            │
└───────────────────────────────────────────┘
```

### 3.3 代码实现

```python
class Agent:
    # ... 同上 ...
    
    def _run_agentic_loop(self) -> str:
        """
        Agentic Loop：一个 Turn 内部的多次 LLM 调用。
        每次循环 = 一次 API call + 可能的工具执行。
        循环直到 LLM 输出纯文本（不再调工具）。
        """
        while True:
            # --- 一次 Loop 开始 ---
            
            # 1. 调用 LLM
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,  # ★ 每次都传完整历史
                tools=self.tool_definitions,
            )
            
            # 2. 检查是否有工具调用
            has_tool_use = any(
                block.type == "tool_use" 
                for block in response.content
            )
            
            if not has_tool_use:
                # ★ 纯文本回复 → Turn 结束
                final_text = response.content[0].text
                self.messages.append({
                    "role": "assistant", 
                    "content": final_text
                })
                return final_text
            
            # 3. 有工具调用 → 记录 assistant 响应
            self.messages.append({
                "role": "assistant",
                "content": [block.dict() for block in response.content]
            })
            
            # 4. 执行每个工具，收集结果
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = self._execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            
            # 5. 工具结果 push 到 messages
            self.messages.append({
                "role": "user",
                "content": tool_results
            })
            
            # --- 一次 Loop 结束，继续下一次 ---
    
    def _execute_tool(self, tool_name: str, tool_input: dict) -> str:
        """执行工具并返回结果"""
        if tool_name == "read_file":
            path = tool_input["path"]
            return open(path).read()
        elif tool_name == "write_file":
            path = tool_input["path"]
            content = tool_input["content"]
            open(path, "w").write(content)
            return f"Successfully wrote to {path}"
        elif tool_name == "run_command":
            import subprocess
            result = subprocess.run(
                tool_input["command"], shell=True,
                capture_output=True, text=True
            )
            return result.stdout + result.stderr
        else:
            return f"Unknown tool: {tool_name}"
```

### 3.4 一个 Turn 的具体例子

用户说："把 src/utils.ts 里的 getCwd 改名为 getCurrentDir"

```
Loop 1:
  → API 入参: messages（含用户请求）
  ← API 出参: tool_use[read_file("src/utils.ts")]
  → 执行: 读文件，拿到内容
  → push assistant msg + tool_result 到 messages

Loop 2:
  → API 入参: messages（含文件内容）
  ← API 出参: tool_use[write_file("src/utils.ts", 新内容)]
  → 执行: 写入修改后的文件
  → push assistant msg + tool_result 到 messages

Loop 3:
  → API 入参: messages（含写入成功的确认）
  ← API 出参: tool_use[run_command("grep -r getCwd src/")]
  → 执行: 搜索还有没有其他地方用到旧名字
  → push assistant msg + tool_result 到 messages

Loop 4:
  → API 入参: messages（含 grep 结果：还有3处）
  ← API 出参: tool_use[write_file("src/index.ts", ...)]
  → 执行: 修改其他文件
  → push assistant msg + tool_result 到 messages

Loop 5:
  → API 入参: messages（含修改确认）
  ← API 出参: "已完成重命名，共修改了4处引用..." ← 纯文本！
  → Turn 结束
```

---

## 四、压缩管道：防止 messages[] 爆掉

### 4.1 为什么需要压缩

messages[] 会一直增长。一个复杂的 Turn 可能产生 20+ 条消息（大量工具调用和结果）。几个 Turn 后就可能接近模型的上下文窗口限制（200K tokens）。

压缩管道在每个 Turn 开始时检查，如果快超了就触发。

### 4.2 五层递进策略

```
Token 使用量不断增长...

              正常区域              ↓ 警戒线               ↓ 硬上限
├─────────────────────────────────┼─────────────────────┼────┤
0                              ~167K                  ~177K  200K
                                  │                     │
                                  ▼                     ▼
                          触发自动压缩             阻塞报错
```

按从轻到重的顺序尝试：

```
第 1 层：截断工具结果
  └─ 一次 grep 返回了 50KB？只保留前 2000 + 后 1000 字符
  └─ 不改变对话结构，只缩短单条消息的 content

第 2 层：裁剪旧消息
  └─ 早期 Turn 的工具调用细节已经不重要了
  └─ 把早期 assistant 消息中的 tool_use 块替换为 "[已省略]"

第 3 层：清理旧工具输出（Microcompact）
  └─ 超过 5 个 Turn 前的 tool_result 全部替换为 "[结果已清除]"
  └─ 保留 assistant 的文本回复（那才是重要的总结）

第 4 层：AI 摘要（Autocompact）
  └─ 把前面所有消息交给 AI 生成一段摘要
  └─ 用摘要替换掉原始消息
  └─ messages 从 100 条变成 4 条（摘要 + 最近对话）

第 5 层：阻塞报错
  └─ 如果压缩后还是超限 → 抛异常，提示用户手动 /compact
```

### 4.3 代码实现

```python
class Agent:
    MAX_CONTEXT = 200_000
    COMPACT_THRESHOLD = 167_000  # 留 33K 给输出和缓冲
    
    def _compact_if_needed(self):
        """每个 Turn 开始时调用"""
        tokens = self._count_tokens(self.messages)
        
        if tokens < self.COMPACT_THRESHOLD:
            return  # 没超，什么都不做
        
        print(f"[压缩] Token {tokens} 超过阈值 {self.COMPACT_THRESHOLD}")
        
        # 第 1 层：截断大工具结果
        for msg in self.messages:
            if msg["role"] == "user" and isinstance(msg["content"], list):
                for block in msg["content"]:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        text = str(block.get("content", ""))
                        if len(text) > 5000:
                            block["content"] = text[:2000] + "\n...[截断]...\n" + text[-1000:]
        
        if self._count_tokens(self.messages) < self.COMPACT_THRESHOLD:
            return
        
        # 第 2-3 层：裁剪旧消息
        keep_recent = 10  # 保留最近 10 条
        for i, msg in enumerate(self.messages[:-keep_recent]):
            if msg["role"] == "assistant" and isinstance(msg.get("content"), list):
                msg["content"] = "[早期工具调用已省略]"
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                msg["content"] = "[早期工具结果已省略]"
        
        if self._count_tokens(self.messages) < self.COMPACT_THRESHOLD:
            return
        
        # 第 4 层：AI 全量摘要
        self._autocompact()
    
    def _autocompact(self):
        """用 AI 把旧对话压缩成摘要"""
        keep_recent = 6  # 保留最近 3 轮
        old_messages = self.messages[:-keep_recent]
        recent_messages = self.messages[-keep_recent:]
        
        # 让 AI 生成摘要
        summary_response = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=2000,
            messages=[{
                "role": "user",
                "content": f"""将以下对话历史压缩为结构化摘要。
保留：任务目标、已完成的操作、关键决策、修改了哪些文件。
丢弃：中间推理、已废弃方案、工具调用细节。

{json.dumps(old_messages, ensure_ascii=False)[:15000]}"""
            }]
        )
        
        summary = summary_response.content[0].text
        
        # ★ 用摘要替换旧历史
        self.messages = [
            {"role": "user", "content": f"<conversation-summary>\n{summary}\n</conversation-summary>"},
            {"role": "assistant", "content": "我已了解之前的上下文。"},
        ] + recent_messages
```

---

## 五、记忆系统：跨 Session 的长期知识

### 5.1 记忆 vs 对话历史（再次强调区别）

```
┌──────────────────────────────────────────────────────────────┐
│                                                                │
│  对话历史（messages[]）         记忆（memory/*.md）             │
│  ──────────────────            ──────────────────              │
│  作用域: 当前 Session          作用域: 永久（跨所有 Session）   │
│  内容: 每一句话、每个工具结果   内容: 精炼的知识点               │
│  存储: 进程内存                存储: 磁盘文件                   │
│  增长: 不断增长直到压缩         增长: 缓慢（只有重要的才存）     │
│  消亡: Session 结束            消亡: 被 AutoDream 整合/修剪     │
│                                                                │
│  类比:                                                         │
│  对话历史 = 你的工作台上摊开的资料（关了灯就清走了）            │
│  记忆 = 你的笔记本（永远留着，下次翻出来看）                   │
│                                                                │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 记忆什么时候产生

**每个 Turn 结束后，后台异步提取。** 不是用户手动触发，不是 Session 结束时，是 **每次 Agent 回复完之后立刻** 后台分析是否有值得记忆的内容。

```
Turn 结束
    │
    ├─► [主线程] 返回回复给用户
    │
    └─► [后台线程 / forked agent] 分析这个 Turn
           │
           ├─ 用户有没有表达偏好？ ("我喜欢用 async/await")
           ├─ 用户有没有纠正 AI？ ("不要用 var")
           ├─ 有没有项目级别的约定？ ("这个项目不用 ORM")
           │
           ├─ 有 → 写入 memory/*.md 文件
           └─ 没有 → 什么都不做
```

### 5.3 记忆什么时候被使用

**每个 Session 开始时加载一次**，注入到第一条消息里。不是每个 Turn 都重新加载（除非压缩后需要重注入）。

### 5.4 代码实现

```python
class MemoryManager:
    """记忆管理器：负责保存和加载跨 Session 的知识"""
    
    def __init__(self, project_dir: str):
        sanitized = project_dir.replace("/", "_")
        self.memory_dir = os.path.expanduser(
            f"~/.agent/projects/{sanitized}/memory/"
        )
        os.makedirs(self.memory_dir, exist_ok=True)
    
    def load_for_session(self) -> str:
        """
        Session 开始时调用。
        扫描记忆文件，用 AI 选择相关的，返回格式化文本。
        """
        files = sorted(
            Path(self.memory_dir).glob("*.md"),
            key=lambda f: f.stat().st_mtime,
            reverse=True
        )[:200]
        
        if not files:
            return ""
        
        # 如果记忆少于 5 条，全部返回
        if len(files) <= 5:
            return "\n\n".join(f.read_text() for f in files)
        
        # 记忆多了就用 AI 选择最相关的（side query）
        # ... 省略 AI 选择逻辑 ...
        return selected_memories_text
    
    def extract_from_turn(self, user_input: str, ai_response: str):
        """
        每个 Turn 结束后调用（后台线程）。
        用 AI 判断是否有值得记忆的内容。
        """
        result = client.messages.create(
            model="claude-haiku-3-20240307",
            max_tokens=500,
            messages=[{
                "role": "user",
                "content": f"""这段对话有值得长期记忆的信息吗？

用户: {user_input}
AI: {ai_response[:500]}

值得记忆: 用户偏好、纠正反馈、项目约定
不值得: 临时问题、一般对话、可从代码看到的事实

有 → 返回 JSON: {{"save": true, "title": "...", "content": "..."}}
没有 → 返回: {{"save": false}}"""
            }]
        )
        
        data = json.loads(result.content[0].text)
        if data.get("save"):
            self._write_memory(data["title"], data["content"])
    
    def _write_memory(self, title: str, content: str):
        """写入一条记忆到文件"""
        safe_name = "".join(c if c.isalnum() or c in "-_" else "_" for c in title)
        filepath = Path(self.memory_dir) / f"{safe_name}.md"
        filepath.write_text(f"""---
title: {title}
type: feedback
created: {datetime.now().isoformat()}
---

{content}
""")
```

---

## 六、完整代码：把所有层组装起来

```python
"""
完整的 Agent 实现。
三层结构：Session → Turn → Agentic Loop。
"""
import os
import json
import threading
from pathlib import Path
from datetime import datetime
from anthropic import Anthropic

client = Anthropic()


class Agent:
    """
    一个 Agent 实例 = 一个 Session。
    用户打开终端创建一个，关闭终端销毁一个。
    """
    
    def __init__(self, project_dir: str = "."):
        """=== Session 初始化 ==="""
        self.project_dir = os.path.abspath(project_dir)
        self.memory = MemoryManager(self.project_dir)
        self.session_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        
        # ★ 核心：对话历史
        self.messages: list[dict] = []
        
        # 系统提示（Session 级别，只算一次）
        self.system_prompt = self._build_system_prompt()
        
        # 加载长期记忆，注入为第一条消息
        memory_text = self.memory.load_for_session()
        if memory_text:
            self.messages.append({
                "role": "user",
                "content": f"<memory>\n{memory_text}\n</memory>"
            })
            self.messages.append({
                "role": "assistant",
                "content": "已加载记忆。"
            })
        
        # 工具定义
        self.tools = [
            {"name": "read_file", "description": "读取文件", 
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "写入文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
            {"name": "run_command", "description": "执行命令",
             "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
        ]
    
    # =====================================================
    # Turn 层
    # =====================================================
    
    def handle_turn(self, user_input: str) -> str:
        """处理一个 Turn：用户提问 → Agent 完整回复"""
        
        # 1. 用户消息入历史
        self.messages.append({"role": "user", "content": user_input})
        
        # 2. 压缩检查
        self._compact_if_needed()
        
        # 3. Agentic Loop
        final_response = self._run_agentic_loop()
        
        # 4. 后台记忆提取
        threading.Thread(
            target=self.memory.extract_from_turn,
            args=(user_input, final_response),
            daemon=True
        ).start()
        
        # 5. 持久化 session
        self._save_session()
        
        return final_response
    
    # =====================================================
    # Agentic Loop 层
    # =====================================================
    
    def _run_agentic_loop(self) -> str:
        """一个 Turn 内的多次 LLM 调用循环"""
        
        loop_count = 0
        max_loops = 20  # 安全阀：防止无限循环
        
        while loop_count < max_loops:
            loop_count += 1
            
            # 调用 LLM
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            # 检查停止原因
            if response.stop_reason == "end_turn":
                # ★ 纯文本回复 → Loop 结束 → Turn 结束
                text = next(
                    (b.text for b in response.content if b.type == "text"), 
                    ""
                )
                self.messages.append({"role": "assistant", "content": text})
                return text
            
            # 有工具调用 → 记录 + 执行 + 继续循环
            # 记录 assistant 的工具调用请求
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content]
            })
            
            # 执行工具，收集结果
            tool_results = []
            for block in response.content:
                if block.type == "tool_use":
                    result = self._execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            
            # 工具结果入历史
            self.messages.append({"role": "user", "content": tool_results})
        
        # 超过最大循环次数
        return "[错误] Agent 循环次数超过上限，请简化任务。"
    
    # =====================================================
    # 压缩管道
    # =====================================================
    
    def _compact_if_needed(self):
        """每个 Turn 开始时检查是否需要压缩"""
        tokens = self._estimate_tokens()
        threshold = 160_000
        
        if tokens < threshold:
            return
        
        # 第 1 层：截断大工具结果
        self._truncate_tool_results()
        if self._estimate_tokens() < threshold:
            return
        
        # 第 2 层：清理旧工具消息
        self._clean_old_tool_messages()
        if self._estimate_tokens() < threshold:
            return
        
        # 第 3 层：AI 全量摘要
        self._autocompact()
    
    def _truncate_tool_results(self):
        """截断超大的工具返回结果"""
        for msg in self.messages:
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                for block in msg["content"]:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        text = str(block.get("content", ""))
                        if len(text) > 8000:
                            block["content"] = (
                                text[:3000] + "\n\n...[已截断]...\n\n" + text[-2000:]
                            )
    
    def _clean_old_tool_messages(self):
        """清理旧的工具调用细节"""
        keep = 12  # 保留最近的消息
        for i, msg in enumerate(self.messages[:-keep]):
            content = msg.get("content")
            if msg["role"] == "assistant" and isinstance(content, list):
                self.messages[i]["content"] = "[早期工具调用已省略]"
            elif msg["role"] == "user" and isinstance(content, list):
                self.messages[i]["content"] = "[早期工具结果已省略]"
    
    def _autocompact(self):
        """AI 生成摘要，替换旧历史"""
        keep = 8
        old = self.messages[:-keep]
        recent = self.messages[-keep:]
        
        resp = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=2000,
            messages=[{"role": "user", "content": 
                f"压缩以下对话为结构化摘要（保留任务目标、已完成操作、关键文件）：\n"
                f"{json.dumps(old, ensure_ascii=False, default=str)[:15000]}"
            }]
        )
        
        self.messages = [
            {"role": "user", "content": f"<summary>\n{resp.content[0].text}\n</summary>"},
            {"role": "assistant", "content": "已了解上下文。"},
        ] + recent
    
    # =====================================================
    # 辅助方法
    # =====================================================
    
    def _build_system_prompt(self) -> str:
        return "你是一个编程助手。可以使用工具读写文件和执行命令。改代码前先读，遵循项目风格。"
    
    def _execute_tool(self, name: str, input: dict) -> str:
        if name == "read_file":
            try:
                return open(input["path"]).read()
            except Exception as e:
                return f"Error: {e}"
        elif name == "write_file":
            open(input["path"], "w").write(input["content"])
            return "OK"
        elif name == "run_command":
            import subprocess
            r = subprocess.run(input["command"], shell=True, capture_output=True, text=True)
            return (r.stdout + r.stderr)[:5000]
        return f"Unknown tool: {name}"
    
    def _estimate_tokens(self) -> int:
        """粗略估算 token 数（1 中文字 ≈ 2 token，1 英文词 ≈ 1.3 token）"""
        total_chars = sum(len(str(m.get("content", ""))) for m in self.messages)
        return int(total_chars * 1.5)  # 粗略近似
    
    def _save_session(self):
        """持久化 session 到磁盘（可选）"""
        session_dir = os.path.expanduser("~/.agent/sessions")
        os.makedirs(session_dir, exist_ok=True)
        filepath = os.path.join(session_dir, f"{self.session_id}.json")
        with open(filepath, "w") as f:
            json.dump({
                "session_id": self.session_id,
                "project": self.project_dir,
                "messages": self.messages,
                "updated": datetime.now().isoformat(),
            }, f, ensure_ascii=False, default=str)


# =====================================================
# 主入口：运行 Session
# =====================================================

def main():
    """一次 main() 执行 = 一个 Session"""
    agent = Agent(project_dir=".")
    
    print("Agent 已启动。输入 exit 退出。")
    print(f"Session ID: {agent.session_id}")
    print(f"已加载 {len(agent.messages)//2} 条记忆\n")
    
    while True:  # ← 这个循环就是 Session 的生命周期
        user_input = input("你: ").strip()
        if user_input.lower() in ("exit", "quit", "/exit"):
            break
        
        # 每次循环 = 一个 Turn
        response = agent.handle_turn(user_input)
        print(f"\nAI: {response}\n")
    
    print("Session 结束。")


if __name__ == "__main__":
    main()
```

---

## 七、完整时序图（按正确的层次标注）

```
时间  ║ 层次      ║ 用户            ║ Agent                    ║ 磁盘
═════╬══════════╬════════════════╬═════════════════════════╬═══════════════
     ║          ║                ║                         ║
T0   ║ Session  ║ 执行 `claude`  ║ __init__()              ║
     ║ 初始化   ║                ║ messages = []           ║
     ║          ║                ║ 组装 system_prompt      ║
     ║          ║                ║ 加载记忆 ←──────────────║── 读 memory/
     ║          ║                ║ 注入记忆到 messages     ║
     ║          ║                ║                         ║
─────╬──────────╬────────────────╬─────────────────────────╬───────────────
     ║          ║                ║                         ║
T1   ║ Turn 1   ║ "看看main.py" ║ handle_turn()           ║
     ║          ║                ║ messages.push(user)     ║
     ║          ║                ║ 压缩检查 → OK           ║
     ║          ║                ║                         ║
     ║ Loop 1   ║                ║ → API(messages)         ║
     ║          ║                ║ ← tool_use:read_file    ║
     ║          ║                ║ 执行工具                 ║
     ║          ║                ║ messages.push(ast+tool)  ║
     ║          ║                ║                         ║
     ║ Loop 2   ║                ║ → API(messages)         ║
     ║          ║                ║ ← 文本回复(end_turn)    ║
     ║          ║                ║ messages.push(ast)      ║
     ║          ║                ║                         ║
     ║ Turn 完  ║                ║ 后台提取记忆 ──────────►║ [可能]写memory
     ║          ║                ║ 持久化session ─────────►║ 写session.json
     ║          ║ ◄──────────── ║ 返回最终回复             ║
     ║          ║                ║                         ║
─────╬──────────╬────────────────╬─────────────────────────╬───────────────
     ║          ║                ║                         ║
T2   ║ Turn 2   ║ "记住用TS"    ║ handle_turn()           ║
     ║          ║                ║ messages.push(user)     ║
     ║          ║                ║ 压缩检查 → OK           ║
     ║          ║                ║                         ║
     ║ Loop 1   ║                ║ → API(messages)         ║
     ║          ║                ║ ← 文本回复(end_turn)    ║
     ║          ║                ║ messages.push(ast)      ║
     ║          ║                ║                         ║
     ║ Turn 完  ║                ║ 后台提取记忆 ──────────►║ ★ 写memory!
     ║          ║                ║ 持久化session ─────────►║ 写session.json
     ║          ║ ◄──────────── ║ 返回回复                 ║
     ║          ║                ║                         ║
─────╬──────────╬────────────────╬─────────────────────────╬───────────────
     ║          ║                ║                         ║
T50  ║ Turn N   ║ "重构模块"    ║ handle_turn()           ║
     ║          ║                ║ messages.push(user)     ║
     ║          ║                ║ 压缩检查 → ⚠️ 超限！    ║
     ║          ║                ║ ├─ 截断工具结果         ║
     ║          ║                ║ ├─ 清理旧消息           ║
     ║          ║                ║ └─ AI摘要压缩           ║
     ║          ║                ║   messages 变短了       ║
     ║          ║                ║                         ║
     ║ Loop 1-5 ║                ║ (正常 Agentic Loop)     ║
     ║          ║                ║                         ║
     ║ Turn 完  ║ ◄──────────── ║ 返回回复                 ║
     ║          ║                ║                         ║
═════╬══════════╬════════════════╬═════════════════════════╬═══════════════
     ║          ║                ║                         ║
T_end║ Session  ║ Ctrl+C        ║ 进程退出                 ║
     ║ 结束     ║               ║ messages[] 消亡 ✝        ║ session.json 留
     ║          ║               ║                          ║ memory/ 留
     ║          ║               ║                          ║
═════╬══════════╬════════════════╬═════════════════════════╬═══════════════
     ║          ║                ║                         ║
T_new║ 新Session║ 再次执行claude ║ __init__()              ║
     ║          ║                ║ messages = [] (全新)     ║
     ║          ║                ║ 加载记忆 ←──────────────║── 读 memory/
     ║          ║                ║ "记得用户喜欢TS"         ║ (跨Session!)
     ║          ║                ║                         ║
```

---

## 八、总结

**三层结构**：Session（会话） > Turn（对话轮次） > Agentic Loop（Agent 循环）。

**历史记录在哪里**：就是 Agent 进程内存里的 `messages[]` 数组。每次 Loop 调用 API 都带着完整数组。不存在"保存历史然后加载"——历史一直就在那个数组里。

**什么时候保存什么**：

| 什么 | 什么时候 | 保存到哪 |
|------|---------|---------|
| 每条消息 | 每次 Loop 后立即 push | messages[]（内存） |
| Session 快照 | 每个 Turn 结束后 | ~/.agent/sessions/xxx.json |
| 长期记忆 | 每个 Turn 结束后（后台判断） | ~/.agent/projects/xxx/memory/*.md |
| 记忆整合 | 每 24h + 5个Session 后 | AutoDream 自动执行 |

**跨 Session 怎么办**：不搬旧的 messages[]。新 Session 的 messages 从空开始，但从 memory/*.md 加载精炼的长期知识注入进去。所以 Agent"记得"你的偏好，但不记得上次具体聊了什么。
