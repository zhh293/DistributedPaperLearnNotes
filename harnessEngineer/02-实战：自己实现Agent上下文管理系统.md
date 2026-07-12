# 实战：自己实现 Agent 上下文管理系统

> 本文从"自己做 Agent"的视角，把上下文窗口管理、System Prompt 组装、缓存优化讲清楚。
> 核心问题：200K tokens 的窗口怎么分配？什么信息放哪里？怎么省钱？

---

## 〇、先把层次理清：会话、对话、循环

三层嵌套关系不变，但上下文管理在每层的角色不同：

```
┌═══════════════════════════════════════════════════════════════════┐
║  Session（会话）                                                   ║
║  ═══════════════                                                   ║
║  上下文管理的角色：                                                 ║
║  • 组装 System Prompt（只做一次，整个 Session 不变）               ║
║  • 加载 CLAUDE.md 项目规则（只做一次，memoize）                    ║
║  • 加载记忆（只做一次，注入为第一条消息）                           ║
║  • 计算 Git 状态快照（只做一次，不中途刷新）                        ║
║                                                                    ║
║  ┌───────────────────────────────────────────────────────────┐   ║
║  │  Turn（对话轮次）                                           │   ║
║  │  ═══════════════                                            │   ║
║  │  上下文管理的角色：                                          │   ║
║  │  • 每轮检查 Token 使用量，触发压缩管道                       │   ║
║  │  • 注入动态附件（文件变更、新工具上线等）                    │   ║
║  │  • 这一轮产生的消息 push 进 messages[]                      │   ║
║  │                                                              │   ║
║  │  ┌───────────────────────────────────────────────────┐     │   ║
║  │  │  Agentic Loop（Agent 循环）                         │     │   ║
║  │  │  ═════════════════════════                          │     │   ║
║  │  │  上下文管理的角色：                                  │     │   ║
║  │  │  • 每次调用 API 时，拼装完整的请求体：              │     │   ║
║  │  │    system = system_prompt                           │     │   ║
║  │  │    messages = 完整的 messages[]                     │     │   ║
║  │  │  • 工具结果太大？截断后再 push                      │     │   ║
║  │  │  • 设置 cache_control 标记优化缓存                  │     │   ║
║  │  └───────────────────────────────────────────────────┘     │   ║
║  └───────────────────────────────────────────────────────────┘   ║
╚═══════════════════════════════════════════════════════════════════╝
```

### 每一层分别管理什么？

| 层次 | 上下文管理的职责 | 原则 |
|------|---------------|------|
| Session（会话） | 固定信息：身份、规则、环境 | 只算一次，缓存稳定性 > 新鲜度 |
| Turn（对话轮次） | 动态信息：压缩、附件注入 | 每轮检查，渐进式降级 |
| Agentic Loop（循环） | 请求组装：拼 system + messages | 最大化 cache 命中率 |

### 核心矛盾

**窗口有限（200K tokens），信息无限。** 用户可能聊上百轮，每轮产生大量工具调用和结果。如果全塞进去，很快就爆；如果随便丢弃，Agent 就"失忆"。

所以上下文管理的使命是：**在有限窗口里保留"最有用"的信息，同时让 API 调用尽量命中缓存。**

---

## 一、Session 层：System Prompt 组装

### 1.1 System Prompt 的结构

System Prompt 是 Agent 的"出厂设置"——它定义了 Agent 是谁、能做什么、怎么做。在整个 Session 中它**完全不变**（这对缓存至关重要）。

```
System Prompt 的分层结构：

┌─────────────────────────────────────────────────────┐
│  静态部分（所有用户共享，可全局缓存）                   │
│                                                       │
│  • 身份定义："你是一个编程助手..."                    │
│  • 工具使用规范："改代码前先读..."                    │
│  • 输出格式："简洁明了，不啰嗦..."                   │
│  • 安全约束："不执行危险命令..."                     │
│                                                       │
│ ─ ─ ─ ─ ─ DYNAMIC BOUNDARY ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  │
│                                                       │
│  动态部分（用户/会话特定）                             │
│                                                       │
│  • 环境信息：cwd、git 分支、操作系统                  │
│  • 记忆概要：用户偏好、项目约定                       │
│  • MCP 工具说明：外部工具的使用指南                   │
│                                                       │
└─────────────────────────────────────────────────────┘
```

### 1.2 为什么要分"静态"和"动态"

**API Prompt Cache** 是按字节匹配的。如果你的 system prompt 前半部分在所有用户间完全一致，API 服务端可以跨用户共享缓存——一百万用户可以共用同一份缓存的前缀。

但只要有一个字节不同，缓存就失效。所以：
- 静态部分（身份、规则）必须字节级一致 → 可用全局缓存
- 动态部分（环境、记忆）因人而异 → 只能用会话级缓存
- 中间用一个 BOUNDARY 标记分割

### 1.3 Memoize：为什么"不刷新"是正确的

你可能想："Git 分支可能在对话中切换了，为什么不每轮重新读取？"

