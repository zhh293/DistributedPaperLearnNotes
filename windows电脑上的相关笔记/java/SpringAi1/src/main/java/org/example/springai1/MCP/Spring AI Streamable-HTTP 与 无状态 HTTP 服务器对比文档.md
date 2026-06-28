# Spring AI Streamable-HTTP 与 无状态 HTTP 服务器对比文档

## 一、概述

Spring AI 提供了两种基于 HTTP 的 MCP 服务器实现：Streamable-HTTP 服务器和无状态 HTTP 服务器。这两种实现都旨在简化部署并支持现代微服务架构，但在设计理念、功能特性和适用场景方面存在显著差异。

本文档将详细对比这两种服务器实现，帮助开发者根据具体需求选择最适合的解决方案。

## 二、核心概念对比

### 2.1 Streamable-HTTP 服务器

Streamable-HTTP 服务器允许 MCP 服务器作为独立进程运行，能够通过 HTTP POST 和 GET 请求处理多个客户端连接，并可选地为多服务器消息提供服务器发送事件（SSE）流。

### 2.2 无状态 HTTP 服务器

无状态 HTTP 服务器设计用于简化部署，请求间不维护会话状态。这些服务器非常适合微服务架构和云原生部署，因为它们不需要在请求之间保存任何状态信息。

## 三、功能特性对比

| 特性 | Streamable-HTTP 服务器 | 无状态 HTTP 服务器 |
|------|------------------------|--------------------|
| 会话状态管理 | 支持 | 不支持 |
| 实时通知 | 支持（通过 SSE） | 不支持 |
| 客户端消息请求 | 支持（ping、progress、logging 等） | 不支持 |
| 工具上下文支持 | 支持 | 不支持 |
| 部署复杂度 | 中等 | 简单 |
| 扩展性 | 高 | 很高 |
| 资源消耗 | 中等 | 低 |
| 适用场景 | 需要实时通信和状态管理 | 简单请求/响应，云原生部署 |

## 四、详细对比分析

### 4.1 会话状态管理

#### Streamable-HTTP 服务器
- 维护客户端连接状态
- 支持长时间运行的会话
- 可以在多个请求之间保持上下文信息
- 适用于需要持续交互的应用场景

#### 无状态 HTTP 服务器
- 每个请求都是独立的
- 不保存任何会话信息
- 请求处理完成后立即释放所有资源
- 适用于简单的请求/响应模式

### 4.2 实时通知能力

#### Streamable-HTTP 服务器
- 通过服务器发送事件（SSE）实现实时通知
- 支持工具、资源和提示的动态变更通知
- 可以主动向客户端推送消息
- 适用于需要实时更新的应用场景

#### 无状态 HTTP 服务器
- 不支持实时通知功能
- 所有通信都是请求驱动的
- 客户端需要主动轮询获取更新
- 适用于不需要实时更新的场景

### 4.3 客户端消息请求

#### Streamable-HTTP 服务器
- 支持向客户端发送多种类型的消息请求：
  - Ping：验证客户端连接活性
  - Progress：发送进度更新
  - Logging：发送结构化日志消息
- 可以实现复杂的双向通信

#### 无状态 HTTP 服务器
- 不支持向客户端发送消息请求
- 所有通信都是单向的（服务器响应客户端请求）
- 无法实现复杂的交互模式

### 4.4 工具上下文支持

#### Streamable-HTTP 服务器
- 支持工具上下文（ToolContext）
- 可以在工具调用中传递上下文信息
- 支持更复杂的工具交互场景

#### 无状态 HTTP 服务器
- 不支持工具上下文功能
- 工具调用是完全独立的
- 适用于简单的工具执行场景

### 4.5 部署和运维

#### Streamable-HTTP 服务器
- 需要考虑会话状态管理
- 可能需要 sticky sessions（粘性会话）支持
- 部署相对复杂，需要考虑状态同步
- 适合需要持续连接的应用

#### 无状态 HTTP 服务器
- 部署极其简单
- 天然支持水平扩展
- 无需考虑状态同步问题
- 适合容器化和云原生部署

## 五、依赖和配置对比

### 5.1 依赖配置

两种服务器都可以使用相同的依赖：

#### WebMVC 实现
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

#### WebFlux 实现
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

### 5.2 配置属性

#### Streamable-HTTP 服务器配置
```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: streamable-mcp-server
        version: 1.0.0
        type: SYNC
        streamable-http:
          mcp-endpoint: /api/mcp
          keep-alive-interval: 30s
```

#### 无状态 HTTP 服务器配置
```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        name: stateless-mcp-server
        version: 1.0.0
        type: SYNC
        stateless:
          mcp-endpoint: /api/mcp
```

## 六、代码实现对比

### 6.1 工具规范实现

#### Streamable-HTTP 服务器
```java
@Bean
public List<McpServerFeatures.SyncToolSpecification> myTools(...) {
    List<McpServerFeatures.SyncToolSpecification> tools = ...
    return tools;
}
```

#### 无状态 HTTP 服务器
```java
@Bean
public List<McpStatelessServerFeatures.SyncToolSpecification> myTools(...) {
    List<McpStatelessServerFeatures.SyncToolSpecification> tools = ...
    return tools;
}
```

### 6.2 资源规范实现

