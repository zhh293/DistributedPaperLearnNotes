# Apache SkyWalking 深度解析

> 从基本使用到高级配置，再到源码级原理剖析，全面理解企业级分布式链路追踪系统。

---

## 目录

1. [概述与整体架构](#1-概述与整体架构)
2. [基本使用](#2-基本使用)
3. [核心概念](#3-核心概念)
4. [高级配置](#4-高级配置)
5. [源码级原理剖析](#5-源码级原理剖析)
6. [完整链路追踪流程图](#6-完整链路追踪流程图)
7. [核心设计亮点与面试要点](#7-核心设计亮点与面试要点)

---

## 1. 概述与整体架构

### 1.1 什么是 SkyWalking

Apache SkyWalking 是一款开源的分布式应用性能监控（APM）系统，专为微服务、云原生和容器化架构设计。它的核心能力包括：分布式链路追踪（Distributed Tracing）、性能指标收集（Metrics）、日志关联（Log Correlation）和告警（Alerting）。

SkyWalking 最大的特点是**无侵入**——通过 Java Agent 的字节码增强技术，业务代码无需任何修改即可获得完整的链路追踪能力。

### 1.2 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        SkyWalking 整体架构                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                      │
│  │ Service A │  │ Service B │  │ Service C │   ← 业务服务集群     │
│  │ + Agent   │  │ + Agent   │  │ + Agent   │                      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                      │
│       │              │              │                             │
│       │    gRPC (11800端口)          │                             │
│       └──────────────┼──────────────┘                             │
│                      ▼                                            │
│  ┌───────────────────────────────────────┐                      │
│  │         OAP Server (后端分析服务)        │                      │
│  │  ┌─────────────┐  ┌────────────────┐  │                      │
│  │  │  Receiver    │  │   Analyzer     │  │                      │
│  │  │  (接收数据)   │  │  (聚合分析)     │  │                      │
│  │  └─────────────┘  └────────────────┘  │                      │
│  │  ┌─────────────┐  ┌────────────────┐  │                      │
│  │  │  Query       │  │   Alarm        │  │                      │
│  │  │  (查询服务)   │  │  (告警引擎)     │  │                      │
│  │  └─────────────┘  └────────────────┘  │                      │
│  └───────────────────┬───────────────────┘                      │
│                      │                                            │
│                      ▼                                            │
│  ┌───────────────────────────────────────┐                      │
│  │           Storage (存储层)              │                      │
│  │   ElasticSearch / MySQL / BanyanDB     │                      │
│  └───────────────────────────────────────┘                      │
│                      ▲                                            │
│                      │ GraphQL (12800端口)                         │
│                      ▼                                            │
│  ┌───────────────────────────────────────┐                      │
│  │            SkyWalking UI               │                      │
│  │     (前端可视化界面，8080端口)            │                      │
│  └───────────────────────────────────────┘                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

四大组件职责：

| 组件 | 职责 | 关键技术 |
|------|------|----------|
| Agent（探针） | 收集 Trace/Metrics/Log 数据 | Java Agent + ByteBuddy 字节码增强 |
| OAP Server | 接收、分析、聚合数据；提供查询 API | gRPC 接收 + 流式聚合分析 |
| Storage | 持久化存储 | ES / MySQL / BanyanDB / H2 |
| UI | 可视化展示拓扑、链路、指标 | Vue.js + GraphQL |

---

## 2. 基本使用

### 2.1 部署 OAP Server + UI

```bash
# 下载 SkyWalking APM 发行包
wget https://archive.apache.org/dist/skywalking/9.7.0/apache-skywalking-apm-9.7.0.tar.gz
tar -xzf apache-skywalking-apm-9.7.0.tar.gz
cd apache-skywalking-apm-bin

# 启动 OAP Server 和 UI（默认使用 H2 内存数据库）
bin/startup.sh

# OAP Server 监听 11800 (gRPC) 和 12800 (REST/GraphQL)
# UI 监听 8080
```

### 2.2 Java 应用接入 Agent

接入方式极其简单，只需在 JVM 启动参数中加入 `-javaagent`：

```bash
java -javaagent:/path/to/skywalking-agent/skywalking-agent.jar \
     -Dskywalking.agent.service_name=order-service \
     -Dskywalking.collector.backend_service=192.168.1.100:11800 \
     -jar order-service.jar
```

整个过程**无需修改任何业务代码**，Agent 会自动拦截 Spring MVC、Dubbo、gRPC、MySQL、Redis、Kafka 等 100+ 框架的调用。

### 2.3 配置文件说明

Agent 的配置文件位于 `skywalking-agent/config/agent.config`：

```properties
# 服务名称（必填）
agent.service_name=${SW_AGENT_NAME:your-service-name}

# OAP Server 地址（必填）
collector.backend_service=${SW_AGENT_COLLECTOR_BACKEND_SERVICES:127.0.0.1:11800}

# 采样率：每3秒最多采集N条Trace，-1为全量采集
agent.sample_n_per_3_secs=${SW_AGENT_SAMPLE:-1}

# 日志级别
logging.level=${SW_LOGGING_LEVEL:INFO}

# 日志文件路径
logging.dir=${SW_LOGGING_DIR:}

# 忽略后缀（静态资源不追踪）
agent.ignore_suffix=${SW_AGENT_IGNORE_SUFFIX:.jpg,.jpeg,.js,.css,.png,.bmp,.gif,.ico,.mp3,.mp4,.html,.svg}

# 单个 Segment 最大 Span 数量
agent.span_limit_per_segment=${SW_AGENT_SPAN_LIMIT:300}
```

### 2.4 Docker Compose 快速启动

```yaml
version: '3'
services:
  elasticsearch:
    image: elasticsearch:7.17.10
    environment:
      - discovery.type=single-node
    ports:
      - "9200:9200"

  oap:
    image: apache/skywalking-oap-server:9.7.0
    environment:
      - SW_STORAGE=elasticsearch
      - SW_STORAGE_ES_CLUSTER_NODES=elasticsearch:9200
    depends_on:
      - elasticsearch
    ports:
      - "11800:11800"
      - "12800:12800"

  ui:
    image: apache/skywalking-ui:9.7.0
    environment:
      - SW_OAP_ADDRESS=http://oap:12800
    depends_on:
      - oap
    ports:
      - "8080:8080"
```

---

## 3. 核心概念

### 3.1 Trace、Segment、Span 三层数据模型

这是理解 SkyWalking 链路追踪的基础，三者的关系是：

```
Trace（一次完整的分布式调用链）
│
├── TraceSegment-A（Service A 的 Thread-1 中的调用片段）
│   ├── EntrySpan    （接收外部请求，如 Tomcat 接收 HTTP）
│   ├── LocalSpan    （本地方法调用，如业务逻辑处理）
│   └── ExitSpan     （发出外部请求，如 HttpClient 调用下游）
│
├── TraceSegment-B（Service B 的 Thread-1 中的调用片段）
│   ├── EntrySpan    （接收来自 Service A 的请求）
│   ├── LocalSpan    （业务处理）
│   └── ExitSpan     （查询数据库）
│
└── TraceSegment-C（Service B 的 Thread-2 中的异步调用片段）
    └── LocalSpan    （异步任务处理）
```

#### Trace

一次完整的分布式请求从入口到结束所经过的所有服务节点，通过一个全局唯一的 `traceId` 串联。

#### TraceSegment

SkyWalking 特有概念（OpenTracing/OpenTelemetry 中没有）。一个 Segment 代表**单个线程**内的一段完整调用。引入 Segment 的核心目的是：将同一线程内的多个 Span 打包成一个整体进行传输和存储，减少网络开销。

#### Span

链路中数据收集的最小单位，代表一次具体的操作（如一次方法调用、一次 RPC 请求）。分为三类：

| Span 类型 | 含义 | 示例 |
|-----------|------|------|
| EntrySpan | 服务的入口点，接收请求 | Tomcat 接收 HTTP 请求、Dubbo Provider 接收调用 |
| LocalSpan | 本地方法调用，不涉及网络 | 业务方法、Spring Bean 调用 |
| ExitSpan | 服务的出口点，发出请求 | HttpClient 调用、JDBC 查询、Redis 操作 |

### 3.2 上下文传播的两种机制

| 场景 | 载体 | 是否需要序列化 | 传输方式 |
|------|------|---------------|----------|
| 跨进程（Cross-Process） | ContextCarrier | 是（Base64编码） | HTTP Header / RPC Attachment / MQ Properties |
| 跨线程（Cross-Thread） | ContextSnapshot | 否（同一 JVM 内存传递） | 方法参数 / 对象字段 |

### 3.3 sw8 跨进程传播协议

sw8 是 SkyWalking Cross-Process Propagation Headers Protocol v3 的简称，是链路追踪能跨服务串联的关键协议。

Header 名称：`sw8`

Header 值格式：8个字段用 `-` 分隔

```
{Sample}-{TraceId}-{ParentSegmentId}-{ParentSpanId}-{ParentService}-{ParentServiceInstance}-{ParentEndpoint}-{TargetAddress}
```

各字段详解：

| 序号 | 字段 | 类型 | 说明 |
|------|------|------|------|
| 1 | Sample | 0 或 1 | 是否采样。1=采样，0=不采样 |
| 2 | Trace Id | Base64(String) | 全局唯一的 Trace 标识 |
| 3 | Parent Segment Id | Base64(String) | 上游 Segment 的唯一 ID |
| 4 | Parent Span Id | Int | 上游 Span 在其 Segment 中的序号 |
| 5 | Parent Service | Base64(String) | 上游服务名称 |
| 6 | Parent Service Instance | Base64(String) | 上游服务实例名称 |
| 7 | Parent Endpoint | Base64(String) | 上游端点名称（如 GET:/api/order） |
| 8 | Target Address | Base64(String) | 本次请求的目标地址（IP:Port） |

实际示例：

```
1-dGVzdFRyYWNlSWQ=-cGFyZW50U2VnbWVudElk-3-b3JkZXItc2VydmljZQ==-aW5zdGFuY2UtMQ==-R0VUOi9hcGkvb3JkZXI=-MTkyLjE2OC4xLjEwOjgwODA=
```

解码后：

```
Sample=1, TraceId=testTraceId, ParentSegmentId=parentSegmentId, 
ParentSpanId=3, ParentService=order-service, 
ParentServiceInstance=instance-1, ParentEndpoint=GET:/api/order, 
TargetAddress=192.168.1.10:8080
```

此外还有扩展 Header `sw8-correlation`，用于传递自定义业务数据（如 userId、灰度标记等）。

---

## 4. 高级配置

### 4.1 采样策略

```properties
# Agent 端采样：每3秒最多采集N条完整Trace
agent.sample_n_per_3_secs=50

# 强制采样：当下游被采样时，即使本服务超出采样限制也强制采样
agent.force_reconnection_period=1
```

OAP Server 还支持动态下发采样率（通过 Dynamic Configuration），在高流量时自动降低采样比例。

### 4.2 忽略特定路径

需要引入 `apm-trace-ignore-plugin`（将 jar 从 optional-plugins 移至 plugins 目录）：

```properties
# 排除健康检查、监控探针等无意义端点
trace.ignore_path=${SW_IGNORE_PATH:/health,/actuator/**,/favicon.ico}
```

支持 Ant 风格路径匹配。

### 4.3 日志与 Trace 关联

这是企业实践中最有价值的功能之一——让每条业务日志都携带 traceId，出问题时通过日志直接跳转到对应链路。

#### 步骤一：添加依赖

```xml
<!-- 以 logback 为例 -->
<dependency>
    <groupId>org.apache.skywalking</groupId>
    <artifactId>apm-toolkit-logback-1.x</artifactId>
    <version>9.1.0</version>
</dependency>
```

#### 步骤二：配置 logback.xml

```xml
<appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
        <layout class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdPatternLogbackLayout">
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%tid] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </layout>
    </encoder>
</appender>
```

`%tid` 就是 SkyWalking 的 traceId，会自动注入到每条日志中。

#### 步骤三（可选）：日志上报到 OAP

配置 gRPC Log Reporter，将日志也发送到 OAP，实现 UI 中 Trace-Log 双向跳转：

```xml
<appender name="grpc-log" class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.log.GRPCLogClientAppender">
    <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
        <layout class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.mdc.TraceIdMDCPatternLogbackLayout">
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%tid] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </layout>
    </encoder>
</appender>
```

### 4.4 自定义 Span（手动埋点 Toolkit）

当自动插桩覆盖不到的场景（如自研 RPC 框架、特殊业务逻辑），可使用 Toolkit API：

```java
import org.apache.skywalking.apm.toolkit.trace.Trace;
import org.apache.skywalking.apm.toolkit.trace.Tag;
import org.apache.skywalking.apm.toolkit.trace.Tags;

public class OrderService {

    @Trace                                          // 该方法自动成为一个 LocalSpan
    @Tag(key = "orderId", value = "returnedObj.id") // 给 Span 打 Tag
    public Order createOrder(OrderRequest req) {
        // 业务逻辑...
        return order;
    }

    @Trace(operationName = "customOperationName")   // 自定义操作名
    @Tags({
        @Tag(key = "param.userId", value = "arg[0]"),
        @Tag(key = "param.amount", value = "arg[1]")
    })
    public void processPayment(String userId, double amount) {
        // 业务逻辑...
    }
}
```

### 4.5 跨进程自定义数据传递（Correlation Context）

sw8-correlation 允许在整个调用链中传递自定义 K-V 数据：

```java
import org.apache.skywalking.apm.toolkit.trace.CorrelationContext;

// 在链路的任意节点设置
CorrelationContext.put("userId", "12345");
CorrelationContext.put("grayFlag", "canary-v2");

// 下游任意节点都能读取
String userId = CorrelationContext.get("userId");  // "12345"
```

配置限制：

```properties
# 最大 correlation 条目数（默认3）
correlation_element_max_number=3
# 单个 value 最大长度（默认128）
correlation_value_max_length=128
```

### 4.6 告警规则配置

OAP Server 的 `alarm-settings.yml`：

```yaml
rules:
  service_resp_time_rule:
    metrics-name: service_resp_time
    op: ">"
    threshold: 1000              # 响应时间超过1000ms
    period: 10                   # 持续10分钟
    count: 3                     # 触发3次
    silence-period: 5            # 告警后静默5分钟
    message: "服务 {name} 的响应时间超过1秒"

  service_sla_rule:
    metrics-name: service_sla
    op: "<"
    threshold: 8000              # 成功率低于80%
    period: 10
    count: 2
    message: "服务 {name} 成功率低于80%"
```

### 4.7 性能调优相关配置

```properties
# gRPC 数据发送缓冲 channel 数量
buffer.channel_size=${SW_BUFFER_CHANNEL_SIZE:5}

# 每个 channel 的 buffer 大小
buffer.buffer_size=${SW_BUFFER_BUFFER_SIZE:300}

# 单个 Segment 内最大 Span 数量（防止循环调用导致OOM）
agent.span_limit_per_segment=${SW_AGENT_SPAN_LIMIT:300}

# 操作名称最大长度
agent.operation_name_threshold=${SW_AGENT_OPERATION_NAME_THRESHOLD:150}

# 是否开启调试模式（生产环境务必关闭）
agent.is_open_debugging_class=${SW_AGENT_OPEN_DEBUG:false}
```

---

## 5. 源码级原理剖析

### 5.1 Agent 启动流程：premain 入口

SkyWalking Agent 的入口类是 `SkyWalkingAgent`，通过 JVM 的 `premain` 机制在 main 方法之前执行：

```java
// 源码位置：apm-sniffer/apm-agent/src/main/java/org/apache/skywalking/apm/agent/SkyWalkingAgent.java
public class SkyWalkingAgent {

    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException {
        
        // ==================== 第一步：初始化配置 ====================
        SnifferConfigInitializer.initializeCoreConfig(agentArgs);
        // 加载 agent.config 文件，支持系统属性 > 环境变量 > 配置文件 的优先级覆盖

        // ==================== 第二步：加载插件 ====================
        // 通过 SPI 机制扫描所有 skywalking-plugin.def 文件
        // 每个插件 jar 的 resources 目录下都有一个 skywalking-plugin.def，声明了插件类
        List<AbstractClassEnhancePluginDefine> plugins = new PluginBootstrap().loadPlugins();
        PluginFinder pluginFinder = new PluginFinder(plugins);
        // pluginFinder 建立了"类名 → 插件列表"的映射关系

        // ==================== 第三步：ByteBuddy 字节码增强 ====================
        final ByteBuddy byteBuddy = new ByteBuddy().with(TypeValidation.of(false));
        
        AgentBuilder agentBuilder = new AgentBuilder.Default(byteBuddy)
            .ignore(                                        // 忽略不需要增强的类
                nameStartsWith("net.bytebuddy.")
                .or(nameStartsWith("org.slf4j."))
                .or(nameStartsWith("org.apache.logging."))
                // ... 更多忽略规则
            )
            .type(pluginFinder.buildMatch())               // 匹配需要增强的类
            .transform(new Transformer(pluginFinder))       // 定义如何增强
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new Listener())                           // 监听增强结果（成功/失败）
            .installOn(instrumentation);                    // 安装到 JVM

        // ==================== 第四步：启动后台服务 ====================
        ServiceManager.INSTANCE.boot();
        // 启动 gRPC 连接管理、心跳、TraceSegmentServiceClient（数据上报）、
        // JVMMetricsSender（JVM 指标上报）等后台服务
    }
}
```

### 5.2 ByteBuddy 字节码增强机制

#### 5.2.1 插件定义结构

每个插件需要继承 `ClassInstanceMethodsEnhancePluginDefine` 并声明三个核心内容：

```java
// 以 Spring MVC 插件为例
// 源码位置：apm-sniffer/apm-sdk-plugin/spring-plugins/mvc-annotation-plugin/
public class SpringMVCInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {

    // 1. 声明要增强的目标类
    @Override
    protected ClassMatch enhanceClass() {
        // 匹配所有标注了 @Controller 或 @RestController 的类
        return ClassAnnotationMatch.byClassAnnotationMatch(
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"
        );
    }

    // 2. 声明构造方法拦截点（可选）
    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return null;
    }

    // 3. 声明实例方法拦截点（核心）
    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[] {
            new InstanceMethodsInterceptPoint() {
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    // 匹配所有标注了 @RequestMapping 等注解的方法
                    return byMethodAnnotationMatch(
                        "org.springframework.web.bind.annotation.RequestMapping",
                        "org.springframework.web.bind.annotation.GetMapping",
                        "org.springframework.web.bind.annotation.PostMapping"
                        // ...
                    );
                }
                @Override
                public String getMethodsInterceptor() {
                    // 拦截器类的全限定名
                    return "org.apache.skywalking.apm.plugin.spring.mvc.RequestMappingMethodInterceptor";
                }
                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
```

#### 5.2.2 拦截器执行逻辑

拦截器实现 `InstanceMethodsAroundInterceptor` 接口：

```java
// 拦截器接口定义
public interface InstanceMethodsAroundInterceptor {
    // 方法执行前
    void beforeMethod(EnhancedInstance objInst, Method method, 
                      Object[] allArguments, Class<?>[] argumentsTypes,
                      MethodInterceptResult result) throws Throwable;
    
    // 方法执行后（正常返回）
    Object afterMethod(EnhancedInstance objInst, Method method,
                       Object[] allArguments, Class<?>[] argumentsTypes,
                       Object ret) throws Throwable;
    
    // 方法执行异常
    void handleMethodException(EnhancedInstance objInst, Method method,
                               Object[] allArguments, Class<?>[] argumentsTypes,
                               Throwable t);
}
```

以 Tomcat 插件的拦截器为例：

```java
// TomcatInvokeInterceptor - Tomcat 服务端入口拦截器
public class TomcatInvokeInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method,
                             Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        
        HttpServletRequest request = (HttpServletRequest) allArguments[0];
        
        // ===== 第一步：从 HTTP Header 中提取 sw8 上下文 =====
        ContextCarrier contextCarrier = new ContextCarrier();
        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            next.setHeadValue(request.getHeader(next.getHeadKey()));
            // 这里读取的就是 "sw8" 和 "sw8-correlation" Header
        }
        
        // ===== 第二步：创建 EntrySpan =====
        String operationName = getOperationName(request);  // 如 "GET:/api/order"
        AbstractSpan span = ContextManager.createEntrySpan(operationName, contextCarrier);
        // 内部会调用 TracingContext.extract(carrier) 建立父子关系
        
        // ===== 第三步：记录 Span 信息 =====
        span.setComponent(ComponentsDefine.TOMCAT);
        SpanLayer.asHttp(span);
        span.tag(Tags.URL, request.getRequestURL().toString());
        span.tag(Tags.HTTP_METHOD, request.getMethod());
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method,
                              Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        HttpServletResponse response = (HttpServletResponse) allArguments[1];
        AbstractSpan span = ContextManager.activeSpan();
        
        // 记录 HTTP 状态码
        span.tag(Tags.HTTP_RESPONSE_STATUS_CODE, String.valueOf(response.getStatus()));
        if (response.getStatus() >= 400) {
            span.errorOccurred();  // 标记为错误
        }
        
        // 结束 Span
        ContextManager.stopSpan();
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method,
                                      Object[] allArguments, Class<?>[] argumentsTypes,
                                      Throwable t) {
        AbstractSpan span = ContextManager.activeSpan();
        span.errorOccurred();
        span.log(t);  // 记录异常堆栈
    }
}
```

### 5.3 上下文管理核心：ContextManager + TracingContext

#### 5.3.1 ContextManager —— 门面类

```java
// 源码位置：apm-sniffer/apm-agent-core/src/main/java/
//   org/apache/skywalking/apm/agent/core/context/ContextManager.java
public class ContextManager implements BootService {

    // 核心：使用 ThreadLocal 为每个线程维护独立的 Context
    private static ThreadLocal<AbstractTracerContext> CONTEXT = new ThreadLocal<>();

    // 获取或创建当前线程的 TracingContext
    private static AbstractTracerContext getOrCreate(String operationName, boolean forceSampling) {
        AbstractTracerContext context = CONTEXT.get();
        if (context == null) {
            if (/* 采样判断 */) {
                // 被采样 → 创建完整的 TracingContext
                context = new TracingContext(operationName);
            } else {
                // 未被采样 → 创建 IgnoredTracerContext（空实现，几乎零开销）
                context = new IgnoredTracerContext();
            }
            CONTEXT.set(context);
        }
        return context;
    }

    // 创建 EntrySpan（服务端接收请求时调用）
    public static AbstractSpan createEntrySpan(String operationName, ContextCarrier carrier) {
        AbstractTracerContext context = getOrCreate(operationName, false);
        AbstractSpan span = context.createEntrySpan(operationName);
        if (carrier != null && carrier.isValid()) {
            context.extract(carrier);  // 提取上游上下文，建立父子关系
        }
        return span;
    }

    // 创建 ExitSpan（调用下游服务前调用）
    public static AbstractSpan createExitSpan(String operationName,
                                              ContextCarrier carrier,
                                              String remotePeer) {
        AbstractTracerContext context = getOrCreate(operationName, false);
        AbstractSpan span = context.createExitSpan(operationName, remotePeer);
        context.inject(carrier);  // 注入当前上下文到 carrier
        return span;
    }

    // 创建 LocalSpan（本地方法调用）
    public static AbstractSpan createLocalSpan(String operationName) {
        AbstractTracerContext context = getOrCreate(operationName, false);
        return context.createLocalSpan(operationName);
    }

    // 结束当前活跃的 Span
    public static void stopSpan() {
        AbstractTracerContext context = CONTEXT.get();
        context.stopSpan(context.activeSpan());
        // 如果所有 Span 都结束了，触发 Segment finish
    }

    // 捕获上下文快照（用于跨线程传播）
    public static ContextSnapshot capture() {
        return CONTEXT.get().capture();
    }

    // 恢复上下文快照（子线程中调用）
    public static void continued(ContextSnapshot snapshot) {
        AbstractTracerContext context = getOrCreate("", false);
        context.continued(snapshot);
    }
}
```

#### 5.3.2 TracingContext —— 核心上下文

```java
// 源码位置：apm-sniffer/apm-agent-core/src/main/java/
//   org/apache/skywalking/apm/agent/core/context/TracingContext.java
public class TracingContext implements AbstractTracerContext {

    private TraceSegment segment;                    // 当前线程的 Segment
    private LinkedList<AbstractSpan> activeSpanStack; // Span 栈（LIFO）
    private int spanIdGenerator;                     // Span ID 自增器

    // 构造时分配全局唯一的 segmentId 和 traceId
    TracingContext(String firstOperationName) {
        this.segment = new TraceSegment();
        this.activeSpanStack = new LinkedList<>();
        // TraceSegment 构造时：
        //   this.traceSegmentId = GlobalIdGenerator.generate();  // 全局唯一
        //   this.relatedGlobalTraceId = new NewDistributedTraceId(); // 新traceId
    }

    // ===== 注入：将当前上下文写入 ContextCarrier =====
    @Override
    public void inject(ContextCarrier carrier) {
        AbstractSpan span = this.activeSpan();  // 当前活跃的 ExitSpan
        
        carrier.setTraceId(getReadablePrimaryTraceId());           // traceId
        carrier.setTraceSegmentId(this.segment.getTraceSegmentId()); // 当前 segmentId
        carrier.setSpanId(span.getSpanId());                        // 当前 spanId
        carrier.setParentService(Config.Agent.SERVICE_NAME);        // 服务名
        carrier.setParentServiceInstance(Config.Agent.INSTANCE_NAME);// 实例名
        carrier.setParentEndpoint(firstSpan().getOperationName());  // 入口端点名
        carrier.setAddressUsedAtClient(span.getPeer());             // 目标地址

        // carrier 会被序列化为 sw8 Header 的值
        // 格式：1-{base64TraceId}-{base64SegmentId}-{spanId}-{base64Service}-...
    }

    // ===== 提取：从 ContextCarrier 恢复上游上下文 =====
    @Override
    public void extract(ContextCarrier carrier) {
        // 关键操作：建立当前 Segment 与上游 Segment 的父子引用关系
        TraceSegmentRef ref = new TraceSegmentRef(carrier);
        this.segment.ref(ref);
        // TraceSegmentRef 记录了：parentTraceSegmentId, parentSpanId, 
        //                         parentService, parentEndpoint 等

        // 使用上游传来的 traceId（保证整条链路共享同一个 traceId）
        this.segment.relatedGlobalTraceId(new PropagatedTraceId(carrier.getTraceId()));
        
        // 提取 correlation context（自定义业务数据）
        this.correlationContext.extract(carrier.getCorrelationContext());
    }

    // ===== Span 栈管理 =====
    @Override
    public AbstractSpan createEntrySpan(String operationName) {
        // 如果栈顶已经是 EntrySpan（嵌套的入口，如 Filter → Servlet）
        // 则复用栈顶 Span（只更新 operationName），不创建新的
        if (!activeSpanStack.isEmpty()) {
            AbstractSpan parentSpan = peek();
            if (parentSpan instanceof EntrySpan) {
                parentSpan.setOperationName(operationName);
                return parentSpan;
            }
        }
        // 否则创建新的 EntrySpan 并压栈
        EntrySpan span = new EntrySpan(spanIdGenerator++, parentSpanId(), operationName);
        activeSpanStack.push(span);
        return span;
    }

    @Override
    public AbstractSpan createExitSpan(String operationName, String remotePeer) {
        // 类似逻辑：如果栈顶已经是 ExitSpan（嵌套的出口调用）
        // 则复用栈顶 Span
        // ...
        ExitSpan span = new ExitSpan(spanIdGenerator++, parentSpanId(), operationName, remotePeer);
        activeSpanStack.push(span);
        return span;
    }

    // ===== Span 结束与 Segment 完成 =====
    @Override
    public boolean stopSpan(AbstractSpan span) {
        AbstractSpan lastSpan = peek();
        if (lastSpan == span) {
            // 弹出栈顶 Span
            activeSpanStack.pop();
            // 将完成的 Span 加入 Segment 的 spans 列表
            segment.archive(span);
        }
        
        // 如果栈空了，说明这个 Segment 的所有 Span 都结束了
        if (activeSpanStack.isEmpty()) {
            finish();  // 触发 Segment 完成
            return true;
        }
        return false;
    }

    private void finish() {
        TraceSegment finishedSegment = this.segment.finish(isLimitMechanismWorking());
        // 通知所有监听器（最重要的是 TraceSegmentServiceClient）
        TracingContext.ListenerManager.notifyFinish(finishedSegment);
        // 清理 ThreadLocal
        ContextManager.CONTEXT.remove();
    }
}
```

### 5.4 跨进程传播的完整源码流程

下面用一个 Service A (HttpClient) → Service B (Tomcat) 的调用来完整走一遍源码流程：

#### 第一阶段：Service A 发出请求（inject）

```
Service A 的 HttpClient 插件拦截器：HttpClientExecuteInterceptor.beforeMethod()

    │
    ▼
1. 创建 ContextCarrier（空对象）
   ContextCarrier contextCarrier = new ContextCarrier();

    │
    ▼
2. 创建 ExitSpan + inject
   AbstractSpan span = ContextManager.createExitSpan(uri, contextCarrier, remotePeer);
   
   内部调用链：
   ContextManager.createExitSpan()
     → TracingContext.createExitSpan()     // 创建 ExitSpan 并压栈
     → TracingContext.inject(carrier)       // 将上下文信息写入 carrier
       → carrier.setTraceId(...)           // 设置 traceId
       → carrier.setTraceSegmentId(...)    // 设置当前 segmentId
       → carrier.setSpanId(...)            // 设置当前 spanId
       → carrier.setParentService(...)     // 设置服务名
       → ...

    │
    ▼
3. 将 carrier 序列化到 HTTP Header
   CarrierItem next = contextCarrier.items();
   while (next.hasNext()) {
       next = next.next();
       // next.getHeadKey() = "sw8"
       // next.getHeadValue() = "1-base64TraceId-base64SegmentId-2-base64Service-..."
       httpRequest.setHeader(next.getHeadKey(), next.getHeadValue());
   }

    │
    ▼
4. HTTP 请求发出，Header 中携带 sw8
```

#### 第二阶段：Service B 接收请求（extract）

```
Service B 的 Tomcat 插件拦截器：TomcatInvokeInterceptor.beforeMethod()

    │
    ▼
1. 从 HTTP Header 中反序列化 ContextCarrier
   ContextCarrier contextCarrier = new ContextCarrier();
   CarrierItem next = contextCarrier.items();
   while (next.hasNext()) {
       next = next.next();
       next.setHeadValue(request.getHeader(next.getHeadKey()));
       // 读取 "sw8" header → 解析出 traceId, parentSegmentId, parentSpanId 等
   }

    │
    ▼
2. 创建 EntrySpan + extract
   AbstractSpan span = ContextManager.createEntrySpan(operationName, contextCarrier);
   
   内部调用链：
   ContextManager.createEntrySpan(name, carrier)
     → getOrCreate()                        // 创建新的 TracingContext
       → new TracingContext()               // 分配新的 segmentId，临时的 traceId
     → context.createEntrySpan(name)        // 创建 EntrySpan 并压栈
     → context.extract(carrier)             // ★ 核心：建立父子关系 ★
       → new TraceSegmentRef(carrier)       // 创建引用对象
       → segment.ref(ref)                   // 将引用挂到当前 Segment
       → segment.relatedGlobalTraceId(      // ★ 使用上游传来的 traceId ★
           carrier.getTraceId())            //   保证全链路同一个 traceId

    │
    ▼
3. 此时 Service B 的 Segment 状态：
   TraceSegment {
       traceSegmentId: "新分配的唯一ID"
       relatedGlobalTraceId: "上游传来的traceId"  ← 同一条Trace！
       refs: [TraceSegmentRef {
           parentTraceSegmentId: "Service A 的 segmentId"
           parentSpanId: 2
           parentService: "service-a"
           parentEndpoint: "POST:/api/order"
       }]
       spans: [EntrySpan {...}]
   }
```

#### 第三阶段：OAP Server 还原调用链

```
OAP Server 收到来自多个 Agent 的 Segment 数据后：

1. 按 traceId 分组 → 找到属于同一条 Trace 的所有 Segment
2. 读取每个 Segment 的 refs（TraceSegmentRef）
3. 通过 parentTraceSegmentId + parentSpanId 建立父子关系
4. 构建完整的调用树（DAG）

最终在 UI 上展示为：

   [Service A] EntrySpan: POST /api/order
       └── [Service A] ExitSpan: GET /api/inventory (→ Service B)
               └── [Service B] EntrySpan: GET /api/inventory
                       └── [Service B] ExitSpan: SELECT * FROM stock (→ MySQL)
```

### 5.5 跨线程传播源码

异步场景（如 `@Async`、`CompletableFuture`）不走 HTTP Header，而是通过内存传递：

```java
// ===== 主线程：捕获快照 =====
// 源码：TracingContext.capture()
public ContextSnapshot capture() {
    ContextSnapshot snapshot = new ContextSnapshot(
        segment.getTraceSegmentId(),    // 当前 segmentId
        activeSpan().getSpanId(),       // 当前 spanId
        getReadablePrimaryTraceId(),    // traceId
        firstSpan().getOperationName(), // 入口端点名
        correlationContext              // 自定义数据
    );
    return snapshot;
}

// 插件通常会在增强后的对象上保存这个 snapshot
// 如 @Async 插件会通过 EnhancedInstance 的 _$EnhancedClassField_ws 字段保存

// ===== 子线程：恢复上下文 =====
// 源码：TracingContext.continued()
public void continued(ContextSnapshot snapshot) {
    if (snapshot.isValid()) {
        // 建立父子关系（与 extract 逻辑几乎完全相同）
        TraceSegmentRef ref = new TraceSegmentRef(snapshot);
        this.segment.ref(ref);
        this.segment.relatedGlobalTraceId(snapshot.getTraceId());
        this.correlationContext.continued(snapshot.getCorrelationContext());
    }
}
```

**跨进程 vs 跨线程的唯一区别**：跨进程需要序列化（Base64编码放入 Header），跨线程不需要序列化（直接在 JVM 内存中传递 ContextSnapshot 对象）。两者建立父子关系的逻辑完全一致。

### 5.6 数据上报：从 Agent 到 OAP

当一个 Segment 的所有 Span 结束后，数据如何到达 OAP Server：

```java
// 第一步：TracingContext.finish() 通知监听器
TracingContext.ListenerManager.notifyFinish(finishedSegment);

// 第二步：TraceSegmentServiceClient 接收通知
// 源码位置：apm-agent-core/.../remote/TraceSegmentServiceClient.java
public class TraceSegmentServiceClient implements BootService, TracingContextListener {
    
    // 高性能环形缓冲队列（生产者-消费者模型）
    private volatile DataCarrier<TraceSegment> carrier;

    @Override
    public void afterFinished(TraceSegment traceSegment) {
        // 将完成的 Segment 放入缓冲队列（非阻塞）
        if (!carrier.produce(traceSegment)) {
            // 如果队列满了，丢弃数据（保证不影响业务性能）
            logger.debug("%.TraceSegment buffer is full.");
        }
    }

    // 第三步：后台消费线程批量取出并通过 gRPC 上报
    @Override
    public void consume(List<TraceSegment> data) {
        // 建立 gRPC 流式连接
        StreamObserver<SegmentObject> upstream = serviceStub.collect(
            new StreamObserver<Commands>() { /* 响应处理 */ }
        );
        
        for (TraceSegment segment : data) {
            // 将 TraceSegment 转换为 protobuf 格式
            SegmentObject segmentObject = segment.transform();
            // 通过 gRPC stream 发送
            upstream.onNext(segmentObject);
        }
        upstream.onCompleted();
    }
}
```

DataCarrier 的设计特点：

- 多 Channel 环形缓冲：减少锁竞争
- 满时丢弃策略：绝不阻塞业务线程
- 批量消费：减少 gRPC 调用次数，提高吞吐

---

## 6. 完整链路追踪流程图

### 6.1 单次请求的完整生命周期

```
时间轴 →→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→→

         Service A (order-service)                    Service B (inventory-service)
    ┌─────────────────────────────────────┐    ┌─────────────────────────────────┐
    │                                     │    │                                 │
t1  │ ① 用户请求到达 Tomcat               │    │                                 │
    │    → Tomcat插件 beforeMethod()       │    │                                 │
    │    → 读取 HTTP Header (无 sw8)       │    │                                 │
    │    → ContextManager.createEntrySpan  │    │                                 │
    │      → 新建 TracingContext           │    │                                 │
    │      → 分配 traceId = "abc123"       │    │                                 │
    │      → 分配 segmentId = "seg-A"      │    │                                 │
    │      → 创建 EntrySpan(spanId=0)      │    │                                 │
    │                                     │    │                                 │
t2  │ ② 业务逻辑执行...                    │    │                                 │
    │    （可能产生 LocalSpan）             │    │                                 │
    │                                     │    │                                 │
t3  │ ③ HttpClient 调用 Service B          │    │                                 │
    │    → HttpClient插件 beforeMethod()   │    │                                 │
    │    → 创建 ExitSpan(spanId=1)         │    │                                 │
    │    → inject(contextCarrier):         │    │                                 │
    │       traceId="abc123"               │    │                                 │
    │       segmentId="seg-A"              │    │                                 │
    │       spanId=1                       │    │                                 │
    │    → 写入 sw8 Header                 │    │                                 │
    │                                     │    │                                 │
    │ ═══════ HTTP + sw8 Header ══════════════► │                                 │
    │                                     │    │                                 │
t4  │                                     │    │ ④ Tomcat 接收请求                │
    │                                     │    │    → Tomcat插件 beforeMethod()   │
    │                                     │    │    → 读取 sw8 Header             │
    │                                     │    │    → 反序列化 ContextCarrier     │
    │                                     │    │    → createEntrySpan + extract   │
    │                                     │    │      → 新建 TracingContext       │
    │                                     │    │      → segmentId = "seg-B"      │
    │                                     │    │      → traceId = "abc123" ← 同一条!│
    │                                     │    │      → ref → {seg-A, spanId=1} │
    │                                     │    │                                 │
t5  │                                     │    │ ⑤ 查询 MySQL                    │
    │                                     │    │    → MySQL插件 创建 ExitSpan     │
    │                                     │    │    → 记录 SQL 语句和耗时          │
    │                                     │    │    → ExitSpan finish             │
    │                                     │    │                                 │
t6  │                                     │    │ ⑥ 返回响应                       │
    │                                     │    │    → EntrySpan finish            │
    │                                     │    │    → Segment-B 所有Span结束      │
    │                                     │    │    → finish() → 放入 DataCarrier │
    │                                     │    │    → 后台 gRPC 上报到 OAP        │
    │                                     │    │                                 │
    │ ◄═══════ HTTP Response ═══════════════════ │                                 │
    │                                     │    │                                 │
t7  │ ⑦ 收到响应                           │    │                                 │
    │    → HttpClient插件 afterMethod()    │    │                                 │
    │    → ExitSpan finish                 │    │                                 │
    │    → EntrySpan finish                │    │                                 │
    │    → Segment-A 所有 Span 结束         │    │                                 │
    │    → finish() → 放入 DataCarrier      │    │                                 │
    │    → 后台 gRPC 上报到 OAP             │    │                                 │
    │                                     │    │                                 │
    └─────────────────────────────────────┘    └─────────────────────────────────┘

         ┌──────────────── OAP Server ─────────────────┐
         │                                              │
    t8   │ ⑧ 接收 Segment-A 和 Segment-B               │
         │    → 按 traceId="abc123" 分组                │
         │    → 通过 ref 还原父子关系                    │
         │    → 构建完整调用树                           │
         │    → 计算拓扑关系、延迟指标                    │
         │    → 写入 ElasticSearch                      │
         │                                              │
         └──────────────────────────────────────────────┘
```

### 6.2 Agent 内部数据流

```
  拦截器 beforeMethod()                    拦截器 afterMethod()
         │                                       │
         ▼                                       ▼
  ContextManager                           ContextManager.stopSpan()
         │                                       │
         ▼                                       ▼
  TracingContext                            activeSpanStack.pop()
  (ThreadLocal 绑定)                              │
         │                                       ▼
         ▼                                  栈空？─── 是 ──→ segment.finish()
  createXxxSpan()                                              │
         │                                                     ▼
         ▼                                            ListenerManager.notifyFinish()
  activeSpanStack.push(span)                                   │
                                                               ▼
                                                    TraceSegmentServiceClient
                                                               │
                                                               ▼
                                                    DataCarrier (环形缓冲队列)
                                                               │
                                                               ▼ (后台线程消费)
                                                    gRPC Stream → OAP Server
```

---

## 7. 核心设计亮点与面试要点

### 7.1 为什么引入 Segment 概念？

**问题**：OpenTracing/Zipkin 直接以 Span 为单位上报，每个 Span 独立发送。

**SkyWalking 的优化**：引入 Segment 作为中间层，将同一线程内的所有 Span 打包成一个 Segment 一次性上报。

**优势**：
- 减少网络请求次数（一个 Segment 可能包含 10+ 个 Span）
- 减少序列化/反序列化开销
- Segment 内的 Span 关系天然有序，OAP 无需额外排序

### 7.2 为什么选择 ByteBuddy？

| 对比维度 | ASM | ByteBuddy |
|----------|-----|-----------|
| 抽象层次 | 底层字节码指令操作 | 高层 Java API |
| 开发难度 | 极高，需要理解 JVM 指令 | 低，类似写普通 Java 代码 |
| 插件开发门槛 | 需要字节码专家 | 普通 Java 开发者即可 |
| 性能 | 极致性能 | 接近 ASM，差距可忽略 |
| 生态结果 | 插件少 | SkyWalking 有 100+ 插件 |

ByteBuddy 让插件开发者只需要关心"增强哪个类的哪个方法"和"拦截前后做什么"，无需理解任何字节码细节。

### 7.3 sw8 为什么用 Base64 编码？

- HTTP Header 对特殊字符有严格限制，Base64 确保安全传输
- 控制 Header 总长度在 2KB 以内（很多网关/代理的默认上限）
- 统一编码方式，跨语言 Agent（Java/Go/Python/.NET）都能正确解析

### 7.4 如何保证 Agent 对业务性能影响最小？

1. **DataCarrier 环形缓冲**：Segment 完成后放入队列即返回，不阻塞业务线程
2. **满时丢弃策略**：队列满了直接丢弃 Trace 数据，绝不影响业务
3. **IgnoredTracerContext**：未被采样的请求使用空实现，方法调用几乎零开销
4. **EntrySpan/ExitSpan 复用**：嵌套的同类型 Span 不创建新对象，减少 GC 压力
5. **异步 gRPC 上报**：后台线程批量发送，不占用业务线程 CPU

### 7.5 面试常见问题

**Q: SkyWalking 的 traceId 是如何保证全局唯一的？**

A: 由 `GlobalIdGenerator` 生成，格式为 `{instanceId}.{Thread.currentThread().getId()}.{timestamp}{seq}`。instanceId 在服务启动时由 OAP 分配或 Agent 自行生成 UUID，结合线程 ID 和时间戳序列号，无需分布式协调即可保证全局唯一。

**Q: SkyWalking 如何处理异步 RPC（如 Dubbo 异步调用）？**

A: 通过 `asyncSpanCounter` 机制。当开启异步 Span 时计数器 +1，异步完成时 -1。只有当 activeSpanStack 清空**且** asyncSpanCounter 归零时，Segment 才会真正 finish 并上报。

**Q: SkyWalking 和 Zipkin/Jaeger 的核心区别？**

A: 
- Zipkin/Jaeger 基于 OpenTracing 标准，以 Span 为传输单位
- SkyWalking 引入 Segment 概念，以 Segment 为传输单位，减少网络开销
- SkyWalking 是纯 Java Agent 字节码增强，零侵入；Zipkin 需要在代码中显式埋点
- SkyWalking 的 OAP Server 内置流式聚合分析，Zipkin 的后端相对简单

---

## 附录：核心源码文件索引

| 功能模块 | 源码路径 | 说明 |
|----------|----------|------|
| Agent 入口 | `apm-agent/SkyWalkingAgent.java` | premain 方法，启动总入口 |
| 插件加载 | `apm-agent-core/plugin/PluginBootstrap.java` | SPI 扫描 skywalking-plugin.def |
| 插件定义基类 | `apm-agent-core/plugin/AbstractClassEnhancePluginDefine.java` | 所有插件的父类 |
| 上下文管理 | `apm-agent-core/context/ContextManager.java` | ThreadLocal 门面 |
| 追踪上下文 | `apm-agent-core/context/TracingContext.java` | Span栈、inject/extract |
| Segment 定义 | `apm-agent-core/context/trace/TraceSegment.java` | 数据容器 |
| Span 定义 | `apm-agent-core/context/trace/AbstractTracingSpan.java` | Span 基类 |
| 跨进程载体 | `apm-agent-core/context/ContextCarrier.java` | sw8 序列化/反序列化 |
| 跨线程快照 | `apm-agent-core/context/ContextSnapshot.java` | 内存传递 |
| gRPC 上报 | `apm-agent-core/remote/TraceSegmentServiceClient.java` | Segment 发送 |
| 缓冲队列 | `apm-commons/datacarrier/DataCarrier.java` | 高性能环形缓冲 |

---

> 本文档基于 Apache SkyWalking 9.x 版本源码编写。如需了解更多细节，可参考 [SkyWalking GitHub](https://github.com/apache/skywalking) 和 [官方文档](https://skywalking.apache.org/docs/)。

---

## 8. 从零实现一个链路追踪系统（完整实战指南）

> 本章将带你从零开始，一步步设计并实现一个类 SkyWalking 的分布式链路追踪系统（命名为 Mini-Tracer）。包含完整的架构设计、模块拆分、核心代码实现，以及每一步的设计决策思考。读完你可以真正动手写出一个可运行的链路追踪原型。

### 8.1 总体思路与分步规划

实现一个链路追踪系统，本质上要解决三个核心问题：

1. **数据采集**：如何在不修改业务代码的前提下，自动记录每次方法调用的耗时、参数、异常？
2. **上下文传播**：如何在跨进程（HTTP/RPC）和跨线程场景下，将 traceId 等信息正确传递？
3. **数据存储与展示**：如何高效接收、存储海量 Trace 数据，并支持按 traceId 查询完整调用链？

#### 开发路线图

```
Phase 1: 核心数据模型 + 手动埋点 SDK        （理解原理）
    ↓
Phase 2: Java Agent + ByteBuddy 字节码增强   （实现零侵入）
    ↓
Phase 3: 跨进程 / 跨线程上下文传播           （串联调用链）
    ↓
Phase 4: Collector 后端收集服务              （接收 + 存储 + 查询）
    ↓
Phase 5: UI 可视化展示                      （调用链瀑布图）
    ↓
Phase 6: 高级特性（采样、告警、日志关联）      （生产可用）
```

#### 项目模块划分

```
mini-tracer/
├── mini-tracer-core/          # 核心数据模型和 API（Span、Segment、ID生成器）
├── mini-tracer-agent/         # Java Agent 入口（premain + ByteBuddy）
├── mini-tracer-plugin-api/    # 插件开发接口定义
├── mini-tracer-plugins/       # 各框架插件（Tomcat、HttpClient、MySQL、Dubbo...）
│   ├── tomcat-plugin/
│   ├── httpclient-plugin/
│   ├── mysql-plugin/
│   └── ...
├── mini-tracer-collector/     # 后端收集服务（gRPC接收 + 存储 + REST查询API）
├── mini-tracer-ui/            # 前端可视化（Vue.js 调用链展示）
└── mini-tracer-demo/          # 演示用的微服务应用
```

---

### 8.2 Phase 1：核心数据模型设计

在写任何代码之前，先把数据模型定义清楚。这是整个系统的地基。

#### 8.2.1 Span 数据结构

```java
package com.minitracer.core.model;

/**
 * Span 是链路追踪的最小单元，代表一次操作
 * （方法调用、RPC请求、DB查询、缓存操作等）
 */
public class Span {
    // ===== 身份标识 =====
    private String traceId;          // 全局唯一的 Trace 标识（整条链路共享）
    private String segmentId;        // 所属 Segment 的 ID
    private int spanId;              // 在 Segment 内的序号（从0递增）
    private int parentSpanId;        // 父 Span 的序号（-1 表示根 Span）

    // ===== 业务语义 =====
    private String operationName;    // 操作名称，如 "GET:/api/order" 或 "MySQL/SELECT"
    private SpanType type;           // ENTRY / LOCAL / EXIT
    private SpanLayer layer;         // HTTP / RPC / DB / MQ / CACHE

    // ===== 时间信息 =====
    private long startTime;          // 开始时间戳（毫秒）
    private long endTime;            // 结束时间戳
    
    // ===== 状态信息 =====
    private boolean isError;         // 是否发生错误
    private String peer;             // 对端地址（ExitSpan 专用，如 "mysql:3306"）
    private Map<String, String> tags;    // 附加标签 K-V
    private List<LogEntry> logs;         // 事件日志（如异常堆栈）

    // ===== 运行时 =====
    private int stackDepth;          // 栈深度（用于 Span 复用判断）
    
    // getter/setter 省略...
    
    public void finish() {
        this.endTime = System.currentTimeMillis();
    }
    
    public long getDuration() {
        return endTime - startTime;
    }
}

/** Span 类型枚举 */
public enum SpanType {
    ENTRY,   // 服务入口（接收请求）
    LOCAL,   // 本地方法调用
    EXIT     // 服务出口（发出请求）
}

/** Span 层次枚举 */
public enum SpanLayer {
    HTTP, RPC, DB, MQ, CACHE, UNKNOWN
}

/** 日志条目（用于记录异常等事件） */
public class LogEntry {
    private long timestamp;
    private Map<String, String> data;  // 如 {"event": "error", "message": "NPE", "stack": "..."}
}
```

#### 8.2.2 TraceSegment 数据结构

```java
package com.minitracer.core.model;

/**
 * TraceSegment = 单个线程内的一段完整调用
 * 一条 Trace 由分布在不同服务、不同线程上的多个 Segment 组成
 */
public class TraceSegment {
    private String traceId;                  // 全局 Trace ID
    private String segmentId;                // 本 Segment 的唯一 ID
    
    private String serviceName;              // 服务名
    private String serviceInstance;           // 实例标识（通常是 IP:Port 或 UUID）
    
    private List<Span> spans;                // 该 Segment 内的所有已完成 Span
    private List<TraceSegmentRef> refs;       // 父 Segment 引用列表
    
    private long createTime;
    private boolean isSizeLimited;            // 是否触发了 Span 数量限制

    public TraceSegment(String serviceName, String serviceInstance) {
        this.segmentId = IdGenerator.generateSegmentId();
        this.traceId = IdGenerator.generateTraceId();  // 初始分配，extract 时会被上游覆盖
        this.serviceName = serviceName;
        this.serviceInstance = serviceInstance;
        this.spans = new ArrayList<>();
        this.refs = new ArrayList<>();
        this.createTime = System.currentTimeMillis();
    }

    /** 将已完成的 Span 归档到 Segment */
    public void archive(Span span) {
        spans.add(span);
    }

    /** 添加父 Segment 引用（跨进程/跨线程时调用） */
    public void ref(TraceSegmentRef ref) {
        // 如果尚未建立引用，则添加（并用上游 traceId 覆盖本地 traceId）
        if (!refs.contains(ref)) {
            refs.add(ref);
        }
    }

    /** 将 traceId 设置为上游传来的值（保证整条链路共享） */
    public void relatedGlobalTraceId(String traceId) {
        this.traceId = traceId;
    }
}
```

#### 8.2.3 TraceSegmentRef（父引用）

```java
package com.minitracer.core.model;

/**
 * 父 Segment 的引用信息
 * Collector 端通过这个信息还原完整的调用树
 */
public class TraceSegmentRef {
    private String parentTraceId;            // 上游的 traceId
    private String parentSegmentId;          // 上游的 segmentId
    private int parentSpanId;                // 上游的 spanId
    private String parentService;            // 上游服务名
    private String parentServiceInstance;    // 上游实例名
    private String parentEndpoint;           // 上游入口端点名
    private String targetAddress;            // 本次调用的目标地址

    /** 从 ContextCarrier 构造（跨进程） */
    public TraceSegmentRef(ContextCarrier carrier) {
        this.parentTraceId = carrier.getTraceId();
        this.parentSegmentId = carrier.getSegmentId();
        this.parentSpanId = carrier.getSpanId();
        this.parentService = carrier.getParentService();
        this.parentServiceInstance = carrier.getParentServiceInstance();
        this.parentEndpoint = carrier.getParentEndpoint();
        this.targetAddress = carrier.getTargetAddress();
    }

    /** 从 ContextSnapshot 构造（跨线程） */
    public TraceSegmentRef(ContextSnapshot snapshot) {
        this.parentTraceId = snapshot.getTraceId();
        this.parentSegmentId = snapshot.getSegmentId();
        this.parentSpanId = snapshot.getSpanId();
        this.parentEndpoint = snapshot.getParentEndpoint();
        // 跨线程不需要 service/instance（同一个服务内）
    }
}
```

#### 8.2.4 ID 生成器

```java
package com.minitracer.core.util;

/**
 * 全局唯一 ID 生成器
 * 
 * 设计原则：
 * 1. 无需分布式协调（不依赖任何外部服务）
 * 2. 单调递增（便于索引和排序）
 * 3. 可从 ID 反推出产生时间和来源（便于调试）
 * 
 * 格式：{processId}.{threadId}.{timestamp}.{sequence}
 */
public class IdGenerator {

    private static final String PROCESS_UUID = UUID.randomUUID().toString().replace("-", "");
    private static final ThreadLocal<long[]> THREAD_ID_SEQUENCE = ThreadLocal.withInitial(() -> {
        // [0] = 上次时间戳, [1] = 序列号
        return new long[]{0L, 0L};
    });

    public static String generateTraceId() {
        return generate();
    }

    public static String generateSegmentId() {
        return generate();
    }

    /**
     * 核心生成逻辑
     * 由 进程标识 + 线程ID + 时间戳 + 自增序列 组成
     * 同一毫秒内通过序列号保证唯一
     */
    private static String generate() {
        long[] holder = THREAD_ID_SEQUENCE.get();
        long currentMillis = System.currentTimeMillis();
        
        if (currentMillis == holder[0]) {
            // 同一毫秒内，序列号递增
            holder[1]++;
        } else {
            // 新的毫秒，序列号重置
            holder[0] = currentMillis;
            holder[1] = 0;
        }
        
        return PROCESS_UUID.substring(0, 8)       // 进程标识（8位）
            + "." + Thread.currentThread().getId() // 线程 ID
            + "." + currentMillis                  // 时间戳
            + "." + holder[1];                     // 序列号
    }
}
```

**设计决策解读**：为什么不用 UUID？UUID 是纯随机的，无法从 ID 中反推出任何有用信息。自定义格式便于调试（一眼看出 ID 是哪个进程、哪个线程、什么时间产生的），同时使用 ThreadLocal 避免了锁竞争。

---

### 8.3 Phase 2：手动埋点 SDK（理解原理的基础）

在实现自动字节码增强之前，先做一个手动 SDK，确保核心逻辑跑通。

#### 8.3.1 ContextCarrier（跨进程传播载体）

```java
package com.minitracer.core.context;

import java.util.Base64;

/**
 * 跨进程传播载体
 * 负责将当前 Context 信息序列化/反序列化
 * 
 * 序列化格式（类似 sw8）：
 * {sample}-{traceId}-{segmentId}-{spanId}-{service}-{instance}-{endpoint}-{target}
 * 各字段使用 Base64 编码，字段间用 "-" 分隔
 */
public class ContextCarrier {
    private static final String HEADER_NAME = "mini-trace";
    
    private String traceId;
    private String segmentId;
    private int spanId;
    private String parentService;
    private String parentServiceInstance;
    private String parentEndpoint;
    private String targetAddress;
    private boolean sampled = true;

    // ===== 序列化：Context → 字符串（放入 HTTP Header） =====
    public String serializeTo() {
        return (sampled ? "1" : "0")
            + "-" + base64Encode(traceId)
            + "-" + base64Encode(segmentId)
            + "-" + spanId
            + "-" + base64Encode(parentService)
            + "-" + base64Encode(parentServiceInstance)
            + "-" + base64Encode(parentEndpoint)
            + "-" + base64Encode(targetAddress);
    }

    // ===== 反序列化：字符串 → Context（从 HTTP Header 读取） =====
    public void deserializeFrom(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) return;
        
        String[] parts = headerValue.split("-", 8);
        if (parts.length != 8) return;
        
        this.sampled = "1".equals(parts[0]);
        this.traceId = base64Decode(parts[1]);
        this.segmentId = base64Decode(parts[2]);
        this.spanId = Integer.parseInt(parts[3]);
        this.parentService = base64Decode(parts[4]);
        this.parentServiceInstance = base64Decode(parts[5]);
        this.parentEndpoint = base64Decode(parts[6]);
        this.targetAddress = base64Decode(parts[7]);
    }

    public boolean isValid() {
        return traceId != null && !traceId.isEmpty();
    }

    public static String getHeaderName() {
        return HEADER_NAME;
    }

    private static String base64Encode(String input) {
        if (input == null) return "";
        return Base64.getEncoder().encodeToString(input.getBytes());
    }

    private static String base64Decode(String input) {
        if (input == null || input.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(input));
    }

    // getter/setter 省略...
}
```

#### 8.3.2 ContextSnapshot（跨线程传播载体）

```java
package com.minitracer.core.context;

/**
 * 跨线程传播载体
 * 与 ContextCarrier 的区别：不需要序列化（同一 JVM 内存传递）
 */
public class ContextSnapshot {
    private String traceId;
    private String segmentId;
    private int spanId;
    private String parentEndpoint;
    private Map<String, String> correlationData;  // 自定义传播数据

    public ContextSnapshot(String traceId, String segmentId, int spanId, 
                           String parentEndpoint, Map<String, String> correlationData) {
        this.traceId = traceId;
        this.segmentId = segmentId;
        this.spanId = spanId;
        this.parentEndpoint = parentEndpoint;
        this.correlationData = correlationData;
    }

    public boolean isValid() {
        return traceId != null && segmentId != null;
    }
    
    // getter 省略...
}
```

#### 8.3.3 TracingContext（线程级上下文 —— 核心中的核心）

```java
package com.minitracer.core.context;

/**
 * 每个线程独立的追踪上下文
 * 管理当前线程内的 Span 栈和 Segment
 * 
 * 核心设计：使用栈（Deque）管理 Span 的嵌套关系
 * - beforeMethod() → push
 * - afterMethod()  → pop
 * - 栈空时 → Segment 完成，触发上报
 */
public class TracingContext {

    private final TraceSegment segment;              // 当前线程的 Segment
    private final Deque<Span> activeSpanStack;       // Span 栈
    private int spanIdGenerator;                     // Span ID 自增器

    public TracingContext(String serviceName, String instance) {
        this.segment = new TraceSegment(serviceName, instance);
        this.activeSpanStack = new ArrayDeque<>();
        this.spanIdGenerator = 0;
    }

    // ==================== 创建 Span ====================

    public Span createEntrySpan(String operationName) {
        // 优化：如果栈顶已经是 EntrySpan，复用它（避免 Filter→Servlet 嵌套创建多个）
        if (!activeSpanStack.isEmpty() && peek().getType() == SpanType.ENTRY) {
            Span existingSpan = peek();
            existingSpan.setOperationName(operationName);  // 更新为更精确的名称
            existingSpan.setStackDepth(existingSpan.getStackDepth() + 1);
            return existingSpan;
        }
        
        Span span = createSpan(operationName, SpanType.ENTRY);
        activeSpanStack.push(span);
        return span;
    }

    public Span createExitSpan(String operationName, String peer) {
        // 优化：如果栈顶已经是 ExitSpan，复用它（避免嵌套的出站调用创建多个）
        if (!activeSpanStack.isEmpty() && peek().getType() == SpanType.EXIT) {
            Span existingSpan = peek();
            existingSpan.setStackDepth(existingSpan.getStackDepth() + 1);
            return existingSpan;
        }
        
        Span span = createSpan(operationName, SpanType.EXIT);
        span.setPeer(peer);
        activeSpanStack.push(span);
        return span;
    }

    public Span createLocalSpan(String operationName) {
        Span span = createSpan(operationName, SpanType.LOCAL);
        activeSpanStack.push(span);
        return span;
    }

    private Span createSpan(String operationName, SpanType type) {
        int parentSpanId = activeSpanStack.isEmpty() ? -1 : peek().getSpanId();
        
        Span span = new Span();
        span.setTraceId(segment.getTraceId());
        span.setSegmentId(segment.getSegmentId());
        span.setSpanId(spanIdGenerator++);
        span.setParentSpanId(parentSpanId);
        span.setOperationName(operationName);
        span.setType(type);
        span.setStartTime(System.currentTimeMillis());
        span.setTags(new HashMap<>());
        span.setLogs(new ArrayList<>());
        span.setStackDepth(0);
        return span;
    }

    // ==================== 结束 Span ====================

    public boolean stopSpan() {
        Span span = peek();
        
        // 如果 stackDepth > 0，说明是复用的 Span，只减少深度不弹栈
        if (span.getStackDepth() > 0) {
            span.setStackDepth(span.getStackDepth() - 1);
            return false;
        }
        
        // 真正弹栈
        activeSpanStack.pop();
        span.finish();  // 记录结束时间
        segment.archive(span);  // 归档到 Segment
        
        // 如果栈空了 → 整个 Segment 完成
        if (activeSpanStack.isEmpty()) {
            finish();
            return true;
        }
        return false;
    }

    // ==================== 跨进程传播 ====================

    /** 注入：将当前上下文写入 ContextCarrier（调用方使用） */
    public void inject(ContextCarrier carrier) {
        Span currentSpan = peek();
        carrier.setTraceId(segment.getTraceId());
        carrier.setSegmentId(segment.getSegmentId());
        carrier.setSpanId(currentSpan.getSpanId());
        carrier.setParentService(segment.getServiceName());
        carrier.setParentServiceInstance(segment.getServiceInstance());
        carrier.setParentEndpoint(getEntryOperationName());
        carrier.setTargetAddress(currentSpan.getPeer());
    }

    /** 提取：从 ContextCarrier 恢复上游上下文（被调用方使用） */
    public void extract(ContextCarrier carrier) {
        if (!carrier.isValid()) return;
        
        // ★ 关键操作一：用上游的 traceId 覆盖本地 traceId ★
        segment.relatedGlobalTraceId(carrier.getTraceId());
        
        // ★ 关键操作二：建立父子引用关系 ★
        TraceSegmentRef ref = new TraceSegmentRef(carrier);
        segment.ref(ref);
    }

    // ==================== 跨线程传播 ====================

    /** 捕获快照（主线程调用） */
    public ContextSnapshot capture() {
        return new ContextSnapshot(
            segment.getTraceId(),
            segment.getSegmentId(),
            peek().getSpanId(),
            getEntryOperationName(),
            new HashMap<>()  // correlationData
        );
    }

    /** 恢复快照（子线程调用） */
    public void continued(ContextSnapshot snapshot) {
        if (snapshot == null || !snapshot.isValid()) return;
        
        // 与 extract 逻辑完全一致：覆盖 traceId + 建立引用
        segment.relatedGlobalTraceId(snapshot.getTraceId());
        TraceSegmentRef ref = new TraceSegmentRef(snapshot);
        segment.ref(ref);
    }

    // ==================== 内部方法 ====================

    private void finish() {
        // 通知上报器：Segment 已完成
        DataReporter.getInstance().report(segment);
    }

    private Span peek() {
        return activeSpanStack.peek();
    }

    public Span activeSpan() {
        return peek();
    }

    private String getEntryOperationName() {
        // 返回栈底的 EntrySpan 的 operationName
        if (activeSpanStack instanceof ArrayDeque) {
            Iterator<Span> it = ((ArrayDeque<Span>) activeSpanStack).descendingIterator();
            if (it.hasNext()) return it.next().getOperationName();
        }
        return "";
    }
}
```

#### 8.3.4 ContextManager（全局静态门面）

```java
package com.minitracer.core.context;

/**
 * 全局静态门面类 —— 所有 Trace 操作的唯一入口
 * 
 * 设计决策：
 * 1. 全静态方法 → 任何地方都可以直接调用
 * 2. ThreadLocal 隔离 → 线程安全
 * 3. 懒创建 → 第一次 createSpan 时才创建 Context
 */
public class ContextManager {

    private static final ThreadLocal<TracingContext> CONTEXT = new ThreadLocal<>();
    
    private static String serviceName = "unknown";
    private static String serviceInstance = "unknown";

    /** 初始化（Agent 启动时调用一次） */
    public static void init(String service, String instance) {
        serviceName = service;
        serviceInstance = instance;
    }

    // ==================== 创建 Span API ====================

    public static Span createEntrySpan(String operationName, ContextCarrier carrier) {
        TracingContext ctx = getOrCreate();
        Span span = ctx.createEntrySpan(operationName);
        if (carrier != null && carrier.isValid()) {
            ctx.extract(carrier);  // 从上游恢复上下文
        }
        return span;
    }

    public static Span createExitSpan(String operationName, ContextCarrier carrier, String peer) {
        TracingContext ctx = getOrCreate();
        Span span = ctx.createExitSpan(operationName, peer);
        ctx.inject(carrier);  // 注入上下文到载体
        return span;
    }

    public static Span createLocalSpan(String operationName) {
        return getOrCreate().createLocalSpan(operationName);
    }

    // ==================== 结束 Span ====================

    public static void stopSpan() {
        TracingContext ctx = CONTEXT.get();
        if (ctx != null) {
            boolean finished = ctx.stopSpan();
            if (finished) {
                CONTEXT.remove();  // Segment 完成，清理 ThreadLocal
            }
        }
    }

    // ==================== 跨线程支持 ====================

    public static ContextSnapshot capture() {
        TracingContext ctx = CONTEXT.get();
        return ctx != null ? ctx.capture() : null;
    }

    public static void continued(ContextSnapshot snapshot) {
        if (snapshot != null && snapshot.isValid()) {
            getOrCreate().continued(snapshot);
        }
    }

    // ==================== 辅助方法 ====================

    public static Span activeSpan() {
        TracingContext ctx = CONTEXT.get();
        return ctx != null ? ctx.activeSpan() : null;
    }

    public static boolean isActive() {
        return CONTEXT.get() != null;
    }

    private static TracingContext getOrCreate() {
        TracingContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = new TracingContext(serviceName, serviceInstance);
            CONTEXT.set(ctx);
        }
        return ctx;
    }
}
```

#### 8.3.5 手动埋点使用示例

```java
// 使用 Mini-Tracer SDK 手动埋点
public class OrderController {

    public Order createOrder(HttpServletRequest request) {
        // ===== 1. 入口：提取上游上下文 =====
        ContextCarrier carrier = new ContextCarrier();
        carrier.deserializeFrom(request.getHeader(ContextCarrier.getHeaderName()));
        Span entrySpan = ContextManager.createEntrySpan("POST:/api/order", carrier);
        entrySpan.tag("http.method", "POST");

        try {
            // ===== 2. 本地逻辑 =====
            Span localSpan = ContextManager.createLocalSpan("OrderService.validate");
            validateOrder(req);  // 业务校验
            ContextManager.stopSpan();  // 结束 localSpan

            // ===== 3. 调用下游（跨进程传播的关键！） =====
            ContextCarrier exitCarrier = new ContextCarrier();
            Span exitSpan = ContextManager.createExitSpan(
                "GET:/api/inventory", exitCarrier, "inventory-service:8080");
            
            // ★ 将 carrier 序列化放入 HTTP Header ★
            HttpRequest httpReq = HttpRequest.newBuilder()
                .header(ContextCarrier.getHeaderName(), exitCarrier.serializeTo())
                .uri(URI.create("http://inventory-service:8080/api/inventory"))
                .build();
            httpClient.send(httpReq, ...);
            
            ContextManager.stopSpan();  // 结束 exitSpan
            return order;
            
        } catch (Exception e) {
            ContextManager.activeSpan().setError(true);
            throw e;
        } finally {
            ContextManager.stopSpan();  // 结束 entrySpan → Segment 完成 → 自动上报
        }
    }
}
```

**到这里**，你已经有了一个能手动使用的链路追踪 SDK。但手动埋点的问题是：每个方法都要写 try-finally、每个 HTTP 调用都要手动注入 Header——太繁琐了。接下来实现自动化。

---

### 8.4 Phase 3：Java Agent 自动字节码增强（核心难点）

这是实现"零侵入"的关键技术。通过 Java Agent 的 premain 机制 + ByteBuddy 字节码增强，在目标类加载时自动改写字节码，注入我们的追踪逻辑。

#### 8.4.1 理解 Java Agent 机制

```
JVM 启动流程：
                                                       
  java -javaagent:agent.jar -jar app.jar
       │
       ▼
  JVM 初始化
       │
       ▼
  加载 agent.jar 的 MANIFEST.MF
  读取 Premain-Class 属性
       │
       ▼
  调用 premain(String args, Instrumentation inst)   ← 我们的代码在这里执行
       │                                               可以通过 inst 注册 ClassFileTransformer
       ▼                                               在类加载时修改字节码
  调用应用的 main() 方法
       │
       ▼
  应用正常运行（被增强过的类已经包含了追踪逻辑）
```

#### 8.4.2 Agent 入口实现

```java
package com.minitracer.agent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;
import java.lang.instrument.Instrumentation;

/**
 * Mini-Tracer Agent 入口
 * 
 * 需要在 pom.xml 中配置 maven-jar-plugin 生成正确的 MANIFEST.MF：
 *   Premain-Class: com.minitracer.agent.MiniTracerAgent
 *   Can-Redefine-Classes: true
 *   Can-Retransform-Classes: true
 */
public class MiniTracerAgent {

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("[Mini-Tracer] Agent initializing...");

        // 1. 解析配置
        AgentConfig config = AgentConfig.parse(agentArgs);
        ContextManager.init(config.getServiceName(), config.getServiceInstance());
        
        // 2. 加载所有插件（SPI 机制）
        List<PluginDefine> plugins = PluginLoader.loadAll();
        System.out.println("[Mini-Tracer] Loaded " + plugins.size() + " plugins");

        // 3. 构建 ByteBuddy AgentBuilder
        AgentBuilder agentBuilder = new AgentBuilder.Default()
            // 忽略 JDK 内部类和 Agent 自身类
            .ignore(ElementMatchers.nameStartsWith("com.minitracer."))
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))
            .ignore(ElementMatchers.nameStartsWith("sun."))
            .ignore(ElementMatchers.nameStartsWith("jdk."));

        // 4. 为每个插件注册增强规则
        for (PluginDefine plugin : plugins) {
            agentBuilder = agentBuilder
                .type(plugin.enhanceClass())  // 匹配目标类
                .transform((builder, type, classLoader, module, domain) ->
                    builder.method(plugin.enhanceMethod())  // 匹配目标方法
                           .intercept(MethodDelegation.to(
                               new InterceptorDelegate(plugin.createInterceptor())))
                );
        }

        // 5. 安装到 JVM（从此刻起，匹配的类在加载时会被自动增强）
        agentBuilder.installOn(instrumentation);
        
        // 6. 启动后台数据上报线程
        DataReporter.getInstance().start(config.getCollectorAddress());
        
        System.out.println("[Mini-Tracer] Agent started! Reporting to: " + config.getCollectorAddress());
    }
}
```

#### 8.4.3 插件接口设计

```java
package com.minitracer.plugin.api;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * 插件定义接口
 * 每个框架适配一个插件，插件需要回答三个问题：
 * 1. 增强哪个类？
 * 2. 增强哪个方法？
 * 3. 怎么增强（拦截器是什么）？
 */
public interface PluginDefine {
    /** 要增强的类（ByteBuddy 类型匹配器） */
    ElementMatcher<? super TypeDescription> enhanceClass();
    
    /** 要增强的方法（ByteBuddy 方法匹配器） */
    ElementMatcher<? super MethodDescription> enhanceMethod();
    
    /** 创建拦截器实例 */
    MethodInterceptor createInterceptor();
}

/**
 * 方法拦截器接口（插件开发者只需实现这个接口）
 */
public interface MethodInterceptor {
    /** 方法执行前（创建 Span） */
    void beforeMethod(Object instance, Method method, Object[] args) throws Throwable;
    
    /** 方法正常返回后（结束 Span） */
    void afterMethod(Object instance, Method method, Object[] args, Object result) throws Throwable;
    
    /** 方法抛出异常时（标记错误） */
    void handleException(Object instance, Method method, Object[] args, Throwable t);
}
```

#### 8.4.4 ByteBuddy 拦截委托器

```java
package com.minitracer.agent;

import net.bytebuddy.implementation.bind.annotation.*;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;

/**
 * 将 ByteBuddy 的方法委托转换为我们的 MethodInterceptor 接口调用
 * 
 * ByteBuddy 会在目标方法被调用时，将执行流转到这个类的 intercept 方法
 */
public class InterceptorDelegate {
    
    private final MethodInterceptor interceptor;
    
    public InterceptorDelegate(MethodInterceptor interceptor) {
        this.interceptor = interceptor;
    }
    
    /**
     * 被 ByteBuddy 调用的入口方法
     * 
     * 注解说明：
     * @This          → 目标类的 this 实例
     * @Origin        → 被拦截的原始方法
     * @AllArguments  → 方法的所有参数
     * @SuperCall     → 对原始方法的调用（相当于 super.method()）
     * @RuntimeType   → 允许运行时类型转换
     */
    @RuntimeType
    public Object intercept(
            @This Object instance,
            @Origin Method method,
            @AllArguments Object[] args,
            @SuperCall Callable<?> superCall) throws Throwable {
        
        // === 前置增强：创建 Span ===
        try {
            interceptor.beforeMethod(instance, method, args);
        } catch (Throwable t) {
            // 增强逻辑出错不应影响业务，打日志后继续
            System.err.println("[Mini-Tracer] beforeMethod error: " + t.getMessage());
        }
        
        Object result = null;
        try {
            // === 执行原始方法 ===
            result = superCall.call();
            
            // === 后置增强：结束 Span ===
            try {
                interceptor.afterMethod(instance, method, args, result);
            } catch (Throwable t) {
                System.err.println("[Mini-Tracer] afterMethod error: " + t.getMessage());
            }
            
        } catch (Throwable bizException) {
            // === 异常处理：标记错误 ===
            try {
                interceptor.handleException(instance, method, args, bizException);
            } catch (Throwable t) {
                System.err.println("[Mini-Tracer] handleException error: " + t.getMessage());
            }
            throw bizException;  // 一定要重新抛出，不能吞掉业务异常！
        }
        
        return result;
    }
}
```

#### 8.4.5 插件加载器（SPI 机制）

```java
package com.minitracer.agent;

/**
 * 插件加载器
 * 使用 Java SPI 机制动态发现和加载所有插件
 * 
 * 每个插件 jar 包中需要有文件：
 * META-INF/services/com.minitracer.plugin.api.PluginDefine
 * 内容为插件实现类的全限定名
 */
public class PluginLoader {
    
    public static List<PluginDefine> loadAll() {
        List<PluginDefine> plugins = new ArrayList<>();
        
        // 使用 SPI 加载所有 PluginDefine 实现
        ServiceLoader<PluginDefine> serviceLoader = ServiceLoader.load(
            PluginDefine.class, 
            MiniTracerAgent.class.getClassLoader()
        );
        
        for (PluginDefine plugin : serviceLoader) {
            plugins.add(plugin);
            System.out.println("[Mini-Tracer] Found plugin: " + plugin.getClass().getSimpleName());
        }
        
        return plugins;
    }
}
```

#### 8.4.6 实现 Tomcat 插件（EntrySpan，完整示例）

```java
package com.minitracer.plugins.tomcat;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Tomcat 插件 —— 拦截 HTTP 请求入口
 * 在每个 HTTP 请求进入时创建 EntrySpan，请求结束时关闭
 */
public class TomcatPlugin implements PluginDefine {

    @Override
    public ElementMatcher<? super TypeDescription> enhanceClass() {
        // 增强 Tomcat 的核心请求处理类
        return named("org.apache.catalina.core.StandardHostValve");
    }

    @Override
    public ElementMatcher<? super MethodDescription> enhanceMethod() {
        // 增强 invoke(Request, Response) 方法
        return named("invoke").and(takesArguments(2));
    }

    @Override
    public MethodInterceptor createInterceptor() {
        return new TomcatInterceptor();
    }
}

/**
 * Tomcat 拦截器实现
 */
public class TomcatInterceptor implements MethodInterceptor {

    @Override
    public void beforeMethod(Object instance, Method method, Object[] args) {
        // args[0] = org.apache.catalina.connector.Request (实现了 HttpServletRequest)
        HttpServletRequest request = (HttpServletRequest) args[0];
        
        // 第一步：从 HTTP Header 提取上游传来的 Context
        ContextCarrier carrier = new ContextCarrier();
        carrier.deserializeFrom(request.getHeader(ContextCarrier.getHeaderName()));
        
        // 第二步：创建 EntrySpan（如果 carrier 有效，会自动 extract 建立父子关系）
        String operationName = request.getMethod() + ":" + request.getRequestURI();
        Span span = ContextManager.createEntrySpan(operationName, carrier);
        
        // 第三步：设置 Span 属性
        span.setLayer(SpanLayer.HTTP);
        span.tag("http.method", request.getMethod());
        span.tag("http.url", request.getRequestURL().toString());
        span.tag("http.params", request.getQueryString());
    }

    @Override
    public void afterMethod(Object instance, Method method, Object[] args, Object result) {
        HttpServletResponse response = (HttpServletResponse) args[1];
        Span span = ContextManager.activeSpan();
        
        if (span != null) {
            span.tag("http.status_code", String.valueOf(response.getStatus()));
            if (response.getStatus() >= 400) {
                span.setError(true);
            }
        }
        
        ContextManager.stopSpan();  // EntrySpan 结束 → 可能触发 Segment 完成
    }

    @Override
    public void handleException(Object instance, Method method, Object[] args, Throwable t) {
        Span span = ContextManager.activeSpan();
        if (span != null) {
            span.setError(true);
            span.log("error", t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
```

#### 8.4.7 实现 HttpClient 插件（ExitSpan + 跨进程注入）

```java
package com.minitracer.plugins.httpclient;

/**
 * HttpClient 插件 —— 拦截出站 HTTP 请求
 * 在发出请求前创建 ExitSpan 并将 Context 注入 HTTP Header
 */
public class HttpClientPlugin implements PluginDefine {

    @Override
    public ElementMatcher<? super TypeDescription> enhanceClass() {
        return named("org.apache.http.impl.client.InternalHttpClient");
    }

    @Override
    public ElementMatcher<? super MethodDescription> enhanceMethod() {
        return named("doExecute");
    }

    @Override
    public MethodInterceptor createInterceptor() {
        return new HttpClientInterceptor();
    }
}

public class HttpClientInterceptor implements MethodInterceptor {

    @Override
    public void beforeMethod(Object instance, Method method, Object[] args) {
        // args[0] = HttpHost, args[1] = HttpRequest
        HttpHost httpHost = (HttpHost) args[0];
        HttpRequest httpRequest = (HttpRequest) args[1];
        
        String peer = httpHost.getHostName() + ":" + httpHost.getPort();
        URI uri = ((HttpUriRequest) httpRequest).getURI();
        String operationName = httpRequest.getRequestLine().getMethod() + ":" + uri.getPath();
        
        // 第一步：创建 ExitSpan + 注入 Context 到 carrier
        ContextCarrier carrier = new ContextCarrier();
        Span span = ContextManager.createExitSpan(operationName, carrier, peer);
        span.setLayer(SpanLayer.HTTP);
        span.tag("http.method", httpRequest.getRequestLine().getMethod());
        span.tag("http.url", uri.toString());
        
        // ★★★ 第二步：将 carrier 序列化后注入 HTTP Header ★★★
        // 这是跨进程传播的关键一步！
        httpRequest.setHeader(ContextCarrier.getHeaderName(), carrier.serializeTo());
    }

    @Override
    public void afterMethod(Object instance, Method method, Object[] args, Object result) {
        // 记录响应状态码
        if (result instanceof HttpResponse) {
            int statusCode = ((HttpResponse) result).getStatusLine().getStatusCode();
            Span span = ContextManager.activeSpan();
            if (span != null) {
                span.tag("http.status_code", String.valueOf(statusCode));
                if (statusCode >= 400) span.setError(true);
            }
        }
        ContextManager.stopSpan();
    }

    @Override
    public void handleException(Object instance, Method method, Object[] args, Throwable t) {
        Span span = ContextManager.activeSpan();
        if (span != null) {
            span.setError(true);
            span.log("error", t.getMessage());
        }
        ContextManager.stopSpan();
    }
}
```

#### 8.4.8 实现 MySQL/JDBC 插件（数据库追踪）

```java
package com.minitracer.plugins.mysql;

/**
 * MySQL JDBC 插件 —— 追踪所有数据库操作
 */
public class MySQLPlugin implements PluginDefine {

    @Override
    public ElementMatcher<? super TypeDescription> enhanceClass() {
        // 拦截 PreparedStatement 的实现类
        return named("com.mysql.cj.jdbc.ClientPreparedStatement")
            .or(named("com.mysql.jdbc.PreparedStatement"));
    }

    @Override
    public ElementMatcher<? super MethodDescription> enhanceMethod() {
        // 拦截 execute/executeQuery/executeUpdate 方法
        return named("execute").or(named("executeQuery")).or(named("executeUpdate"));
    }

    @Override
    public MethodInterceptor createInterceptor() {
        return new MySQLInterceptor();
    }
}

public class MySQLInterceptor implements MethodInterceptor {

    @Override
    public void beforeMethod(Object instance, Method method, Object[] args) {
        // 通过反射获取连接信息和 SQL 语句
        String sql = extractSQL(instance);
        String dbUrl = extractDBUrl(instance);
        String peer = extractPeer(dbUrl);  // 如 "mysql-host:3306"
        
        ContextCarrier carrier = new ContextCarrier(); // JDBC 不需要跨进程传播
        Span span = ContextManager.createExitSpan("MySQL/EXECUTE", carrier, peer);
        span.setLayer(SpanLayer.DB);
        span.tag("db.type", "MySQL");
        span.tag("db.statement", sql.length() > 256 ? sql.substring(0, 256) : sql);
        span.tag("db.instance", extractDBName(dbUrl));
    }

    @Override
    public void afterMethod(Object instance, Method method, Object[] args, Object result) {
        ContextManager.stopSpan();
    }

    @Override
    public void handleException(Object instance, Method method, Object[] args, Throwable t) {
        Span span = ContextManager.activeSpan();
        if (span != null) {
            span.setError(true);
            span.tag("db.error", t.getMessage());
        }
        ContextManager.stopSpan();
    }
    
    // 辅助方法：通过反射提取 SQL（省略具体实现）
    private String extractSQL(Object statement) { /* ... */ }
    private String extractDBUrl(Object statement) { /* ... */ }
    private String extractPeer(String url) { /* ... */ }
    private String extractDBName(String url) { /* ... */ }
}
```

---

### 8.5 Phase 4：Collector 后端收集服务

Agent 收集的数据需要一个后端来接收、存储和查询。

#### 8.5.1 整体设计

```
┌─────────────────────────────────────────────────────────────┐
│                   Mini-Tracer Collector                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌────────────────┐                                         │
│  │  gRPC Receiver  │ ← 接收 Agent 上报的 Segment 数据        │
│  │  (port: 11800) │                                         │
│  └───────┬────────┘                                         │
│          │                                                   │
│          ▼                                                   │
│  ┌────────────────┐                                         │
│  │  Segment Queue  │ ← 内存队列，缓冲突发流量                 │
│  │  (Disruptor)    │                                         │
│  └───────┬────────┘                                         │
│          │                                                   │
│          ▼                                                   │
│  ┌────────────────┐    ┌─────────────────┐                  │
│  │  Trace Builder  │ ──►│  Storage Writer  │                  │
│  │  (组装调用树)    │    │  (写入存储)       │                  │
│  └────────────────┘    └────────┬────────┘                  │
│                                 │                            │
│                                 ▼                            │
│  ┌────────────────────────────────────────────┐             │
│  │  Storage Layer                              │             │
│  │  ├── ElasticSearch（生产推荐）               │             │
│  │  ├── MySQL（开发调试）                       │             │
│  │  └── H2 InMemory（快速演示）                 │             │
│  └────────────────────────────────────────────┘             │
│                                 ▲                            │
│                                 │                            │
│  ┌────────────────┐             │                            │
│  │  REST API       │ ───────────┘                            │
│  │  (port: 12800) │ ← 提供 Trace 查询接口                    │
│  └────────────────┘                                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

#### 8.5.2 数据上报器（Agent 端）

```java
package com.minitracer.core.reporter;

/**
 * 数据上报器 —— 负责将 Agent 采集的 Segment 发送到 Collector
 * 
 * 设计要点：
 * 1. 异步上报：不阻塞业务线程
 * 2. 批量发送：减少网络开销
 * 3. 满时丢弃：保护业务性能
 * 4. 优雅降级：Collector 不可用时不影响业务
 */
public class DataReporter {
    
    private static final DataReporter INSTANCE = new DataReporter();
    
    private BlockingQueue<TraceSegment> buffer;  // 缓冲队列
    private Thread reporterThread;               // 后台上报线程
    private String collectorAddress;
    private volatile boolean running = false;
    
    private static final int BUFFER_SIZE = 1024;     // 队列容量
    private static final int BATCH_SIZE = 50;        // 每批发送数量
    private static final int FLUSH_INTERVAL_MS = 1000; // 定时刷新间隔
    
    public static DataReporter getInstance() { return INSTANCE; }
    
    public void start(String address) {
        this.collectorAddress = address;
        this.buffer = new ArrayBlockingQueue<>(BUFFER_SIZE);
        this.running = true;
        
        // 启动后台上报线程
        this.reporterThread = new Thread(this::reportLoop, "MiniTracer-Reporter");
        this.reporterThread.setDaemon(true);  // 守护线程，JVM退出时自动结束
        this.reporterThread.start();
    }
    
    /**
     * 接收一个完成的 Segment（被 TracingContext.finish() 调用）
     * 
     * 注意：这个方法在业务线程中执行，必须非阻塞！
     */
    public void report(TraceSegment segment) {
        // offer 是非阻塞的：队列满了直接返回 false（丢弃数据）
        boolean success = buffer.offer(segment);
        if (!success) {
            // 队列满了，丢弃这条 Trace（保护业务性能 > 数据完整性）
            System.err.println("[Mini-Tracer] Buffer full, segment dropped. traceId=" 
                + segment.getTraceId());
        }
    }
    
    /** 后台上报循环 */
    private void reportLoop() {
        List<TraceSegment> batch = new ArrayList<>(BATCH_SIZE);
        
        while (running) {
            try {
                // 从队列中批量取出
                batch.clear();
                // poll 带超时：队列空时最多等 FLUSH_INTERVAL_MS 毫秒
                TraceSegment first = buffer.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    buffer.drainTo(batch, BATCH_SIZE - 1);  // 尽量凑满一批
                }
                
                if (!batch.isEmpty()) {
                    sendBatch(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 上报失败不能让线程退出
                System.err.println("[Mini-Tracer] Report failed: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
            }
        }
    }
    
    /** 批量发送到 Collector（可以用 gRPC/HTTP） */
    private void sendBatch(List<TraceSegment> segments) {
        // 简化实现：使用 HTTP + JSON（生产环境应使用 gRPC + Protobuf）
        String json = JsonSerializer.serialize(segments);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(collectorAddress + "/v1/segments"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Mini-Tracer] Report failed, status: " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("[Mini-Tracer] Report error: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        running = false;
        reporterThread.interrupt();
    }
}
```

#### 8.5.3 Collector 接收与存储

```java
package com.minitracer.collector;

/**
 * Collector Server 主入口
 * 使用 Spring Boot 快速搭建
 */
@SpringBootApplication
public class CollectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}

/**
 * Segment 接收 Controller
 */
@RestController
@RequestMapping("/v1")
public class SegmentReceiver {
    
    @Autowired
    private TraceStorage storage;
    
    /**
     * 接收 Agent 上报的 Segment 数据
     */
    @PostMapping("/segments")
    public ResponseEntity<String> receiveSegments(@RequestBody List<TraceSegment> segments) {
        for (TraceSegment segment : segments) {
            storage.store(segment);
        }
        return ResponseEntity.ok("OK");
    }
}

/**
 * Trace 查询 Controller（供 UI 调用）
 */
@RestController
@RequestMapping("/v1/trace")
public class TraceQueryController {
    
    @Autowired
    private TraceStorage storage;
    
    /** 根据 traceId 查询完整调用链 */
    @GetMapping("/{traceId}")
    public ResponseEntity<TraceDetail> queryTrace(@PathVariable String traceId) {
        List<TraceSegment> segments = storage.findByTraceId(traceId);
        if (segments.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // 将多个 Segment 组装成完整的调用树
        TraceDetail detail = TraceBuilder.buildTree(segments);
        return ResponseEntity.ok(detail);
    }
    
    /** 查询最近的 Trace 列表 */
    @GetMapping("/list")
    public ResponseEntity<List<TraceSummary>> listTraces(
            @RequestParam(defaultValue = "") String serviceName,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") long startTime,
            @RequestParam(defaultValue = "0") long endTime) {
        List<TraceSummary> traces = storage.listTraces(serviceName, limit, startTime, endTime);
        return ResponseEntity.ok(traces);
    }
}
```

#### 8.5.4 调用树还原算法

```java
package com.minitracer.collector.analysis;

/**
 * 将多个 Segment 还原为完整的调用树
 * 
 * 核心算法：
 * 1. 按 traceId 聚合所有 Segment
 * 2. 找到根 Segment（没有 ref 的那个）
 * 3. 通过 ref 中的 parentSegmentId + parentSpanId 递归构建树结构
 */
public class TraceBuilder {
    
    public static TraceDetail buildTree(List<TraceSegment> segments) {
        TraceDetail detail = new TraceDetail();
        detail.setTraceId(segments.get(0).getTraceId());
        
        // 第一步：建立 segmentId → segment 的索引
        Map<String, TraceSegment> segmentMap = segments.stream()
            .collect(Collectors.toMap(TraceSegment::getSegmentId, s -> s));
        
        // 第二步：建立 "parentSegmentId:parentSpanId" → childSegment 的索引
        Map<String, List<TraceSegment>> childrenMap = new HashMap<>();
        TraceSegment rootSegment = null;
        
        for (TraceSegment seg : segments) {
            if (seg.getRefs() == null || seg.getRefs().isEmpty()) {
                rootSegment = seg;  // 没有引用的是根 Segment
            } else {
                for (TraceSegmentRef ref : seg.getRefs()) {
                    String parentKey = ref.getParentSegmentId() + ":" + ref.getParentSpanId();
                    childrenMap.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(seg);
                }
            }
        }
        
        // 第三步：从根节点递归构建树
        if (rootSegment != null) {
            List<SpanNode> tree = buildSpanTree(rootSegment, childrenMap);
            detail.setSpans(tree);
            detail.setDuration(calculateTotalDuration(tree));
        }
        
        return detail;
    }
    
    private static List<SpanNode> buildSpanTree(TraceSegment segment, 
                                                 Map<String, List<TraceSegment>> childrenMap) {
        List<SpanNode> nodes = new ArrayList<>();
        
        for (Span span : segment.getSpans()) {
            SpanNode node = new SpanNode(span, segment.getServiceName());
            
            // 如果这个 Span 是 ExitSpan，检查是否有下游 Segment
            String key = segment.getSegmentId() + ":" + span.getSpanId();
            List<TraceSegment> children = childrenMap.get(key);
            if (children != null) {
                for (TraceSegment childSeg : children) {
                    // 递归处理子 Segment
                    node.getChildren().addAll(buildSpanTree(childSeg, childrenMap));
                }
            }
            
            nodes.add(node);
        }
        
        return nodes;
    }
}

/** 调用树的节点（用于前端展示） */
public class SpanNode {
    private String spanId;
    private String operationName;
    private String serviceName;
    private String type;         // ENTRY/LOCAL/EXIT
    private long startTime;
    private long duration;
    private boolean isError;
    private Map<String, String> tags;
    private List<SpanNode> children = new ArrayList<>();
}
```

#### 8.5.5 存储层设计（ElasticSearch）

```java
package com.minitracer.collector.storage;

/**
 * ES 存储实现
 * 
 * 索引设计：
 * - mini_tracer_segment_{日期} : 存储原始 Segment 数据
 * - mini_tracer_service        : 存储服务元数据
 * - mini_tracer_topology       : 存储服务拓扑关系
 */
@Repository
public class ElasticSearchStorage implements TraceStorage {
    
    @Autowired
    private RestHighLevelClient esClient;
    
    @Override
    public void store(TraceSegment segment) {
        // 将 Segment 写入 ES
        String indexName = "mini_tracer_segment_" + dateFormat(segment.getCreateTime());
        IndexRequest request = new IndexRequest(indexName)
            .id(segment.getSegmentId())
            .source(JsonSerializer.toMap(segment));
        
        esClient.indexAsync(request, RequestOptions.DEFAULT, new ActionListener<>() {
            @Override
            public void onResponse(IndexResponse response) { /* 成功 */ }
            @Override
            public void onFailure(Exception e) {
                System.err.println("[Collector] ES write failed: " + e.getMessage());
            }
        });
    }
    
    @Override
    public List<TraceSegment> findByTraceId(String traceId) {
        // 按 traceId 查询所有 Segment
        SearchRequest request = new SearchRequest("mini_tracer_segment_*");
        request.source().query(QueryBuilders.termQuery("traceId", traceId));
        request.source().size(100);  // 一条 Trace 最多100个 Segment
        
        SearchResponse response = esClient.search(request, RequestOptions.DEFAULT);
        return Arrays.stream(response.getHits().getHits())
            .map(hit -> JsonSerializer.fromMap(hit.getSourceAsMap(), TraceSegment.class))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<TraceSummary> listTraces(String serviceName, int limit, 
                                          long startTime, long endTime) {
        SearchRequest request = new SearchRequest("mini_tracer_segment_*");
        BoolQueryBuilder query = QueryBuilders.boolQuery();
        
        if (!serviceName.isEmpty()) {
            query.must(QueryBuilders.termQuery("serviceName", serviceName));
        }
        if (startTime > 0) {
            query.must(QueryBuilders.rangeQuery("createTime").gte(startTime));
        }
        if (endTime > 0) {
            query.must(QueryBuilders.rangeQuery("createTime").lte(endTime));
        }
        // 只查询根 Segment（没有 ref 的）
        query.mustNot(QueryBuilders.existsQuery("refs"));
        
        request.source().query(query)
            .sort("createTime", SortOrder.DESC)
            .size(limit);
        
        // ... 执行查询并转换为 TraceSummary
    }
}
```

---

### 8.6 Phase 5：前端 UI 可视化

#### 8.6.1 调用链瀑布图

前端最核心的功能是将调用链渲染为瀑布图（Waterfall Chart），让每次调用的时间关系一目了然。

```html
<!-- 简化的 Vue.js 瀑布图组件 -->
<template>
  <div class="trace-waterfall">
    <div class="header">
      <span>TraceId: {{ trace.traceId }}</span>
      <span>Duration: {{ trace.duration }}ms</span>
    </div>
    
    <!-- 时间标尺 -->
    <div class="timeline-ruler">
      <span v-for="tick in ticks" :style="{ left: tick.position + '%' }">
        {{ tick.label }}
      </span>
    </div>
    
    <!-- 递归渲染 Span 树 -->
    <SpanRow 
      v-for="span in flattenSpans" 
      :key="span.spanId"
      :span="span"
      :trace-start="trace.startTime"
      :trace-duration="trace.duration"
      :depth="span.depth"
    />
  </div>
</template>

<!-- 单行 Span 组件 -->
<template>
  <div class="span-row" :style="{ paddingLeft: depth * 20 + 'px' }">
    <!-- 左侧：服务名 + 操作名 -->
    <div class="span-info">
      <span class="service-badge" :class="span.type.toLowerCase()">
        {{ span.serviceName }}
      </span>
      <span class="operation-name">{{ span.operationName }}</span>
      <span class="duration">{{ span.duration }}ms</span>
      <span v-if="span.isError" class="error-badge">ERROR</span>
    </div>
    
    <!-- 右侧：时间条（位置和宽度代表相对时间） -->
    <div class="span-bar-container">
      <div 
        class="span-bar" 
        :class="{ error: span.isError }"
        :style="{
          left: ((span.startTime - traceStart) / traceDuration * 100) + '%',
          width: (span.duration / traceDuration * 100) + '%'
        }"
      />
    </div>
  </div>
</template>
```

#### 8.6.2 服务拓扑图

```javascript
// 从 Trace 数据中提取服务间调用关系
function buildTopology(traces) {
    const edges = new Map(); // "serviceA → serviceB" → { calls, avgDuration, errorRate }
    
    for (const trace of traces) {
        for (const segment of trace.segments) {
            for (const span of segment.spans) {
                if (span.type === 'EXIT' && span.refs) {
                    // 找到对应的下游 EntrySpan
                    const sourceService = segment.serviceName;
                    const targetService = findTargetService(span, trace.segments);
                    const key = `${sourceService}->${targetService}`;
                    
                    if (!edges.has(key)) {
                        edges.set(key, { source: sourceService, target: targetService, 
                                         calls: 0, totalDuration: 0, errors: 0 });
                    }
                    const edge = edges.get(key);
                    edge.calls++;
                    edge.totalDuration += span.duration;
                    if (span.isError) edge.errors++;
                }
            }
        }
    }
    
    return Array.from(edges.values());
}
```

---

### 8.7 Phase 6：高级特性

#### 8.7.1 采样器实现

```java
package com.minitracer.core.sampling;

/**
 * 采样器 —— 决定哪些请求需要被追踪
 * 
 * 在高流量场景下，全量采集会产生巨大的数据量。
 * 采样器的作用是在保证统计准确性的前提下，只采集一部分请求。
 */
public class RateLimitingSampler implements Sampler {
    
    private final int maxPerSecond;       // 每秒最多采样N条
    private final AtomicInteger counter;  // 当前秒已采样数
    private volatile long currentSecond;  // 当前秒的时间戳
    
    public RateLimitingSampler(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
        this.counter = new AtomicInteger(0);
        this.currentSecond = System.currentTimeMillis() / 1000;
    }
    
    @Override
    public boolean shouldSample(String operationName) {
        long now = System.currentTimeMillis() / 1000;
        
        // 新的一秒，重置计数器
        if (now != currentSecond) {
            currentSecond = now;
            counter.set(0);
        }
        
        // 未达到上限则采样
        return counter.incrementAndGet() <= maxPerSecond;
    }
}

/**
 * 强制采样规则：如果上游已经在采样（carrier 中 sampled=true），
 * 则下游必须也采样，保证完整链路不断裂
 */
public class ForceSampler implements Sampler {
    private final Sampler delegate;
    
    @Override
    public boolean shouldSample(String operationName, ContextCarrier carrier) {
        // 上游已采样 → 强制采样
        if (carrier != null && carrier.isValid() && carrier.isSampled()) {
            return true;
        }
        // 否则走正常采样逻辑
        return delegate.shouldSample(operationName);
    }
}
```

#### 8.7.2 日志关联（TraceId 注入 MDC）

```java
package com.minitracer.toolkit.log;

/**
 * Logback 布局扩展 —— 将 traceId 注入日志
 * 
 * 使用后，每条业务日志都会自动携带 traceId：
 * 2024-01-15 10:23:45.123 [abc123.1.17053...] INFO OrderService - 创建订单成功
 * 
 * 出问题时：复制日志中的 traceId → 在 UI 中搜索 → 直接看到完整调用链
 */
public class TraceIdPatternConverter extends ClassicConverter {
    
    @Override
    public String convert(ILoggingEvent event) {
        // 从 ContextManager 获取当前线程的 traceId
        Span activeSpan = ContextManager.activeSpan();
        if (activeSpan != null) {
            return activeSpan.getTraceId();
        }
        return "N/A";  // 非追踪线程
    }
}

// 在 logback.xml 中使用：
// <conversionRule conversionWord="tid" 
//   converterClass="com.minitracer.toolkit.log.TraceIdPatternConverter"/>
// <pattern>%d [%tid] %-5level %logger - %msg%n</pattern>
```

#### 8.7.3 告警引擎

```java
package com.minitracer.collector.alarm;

/**
 * 告警引擎 —— 基于规则实时检测异常
 */
public class AlarmEngine {
    
    private List<AlarmRule> rules;
    private Map<String, RingBuffer<Long>> metricsWindow;  // 滑动窗口
    
    /** 每次 Segment 入库后触发检查 */
    public void check(TraceSegment segment) {
        for (Span span : segment.getSpans()) {
            // 检查慢请求
            for (AlarmRule rule : rules) {
                if (rule.matches(span)) {
                    RingBuffer<Long> window = getWindow(rule.getName(), span.getOperationName());
                    window.add(span.getDuration());
                    
                    if (shouldAlarm(rule, window)) {
                        triggerAlarm(rule, span);
                    }
                }
            }
        }
    }
    
    private boolean shouldAlarm(AlarmRule rule, RingBuffer<Long> window) {
        // 滑动窗口内超过阈值的次数 >= rule.count 则告警
        long count = window.stream()
            .filter(duration -> duration > rule.getThreshold())
            .count();
        return count >= rule.getCount();
    }
    
    private void triggerAlarm(AlarmRule rule, Span span) {
        AlarmMessage msg = new AlarmMessage();
        msg.setRuleName(rule.getName());
        msg.setMessage(String.format("服务 %s 的 %s 响应时间超过 %dms", 
            span.getServiceName(), span.getOperationName(), rule.getThreshold()));
        msg.setTimestamp(System.currentTimeMillis());
        
        // 发送告警（Webhook / 邮件 / 钉钉等）
        for (AlarmChannel channel : alarmChannels) {
            channel.send(msg);
        }
    }
}
```

---

### 8.8 项目打包与运行

#### 8.8.1 Agent 打包（Maven 配置）

```xml
<!-- mini-tracer-agent/pom.xml -->
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <archive>
                    <manifestEntries>
                        <!-- 关键：声明 premain 入口类 -->
                        <Premain-Class>com.minitracer.agent.MiniTracerAgent</Premain-Class>
                        <Can-Redefine-Classes>true</Can-Redefine-Classes>
                        <Can-Retransform-Classes>true</Can-Retransform-Classes>
                    </manifestEntries>
                </archive>
            </configuration>
        </plugin>
        
        <!-- 将所有依赖打入一个 fat jar -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals><goal>shade</goal></goals>
                    <configuration>
                        <!-- 重定位 ByteBuddy 包名，避免和业务代码冲突 -->
                        <relocations>
                            <relocation>
                                <pattern>net.bytebuddy</pattern>
                                <shadedPattern>com.minitracer.shaded.bytebuddy</shadedPattern>
                            </relocation>
                        </relocations>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>

<dependencies>
    <dependency>
        <groupId>net.bytebuddy</groupId>
        <artifactId>byte-buddy</artifactId>
        <version>1.14.12</version>
    </dependency>
    <dependency>
        <groupId>net.bytebuddy</groupId>
        <artifactId>byte-buddy-agent</artifactId>
        <version>1.14.12</version>
    </dependency>
</dependencies>
```

#### 8.8.2 运行方式

```bash
# 1. 编译打包
mvn clean package -pl mini-tracer-agent -am

# 2. 启动 Collector
java -jar mini-tracer-collector/target/collector.jar

# 3. 启动业务应用（附加 Agent）
java -javaagent:mini-tracer-agent/target/mini-tracer-agent.jar=service_name=order-service,collector=http://localhost:12800 \
     -jar order-service.jar

# 4. 发送请求后，在 UI 查看链路
# 访问 http://localhost:8080 → 输入 traceId → 查看瀑布图
```

---

### 8.9 核心设计决策总结

| 设计问题 | 决策 | 原因 |
|----------|------|------|
| 字节码工具选型 | ByteBuddy（而非 ASM/Javassist） | API 高层易用，插件开发门槛低 |
| 数据传输格式 | Header 用自定义协议 Base64 编码 | 控制长度、跨语言兼容、避免特殊字符 |
| 缓冲策略 | 环形队列 + 满时丢弃 | 绝不阻塞业务线程 |
| ID 生成 | 进程ID + 线程ID + 时间戳 + 序列 | 无分布式协调、可反推来源 |
| Span 管理 | ThreadLocal + 栈 | 天然匹配方法调用的 LIFO 顺序 |
| 采样策略 | 限速 + 强制跟随 | 控制数据量 + 保证链路完整性 |
| Span 复用 | 嵌套同类型 Span 只增加深度 | 减少对象创建和 GC 压力 |
| 数据上报 | 后台守护线程异步批量发送 | 不占用业务线程 CPU |
| Collector 存储 | 按日期分索引 + 按 traceId 查询 | 支持 TTL 清理、查询高效 |

---

### 8.10 扩展方向与进阶挑战

完成上述六个 Phase 后，你已经有了一个可用的链路追踪原型。如果想继续打磨成生产级系统，还可以考虑：

1. **gRPC 替代 HTTP**：使用 Protobuf + gRPC Streaming 传输数据，吞吐量提升 5-10 倍
2. **动态配置下发**：Collector 通过长轮询或 gRPC 双向流向 Agent 下发采样率变更
3. **服务拓扑自动发现**：从 Trace 数据中自动提取服务间调用关系，绘制实时拓扑图
4. **Metrics 聚合**：Agent 端预聚合 P50/P99/P999 指标，减轻 Collector 压力
5. **多语言 Agent**：参照 sw8 协议标准，开发 Go/Python/Node.js Agent
6. **集群部署**：Collector 做成无状态服务，通过 Kafka 做 Agent 和 Collector 之间的解耦
7. **自适应采样**：根据 QPS 自动调整采样率（流量大时降低，流量小时提高）
8. **安全传输**：Agent → Collector 的通信支持 TLS 加密和 Token 认证

```
最终完整架构演进方向：

Agent 集群                    消息队列               Collector 集群          Storage
┌─────────┐               ┌──────────┐           ┌────────────┐       ┌──────────┐
│ Agent A  │ ─── gRPC ──► │          │ ────────► │ Collector1 │ ────► │          │
│ Agent B  │ ─── gRPC ──► │  Kafka   │ ────────► │ Collector2 │ ────► │    ES    │
│ Agent C  │ ─── gRPC ──► │          │ ────────► │ Collector3 │ ────► │  Cluster │
│ ...      │              └──────────┘           └────────────┘       └──────────┘
└─────────┘                                            │
                                                       ▼
                                                ┌────────────┐
                                                │  Alarm     │
                                                │  Engine    │→ Webhook/Email/DingTalk
                                                └────────────┘
```

---

> 至此，你已经完全理解了从零实现一个类 SkyWalking 链路追踪系统的全部思路、架构和核心代码。动手实现它是深入理解分布式系统可观测性的最佳方式。