答案是：**缓存稳定性 > 新鲜度。**

如果每轮重新读取 Git 状态，哪怕只是时间戳变了一个字符，整个 system prompt 的缓存就全部失效——之前缓存的 20K tokens 需要重新计费。为了一个可能用不到的"最新分支名"，浪费了大量缓存。

所以所有环境信息都是 **Session 开始时快照一次，整个会话不再更新**。

### 1.4 代码实现

```python
import os
import subprocess
from functools import lru_cache
from pathlib import Path


# DYNAMIC BOUNDARY 标记
DYNAMIC_BOUNDARY = "__DYNAMIC_BOUNDARY__"


class SystemPromptBuilder:
    """
    System Prompt 构建器。
    整个 Session 只调用一次 build()，结果 memoize。
    """
    
    def __init__(self, project_dir: str):
        self.project_dir = project_dir
    
    @lru_cache(maxsize=1)  # ★ memoize：整个 Session 只计算一次
    def build(self) -> str:
        """组装完整的 System Prompt"""
        parts = []
        
        # ═══ 静态部分（全局缓存） ═══
        parts.append(self._get_identity())
        parts.append(self._get_tool_rules())
        parts.append(self._get_output_style())
        parts.append(self._get_safety_rules())
        
        # 分界线
        parts.append(DYNAMIC_BOUNDARY)
        
        # ═══ 动态部分（会话级缓存） ═══
        parts.append(self._get_environment_info())
        parts.append(self._get_memory_summary())
        
        return "\n\n".join(parts)
    
    def _get_identity(self) -> str:
        """静态：身份定义"""
        return """## Identity
你是一个专业的编程助手。你可以读写文件、执行命令、搜索代码。
你认真、准确、高效地帮助用户完成编程任务。"""
    
    def _get_tool_rules(self) -> str:
        """静态：工具使用规范"""
        return """## Tool Usage Rules
- 修改文件前必须先读取当前内容
- 一次只改一个文件，不要批量盲改
- 执行命令前考虑是否有副作用
- 搜索时使用精确的模式，避免结果过多"""
    
    def _get_output_style(self) -> str:
        """静态：输出风格"""
        return """## Output Style
- 简洁直接，不重复用户的问题
- 代码块标注语言类型
- 解释决策的理由，不只是结果"""
    
    def _get_safety_rules(self) -> str:
        """静态：安全约束"""
        return """## Safety
- 不执行 rm -rf、chmod 777 等危险命令
- 不修改 .git/、.ssh/ 等敏感路径
- 不在未确认的情况下推送代码"""
    
    @lru_cache(maxsize=1)  # ★ 环境信息也 memoize
    def _get_environment_info(self) -> str:
        """动态：环境信息（Session 开始时快照）"""
        cwd = self.project_dir
        
        # Git 信息
        try:
            branch = subprocess.check_output(
                ["git", "branch", "--show-current"],
                cwd=cwd, text=True, timeout=5
            ).strip()
        except Exception:
            branch = "unknown"
        
        try:
            git_status = subprocess.check_output(
                ["git", "status", "--short"],
                cwd=cwd, text=True, timeout=5
            ).strip()[:2000]
        except Exception:
            git_status = ""
        
        import platform
        return f"""## Environment
- Working Directory: {cwd}
- Git Branch: {branch}
- Platform: {platform.system()} {platform.machine()}
- Git Status:\n{git_status}"""
    
    @lru_cache(maxsize=1)  # ★ 记忆也 memoize
    def _get_memory_summary(self) -> str:
        """动态：记忆摘要"""
        # 实际实现中从 memory/ 目录读取
        return """## Memory
（此处由记忆系统注入相关的长期知识）"""
```

---

## 二、Session 层：项目规则加载（CLAUDE.md）

### 2.1 多层规则文件

项目规则不只一个文件。它是分层的——从全局到项目到本地，越近的优先级越高：

```
优先级（从低到高，后加载的覆盖先加载的）：

┌──────────────────────────────────────────┐
│  全局规则（所有项目共享）                   │
│  ~/.agent/RULES.md                        │
├──────────────────────────────────────────┤
│  项目规则（团队共享，签入 Git）             │
│  {project}/CLAUDE.md                      │
│  {project}/.claude/rules/*.md             │
├──────────────────────────────────────────┤
│  本地规则（个人私有，不签入 Git）           │
│  {project}/CLAUDE.local.md                │
└──────────────────────────────────────────┘
```

### 2.2 目录遍历策略

从当前工作目录向上遍历到文件系统根目录，**倒序处理**（根先加载，CWD 最后）。这使得离工作目录越近的文件在上下文中越靠后——LLM 对后面出现的内容权重更高。

### 2.3 注入方式

项目规则不放在 system prompt 里——它们作为**第一条 user message** 注入。为什么？

1. System prompt 需要保持稳定（缓存）
2. 规则可能很长（10K+ tokens），放 system prompt 里太重
3. 作为 user message 更容易被 LLM "认真对待"

