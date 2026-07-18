# MP 需求数据修复 — 简历亮点

## MP 平台与业务全链路

### 什么是 MP 平台

MP（MultiPilot）是美团内部的需求管理与 AI Agent 协作平台，类似于内部的 Jira/Linear，但核心区别在于：MP 不仅是需求管理工具，更是 AI Agent 驱动的软件交付流水线的编排中枢。在美团推进 SDD（Spec Driven Development，规范驱动开发）的背景下，MP 平台承载了从需求创建到上线交付的全流程。

核心概念：

- **Workspace（工作空间）**：按业务团队划分的工作区，每个 workspace 有唯一的 `workspace_id`（UUID）和 `slug`（可读标识，如 `rms-innovate`、`bpaas`）。
- **Issue（需求/缺陷条目）**：工作空间下的具体需求或缺陷，每个 issue 有 `number`（数字编号）、`identifier`（带前缀标识，如 `WS-297`）和 `title`。
- **Agent 工作流**：MP 支持在 Issue 中 @mention Agent，由 Agent 自动执行 PRD 评审、技术方案评审、代码审查、测试设计、测试执行等工作，形成 AI 驱动的交付流水线。
- **External API**：MP 提供外部 API 接口，通过 PAT（Personal Access Token）鉴权，支持 workspace lookup、issue 查询等操作，供外部系统集成。

### 员工使用 MP 的全链路（以一个真实需求为例）

下面用一个具体的需求场景来说明。假设餐饮 SaaS 业务平台团队要做一个需求：**「非叫号和 KDS 商户下线交易履约单同步订单中心」**——简单说就是，某些类型的商户（不做叫号、不用 KDS 后厨显示系统）在关闭交易时，他们的履约单数据也要同步到订单中心去。

这个需求涉及后端服务改动，需要走完整的 SDD 交付流程。以下是它在 MP 平台上的完整生命周期：

**第一步：PM 创建需求。** 产品经理小李打开 MP 平台的 `bpaas`（业务平台工作空间），点击「新建 Issue」，填写标题「非叫号和KDS商户下线交易履约单同步订单中心」，附上 PRD 文档链接，描述验收标准：商户下线交易后 5 分钟内履约单同步到订单中心，支持重试和幂等。MP 自动分配 Issue 编号 `661`，生成 identifier `BPAS-661`。此时 Issue 的 URL 是 `https://saas.sankuai.com/mp-app/bpaas/issues/661`。

**第二步：PRD 评审（PRD Gate）。** Issue 创建后，研发侧的 Scrum Master Agent 自动启动 PRD 评审流程，调度 PRD Evaluator Agent 检查这份 PRD。Agent 发现两个问题：一是没写清楚「下线交易」具体指哪些交易类型（是关店、停业还是临时挂起？），二是没有定义同步失败的补偿机制。Agent 在 Issue 评论中列出这些待确认项，打回给 PM 修改。小李补充说明后重新提交，PRD Evaluator 评审通过，生成 PRD-SPEC-DOC。同时，这次评审的结论（passed + 拦截了 2 个问题）通过 hss-pub-sdd-test-report Skill 写入 cd-ops-center 的 `test_report_facts` 表，`requirement_key` 为 `bpaas-workspace-uuid__661`。

**第三步：技术方案评审（Design Gate）。** PRD 评审通过后，研发老王产出技术方案：新增一个 MQ 消费者监听商户状态变更事件，调用订单中心接口同步履约单，失败走定时补偿任务。Design Evaluator Agent 评审技术方案，检查接口设计是否合理（同步接口是否幂等？重试策略是否会导致重复同步？）、数据模型是否完整。同时 Test Design Agent 基于 PRD 和技术方案生成测试设计：PRD 层梳理出正常流程（关店→同步）、异常流程（同步失败→重试→补偿）和边界条件（并发关店、网络超时）；Design 层补充接口契约测试和 MQ 消费幂等性测试。评审结论和测试设计产物同样写入事实库。

**第四步：编码与 Code 评审（Code Gate）。** 老王用 MP 平台的 Coding Agent 或本地 IDE 进行开发，拆成 3 个 Task：新增 MQ 消费者、实现同步逻辑、增加补偿任务。每个 Task 提交 PR 后，Code Reviewer Agent 自动审查代码——检查异常处理是否完善、是否有线程安全问题、日志是否充分。同时触发自动化测试：单元测试覆盖同步逻辑的核心分支，契约测试验证与订单中心的接口约定。测试结果（覆盖率 85%、自动化通过率 100%）写入事实库。

