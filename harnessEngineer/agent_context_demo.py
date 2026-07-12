"""
完整的 Agent 实现 Demo。
三层结构：Session → Turn → Agentic Loop。

运行方式：
  pip install anthropic
  export ANTHROPIC_API_KEY=your_key
  python agent_context_demo.py
"""
import os
import json
import threading
from pathlib import Path
from datetime import datetime
from typing import Optional

# 如果你没有 anthropic 库，下面的 MockClient 会作为替代
try:
    from anthropic import Anthropic
    client = Anthropic()
    USE_MOCK = False
except ImportError:
    USE_MOCK = True


# =====================================================
# Mock Client（没装 anthropic 时用来演示流程）
# =====================================================

class MockResponse:
    def __init__(self, text: str, stop_reason: str = "end_turn", tool_calls=None):
        if tool_calls:
            self.content = tool_calls
            self.stop_reason = "tool_use"
        else:
            self.content = [type("Block", (), {"type": "text", "text": text})()]
            self.stop_reason = stop_reason

class MockClient:
    """模拟 Anthropic API，用于演示流程"""
    class messages:
        @staticmethod
        def create(**kwargs):
            msgs = kwargs.get("messages", [])
            last_msg = msgs[-1] if msgs else {}
            content = last_msg.get("content", "")
            
            # 模拟 AI 的记忆提取判断
            if "值得长期记忆" in str(content):
                return MockResponse('{"save": false}')
            if "压缩" in str(content) or "摘要" in str(content):
                return MockResponse("## 摘要\n之前的对话涉及代码修改和重构工作。")
            
            # 模拟正常回复
            return MockResponse(
                f"[模拟回复] 收到，当前对话历史有 {len(msgs)} 条消息。"
            )

if USE_MOCK:
    client = MockClient()


# =====================================================
# 记忆管理器
# =====================================================

class MemoryManager:
    """
    负责记忆的 保存 / 加载 / 提取。
    记忆是跨 Session 的长期知识，存在磁盘上。
    """
    
    def __init__(self, project_dir: str):
        sanitized = project_dir.replace("/", "_").replace("\\", "_").strip("_")
        self.memory_dir = Path.home() / ".agent" / "projects" / sanitized / "memory"
        self.memory_dir.mkdir(parents=True, exist_ok=True)
    
    def load_for_session(self) -> str:
        """
        Session 开始时调用一次。
        扫描记忆文件，返回格式化文本用于注入。
        """
        files = sorted(
            self.memory_dir.glob("*.md"),
            key=lambda f: f.stat().st_mtime,
            reverse=True
        )[:20]  # 最多 20 个文件
        
        if not files:
            return ""
        
        parts = []
        for f in files[:5]:  # 简化版：取最新 5 条
            content = f.read_text(encoding="utf-8")
            # 去掉 frontmatter
            if content.startswith("---"):
                sections = content.split("---", 2)
                if len(sections) >= 3:
                    content = sections[2].strip()
            parts.append(f"- {f.stem}: {content[:200]}")
        
        return "\n".join(parts)
    
    def extract_from_turn(self, user_input: str, ai_response: str):
        """
        每个 Turn 结束后，后台线程调用。
        用 AI 判断是否有值得保存的记忆。
        """
        try:
            result = client.messages.create(
                model="claude-haiku-3-20240307" if not USE_MOCK else "mock",
                max_tokens=500,
                messages=[{
                    "role": "user",
                    "content": f"""这段对话有值得长期记忆的信息吗？

用户: {user_input}
AI: {ai_response[:500]}

值得记忆: 用户偏好、纠正反馈、项目约定
不值得: 临时问题、一般闲聊

有 → JSON: {{"save": true, "title": "标题", "content": "内容"}}
没有 → JSON: {{"save": false}}"""
                }]
            )
            
            text = result.content[0].text
            data = json.loads(text)
            if data.get("save"):
                self._write_memory(data["title"], data["content"])
                print(f"  💾 [Memory] 提取并保存: {data['title']}")
        except Exception as e:
            pass  # 记忆提取失败不影响主流程
    
    def _write_memory(self, title: str, content: str):
        safe_name = "".join(c if c.isalnum() or c in "-_" else "_" for c in title)
        filepath = self.memory_dir / f"{safe_name}.md"
        filepath.write_text(f"""---
title: {title}
type: feedback
created: {datetime.now().isoformat()}
---

{content}
""", encoding="utf-8")


# =====================================================
# Agent 主体
# =====================================================