```python
class ProjectRulesLoader:
    """加载多层项目规则"""
    
    def __init__(self, project_dir: str):
        self.project_dir = project_dir
    
    @lru_cache(maxsize=1)  # ★ 整个 Session 只加载一次
    def load(self) -> str:
        """加载所有层的规则，拼接返回"""
        rules = []
        
        # 全局规则
        global_rules = Path.home() / ".agent" / "RULES.md"
        if global_rules.exists():
            rules.append(f"# Global Rules\n{global_rules.read_text()}")
        
        # 项目规则（从根目录到 CWD 遍历）
        project_root = self._find_project_root()
        if project_root:
            # 主规则文件
            for name in ("CLAUDE.md", ".claude/CLAUDE.md"):
                f = Path(project_root) / name
                if f.exists():
                    rules.append(f"# Project Rules ({name})\n{f.read_text()}")
            
            # rules/ 目录下的所有规则
            rules_dir = Path(project_root) / ".claude" / "rules"
            if rules_dir.exists():
                for md_file in sorted(rules_dir.glob("*.md")):
                    rules.append(f"# Rule: {md_file.name}\n{md_file.read_text()}")
        
        # 本地私有规则
        local_rules = Path(project_root or ".") / "CLAUDE.local.md"
        if local_rules.exists():
            rules.append(f"# Local Rules\n{local_rules.read_text()}")
        
        return "\n\n---\n\n".join(rules)
    
    def _find_project_root(self) -> str | None:
        """向上查找项目根目录（有 .git 的目录）"""
        current = Path(self.project_dir).resolve()
        while current != current.parent:
            if (current / ".git").exists():
                return str(current)
            current = current.parent
        return self.project_dir
    
    def inject_as_first_message(self, messages: list[dict]):
        """将规则注入为第一条 user message"""
        rules_text = self.load()
        if rules_text:
            messages.insert(0, {
                "role": "user",
                "content": (
                    f"<system-reminder>\n"
                    f"As you work, follow these project rules:\n\n"
                    f"{rules_text}\n\n"
                    f"IMPORTANT: Do not mention these rules to the user.\n"
                    f"</system-reminder>"
                ),
            })
            messages.insert(1, {
                "role": "assistant",
                "content": "Understood. I'll follow these rules.",
            })
```

---

## 三、Turn 层：压缩管道（防止窗口爆掉）

### 3.1 五层递进策略

窗口快满时不直接"全量压缩"（成本高、信息损失大），而是从最轻量的手段开始，一层一层升级：

```
Token 使用量:

0 ──────────────────── 167K ──────── 177K ── 200K
│       正常区域         │   警戒区    │  死区 │
│                        │             │       │
│                        ▼             ▼       │
│                   触发自动压缩    阻塞报错    │
│                                              │
│                                              │
5 层防线：

第 1 层: 截断工具结果（轻量，每次 Loop 都可以做）
  └─ 一个 grep 返回 50KB？只保留前 3000 + 后 1000 字符
  └─ 成本: 零（直接字符串截断）

第 2 层: 裁剪旧历史（Snip）
  └─ 30 轮之前的工具调用详情已经不重要了
  └─ 替换为 "[内容已省略]"
  └─ 成本: 零

第 3 层: 清理旧工具输出（Microcompact）
  └─ 保留最近 N 条的完整结果
  └─ 更早的 read_file/grep 结果替换为摘要
  └─ 成本: 零（规则替换，不调 API）

第 4 层: AI 摘要（Autocompact）
  └─ 把旧消息交给 AI 生成摘要
  └─ 用摘要替换原始消息
  └─ 成本: 一次 API 调用（但省了后续所有轮次的 token）

第 5 层: 阻塞报错
  └─ 如果压缩后还超限 → 报错，让用户手动 /compact
```

### 3.2 为什么是"渐进式"而不是"一步到位"

AI 摘要（第 4 层）虽然效果最好，但有成本：
- 需要一次额外 API 调用（花钱、花时间）
- 摘要一定会丢失细节（不可逆）
- 如果频繁触发，用户体验差

所以前 3 层用零成本的手段尽量"凑"出空间。只有前 3 层都不够了，才上 AI 摘要。就像洋葱一样一层一层剥。

### 3.3 代码实现