**第五步：测试执行与报告生成。** QA 测试同学小赵在 VCS 上创建测试计划，执行人工测试（正常关店同步、异常重试、并发场景）和自动化回归。测试过程中发现一个 Bug：商户快速连续关店开店时，补偿任务会重复同步。老王修复后回归通过。测试完成后，小赵在 MP Issue 中 `@TestReportAgent`，Agent 从 cd-ops-center 事实库读取这个需求的全部测试事实——PRD 层（评审通过，拦截 2 项）、Design 层（测试设计完成，覆盖正常/异常/边界）、Code 层（覆盖率 85%，自动化 100%，发现 1 个并发 Bug 已修复）——汇总成一份测试报告，结论是「✅ 通过，建议上线」，写回 MP Issue 并在 cd-ops-center 看板展示。看板上这条需求的 `source_url` 指向 `https://saas.sankuai.com/mp-app/bpaas/issues/661`，小赵点击就能跳转到 MP Issue 查看完整的交付过程和测试报告。

**第六步：上线交付。** 测试报告通过后，需求进入灰度发布。先灰度 10% 的商户观察一天，监控同步成功率（99.5%+）、同步延迟（< 5 分钟）、订单中心错误率等指标，确认无异常后全量上线。整个交付过程的测试事实、风险决策、质量数据都沉淀在 cd-ops-center 中，后续可以追溯和审计。

**这就是 MP + cd-ops-center 一起做的事情：** PM 在 MP 上提需求，Agent 自动评审和测试，测试事实存到 cd-ops-center，TestReportAgent 基于事实生成报告，QA 和 TL 在 cd-ops-center 看板上查看所有需求的测试状态和报告。而 `source_url` 就是连接两个系统的桥梁——看板上点一下就能跳到 MP Issue。

### cd-ops-center 在链路中的角色

cd-ops-center 是软件研发部持续交付运维平台，在上述链路中承担两个核心职责：

一是**测试事实存储**：通过 `test_report_requirements`（需求表）、`test_report_facts`（事实表）、`test_report_artifacts`（产物表）等表，存储 SDD 各阶段的测试工作事实。SDD Agent 在 PRD / Design / Code 各阶段执行检查后，通过 hss-pub-sdd-test-report Skill 将结论写入这些表。TestReportAgent 生成报告时，从这些表读取事实作为证据。

二是**测试报告看板**：提供 `https://saas.sankuai.com/cd/ops/test-report-dashboard.html` 看板，QA 和 TL 可以查看所有需求的测试工作上报情况、三层测试状态（passed / failed / missing）和测试报告链接。

其中 `test_report_requirements` 表是核心——每条记录通过 `requirement_key`（格式 `{workspace_id}__{issue_number}`）关联 MP 平台上的 Issue，`source_url` 字段存储 MP Issue 的前端跳转链接。用户在看板点击某条需求时，通过 `source_url` 跳转到 MP 平台查看 Issue 详情。

### 数据修复要解决的问题（继续上面的例子）

现在假设过了一段时间，`bpaas` 这个 workspace 因为调整，迁移到了新的工作空间，slug名字 从 `bpaas` 改成了 `bpaas-v2`，workspace_id则是不变的。同时上面那个需求 `BPAS-661` 在 MP 上的标题也被 PM 改成了更准确的描述「非叫号商户关店时履约单同步订单中心」。

这时候 cd-ops-center 数据库里的问题就暴露了：

1. **source_url 失效**：数据库里存的还是 `https://saas.sankuai.com/mp-app/bpaas/issues/661`，但实际 URL 已经变成了 `https://saas.sankuai.com/mp-app/bpaas-v2/issues/661`。小赵在看板上点击这条需求，跳转到 MP 页面直接 404，看不到 Issue 详情和测试报告。

2. **workspace 信息过时**：metadata_json 里的 `workspace_slug` 还是 `bpaas`，TestReportAgent 下次想给这个需求刷新测试报告时，用旧 slug 去调 MP API 查不到 Issue，报告无法更新。

3. **title 过时**：数据库里的标题还是旧的「非叫号和KDS商户下线交易履约单同步订单中心」，看板上展示的是过时信息，QA 可能以为这是另一个需求。

4. **无效数据未清理**：有些 Issue 在 MP 上已经被删除了（比如需求被砍掉了），但 cd-ops-center 数据库里还留着，看板上显示一堆 missing 状态的记录，干扰判断。

