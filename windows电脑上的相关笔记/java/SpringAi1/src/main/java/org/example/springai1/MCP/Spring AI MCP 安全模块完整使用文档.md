# Spring AI MCP 安全模块完整使用文档

## 一、核心概念

Spring AI MCP 安全模块为 Spring AI 中的模型上下文协议实现提供了全面的基于 OAuth 2.0 和基于 API 密钥的安全支持。这一由社区驱动的项目使开发者能够通过行业标准的认证和授权机制保护 MCP 服务器和客户端的安全。

该模块是 spring-ai-community/mcp-security 项目的一部分，目前仅支持 Spring AI 的 1.1.x 分支。这是一个社区驱动的项目，尚未获得 Spring AI 或 MCP 项目的官方认可。

### 1.1 MCP 安全模块的核心价值

MCP 安全模块为 AI 应用提供了以下核心能力：

1. **OAuth 2.0 认证支持**：为 MCP 服务器和客户端提供标准的 OAuth 2.0 认证机制
2. **API 密钥认证**：支持基于 API 密钥的简单认证方式
3. **细粒度访问控制**：能够对 MCP 工具和资源实现精细的访问控制
4. **授权服务器增强**：提供专为 MCP 工作流程设计的授权服务器功能

### 1.2 注意事项

该模块仍处于开发阶段，文档和 API 可能会在未来版本中发生变化。在生产环境中使用时需要谨慎评估。

## 二、模块组成

MCP 安全模块提供三个主要组件：

### 2.1 MCP 服务器安全

为 Spring AI 的 MCP 服务器提供 OAuth 2.0 资源服务器功能和基于 API 密钥的认证基础支持。

### 2.2 MCP 客户端安全

为 Spring AI 的 MCP 客户端提供 OAuth 2.0 支持，支持基于 HttpClient 的客户端（来自 spring-ai-starter-mcp-client）和基于 WebClient 的客户端（来自 spring-ai-starter-mcp-client-webflux）。

### 2.3 MCP 授权服务器

具有 MCP 特定功能的增强型 Spring 授权服务器。

## 三、MCP 服务器安全

MCP 服务器安全模块为 Spring AI 的 MCP 服务器提供 OAuth 2.0 资源服务器功能。它还提供了基于 API 密钥的认证基础支持。

### 3.1 依赖配置

该模块仅兼容基于 Spring WebMVC 的服务器。

#### 3.1.1 Maven 依赖

```xml
<dependencies>
    <dependency>
        <groupId>org.springaicommunity</groupId>
        <artifactId>mcp-server-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- OPTIONAL: For OAuth2 support -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
</dependencies>
```

#### 3.1.2 Gradle 依赖

```gradle
implementation 'org.springaicommunity:mcp-server-security'
implementation 'org.springframework.boot:spring-boot-starter-security'

// OPTIONAL: For OAuth2 support
implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
```

### 3.2 OAuth 2.0 配置

#### 3.2.1 基础 OAuth 2.0 设置

首先，在 `application.properties` 中配置：

```properties
spring.ai.mcp.server.name=my-cool-mcp-server
# Supported protocols: STREAMABLE, STATELESS
spring.ai.mcp.server.protocol=STREAMABLE
```

然后，使用 Spring Security 的标准 API 配合提供的 MCP 配置器进行安全配置：

```java
@Configuration
@EnableWebSecurity
class McpServerConfiguration {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUrl;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Enforce authentication with token on EVERY request
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // Configure OAuth2 on the MCP server
                .with(
                        McpServerOAuth2Configurer.mcpServerOAuth2(),
                        (mcpAuthorization) -> {
                            // REQUIRED: the issuerURI
                            mcpAuthorization.authorizationServer(issuerUrl);
                            // OPTIONAL: enforce the `aud` claim in the JWT token.
                            // Not all authorization servers support resource indicators,
                            // so it may be absent. Defaults to `false`.
                            // See RFC 8707 Resource Indicators for OAuth 2.0
                            // https://www.rfc-editor.org/rfc/rfc8707.html
                            mcpAuthorization.validateAudienceClaim(true);
                        }
                )
                .build();
    }
}
```

