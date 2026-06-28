# Spring AI Streamable-HTTP 服务器完整使用文档

## 一、核心概念

Streamable-HTTP 是 Model Context Protocol (MCP) 中的一种传输协议，允许 MCP 服务器作为独立进程运行，能够通过 HTTP POST 和 GET 请求处理多个客户端连接，并可选地为多服务器消息提供服务器发送事件（SSE）流。它取代了传统的 SSE 传输方式，为需要通知客户端工具、资源或提示动态变更的应用提供了更好的支持。

### 1.1 Streamable-HTTP 的核心价值

Streamable-HTTP 传输协议为 AI 应用提供了以下核心能力：

1. **独立进程运行**：服务器可以作为独立进程运行，不依赖于客户端的生命周期
2. **多客户端支持**：能够同时处理多个客户端的连接和请求
3. **实时变更通知**：通过 SSE 流为客户端提供实时的工具、资源和提示变更通知
4. **标准化接口**：提供统一的 HTTP 接口，简化了客户端与服务器的集成

### 1.2 Streamable-HTTP 的应用场景

Streamable-HTTP 适用于多种 AI 应用场景：

- **企业级 MCP 服务**：作为独立服务运行，为多个客户端应用提供 MCP 功能
- **实时通知系统**：需要实时通知客户端工具、资源或提示变更的场景
- **微服务架构**：在微服务架构中作为独立的 MCP 服务组件
- **云原生部署**：适合在容器化环境和云平台中部署

## 二、快速入门

### 2.1 环境准备

在开始使用 Spring AI Streamable-HTTP 服务器之前，需要添加相应的依赖到项目中。

#### 2.1.1 Maven 依赖配置

对于基于 Spring MVC 的 Streamable-HTTP 服务器：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

对于基于 WebFlux 的 Streamable-HTTP 服务器：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

#### 2.1.2 Gradle 依赖配置

对于基于 Spring MVC 的 Streamable-HTTP 服务器：

```gradle
implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
```

对于基于 WebFlux 的 Streamable-HTTP 服务器：

```gradle
implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webflux'
```

### 2.2 基本配置

在 `application.yml` 中配置 Streamable-HTTP 服务器相关属性：

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: streamable-mcp-server
        version: 1.0.0
        type: SYNC
```

### 2.3 简单示例

创建一个简单的 Streamable-HTTP 服务器应用：

```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StreamableHttpMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(StreamableHttpMcpServerApplication.class, args);
    }
}
```

## 三、详细配置指南

### 3.1 通用配置属性

Streamable-HTTP 服务器的通用配置属性以 `spring.ai.mcp.server` 为前缀：

| 属性 | 描述 | 默认值 |
|------|------|--------|
| enabled | 启用/禁用 Streamable-HTTP MCP 服务器 | true |
| protocol | MCP 服务器协议，必须设置为 STREAMABLE 才能启用可流式服务器 | - |
| tool-callback-converter | 启用/禁用将 Spring AI ToolCallback 转换为 MCP 工具规范 | true |
| name | 用于识别的服务器名称 | mcp-server |
| version | 服务器版本 | 1.0.0 |
| instructions | 客户交互的可选说明 | null |
| type | 服务器类型（SYNC/ASYNC） | SYNC |
| capabilities.resource | 启用/禁用资源功能 | true |
| capabilities.tool | 启用/禁用工具功能 | true |
| capabilities.prompt | 启用/禁用提示符功能 | true |
| capabilities.completion | 启用/禁用完成功能 | true |
| resource-change-notification | 启用资源变更通知 | true |
| prompt-change-notification | 启用提示变更通知 | true |
| tool-change-notification | 启用工具更换通知 | true |
| tool-response-mime-type | 每个工具名称的响应 MIME 类型 | - |
| request-timeout | 请求超时时长 | 20 seconds |

### 3.2 Streamable-HTTP 特定配置

所有可流媒体的 HTTP 属性都以 `spring.ai.mcp.server.streamable-http` 为前缀：

| 属性 | 描述 | 默认值 |
|------|------|--------|
| mcp-endpoint | 自定义 MCP 端点路径 | /mcp |
| keep-alive-interval | 连接保持活体间隔 | null（禁用） |
| disallow-delete | 禁止删除操作 | false |

### 3.3 完整配置示例

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        protocol: STREAMABLE
        name: streamable-mcp-server
        version: 1.0.0
        type: SYNC
        instructions: "This streamable server provides real-time notifications"
        request-timeout: 30s
        tool-callback-converter: true
        capabilities:
          resource: true
          tool: true
          prompt: true
          completion: true
        resource-change-notification: true
        prompt-change-notification: true
        tool-change-notification: true
        streamable-http:
          mcp-endpoint: /api/mcp
          keep-alive-interval: 30s
          disallow-delete: false
```