全表约 1200 条记录，涉及 12+ 个 workspace，这种问题不是个例而是普遍存在。且定时任务会反复执行，直接每条调 2 次 API（lookup + getIssue）耗时 6+ 分钟，存在性能挑战。如果不修复，看板上的需求链接会大量 404，TestReportAgent 无法为这些需求生成完整的测试报告，整个 SDD 交付流程的可追溯性和可信度都会打折扣。

## 方案迭代过程

这个修复系统不是一次性设计到位的，而是经过四轮迭代，每一轮都是上线跑完后发现实际问题再针对性优化。

### V1：逐条调 API + 逐条写 DB（6 分钟）

最初的方案很直接：游标分页遍历全表，每条记录调两次 MP API（lookup 获取 workspace 信息、getIssue 验证 issue 并获取 title），根据 getIssue 的状态码决定是更新还是软删除，然后逐条 `UPDATE` 写回数据库。

上线后发现两个问题：一是 1200 条记录每条都要调 lookup API，但实际只有 12 个 workspace，同一个 workspace 的 slug 和 name 在一次任务里根本不会变，重复调了 1188 次完全浪费。二是逐条写 DB，1200 条就是 1200 次 RTT，加上每次 API 调用 200-300ms 的网络延迟，整体跑下来 6 分多钟。

### V2：workspace 内存缓存 + 游标分页优化（仍 6 分钟）

第一个优化是加 `ConcurrentHashMap` 做 workspace 内存缓存，lookup API 调用从 1200 次降到 12 次。同时确认游标分页 `WHERE id > lastId` 避免了 OFFSET 深度分页的性能退化。

但这轮优化后整体耗时几乎没有下降——瓶颈不在 lookup 而在 getIssue（每条都要调，无法缓存）和逐条写 DB。1200 次 DB 写入的 RTT 才是主要耗时。

### V3：攒批写入 + 事务隔离（首次 55 秒）

第三个版本做了两件事。一是把逐条 `UPDATE` 改成攒批 200 条一次 `CASE WHEN` 批量 SQL，DB 写入 RTT 从 1200 次降到 6 次。二是把网络请求和 DB 写入拆成两个阶段：计算段（调 API、组装数据）无事务不占 DB 连接，写入段通过独立的 `RequirementBatchWriter` 组件让 `@Transactional` 生效，事务内只做纯 DB 写，持有时间从分钟级降到毫秒级。

首次执行从 6 分钟降到 55 秒。但定时任务每天跑一次，每次都把这 1200 条重新调一遍 API，其中 95%+ 的数据上次已经修复过了，完全是浪费。

### V4：缓存表 + TTL + 只缓存成功记录（二次 4 秒，闭环）

最后一轮引入缓存表。每批读取后先批量查缓存，命中的直接跳过不调 API。但这里踩了坑：最初的设计是所有处理过的记录都写缓存，包括错误状态的。后来发现这样会导致错误数据被永久固化——如果某条记录因为 API 异常走了错误分支，写缓存后下次就永远不会再重新校验了。

最终方案确定了核心原则：只有 lookup 和 getIssue 都返回 200 时才写缓存，错误状态不缓存。对于大概率由网络抖动引起的非 200 响应，使用指数退避重试 3 次，成功则继续处理，仍失败则跳过等下次定时任务重新调 API。缓存加 30 天 TTL，保证修复后 MP 上又发生的变化也能被重新校验。404 触发软删除但不写缓存（软删除后天然被查询过滤）。这样形成了完整闭环。

二次执行从 6 分钟降到 4 秒，95%+ 记录命中缓存跳过。

## 关键技术设计与性能提升

### 1. 缓存表 + TTL 机制 — 二次执行耗时从 6 分钟降至 4 秒，且 30 天自动重验保证数据不过时

定时任务每天执行，但绝大多数数据在上次已经修复过，不需要重复调 API。新增 `test_report_requirements_cache` 缓存表，记录已修补过的 `requirement_key` 和 `cached_at` 时间戳。每批读取后先批量查缓存（`IN` 查询走唯一索引，1 次 RTT），命中的直接跳过不调 API。

关键设计：缓存有 30 天 TTL。查询时 `WHERE requirement_key IN (...) AND cached_at > DATE_SUB(NOW(), INTERVAL 30 DAY)`，超过 30 天的缓存记录视为过期，需要重新调 API 校验。这样即使某个 issue 在修复后被删除、标题被修改、或 workspace 再次迁移，30 天后也会自动重新校验，不会永久停留在过期状态。