class Agent:
    """
    一个 Agent 实例 = 一个 Session。
    内部分三层：Session 初始化 → Turn 处理 → Agentic Loop。
    """
    
    def __init__(self, project_dir: str = "."):
        """
        ═══ Session 初始化 ═══
        用户打开终端时执行一次。
        """
        self.project_dir = os.path.abspath(project_dir)
        self.memory = MemoryManager(self.project_dir)
        self.session_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        
        # ★ 核心数据结构：对话历史
        # 整个 Session 期间一直在内存中，不断增长
        self.messages: list[dict] = []
        
        # 系统提示（Session 级，只算一次）
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
                "content": "已加载记忆，随时准备帮助你。"
            })
        
        # 工具定义（简化版）
        self.tools = [
            {
                "name": "read_file",
                "description": "读取文件内容",
                "input_schema": {
                    "type": "object",
                    "properties": {"path": {"type": "string"}},
                    "required": ["path"]
                }
            },
            {
                "name": "write_file",
                "description": "写入文件",
                "input_schema": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"}
                    },
                    "required": ["path", "content"]
                }
            },
            {
                "name": "run_command",
                "description": "执行 shell 命令",
                "input_schema": {
                    "type": "object",
                    "properties": {"command": {"type": "string"}},
                    "required": ["command"]
                }
            },
        ]
    
    # ═══════════════════════════════════════════════════
    # Turn 层：一次用户提问 → 完整回复
    # ═══════════════════════════════════════════════════
    
    def handle_turn(self, user_input: str) -> str:
        """
        处理一个 Turn。
        一个 Session 中会多次调用此方法。
        """
        print(f"\n{'─'*50}")
        print(f"  Turn 开始 | messages 当前长度: {len(self.messages)}")
        
        # Step 1: 用户消息入历史
        self.messages.append({"role": "user", "content": user_input})
        
        # Step 2: 压缩检查
        self._compact_if_needed()
        
        # Step 3: Agentic Loop（可能多次调用 LLM）
        final_response = self._run_agentic_loop()
        
        # Step 4: 后台记忆提取
        threading.Thread(
            target=self.memory.extract_from_turn,
            args=(user_input, final_response),
            daemon=True
        ).start()
        
        # Step 5: 持久化 session
        self._save_session()
        
        print(f"  Turn 结束 | messages 当前长度: {len(self.messages)}")
        return final_response
    
    # ═══════════════════════════════════════════════════
    # Agentic Loop 层：一次 Turn 内的多次 LLM 调用
    # ═══════════════════════════════════════════════════
    
    def _run_agentic_loop(self) -> str:
        """
        循环调用 LLM，直到 LLM 输出最终文本回复（不再调工具）。
        每次循环 = 一次 API 调用 + 可能的工具执行。
        """
        loop_count = 0
        max_loops = 20
        
        while loop_count < max_loops:
            loop_count += 1
            print(f"    Loop {loop_count}: 调用 LLM...")
            
            # 调用 LLM API
            response = client.messages.create(
                model="claude-sonnet-4-20250514" if not USE_MOCK else "mock",
                max_tokens=8000,
                system=self.system_prompt,
                messages=self.messages,
                tools=self.tools,
            )
            
            # 检查是否结束
            if response.stop_reason == "end_turn":
                # 纯文本回复 → Loop 结束 → Turn 结束
                text = next(
                    (b.text for b in response.content if hasattr(b, "text")),
                    "[无文本回复]"
                )
                self.messages.append({"role": "assistant", "content": text})
                print(f"    Loop {loop_count}: 最终回复（Turn 结束）")
                return text
            
            # 有工具调用 → 执行并继续
            print(f"    Loop {loop_count}: 有工具调用，继续循环...")
            
            # 记录 assistant 的工具调用
            self.messages.append({
                "role": "assistant",
                "content": [
                    {"type": "tool_use", "id": b.id, "name": b.name, "input": b.input}
                    for b in response.content if hasattr(b, "name")
                ]
            })
            
            # 执行工具
            tool_results = []
            for block in response.content:
                if hasattr(block, "name"):
                    result = self._execute_tool(block.name, block.input)
                    tool_results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result,
                    })
            
            # 工具结果入历史
            self.messages.append({"role": "user", "content": tool_results})
        
        return "[错误] 循环次数超限"
    
    # ═══════════════════════════════════════════════════
    # 压缩管道
    # ═══════════════════════════════════════════════════
    
    def _compact_if_needed(self):
        """每个 Turn 开始时检查"""
        tokens = self._estimate_tokens()
        threshold = 150_000
        
        if tokens < threshold:
            return
        
        print(f"  ⚠️ [Compact] Token {tokens} 超阈值，开始压缩...")
        
        # 第 1 层：截断大工具结果
        for msg in self.messages:
            if msg["role"] == "user" and isinstance(msg.get("content"), list):
                for block in msg["content"]:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        text = str(block.get("content", ""))
                        if len(text) > 8000:
                            block["content"] = text[:3000] + "\n...[截断]...\n" + text[-2000:]
        
        if self._estimate_tokens() < threshold:
            print(f"  ✅ [Compact] 截断工具结果后恢复正常")
            return
        
        # 第 2 层：清理旧消息
        keep = 10
        for i, msg in enumerate(self.messages[:-keep]):
            if isinstance(msg.get("content"), list):
                self.messages[i]["content"] = "[已省略]"
        
        if self._estimate_tokens() < threshold:
            print(f"  ✅ [Compact] 清理旧消息后恢复正常")
            return
        
        # 第 3 层：AI 摘要
        self._autocompact()
        print(f"  ✅ [Compact] AI 摘要后 messages 长度: {len(self.messages)}")
    
    def _autocompact(self):
        keep = 6
        old = self.messages[:-keep]
        recent = self.messages[-keep:]
        
        resp = client.messages.create(
            model="claude-sonnet-4-20250514" if not USE_MOCK else "mock",
            max_tokens=2000,
            messages=[{"role": "user", "content":
                f"压缩以下对话为摘要：\n{json.dumps(old, ensure_ascii=False, default=str)[:15000]}"
            }]
        )
        
        summary = resp.content[0].text
        self.messages = [
            {"role": "user", "content": f"<summary>\n{summary}\n</summary>"},
            {"role": "assistant", "content": "已了解上下文。"},
        ] + recent
    
    # ═══════════════════════════════════════════════════
    # 辅助方法
    # ═══════════════════════════════════════════════════
    
    def _build_system_prompt(self) -> str:
        return """你是一个专业的编程助手。
可用工具：read_file, write_file, run_command。
规则：改代码前先读，遵循项目现有风格。"""
    
    def _execute_tool(self, name: str, tool_input: dict) -> str:
        try:
            if name == "read_file":
                return open(tool_input["path"]).read()[:10000]
            elif name == "write_file":
                open(tool_input["path"], "w").write(tool_input["content"])
                return "写入成功"
            elif name == "run_command":
                import subprocess
                r = subprocess.run(
                    tool_input["command"], shell=True,
                    capture_output=True, text=True, timeout=30
                )
                return (r.stdout + r.stderr)[:5000]
        except Exception as e:
            return f"Error: {e}"
        return f"Unknown tool: {name}"
    
    def _estimate_tokens(self) -> int:
        total = sum(len(str(m.get("content", ""))) for m in self.messages)
        return int(total * 1.5)
    
    def _save_session(self):
        session_dir = Path.home() / ".agent" / "sessions"
        session_dir.mkdir(parents=True, exist_ok=True)
        filepath = session_dir / f"{self.session_id}.json"
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump({
                "session_id": self.session_id,
                "project": self.project_dir,
                "messages": self.messages,
                "updated": datetime.now().isoformat(),
            }, f, ensure_ascii=False, default=str, indent=2)