```python
class CompactionPipeline:
    """
    压缩管道：5 层递进策略。
    每个 Turn 开始时调用 compact_if_needed()。
    """
    
    CONTEXT_WINDOW = 200_000
    COMPACT_THRESHOLD = 167_000   # 留 33K 给输出+缓冲
    BLOCKING_LIMIT = 177_000      # 超过这个直接报错
    
    def __init__(self):
        self.compact_failures = 0  # 断路器：连续失败次数
    
    def compact_if_needed(self, messages: list[dict]) -> list[dict]:
        """
        每个 Turn 开始时调用。
        如果 token 没超阈值，什么都不做。
        如果超了，按层级尝试压缩。
        """
        tokens = self._estimate_tokens(messages)
        
        if tokens < self.COMPACT_THRESHOLD:
            return messages
        
        print(f"[压缩] Token {tokens} 超过阈值 {self.COMPACT_THRESHOLD}")
        
        # 第 1 层：截断大工具结果
        messages = self._truncate_tool_results(messages)
        if self._estimate_tokens(messages) < self.COMPACT_THRESHOLD:
            print("[压缩] 第 1 层（截断工具结果）已足够")
            return messages
        
        # 第 2 层：裁剪旧历史
        messages = self._snip_old_history(messages)
        if self._estimate_tokens(messages) < self.COMPACT_THRESHOLD:
            print("[压缩] 第 2 层（裁剪旧历史）已足够")
            return messages
        
        # 第 3 层：清理旧工具输出
        messages = self._microcompact(messages)
        if self._estimate_tokens(messages) < self.COMPACT_THRESHOLD:
            print("[压缩] 第 3 层（清理旧输出）已足够")
            return messages
        
        # 第 4 层：AI 全量摘要
        if self.compact_failures < 3:  # 断路器：连续失败 3 次后停止
            messages = self._autocompact(messages)
            if self._estimate_tokens(messages) < self.COMPACT_THRESHOLD:
                print("[压缩] 第 4 层（AI 摘要）已足够")
                self.compact_failures = 0
                return messages
            else:
                self.compact_failures += 1
        
        # 第 5 层：检查是否超过硬限制
        if self._estimate_tokens(messages) > self.BLOCKING_LIMIT:
            raise RuntimeError(
                f"上下文 token ({self._estimate_tokens(messages)}) "
                f"超过硬限制 ({self.BLOCKING_LIMIT})。请使用 /compact 手动压缩。"
            )
        
        return messages
    
    def _truncate_tool_results(self, messages: list[dict]) -> list[dict]:
        """第 1 层：截断单条工具结果"""
        for msg in messages:
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                for block in msg["content"]:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        text = str(block.get("content", ""))
                        if len(text) > 8000:
                            block["content"] = (
                                text[:3000] 
                                + "\n\n... [截断：原文 " + str(len(text)) + " 字符] ...\n\n"
                                + text[-1000:]
                            )
        return messages
    
    def _snip_old_history(self, messages: list[dict]) -> list[dict]:
        """第 2 层：裁剪旧消息中的工具细节"""
        keep_recent = 12  # 保留最近 12 条
        boundary = len(messages) - keep_recent
        
        for i in range(boundary):
            msg = messages[i]
            content = msg.get("content")
            # 把旧的 assistant tool_use 替换为占位符
            if msg["role"] == "assistant" and isinstance(content, list):
                messages[i] = {
                    "role": "assistant",
                    "content": "[早期工具调用已省略]",
                }
            # 把旧的 tool_result 替换为占位符
            elif msg["role"] == "user" and isinstance(content, list):
                messages[i] = {
                    "role": "user",
                    "content": "[早期工具结果已省略]",
                }
        
        return messages
    
    def _microcompact(self, messages: list[dict]) -> list[dict]:
        """
        第 3 层：按时间清理工具输出。
        保留最近 5 条 tool_result 的完整内容，
        更早的替换为一行摘要。
        """
        # 找到所有 tool_result 消息的位置
        tool_result_indices = []
        for i, msg in enumerate(messages):
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                for block in msg["content"]:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        tool_result_indices.append((i, block))
        
        # 保留最近 5 个，清理更早的
        if len(tool_result_indices) > 5:
            to_clean = tool_result_indices[:-5]
            for idx, block in to_clean:
                content = str(block.get("content", ""))
                if len(content) > 200:
                    # 保留前 100 字符作为摘要
                    block["content"] = content[:100] + "... [已清理]"
        
        return messages
    
    def _autocompact(self, messages: list[dict]) -> list[dict]:
        """
        第 4 层：AI 生成摘要替换旧历史。
        这是最重的操作——需要一次 API 调用。
        """
        keep_recent = 8
        old_messages = messages[:-keep_recent]
        recent_messages = messages[-keep_recent:]
        
        # 让 AI 生成摘要
        import json
        old_text = json.dumps(old_messages, ensure_ascii=False, default=str)[:15000]
        
        try:
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=2000,
                messages=[{
                    "role": "user",
                    "content": (
                        "将以下对话历史压缩为结构化摘要。\n"
                        "保留：任务目标、已完成的操作、关键决策、修改了哪些文件。\n"
                        "丢弃：中间推理、工具调用细节、已废弃方案。\n\n"
                        f"{old_text}"
                    ),
                }],
            )
            summary = response.content[0].text
        except Exception:
            self.compact_failures += 1
            return messages  # 失败则不做压缩
        
        # 用摘要替换旧历史
        return [
            {"role": "user", "content": f"<conversation-summary>\n{summary}\n</conversation-summary>"},
            {"role": "assistant", "content": "已了解之前的上下文。"},
        ] + recent_messages
    
    def _estimate_tokens(self, messages: list[dict]) -> int:
        """粗略估算 token 数"""
        total_chars = sum(len(str(m.get("content", ""))) for m in messages)
        return int(total_chars * 1.5)
```