缓存表写入与原表更新在同一个 `@Transactional` 事务内，保证原子性；使用 `INSERT IGNORE` 幂等写入，重复执行不报错。

效果：首次执行后，后续定时任务 95%+ 记录命中缓存跳过，API 调用量从 2400+ 次降到 0，执行时间从 6 分钟降到 4 秒。30 天后自动触发重验，形成闭环。

### 2. 游标分页 + workspace 内存缓存 — 消除深度分页和重复 API 调用

全表 1200 条记录需要逐条处理，传统 LIMIT/OFFSET 分页在扫描到后面批次时，MySQL 需要扫描并丢弃前面的行，越往后越慢。改用游标分页：`WHERE id > lastId ORDER BY id ASC LIMIT 500`，每次查询直接定位到上一批最后一条记录之后，无论扫到第几页查询性能都恒定，O(1) 定位无需回扫。

同时，1200 条记录涉及 12+ 个 workspace，但每条记录都要调 lookup API 获取 workspace 信息。同一个 workspace 的 slug 和 name 在一次任务执行中不会变，没必要重复查。用 `ConcurrentHashMap` 做内存缓存，第一次查到的 workspace 信息缓存起来，后续相同 workspace_id 直接命中内存。lookup API 调用从 1200 次降到 12 次（每个 workspace 只查一次）。

效果：游标分页保证查询性能恒定不退化，workspace 内存缓存将 lookup API 调用降低 100x。

### 3. 网络请求与 DB 事务隔离 + 攒批写入 — DB 连接持有从分钟级降至毫秒级

将修复流程拆为"计算段"和"写入段"：计算段（`buildUpdate`）无事务、含网络请求，调 MP API 获取最新数据并组装 PO，网络延迟不占用任何 DB 连接；写入段（`flushBatch`）通过独立的 `RequirementBatchWriter` 组件让 Spring `@Transactional` 代理生效，事务内仅做纯 DB 批量写，持有时间 < 100ms。写用攒批（200 条/批，`CASE WHEN` 批量 SQL）。

效果：DB 写入 RTT 从 1200 次降到 6 次，连接持有时间从分钟级降到毫秒级。

### 4. 只缓存成功记录 + 软删除确认机制 — 保证缓存数据可信，无效数据安全清理

之前所有非 200 响应统一返回 null 跳过，导致需修复的数据被误跳过。将 `getIssue` 返回值从 String 改为 `IssueResult` 对象，透传 HTTP 状态码，按以下规则分级处理：

**核心原则：只有 lookup 和 getIssue 都返回 200 时才写缓存。** 只有数据被 MP API 双重确认正确时，才认为这条记录已修复并写入缓存表。其他状态都不写缓存，记录保持未修复状态，下次定时任务会重新调 API。

具体分支：

- **lookup 200 + getIssue 200**：数据确认正确。更新 source_url / title / metadata + 写缓存。下次命中缓存跳过，30 天后 TTL 过期重新校验。
- **lookup 200 + getIssue 404**：lookup 成功说明 slug 是 MP 确认的最新值，用这个 slug 去查 issue 返回 404，说明 issue 确实已被删除。软删除原表记录（`SET deleted_at = NOW(3)`）。不需要写缓存——软删除后 `deleted_at IS NOT NULL`，后续查询天然过滤掉，不会产生冗余 API 调用。
- **lookup 404**：workspace_id 在 MP 上已不存在。这种情况下 issue 也不会存在，软删除原表记录。同样不写缓存。
- **其他非 200 状态**：大概率是网络抖动引起的临时错误。使用指数退避重试（间隔 1s → 2s → 4s），最多 3 次。重试成功走正常流程，仍失败则跳过不缓存，下次定时任务重新调 API。

这个设计保证了闭环：
- 正常记录 → 修复 + 缓存（30 天内跳过）→ TTL 过期后重新校验
- 被删除的 issue → 确认后软删除（天然排除）
- 网络抖动 → 指数退避重试 3 次，仍失败则不缓存，下次自动重试
- 修复后 MP 上又发生变化 → 30 天 TTL 过期后重新校验
- 没有任何记录会被永久卡在错误状态

## 性能数据

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| 首次执行耗时 | 6+ 分钟 | 55 秒 | 6.5x |
| 二次执行耗时 | 6+ 分钟 | 4 秒 | 90x |
| API 调用次数（二次执行） | 2400+ | 0 | 完全消除 |
| DB 写入 RTT | 1200 次 | 6 次 | 200x |
| DB 连接持有时间 | 分钟级 | 毫秒级 | ~1000x |
| lookup API 调用 | 1200 次 | 12 次 | 100x |