#### 3.2.2 仅保护工具调用

你可以配置服务器只保护工具调用，同时保持其他 MCP 操作（如 initialize 和 tools/list）为公开：

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enable annotation-driven security
class McpServerConfiguration {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUrl;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Open every request on the server
                .authorizeHttpRequests(auth -> {
                    auth.requestMatcher("/mcp").permitAll();
                    auth.anyRequest().authenticated();
                })
                // Configure OAuth2 on the MCP server
                .with(
                        McpResourceServerConfigurer.mcpServerOAuth2(),
                        (mcpAuthorization) -> {
                            // REQUIRED: the issuerURI
                            mcpAuthorization.authorizationServer(issuerUrl);
                        }
                )
                .build();
    }
}
```

然后，利用带有方法安全性的注释保护你的工具调用：

```java
@Service
public class MyToolsService {

    @PreAuthorize("isAuthenticated()")
    @McpTool(name = "greeter", description = "A tool that greets you, in the selected language")
    public String greet(
            @ToolParam(description = "The language for the greeting (example: english, french, ...)") String language
    ) {
        if (!StringUtils.hasText(language)) {
            language = "";
        }
        return switch (language.toLowerCase()) {
            case "english" -> "Hello you!";
            case "french" -> "Salut toi!";
            default -> "I don't understand language \"%s\". So I'm just going to say Hello!".formatted(language);
        };
    }
}
```

你也可以直接通过工具方法访问当前认证：

```java
@McpTool(name = "greeter", description = "A tool that greets the user by name, in the selected language")
@PreAuthorize("isAuthenticated()")
public String greet(
        @ToolParam(description = "The language for the greeting (example: english, french, ...)") String language
) {
    if (!StringUtils.hasText(language)) {
        language = "";
    }
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    var name = authentication.getName();
    return switch (language.toLowerCase()) {
        case "english" -> "Hello, %s!".formatted(name);
        case "french" -> "Salut %s!".formatted(name);
        default -> ("I don't understand language \"%s\". " +
                    "So I'm just going to say Hello %s!").formatted(language, name);
    };
}
```

### 3.3 API 密钥认证

MCP 服务器安全模块还支持 API 密钥认证。你需要提供自己的实现来存储 ApiKeyEntity 对象。

有一个示例实现，附带默认的 InMemoryApiKeyEntityRepository 和 ApiKeyEntityImpl。

它使用 bcrypt 来存储 API 密钥，计算成本高。它不适合高流量的生产使用。生产环境时，请实现自己的 ApiKeyEntityRepository。

```java
@Configuration
@EnableWebSecurity
class McpServerConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(authz -> authz.anyRequest().authenticated())
                .with(
                        mcpServerApiKey(),
                        (apiKey) -> {
                            // REQUIRED: the repo for API keys
                            apiKey.apiKeyRepository(apiKeyRepository());

                            // OPTIONAL: name of the header containing the API key.
                            // Here for example, api keys will be sent with "CUSTOM-API-KEY: <value>"
                            // Replaces .authenticationConverter(...) (see below)
                            //
                            // apiKey.headerName("CUSTOM-API-KEY");

                            // OPTIONAL: custom converter for transforming an http request
                            // into an authentication object. Useful when the header is
                            // "Authorization: Bearer <value>".
                            // Replaces .headerName(...) (see above)
                            //
                            // apiKey.authenticationConverter(request -> {
                            //     var key = extractKey(request);
                            //     return ApiKeyAuthenticationToken.unauthenticated(key);
                            // });
                        }
                )
                .build();
    }

    /**
     * Provide a repository of {@link ApiKeyEntity}.
     */
    private ApiKeyEntityRepository<ApiKeyEntityImpl> apiKeyRepository() {
        var apiKey = ApiKeyEntityImpl.builder()
                .name("test api key")
                .id("api01")
                .secret("mycustomapikey")
                .build();

        return new InMemoryApiKeyEntityRepository<>(List.of(apiKey));
    }
}
```

通过这种配置，你可以用 `X-API-key: api01.mycustomapikey` 头部调用你的 MCP 服务器。

### 3.4 已知的局限性

1. 已弃用的 SSE 传输不被支持。使用可流式 HTTP 或无状态传输。
2. 不支持基于 WebFlux 的服务器。
3. 不支持不透明令牌。请使用 JWT。

## 四、MCP 客户端安全

MCP 客户端安全模块为 Spring AI 的 MCP 客户端提供了 OAuth 2.0 支持，支持基于 HttpClient 的客户端（来自 spring-ai-starter-mcp-client）和基于 WebClient 的客户端（来自 spring-ai-starter-mcp-client-webflux）。

该模块仅支持 McpSyncClient。

### 4.1 依赖配置

#### 4.1.1 Maven 依赖

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>mcp-client-security</artifactId>
</dependency>
```

