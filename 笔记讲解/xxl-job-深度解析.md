# xxl-job 深度解析：调度器 + 执行器全流程

> 基于源码版本：Spring Boot 4.0.5 + Spring 7.0.6 + Java 17
>
> 本文结合项目所有核心类，从执行器注册、调度触发、任务执行到结果回调，完整还原 xxl-job 的每一个细节。

---

## 目录

1. [整体架构概览](#1-整体架构概览)
2. [模块划分与核心类地图](#2-模块划分与核心类地图)
3. [调度中心启动流程（XxlJobAdminBootstrap）](#3-调度中心启动流程)
4. [执行器注册与发现](#4-执行器注册与发现)
   - 4.1 [执行器侧：EmbedServer + ExecutorRegistryThread](#41-执行器侧-embedserver--executorregistrythread)
   - 4.2 [调度中心侧：JobRegistryHelper](#42-调度中心侧-jobregistryhelper)
   - 4.3 [XxlJobGroup 的 group 语义](#43-xxljobgroup-的-group-语义)
5. [调度算法详解（JobScheduleHelper）](#5-调度算法详解-jobschedulehelper)
   - 5.1 [scheduleThread：预读线程](#51-schedulethread预读线程)
   - 5.2 [ringThread：时间轮线程](#52-ringthread时间轮线程)
   - 5.3 [过期任务补偿：MisfireStrategy](#53-过期任务补偿-misfirestrategy)
   - 5.4 [下次触发时间计算：refreshNextTriggerTime](#54-下次触发时间计算-refreshnexttriggertime)
6. [触发流程详解（JobTriggerPoolHelper → JobTrigger）](#6-触发流程详解)
   - 6.1 [快慢线程池：JobTriggerPoolHelper](#61-快慢线程池-jobtriggerpoolhelper)
   - 6.2 [路由选址：ExecutorRouteStrategyEnum（9种路由）](#62-路由选址-executorroutestrategyenum)
   - 6.3 [触发执行器：JobTrigger.processTrigger](#63-触发执行器-jobtriggerprocesstrigger)
7. [执行器内部架构](#7-执行器内部架构)
   - 7.1 [执行器启动：XxlJobExecutor.start()](#71-执行器启动-xxljobexecutorstart)
   - 7.2 [Spring 集成：XxlJobSpringExecutor 与 @XxlJob 扫描](#72-spring-集成-xxljobspringexecutor-与-xxljob-扫描)
   - 7.3 [Netty HTTP 服务：EmbedServer + EmbedHttpServerHandler](#73-netty-http-服务-embedserver--embedhttpserverhandler)
   - 7.4 [请求处理核心：ExecutorBizImpl](#74-请求处理核心-executorbizimpl)
8. [任务执行详解（JobThread）](#8-任务执行详解-jobthread)
   - 8.1 [JobThread 的生命周期](#81-jobthread-的生命周期)
   - 8.2 [阻塞策略（ExecutorBlockStrategyEnum）](#82-阻塞策略-executorblockstrategyenum)
   - 8.3 [超时控制机制](#83-超时控制机制)
   - 8.4 [JobHandler 的三种实现](#84-jobhandler-的三种实现)
9. [任务上下文：XxlJobContext + XxlJobHelper](#9-任务上下文-xxljobcontext--xxljobhelper)
10. [回调与可靠性保障](#10-回调与可靠性保障)
    - 10.1 [执行器侧：TriggerCallbackThread](#101-执行器侧-triggercallbackthread)
    - 10.2 [调度中心侧：JobCompleteHelper + JobCompleter](#102-调度中心侧-jobcompletehelper--jobcompleter)
    - 10.3 [子任务触发](#103-子任务触发)
    - 10.4 [失败重试与结果丢失兜底](#104-失败重试与结果丢失兜底)
11. [GLUE 动态任务](#11-glue-动态任务)
    - 11.1 [GlueTypeEnum：支持的任务类型](#111-gluetypeenum支持的任务类型)
    - 11.2 [GlueFactory：Groovy 动态编译](#112-gluefactorygroovy-动态编译)
    - 11.3 [ScriptJobHandler：脚本执行](#113-scriptjobhandler脚本执行)
12. [完整调用链路总结](#12-完整调用链路总结)
13. [如何接入 xxl-job（实操指南）](#13-如何接入-xxl-job实操指南)

---

## 1. 整体架构概览

xxl-job 的架构思想是"调度与执行分离"，整个系统由两种角色组成：

**调度中心（xxl-job-admin）** 是一个独立部署的 Spring Boot Web 应用，负责所有的任务管理、调度决策、注册发现和日志查询。它本身不执行任何业务逻辑，只负责"告诉"执行器该执行什么任务。

**执行器（Executor）** 是业务应用，只需要引入 `xxl-job-core` 依赖并做简单配置，就能把自己注册到调度中心，并对外暴露一个 Netty HTTP 服务接受触发请求。

两者之间的通信全部基于简单的 HTTP，没有引入任何重型中间件（MQ、RPC 框架等），这也是 xxl-job 一直标榜"轻量"的核心原因。

```
┌─────────────────────────────────────────────┐
│              xxl-job-admin（调度中心）         │
│                                             │
│  JobScheduleHelper ──→ JobTriggerPoolHelper │
│  (时间轮/预读扫描)        (快/慢线程池)         │
│                              │              │
│                         JobTrigger          │
│                        (路由选址 + HTTP触发)  │
│                                             │
│  JobRegistryHelper     JobCompleteHelper    │
│  (注册表维护)            (回调 + 结果丢失检测)  │
└──────────────────────┬──────────────────────┘
                       │  HTTP
         ┌─────────────┼───────────────┐
         ↓             ↓               ↓
  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
  │  Executor-1  │ │  Executor-2  │ │  Executor-3  │
  │             │ │             │ │             │
  │ EmbedServer │ │ EmbedServer │ │ EmbedServer │
  │ (Netty HTTP)│ │ (Netty HTTP)│ │ (Netty HTTP)│
  │             │ │             │ │             │
  │ JobThread-A │ │ JobThread-B │ │ JobThread-C │
  └─────────────┘ └─────────────┘ └─────────────┘
         │                                  │
         └──────────── HTTP 回调 ────────────┘
                       (执行结果)
```

---

## 2. 模块划分与核心类地图

### xxl-job-core（公共基础库）

这是所有模块都依赖的公共库，包含执行器的完整实现和与调度中心通信的接口定义。

| 包路径 | 核心类 | 职责 |
|---|---|---|
| `executor` | `XxlJobExecutor` | 执行器基类，管理 Handler/Thread 两张注册表 |
| `executor.impl` | `XxlJobSpringExecutor` | Spring 集成版，扫描 `@XxlJob` 注解 |
| `server` | `EmbedServer` | Netty HTTP 服务，暴露 5 个接口给调度中心 |
| `thread` | `JobThread` | 每个 Job 独占一条线程，内置队列 |
| `thread` | `TriggerCallbackThread` | 异步批量回调执行结果给调度中心 |
| `thread` | `ExecutorRegistryThread` | 定时心跳注册 |
| `thread` | `JobLogFileCleanThread` | 定时清理过期日志文件 |
| `openapi.impl` | `ExecutorBizImpl` | 处理调度中心发来的 run/kill/log 等请求 |
| `context` | `XxlJobContext` | ThreadLocal 存储当前任务上下文 |
| `context` | `XxlJobHelper` | 用户 API：获取参数、写日志、设置执行结果 |
| `handler.impl` | `MethodJobHandler` | BEAN 模式的 Handler 实现（反射调用方法） |
| `handler.impl` | `GlueJobHandler` | GLUE(Java) 模式的 Handler 实现 |
| `handler.impl` | `ScriptJobHandler` | 脚本类 GLUE 的 Handler 实现 |
| `glue` | `GlueFactory` | Groovy 动态编译，缓存 Class |
| `constant` | `ExecutorBlockStrategyEnum` | 阻塞策略（串行/丢弃/覆盖） |
| `constant` | `GlueTypeEnum` | GLUE 类型枚举 |

### xxl-job-admin（调度中心）

| 包路径 | 核心类 | 职责 |
|---|---|---|
| `scheduler.config` | `XxlJobAdminBootstrap` | 调度中心的总入口，管理所有子模块的生命周期 |
| `scheduler.thread` | `JobScheduleHelper` | 双线程调度引擎：预读线程 + 时间轮线程 |
| `scheduler.thread` | `JobTriggerPoolHelper` | 快/慢两个线程池，异步执行触发逻辑 |
| `scheduler.thread` | `JobRegistryHelper` | 执行器注册监控，清理死节点，刷新地址列表 |
| `scheduler.thread` | `JobCompleteHelper` | 处理执行器回调；检测结果丢失（10分钟超时） |
| `scheduler.thread` | `JobLogReportHelper` | 定时统计日志报表（成功/失败/进行中） |
| `scheduler.thread` | `JobFailAlarmMonitorHelper` | 监控失败任务，触发报警（Email 等） |
| `scheduler.trigger` | `JobTrigger` | 触发核心：加载数据、路由选址、HTTP 调用 |
| `scheduler.route` | `ExecutorRouteStrategyEnum` | 9 种路由策略枚举 |
| `scheduler.complete` | `JobCompleter` | 处理回调：更新日志、触发子任务 |
| `scheduler.misfire` | `MisfireStrategyEnum` | 过期任务补偿策略（跳过 / 补偿一次） |

---

## 3. 调度中心启动流程

调度中心的总入口是 `XxlJobAdminBootstrap`，它实现了 Spring 的 `InitializingBean` 接口，在所有 Bean 初始化完成后自动调用 `afterPropertiesSet()`，进而触发 `doStart()`。

```java
// XxlJobAdminBootstrap.java
@Component
public class XxlJobAdminBootstrap implements InitializingBean, DisposableBean {

    @Override
    public void afterPropertiesSet() throws Exception {
        adminConfig = this;   // 保存单例引用，供全局访问
        doStart();
    }

    private void doStart() throws Exception {
        // ① 启动快/慢触发线程池
        jobTriggerPoolHelper = new JobTriggerPoolHelper();
        jobTriggerPoolHelper.start();

        // ② 启动执行器注册监控线程
        jobRegistryHelper = new JobRegistryHelper();
        jobRegistryHelper.start();

        // ③ 启动失败报警监控线程
        jobFailAlarmMonitorHelper = new JobFailAlarmMonitorHelper();
        jobFailAlarmMonitorHelper.start();

        // ④ 启动回调处理线程 + 结果丢失检测线程（依赖 ① 的线程池）
        jobCompleteHelper = new JobCompleteHelper();
        jobCompleteHelper.start();

        // ⑤ 启动日志报表统计线程
        jobLogReportHelper = new JobLogReportHelper();
        jobLogReportHelper.start();

        // ⑥ 启动调度引擎（依赖 ①）
        jobScheduleHelper = new JobScheduleHelper();
        jobScheduleHelper.start();
    }
}
```

启动顺序有严格的依赖关系：`JobScheduleHelper` 和 `JobCompleteHelper` 都需要先有 `JobTriggerPoolHelper` 才能工作，所以触发线程池必须最先启动。

`XxlJobAdminBootstrap.getExecutorBiz(address)` 是一个工厂方法，用来获取调度中心与特定执行器通信的 HTTP 客户端代理，内部有 `ConcurrentHashMap` 缓存，相同地址复用同一个客户端：

```java
private static ConcurrentMap<String, ExecutorBiz> executorBizRepository = new ConcurrentHashMap<>();

public static ExecutorBiz getExecutorBiz(String address) throws Exception {
    ExecutorBiz executorBiz = executorBizRepository.get(address);
    if (executorBiz != null) {
        return executorBiz;  // 命中缓存直接返回
    }
    // 用 HttpTool 动态代理创建 HTTP 客户端
    executorBiz = HttpTool.createClient()
            .url(address)
            .timeout(timeout * 1000)
            .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken)
            .proxy(ExecutorBiz.class);
    executorBizRepository.put(address, executorBiz);
    return executorBiz;
}
```

---

## 4. 执行器注册与发现

注册与发现是整个系统的基础，解决的是"调度中心如何知道哪些执行器在线、在哪里"的问题。

### 4.1 执行器侧：EmbedServer + ExecutorRegistryThread

**EmbedServer.start()** 在 Netty 服务绑定端口成功后，立即调用 `startRegistry(appname, address)`：

```java
// EmbedServer.java
public void startRegistry(final String appname, final String address) {
    ExecutorRegistryThread.getInstance().start(appname, address);
}
```

**ExecutorRegistryThread** 是一个单例守护线程，启动后进入无限循环，每隔 `Const.BEAT_TIMEOUT`（30秒）向所有配置的调度中心地址发送一次注册请求：

```java
// ExecutorRegistryThread.java
registryThread = new Thread(() -> {
    // 注册阶段：循环心跳
    while (!toStop) {
        RegistryRequest registryParam = new RegistryRequest(
            RegistType.EXECUTOR.name(),  // 类型：执行器
            appname,                     // 应用名，如 "my-job-executor"
            address                      // 执行器地址，如 "http://192.168.1.100:9999/"
        );
        // 遍历所有 admin 地址，任一成功即 break
        for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
            Response<String> registryResult = adminBiz.registry(registryParam);
            if (registryResult != null && registryResult.isSuccess()) {
                break;
            }
        }
        TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);  // 等待 30 秒
    }

    // 停止阶段：主动注销
    RegistryRequest registryParam = new RegistryRequest(...);
    for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
        adminBiz.registryRemove(registryParam);  // 告知 admin 自己下线了
    }
});
```

关键点有两个。第一，注册时既包含应用名（`appname`），也包含地址（`address`），调度中心据此维护 `appname → List<address>` 的映射关系。第二，线程停止时不是直接退出，而是先主动发一次 `registryRemove`，这样调度中心能立刻感知到执行器下线，而不需要等待 90 秒超时。

**AdminBiz 是怎么来的？** 在 `XxlJobExecutor.initAdminBizList()` 中，会把配置文件里的 `adminAddresses`（可以是逗号分隔的多个地址）解析成多个 `AdminBiz` 代理对象：

```java
// XxlJobExecutor.java
private void initAdminBizList(String adminAddresses, String accessToken, int timeout) {
    for (String address : adminAddresses.split(",")) {
        // 拼接 /api 路径：http://localhost:8080/xxl-job-admin/api
        String finalAddress = address.trim().endsWith("/")
            ? address + "api"
            : address + "/api";

        AdminBiz adminBiz = HttpTool.createClient()
                .url(finalAddress)
                .timeout(finalTimeout * 1000)
                .header(Const.XXL_JOB_ACCESS_TOKEN, accessToken)
                .proxy(AdminBiz.class);  // 动态代理，方法调用会转为 HTTP 请求

        adminBizList.add(adminBiz);
    }
}
```

### 4.2 调度中心侧：JobRegistryHelper

调度中心的 `JobRegistryHelper` 负责接收注册请求和维护注册表。它有两个工作内容：

**接收注册请求（registry 方法）**：这是个同步返回、异步执行的设计。调用方立刻得到成功响应，实际的数据库写入在线程池里异步完成：

```java
// JobRegistryHelper.java
public Response<String> registry(RegistryRequest registryParam) {
    // 参数校验...

    // 异步执行，快速返回
    registryOrRemoveThreadPool.execute(() -> {
        // registrySaveOrUpdate：有则更新时间，无则插入
        // 返回 1 表示新增（第一次注册）
        int ret = xxlJobRegistryMapper.registrySaveOrUpdate(
            registryParam.getRegistryGroup(),  // "EXECUTOR"
            registryParam.getRegistryKey(),    // appname
            registryParam.getRegistryValue(),  // address
            new Date()
        );
    });

    return Response.ofSuccess();  // 立刻返回
}
```

线程池配置是 `coreSize=2, maxSize=10, queue=2000`，能承受大量执行器同时注册的冲击。

**监控线程（registryMonitorThread）**：每 30 秒执行一次，做两件事：

```java
registryMonitorThread = new Thread(() -> {
    while (!toStop) {
        // 1. 清理死节点（超过 90 秒没有心跳的记录）
        List<Integer> ids = xxlJobRegistryMapper.findDead(Const.DEAD_TIMEOUT, new Date());
        if (!ids.isEmpty()) {
            xxlJobRegistryMapper.removeDead(ids);
        }

        // 2. 刷新在线地址列表到 xxl_job_group 表
        // 先查出所有存活的注册记录，按 appname 分组聚合地址
        HashMap<String, List<String>> appAddressMap = new HashMap<>();
        List<XxlJobRegistry> list = xxlJobRegistryMapper.findAll(Const.DEAD_TIMEOUT, new Date());
        for (XxlJobRegistry item : list) {
            if ("EXECUTOR".equals(item.getRegistryGroup())) {
                appAddressMap.computeIfAbsent(item.getRegistryKey(), k -> new ArrayList<>())
                             .add(item.getRegistryValue());
            }
        }

        // 把聚合后的地址列表更新到对应的执行器组
        for (XxlJobGroup group : groupList) {
            List<String> registryList = appAddressMap.get(group.getAppname());
            // 排序后拼成逗号分隔字符串，更新到 xxl_job_group.address_list
            group.setAddressList(String.join(",", registryList));
            xxlJobGroupMapper.update(group);
        }
    }
});
```

`DEAD_TIMEOUT` 是 90 秒（3倍心跳周期），这个设计保证了即使有一次心跳丢失，执行器也不会被误判为下线。

### 4.3 XxlJobGroup 的 group 语义

一句话概括：**`appname` 代表一种执行器，`XxlJobGroup` 就是这种执行器在调度中心的"名片"，持有它当前所有存活实例的地址列表。** 同一个 appname 可以部署多个实例（集群），它们都注册相同的 appname，自动聚合到同一个 group 下。

`XxlJobGroup` 的核心字段如下：

```java
// XxlJobGroup.java
private int    id;           // 主键，XxlJobInfo.jobGroup 字段引用这个 id
private String appname;      // 执行器标识，如 "my-job-executor"（类比微服务里的 serviceId）
private String title;        // 显示名称，仅用于控制台展示
private int    addressType;  // 0=自动注册，1=手动录入
private String addressList;  // 逗号分隔的地址串，如 "http://10.0.0.1:9999,http://10.0.0.2:9999"
```

`getRegistryList()` 会把 `addressList` 解析成 `List<String>`，供路由策略使用。`addressList` 由 `JobRegistryHelper` 监控线程动态维护，逻辑就是：

```
group.addressList = JOIN(所有 registryKey == group.appname 的存活 registryValue, ",")
```

**XxlJobInfo 与 group 的绑定关系**：

```java
// XxlJobInfo.java
private int    jobGroup;        // 关联 XxlJobGroup.id，决定"发给哪种执行器"
private String executorHandler; // JobHandler 名称，如 "demoJobHandler"，决定"执行哪个方法"
```

任务触发时，调度中心用 `jobGroup` 找到对应的 group，从地址列表里按路由策略挑一个实例，把 `executorHandler` 名字放进请求发过去。整个模型就三层：appname 是执行器的身份，group 是它的地址簿，路由策略决定从地址簿里选哪一台。

---

## 5. 调度算法详解（JobScheduleHelper）

这是 xxl-job 最精髓的部分，用时间轮算法实现了高效、精准的任务调度，同时通过数据库锁解决了调度中心集群部署时的重复调度问题。

### 5.1 scheduleThread：预读线程

预读线程是"数据库 → 时间轮"的搬运工，每秒运行一次。

```java
// JobScheduleHelper.java
scheduleThread = new Thread(() -> {

    // 对齐到整秒（减少调度误差）
    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis() % 1000);

    // 预读数量上限 = (快线程池max + 慢线程池max) * 10
    int preReadCount = (fastMax + slowMax) * 10;

    while (!scheduleThreadToStop) {
        long start = System.currentTimeMillis();

        // ===== 数据库事务开始 =====
        TransactionStatus transactionStatus = transactionManager.getTransaction(...);
        try {
            // 1. 抢分布式锁（SELECT ... FOR UPDATE）
            // 集群中只有一个 admin 实例能抢到锁，防止重复调度
            xxlJobLockMapper.scheduleLock();

            long nowTime = System.currentTimeMillis();

            // 2. 预读未来 5 秒内需要触发的任务
            List<XxlJobInfo> scheduleList = xxlJobInfoMapper.scheduleJobQuery(
                nowTime + PRE_READ_MS,  // 查询 triggerNextTime <= now+5000 的任务
                preReadCount            // 最多查 preReadCount 条
            );

            if (!scheduleList.isEmpty()) {
                for (XxlJobInfo jobInfo : scheduleList) {

                    if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
                        // ① 已经过期超过 5 秒 → 过期补偿
                        //    根据 MisfireStrategy 决定是跳过还是立即触发一次
                        misfireStrategyEnum.getMisfireHandler().handle(jobInfo.getId());
                        refreshNextTriggerTime(jobInfo, new Date());

                    } else if (nowTime >= jobInfo.getTriggerNextTime()) {
                        // ② 刚刚过期（0~5秒内） → 直接触发
                        jobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null, null);
                        refreshNextTriggerTime(jobInfo, new Date());

                        // 如果下次触发时间还在 5 秒内，再推入时间轮
                        if (jobInfo.getTriggerStatus() == RUNNING
                                && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {
                            int ringSecond = (int)((jobInfo.getTriggerNextTime() / 1000) % 60);
                            pushTimeRing(ringSecond, jobInfo.getId());
                            refreshNextTriggerTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));
                        }

                    } else {
                        // ③ 还没到时间 → 推入时间轮等待
                        int ringSecond = (int)((jobInfo.getTriggerNextTime() / 1000) % 60);
                        pushTimeRing(ringSecond, jobInfo.getId());
                        refreshNextTriggerTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));
                    }
                }

                // 3. 批量更新 triggerNextTime / triggerLastTime 到数据库
                xxlJobInfoMapper.scheduleBatchUpdate(scheduleList);
            }

        } finally {
            transactionManager.commit(transactionStatus);
        }
        // ===== 数据库事务结束 =====

        // 对齐到下一整秒
        long cost = System.currentTimeMillis() - start;
        if (cost < 1000) {
            TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
        }
    }
});
```

这里有几个设计亮点值得深入理解：

**预读 5 秒的意义**：每次不只读"现在该触发的"，而是读"5 秒内要触发的"，目的是把即将到来的任务提前放入内存时间轮，避免线程睡眠期间的调度延迟。

**分布式锁的实现**：`xxlJobLockMapper.scheduleLock()` 在数据库层面执行 `SELECT * FROM xxl_job_lock WHERE lock_name='schedule_lock' FOR UPDATE`。整个 select-process-update 过程在同一个事务里，事务提交后锁才释放。这样在 admin 集群场景下，同一时刻只有一个节点能进行调度，彻底避免重复触发。

**批量更新性能优化**：`scheduleBatchUpdate()` 把所有需要更新的任务合并成一条 SQL 批量执行，而非逐条更新，显著降低数据库压力。

### 5.2 ringThread：时间轮线程

时间轮线程是真正"打点"执行的那一层。

```java
// JobScheduleHelper.java
// 时间轮数据结构：ConcurrentHashMap<秒刻度(0~59), List<jobId>>
private final Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();

ringThread = new Thread(() -> {
    while (!ringThreadToStop) {
        // 对齐到整秒
        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);

        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);

        // 取当前秒 + 往前 2 个刻度（防止调度耗时太长跨过刻度导致遗漏）
        List<Integer> ringItemData = new ArrayList<>();
        for (int i = 0; i <= 2; i++) {
            List<Integer> ringItemList = ringData.remove((nowSecond + 60 - i) % 60);
            if (ringItemList != null) {
                // 去重，防止同一个 jobId 被重复触发
                List<Integer> distinct = ringItemList.stream().distinct().toList();
                ringItemData.addAll(distinct);
            }
        }

        // 触发这一秒到期的所有任务
        for (int jobId : ringItemData) {
            jobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
        }
    }
});
```

**为什么要取前 2 个刻度？** 假设处理上一批任务耗时 2.5 秒（超过了 1 秒的间隔），那么当前唤醒时，秒数已经是 N+2 了，N 和 N+1 两个刻度里的任务都没有被处理。通过向前多取 2 个刻度，能把这些本该更早触发的任务"补回来"。

**pushTimeRing 方法**：

```java
private void pushTimeRing(int ringSecond, int jobId) {
    // computeIfAbsent：没有这个 key 就创建一个新 List
    List<Integer> ringItemList = ringData.computeIfAbsent(ringSecond, k -> new ArrayList<>());
    ringItemList.add(jobId);
}
```

### 5.3 过期任务补偿：MisfireStrategy

当任务触发时间已经过去超过 5 秒时（比如调度中心宕机重启后），xxl-job 提供两种补偿策略：

```java
// MisfireStrategyEnum.java
DO_NOTHING   // 跳过，什么都不做，等下次正常调度
FIRE_ONCE_NOW // 立即触发一次，相当于手动补偿这次错过的调度
```

对应的 `MisfireFireOnceNow.handle()` 方法实际上就是调用 `jobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.MISFIRE, ...)` 立即触发一次，并在日志里注明触发类型是 MISFIRE，方便追溯。

### 5.4 下次触发时间计算：refreshNextTriggerTime

```java
// JobScheduleHelper.java
private void refreshNextTriggerTime(XxlJobInfo jobInfo, Date fromTime) {
    ScheduleTypeEnum scheduleTypeEnum = ScheduleTypeEnum.match(jobInfo.getScheduleType());
    // 根据调度类型（CRON / FIX_RATE / NONE）计算下次触发时间
    Date nextTriggerTime = scheduleTypeEnum.getScheduleType()
                                           .generateNextTriggerTime(jobInfo, fromTime);

    if (nextTriggerTime != null) {
        jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
        jobInfo.setTriggerNextTime(nextTriggerTime.getTime());
    } else {
        // 计算失败（比如 CRON 表达式在未来没有触发点了），停止该任务
        jobInfo.setTriggerStatus(TriggerStatus.STOPPED.getValue());
        jobInfo.setTriggerLastTime(0);
        jobInfo.setTriggerNextTime(0);
    }
}
```

支持三种调度类型：`CRON`（解析 Cron 表达式，用 `CronExpression.getNextValidTimeAfter()`）、`FIX_RATE`（固定频率，`fromTime + intervalSeconds * 1000`）、`NONE`（一次性任务，不再生成下次时间）。

---

## 6. 触发流程详解

从时间轮打点到任务最终在执行器上运行，要经过三层：`JobTriggerPoolHelper` → `JobTrigger` → HTTP 调用执行器的 `/run` 接口。

### 6.1 快慢线程池：JobTriggerPoolHelper

设计了两个线程池，根据 Job 历史表现动态路由：

```java
// JobTriggerPoolHelper.java
private ThreadPoolExecutor fastTriggerPool;  // 快线程池：core=10, max=200, queue=2000
private ThreadPoolExecutor slowTriggerPool;  // 慢线程池：core=10, max=100, queue=5000

// 记录每个 Job 在当前分钟内的超时次数
private volatile ConcurrentMap<Integer, AtomicInteger> jobTimeoutCountMap = new ConcurrentHashMap<>();
// 当前分钟时间戳（用于每分钟清零）
private volatile long minTim = System.currentTimeMillis() / 60000;

public void trigger(int jobId, ...) {

    // 路由决策：如果这个 Job 在当前分钟内已经触发超时超过 10 次，分配到慢线程池
    ThreadPoolExecutor triggerPool_ = fastTriggerPool;
    AtomicInteger jobTimeoutCount = jobTimeoutCountMap.get(jobId);
    if (jobTimeoutCount != null && jobTimeoutCount.get() > 10) {
        triggerPool_ = slowTriggerPool;  // 慢任务用慢池，不占用快池资源
    }

    triggerPool_.execute(() -> {
        long start = System.currentTimeMillis();
        try {
            // 实际触发
            XxlJobAdminBootstrap.getInstance().getJobTrigger()
                .trigger(jobId, triggerType, failRetryCount, shardingParam, executorParam, addressList);
        } finally {
            // 每分钟清零一次计数器
            long minTim_now = System.currentTimeMillis() / 60000;
            if (minTim != minTim_now) {
                minTim = minTim_now;
                jobTimeoutCountMap.clear();
            }

            // 本次触发耗时超过 500ms，认为是慢任务，累加超时计数
            long cost = System.currentTimeMillis() - start;
            if (cost > 500) {
                jobTimeoutCountMap.merge(jobId, new AtomicInteger(1),
                    (old, val) -> { old.incrementAndGet(); return old; });
            }
        }
    });
}
```

**这个设计的价值在于**：如果某个 Job 对应的执行器响应很慢（比如网络延迟高），它会频繁地占用触发线程超过 500ms，触发次数超过 10 次后就会被"降级"到慢线程池。这样快线程池里的资源专门留给那些触发迅速的 Job，不会被少数慢 Job 拖垮整体调度性能。

### 6.2 路由选址：ExecutorRouteStrategyEnum

路由是在有多个执行器实例时，决定把任务发给哪一个的逻辑。全部策略封装在枚举里，每个枚举项都持有一个 `ExecutorRouter` 实现：

```java
// ExecutorRouteStrategyEnum.java
FIRST           → ExecutorRouteFirst        // 始终取地址列表第一个
LAST            → ExecutorRouteLast         // 始终取地址列表最后一个
ROUND           → ExecutorRouteRound        // 轮询，每次取下一个（AtomicInteger 自增取模）
RANDOM          → ExecutorRouteRandom       // 随机取一个
CONSISTENT_HASH → ExecutorRouteConsistentHash // 一致性哈希，相同 jobId 打到相同实例
LEAST_FREQUENTLY_USED → ExecutorRouteLFU    // 最不常用
LEAST_RECENTLY_USED   → ExecutorRouteLRU    // 最久未使用
FAILOVER        → ExecutorRouteFailover     // 故障转移：依次 /beat 探测，用第一个在线的
BUSYOVER        → ExecutorRouteBusyover     // 忙碌转移：依次 /idleBeat 探测，用第一个空闲的
SHARDING_BROADCAST → null                  // 分片广播，不走路由，直接广播给所有实例
```

以最实用的 `FAILOVER` 为例，源码逻辑很清晰：

```java
// ExecutorRouteFailover.java
public Response<String> route(TriggerRequest triggerParam, List<String> addressList) {
    for (String address : addressList) {
        // 依次对每个地址发 /beat 心跳探测
        ExecutorBiz executorBiz = XxlJobAdminBootstrap.getExecutorBiz(address);
        Response<String> beatResult = executorBiz.beat();

        if (beatResult.isSuccess()) {
            // 第一个响应成功的，就用它
            beatResult.setData(address);
            return beatResult;
        }
    }
    // 全部失败，返回 fail
    return Response.ofFail("all executor beat fail.");
}
```

`BUSYOVER` 与之类似，只不过探测接口换成了 `/idleBeat`，它会检查目标执行器上这个 Job 的线程是否空闲（`isRunningOrHasQueue()`），找第一个空闲的实例。

**路由选址的安全性：为什么不会选到"不认识这个 Handler"的执行器？**

这个问题的答案根植于 group 的语义设计（见 4.3 节）。路由策略选地址时，所有候选地址都来自同一个 group 的 `registryList`，而同一个 group 对应的是同一个 `appname` 下的所有实例——它们部署的是同一套代码，因此必然注册了完全相同的 Handler 集合。无论路由选中哪台机器，都认识 `executorHandler` 字段里的 Handler 名称。从代码层面看，`processTrigger` 的流程也印证了这一点：

```java
// JobTrigger.java processTrigger()
// 第一步：从 group.registryList 中路由选一个地址（所有地址属于同一 appname 集群）
routeAddressResult = routeStrategy.getRouter().route(triggerParam, group.getRegistryList());
address = routeAddressResult.getData();

// 第二步：把 executorHandler 名字放进触发参数，发给选中的地址
triggerParam.setExecutorHandler(jobInfo.getExecutorHandler());
Response<String> triggerResult = doTrigger(triggerParam, address);
```

选址和 Handler 分派是解耦的两步，但因为 group 的同质性保证，两步之间不存在"选错"的问题。

**唯一的例外：灰度发布窗口期。** 如果在旧版本实例还没全部下线的情况下，新版本（新增了某个 Handler）的实例已经部署上线，此时 group 内同时存在新旧两个版本。如果针对新 Handler 的任务恰好被路由到旧实例，执行器会返回 `can not find job handler` 错误。这不是 xxl-job 的缺陷，而是所有需要新增 Handler 的发布都面临的经典问题。通常的处理方式是：先完成全量发布（确保所有实例都升级到新版本），再在调度中心创建新任务并启用。

### 6.3 触发执行器：JobTrigger.processTrigger

这是整个触发链路的核心方法，完整实现了"找地址 → HTTP 调用 → 记录日志"的全过程：

```java
// JobTrigger.java
private void processTrigger(XxlJobGroup group, XxlJobInfo jobInfo,
                             int failRetryCount, TriggerTypeEnum triggerType,
                             Date triggerTime, int index, int total) {

    // ① 确定阻塞策略和路由策略
    ExecutorBlockStrategyEnum blockStrategy = ExecutorBlockStrategyEnum.match(...);
    ExecutorRouteStrategyEnum routeStrategy = ExecutorRouteStrategyEnum.match(...);
    // 分片广播时，shardingParam = "0/3"、"1/3"、"2/3"（index/total）
    String shardingParam = (SHARDING_BROADCAST == routeStrategy)
        ? index + "/" + total : null;

    // ② 先插入日志记录（此时只有触发时间，执行结果还不知道）
    XxlJobLog jobLog = new XxlJobLog();
    jobLog.setJobGroup(jobInfo.getJobGroup());
    jobLog.setJobId(jobInfo.getId());
    jobLog.setTriggerTime(triggerTime);
    xxlJobLogMapper.save(jobLog);  // 拿到 logId

    // ③ 构建触发参数
    TriggerRequest triggerParam = new TriggerRequest();
    triggerParam.setJobId(jobInfo.getId());
    triggerParam.setExecutorHandler(jobInfo.getExecutorHandler()); // JobHandler 名称
    triggerParam.setExecutorParams(jobInfo.getExecutorParam());    // 任务参数
    triggerParam.setExecutorBlockStrategy(jobInfo.getExecutorBlockStrategy());
    triggerParam.setExecutorTimeout(jobInfo.getExecutorTimeout());
    triggerParam.setLogId(jobLog.getId());           // 日志 ID（执行器回调时要带上）
    triggerParam.setLogDateTime(triggerTime.getTime());
    triggerParam.setGlueType(jobInfo.getGlueType()); // BEAN / GLUE(Java) / GLUE(Shell) 等
    triggerParam.setGlueSource(jobInfo.getGlueSource()); // GLUE 模式的代码内容
    triggerParam.setGlueUpdatetime(jobInfo.getGlueUpdatetime().getTime());
    triggerParam.setBroadcastIndex(index);
    triggerParam.setBroadcastTotal(total);

    // ④ 路由选址，确定发给哪个执行器实例
    String address = null;
    if (SHARDING_BROADCAST == routeStrategy) {
        // 分片广播：按 index 直接取对应位置的地址
        address = group.getRegistryList().get(index);
    } else {
        // 其他路由：调用对应策略的 route() 方法
        Response<String> routeResult = routeStrategy.getRouter().route(triggerParam, group.getRegistryList());
        address = routeResult.getData();
    }

    // ⑤ 发送 HTTP 请求给执行器
    Response<String> triggerResult = doTrigger(triggerParam, address);

    // ⑥ 更新日志的触发信息（triggerCode、triggerMsg）
    jobLog.setExecutorAddress(address);
    jobLog.setExecutorHandler(jobInfo.getExecutorHandler());
    jobLog.setTriggerCode(triggerResult.getCode());  // 200=触发成功，500=触发失败
    jobLog.setTriggerMsg(buildTriggerMsg(...));       // 拼装详细的触发日志信息
    xxlJobLogMapper.updateTriggerInfo(jobLog);
}
```

`doTrigger()` 是真正发 HTTP 的地方：

```java
private Response<String> doTrigger(TriggerRequest triggerParam, String address) {
    ExecutorBiz executorBiz = XxlJobAdminBootstrap.getExecutorBiz(address); // 从缓存取客户端
    Response<String> runResult = executorBiz.run(triggerParam);             // HTTP POST /run
    return runResult;
}
```

---

## 7. 执行器内部架构

### 7.1 执行器启动：XxlJobExecutor.start()

```java
// XxlJobExecutor.java
public void start() throws Exception {

    // enabled=false 时直接跳过（方便测试环境禁用）
    if (enabled != null && !enabled) {
        return;
    }

    // ① 初始化日志路径（按 yyyy-MM-dd 分目录，每个任务一个 logId.log 文件）
    XxlJobFileAppender.initLogPath(logPath);

    // ② 初始化 admin-client（AdminBiz 代理列表）
    initAdminBizList(adminAddresses, accessToken, timeout);

    // ③ 启动日志文件清理线程（按 logRetentionDays 删除过期日志）
    JobLogFileCleanThread.getInstance().start(logRetentionDays);

    // ④ 启动回调线程（执行完任务后，把结果异步 HTTP 回调给 admin）
    TriggerCallbackThread.getInstance().start();

    // ⑤ 启动 Netty HTTP 服务 + 注册线程
    initEmbedServer(address, ip, port, appname, accessToken);
}
```

**destroy()** 按照相反的顺序优雅关闭，并且在关闭 Netty 服务后等待 5 秒（`ELEGANT_SHUTDOWN_WAITING_SECONDS`），给正在运行的 JobThread 一些时间完成当前任务并做回调，然后再逐一 interrupt 并 join：

```java
public void destroy() {
    // ① 停止 Netty 服务（不再接受新的触发请求）
    stopEmbedServer();

    // ② 等待 5 秒，让正在执行的任务有时间完成
    TimeUnit.SECONDS.sleep(ELEGANT_SHUTDOWN_WAITING_SECONDS);

    // ③ 停止所有 JobThread，等它们把回调推入队列
    for (Map.Entry<Integer, JobThread> item : jobThreadRepository.entrySet()) {
        JobThread oldJobThread = removeJobThread(item.getKey(), "web container destroy and kill the job.");
        oldJobThread.join();  // 等线程真正退出
    }

    // ④ 清理 Handler 和 Thread 注册表
    jobThreadRepository.clear();
    jobHandlerRepository.clear();

    // ⑤ 停止日志清理线程和回调线程
    JobLogFileCleanThread.getInstance().toStop();
    TriggerCallbackThread.getInstance().toStop();
}
```

### 7.2 Spring 集成：XxlJobSpringExecutor 与 @XxlJob 扫描

`XxlJobSpringExecutor` 继承自 `XxlJobExecutor`，额外实现了 `SmartInitializingSingleton`（所有 Bean 初始化完成后回调）和 `ApplicationContextAware`（获取 Spring 容器）。

它的核心方法是 `scanJobHandlerMethod()`，在 Spring 容器就绪后自动扫描所有带 `@XxlJob` 注解的方法：

```java
// XxlJobSpringExecutor.java
@Override
public void afterSingletonsInstantiated() {
    // ① 扫描 @XxlJob 方法，注册到 jobHandlerRepository
    scanJobHandlerMethod(applicationContext);

    // ② 初始化 GlueFactory 为 Spring 版本（支持注入 Spring Bean）
    GlueFactory.refreshInstance(1);  // 1=SpringGlueFactory

    // ③ 调用父类 start()
    super.start();
}

private void scanJobHandlerMethod(ApplicationContext applicationContext) {
    String[] beanNames = applicationContext.getBeanNamesForType(Object.class, false, false);
    for (String beanName : beanNames) {

        // 跳过被排除的包（默认排除 org.springframework 和 spring.）
        if (isExcluded(excludedPackageList, beanClassName)) continue;

        // 跳过懒加载 Bean（避免提前初始化）
        if (beanDefinition.isLazyInit()) continue;

        // 用 Spring 的 MethodIntrospector 找到该 Bean 上所有带 @XxlJob 的方法
        Map<Method, XxlJob> annotatedMethods = MethodIntrospector.selectMethods(beanClass,
            method -> AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class));

        if (annotatedMethods.isEmpty()) continue;

        // 取出 Bean 实例，把每个方法包装成 MethodJobHandler 注册
        Object jobBean = applicationContext.getBean(beanName);
        for (Map.Entry<Method, XxlJob> entry : annotatedMethods.entrySet()) {
            registryJobHandler(entry.getValue(), jobBean, entry.getKey());
        }
    }
}
```

`registryJobHandler()` 把 `@XxlJob` 注解里的 `value`（任务名）、`init`（初始化方法名）、`destroy`（销毁方法名）都解析出来，包装成 `MethodJobHandler`，存入 `jobHandlerRepository` 这张 `ConcurrentHashMap<String, IJobHandler>` 里：

```java
// key = @XxlJob("demoJobHandler") 的 "demoJobHandler"
// value = MethodJobHandler(bean, executeMethod, initMethod, destroyMethod)
jobHandlerRepository.put(name, new MethodJobHandler(bean, executeMethod, initMethod, destroyMethod));
```

### 7.3 Netty HTTP 服务：EmbedServer + EmbedHttpServerHandler

`EmbedServer` 是执行器对外暴露的唯一入口，基于 Netty 实现，监听配置的端口（默认 9999）。

```java
// EmbedServer.java
public void start(String address, int port, String appname, String accessToken) {
    executorBiz = new ExecutorBizImpl();  // 业务处理器

    thread = new Thread(() -> {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        // 业务线程池（与 Netty IO 线程分离，防止阻塞 IO）
        ThreadPoolExecutor bizThreadPool = new ThreadPoolExecutor(
            0, 200, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000), ...
        );

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                 .channel(NioServerSocketChannel.class)
                 .childHandler(new ChannelInitializer<SocketChannel>() {
                     public void initChannel(SocketChannel channel) {
                         channel.pipeline()
                             // 90 秒空闲自动关闭连接
                             .addLast(new IdleStateHandler(0, 0, 30 * 3, TimeUnit.SECONDS))
                             .addLast(new HttpServerCodec())          // HTTP 编解码
                             .addLast(new HttpObjectAggregator(5 * 1024 * 1024))  // 聚合请求，最大 5MB
                             .addLast(new EmbedHttpServerHandler(executorBiz, accessToken, bizThreadPool));
                     }
                 });

        ChannelFuture future = bootstrap.bind(port).sync();

        // Netty 绑定成功后，启动注册线程
        startRegistry(appname, address);

        future.channel().closeFuture().sync();  // 阻塞，直到服务被关闭
    });
    thread.setDaemon(true);
    thread.start();
}
```

`EmbedHttpServerHandler` 继承自 `SimpleChannelInboundHandler<FullHttpRequest>`，收到请求后立刻把处理任务提交给 `bizThreadPool`，避免阻塞 Netty 的 IO 线程：

```java
// EmbedServer.EmbedHttpServerHandler
@Override
protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
    String requestData = msg.content().toString(CharsetUtil.UTF_8);
    String uri = msg.uri();
    String accessTokenReq = msg.headers().get(Const.XXL_JOB_ACCESS_TOKEN);
    boolean keepAlive = HttpUtil.isKeepAlive(msg);

    // 提交给业务线程池异步处理
    bizThreadPool.execute(() -> {
        Object responseObj = dispatchRequest(httpMethod, uri, requestData, accessTokenReq);
        String responseJson = GsonTool.toJson(responseObj);
        writeResponse(ctx, keepAlive, responseJson);
    });
}

private Object dispatchRequest(HttpMethod httpMethod, String uri, String requestData, String accessTokenReq) {
    // 安全校验：只接受 POST，校验 accessToken
    if (HttpMethod.POST != httpMethod) return Response.ofFail("invalid request");
    if (!accessToken.equals(accessTokenReq)) return Response.ofFail("wrong access token");

    // 路由分发：5 个接口
    switch (uri) {
        case "/beat":     return executorBiz.beat();
        case "/idleBeat": return executorBiz.idleBeat(GsonTool.fromJson(requestData, IdleBeatRequest.class));
        case "/run":      return executorBiz.run(GsonTool.fromJson(requestData, TriggerRequest.class));
        case "/kill":     return executorBiz.kill(GsonTool.fromJson(requestData, KillRequest.class));
        case "/log":      return executorBiz.log(GsonTool.fromJson(requestData, LogRequest.class));
        default:          return Response.ofFail("uri not found");
    }
}
```

### 7.4 请求处理核心：ExecutorBizImpl

这是 5 个接口的实际业务实现：

**beat()** — 心跳探测，什么都不做，直接返回 success：
```java
public Response<String> beat() {
    return Response.ofSuccess();
}
```

**idleBeat()** — 空闲探测，检查指定 Job 是否正在运行或有待处理请求：
```java
public Response<String> idleBeat(IdleBeatRequest idleBeatRequest) {
    JobThread jobThread = XxlJobExecutor.loadJobThread(idleBeatRequest.getJobId());
    if (jobThread != null && jobThread.isRunningOrHasQueue()) {
        return Response.ofFail("job thread is running or has trigger queue.");
    }
    return Response.ofSuccess();
}
```

**run()** — 触发任务执行，这是最复杂的方法，完整流程如下：

```java
public Response<String> run(TriggerRequest triggerRequest) {
    // ① 查找已有的 JobThread 和 Handler
    JobThread jobThread = XxlJobExecutor.loadJobThread(triggerRequest.getJobId());
    IJobHandler jobHandler = jobThread != null ? jobThread.getHandler() : null;

    // ② 根据 GlueType 决定使用哪种 Handler
    GlueTypeEnum glueTypeEnum = GlueTypeEnum.match(triggerRequest.getGlueType());

    if (GlueTypeEnum.BEAN == glueTypeEnum) {
        // BEAN 模式：从 jobHandlerRepository 查注册的 Handler
        IJobHandler newJobHandler = XxlJobExecutor.loadJobHandler(triggerRequest.getExecutorHandler());

        // Handler 换了？销毁旧线程，用新 Handler 重建
        if (jobThread != null && jobHandler != newJobHandler) {
            removeOldReason = "change jobhandler, kill old thread.";
            jobThread = null; jobHandler = null;
        }
        jobHandler = newJobHandler;

    } else if (GlueTypeEnum.GLUE_GROOVY == glueTypeEnum) {
        // GLUE(Java) 模式：每次带来 glueSource（代码字符串）+ glueUpdatetime
        // 如果代码版本变了（glueUpdatetime 不同），销毁旧线程重新编译
        if (jobThread != null && glueUpdatetime != triggerRequest.getGlueUpdatetime()) {
            removeOldReason = "glue source updated, kill old thread.";
            jobThread = null;
        }
        if (jobHandler == null) {
            // 用 GlueFactory 动态编译 Groovy 代码，得到 IJobHandler 实例
            IJobHandler originJobHandler = GlueFactory.getInstance().loadNewInstance(triggerRequest.getGlueSource());
            jobHandler = new GlueJobHandler(originJobHandler, triggerRequest.getGlueUpdatetime());
        }

    } else if (glueTypeEnum.isScript()) {
        // 脚本模式（Shell/Python/Node.js 等）：逻辑与 GLUE(Java) 类似
        jobHandler = new ScriptJobHandler(jobId, glueUpdatetime, glueSource, glueTypeEnum);
    }

    // ③ 处理阻塞策略
    if (jobThread != null) {
        switch (blockStrategy) {
            case DISCARD_LATER:
                // 当前线程忙，直接丢弃这次触发
                if (jobThread.isRunningOrHasQueue()) {
                    return Response.ofFail("discard, job is running.");
                }
                break;
            case COVER_EARLY:
                // 当前线程忙，强制终止，用新线程覆盖
                if (jobThread.isRunningOrHasQueue()) {
                    jobThread = null;  // 标记需要重建（下面会 removeJobThread）
                }
                break;
            case SERIAL_EXECUTION:
            default:
                // 排队，把触发请求推入当前线程的队列
                break;
        }
    }

    // ④ 如果需要创建新线程
    if (jobThread == null) {
        jobThread = XxlJobExecutor.registJobThread(triggerRequest.getJobId(), jobHandler, removeOldReason);
    }

    // ⑤ 把触发参数推入 JobThread 的队列
    return jobThread.pushTriggerQueue(triggerRequest);
}
```

**kill()** — 终止任务线程：
```java
public Response<String> kill(KillRequest killRequest) {
    JobThread jobThread = XxlJobExecutor.loadJobThread(killRequest.getJobId());
    if (jobThread != null) {
        XxlJobExecutor.removeJobThread(killRequest.getJobId(), "scheduling center kill job.");
    }
    return Response.ofSuccess();
}
```

**log()** — 分片读取日志文件（支持分页）：
```java
public Response<LogResult> log(LogRequest logRequest) {
    String logFileName = XxlJobFileAppender.makeLogFileName(
        new Date(logRequest.getLogDateTim()), logRequest.getLogId());
    LogResult logResult = XxlJobFileAppender.readLog(logFileName, logRequest.getFromLineNum());
    return Response.ofSuccess(logResult);
}
```

---

## 8. 任务执行详解（JobThread）

`JobThread` 是任务执行的最小单元，每个 Job 在执行器上独占一条线程（而不是共享线程池），这是 xxl-job 能精确控制任务状态的关键。

### 8.1 JobThread 的生命周期

```java
// JobThread.java
public class JobThread extends Thread {
    private int jobId;
    private IJobHandler handler;
    private LinkedBlockingQueue<TriggerRequest> triggerQueue; // 触发请求队列
    private Set<Long> triggerLogIdSet;    // 防重复触发集合（ConcurrentHashSet）

    private volatile boolean toStop = false;
    private String stopReason;

    private boolean running = false;  // 当前是否在执行任务
    private int idleTimes = 0;        // 连续空闲次数计数
```

**初始化阶段**（线程启动后立刻）：
```java
// 调用 handler.init()（对应 @XxlJob(init="initMethod") 配置的初始化方法）
try {
    handler.init();
} catch (Throwable e) {
    logger.error(e.getMessage(), e);
}
```

**执行循环**：
```java
while (!toStop) {
    running = false;
    idleTimes++;

    // poll(3s)：最多等 3 秒，超时继续循环（而不是 take() 无限阻塞）
    // 这样可以定期检查 toStop 信号
    TriggerRequest triggerParam = triggerQueue.poll(3L, TimeUnit.SECONDS);

    if (triggerParam != null) {
        running = true;
        idleTimes = 0;                              // 复位空闲计数
        triggerLogIdSet.remove(triggerParam.getLogId()); // 从防重集合移除

        // 构建任务上下文，放入 ThreadLocal
        XxlJobContext xxlJobContext = new XxlJobContext(
            triggerParam.getJobId(),
            triggerParam.getExecutorParams(),  // 任务参数
            triggerParam.getLogId(),
            triggerParam.getLogDateTime(),
            logFileName,                       // logPath/yyyy-MM-dd/logId.log
            triggerParam.getBroadcastIndex(),  // 分片序号
            triggerParam.getBroadcastTotal()   // 分片总数
        );
        XxlJobContext.setXxlJobContext(xxlJobContext);

        // 执行任务（有超时控制 / 无超时控制两个分支）
        if (triggerParam.getExecutorTimeout() > 0) {
            // 带超时：包一层 FutureTask + 新线程
            // ...（详见 8.3 节）
        } else {
            handler.execute();  // 直接调用
        }

    } else {
        // 连续 30 次（约 90 秒）没有任务，且队列为空 → 自我销毁
        if (idleTimes > 30 && triggerQueue.isEmpty()) {
            XxlJobExecutor.removeJobThread(jobId, "executor idle times over limit.");
        }
    }
}
```

**线程停止阶段**（toStop=true 后）：
```java
// 把队列里剩余的、还没执行的触发请求全部以"被杀死"的状态回调
while (!triggerQueue.isEmpty()) {
    TriggerRequest triggerParam = triggerQueue.poll();
    TriggerCallbackThread.pushCallBack(new CallbackRequest(
        triggerParam.getLogId(),
        triggerParam.getLogDateTime(),
        XxlJobContext.HANDLE_CODE_FAIL,
        stopReason + " [job not executed, in the job queue, killed.]"
    ));
}

// 调用 handler.destroy()（对应 @XxlJob(destroy="destroyMethod") 配置的销毁方法）
handler.destroy();
```

### 8.2 阻塞策略（ExecutorBlockStrategyEnum）

当调度中心连续多次触发同一个 Job，但执行器上上一次任务还没执行完时，就会产生"阻塞"。xxl-job 提供三种应对策略：

**SERIAL_EXECUTION（串行执行，默认）**：新触发请求进入 `triggerQueue` 排队，按顺序依次处理。适合不允许并发、必须保证顺序的场景（如数据同步）。

**DISCARD_LATER（丢弃后续）**：如果当前线程正忙（`isRunningOrHasQueue()` 为 true），直接拒绝这次触发，返回失败。适合"能接受漏执行、但不能积压"的场景（如实时性要求高的轮询任务）。

**COVER_EARLY（覆盖之前）**：如果当前线程正忙，强制 kill 旧线程并创建新线程来执行本次任务。适合"只要最新一次、之前的可以放弃"的场景（如定时刷新缓存）。

`isRunningOrHasQueue()` 的实现很简洁：

```java
public boolean isRunningOrHasQueue() {
    return running || triggerQueue.size() > 0;
}
```

### 8.3 超时控制机制

当任务配置了超时时间（`executorTimeout > 0`），JobThread 会另起一条线程用 FutureTask 来实现可中断的超时控制：

```java
if (triggerParam.getExecutorTimeout() > 0) {
    Thread futureThread = null;
    try {
        // ① 把 handler.execute() 包在 FutureTask 里
        FutureTask<Boolean> futureTask = new FutureTask<>(() -> {
            XxlJobContext.setXxlJobContext(xxlJobContext); // 子线程需要重新设置 ThreadLocal
            handler.execute();
            return true;
        });

        // ② 用新线程执行（不阻塞 JobThread 自身）
        futureThread = new Thread(futureTask);
        futureThread.setName("xxl-job, JobThread-future-" + jobId + "-" + ...);
        futureThread.start();

        // ③ JobThread 等待结果，超时则抛出 TimeoutException
        Boolean result = futureTask.get(triggerParam.getExecutorTimeout(), TimeUnit.SECONDS);

    } catch (TimeoutException e) {
        // 超时了：设置超时状态码
        XxlJobHelper.handleTimeout("job execute timeout");
    } finally {
        // 不管成功还是超时，都 interrupt 执行线程（尽力而为，不保证一定能停）
        futureThread.interrupt();
    }
}
```

注意这里有个细节：`XxlJobContext.setXxlJobContext(xxlJobContext)` 在 FutureTask 的 Callable 里需要重新设置一次。因为 `XxlJobContext` 用的是 `InheritableThreadLocal`，虽然子线程可以继承父线程的值，但这里是显式重新 set 以确保准确性。

### 8.4 JobHandler 的三种实现

**MethodJobHandler（BEAN 模式）**：

```java
// MethodJobHandler.java
public class MethodJobHandler extends IJobHandler {
    private final Object target;     // 业务 Bean 实例
    private final Method method;     // @XxlJob 注解的方法
    private Method initMethod;       // @XxlJob(init="xxx") 对应的方法
    private Method destroyMethod;    // @XxlJob(destroy="xxx") 对应的方法

    @Override
    public void execute() throws Exception {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length > 0) {
            method.invoke(target, new Object[paramTypes.length]); // 有参（传 null）
        } else {
            method.invoke(target);  // 无参
        }
    }

    @Override
    public void init() throws Exception {
        if (initMethod != null) initMethod.invoke(target);
    }

    @Override
    public void destroy() throws Exception {
        if (destroyMethod != null) destroyMethod.invoke(target);
    }
}
```

**GlueJobHandler（GLUE Java 模式）**：包装由 `GlueFactory` 动态编译生成的 `IJobHandler` 实例，额外记录 `glueUpdatetime`，用于判断代码版本是否变化：

```java
public class GlueJobHandler extends IJobHandler {
    private long glueUpdatetime;
    private IJobHandler originJobHandler;  // 真正的 Groovy 编译出的 Handler

    public void execute() throws Exception {
        originJobHandler.execute();
    }
}
```

**ScriptJobHandler（脚本模式）**：

```java
// ScriptJobHandler.java
public void execute() throws Exception {
    // ① 把脚本代码写到本地文件，如 /data/xxl-job/data/glue-source/123_1622000000000.sh
    String scriptFileName = glueSrcPath + "/" + jobId + "_" + glueUpdatetime + glueType.getSuffix();
    if (!new File(scriptFileName).exists()) {
        ScriptUtil.markScriptFile(scriptFileName, gluesource);
    }

    // ② 准备脚本参数：[任务参数, 分片序号, 分片总数]
    String[] scriptParams = {jobParam, shardIndex, shardTotal};

    // ③ 执行脚本，输出重定向到任务日志文件
    // cmd 示例："bash /path/to/script.sh param 0 1 >> /path/to/logId.log"
    int exitValue = ScriptUtil.execToFile(glueType.getCmd(), scriptFileName, logFileName, scriptParams);

    if (exitValue == 0) {
        XxlJobHelper.handleSuccess();
    } else {
        XxlJobHelper.handleFail("script exit value(" + exitValue + ") is failed");
    }
}
```

---

## 9. 任务上下文：XxlJobContext + XxlJobHelper

`XxlJobContext` 是任务执行期间的"环境变量"，通过 `InheritableThreadLocal` 绑定到当前线程（以及它的子线程）：

```java
// XxlJobContext.java
public class XxlJobContext {
    // 任务信息
    private final long jobId;
    private final String jobParam;    // 任务参数（在控制台配置的参数）

    // 日志信息（用于定位日志文件）
    private final long logId;
    private final long logDateTime;
    private final String logFileName; // 实际路径：logPath/2024-01-01/12345.log

    // 分片信息
    private final int shardIndex;     // 当前实例的分片序号（从 0 开始）
    private final int shardTotal;     // 总分片数

    // 执行结果（可修改）
    private int handleCode = HANDLE_CODE_SUCCESS;  // 默认成功
    private String handleMsg;

    // 状态码常量
    public static final int HANDLE_CODE_SUCCESS = 200;
    public static final int HANDLE_CODE_FAIL    = 500;
    public static final int HANDLE_CODE_TIMEOUT = 502;

    // ThreadLocal 存取
    private static final InheritableThreadLocal<XxlJobContext> contextHolder = new InheritableThreadLocal<>();
}
```

`XxlJobHelper` 是封装给业务代码使用的工具类，提供简洁的静态方法 API：

| 方法 | 用途 |
|---|---|
| `getJobId()` | 获取当前 Job ID |
| `getJobParam()` | 获取控制台配置的任务参数 |
| `getShardIndex()` | 获取分片序号（分片广播时使用） |
| `getShardTotal()` | 获取总分片数 |
| `log(pattern, args...)` | 向当前任务日志文件追加一行日志 |
| `log(Throwable e)` | 向日志文件追加异常栈信息 |
| `handleSuccess()` | 主动标记执行成功（不调用也默认成功） |
| `handleFail(msg)` | 主动标记执行失败，附带原因 |
| `handleTimeout(msg)` | 主动标记超时 |

`XxlJobHelper.log()` 的日志格式是带调用位置的：

```
2024-01-15 10:30:00 [com.example.MyJobHandler#execute]-[42]-[xxl-job, JobThread-1] 开始处理数据...
```

格式：`时间 [类名#方法名]-[行号]-[线程名] 日志内容`

---

## 10. 回调与可靠性保障

任务执行完后，结果如何告知调度中心？这一套机制设计得相当完善，有三层保障。

### 10.1 执行器侧：TriggerCallbackThread

`JobThread` 在 `finally` 块中（无论成功、失败还是超时）把执行结果推入 `TriggerCallbackThread` 的队列：

```java
// JobThread.java finally 块
if (triggerParam != null) {
    if (!toStop) {
        // 正常结束：推送实际执行结果
        TriggerCallbackThread.pushCallBack(new CallbackRequest(
            triggerParam.getLogId(),
            triggerParam.getLogDateTime(),
            XxlJobContext.getXxlJobContext().getHandleCode(),  // 200/500/502
            XxlJobContext.getXxlJobContext().getHandleMsg()
        ));
    } else {
        // 被 kill：推送失败结果
        TriggerCallbackThread.pushCallBack(new CallbackRequest(
            triggerParam.getLogId(),
            triggerParam.getLogDateTime(),
            XxlJobContext.HANDLE_CODE_FAIL,
            stopReason + " [job running, killed]"
        ));
    }
}
```

`TriggerCallbackThread` 用 `LinkedBlockingQueue` 作为缓冲，后台线程批量消费：

```java
// TriggerCallbackThread.java
triggerCallbackThread = new Thread(() -> {
    while (!toStop) {
        CallbackRequest callback = callBackQueue.take();  // 阻塞等待

        // 用 drainTo 一次性取出队列中所有元素（批量发送，减少 HTTP 次数）
        List<CallbackRequest> callbackParamList = new ArrayList<>();
        callbackParamList.add(callback);
        callBackQueue.drainTo(callbackParamList);  // 把剩余的全取出来

        doCallback(callbackParamList);
    }
});
```

`doCallback()` 依次尝试每个 admin 地址，任一成功即止：

```java
private void doCallback(List<CallbackRequest> callbackParamList) {
    boolean callbackRet = false;
    for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
        Response<String> callbackResult = adminBiz.callback(callbackParamList);
        if (callbackResult != null && callbackResult.isSuccess()) {
            callbackRet = true;
            break;
        }
    }
    // 所有 admin 都失败了 → 持久化到本地文件
    if (!callbackRet) {
        appendFailCallbackFile(callbackParamList);
    }
}
```

**失败回调文件**：序列化为 JSON 写到 `logPath/callbackLog/` 目录，文件名是回调数据的 MD5：

```java
private void appendFailCallbackFile(List<CallbackRequest> callbackParamList) {
    String callbackData = GsonTool.toJson(callbackParamList);
    String callbackDataMd5 = Md5Tool.md5(callbackData);
    String finalLogFileName = failCallbackFileName.replace("{x}", callbackDataMd5);
    FileTool.writeString(finalLogFileName, callbackData);
}
```

**重试线程**：`triggerRetryCallbackThread` 每 30 秒扫描一次失败文件目录，对每个文件重新执行 `doCallback()`：

```java
triggerRetryCallbackThread = new Thread(() -> {
    while (!toStop) {
        retryFailCallbackFile();  // 扫描并重试
        TimeUnit.SECONDS.sleep(Const.BEAT_TIMEOUT);
    }
});
```

### 10.2 调度中心侧：JobCompleteHelper + JobCompleter

调度中心收到执行器的回调 HTTP 请求后，由 `JobCompleteHelper.callback()` 处理：

```java
// JobCompleteHelper.java
public Response<String> callback(List<CallbackRequest> callbackParamList) {
    // 异步处理，立即返回
    callbackThreadPool.execute(() -> {
        for (CallbackRequest callbackRequest : callbackParamList) {
            doCallback(callbackRequest);
        }
    });
    return Response.ofSuccess();
}

private Response<String> doCallback(CallbackRequest handleCallbackParam) {
    // ① 加载日志记录（校验 logId 存在，防止重复回调）
    XxlJobLog log = xxlJobLogMapper.load(handleCallbackParam.getLogId());
    if (log.getHandleCode() > 0) {
        return Response.ofFail("log repeate callback.");  // 已处理过，幂等保护
    }

    // ② 合并 handleMsg（触发阶段的消息 + 执行阶段的消息）
    String handleMsg = log.getHandleMsg() + handleCallbackParam.getHandleMsg();

    // ③ 更新日志状态（handleCode + handleMsg + handleTime）
    log.setHandleTime(new Date());
    log.setHandleCode(handleCallbackParam.getHandleCode());
    log.setHandleMsg(handleMsg);

    // ④ 调用 JobCompleter.complete()（更新日志 + 触发子任务）
    XxlJobAdminBootstrap.getInstance().getJobCompleter().complete(log);

    return Response.ofSuccess();
}
```

`JobCompleter.complete()` 是回调的最终处理：

```java
// JobCompleter.java
public int complete(XxlJobLog xxlJobLog) {
    // ① 处理子任务触发
    processChildJob(xxlJobLog);

    // ② 日志截断（防止 TEXT 字段超限）
    if (xxlJobLog.getHandleMsg().length() > 15000) {
        xxlJobLog.setHandleMsg(xxlJobLog.getHandleMsg().substring(0, 15000));
    }

    // ③ 更新日志的 handle 信息
    return xxlJobLogMapper.updateHandleInfo(xxlJobLog);
}
```

### 10.3 子任务触发

当一个任务执行成功后，可以自动触发另一个任务（配置在控制台的"子任务 ID"字段）：

```java
// JobCompleter.java
private void processChildJob(XxlJobLog xxlJobLog) {
    if (XxlJobContext.HANDLE_CODE_SUCCESS == xxlJobLog.getHandleCode()) {
        XxlJobInfo xxlJobInfo = xxlJobInfoMapper.loadById(xxlJobLog.getJobId());

        if (xxlJobInfo != null && StringTool.isNotBlank(xxlJobInfo.getChildJobId())) {
            // 子任务 ID 支持配置多个，逗号分隔，如 "101,102,103"
            String[] childJobIds = xxlJobInfo.getChildJobId().split(",");
            for (String childJobId : childJobIds) {
                // 触发类型标记为 PARENT（父任务触发），方便日志追溯
                jobTriggerPoolHelper.trigger(childJobId, TriggerTypeEnum.PARENT, -1, null, null, null);
            }
        }
    }
}
```

### 10.4 失败重试与结果丢失兜底

**失败重试**：在 `JobTrigger.trigger()` 方法里，`finalFailRetryCount` 决定失败后重试几次。当 `JobCompleter.complete()` 处理回调时，如果 `handleCode != SUCCESS` 且 `failRetryCount > 0`，会再次调用 `trigger()`，参数中 `failRetryCount - 1`，并且 `executorShardingParam` 保持不变（重试同一个分片），`addressList` 也保持不变（重试同一台机器）。

**结果丢失兜底**：`JobCompleteHelper` 的 `monitorThread` 每 60 秒扫描一次：

```java
monitorThread = new Thread(() -> {
    while (!toStop) {
        // 查找：trigger_code=200（触发成功）且 handle_code=0（还没回调）
        //       且 trigger_time < now - 10分钟
        //       且对应执行器已不在线（注册表里找不到）
        Date losedTime = DateTool.addMinutes(new Date(), -10);
        List<Long> losedJobIds = xxlJobLogMapper.findLostJobIds(losedTime);

        for (Long logId : losedJobIds) {
            XxlJobLog jobLog = new XxlJobLog();
            jobLog.setId(logId);
            jobLog.setHandleTime(new Date());
            jobLog.setHandleCode(XxlJobContext.HANDLE_CODE_FAIL);
            jobLog.setHandleMsg(I18nUtil.getString("joblog_lost_fail"));

            // 直接调 complete，把这条日志标记为失败
            XxlJobAdminBootstrap.getInstance().getJobCompleter().complete(jobLog);
        }

        TimeUnit.SECONDS.sleep(60);
    }
});
```

---

## 11. GLUE 动态任务

GLUE 模式是 xxl-job 的特色功能，允许在调度中心的 Web 界面上在线编写代码，不需要重新部署执行器。

### 11.1 GlueTypeEnum：支持的任务类型

```java
// GlueTypeEnum.java
BEAN          ("BEAN",            false, null,        null   ) // 普通 Bean 模式
GLUE_GROOVY   ("GLUE(Java)",      false, null,        null   ) // Groovy/Java 代码，在线编写
GLUE_SHELL    ("GLUE(Shell)",     true,  "bash",      ".sh"  ) // Shell 脚本
GLUE_PYTHON   ("GLUE(Python3)",   true,  "python3",   ".py"  ) // Python3 脚本
GLUE_PYTHON2  ("GLUE(Python2)",   true,  "python",    ".py"  ) // Python2 脚本
GLUE_NODEJS   ("GLUE(Nodejs)",    true,  "node",      ".js"  ) // Node.js 脚本
GLUE_POWERSHELL("GLUE(PowerShell)",true, "powershell",".ps1" ) // PowerShell 脚本
GLUE_PHP      ("GLUE(PHP)",       true,  "php",       ".php" ) // PHP 脚本
```

`isScript=true` 的类型走 `ScriptJobHandler`，通过 `Runtime.exec()` 执行；`isScript=false` 的走 `GlueJobHandler`，通过 Groovy 动态编译执行。

### 11.2 GlueFactory：Groovy 动态编译

```java
// GlueFactory.java
public class GlueFactory {
    private GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    private ConcurrentMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    public IJobHandler loadNewInstance(String codeSource) throws Exception {
        Class<?> clazz = getCodeSourceClass(codeSource);
        Object instance = clazz.newInstance();
        if (instance instanceof IJobHandler) {
            this.injectService(instance);  // SpringGlueFactory 会注入 Spring Bean
            return (IJobHandler) instance;
        }
        throw new IllegalArgumentException("cannot convert to IJobHandler");
    }

    private Class<?> getCodeSourceClass(String codeSource) {
        // 用 MD5 作为缓存 key，相同代码直接复用编译结果
        String md5Str = Md5Tool.md5(codeSource);
        Class<?> clazz = CLASS_CACHE.get(md5Str);
        if (clazz == null) {
            clazz = groovyClassLoader.parseClass(codeSource);  // Groovy 编译
            CLASS_CACHE.putIfAbsent(md5Str, clazz);
        }
        return clazz;
    }
}
```

`SpringGlueFactory` 覆盖了 `injectService()` 方法，用反射把 Groovy 类中带 `@Resource` 或 `@Autowired` 注解的字段注入 Spring Bean，这样 GLUE 代码里也能用 `@Resource` 注入 Service。

**代码版本管理**：每次修改 GLUE 代码后，`glueUpdatetime` 会更新。执行器收到触发请求时，会比较 `triggerRequest.getGlueUpdatetime()` 和当前 `JobThread` 里 Handler 的 `glueUpdatetime`，不一致就销毁旧线程，用新代码重新创建 Handler。数据库里会保存最近 30 个版本的代码（`xxl_job_logglue` 表）。

### 11.3 ScriptJobHandler：脚本执行

脚本任务的执行流程是：

1. 把脚本代码写入本地文件，文件名格式为 `{jobId}_{glueUpdatetime}{suffix}`（如 `123_1622000000000.sh`），旧版本文件在构造时自动清理
2. 调用 `ScriptUtil.execToFile(cmd, scriptFileName, logFileName, scriptParams)` 执行
3. `execToFile` 内部用 `Runtime.getRuntime().exec()` 启动子进程，stdout 和 stderr 都重定向到任务日志文件，等待进程结束
4. 通过退出码判断成功（0）还是失败（非 0）
5. 脚本可以通过 3 个位置参数获取任务信息：`$1`=任务参数，`$2`=分片序号，`$3`=分片总数

---

## 12. 完整调用链路总结

下面用一个完整的链路图描述从"任务调度时间到了"到"执行结果写入数据库"的全过程：

```
① 调度中心 JobScheduleHelper.scheduleThread
   - SELECT FOR UPDATE（抢分布式锁）
   - 查询 triggerNextTime <= now+5s 的任务
   - 任务未过期 → pushTimeRing(second, jobId)
   - 更新 triggerNextTime

② 调度中心 JobScheduleHelper.ringThread
   - 每秒唤醒，取当前秒+前2秒的任务列表
   - jobTriggerPoolHelper.trigger(jobId, CRON, ...)

③ 调度中心 JobTriggerPoolHelper
   - 按 Job 历史超时次数分配到 fastPool 或 slowPool
   - 在线程池里异步执行 JobTrigger.trigger()

④ 调度中心 JobTrigger.trigger()
   - 加载 XxlJobInfo（任务配置）、XxlJobGroup（执行器组）
   - 如果是分片广播：并发触发组内所有实例（循环调 processTrigger）
   - 其他路由：调一次 processTrigger

⑤ 调度中心 JobTrigger.processTrigger()
   - INSERT xxl_job_log（触发记录），拿到 logId
   - 构建 TriggerRequest（携带 logId）
   - 调用路由策略 .route() 选出目标地址
   - doTrigger(triggerParam, address)：HTTP POST {address}/run
   - UPDATE xxl_job_log（更新触发结果）

⑥ 执行器 EmbedServer 收到 /run 请求
   - Netty IO 线程接收，提交给 bizThreadPool
   - EmbedHttpServerHandler.dispatchRequest() → executorBiz.run()

⑦ 执行器 ExecutorBizImpl.run()
   - 解析 GlueType，确定 IJobHandler
   - 处理 BlockStrategy（丢弃/覆盖/排队）
   - 若需要新建：registJobThread(jobId, handler)
   - jobThread.pushTriggerQueue(triggerRequest)
   - 立即返回 HTTP 响应（不等任务执行完）

⑧ 执行器 JobThread（异步执行）
   - triggerQueue.poll(3s) 取出触发请求
   - 设置 XxlJobContext（ThreadLocal）
   - 有超时：FutureTask + futureTask.get(timeout)
   - 无超时：handler.execute() 直接调用
   - finally：TriggerCallbackThread.pushCallBack(result)

⑨ 执行器 TriggerCallbackThread
   - 批量 drainTo 队列里的回调请求
   - HTTP POST {adminAddress}/api → callback(callbackList)
   - 失败：写本地文件，定时重试

⑩ 调度中心 JobCompleteHelper.callback()
   - callbackThreadPool 异步处理
   - 幂等校验（handle_code > 0 表示已处理）
   - JobCompleter.complete(log)
     - processChildJob()：成功则触发子任务
     - UPDATE xxl_job_log（写入 handleCode、handleMsg、handleTime）

【完成】
xxl_job_log 的 handle_code 从 0 变为 200/500/502
在控制台就能看到任务执行结果
```

---

## 13. 如何接入 xxl-job（实操指南）

### 第一步：引入依赖

```xml
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
    <version>${版本号}</version>
</dependency>
```

### 第二步：配置文件

```yaml
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8080/xxl-job-admin  # 调度中心地址（多个用逗号分隔）
    accessToken: ""        # 与调度中心保持一致的 accessToken
    executor:
      appname: my-executor # 执行器名称，在调度中心注册执行器时使用
      address: ""          # 手动指定地址（不填则自动探测 IP）
      ip: ""               # 手动指定 IP
      port: 9999           # Netty 监听端口
      logpath: /data/xxl-job/log
      logretentiondays: 30
```

### 第三步：注册 XxlJobSpringExecutor Bean

```java
@Bean
public XxlJobSpringExecutor xxlJobExecutor() {
    XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
    executor.setAdminAddresses(adminAddresses);
    executor.setAppname(appname);
    executor.setIp(ip);
    executor.setPort(port);
    executor.setAccessToken(accessToken);
    executor.setLogPath(logPath);
    executor.setLogRetentionDays(logRetentionDays);
    return executor;
}
```

### 第四步：编写 JobHandler

```java
@Component
public class MyJobHandler {

    @XxlJob("myJobHandler")                // 在控制台配置 JobHandler 时填这个名字
    public void demoJobHandler() {
        // 获取任务参数
        String param = XxlJobHelper.getJobParam();
        XxlJobHelper.log("任务开始执行，参数：{}", param);

        try {
            // === 业务逻辑 ===
            doSomeWork(param);
            // === 业务逻辑结束 ===

            XxlJobHelper.log("任务执行成功");
            // 不调用 handleSuccess() 也可以，默认就是成功
        } catch (Exception e) {
            XxlJobHelper.log("任务执行失败：{}", e.getMessage());
            XxlJobHelper.handleFail("执行异常：" + e.getMessage());
        }
    }

    // 带 init/destroy 生命周期的示例
    @XxlJob(value = "myJobWithLifecycle", init = "init", destroy = "destroy")
    public void jobWithLifecycle() {
        XxlJobHelper.log("正在执行任务");
    }

    public void init() {
        // 线程创建时调用一次（可以做资源初始化）
        System.out.println("JobThread 初始化");
    }

    public void destroy() {
        // 线程销毁时调用一次（可以做资源清理）
        System.out.println("JobThread 销毁");
    }
}
```

### 第五步：分片广播示例

当路由策略设为分片广播，每个执行器实例会收到不同的分片参数，用于分布式并行处理大数据量任务：

```java
@XxlJob("shardingJobHandler")
public void shardingJobHandler() {
    int shardIndex = XxlJobHelper.getShardIndex();  // 本机分片序号，如 0
    int shardTotal = XxlJobHelper.getShardTotal();  // 总分片数，如 3

    XxlJobHelper.log("分片参数：当前分片={}/总分片={}", shardIndex, shardTotal);

    // 按分片序号取模，每个实例只处理自己那一份数据
    // 比如处理 id % shardTotal == shardIndex 的数据
    List<Long> dataIds = dataService.findBySharding(shardIndex, shardTotal);
    for (Long id : dataIds) {
        process(id);
    }
}
```

### 第六步：GLUE(Java) 模式示例

在控制台的代码编辑框里编写（继承 `IJobHandler`）：

```java
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.context.XxlJobHelper;
import org.springframework.beans.factory.annotation.Autowired;

public class DemoGlueJobHandler extends IJobHandler {

    @Autowired  // SpringGlueFactory 会注入 Spring Bean
    private UserService userService;

    @Override
    public void execute() throws Exception {
        String param = XxlJobHelper.getJobParam();
        XxlJobHelper.log("GLUE 任务执行，参数：{}", param);
        userService.doSomething(param);
        XxlJobHelper.handleSuccess("执行完成");
    }
}
```

---

> **总结**：xxl-job 的设计哲学是"简单到极致"。调度与执行的唯一通信手段是 HTTP，数据库的唯一角色是"分布式状态共享"（通过 FOR UPDATE 做分布式锁），没有引入任何复杂的中间件。执行器用 Netty 自嵌一个轻量 HTTP 服务，既避免了对 Web 容器的依赖，又获得了高并发处理能力。时间轮 + 预读的调度算法，以及快慢线程池的分级隔离，在工程实践上极为精巧。这些设计放在一起，构成了一个真正"能在生产环境放心用"的分布式任务调度系统。