# =====================================================
# 主入口
# =====================================================

def main():
    """
    一次 main() 执行 = 一个 Session 的完整生命周期。
    内部 while 循环的每次迭代 = 一个 Turn。
    """
    print("=" * 60)
    print("  Agent Demo — 三层结构演示")
    print("  Session > Turn > Agentic Loop")
    print("=" * 60)
    
    agent = Agent(project_dir=os.getcwd())
    
    print(f"\n📋 Session ID: {agent.session_id}")
    print(f"📁 项目目录: {agent.project_dir}")
    print(f"🧠 已加载记忆: {len(agent.messages) // 2} 条")
    print(f"🔧 使用模式: {'Mock（演示）' if USE_MOCK else 'Anthropic API'}")
    print(f"\n输入 exit 退出 | 输入 /status 查看状态\n")
    
    turn_count = 0
    
    while True:
        user_input = input("你: ").strip()
        
        if not user_input:
            continue
        if user_input.lower() in ("exit", "quit", "/exit"):
            break
        if user_input == "/status":
            print(f"\n  Session: {agent.session_id}")
            print(f"  Turns: {turn_count}")
            print(f"  Messages: {len(agent.messages)}")
            print(f"  Est. Tokens: {agent._estimate_tokens():,}")
            print(f"  Memory dir: {agent.memory.memory_dir}\n")
            continue
        
        turn_count += 1
        response = agent.handle_turn(user_input)
        print(f"\n🤖 AI: {response}\n")
    
    # Session 结束
    print(f"\n{'═' * 60}")
    print(f"  Session 结束")
    print(f"  共 {turn_count} 个 Turn，{len(agent.messages)} 条消息")
    print(f"  Session 文件: ~/.agent/sessions/{agent.session_id}.json")
    print(f"  记忆目录: {agent.memory.memory_dir}")
    print(f"{'═' * 60}")


if __name__ == "__main__":
    main()
