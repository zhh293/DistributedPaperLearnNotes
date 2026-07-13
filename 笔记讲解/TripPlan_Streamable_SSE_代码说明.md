# TripPlan Streamable SSE 代码说明（含流程与异常场景）

本文基于以下两份代码进行讲解：

- [TripPlanServiceImpl.java](file:///e:/GaoDeMCPTest/src/main/java/com/zhh/handsome/gaodemcptest/service/TripPlanServiceImpl.java)
- [TripPlanStreamableSseStore.java](file:///e:/GaoDeMCPTest/src/main/java/com/zhh/handsome/gaodemcptest/service/TripPlanStreamableSseStore.java)

目标：在保留原有 AI 行程生成逻辑的前提下，实现 SSE 消息“不丢失、可断线续传、可历史回放”。

---

## 1. 总体设计

当前实现采用“两层模型”：

- 业务生成层：`TripPlanServiceImpl`
  - 负责原有 AI 行程生成、天气、路线、JSON 片段处理等业务流程
  - 在每次推送前，把事件追加到 Redis Stream
- 流式可靠层：`TripPlanStreamableSseStore`
  - 负责事件持久化（`XADD`）
  - 负责历史回放（`XRANGE`）
  - 负责实时续推（`XREAD BLOCK`）
  - 负责任务完成标记（`status key`）

这样可以保证：

- 连接断开时，消息仍在 Stream 中
- 重连时通过 `taskId + lastEventId` 精准补发
- 历史补发后无缝衔接实时流

---

## 2. 关键数据结构

`TripPlanStreamableSseStore` 内部使用两个 Key：

- `trip:sse:stream:{taskId}`
  - Redis Stream，存每条事件（含 `event`、`data`）
  - ID 由 Redis 自动分配（如 `1713500002000-0`），用于断点续传
- `trip:sse:status:{taskId}`
  - 任务完成状态（`DONE`）
  - 实时监听循环用它判断何时自然结束

默认配置：

- `trip.sse.stream.retention-seconds`（默认 300 秒）
- `trip.sse.stream.read-batch-size`（默认 100）
- `trip.sse.stream.block-seconds`（默认 5）

---

## 3. 主流程（按执行顺序）

### 3.1 建连入口：`streamPlan(...)`

代码位置：`TripPlanServiceImpl`

作用：

1. 解析 `taskId`（没有就生成 UUID）
2. 创建 `SseEmitter`
3. 注册回调（超时/完成/错误）
4. 异步启动“历史回放 + 实时续推”线程
5. 判断是否新任务
   - 若是新任务：启动原有 AI 生成流程
   - 若是已存在任务：仅复用已有流，不重复启动生成

这一步是“兼容旧逻辑 + 支持重连”的核心分叉点。

---

### 3.2 历史回放 + 实时续推：`resumeHistoryAndTailRealtime(...)`

代码位置：`TripPlanServiceImpl`

作用：

1. 调 `replayHistory(taskId, lastEventId, emitter)` 补发历史
2. 拿到新游标 `cursor`
3. 调 `streamRealtimeUntilDone(taskId, cursor, emitter, stop)` 进入实时监听
4. 若检测到任务已完成，主动 `completeEmitter()`

说明：

- 先历史，后实时，避免“断线期间消息空洞”
- 续推游标使用“历史最后一条消息 ID”，防止重复与漏发

---

### 3.3 业务生成：原有 AI 规划流程（保留）

代码位置：`TripPlanServiceImpl`

保留能力包括：

- 参数校验、景点抓取、Prompt 构建、模型流式解析
- `splitValidJson(...)`、`isValidJson(...)`、`processAiJsonFragment(...)`
- 路线生成、天气补充、日完成标记处理等

改造点是：业务每次推送前会先写 Stream（见第 4 节）。

---

### 3.4 推送完成：`sendDone(...) + markFinished(...)`

代码位置：`TripPlanServiceImpl`

作用：

- 发 `trip-done`
- 标记 `streamableSseStore.markFinished(taskId)`
- 连接自然收尾

这样即使客户端断开后重连，也能判断任务是否结束。

---

## 4. 推送函数改造说明（不改业务语义）

以下函数都在 `TripPlanServiceImpl` 中：

- `sendData(...)`
- `sendWeather(...)`
- `sendError(...)`
- `sendDone(...)`
- `sendTaskMeta(...)`

统一流程：

1. `appendForEmitter(...)`：先写 Stream，拿 `eventId`
2. `SseEmitter.event().id(eventId)...`：再推当前连接

结果：

- 在线用户即时看到数据
- 断线用户后续可补发同一条数据

---

## 5. Store 类函数逐个说明

文件：`TripPlanStreamableSseStore`

### `appendEvent(taskId, eventName, payload)`
- `XADD` 到 `trip:sse:stream:{taskId}`
- 事件字段：
  - `event`: 事件名（如 `trip-data`）
  - `data`: JSON 字符串
- 每次写入后刷新过期时间
- 返回 Redis Stream ID，供 SSE `id` 使用

### `replayHistory(taskId, lastEventId, emitter)`
- 当 `lastEventId = 0-0`：读初始历史
- 否则：读 `> lastEventId` 的历史
- 把历史逐条推给前端
- 返回最后一条推送 ID（cursor）

### `streamRealtimeUntilDone(taskId, startAfterEventId, emitter, stop)`
- 基于 cursor 执行 `XREAD BLOCK`
- 有新消息就推送并更新 cursor
- 无消息时检查 `isFinished(taskId)`，完成则退出
- IO 异常时停止（连接通常已断）

### `markFinished(taskId)` / `isFinished(taskId)`
- 写/读 `trip:sse:status:{taskId}=DONE`
- 用于控制实时循环退出

### `hasAnyEvent(taskId)`
- 判断是否已有历史消息
- 用于 `TripPlanServiceImpl` 判断“是否需要新建任务”

### `clearTask(taskId)`
- 清理 Stream 和状态 Key
- 用于后续后台清理策略

### `emitRecord(...)`
- 从 Stream Record 取 `id/event/data`
- 推为标准 SSE 事件（带 id）

### `toJson(...)` / `parseJsonOrRaw(...)`
- 写入时统一转 JSON 字符串
- 推送时尽量解析为 JSON；失败则原样字符串

---

## 6. 四种场景行为说明

### 场景 A：正常流程（不断线）

1. 前端连入：`taskId=新`，`lastEventId=0-0`
2. 历史为空
3. 业务不断产生日志/数据
4. 每条先 `XADD` 后推 SSE
5. 前端持续更新 `lastEventId`

结果：实时流畅，且每条事件都有可追溯 ID。

---

### 场景 B：中途断线，任务继续跑

1. 连接中断，`SseEmitter` 触发错误/完成回调
2. 后端业务仍在跑，`appendEvent` 继续写 Stream
3. 用户未收到的消息在 Stream 中积压

结果：不会丢消息。

---

### 场景 C：重连时任务已结束

1. 前端携带旧 `taskId + lastEventId`
2. 后端先 `replayHistory` 补齐漏消息
3. 检测到 `isFinished=true`
4. 连接可立即结束

结果：界面快速追平到最终状态。

---

### 场景 D：重连时任务未结束（最关键）

1. 先补历史，拿到 `cursor`
2. 立刻从 `cursor` 进入 `XREAD BLOCK`
3. 后续新消息无缝接上

结果：不会出现“历史补完和实时监听之间丢一条”的缝隙问题。

---

## 7. 特殊情况与边界

- `lastEventId` 为空：默认按 `0-0` 处理
- 历史为空但任务运行中：直接进入实时监听
- 历史很多：受 `read-batch-size` 控制（当前是单次读取，后续可扩展为分页回放）
- 连接异常：store 层停止实时循环，业务层可继续写 Stream
- 过期清理：默认 5 分钟，适合短时重连；若用户可能晚重连建议调大

---

## 8. 当前实现的优点与后续可优化

优点：

- 最小侵入：保留原 `TripPlanServiceImpl` 业务核心
- 可靠增强：先持久化再推送
- 可续传：`taskId + lastEventId` 即可恢复

可优化：

- 历史回放改为分页循环，避免长任务一次拉太多
- 事件字段增加 `type/seq/ts`，前端更易渲染与去重
- 增加后台清理任务（完成后一段时间自动 `clearTask`）
- 前端重连策略可统一封装（指数退避 + 自动带上 Last-Event-ID）

---

## 9. 你可以直接给前端的协作约定

- 首次请求：
  - `taskId` 为空
  - `Last-Event-ID` 为空
- 服务端第一条会推 `trip-meta`（含 `taskId`）
- 前端保存：
  - 最新 `taskId`
  - 最新事件 `id`
- 断线重连时带：
  - `taskId`
  - `Last-Event-ID`

这样就能稳定实现“不断点续传”。