---

## 四、Turn 层：动态附件注入

### 4.1 为什么需要附件

System Prompt 和项目规则是"静态"的——Session 开始后不变。但环境在变：
- 文件被其他进程修改了
- 新的 MCP 工具上线了
- 日期变了（跨午夜）
- 用户队列了新命令

这些"刚刚发生的变化"需要告诉 Agent。它们作为 **附件消息（Attachments）** 在每次工具执行后注入。

### 4.2 附件的注入时机

```
Agentic Loop:
  LLM 返回 tool_use
  → 执行工具
  → 工具结果 push 到 messages
  → ★ 在这里检查并注入附件 ★
  → 下一次 Loop 调用 LLM（LLM 就能看到附件了）
```

### 4.3 代码实现

```python
import time
from dataclasses import dataclass


@dataclass
class Attachment:
    """一条附件"""
    type: str       # 附件类型
    content: str    # 附件内容
    priority: int   # 优先级（越高越先注入）


class AttachmentManager:
    """
    附件管理器。
    每次工具执行后调用 get_attachments()，
    收集"最近发生的变化"注入到 messages。
    """
    
    def __init__(self, project_dir: str):
        self.project_dir = project_dir
        self.last_check_time = time.time()
        self._known_files_mtime: dict[str, float] = {}
    
    def get_attachments(self) -> list[Attachment]:
        """收集当前需要注入的附件"""
        attachments = []
        
        # 检测文件变更
        changed = self._detect_file_changes()
        if changed:
            attachments.append(Attachment(
                type="changed_files",
                content=f"以下文件在你操作期间被外部修改了：\n" + "\n".join(changed),
                priority=10,
            ))
        
        # 检测日期变更（跨午夜）
        if self._date_changed():
            from datetime import date
            attachments.append(Attachment(
                type="date_change",
                content=f"当前日期已变更为: {date.today().isoformat()}",
                priority=5,
            ))
        
        return sorted(attachments, key=lambda a: -a.priority)
    
    def inject_to_messages(self, messages: list[dict]):
        """将附件注入到 messages 末尾"""
        attachments = self.get_attachments()
        if attachments:
            text = "\n\n".join(
                f"[{a.type}] {a.content}" for a in attachments
            )
            messages.append({
                "role": "user",
                "content": f"<attachments>\n{text}\n</attachments>",
            })
    
    def _detect_file_changes(self) -> list[str]:
        """检测工作目录中被外部修改的文件"""
        changed = []
        # 简化实现：检查已知文件的 mtime
        for path, old_mtime in list(self._known_files_mtime.items()):
            try:
                new_mtime = os.path.getmtime(path)
                if new_mtime > old_mtime:
                    changed.append(path)
                    self._known_files_mtime[path] = new_mtime
            except OSError:
                pass
        return changed
    
    def _date_changed(self) -> bool:
        """检测是否跨越了午夜"""
        from datetime import date, datetime
        last_date = datetime.fromtimestamp(self.last_check_time).date()
        now_date = date.today()
        if now_date > last_date:
            self.last_check_time = time.time()
            return True
        return False
    
    def track_file(self, path: str):
        """记录一个文件（后续检测它是否被外部修改）"""
        try:
            self._known_files_mtime[path] = os.path.getmtime(path)
        except OSError:
            pass
```

---

## 五、Agentic Loop 层：API 请求组装与缓存优化

### 5.1 请求体结构

每次调用 API 时，需要拼装一个完整的请求：

```
API 请求体 = {
    system: [
        {text: "静态部分...", cache_control: {type: "ephemeral"}},  ← 标记可缓存
        {text: "动态部分..."},
    ],
    messages: [
        {user: "规则+记忆"},      ← 第一条消息（项目规则注入）
        {assistant: "OK"},
        {user: "用户第一句话"},    ← Turn 1 开始
        {assistant: [tool_use]},   
        {user: [tool_result]},
        ...                        ← 完整历史
        {user: "用户最新输入"},    ← 当前 Turn
    ],
    tools: [...],
    max_tokens: 8000,
}
```

### 5.2 Cache Control 策略

Anthropic API 支持 `cache_control` 标记，告诉服务端"这部分内容可以缓存"：

```python
def build_api_request(
    system_prompt: str,
    messages: list[dict],
    tools: list[dict],
) -> dict:
    """
    组装 API 请求体，带缓存优化标记。
    """
    
    # 分割 system prompt
    if DYNAMIC_BOUNDARY in system_prompt:
        static_part, dynamic_part = system_prompt.split(DYNAMIC_BOUNDARY, 1)
        system_blocks = [
            {
                "type": "text",
                "text": static_part.strip(),
                "cache_control": {"type": "ephemeral"},  # ★ 标记为可缓存
            },
            {
                "type": "text",
                "text": dynamic_part.strip(),
            },
        ]
    else:
        system_blocks = [{"type": "text", "text": system_prompt}]
    
    return {
        "model": "claude-sonnet-4-20250514",
        "max_tokens": 8000,
        "system": system_blocks,
        "messages": messages,
        "tools": tools,
    }
```