## 四、功能与能力

MCP 服务器支持四种主要功能类型，可以单独启用或禁用：

### 4.1 工具功能

工具功能允许服务器暴露可被语言模型调用的工具。

启用/禁用工具功能：
```yaml
spring:
  ai:
    mcp:
      server:
        capabilities:
          tool: true|false
```

#### 4.1.1 自动工具配置

通过 Spring Bean 自动工具配置：

```java
@Bean
public ToolCallbackProvider myTools(...) {
    List<ToolCallback> tools = ...
    return ToolCallbackProvider.from(tools);
}
```

或者使用底层 API：

```java
@Bean
public List<McpServerFeatures.SyncToolSpecification> myTools(...) {
    List<McpServerFeatures.SyncToolSpecification> tools = ...
    return tools;
}
```

#### 4.1.2 工具上下文支持

支持 ToolContext，允许将上下文信息传递给工具调用：

```java
(exchange, request) -> {
    // 获取工具上下文
    McpSyncServerExchange exchange = McpToolUtils.getMcpExchange(toolContext);
    
    // 发送日志消息
    exchange.loggingNotification(LoggingMessageNotification.builder()
        .level(LoggingLevel.INFO)
        .logger("tool-logger")
        .data("Processing tool call: " + request.name())
        .build());
        
    // 发送进度通知
    exchange.progressNotification(ProgressNotification.builder()
        .progressToken("tool-progress")
        .progress(0.5)
        .total(1.0)
        .message("Tool call in progress")
        .build());
}
```

### 4.2 资源功能

资源功能为服务器向客户端暴露资源提供了标准化方式。

启用/禁用资源功能：
```yaml
spring:
  ai:
    mcp:
      server:
        capabilities:
          resource: true|false
```

#### 4.2.1 静态与动态资源规范

通过 Spring Bean 自动资源分配：

```java
@Bean
public List<McpServerFeatures.SyncResourceSpecification> myResources(...) {
    var systemInfoResource = new McpSchema.Resource(
        URI.create("file:///system/info.json"),
        "System Information",
        Set.of("application/json")
    );
    
    var resourceSpecification = new McpServerFeatures.SyncResourceSpecification(
        systemInfoResource, 
        (exchange, request) -> {
            try {
                var systemInfo = Map.of(
                    "os", System.getProperty("os.name"),
                    "version", System.getProperty("os.version"),
                    "arch", System.getProperty("os.arch")
                );
                String jsonContent = new ObjectMapper().writeValueAsString(systemInfo);
                return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                        request.uri(), 
                        "application/json", 
                        jsonContent
                    ))
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to generate system info", e);
            }
        }
    );

    return List.of(resourceSpecification);
}
```

#### 4.2.2 变更通知

资源变更通知可以通过配置启用或禁用：

```yaml
spring:
  ai:
    mcp:
      server:
        resource-change-notification: true
```

### 4.3 提示功能

提示功能为服务器提供了一种标准化的方式，以向客户端展示提示模板。

启用/禁用提示功能：
```yaml
spring:
  ai:
    mcp:
      server:
        capabilities:
          prompt: true|false
```