#### Streamable-HTTP 服务器
```java
@Bean
public List<McpServerFeatures.SyncResourceSpecification> myResources(...) {
    var resourceSpecification = new McpServerFeatures.SyncResourceSpecification(
        systemInfoResource, 
        (exchange, request) -> {
            // 使用 exchange 对象进行复杂操作
            exchange.loggingNotification(...);
            exchange.progressNotification(...);
            // 实现资源读取逻辑
        }
    );
    return List.of(resourceSpecification);
}
```

#### 无状态 HTTP 服务器
```java
@Bean
public List<McpStatelessServerFeatures.SyncResourceSpecification> myResources(...) {
    var resourceSpecification = new McpStatelessServerFeatures.SyncResourceSpecification(
        systemInfoResource, 
        (context, request) -> {
            // 使用 context 对象进行简单操作
            // 不支持 loggingNotification 和 progressNotification
            // 实现资源读取逻辑
        }
    );
    return List.of(resourceSpecification);
}
```

## 七、适用场景分析

### 7.1 Streamable-HTTP 服务器适用场景

1. **实时通知需求**：需要向客户端推送工具、资源或提示变更通知
2. **复杂交互场景**：需要与客户端进行双向通信，如发送进度更新、日志消息等
3. **长时间会话**：需要维护客户端连接状态，支持长时间运行的交互
4. **工具上下文需求**：需要在工具调用中传递上下文信息
5. **传统 Web 应用**：需要维持会话状态的传统 Web 应用场景

### 7.2 无状态 HTTP 服务器适用场景

1. **微服务架构**：作为独立的微服务组件，无需维护状态
2. **云原生部署**：在 Kubernetes 或其他容器编排平台中部署
3. **简单请求/响应**：只需要处理简单的请求/响应交互
4. **高并发场景**：处理大量短生命周期的请求
5. **无状态要求**：符合无状态设计原则的应用架构
6. **成本敏感场景**：希望降低资源消耗和运维复杂度

## 八、性能和资源消耗对比

### 8.1 内存消耗

- **Streamable-HTTP 服务器**：由于需要维护会话状态，内存消耗相对较高
- **无状态 HTTP 服务器**：每个请求独立处理，内存消耗较低

### 8.2 扩展性

- **Streamable-HTTP 服务器**：扩展性良好，但可能需要考虑会话状态同步
- **无状态 HTTP 服务器**：天然支持水平扩展，扩展性极佳

### 8.3 网络资源

- **Streamable-HTTP 服务器**：支持持久连接，减少连接建立开销
- **无状态 HTTP 服务器**：每次请求建立新连接，连接开销较大

## 九、迁移考虑

### 9.1 从 Streamable-HTTP 迁移到无状态 HTTP

如果决定从 Streamable-HTTP 迁移到无状态 HTTP，需要考虑以下几点：

1. **移除实时通知功能**：所有主动推送消息的功能都需要改为客户端轮询
2. **重构工具实现**：移除对工具上下文的依赖
3. **修改配置**：将 protocol 从 STREAMABLE 改为 STATELESS
4. **调整客户端**：客户端需要适应无状态服务器的行为

### 9.2 从无状态 HTTP 迁移到 Streamable-HTTP

如果需要从无状态 HTTP 迁移到 Streamable-HTTP，需要考虑以下几点：

1. **增加状态管理**：实现会话状态管理机制
2. **添加实时通知**：如有需要，实现 SSE 通知机制
3. **重构工具实现**：可以利用工具上下文功能增强工具能力
4. **修改配置**：将 protocol 从 STATELESS 改为 STREAMABLE

## 十、最佳实践建议

### 10.1 选择建议

| 需求场景 | 推荐方案 | 理由 |
|----------|----------|------|
| 需要实时通知 | Streamable-HTTP | 原生支持 SSE 实时推送 |
| 微服务架构 | 无状态 HTTP | 更好的云原生支持 |
| 简单工具调用 | 无状态 HTTP | 更低的资源消耗 |
| 复杂交互场景 | Streamable-HTTP | 支持双向通信和上下文 |
| 高并发短请求 | 无状态 HTTP | 更好的扩展性 |
| 长时间会话 | Streamable-HTTP | 支持会话状态管理 |

### 10.2 设计原则

#### 使用 Streamable-HTTP 时：
1. 合理管理会话状态，避免内存泄漏
2. 正确配置 keep-alive 间隔，平衡资源消耗和连接维持
3. 充分利用实时通知功能提升用户体验
4. 考虑负载均衡时的会话粘性需求

#### 使用无状态 HTTP 时：
1. 确保每个请求的独立性和完整性
2. 避免在服务器端存储任何会话数据
3. 优化请求处理逻辑，提高响应速度
4. 充分利用云原生平台的自动扩缩容能力

## 十一、总结

Streamable-HTTP 和无状态 HTTP 服务器各有优势，选择哪种实现取决于具体的业务需求和技术约束：

- 如果需要实时通信、会话管理和复杂交互，应选择 Streamable-HTTP 服务器
- 如果追求简单部署、高扩展性和低资源消耗，应选择无状态 HTTP 服务器

在实际项目中，可以根据不同服务的特点选择不同的实现方式，甚至在同一系统中混合使用两种服务器类型，以达到最优的整体架构效果。