# Spring AI 无状态 HTTP 服务器完整使用文档

## 一、核心概念

无状态可流式 HTTP MCP 服务器设计用于简化部署，即请求间不维护会话状态。这些服务器非常适合微服务架构和云原生部署，因为它们不需要在请求之间保存任何状态信息。

### 1.1 无状态服务器的核心价值

无状态 HTTP 服务器为 AI 应用提供了以下核心能力：

1. **简化部署**：无需维护会话状态，部署更加简单
2. **水平扩展**：易于进行水平扩展，适应高负载场景
3. **云原生友好**：特别适合容器化和云原生环境
4. **高可用性**：无状态设计提高了系统的可靠性和容错能力

### 1.2 无状态服务器的限制

需要注意的是，无状态服务器有一些功能限制：

- 不支持向 MCP 客户端发送消息请求（例如，触发、采样、ping）
- 工具上下文支持不适用于无状态服务器
- 无法维持长时间运行的会话

### 1.3 适用场景

无状态 HTTP 服务器适用于以下场景：

- **微服务架构**：作为轻量级的独立服务组件
- **云原生部署**：在 Kubernetes 或其他容器编排平台中部署
- **高并发场景**：处理大量短生命周期的请求
- **无状态要求**：符合无状态设计原则的应用架构

## 二、快速入门

### 2.1 环境准备

在开始使用 Spring AI 无状态 HTTP 服务器之前，需要添加相应的依赖到项目中。

#### 2.1.1 Maven 依赖配置

对于基于 Spring MVC 的无状态 HTTP 服务器：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

对于基于 WebFlux 的无状态 HTTP 服务器：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webflux</artifactId>
</dependency>
```

#### 2.1.2 Gradle 依赖配置

对于基于 Spring MVC 的无状态 HTTP 服务器：

```gradle
implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webmvc'
```

对于基于 WebFlux 的无状态 HTTP 服务器：

```gradle
implementation 'org.springframework.ai:spring-ai-starter-mcp-server-webflux'
```

### 2.2 基本配置

在 `application.yml` 中配置无状态 HTTP 服务器相关属性：

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        name: stateless-mcp-server
        version: 1.0.0
        type: SYNC
```

### 2.3 简单示例

创建一个简单的无状态 HTTP 服务器应用：

```java
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StatelessHttpMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(StatelessHttpMcpServerApplication.class, args);
    }
}
```

## 三、详细配置指南

### 3.1 通用配置属性

无状态 HTTP 服务器的通用配置属性以 `spring.ai.mcp.server` 为前缀：

| 属性 | 描述 | 默认值 |
|------|------|--------|
| enabled | 启用/禁用无状态 MCP 服务器 | true |
| protocol | MCP 服务器协议，必须设置为 STATELESS 才能启用无状态服务器 | - |
| tool-callback-converter | 启用/禁用将 Spring AI ToolCallback 转换为 MCP 工具规范 | true |
| name | 用于识别的服务器名称 | mcp-server |
| version | 服务器版本 | 1.0.0 |
| instructions | 客户交互的可选说明 | null |
| type | 服务器类型（SYNC/ASYNC） | SYNC |
| capabilities.resource | 启用/禁用资源功能 | true |
| capabilities.tool | 启用/禁用工具功能 | true |
| capabilities.prompt | 启用/禁用提示符功能 | true |
| capabilities.completion | 启用/禁用完成功能 | true |
| tool-response-mime-type | 每个工具名称的响应 MIME 类型 | - |
| request-timeout | 请求超时时长 | 20 seconds |

### 3.2 无状态特定配置

所有无状态连接属性都以 `spring.ai.mcp.server.stateless` 为前缀：

| 属性 | 描述 | 默认值 |
|------|------|--------|
| mcp-endpoint | 自定义 MCP 端点路径 | /mcp |
| disallow-delete | 禁止删除操作 | false |

### 3.3 完整配置示例

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        protocol: STATELESS
        name: stateless-mcp-server
        version: 1.0.0
        type: ASYNC
        instructions: "This stateless server is optimized for cloud deployments"
        request-timeout: 30s
        tool-callback-converter: true
        capabilities:
          resource: true
          tool: true
          prompt: true
          completion: true
        stateless:
          mcp-endpoint: /api/mcp
          disallow-delete: false