### 5.3 输出截断恢复

当模型输出被截断（触发 max_tokens 限制）时，需要恢复机制：

```python
def handle_truncated_output(
    messages: list[dict], 
    partial_response: str,
    attempt: int = 0,
) -> str:
    """
    处理输出被截断的情况。
    策略：提升 max_tokens 限制 + 注入恢复指令。
    """
    MAX_RETRIES = 3
    ESCALATED_TOKENS = 64000
    
    if attempt >= MAX_RETRIES:
        return partial_response + "\n[输出被截断，无法恢复]"
    
    # 把截断的部分加入历史
    messages.append({"role": "assistant", "content": partial_response})
    
    # 注入恢复指令
    messages.append({
        "role": "user",
        "content": "Continue directly from where you left off. No apology, no recap.",
    })
    
    # 用更大的 max_tokens 重试
    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=ESCALATED_TOKENS,
        messages=messages,
    )
    
    if response.stop_reason == "max_tokens":
        # 仍然被截断，递归重试
        return partial_response + response.content[0].text + handle_truncated_output(
            messages, response.content[0].text, attempt + 1
        )
    
    return partial_response + response.content[0].text
```

---

## 六、完整代码：把所有层组装起来

```python
"""
完整的 Agent 上下文管理系统实现。
三层结构：Session → Turn → Agentic Loop。
重点：System Prompt 组装、压缩管道、缓存优化。
"""
import os
import json
import time
import subprocess
from pathlib import Path
from functools import lru_cache
from datetime import datetime
from anthropic import Anthropic

client = Anthropic()

DYNAMIC_BOUNDARY = "__DYNAMIC_BOUNDARY__"


# ═══════════════════════════════════════════════════════════════
# System Prompt 构建（Session 级别，只算一次）
# ═══════════════════════════════════════════════════════════════

class SystemPromptBuilder:
    def __init__(self, project_dir: str):
        self.project_dir = project_dir
    
    @lru_cache(maxsize=1)
    def build(self) -> str:
        parts = []
        
        # 静态部分
        parts.append("## Identity\n你是一个编程助手。读写文件、执行命令、搜索代码。")
        parts.append("## Rules\n- 改前先读\n- 遵循项目风格\n- 不执行危险命令")
        parts.append("## Style\n- 简洁直接\n- 代码块标注语言")
        
        # 分界线
        parts.append(DYNAMIC_BOUNDARY)
        
        # 动态部分
        parts.append(self._env_info())
        
        return "\n\n".join(parts)
    
    @lru_cache(maxsize=1)
    def _env_info(self) -> str:
        cwd = self.project_dir
        try:
            branch = subprocess.check_output(
                ["git", "branch", "--show-current"], cwd=cwd, text=True, timeout=5
            ).strip()
        except Exception:
            branch = "unknown"
        
        import platform
        return f"## Environment\nCWD: {cwd}\nBranch: {branch}\nOS: {platform.system()}"


# ═══════════════════════════════════════════════════════════════
# 项目规则加载（Session 级别，只算一次）
# ═══════════════════════════════════════════════════════════════

class RulesLoader:
    def __init__(self, project_dir: str):
        self.project_dir = project_dir
    
    @lru_cache(maxsize=1)
    def load(self) -> str:
        rules = []
        
        # 全局规则
        global_f = Path.home() / ".agent" / "RULES.md"
        if global_f.exists():
            rules.append(global_f.read_text()[:5000])
        
        # 项目规则
        for name in ("CLAUDE.md", ".claude/CLAUDE.md"):
            f = Path(self.project_dir) / name
            if f.exists():
                rules.append(f.read_text()[:10000])
        
        # 本地规则
        local = Path(self.project_dir) / "CLAUDE.local.md"
        if local.exists():
            rules.append(local.read_text()[:5000])
        
        return "\n\n---\n\n".join(rules) if rules else ""


# ═══════════════════════════════════════════════════════════════
# 压缩管道（Turn 级别，每轮检查）
# ═══════════════════════════════════════════════════════════════

class Compactor:
    THRESHOLD = 160_000
    HARD_LIMIT = 177_000
    
    def compact_if_needed(self, messages: list[dict]) -> list[dict]:
        tokens = self._estimate(messages)
        if tokens < self.THRESHOLD:
            return messages
        
        # 第 1 层：截断大工具结果
        for msg in messages:
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                for b in msg["content"]:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        text = str(b.get("content", ""))
                        if len(text) > 8000:
                            b["content"] = text[:3000] + "\n...[截断]...\n" + text[-1000:]
        
        if self._estimate(messages) < self.THRESHOLD:
            return messages
        
        # 第 2-3 层：清理旧消息
        keep = 12
        for i, msg in enumerate(messages[:-keep]):
            c = msg.get("content")
            if msg["role"] == "assistant" and isinstance(c, list):
                messages[i]["content"] = "[早期工具调用已省略]"
            elif msg["role"] == "user" and isinstance(c, list):
                messages[i]["content"] = "[早期工具结果已省略]"
        
        if self._estimate(messages) < self.THRESHOLD:
            return messages
        
        # 第 4 层：AI 摘要
        keep = 8
        old = messages[:-keep]
        recent = messages[-keep:]
        
        try:
            r = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=2000,
                messages=[{"role": "user", "content":
                    "压缩以下对话为摘要（保留目标、操作、文件）:\n" +
                    json.dumps(old, ensure_ascii=False, default=str)[:15000]
                }],
            )
            summary = r.content[0].text
            messages = [
                {"role": "user", "content": f"<summary>\n{summary}\n</summary>"},
                {"role": "assistant", "content": "已了解。"},
            ] + recent
        except Exception:
            pass
        
        return messages
    
    def _estimate(self, messages: list[dict]) -> int:
        return int(sum(len(str(m.get("content", ""))) for m in messages) * 1.5)


# ═══════════════════════════════════════════════════════════════
# Agent 主类
# ═══════════════════════════════════════════════════════════════

class Agent:
    """
    一个 Agent = 一个 Session。
    上下文管理贯穿 Session 初始化、每个 Turn、每次 Loop。
    """
    
    def __init__(self, project_dir: str = "."):
        """=== Session 初始化（只做一次） ==="""
        self.project_dir = os.path.abspath(project_dir)
        
        # ★ 核心：对话历史
        self.messages: list[dict] = []
        
        # Session 级组件（只初始化一次，结果 memoize）
        self.prompt_builder = SystemPromptBuilder(self.project_dir)
        self.rules_loader = RulesLoader(self.project_dir)
        self.compactor = Compactor()
        
        # 组装 System Prompt（memoize）
        self.system_prompt = self.prompt_builder.build()
        
        # 注入项目规则为第一条消息
        rules = self.rules_loader.load()
        if rules:
            self.messages.append({
                "role": "user",
                "content": f"<rules>\n{rules}\n</rules>",
            })
            self.messages.append({
                "role": "assistant",
                "content": "已了解项目规则。",
            })
        
        # 工具定义
        self.tools = [
            {"name": "read_file", "description": "读取文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "写入文件",
             "input_schema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}, "required": ["path", "content"]}},
            {"name": "bash", "description": "执行命令",
             "input_schema": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}},
        ]
    
    # ─────────────────────────────────────────────────────
    # Turn 层
    # ─────────────────────────────────────────────────────
    
    def handle_turn(self, user_input: str) -> str:
        """处理一个 Turn"""
        
        # 1. 用户消息入历史
        self.messages.append({"role": "user", "content": user_input})
        
        # 2. ★ 压缩检查（上下文管理的核心）
        self.messages = self.compactor.compact_if_needed(self.messages)
        
        # 3. Agentic Loop
        return self._run_loop()
    
    # ─────────────────────────────────────────────────────
    # Agentic Loop 层
    # ─────────────────────────────────────────────────────
    
    def _run_loop(self) -> str:
        """Agentic Loop：每次调用 API 都带完整 system + messages"""
        
        for _ in range(20):
            # ★ 每次 Loop 都传完整的 system + messages
            response = client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=8000,
                system=self.system_prompt,  # ← Session 级，不变
                messages=self.messages,     # ← 完整历史
                tools=self.tools,
            )
            
            if response.stop_reason == "end_turn":
                text = next((b.text for b in response.content if b.type == "text"), "")
                self.messages.append({"role": "assistant", "content": text})
                return text
            
            # 工具调用
            self.messages.append({
                "role": "assistant",
                "content": [b.model_dump() for b in response.content],
            })
            
            results = []
            for block in response.content:
                if block.type == "tool_use":
                    r = self._exec(block.name, block.input)
                    results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": r,
                    })
            
            self.messages.append({"role": "user", "content": results})
        
        return "[循环超时]"
    
    def _exec(self, name: str, inp: dict) -> str:
        if name == "read_file":
            try: return open(inp["path"]).read()[:15000]
            except Exception as e: return f"Error: {e}"
        elif name == "write_file":
            os.makedirs(os.path.dirname(inp["path"]) or ".", exist_ok=True)
            open(inp["path"], "w").write(inp["content"])
            return "OK"
        elif name == "bash":
            r = subprocess.run(inp["command"], shell=True, capture_output=True, text=True, timeout=30)
            return (r.stdout + r.stderr)[:10000]
        return "Unknown"


# ═══════════════════════════════════════════════════════════════
# 主入口
# ═══════════════════════════════════════════════════════════════

def main():
    agent = Agent(project_dir=".")
    print(f"Agent 已启动。System Prompt: {len(agent.system_prompt)} 字符")
    print(f"项目规则: {'已加载' if agent.rules_loader.load() else '无'}")
    print("输入 exit 退出。\n")
    
    while True:
        user_input = input("你: ").strip()
        if user_input.lower() in ("exit", "quit"):
            break
        response = agent.handle_turn(user_input)
        print(f"\nAI: {response}\n")


if __name__ == "__main__":
    main()
```