#### 4.3.1 提示模板

通过 Spring Beans 自动提示指定：

```java
@Bean
public List<McpServerFeatures.SyncPromptSpecification> myPrompts() {
    var prompt = new McpSchema.Prompt(
        "greeting", 
        "A friendly greeting prompt",
        List.of(new McpSchema.PromptArgument("name", "The name to greet", true))
    );

    var promptSpecification = new McpServerFeatures.SyncPromptSpecification(
        prompt, 
        (exchange, getPromptRequest) -> {
            String nameArgument = (String) getPromptRequest.arguments().get("name");
            if (nameArgument == null) { 
                nameArgument = "friend"; 
            }
            var userMessage = new PromptMessage(
                Role.USER, 
                new TextContent("Hello " + nameArgument + "! How can I assist you today?")
            );
            return new GetPromptResult(
                "A personalized greeting message", 
                List.of(userMessage)
            );
        }
    );

    return List.of(promptSpecification);
}
```

#### 4.3.2 变更通知

提示变更通知可以通过配置启用或禁用：

```yaml
spring:
  ai:
    mcp:
      server:
        prompt-change-notification: true
```

### 4.4 完成功能

完成功能为服务器提供了一种标准化的方式，将完成功能暴露给客户端。

启用/禁用完成功能：
```yaml
spring:
  ai:
    mcp:
      server:
        capabilities:
          completion: true|false
```

#### 4.4.1 完成建议

通过 Spring Bean 自动注册：

```java
@Bean
public List<McpServerFeatures.SyncCompletionSpecification> myCompletions() {
    var completion = new McpServerFeatures.SyncCompletionSpecification(
        new McpSchema.PromptReference(
            "ref/prompt", 
            "code-completion", 
            "Provides code completion suggestions"
        ),
        (exchange, request) -> {
            // Implementation that returns completion suggestions
            return new McpSchema.CompleteResult(
                List.of("python", "pytorch", "pyside"), 
                10, 
                true
            );
        }
    );

    return List.of(completion);
}
```

## 五、高级功能

### 5.1 日志功能

为服务器向客户端发送结构化日志消息提供了标准化方式。在工具、资源、提示符或完成调用处理器内，使用提供的 McpSyncServerExchange/McpAsyncServerExchange 对象发送日志消息：

```java
(exchange, request) -> {
    exchange.loggingNotification(LoggingMessageNotification.builder()
        .level(LoggingLevel.INFO)
        .logger("test-logger")
        .data("This is a test log message")
        .build());
}
```

在 MCP 客户端上，可以注册日志消费者以处理以下消息：

```java
mcpClientSpec.loggingConsumer((McpSchema.LoggingMessageNotification log) -> {
    // Handle log messages
});
```

### 5.2 进度通知

为服务器发送进度更新的标准化方式。在工具、资源、提示符或完成调用处理器内，使用提供的 McpSyncServerExchange/McpAsyncServerExchange 对象发送进度通知：

```java
(exchange, request) -> {
    exchange.progressNotification(ProgressNotification.builder()
        .progressToken("test-progress-token")
        .progress(0.25)
        .total(1.0)
        .message("tool call in progress")
        .build());
}
```

MCP 客户端可以接收进度通知并相应更新其 UI。为此，它需要注册一个进度消费者。

```java
mcpClientSpec.progressConsumer((McpSchema.ProgressNotification progress) -> {
    // Handle progress notifications
});
```

### 5.3 根列表变更

当根节点变更时，支持的客户端会发送根变更通知。

支持监控根变更，为被动应用自动转换为异步消费者，可选择通过 Spring Beans 注册：

```java
@Bean
public BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> rootsChangeHandler() {
    return (exchange, roots) -> {
        logger.info("Registering root resources: {}", roots);
    };
}
```

### 5.4 Ping 机制与保持活跃

服务器用来验证客户端仍然存活的 ping 机制。在工具、资源、提示符或完成调用处理器内，使用提供的 McpSyncServerExchange/McpAsyncServerExchange 对象发送 ping 消息：

