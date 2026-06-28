# Spring AI 聊天内存（Chat Memory）完整使用文档

在构建对话式AI应用时，保持对话上下文是提供连贯用户体验的关键。Spring AI提供了强大的聊天内存功能，使得在多次交互中维护对话状态成为可能。本文档将详细介绍如何使用Spring AI的聊天内存功能。

## 核心概念

在深入了解实现细节之前，我们需要明确两个重要概念：

1. **聊天记忆（Chat Memory）**：大型语言模型保留并利用的信息，用于在整个对话过程中保持上下文意识。

2. **聊天历史（Chat History）**：整个对话历史，包括用户与模型之间所有交换的信息。

聊天内存抽象主要用于管理当前对话上下文，而不是存储完整的聊天历史记录。如果需要完整记录所有消息交换，建议使用其他存储方案，如Spring Data。

## 快速入门

Spring AI会自动配置一个[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53) bean，您可以直接在应用中使用。默认情况下，它使用内存存储库来存储消息，并通过[MessageWindowChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)实现管理对话历史。

```java
@Autowired
ChatMemory chatMemory;
```

如果已经配置了不同的存储库（例如Cassandra、JDBC或Neo4j），Spring AI会使用那个存储库。

## 内存类型

Spring AI的[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)抽象允许您实现各种类型的内存以满足不同的使用场景。

### 消息窗口聊天内存（MessageWindowChatMemory）

[MessageWindowChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)维护一个有限大小的消息窗口。当消息数量超过最大值时，旧消息会被删除，但系统消息会被保留。

```java
// 创建一个最大消息数为10的聊天内存
MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(10)
    .build();
```

这是Spring AI用来自动配置[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53) bean的默认消息类型。

## 存储库（Repositories）

Spring AI提供了多种存储聊天内存的抽象化实现。您可以根据应用需求选择合适的存储方案。

### 内存存储库（InMemoryChatMemoryRepository）

[InMemoryChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/InMemoryChatMemory.java#L10-L55)通过ConcurrentHashMap在内存中存储消息。

默认情况下，如果没有其他存储库配置，Spring AI会自动配置一个[InMemoryChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/InMemoryChatMemory.java#L10-L55)类型的bean：

```java
@Autowired
ChatMemoryRepository chatMemoryRepository;
```

如果您希望手动创建，可以这样做：

```java
ChatMemoryRepository repository = new InMemoryChatMemoryRepository();
```

### JDBC存储库（JdbcChatMemoryRepository）

[JdbcChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)是一个内置实现，使用JDBC将消息存储在关系数据库中。

#### 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-jdbc'
```

#### 使用方法

Spring AI提供自动配置，您可以直接在应用中使用：

```java
@Autowired
JdbcChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

如果您希望手动创建，可以通过提供[JdbcTemplate](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L21-L21)和[JdbcChatMemoryRepositoryDialect](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)实例：

```java
ChatMemoryRepository chatMemoryRepository = JdbcChatMemoryRepository.builder()
    .jdbcTemplate(jdbcTemplate)
    .dialect(new PostgresChatMemoryRepositoryDialect())
    .build();

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

#### 支持的数据库

Spring AI通过方言抽象支持多个关系数据库：

- PostgreSQL
- MySQL / MariaDB
- SQL Server
- HSQLDB
- Oracle

使用时，可以通过JDBC URL自动检测正确的方言。

#### 配置属性

| 属性 | 描述 | 默认值 |
|------|------|--------|
| spring.ai.chat.memory.repository.jdbc.initialize-schema | 控制何时初始化模式（embedded, always, never） | embedded |
| spring.ai.chat.memory.repository.jdbc.schema | 用于初始化的模式脚本位置 | classpath:org/springframework/ai/chat/memory/repository/jdbc/schema-@@platform@@.sql |
| spring.ai.chat.memory.repository.jdbc.platform | 用于初始化脚本的平台 | 自动检测 |

#### 模式初始化

自动配置会在启动时自动创建表，使用厂商专用的SQL脚本来管理数据库。默认情况下，模式初始化仅运行于嵌入式数据库。

```properties
# 仅对嵌入式数据库初始化（默认）
spring.ai.chat.memory.repository.jdbc.initialize-schema=embedded

# 总是初始化
spring.ai.chat.memory.repository.jdbc.initialize-schema=always

# 从不初始化（适用于Flyway/Liquibase）
spring.ai.chat.memory.repository.jdbc.initialize-schema=never
```

#### 扩展方言

要支持新数据库，实现[JdbcChatMemoryRepositoryDialect](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)接口并提供SQL用于选择、插入和删除消息：

```java
ChatMemoryRepository chatMemoryRepository = JdbcChatMemoryRepository.builder()
    .jdbcTemplate(jdbcTemplate)
    .dialect(new MyCustomDbDialect())
    .build();
```

### Cassandra存储库（CassandraChatMemoryRepository）

[CassandraChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)使用Apache Cassandra来存储消息，适合需要高可用性、耐用性和扩展性的应用。

#### 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-cassandra</artifactId>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-cassandra'
```

#### 使用方法

Spring AI提供自动配置：

```java
@Autowired
CassandraChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

手动创建：

```java
ChatMemoryRepository chatMemoryRepository = CassandraChatMemoryRepository
    .create(CassandraChatMemoryRepositoryConfig.builder().withCqlSession(cqlSession));

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

#### 配置属性

| 属性 | 描述 | 默认值 |
|------|------|--------|
| spring.cassandra.contactPoints | Cassandra集群地址 | 127.0.0.1 |
| spring.cassandra.port | Cassandra端口 | 9042 |
| spring.cassandra.localDatacenter | 数据中心 | datacenter1 |
| spring.ai.chat.memory.cassandra.time-to-live | 消息TTL | - |
| spring.ai.chat.memory.cassandra.keyspace | Keyspace | springframework |
| spring.ai.chat.memory.cassandra.table | 表名 | ai_chat_memory |
| spring.ai.chat.memory.cassandra.initialize-schema | 是否初始化模式 | true |

### Neo4j存储库（Neo4jChatMemoryRepository）

[Neo4jChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)利用Neo4j将聊天消息作为节点和关系存储在属性图数据库中。

#### 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-neo4j</artifactId>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-neo4j'
```

#### 使用方法

Spring AI提供自动配置：

```java
@Autowired
Neo4jChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

手动创建：

```java
ChatMemoryRepository chatMemoryRepository = Neo4jChatMemoryRepository.builder()
    .driver(driver)
    .build();

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

#### 配置属性

| 属性 | 描述 | 默认值 |
|------|------|--------|
| spring.ai.chat.memory.repository.neo4j.sessionLabel | 会话节点标签 | Session |
| spring.ai.chat.memory.repository.neo4j.messageLabel | 消息节点标签 | Message |
| spring.ai.chat.memory.repository.neo4j.toolCallLabel | 工具调用节点标签 | ToolCall |
| spring.ai.chat.memory.repository.neo4j.metadataLabel | 元数据节点标签 | Metadata |
| spring.ai.chat.memory.repository.neo4j.toolResponseLabel | 工具响应节点标签 | ToolResponse |
| spring.ai.chat.memory.repository.neo4j.mediaLabel | 媒体节点标签 | Media |

### CosmosDB存储库（CosmosDBChatMemoryRepository）

[CosmosDBChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)使用Azure Cosmos DB NoSQL API来存储消息。

#### 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-cosmos-db</artifactId>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-cosmos-db'
```

#### 使用方法

Spring AI提供自动配置：

```java
@Autowired
CosmosDBChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

手动创建：

```java
ChatMemoryRepository chatMemoryRepository = CosmosDBChatMemoryRepository
    .create(CosmosDBChatMemoryRepositoryConfig.builder()
        .withCosmosClient(cosmosAsyncClient)
        .withDatabaseName("chat-memory-db")
        .withContainerName("conversations")
        .build());

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

#### 配置属性

| 属性 | 描述 | 默认值 |
|------|------|--------|
| spring.ai.chat.memory.repository.cosmosdb.endpoint | Cosmos DB endpoint URI | - |
| spring.ai.chat.memory.repository.cosmosdb.key | Cosmos DB密钥 | - |
| spring.ai.chat.memory.repository.cosmosdb.connection-mode | 连接模式（direct/gateway） | gateway |
| spring.ai.chat.memory.repository.cosmosdb.database-name | 数据库名称 | SpringAIChatMemory |
| spring.ai.chat.memory.repository.cosmosdb.container-name | 容器名称 | ChatMemory |
| spring.ai.chat.memory.repository.cosmosdb.partition-key-path | 分区键路径 | /conversationId |

#### 认证

Cosmos DB聊天内存存储库支持两种认证方法：

1. **基于密钥的认证**：向`spring.ai.chat.memory.repository.cosmosdb.key`属性提供您的Cosmos DB主密钥或副密钥。

2. **Azure身份认证**：当没有提供密钥时，存储库使用Azure身份（DefaultAzureCredential）来通过托管身份、服务主体或其他Azure凭证来源进行认证。

#### 模式初始化

如果指定的数据库和容器不存在，自动配置会自动创建它们。容器配置中，会话ID为分区键（/conversationId），以确保聊天内存操作的最佳性能。无需手动设置模式。

### MongoDB存储库（MongoChatMemoryRepository）

[MongoChatMemoryRepository](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)是一个内置实现，使用MongoDB来存储消息。

#### 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-chat-memory-repository-mongodb</artifactId>
</dependency>
```

**Gradle:**
```gradle
implementation 'org.springframework.ai:spring-ai-starter-model-chat-memory-repository-mongodb'
```

#### 使用方法

Spring AI提供自动配置：

```java
@Autowired
MongoChatMemoryRepository chatMemoryRepository;

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

手动创建：

```java
ChatMemoryRepository chatMemoryRepository = MongoChatMemoryRepository.builder()
    .mongoTemplate(mongoTemplate)
    .build();

ChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(chatMemoryRepository)
    .maxMessages(10)
    .build();
```

#### 配置属性

| 属性 | 描述 | 默认值 |
|------|------|--------|
| spring.ai.chat.memory.repository.mongo.create-indices | 是否在启动时自动创建索引 | false |
| spring.ai.chat.memory.repository.mongo.ttl | 消息存活时间（秒），0表示无限期存储 | 0 |

#### 集合初始化

如果集合还不存在，自动配置会在启动时自动创建`ai_chat_memory`集合。

## 聊天客户端中的内存使用

使用ChatClient API时，您可以提供[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)实现，以在多次交互中保持对话上下文。

Spring AI提供了几个内置的顾问，您可以根据需求配置ChatClient的内存行为。

### MessageChatMemoryAdvisor

[MessageChatMemoryAdvisor](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)使用提供的[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)实现管理对话内存。每次互动时，它会从内存中检索对话历史，并将其作为一组消息包含在提示中。

```java
ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    .build();
```

当对ChatClient执行调用时，内存将由[MessageChatMemoryAdvisor](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)自动管理。对话历史将根据指定的对话ID从内存中检索：

```java
String conversationId = "007";

chatClient.prompt()
    .user("我有编程许可吗？")
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
    .call()
    .content();
```

### PromptChatMemoryAdvisor

[PromptChatMemoryAdvisor](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)使用提供的[ChatMemory](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/Repository/ChatHistoryReposity.java#L13-L53)实现管理对话内存。每次交互时，它会从内存中检索对话历史，并以纯文本形式附加到系统提示中。

#### 自定义模板

它使用默认模板来增强系统消息，并用检索到的对话内存。您可以通过构建器方法自定义自己的[PromptTemplate](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/结构化输出/Demo1.java#L45-L45)对象来自定义这种行为。

```java
PromptTemplate customTemplate = new PromptTemplate("""
    你是 helpful assistant. 请使用以下对话历史作为上下文：
    
    {memory}
    
    用户问题: {instructions}
    """);
    
ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(PromptChatMemoryAdvisor.builder()
        .chatMemory(chatMemory)
        .promptTemplate(customTemplate)
        .build())
    .build();
```

这里提供的方案可定制顾问如何将检索到的内存与系统消息合并。这与在ChatClient自身配置一个TemplateRenderer（使用ChatClient.templateRenderer()）不同，后者会影响在顾问运行前对初始用户/系统提示内容的渲染。

#### 模板要求

自定义[PromptTemplate](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/结构化输出/Demo1.java#L45-L45)可以使用任何实现（默认情况下，它是基于StringTemplate引擎的）。重要要求是模板必须包含以下两个占位符：

1. `{instructions}`占位符，用于接收原始系统消息。
2. `{memory}`占位符，用于接收检索到的对话记忆。

### VectorStoreChatMemoryAdvisor

[VectorStoreChatMemoryAdvisor](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L16-L16)使用提供的VectorStore实现管理对话内存。每次交互中，从向量存储中获取对话历史，并将其作为明文附加到系统消息中。

#### 自定义模板

它使用默认模板来增强系统消息，并用检索到的对话内存。您可以通过构建器方法自定义自己的[PromptTemplate](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/结构化输出/Demo1.java#L45-L45)对象来自定义这种行为。

## 实战示例

### 示例1：简单的聊天应用

```java
@RestController
@RequestMapping("/chat")
public class ChatController {
    
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    
    public ChatController(ChatClient.Builder chatClientBuilder) {
        this.chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(10)
            .build();
            
        this.chatClient = chatClientBuilder
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }
    
    @PostMapping("/{conversationId}")
    public String chat(@PathVariable String conversationId, @RequestBody String userMessage) {
        return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
    }
    
    @DeleteMapping("/{conversationId}")
    public void clearMemory(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
    }
}
```

### 示例2：使用JDBC存储的聊天应用

```java
@Configuration
public class ChatMemoryConfig {
    
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repository)
            .maxMessages(20)
            .build();
    }
}

@RestController
@RequestMapping("/persistent-chat")
public class PersistentChatController {
    
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    
    public PersistentChatController(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.chatClient = chatClientBuilder
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }
    
    @PostMapping("/{conversationId}")
    public String chat(@PathVariable String conversationId, @RequestBody String userMessage) {
        return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
    }
}
```

### 示例3：多用户聊天系统

```java
@Service
public class MultiUserChatService {
    
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    
    public MultiUserChatService(ChatClient.Builder chatClientBuilder) {
        // 为每个用户维护独立的聊天内存
        this.chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(15)
            .build();
            
        this.chatClient = chatClientBuilder
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
    }
    
    public String chat(String userId, String sessionId, String userMessage) {
        // 使用用户ID和会话ID组合作为对话标识
        String conversationId = userId + "_" + sessionId;
        
        return chatClient.prompt()
            .user(userMessage)
            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
            .call()
            .content();
    }
    
    public List<Message> getConversationHistory(String userId, String sessionId) {
        String conversationId = userId + "_" + sessionId;
        return chatMemory.getMessages(conversationId);
    }
    
    public void clearConversation(String userId, String sessionId) {
        String conversationId = userId + "_" + sessionId;
        chatMemory.clear(conversationId);
    }
}
```

## 最佳实践

### 1. 选择合适的存储库

- **内存存储**：适用于简单应用或原型开发，重启后数据丢失
- **关系数据库**：适用于需要ACID特性和复杂查询的场景
- **NoSQL数据库**：适用于高并发、大规模分布式部署

### 2. 设置合适的消息窗口大小

根据应用需求和成本考虑设置合理的[maxMessages](file:///E:/java/SpringAi1/src/main/java/org/example/springai1/util/ReReadingAdvisor.java#L23-L23)值：

```java
// 对于简单对话，较小的窗口即可
MessageWindowChatMemory smallWindow = MessageWindowChatMemory.builder()
    .maxMessages(5)
    .build();

// 对于复杂对话，需要更大的上下文窗口
MessageWindowChatMemory largeWindow = MessageWindowChatMemory.builder()
    .maxMessages(50)
    .build();
```

### 3. 合理清理内存

及时清理不再需要的对话历史，避免内存泄漏：

```java
// 在会话结束时清理内存
chatMemory.clear(conversationId);

// 定期清理过期会话
@Scheduled(fixedRate = 3600000) // 每小时执行一次
public void cleanupExpiredSessions() {
    // 实现清理逻辑
}
```

### 4. 异常处理

在使用聊天内存时要考虑异常处理：

```java
try {
    String response = chatClient.prompt()
        .user(userMessage)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .call()
        .content();
    return response;
} catch (Exception e) {
    // 清理可能损坏的内存状态
    chatMemory.clear(conversationId);
    throw new RuntimeException("聊天处理失败", e);
}
```

## 常见问题解答

### Q1：聊天内存和聊天历史有什么区别？

聊天内存主要用于维护当前对话的上下文，以便LLM能够理解对话的连续性。而聊天历史是指完整记录所有对话交互的历史数据，通常用于分析、审计或训练目的。

### Q2：如何选择合适的存储方案？

- **开发/测试环境**：使用内存存储库，简单快捷
- **生产环境**：根据数据持久性、扩展性和查询需求选择JDBC、Cassandra、MongoDB等
- **云原生应用**：考虑CosmosDB等云服务

### Q3：消息窗口大小应该如何设置？

消息窗口大小取决于：
1. LLM的上下文长度限制
2. 应用所需的上下文深度
3. 成本考虑（更大的上下文意味着更高的计算成本）

### Q4：如何处理内存泄漏问题？

定期清理不再需要的对话历史，实现适当的会话过期机制，并监控内存使用情况。

## 参考资源

1. [Spring AI官方文档 - 聊天内存](https://docs.spring.io/spring-ai/docs/current/reference/html/chat-memory.html)
2. [Spring AI GitHub仓库](https://github.com/spring-projects/spring-ai)
3. [Spring AI示例项目](https://github.com/spring-projects/spring-ai/tree/main/samples)

通过本文档，您应该能够掌握Spring AI聊天内存功能的核心概念和使用方法，并能在实际项目中有效地应用这些技术来构建具备上下文感知能力的对话式AI应用。