```

## 四、功能与能力

MCP 无状态服务器支持三种主要功能类型，可以单独启用或禁用：

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

或者使用底层 API（无状态专用）：

```java
@Bean
public List<McpStatelessServerFeatures.SyncToolSpecification> myTools(...) {
    List<McpStatelessServerFeatures.SyncToolSpecification> tools = ...
    return tools;
}
```

#### 4.1.2 工具回调自动检测

自动配置会自动检测并注册来自以下所有工具回调：

- 单个 ToolCallback Bean
- ToolCallback 列表 Bean
- ToolCallbackProvider Bean

工具按名称进行重复处理，每个工具名称首次出现的顺序被使用。

你可以通过设置 `tool-callback-converter: false` 来关闭所有工具回调的自动检测和注册。

注意：工具上下文支持不适用于无状态服务器。

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

通过 Spring Bean 自动资源分配（无状态专用）：

```java
@Bean
public List<McpStatelessServerFeatures.SyncResourceSpecification> myResources(...) {
    var systemInfoResource = new McpSchema.Resource(
        URI.create("file:///system/info.json"),
        "System Information",
        Set.of("application/json")
    );
    
    var resourceSpecification = new McpStatelessServerFeatures.SyncResourceSpecification(
        systemInfoResource, 
        (context, request) -> {
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

虽然无状态服务器支持资源功能，但由于其无状态特性，某些需要维持会话状态的通知功能可能受限。

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

通过 Spring Beans 自动提示指定（无状态专用）：

```java
@Bean
public List<McpStatelessServerFeatures.SyncPromptSpecification> myPrompts() {
    var prompt = new McpSchema.Prompt(
        "greeting", 
        "A friendly greeting prompt",
        List.of(new McpSchema.PromptArgument("name", "The name to greet", true))
    );

    var promptSpecification = new McpStatelessServerFeatures.SyncPromptSpecification(
        prompt, 
        (context, getPromptRequest) -> {
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

通过 Spring Bean 自动注册（无状态专用）：

```java
@Bean
public List<McpStatelessServerFeatures.SyncCompletionSpecification> myCompletions() {
    var completion = new McpStatelessServerFeatures.SyncCompletionSpecification(
        new McpSchema.PromptReference(
            "ref/prompt", 
            "code-completion", 
            "Provides code completion suggestions"
        ),
        (context, request) -> {
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

## 五、WebMVC 与 WebFlux 实现

### 5.1 无状态 WebMVC 服务器

基于 Spring MVC 的无状态服务器具有以下特点：

- 无会话状态管理
- 简化部署模型
- 针对云原生环境优化

配置示例：
```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        type: SYNC  # WebMVC 通常使用同步类型
```

### 5.2 无状态 WebFlux 服务器

基于 WebFlux 的无状态服务器具有以下特点：

- 无会话状态管理
- 非阻塞请求处理
- 针对高通量场景进行了优化

配置示例：
```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        type: ASYNC  # WebFlux 通常使用异步类型
```

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

### 6.2 无状态服务器配置

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        name: stateless-mcp-server
        version: 1.0.0
        type: ASYNC
        instructions: "This stateless server is optimized for cloud deployments"
        stateless:
          mcp-endpoint: /api/mcp
```

## 七、最佳实践

### 7.1 设计原则

1. **保持无状态**：确保服务器在请求之间不保留任何状态
2. **幂等性**：设计幂等的操作，使得重复请求产生相同结果
3. **快速响应**：优化处理逻辑，确保快速响应
4. **容错设计**：实现适当的错误处理和恢复机制

### 7.2 安全性考虑

1. **输入验证**：对所有输入进行严格验证
2. **访问控制**：实施适当的认证和授权机制
3. **资源限制**：限制资源访问和执行时间
4. **日志记录**：记录所有 MCP 操作以便审计

### 7.3 性能优化

1. **缓存机制**：缓存频繁访问的资源和工具
2. **连接池**：使用连接池管理外部资源连接
3. **异步处理**：使用异步操作提高响应速度（对于 WebFlux）
4. **资源释放**：及时释放请求处理过程中使用的资源

### 7.4 部署建议

1. **容器化部署**：使用 Docker 容器化部署便于管理
2. **负载均衡**：使用负载均衡器分发请求
3. **自动扩缩容**：配置自动扩缩容策略应对流量变化
4. **监控告警**：实施监控和告警机制确保服务稳定性

## 八、与其他 MCP 服务器类型的比较

| 特性 | 传统服务器 | Streamable-HTTP 服务器 | 无状态服务器 |
|------|------------|------------------------|--------------|
| 会话状态 | 支持 | 支持 | 不支持 |
| 实时通知 | 支持 | 支持 | 不支持 |
| 客户端消息 | 支持 | 支持 | 不支持 |
| 部署复杂度 | 中等 | 中等 | 简单 |
| 扩展性 | 中等 | 高 | 很高 |
| 适用场景 | 通用 | 需要通知 | 云原生、微服务 |

## 九、故障排除

### 9.1 常见问题

1. **服务器无法启动**：检查端口是否被占用，配置是否正确
2. **客户端无法连接**：检查网络连接，防火墙设置，URL 是否正确
3. **工具无法发现**：检查工具注解配置，确保工具被正确注册
4. **功能受限**：确认无状态服务器不支持的功能（如实时通知）

### 9.2 调试技巧

1. **启用详细日志**：
```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    org.springframework.ai.mcp.server: TRACE
```

2. **使用调试工具**监控请求和响应
3. **查看服务器日志**确认服务器状态和错误信息

## 十、参考资源

1. [Spring AI 官方文档 - MCP 集成](https://docs.spring.io/spring-ai/docs/current/reference/html/mcp.html)
2. [MCP GitHub 仓库](https://github.com/modelcontextprotocol)
3. [MCP 规范文档](https://spec.modelcontextprotocol.io/)
4. [Spring AI 示例项目](https://github.com/spring-projects/spring-ai/tree/main/samples/mcp)

通过本文档，您应该能够掌握 Spring AI 中无状态 HTTP 服务器的核心概念和使用方法，并能在实际项目中有效地应用这些技术来构建适合云原生和微服务架构的 MCP 服务。