#### 4.1.2 Gradle 依赖

```gradle
implementation 'org.springaicommunity:mcp-client-security'
```

### 4.2 授权流程

有三种 OAuth 2.0 流程可用于获取令牌：

1. **授权代码流程** - 当每个 MCP 请求都在用户请求的上下文中发出时，用于用户级权限
2. **客户端凭证流程** - 适用于机器对机器的场景，且无人工参与
3. **混合流** - 结合两种流程，适用于某些操作（如 initialize 或 tools/list）在无用户的情况下进行，但工具调用需要用户级权限

当你拥有用户级权限且所有 MCP 请求都发生在用户上下文中时，使用授权代码流程。使用客户端凭证进行机器间通信。使用 Spring Boot 属性进行 MCP 客户端配置时，请使用混合流程，因为工具发现是在启动时进行的，无需用户参与。

### 4.3 常见配置

对于所有流程，请在 `application.properties` 中配置：

```properties
# Ensure MCP clients are sync
spring.ai.mcp.client.type=SYNC

# For authorization_code or hybrid flow
spring.security.oauth2.client.registration.authserver.client-id=<THE CLIENT ID>
spring.security.oauth2.client.registration.authserver.client-secret=<THE CLIENT SECRET>
spring.security.oauth2.client.registration.authserver.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.authserver.provider=authserver

# For client_credentials or hybrid flow
spring.security.oauth2.client.registration.authserver-client-credentials.client-id=<THE CLIENT ID>
spring.security.oauth2.client.registration.authserver-client-credentials.client-secret=<THE CLIENT SECRET>
spring.security.oauth2.client.registration.authserver-client-credentials.authorization-grant-type=client_credentials
spring.security.oauth2.client.registration.authserver-client-credentials.provider=authserver

# Authorization server configuration
spring.security.oauth2.client.provider.authserver.issuer-uri=<THE ISSUER URI OF YOUR AUTH SERVER>
```

然后，创建一个配置类来激活 OAuth2 客户端能力：

```java
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // in this example, the client app has no security on its endpoints
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // turn on OAuth2 support
                .oauth2Client(Customizer.withDefaults())
                .build();
    }
}
```

### 4.4 基于 HttpClient 的客户端

使用 spring-ai-starter-mcp-client 时，配置 Bean：

```java
@Configuration
class McpConfiguration {

    @Bean
    McpSyncClientCustomizer syncClientCustomizer() {
        return (name, syncSpec) ->
                syncSpec.transportContextProvider(
                        new AuthenticationMcpTransportContextProvider()
                );
    }

    @Bean
    McpSyncHttpClientRequestCustomizer requestCustomizer(
            OAuth2AuthorizedClientManager clientManager
    ) {
        // The clientRegistration name, "authserver",
        // must match the name in application.properties
        return new OAuth2AuthorizationCodeSyncHttpRequestCustomizer(
                clientManager,
                "authserver"
        );
    }
}
```

#### 4.4.1 可用的定制器

- `OAuth2AuthorizationCodeSyncHttpRequestCustomizer` - 用于授权码流程
- `OAuth2ClientCredentialsSyncHttpRequestCustomizer` - 用于客户端凭证流程
- `OAuth2HybridSyncHttpRequestCustomizer` - 混合流

### 4.5 基于 WebClient 的客户端