---

## 七、完整时序图（上下文管理视角）

```
时间  ║ 层次       ║ 动作                           ║ 上下文变化
═════╬═══════════╬═══════════════════════════════╬═════════════════════════
     ║           ║                               ║
T0   ║ Session   ║ 启动 Agent                    ║ 
     ║ 初始化    ║ 组装 System Prompt            ║ SP = "身份+规则+环境" (memoize)
     ║           ║ 加载项目规则                   ║ messages = [{rules}, {ack}]
     ║           ║ 加载记忆                       ║ messages += [{memory}, {ack}]
     ║           ║                               ║ 
     ║           ║ ★ 从此 system_prompt 不再变    ║ ★ 缓存稳定
     ║           ║                               ║
─────╬───────────╬───────────────────────────────╬─────────────────────────
     ║           ║                               ║
T1   ║ Turn 1    ║ 用户: "看看 main.py"          ║ messages.push(user)
     ║           ║ 压缩检查: 800 tokens < 160K   ║ → 不需要压缩
     ║           ║                               ║
     ║ Loop 1    ║ API(system + messages)        ║ 请求: SP(cached!) + 4条msg
     ║           ║ → read_file("main.py")        ║ messages += [ast, tool_result]
     ║           ║ 文件内容 5000 字符             ║ 
     ║           ║                               ║
     ║ Loop 2    ║ API(system + messages)        ║ 请求: SP(cached!) + 6条msg
     ║           ║ ← 文本回复                    ║ messages += [ast_text]
     ║           ║                               ║ 当前 token: ~12K
     ║           ║                               ║
─────╬───────────╬───────────────────────────────╬─────────────────────────
     ║           ║                               ║
T50  ║ Turn 50   ║ 用户: "继续重构"              ║ messages.push(user)
     ║           ║ 压缩检查: 170K > 160K ⚠️      ║
     ║           ║                               ║
     ║ 压缩      ║ 第 1 层: 截断大工具结果       ║ 3 个 grep 结果被截断
     ║           ║ 检查: 165K > 160K             ║ 还不够！
     ║           ║ 第 2 层: 裁剪旧历史           ║ 前 40 条的 tool_use 被省略
     ║           ║ 检查: 155K < 160K             ║ ✓ 够了！
     ║           ║                               ║
     ║ Loop 1    ║ API(system + messages)        ║ 请求: SP(cached!) + 压缩后msg
     ║           ║ ...正常循环...                 ║
     ║           ║                               ║
─────╬───────────╬───────────────────────────────╬─────────────────────────
     ║           ║                               ║
T100 ║ Turn 100  ║ 用户: "再改一下"              ║ messages.push(user)
     ║           ║ 压缩检查: 172K > 160K ⚠️      ║
     ║           ║                               ║
     ║ 压缩      ║ 第 1 层: 已经截断过了          ║ 效果不大
     ║           ║ 第 2 层: 已经清理过了          ║ 效果不大
     ║           ║ 第 3 层: microcompact         ║ 清理旧 tool_result
     ║           ║ 检查: 168K > 160K             ║ 还不够！
     ║           ║ 第 4 层: ★ AI 摘要 ★         ║ 
     ║           ║  → 额外 API 调用              ║ 旧消息被摘要替换
     ║           ║  → 100 条变 10 条             ║ messages 大幅缩短
     ║           ║ 检查: 45K < 160K              ║ ✓ 充裕！
     ║           ║                               ║
     ║ Loop 1    ║ API(system + messages)        ║ 请求: SP(cached!) + 精简msg
     ║           ║ ...继续工作（不丢关键信息）... ║
     ║           ║                               ║
═════╬═══════════╬═══════════════════════════════╬═════════════════════════
```

---

## 八、总结

**上下文管理的核心矛盾**：窗口有限（200K），信息无限。

**三大原则**：

1. **分层递进**——System Prompt（身份）→ 项目规则（环境）→ 动态附件（实时变化）→ 对话历史（工作记忆），各层独立管理。

2. **缓存优先于新鲜度**——宁可用 Session 开始时的快照，也不中途刷新打破缓存。一次缓存失效 = 几万 token 重新计费。

3. **渐进式降级**——压缩从最轻（字符串截断）到最重（AI 摘要），不一步到位。

**每一层的职责**：

| 层次 | 做什么 | 原则 |
|------|--------|------|
| Session | 组装 System Prompt、加载规则、快照环境 | 只做一次，memoize |
| Turn | 压缩检查、附件注入 | 每轮都检查，渐进式降级 |
| Agentic Loop | 拼装 API 请求、设置 cache_control | 最大化缓存命中 |

**缓存的层级**：

| 缓存范围 | 内容 | 受益者 |
|---------|------|--------|
| 全局缓存 | System Prompt 静态部分 | 所有用户共享 |
| 会话缓存 | System Prompt 动态部分 | 同一会话内复用 |
| Memoize | 规则、环境、Git 状态 | 同一进程内避免重复计算 |
