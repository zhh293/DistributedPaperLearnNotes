# Spring Expression Language (SpEL) 应用场景和开发工具

## 目录
1. [日常开发中的应用场景](#日常开发中的应用场景)
2. [开发所需的工具](#开发所需的工具)
3. [最佳实践建议](#最佳实践建议)

---

## 日常开发中的应用场景

### 1. 配置管理

#### 动态属性注入
```java
@Component
public class ApplicationConfig {
    // 从配置文件读取默认值，支持表达式计算
    @Value("#{environment.getProperty('app.max-users') ?: 100}")
    private int maxUsers;
    
    // 基于环境的配置
    @Value("#{systemProperties['env'] == 'prod' ? '${prod.db.url}' : '${dev.db.url}'}")
    private String databaseUrl;
    
    // 计算属性值
    @Value("#{${app.base-threads} * ${app.thread-multiplier}}")
    private int threadPoolSize;
}
```

#### 条件化 Bean 创建
```java
@Component
@ConditionalOnExpression("#{environment.getProperty('cache.enabled', 'false') == 'true'}")
public class CacheManager {
    // 仅当配置启用缓存时才创建这个 Bean
}

@Component
@ConditionalOnExpression("#{T(org.apache.commons.lang3.StringUtils).isNotBlank('${external.api.key:}')}")
public class ExternalApiClient {
    // 仅当配置了 API Key 时才创建客户端
}
```

### 2. 安全控制

#### 方法级别安全
```java
@Service
public class DocumentService {
    @PreAuthorize("#document.owner == authentication.name or hasRole('ADMIN')")
    public void updateDocument(Document document) {
        documentRepository.save(document);
    }
    
    @PostFilter("hasRole('ADMIN') or filterObject.department == principal.department")
    public List<Document> getDocuments() {
        return documentRepository.findAll();
    }
}
```

#### Web 安全路径控制
```java
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    // 使用 SpEL 控制谁可以访问特定文档
    @GetMapping("/{id}")
    @PostAuthorize("@documentSecurity.checkAccess(returnObject, authentication)")
    public Document getDocument(@PathVariable Long id) {
        return documentService.findById(id);
    }
}

@Component("documentSecurity")
public class DocumentSecurityChecker {
    public boolean checkAccess(Document document, Authentication authentication) {
        return document.getOwner().equals(authentication.getName()) || 
               authentication.getAuthorities().stream()
                   .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
```

### 3. 数据处理和转换

#### 数据绑定时的动态计算
```java
public class OrderForm {
    private BigDecimal price;
    private Integer quantity;
    
    // 动态计算总金额
    @Value("#{price * quantity}")
    private BigDecimal totalAmount;
    
    // 根据其他字段计算折扣
    @Value("#{orderType == 'VIP' ? (price * quantity * 0.1) : 0}")
    private BigDecimal discount;
}
```

#### 查询条件构建
```java
@Repository
public class UserRepository {
    public List<User> findUsersByCriteria(UserSearchCriteria criteria) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<User> query = cb.createQuery(User.class);
        Root<User> root = query.from(User.class);
        
        // 使用 SpEL 构建动态查询条件
        if (StringUtils.hasText(criteria.getSearchExpression())) {
            // 解析并应用搜索表达式
            Expression<Boolean> expr = parseExpression(criteria.getSearchExpression(), root);
            query.where(expr);
        }
        
        return entityManager.createQuery(query).getResultList();
    }
}
```

### 4. 消息路由和过滤

#### Spring Integration 中的消息路由
```java
@Configuration
@EnableIntegration
public class MessageRoutingConfig {
    @Bean
    @Router(inputChannel = "inputChannel")
    public ExpressionEvaluatingRouter router() {
        ExpressionEvaluatingRouter router = new ExpressionEvaluatingRouter(
            new SpelExpressionParser().parseExpression("payload.type"));
        router.setResolutionRequired(false);
        router.setDefaultOutputChannelName("defaultChannel");
        router.setChannelMapping("ORDER", "orderChannel");
        router.setChannelMapping("PAYMENT", "paymentChannel");
        return router;
    }
}
```

#### 消息过滤
```java
@Bean
@Filter(inputChannel = "inputChannel", outputChannel = "outputChannel")
public ExpressionEvaluatingSelector filter() {
    return new ExpressionEvaluatingSelector(
        new SpelExpressionParser().parseExpression("payload.priority > 5"));
}
```

### 5. 模板渲染

#### Thymeleaf 模板中的表达式
```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>
    <div th:text="${user.name}">
        用户名将显示在这里
    </div>
    
    <!-- 条件渲染 -->
    <div th:if="${user.age >= 18}">
        成年用户专属内容
    </div>
    
    <!-- 循环渲染 -->
    <ul>
        <li th:each="item : ${items}" th:text="${item.name}"></li>
    </ul>
    
    <!-- URL 参数构建 -->
    <a th:href="@{/user/{id}(id=${user.id}, tab='profile')}">用户资料</a>
</body>
</html>
```

### 6. 定时任务配置

```java
@Component
public class ScheduledTasks {
    // 根据配置文件动态设置 cron 表达式
    @Scheduled(cron = "#{systemProperties['report.cron'] ?: '${default.report.cron:0 0 1 * * ?}'}")
    public void generateDailyReport() {
        // 生成日报
    }
    
    // 根据环境决定是否执行任务
    @Scheduled(fixedRateString = "#{systemProperties['heartbeat.interval'] ?: '30000'}")
    @ConditionalOnExpression("#{systemProperties['heartbeat.enabled'] != 'false'}")
    public void sendHeartbeat() {
        // 发送心跳
    }
}
```

### 7. 测试中的 Mock 数据生成

```java
@TestConfiguration
public class TestDataConfig {
    @Bean
    @Primary
    public UserService mockUserService() {
        UserService mock = Mockito.mock(UserService.class);
        
        // 使用 SpEL 生成测试数据
        given(mock.getCurrentUser())
            .willReturn(User.builder()
                .id(1L)
                .name("testUser")
                .email("test@example.com")
                .build());
                
        return mock;
    }
}
```

## 开发所需的工具

### 1. IDE 插件和支持

#### IntelliJ IDEA
IntelliJ IDEA 提供了对 SpEL 的良好支持：
- 语法高亮
- 代码补全
- 错误检测
- 表达式求值

```java
// IDEA 会在编辑器中提供 SpEL 表达式的实时反馈
@Value("#{systemProperties['java.version'].toUpperCase()}") 
private String javaVersionUpper;
```

#### Eclipse
Eclipse 通过 Spring Tools 提供 SpEL 支持：
- 基本语法高亮
- 简单的错误提示

#### VS Code
通过 Spring Boot Extension Pack 提供支持：
- 语法着色
- 基本智能感知

### 2. 在线表达式测试工具

#### Spring Expression Tester
可以使用在线工具来测试 SpEL 表达式：
```java
// 示例表达式可以在在线工具中快速测试
String expression = "T(Math).pow(2, 3) + #root.size()";
EvaluationContext context = new StandardEvaluationContext(Arrays.asList(1, 2, 3));
ExpressionParser parser = new SpelExpressionParser();
Object result = parser.parseExpression(expression).getValue(context);
// 结果为 11.0
```

#### 自建测试工具
```java
@Component
public class SpelTester {
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public Object evaluate(String expression, Object rootObject) {
        EvaluationContext context = new StandardEvaluationContext(rootObject);
        return parser.parseExpression(expression).getValue(context);
    }
    
    public void testWithContext(String expression, Map<String, Object> variables) {
        EvaluationContext context = new StandardEvaluationContext();
        variables.forEach(context::setVariable);
        Object result = parser.parseExpression(expression).getValue(context);
        System.out.println("Expression: " + expression);
        System.out.println("Result: " + result);
    }
}
```

### 3. 调试工具

#### 日志调试
```java
@Configuration
public class SpelDebugConfig {
    @Bean
    public EvaluationContext debugEvaluationContext() {
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.addPropertyAccessor(new LoggingPropertyAccessor());
        return context;
    }
    
    static class LoggingPropertyAccessor implements PropertyAccessor {
        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) {
            System.out.println("Reading property: " + name + " from " + target);
            // 实际的属性读取逻辑
            return null;
        }
        
        // 其他必需的方法...
    }
}
```

#### 表达式性能监控
```java
@Aspect
@Component
public class SpelPerformanceMonitor {
    private final Map<String, Long> expressionTimes = new ConcurrentHashMap<>();
    
    @Around("@annotation(SpelMonitored)")
    public Object monitorSpelExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long endTime = System.currentTimeMillis();
            String methodName = joinPoint.getSignature().getName();
            expressionTimes.merge(methodName, endTime - startTime, Long::sum);
        }
    }
}
```

### 4. 第三方库集成

#### Apache Commons Lang
```java
// 结合 Apache Commons 工具类使用
@Value("#{T(org.apache.commons.lang3.StringUtils).isBlank(#root) ? 'N/A' : #root.toUpperCase()}")
private String processedValue;
```

#### Jackson JSON 处理
```java
// 在 JSON 处理中使用 SpEL
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {
    private Object data;
    
    @JsonProperty("message")
    @Value("#{#root.success ? 'Operation successful' : 'Operation failed'}")
    private String message;
}
```

## 最佳实践建议

### 1. 表达式设计原则

#### 保持简单性
```java
// 推荐：简单的表达式
@Value("#{systemProperties['app.name'] ?: 'default-app'}")
private String appName;

// 不推荐：过于复杂的表达式
@Value("#{T(java.util.stream.Collectors).toList().apply(T(java.util.Arrays).stream(#root.split(',')).map(T(Integer)::parseInt).filter(n -> n > 0))}")
private List<Integer> numbers;
```

#### 可读性和维护性
```java
// 好的做法：有意义的默认值和清晰的逻辑
@Value("#{environment.getProperty('server.servlet.context-path') ?: '/'}")
private String contextPath;

// 使用常量提高可读性
public class SpelConstants {
    public static final String DEFAULT_CONTEXT_PATH = "'/'";
    public static final String CONTEXT_PATH_EXPR = "#{environment.getProperty('server.servlet.context-path') ?: " + DEFAULT_CONTEXT_PATH + "}";
}
```

### 2. 性能优化

#### 缓存编译后的表达式
```java
@Component
public class CompiledExpressionCache {
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final ExpressionParser parser = new SpelExpressionParser(
        new SpelParserConfiguration(SpelCompilerMode.IMMEDIATE, this.getClass().getClassLoader())
    );
    
    public Expression getExpression(String expression) {
        return cache.computeIfAbsent(expression, parser::parseExpression);
    }
}
```

#### 避免重复解析
```java
@Service
public class UserService {
    private final Expression isActiveExpression;
    
    public UserService(ExpressionParser parser) {
        // 在初始化时解析表达式，而不是每次使用时解析
        this.isActiveExpression = parser.parseExpression("status == 'ACTIVE' and lastLogin > (new Date().time - 86400000)");
    }
    
    public boolean isUserActive(User user) {
        return (Boolean) isActiveExpression.getValue(user);
    }
}
```

### 3. 错误处理

#### 安全的表达式求值
```java
public class SafeExpressionEvaluator {
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public Optional<Object> safeEvaluate(String expression, Object rootObject) {
        try {
            EvaluationContext context = new StandardEvaluationContext(rootObject);
            Expression expr = parser.parseExpression(expression);
            return Optional.ofNullable(expr.getValue(context));
        } catch (Exception e) {
            log.warn("Failed to evaluate expression: {}", expression, e);
            return Optional.empty();
        }
    }
}
```

#### 默认值处理
```java
@Component
public class ConfigProvider {
    // 提供合理的默认值
    @Value("#{environment.getProperty('api.timeout', T(Long).valueOf(5000))}")
    private long apiTimeout;
    
    @Value("#{environment.getProperty('features.new-ui', 'false').equalsIgnoreCase('true')}")
    private boolean isNewUiEnabled;
}
```

通过合理地应用这些场景和工具，你可以充分发挥 SpEL 在 Spring 应用中的作用，提高开发效率和代码质量。