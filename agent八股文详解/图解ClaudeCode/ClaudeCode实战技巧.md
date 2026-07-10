# Claude Code 实战技巧

> 来源：小林面试笔记 - 图解 Claude Code 系列
> 网站：https://www.xiaolinnote.com/claudecode/

---

## 文章一：Claude Code 使用教程：新手入门必学的基础技巧

原创 公众号@小林coding | 大约 34 分钟 约 10216 字

---

大家好，我是小林。

最近这一年，AI编程真的火到没朋友，各种工具轮番上线，相信很多做开发的朋友都或多或少尝试过。

但要说哪款工具，真正颠覆了我的编程习惯、让效率直接翻倍，那我必须实名安利 **Claude Code**！

可能还有朋友对它不太熟悉，简单说一句：它是Anthropic推出的终端AI编程助手，不用切换窗口，就能直接读写你的项目文件、执行命令、搜索代码，甚至帮你跑测试、提交代码，相当于把一个专业编程助手，直接装进了你的终端里。

这段时间，很多林友问我：现在Claude Code、openclaw这些 AI Agent这么火，面试的时候会不会考啊？

![](https://cdn.xiaolincoding.com//picgo/1775372644383-78735ce2-0ebd-45e8-924b-678ed39c6582.png)

答案是肯定的！尤其是面试后端或者Agent开发岗位，十有八九会聊到这类工具。浅一点的，会问你实际使用感受和心得；深一点的，直接追问实现原理，一点不含糊。

除此之外，我还特意收集了一波Claude Code相关面经，发现一个关键点：除了原理，面试官还特别在意，你有没有真正用过这类AI编程工具。毕竟工作后，这些工具就是帮你提升效率、完成开发任务的「刚需」，光懂理论可不行。

![](https://cdn.xiaolincoding.com//picgo/1775373224891-72358dd5-8f16-48de-a3aa-d55623ae29c5.png)

所以今天这篇文章，我不聊复杂的原理，也不堆枯燥的理论，就专门分享 Claude Code 最常用的基础使用技巧。

### 01｜Claude Code 的基本操作

在动手写项目之前，先花两分钟了解一下 Claude Code 的基本操作。

#### 启动 Claude Code

打开你的终端，进入项目的目录，输入：

```plain
claude
```

第一次启动后，你会看到 Claude Code 的交互界面，底部有一个输入框，你可以直接在里面打字跟 Claude 对话。

![](https://cdn.xiaolincoding.com//picgo/1775292084572-ae40195f-bb52-457c-9712-fa5d1ee3cf82.png)

#### 三个工作模式

Claude Code 有三个工作模式，这是你必须要了解的。

![](https://cdn.xiaolincoding.com//picgo/1775363651102-39f07e2a-d698-481b-ae65-be0b255afb8c.png)

按 **Shift+Tab** 可以在三个模式之间切换：

- **Normal 模式**（默认）：Claude 每一步操作都需要你确认后才执行。比如它要创建一个文件，会先问你「允许吗？」，你同意了它才会创建
- **Auto-accept 模式**：Claude 自动执行所有操作，不需要你逐个确认。适合你对 Claude 已经比较信任的场景
- **Plan 模式**：Claude 只看不改，先帮你分析和规划，规划过程中不会修改你的文件，等你确定计划没问题，才会一步一步执行计划

**新手建议**：先用 Normal 模式，能看到 Claude 每一步在干什么，心里有底。等熟悉了再切到 Auto-accept 提速。

### 02｜用 Plan 模式规划

很多人一上来就迫不及待地让 Claude 写代码，结果写出来的东西跟自己想的完全不一样，又要改来改去。

我的建议是：**先规划，再动手。**

怎么做？先按 Shift+Tab 切到 **Plan 模式**，然后输入下面的提示词：

```plain
请帮我开发一个番茄钟 Web 应用，技术栈用 React + TypeScript + Tailwind CSS。功能包括：25 分钟倒计时、开始/暂停/重置、番茄计数、休息提醒。请先帮我规划一下项目结构和开发步骤，不要写代码。
```

![](https://cdn.xiaolincoding.com//picgo/1775292259481-7f148540-07f1-4c31-82d8-d367e7b3054d.png)

注意我写的这段提示词有几个关键点：

- **明确技术栈**：React + TypeScript + Tailwind CSS，不让 Claude 自己猜
- **明确功能**：25 分钟倒计时、开始/暂停/重置、番茄计数、休息提醒
- **明确要求**：先规划不写代码

这种「清晰明确」的提示词写法，是用好 Claude Code 的基本功。提示词越具体，Claude 输出的质量就越高。

### 03｜需求不明确？让 Claude 来「采访」你

前面我是直接给出了明确的提示词，比如技术栈、功能、要求都写得清清楚楚。但现实中，很多时候你自己都还没想清楚要做什么。

这种情况下，与其自己硬憋一个需求文档，不如让 Claude 来「采访」你。

试试这样写提示词：

```plain
我想做一个番茄钟应用，但我还没想清楚具体要怎么做。
请你先不要写代码，而是向我提几个问题，
帮我理清需求，等我们讨论清楚了你再动手。
```

Claude 会像一位经验丰富的产品经理一样，主动问你技术栈偏好、目标平台、核心功能、数据存储、界面风格等问题。

**这种「让 AI 来问你」的技巧，在需求不明确的时候特别好用。本质上就是让 Claude 帮你做需求分析，而不是一上来就埋头写代码。**

### 04｜页面不好看？用 Skill 美化

功能都写完了，你打开浏览器一看，嗯，功能是有了，但页面不算太好看。

有没有一种方式，能让 Claude Code 突然「学会」前端设计？

还真有。Claude Code 有一个叫 **Skills** 的机制，你可以把它理解为 Claude 的「专业技能包」。其中有一个 Skill 叫 **frontend-design**，专门用来生成高质量的前端界面。

安装 frontend-design Skill 的方式很简单，在 claude code 终端输入下面的命令就行：

```plain
/plugin install frontend-design
```

安装好之后，你只需要这样输入：

```plain
番茄钟的功能都完成了，但页面很丑。请用 frontend-design skill 帮我重新设计界面。
我想要简洁现代的风格，暖色调，圆形倒计时显示，配合番茄的红色主题。
```

Claude Code 会自动加载 frontend-design 这个 Skill，然后按照专业的设计标准来重构你的页面。

### 05｜用 @ 精准指定文件

页面美化完了，你想微调一下倒计时数字的样式。但项目文件越来越多，你不想让 Claude 乱翻一通，改到不该改的文件。

怎么办？用 **@ 符号**精准指定文件：

```plain
请把倒计时数字改大一点，加一个呼吸动画效果。
@src/components/TimerDisplay.tsx
```

@ 的用法很简单：

- `@./src/components/Timer.tsx`：引用单个文件
- `@./src/components/`：引用整个目录
- `@./src/App.tsx @./src/styles/global.css`：引用多个文件

**@ 还有一个很容易被忽略的好处：节省上下文。** 如果你不给 Claude 指定文件，它为了理解你的需求，可能会自己去搜索和读取项目里的很多文件，每一行代码都会占用上下文空间。

所以养成一个习惯：**能 @ 指定文件就 @ 指定，别让 Claude 自己去找。**

### 06｜遇到复杂功能？用 ultrathink 深度思考

番茄钟的基本功能都做完了，现在你想加一个「统计面板」，展示今天完成了多少个番茄、累计专注了多长时间、连续完成了几天。

这是一个涉及数据结构设计和多组件协调的复杂功能。这时候你可以在提示词里加上 **「ultrathink」**：

```plain
请帮我添加一个番茄统计面板，展示今日完成番茄数、累计专注时长、连续完成天数。
需要考虑数据的持久化存储（localStorage）。请 ultrathink 后给我方案。
```

「ultrathink」会让 Claude 这一轮临时把思考的努力等级拉到最高，多花一些时间把方案从头捋一遍，分析得更深入。

这里林友要注意一个新旧版本的差异。老版本的 Claude Code 曾经有 think、think hard、think harder、ultrathink 四个思考关键词，网上很多教程也还在这么写。但在新版本里，官方已经明确说明：**只有「ultrathink」这一个关键词是真正生效的**，其他几个都只会被当成普通的提示词，不会真的加深思考。

**注意：思考越深，消耗的 token 越多，响应也会更慢。简单问题别滥用，复杂问题再用。**

### 07｜每次都要重复解释项目？用 CLAUDE.md

项目写到这个阶段，你会发现一个烦人的事情：每次 `/clear` 或者第二天重新打开 Claude Code，你都要重新告诉它项目信息。

有没有办法让 Claude 永远记得你的项目信息？有，用 **CLAUDE.md**。

#### CLAUDE.md 是什么？

CLAUDE.md 是一个放在项目根目录的文件，Claude Code 每次启动时都会**自动读取**这个文件的内容，作为它理解项目的背景信息。

#### 怎么创建 CLAUDE.md？

最简单的方式是输入 `/init` 命令：

```plain
/init
```

Claude 会扫描你的项目结构，自动生成一份 CLAUDE.md。

#### 注意事项

CLAUDE.md 不是越长越好，而是**越精准越好**。一份好的 CLAUDE.md 应该包含：项目简介、技术栈、代码规范、项目结构（可选）。

每条信息都要问自己「如果删掉这条，会不会让 Claude 犯错？」如果不会，就不写。

### 08｜Claude 改错了怎么办？用 Rewind 回滚

Claude 改错代码是 AI 编程的常态。Claude Code 自带「后悔药」：

#### 三种「撤销」方式

- **连按两次 Esc**：在输入框为空的状态下，快速连按两次 Esc 键，就会打开回滚菜单
- **/rewind 命令**：效果跟连按 Esc 一样
- **Git**：最靠谱的方式，先 `git commit` 存档，万一改坏了用 `git checkout .` 回退

### 09｜Claude 开始犯迷糊？先压缩，再清空

当 Claude 开始犯迷糊（重复之前的错误、忘记你说过的话），通常是上下文快满了。

#### 先试 /compact：压缩上下文

```plain
/compact
```

这个命令会让 Claude 把当前的对话历史做一次「摘要压缩」，把冗长的历史浓缩成精华，腾出上下文空间。

#### 再考虑 /clear：彻底清空

```plain
/clear
```

如果 /compact 之后还是犯迷糊，就用 /clear 彻底清空对话，从头开始。

### 10｜关掉终端对话就没了？用 resume 恢复

关掉终端后，下次想继续之前的对话：

```plain
claude --resume
```

它会列出你最近的几次对话，选一个就能接着聊。

### 11｜写完代码谁来把关？用子代理做 Code Review

#### 什么是子代理？

子代理（Sub-agent）是你自己定义的一个「专家角色」，它有独立的系统提示词，专门负责某一类任务。

#### 创建一个代码审查子代理

在项目的 `.claude/agents/` 目录下创建一个 markdown 文件，比如 `code-reviewer.md`：

```markdown
你是一个严格的代码审查专家。请审查用户提供的代码，重点关注：
1. 潜在的 bug 和边界情况
2. 性能问题
3. 代码可读性和命名规范
4. TypeScript 类型安全
请给出具体的改进建议，并说明原因。
```

#### 使用子代理审查代码

```plain
请用 code-reviewer 子代理审查一下 @src/hooks/useTimer.ts
```

### 总结

以上就是 Claude Code 最常用的 11 个基础技巧。核心思路就是：先规划再动手、精准指定文件、善用工具和模式。

---

## 文章二：Claude Code /powerup 教程：18 个官方互动课程全解析

原创 公众号@小林coding | 大约 36 分钟 约 10919 字

---

大家好，我是小林。

Claude Code 在 v2.1.90 版本（2026 年 4 月 1 日发布）里悄悄上线了一个新命令：**/powerup**。

这个命令不是什么花里胡哨的功能，而是官方直接在你的终端里内置了一套交互式教程，总共 10 个课程，每个课程都带动画演示，手把手教你怎么用 Claude Code。

![](https://cdn.xiaolincoding.com//picgo/1777169437374-37a6ff54-904c-4850-ad59-ab1f958eac97.png)

### 先确认你的版本

/powerup 是 v2.1.90+ 才新增的命令。怎么查版本？在终端里输入：

```plain
claude --version
```

如果版本号低于 2.1.90，就需要先升级：

```plain
npm update -g @anthropic-ai/claude-code
```

### 打开 /powerup

在终端里启动 Claude Code，然后在输入框里输入：

```plain
/powerup
```

一共 10 个课程，每类都标注了对应的核心命令或操作。右上角还有进度条，每完成一个课程就会解锁一格。

### 01｜Talk to your codebase：与代码库对话

教你最基本也是最高频的操作：怎么让 Claude「看到」你的代码。用 **@ 符号** 精准指定文件。

- `@./src/App.tsx`：引用单个文件
- `@./src/components/`：引用整个目录
- `@./src/App.tsx @./src/styles/global.css`：引用多个文件

还可以在 `.claude/settings.json` 里用 `permissions.deny` 规则屏蔽敏感文件：

```json
{
  "permissions": {
    "deny": [
      "Read(./.env)",
      "Read(./.env.*)",
      "Read(./secrets/**)"
    ]
  }
}
```

### 02｜Steer with modes：用模式驾驭 Claude

四个工作模式：

- **Normal 模式（默认）**：每步操作都需要确认
- **accept edits（自动接受编辑）**：改文件不问，执行命令还会问
- **Plan 模式（计划模式）**：只看不改，先出方案
- **auto（自动模式）**：所有操作全自动执行

建议：刚入门用 default，日常写代码切 accept edits，复杂任务用 plan，长任务全自动用 auto。

### 03｜Undo anything：Claude 改错了？一键撤销

两种撤销方式：

- **连按两次 Esc**：打开回滚菜单
- **输入 /rewind 命令**：效果一样

更靠谱的方式还是 Git：

```plain
git add .
git commit -m "改动前的存档"
```

### 04｜Run in the background：让 Claude 在后台干活

直接用大白话告诉 Claude「这条命令请在后台跑」就行：

```plain
请帮我在后台运行 npm run build，跑完了告诉我结果。
```

用 `/tasks` 查看后台任务状态。

### 05｜Teach Claude your rules：让 Claude 记住你的规则

用 **CLAUDE.md** 作为项目记忆。用 `/init` 创建，用 `/memory` 维护。

CLAUDE.md 的几个层级：
- 项目根的 CLAUDE.md：整个项目的通用约定
- 子目录的 CLAUDE.md：模块特有规则
- `~/.claude/CLAUDE.md`：跨项目的个人偏好

### 06｜Extend with tools：用 MCP 给 Claude 装外挂

MCP（Model Context Protocol）让 Claude 能调用外部工具。用 `/mcp` 管理 MCP server。

添加 MCP server 示例：

```plain
/mcp add 12306-mcp -- npx -y @anthropic-ai/12306-mcp
```

### 07｜Automate your workflow：让 Claude 自动化你的工作流

**Skills**：给 Claude 装技能包

```plain
/plugin install frontend-design
```

**Hooks**：给 Claude 的操作加「钩子」，在特定事件触发时自动执行脚本。

### 08｜Multiply yourself：让 Claude 的分身帮你干活

子代理（Sub-agent）是你自己定义的「专家角色」。在 `.claude/agents/` 目录下创建 markdown 文件定义子代理。

适合场景：代码审查、文档生成、测试编写、安全检查。

### 09｜Code from anywhere：随时随地编码

- `/remote-control`：把本地会话「暴露」给网页
- `/teleport`：把网页上的会话「传送」到终端

### 10｜Dial the model：调节 Claude 的「大脑」

- `/model` 切换模型
- `/effort` 调节思考深度
- `ultrathink`：临时顶到最高挡

### 补充：几个高频使用的「维护」命令

- `/context`：看看 Claude 还有多少「脑容量」
- `/compact`：给 Claude 减负
- `/clear`：彻底清空对话
- `claude --resume`：恢复上次的对话

---

## 文章三：CLAUDE.md 怎么写？Claude Code 项目记忆文件维护指南

原创 公众号@小林coding | 大约 21 分钟 约 6263 字

---

大家好，我是小林。

前阵子，有个林友在群里发牢骚。他说给 Claude Code 写了一份 1000 多行的 CLAUDE.md：整个项目架构文档抄了一份、团队术语表搬了一份、连「我们希望测试覆盖率到 90%」这种愿望也堆上去，自我感觉特别细致。

结果呢？Claude 该忘的还是忘，该违规的还是违规。

我把 Anthropic 官方文档整个翻了一遍，发现自己之前的 CLAUDE.md 一半内容压根是负资产。

### 01｜CLAUDE.md 到底是个什么东西？

你想象一个场景。你刚入职一家公司，主管丢给你一份文档，标题叫「团队约定」。里面写了：我们用 yarn 不用 npm，API 在 `src/api/` 下，生产数据库千万别动，提 PR 之前要跑 `yarn lint`。

CLAUDE.md 就是给 Claude 的这份「团队约定」。

![](https://cdn.xiaolincoding.com//picgo/01-team-handbook-d89aa628.png)

它本质上就是一个普通的 markdown 文件，文件名固定叫 `CLAUDE.md`，放在你项目的根目录下。每次你打开 Claude Code 跟它聊天，**Claude 都会自动把这个文件读一遍**，作为整个对话的「ground truth」。

在你输入第一句提问之前，Claude 都会先读这个文件，并把它当作整段会话的默认前提。它不是「可选的提示」，而是「默认的前提」。

![](https://cdn.xiaolincoding.com//picgo/02-claude-md-load-timeline-749f46ae.png)

源码里的加载逻辑（`src/utils/claudemd.ts`）：从你当前所在的目录一路往上爬到文件系统根目录，每爬一层就把目录名记下来。爬完之后再反向遍历，从根目录往下读每一层的 CLAUDE.md 和 `.claude/CLAUDE.md`，全部合并喂给模型。

### 02｜写多了反而废？

一组实测数据（来自 SFEIR Institute）：把所有规则塞在一个 CLAUDE.md 里，**控制在 200 行以内的时候，规则遵守率大概 92%**。但写到 400 行往上，遵守率就肉眼可见地往下掉。

如果你把 200 行拆成 5 个 30 行的模块化文件，丢到 `.claude/rules/` 目录里，**遵守率反而能涨到 96%**。

为啥会这样？两个原因：

**第一，token 经济**。CLAUDE.md 每次启动都会被完整加载进上下文窗口。你写 400 行，每次请求就消耗几千 token，挤压你的对话空间。

**第二，注意力稀释**。模型的注意力不是无限的，规则一多，每条规则在模型脑子里的权重就被摊薄了。

![](https://cdn.xiaolincoding.com//picgo/07-sticky-notes-50-vs-400-f8efa8f2.png)

最典型的三类反例（负资产）：

- **复述型**：把整个项目架构文档复制粘贴进 CLAUDE.md。正确做法是一行话指过去：「项目架构详见 docs/architecture.md」
- **愿望型**：「我们希望测试覆盖率达到 90%」。CLAUDE.md 里只写当下实际执行的规则
- **术语表型**：把团队术语表往 CLAUDE.md 里搬。Claude 是个 LLM，通用术语它都懂

### 03｜什么样的规则才真正「有效」？

一句话四个原则：**短、具体、告诉为什么、持续更新。**

**具体** 的例子：

| 模糊写法（无效） | 具体写法（有效） |
|----------|----------|
| 测试一下你的修改 | 提交前跑 `npm test` |
| 保持目录整洁 | API 处理函数放在 `src/api/handlers/` 目录下 |
| 别把构建搞挂了 | 推代码前跑 `npm run typecheck` 检查类型 |
| 用好的命名 | 组件文件用 PascalCase，工具函数用 kebab-case |

**告诉为什么**：比如你写「不要在测试里写入生产数据库」，加一句「因为去年有次测试不小心把 users 表清空了，出过事故」，Claude 不光知道这条规则，还知道**规则的边界**。

**持续更新**：Claude 在哪儿犯错了两次以上，就加一条防御规则。但同样重要的是：**老规则要删**。错误的规则比没有规则更糟糕。

![](https://cdn.xiaolincoding.com//picgo/14-four-principles-grid-ff524710.png)

### 04｜CLAUDE.md 不只是一个文件

CLAUDE.md 是分层的：

- **项目根的 CLAUDE.md**：整个项目的通用约定，每次启动都加载
- **子目录的 CLAUDE.md**：比如 `frontend/CLAUDE.md` 写组件约定，按需加载
- **`~/.claude/CLAUDE.md` 全局**：跨项目的个人偏好

还有更进阶的：`.claude/rules/` 目录（模块化 CLAUDE.md）。每个主题一个文件，控制在 30 行以内。

每个 rules 文件可以加 YAML frontmatter，标注「这规则只在改某类文件的时候加载」：

```markdown
---
paths: ["**/*.test.ts", "**/*.spec.ts"]
---
# 测试规则
- 用 describe / it，不用 test()
- mock 外部依赖必须用 vi.mock
- 每个测试只写一个断言
- 别用 expect.anything()，断言要精确
```

这就叫 **path-scoped rules**（路径作用域规则）。

跨工具兼容技巧：把所有规则写在 AGENTS.md 里，CLAUDE.md 里只留一行 `@AGENTS.md`。

### 05｜/init 起步、/memory 维护

- `/init`：自动扫描代码库，生成 CLAUDE.md 草稿
- `/memory`：session 中途想加规则，直接输入 `/memory` 会弹出 CLAUDE.md 让你直接改

规则触发标准：**Claude 错两次以上，就加一条新规则。**

### 06｜可以参考的模板

```markdown
# 项目名称

一句话说清楚这个项目是做什么的。

## 技术栈
- React 18 + TypeScript + Tailwind CSS

## 常用命令
- `npm run dev` 启动开发服务器
- `npm test` 跑测试
- `npm run lint` 检查代码规范

## 代码规范
- 使用函数式组件，不用 class 组件
- 变量命名用驼峰命名法
- 组件文件名用 PascalCase

## 目录结构
- src/components/ - UI 组件
- src/hooks/ - 自定义 Hook
- src/utils/ - 工具函数

## 硬约束
- 不要修改 .env 文件
- 提交前必须跑 npm test
- 不要直接操作生产数据库
```

### 收尾：3 句话精华

1. CLAUDE.md 是配置不是文档，200 行以内，每条规则要具体可验证
2. 分层组织：项目根放通用规则、子目录放模块规则、全局放个人偏好
3. 持续维护：Claude 错两次就加规则，过时规则要删

---

## 文章四：Claude Code 如何应对百万行大型代码库？实战策略详解

> 原文链接：https://www.xiaolinnote.com/claudecode/playbook/cc_large_codebase.html
> 作者：公众号@小林coding

### 核心问题

大型代码库（百万行级别）对 Claude Code 的挑战：
- 上下文窗口有限，不可能一次读完所有代码
- 文件数量庞大，Claude 容易迷路
- 模块间依赖复杂，改一处可能影响多处

### 实战策略

#### 策略一：用 CLAUDE.md 画地图

在 CLAUDE.md 中写清楚项目的目录结构和模块职责，让 Claude 知道去哪找什么：

```markdown
## 项目结构
- src/auth/ - 认证模块（JWT + OAuth2）
- src/api/ - API 层（RESTful，统一错误处理）
- src/db/ - 数据库层（PostgreSQL + Prisma ORM）
- src/workers/ - 后台任务（Bull 队列）
```

#### 策略二：用 @ 精准定位

不要让 Claude 自己去翻文件，用 @ 直接告诉它看哪里：

```
请帮我优化 @src/api/handlers/user.ts 的错误处理逻辑
```

#### 策略三：分模块拆任务

大任务拆成小任务，每次只让 Claude 处理一个模块：
- 先改数据库层
- 再改 API 层
- 最后改前端调用

#### 策略四：用 Plan 模式先规划

复杂改动先用 Plan 模式出方案，确认影响范围后再动手。

#### 策略五：善用子代理（SubAgent）

让主 agent 派子代理去调研代码库的某个模块，子代理带着只读工具去翻代码，翻完把结果交回来。这样主 agent 的上下文不会被调研过程污染。

#### 策略六：利用 /compact 管理上下文

长时间工作后上下文会膨胀，用 /compact 压缩历史对话，腾出空间给新任务。

#### 策略七：Git 分支隔离

大改动在新分支上做，改坏了随时可以回滚，不影响主分支。

### 总结

应对大型代码库的核心思路：
1. 给 Claude 画地图（CLAUDE.md）
2. 精准定位（@）
3. 分而治之（拆模块）
4. 先规划再动手（Plan 模式）
5. 派侦察兵（子代理）
6. 管理记忆（/compact）
7. 安全网（Git 分支）