修复结果：扫描 1188 条，修复 52 条，缓存跳过 966 条，软删除 53 条，失败 0 条。

## 简历表述

- 设计并实现 MP 平台需求数据自动化修复系统，处理 **1200+** 条数据，涉及 **12+** 个 workspace
- 通过攒批写入和事务隔离优化，DB 写入 RTT 降低 **200x**，连接持有时间从分钟级降至毫秒级
- 引入缓存表 + TTL 机制，二次执行耗时从 **6 分钟降至 4 秒**（**90x** 提升），30 天自动重验保证数据不过时
- 设计错误分级处理机制，仅缓存 API 双重确认成功的记录，网络抖动通过指数退避重试 3 次容错，软删除无效数据 **53 条**，30 天 TTL 保证长期一致性

## 面试要点

**Q: 为什么不直接用 Spring Batch？**
数据量在千级别，引入 Spring Batch 过重。自定义方案更灵活，能精确控制攒批大小、事务边界和缓存策略，代码量不到 1000 行，维护成本低。

**Q: 缓存表会不会有一致性问题？比如修复后 MP 上的 issue 又被删了怎么办？**
缓存有 30 天 TTL。修复成功写入缓存后，30 天内跳过不调 API。超过 30 天缓存自动过期，下次定时任务会重新调 API 校验。如果 issue 在 MP 上已被删除，重验时 getIssue 返回 404，此时 lookup 成功说明 slug 是正确的，确认 issue 确实被删除，触发软删除。整个链路是闭环的：修复 → 缓存（30天）→ 过期重验 → 发现变化 → 更新或软删除 → 重新缓存。

**Q: 为什么 404 不写缓存？**
404 触发软删除后，记录的 `deleted_at` 不为空，后续查询 `WHERE deleted_at IS NULL` 天然过滤掉，不会产生冗余 API 调用，所以不需要写缓存。更重要的是，软删除有前提条件：只有 lookup 成功（slug 被 MP 确认是最新值）后的 getIssue 404 才触发软删除。如果 lookup 本身失败，不会触发软删除，记录保持原状下次重试。这样保证了只有 MP 双重确认 issue 已删除时才执行软删除，不会误删。

**Q: 为什么不从根上改 source_url，用 workspace_id 代替 slug 拼链接？**
MP 前端路由只认 slug，拿 workspace_id 拼的 URL 打不开，这是 MP 平台的限制。而且 source_url 是 cd-ops-center 的既有字段，历史数据全都是 slug 格式，改生成逻辑只能管新写入的数据，老数据还是得修。所以根因修复和批量修复不冲突——根因修复防新增，批量修复治存量，两件事都得做。

**Q: 为什么不用事件驱动，让 MP 在 slug 变更时推通知？**
MP 没有提供 workspace slug 变更的 webhook 或 MQ 通知通道。要做事件驱动，得推动 MP 团队加这个能力，跨团队排期，周期不可控。批量修复是在当前条件下能立刻落地的务实选择——不依赖外部团队改动，自己就能把存量脏数据修干净。

**Q: 404 怎么区分"issue 被删除"和"没权限查看"？**
用的 PAT token 是管理员级别的，对全部 12 个 workspace 都有访问权限。在这个前提下，getIssue 返回 404 就是 issue 确实不存在，不是权限问题。如果 PAT token 权限不全，那些没权限的 workspace 下的 issue 会被误判为 404 误删——所以软删除的前置条件是 lookup 必须成功，只有 MP 双重确认（slug 有效 + issue 不存在）才执行软删除。

**Q: 30 天 TTL 意味着数据最多有 30 天不一致窗口，业务能接受吗？**
这是 test reporting 看板的数据，不是实时生产链路。QA 看的是"这个需求测过了没、测试报告在哪"，不是"这个需求标题是不是最新到秒级"。标题晚 30 天更新对测试报告场景完全可接受，换来的是 95%+ 的记录在定时任务里直接跳过、执行时间从 6 分钟降到 4 秒。这个 trade-off 是合理的。

**Q: 缓存表为什么不用 Redis？**
1200 条数据，缓存表就 1200 行，一次 IN 查询走唯一索引，1ms 级别，用 DB 表完全够。引 Redis 要额外维护一个中间件、处理缓存一致性、加运维成本，这个数据量根本不值得。如果数据量涨到十万级以上，再考虑迁移 Redis 也不迟。