使用 spring-ai-starter-mcp-client-webflux 时，配置一个 WebClient.Builder 与 MCP ExchangeFilterFunction：

```java
@Configuration
class McpConfiguration {

    @Bean
    McpSyncClientCustomizer syncClientCustomizer() {
        return (name, syncSpec) ->
                syncSpec.transportContextProvider(
                        new AuthenticationMcpTransportContextProvider()
                );
    }

    @Bean
    WebClient.Builder mcpWebClientBuilder(OAuth2AuthorizedClientManager clientManager) {
        // The clientRegistration name, "authserver", must match the name in application.properties
        return WebClient.builder().filter(
                new McpOAuth2AuthorizationCodeExchangeFilterFunction(
                        clientManager,
                        "authserver"
                )
        );
    }
}
```

#### 4.5.1 可用的过滤功能

- `McpOAuth2AuthorizationCodeExchangeFilterFunction` - 用于授权码流程
- `McpOAuth2ClientCredentialsExchangeFilterFunction` - 用于客户端凭证流程
- `McpOAuth2HybridExchangeFilterFunction` - 混合流

### 4.6 如何绕过 Spring AI 自动配置

Spring AI 的自动配置在启动时初始化 MCP 客户端，这可能导致基于用户的身份验证出现问题。为了避免这种情况：

#### 4.6.1 选项1：禁用 @Tool 自动配置

通过发布一个空 Bean 来禁用 Spring AI 的自动配置：

```java
@Configuration
public class McpConfiguration {

    @Bean
    ToolCallbackResolver resolver() {
        return new StaticToolCallbackResolver(List.of());
    }
}
```

#### 4.6.2 选项2：程序化客户端配置

通过程序化配置 MCP 客户端，而不是使用 Spring Boot 属性。对于基于 HttpClient 的客户端：

```java
@Bean
McpSyncClient client(
        ObjectMapper objectMapper,
        McpSyncHttpClientRequestCustomizer requestCustomizer,
        McpClientCommonProperties commonProps
) {
    var transport = HttpClientStreamableHttpTransport.builder(mcpServerUrl)
            .clientBuilder(HttpClient.newBuilder())
            .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
            .httpRequestCustomizer(requestCustomizer)
            .build();

    var clientInfo = new McpSchema.Implementation("client-name", commonProps.getVersion());

    return McpClient.sync(transport)
            .clientInfo(clientInfo)
            .requestTimeout(commonProps.getRequestTimeout())
            .transportContextProvider(new AuthenticationMcpTransportContextProvider())
            .build();
}
```

对于基于 WebClient 的客户端：

```java
@Bean
McpSyncClient client(
        WebClient.Builder mcpWebClientBuilder,
        ObjectMapper objectMapper,
        McpClientCommonProperties commonProperties
) {
    var builder = mcpWebClientBuilder.baseUrl(mcpServerUrl);
    var transport = WebClientStreamableHttpTransport.builder(builder)
            .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
            .build();

    var clientInfo = new McpSchema.Implementation("clientName", commonProperties.getVersion());

    return McpClient.sync(transport)
            .clientInfo(clientInfo)
            .requestTimeout(commonProperties.getRequestTimeout())
            .transportContextProvider(new AuthenticationMcpTransportContextProvider())
            .build();
}
```

然后把客户端添加到你的聊天客户端：

```java
var chatResponse = chatClient.prompt("Prompt the LLM to do the thing")
        .toolCallbacks(new SyncMcpToolCallbackProvider(mcpClient1, mcpClient2, mcpClient3))
        .call()
        .content();
```

### 4.7 已知的局限性

1. 不支持 Spring WebFlux 服务器。
2. Spring AI 自动配置在应用启动时初始化 MCP 客户端，因此需要通过用户验证采取变通方法。
3. 与服务器模块不同，客户端实现支持 SSE 传输，支持 HttpClient 和 WebClient。

## 五、MCP 授权服务器

MCP 授权服务器模块增强了 Spring Security 的 OAuth 2.0 授权服务器，增加了与 MCP 授权规范相关的功能，如动态客户端注册和资源指示器。