```java
(exchange, request) -> {
    exchange.ping();
}
```

服务器可以选择性地定期向连接的客户端发送 ping 以验证连接健康。

默认情况下，保持生命功能是被禁用的。要启用 keep-alive，请在你的配置中设置该属性：

```yaml
spring:
  ai:
    mcp:
      server:
        streamable-http:
          keep-alive-interval: 30s
```

注意：目前，对于可流式 HTTP 服务器，保持活着机制仅适用于服务器（SSE）连接的监听消息。

## 六、实战示例

### 6.1 创建带有 MCP 服务器的 Spring Boot 应用程序

```java
import org.springframework.ai.tool.Tool;
import org.springframework.stereotype.Service;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.mcp.server.tool.ToolCallbackProvider;
import org.springframework.ai.mcp.server.tool.MethodToolCallbackProvider;

@Service
public class WeatherService {

    @Tool(description = "Get weather information by city name")
    public String getWeather(String cityName) {
        // Implementation
        return "The weather in " + cityName + " is sunny with a temperature of 25°C";
    }
}

@SpringBootApplication
public class McpServerApplication {

    private static final Logger logger = LoggerFactory.getLogger(McpServerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider weatherTools(WeatherService weatherService) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherService).build();
    }
}
```

自动配置会自动将工具回调注册为 MCP 工具。你可以有多个豆子生成 ToolCallback，自动配置会将它们合并。

### 6.2 Streamable-HTTP 服务器配置

使用 spring-ai-starter-mcp-server-webmvc：

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: streamable-mcp-server
        version: 1.0.0
        type: SYNC
        instructions: "This streamable server provides real-time notifications"
        resource-change-notification: true
        tool-change-notification: true
        prompt-change-notification: true
        streamable-http:
          mcp-endpoint: /api/mcp
          keep-alive-interval: 30s
```

## 七、最佳实践

### 7.1 安全性考虑

1. **访问控制**：实施适当的认证和授权机制
2. **输入验证**：对所有输入进行严格验证
3. **资源限制**：限制资源访问和执行时间
4. **日志记录**：记录所有 MCP 操作以便审计

### 7.2 性能优化

1. **连接池**：使用连接池管理 MCP 连接
2. **缓存机制**：缓存频繁访问的资源和工具
3. **异步处理**：使用异步操作提高响应速度
4. **批量操作**：尽可能使用批量操作减少网络往返

### 7.3 错误处理

1. **异常捕获**：捕获并处理 MCP 相关异常
2. **重试机制**：实现适当的重试逻辑
3. **降级策略**：在 MCP 服务不可用时提供降级方案
4. **监控告警**：监控 MCP 服务状态并设置告警

## 八、故障排除

### 8.1 常见问题

1. **服务器无法启动**：检查端口是否被占用，配置是否正确
2. **客户端无法连接**：检查网络连接，防火墙设置，URL 是否正确
3. **工具无法发现**：检查工具注解配置，确保工具被正确注册
4. **通知不工作**：检查变更通知配置是否启用，客户端是否注册了相应的处理器

### 8.2 调试技巧

1. **启用详细日志**：
```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    org.springframework.ai.mcp.server: TRACE
```

2. **使用调试工具**监控请求和响应
3. **查看服务器日志**确认服务器状态和错误信息

## 九、参考资源

1. [Spring AI 官方文档 - MCP 集成](https://docs.spring.io/spring-ai/docs/current/reference/html/mcp.html)
2. [MCP GitHub 仓库](https://github.com/modelcontextprotocol)
3. [MCP 规范文档](https://spec.modelcontextprotocol.io/)
4. [Spring AI 示例项目](https://github.com/spring-projects/spring-ai/tree/main/samples/mcp)

通过本文档，您应该能够掌握 Spring AI 中 Streamable-HTTP 服务器的核心概念和使用方法，并能在实际项目中有效地应用这些技术来构建具备实时通知功能的 MCP 服务。