### 5.1 依赖配置

#### 5.1.1 Maven 依赖

```xml
<dependency>
    <groupId>org.springaicommunity</groupId>
    <artifactId>mcp-authorization-server</artifactId>
</dependency>
```

#### 5.1.2 Gradle 依赖

```gradle
implementation 'org.springaicommunity:mcp-authorization-server'
```

### 5.2 配置

在 `application.yml` 中配置：

```yaml
spring:
  application:
    name: sample-authorization-server
  security:
    oauth2:
      authorizationserver:
        client:
          default-client:
            token:
              access-token-time-to-live: 1h
            registration:
              client-id: "default-client"
              client-secret: "{noop}default-secret"
              client-authentication-methods:
                - "client_secret_basic"
                - "none"
              authorization-grant-types:
                - "authorization_code"
                - "client_credentials"
              redirect-uris:
                - "http://127.0.0.1:8080/authorize/oauth2/code/authserver"
                - "http://localhost:8080/authorize/oauth2/code/authserver"
                # mcp-inspector
                - "http://localhost:6274/oauth/callback"
                # claude code
                - "https://claude.ai/api/mcp/auth_callback"
    user:
      # A single user, named "user"
      name: user
      password: password

server:
  servlet:
    session:
      cookie:
        # Override the default cookie name (JSESSIONID).
        # This allows running multiple Spring apps on localhost, and they'll each have their own cookie.
        # Otherwise, since the cookies do not take the port into account, they are confused.
        name: MCP_AUTHORIZATION_SERVER_SESSIONID
```

然后通过安全过滤链激活授权服务器功能：

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
            // all requests must be authenticated
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            // enable authorization server customizations
            .with(McpAuthorizationServerConfigurer.mcpAuthorizationServer(), withDefaults())
            // enable form-based login, for user "user"/"password"
            .formLogin(withDefaults())
            .build();
}
```

### 5.3 已知的局限性

1. 不支持 Spring WebFlux 服务器。
2. 每个客户端都支持所有 resource identifier。

## 六、最佳实践

### 6.1 安全性考虑

1. **访问控制**：实施适当的认证和授权机制
2. **输入验证**：对所有输入进行严格验证
3. **资源限制**：限制资源访问和执行时间
4. **日志记录**：记录所有 MCP 操作以便审计

### 6.2 性能优化

1. **连接池**：使用连接池管理 MCP 连接
2. **缓存机制**：缓存频繁访问的资源和工具
3. **异步处理**：使用异步操作提高响应速度
4. **批量操作**：尽可能使用批量操作减少网络往返

### 6.3 错误处理

1. **异常捕获**：捕获并处理 MCP 相关异常
2. **重试机制**：实现适当的重试逻辑
3. **降级策略**：在 MCP 服务不可用时提供降级方案
4. **监控告警**：监控 MCP 服务状态并设置告警

## 七、故障排除

### 7.1 常见问题

1. **认证失败**：检查客户端 ID 和密钥是否正确，授权服务器是否可达
2. **令牌无效**：确认令牌未过期，受众声明是否正确
3. **权限不足**：检查用户权限和角色配置
4. **连接问题**：检查网络连接和防火墙设置

### 7.2 调试技巧

1. **启用详细日志**：
```yaml
logging:
  level:
    org.springframework.ai.mcp: DEBUG
    org.springframework.security: DEBUG
```

2. **使用调试工具**监控请求和响应
3. **查看授权服务器日志**确认认证和授权过程

## 八、参考资源

1. [Spring AI 官方文档](https://docs.spring.io/spring-ai/docs/current/reference/html/)
2. [Spring Security 官方文档](https://docs.spring.io/spring-security/reference/)
3. [OAuth 2.0 RFC 6749](https://www.rfc-editor.org/rfc/rfc6749.html)
4. [RFC 8707 Resource Indicators for OAuth 2.0](https://www.rfc-editor.org/rfc/rfc8707.html)

通过本文档，您应该能够掌握 Spring AI 中 MCP 安全模块的核心概念和使用方法，并能在实际项目中有效地应用这些技术来构建安全的 MCP 应用。