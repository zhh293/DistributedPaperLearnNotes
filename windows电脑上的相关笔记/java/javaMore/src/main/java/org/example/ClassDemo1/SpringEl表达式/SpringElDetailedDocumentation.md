# Spring Expression Language (SpEL) 超详细讲解文档

## 目录
1. [SpEL 简介](#spel-简介)
2. [基础语法](#基础语法)
3. [字面量表达式](#字面量表达式)
4. [变量和属性访问](#变量和属性访问)
5. [数组、列表和映射](#数组列表和映射)
6. [运算符](#运算符)
7. [方法调用](#方法调用)
8. [类型操作符](#类型操作符)
9. [构造器调用](#构造器调用)
10. [条件操作符](#条件操作符)
11. [集合选择](#集合选择)
12. [模板表达式](#模板表达式)
13. [注解中的 SpEL](#注解中的-spel)
14. [XML 配置中的 SpEL](#xml-配置中的-spel)
15. [在 Spring Security 中使用 SpEL](#在-spring-security-中使用-spel)
16. [实际应用案例](#实际应用案例)
17. [性能考虑](#性能考虑)
18. [常见错误与调试](#常见错误与调试)
19. [面试重点](#面试重点)

---

## SpEL 简介

Spring Expression Language（简称 SpEL）是一个支持查询和操作运行时对象图功能强大的表达式语言。这是 Spring 3.0 版本新增的功能。

### 主要特性：
- 表达式求值
- 属性绑定
- 方法调用
- 运算符支持
- 集合选择
- 模板表达式

### 使用场景：
- 在 Spring 配置文件中定义 Bean 属性
- 在注解中动态指定值
- 数据绑定和验证
- Spring Security 权限控制
- Thymeleaf 模板引擎

---

## 基础语法

SpEL 表达式使用 `#{}` 作为定界符：

```java
@Value("#{systemProperties['java.home']}")
private String javaHome;

@Value("#{T(java.lang.Math).random() * 100.0}")
private double randomNumber;
```

对于默认值，可以使用 `:` 分隔符：

```java
@Value("#{systemProperties['user.region'] ?: 'US'}")
private String region;
```

---

## 字面量表达式

SpEL 支持多种类型的字面量：

### 字符串
```java
@Value("#{'Hello World'}")
private String greeting;

@Value('#{"Hello World"}') // 单引号也可以
private String greeting2;
```

### 数字
```java
@Value("#{3.14159}")
private double pi;

@Value("#{1024}")
private int number;

@Value("#{0xFFFF}") // 十六进制
private int hexNumber;
```

### 布尔值和 null
```java
@Value("#{true}")
private boolean.isTrue;

@Value("#{false}")
private boolean.isFalse;

@Value("#{null}")
private Object nullValue;
```

---

## 变量和属性访问

### 访问系统属性
```java
@Value("#{systemProperties['java.version']}")
private String javaVersion;

@Value("#{systemEnvironment['PATH']}")
private String path;
```

### 访问其他 Bean 的属性
```java
@Component("myBean")
public class MyBean {
    private String name = "MyBean";
    
    public String getName() {
        return name;
    }
}

@Component
public class AnotherBean {
    @Value("#{myBean.name}")
    private String myBeanName;
}
```

### 访问对象属性
```java
public class Person {
    private String name;
    private Address address;
    
    // getters and setters
}

public class Address {
    private String city;
    
    // getters and setters
}

// 使用 SpEL 访问嵌套属性
@Value("#{person.address.city}")
private String city;
```

---

## 数组、列表和映射

### 创建数组
```java
@Value("#{{1, 2, 3, 4, 5}}")
private int[] numbers;

@Value("#{{'apple', 'banana', 'orange'}}")
private String[] fruits;
```

### 创建列表
```java
@Value("#{{1, 2, 3} instanceof T(java.util.List)}")
private boolean isList;
```

### 创建映射
```java
@Value("#{{'name':'John', 'age':30}}")
private Map<String, Object> personMap;
```

### 访问元素
```java
@Value("#{{'apple', 'banana', 'orange'}[0]}")
private String firstFruit;

@Value("#{{'name':'John', 'age':30}['name']}")
private String personName;
```

---

## 运算符

### 算术运算符
```java
@Value("#{1 + 2}")
private int sum;

@Value("#{5 - 3}")
private int difference;

@Value("#{4 * 3}")
private int product;

@Value("#{10 / 2}")
private int quotient;

@Value("#{10 % 3}")
private int remainder;

@Value("#{2 ^ 3}")
private int power; // 2的3次方
```

### 关系运算符
```java
@Value("#{1 == 1}")
private boolean equal;

@Value("#{1 != 2}")
private boolean notEqual;

@Value("#{5 < 10}")
private boolean lessThan;

@Value("#{5 > 10}")
private boolean greaterThan;

@Value("#{5 <= 5}")
private boolean lessThanOrEqual;

@Value("#{5 >= 5}")
private boolean greaterThanOrEqual;
```

### 逻辑运算符
```java
@Value("#{true and false}")
private boolean andResult;

@Value("#{true or false}")
private boolean orResult;

@Value("#{!true}")
private boolean notResult;
```

### 三元运算符
```java
@Value("#{1 > 2 ? 'greater' : 'less'}")
private String result;

// Elvis 运算符 (类似 Groovy)
@Value("#{someBean.name ?: 'defaultName'}")
private String nameWithDefault;
```

---

## 方法调用

### 调用静态方法
```java
@Value("#{T(java.lang.Math).random()}")
private double random;

@Value("#{T(java.lang.Math).abs(-5)}")
private int absValue;

@Value("#{T(java.util.Arrays).asList(1,2,3)}")
private List<Integer> list;
```

### 调用实例方法
```java
@Value("#{'Hello World'.toUpperCase()}")
private String upperCaseString;

@Value("#{{'apple', 'banana'}.size()}")
private int listSize;
```

### 调用其他 Bean 的方法
```java
@Component("calculator")
public class Calculator {
    public int add(int a, int b) {
        return a + b;
    }
}

@Component
public class Client {
    @Value("#{calculator.add(5, 3)}")
    private int sum;
}
```

---

## 类型操作符

类型操作符 `T()` 用于表示 java.lang.Class 的实例，以及调用静态方法：

```java
@Value("#{T(java.util.Date)}")
private Class<Date> dateClass;

@Value("#{T(java.util.UUID).randomUUID().toString()}")
private String uuid;
```

---

## 构造器调用

可以通过 `new` 操作符调用构造器：

```java
@Value("#{new java.util.Date()}")
private Date currentDate;

@Value("#{new java.util.ArrayList(3)}")
private List<String> listWithCapacity;
```

---

## 条件操作符

### instanceof 操作符
```java
@Value("#{'hello' instanceof T(String)}")
private boolean isString;

@Value("#{{1,2,3} instanceof T(java.util.Collection)}")
private boolean isCollection;
```

### 正则表达式匹配
```java
@Value("#{'123' matches '\\d+'}")
private boolean isDigit;

@Value("#{'email@example.com' matches '^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$'}")
private boolean isValidEmail;
```

---

## 集合选择

集合选择是 SpEL 提供的一个强大功能，可以过滤集合中的元素：

### 选择器 (Selection)
```java
// 选择所有年龄大于30的人
@Value("#{people.?[age > 30]}")
private List<Person> peopleOver30;

// 选择第一个年龄大于30的人
@Value("#{people.^[age > 30]}")
private Person firstPersonOver30;

// 选择最后一个年龄大于30的人
@Value("#{people.$[age > 30]}")
private Person lastPersonOver30;
```

### 投影 (Projection)
```java
// 获取所有人的姓名
@Value("#{people.![name]}")
private List<String> allNames;

// 获取所有年龄大于30的人的姓名
@Value("#{people.?[age > 30].![name]}")
private List<String> namesOver30;
```

---

## 模板表达式

模板表达式是混合文字文本与一个或多个评估块。每个评估块都用分隔符界定，这里是 `#{}`：

```java
@Value("Hello #{systemProperties['user.name']}!")
private String greeting;

@Value("Today is #{new java.util.Date()}.")
private String todayMessage;
```

---

## 注解中的 SpEL

### @Value 注解
```java
@Component
public class AppConfig {
    @Value("#{systemProperties['app.name'] ?: 'MyApp'}")
    private String appName;
    
    @Value("#{T(java.lang.Math).random() * 100}")
    private double randomValue;
}
```

### @ConditionalOnExpression 注解
```java
@Component
@ConditionalOnExpression("#{systemProperties['feature.enabled'] == 'true'}")
public class FeatureService {
    // 仅当系统属性 feature.enabled 为 true 时才创建此 bean
}
```

### Spring Security 中的 @PreAuthorize 和 @PostAuthorize
```java
@Service
public class ContactService {
    @PreAuthorize("#contact.owner == authentication.name")
    public void updateContact(Contact contact) {
        // 只有联系人所有者才能更新联系人
    }
    
    @PostAuthorize("returnObject.owner == authentication.name")
    public Contact getContact(Long id) {
        // 只返回当前认证用户拥有的联系人
        return contactRepository.findById(id);
    }
}
```

---

## XML 配置中的 SpEL

虽然现在多使用注解方式，但了解 XML 配置中的 SpEL 仍然有用：

```xml
<bean id="numberGuess" class="com.example.NumberGuess">
    <property name="randomNumber" value="#{T(java.lang.Math).random() * 100}"/>
    <property name="defaultLocale" value="#{systemProperties['user.region'] ?: 'US'}"/>
</bean>

<util:properties id="settings" location="classpath:config.properties"/>
<bean id="appConfig" class="com.example.AppConfig">
    <property name="appName" value="#{settings['app.name']}"/>
</bean>
```

---

## 在 Spring Security 中使用 SpEL

Spring Security 广泛使用 SpEL 进行权限控制：

### 方法级安全
```java
@PreAuthorize("hasRole('ADMIN') or #employee.id == principal.id")
public void updateEmployee(Employee employee) {
    employeeRepository.save(employee);
}

@PostFilter("hasRole('ADMIN') or filterObject.department == principal.department")
public List<Employee> getAllEmployees() {
    return employeeRepository.findAll();
}
```

### Web 安全
```java
@Override
protected void configure(HttpSecurity http) throws Exception {
    http
        .authorizeRequests()
            .antMatchers("/admin/**").access("hasRole('ADMIN')")
            .antMatchers("/user/{userId}/**").access("@webSecurity.checkUserId(authentication,#userId)")
            .anyRequest().authenticated();
}
```

### 自定义安全表达式
```java
@Component("webSecurity")
public class WebSecurity {
    public boolean checkUserId(Authentication authentication, int userId) {
        // 自定义逻辑检查用户是否有权访问指定 userId 的资源
        return true;
    }
}
```

---

## 实际应用案例

### 1. 动态配置数据库连接
```java
@Configuration
public class DatabaseConfig {
    @Value("#{environment.databaseUrl ?: 'jdbc:h2:mem:testdb'}")
    private String databaseUrl;
    
    @Bean
    public DataSource dataSource() {
        // 根据表达式结果创建数据源
        return new DriverManagerDataSource(databaseUrl);
    }
}
```

### 2. 条件化 Bean 创建
```java
@Component
@ConditionalOnExpression(
    "#{environment.acceptsTraffic != false && T(java.lang.management.ManagementFactory)"
    + ".getRuntimeMXBean().getInputArguments()"
    + ".toString().contains('spring.profiles.active')}")
public class TrafficAcceptingService {
    // 只在特定条件下创建此服务
}
```

### 3. 动态定时任务配置
```java
@Component
public class ScheduledTasks {
    @Scheduled(cron = "#{systemProperties['report.cron'] ?: '0 0 1 * * ?'}")
    public void generateDailyReport() {
        // 根据系统属性设置 cron 表达式，默认每天凌晨1点执行
    }
}
```

---

## 性能考虑

### 编译后的 SpEL 表达式
从 Spring 4.1 开始，SpEL 支持表达式编译以提高性能：

```java
@Configuration
public class SpelConfig {
    @Bean
    public SpelCompilerMode spelCompilerMode() {
        return SpelCompilerMode.IMMEDIATE; // 或 MIXED, OFF
    }
}
```

### 缓存解析结果
```java
public class CachedExpressionEvaluator extends CachedExpressionEvaluator {
    private final Map<ExpressionKey, Expression> expressionCache = new ConcurrentHashMap<>();
    
    public Object evaluate(String expression, EvaluationContext context) {
        return getExpression(this.expressionCache, context.getRootObject().getValue(), expression)
                .getValue(context);
    }
}
```

---

## 常见错误与调试

### 1. 表达式语法错误
```java
// 错误示例：缺少大括号
@Value("systemProperties['user.name']")
private String userName;

// 正确写法
@Value("#{systemProperties['user.name']}")
private String userName;
```

### 2. 类型转换问题
```java
// 可能出现类型不匹配的问题
@Value("#{systemProperties['server.port']}") // 返回字符串
private int port; // 期望整数

// 解决方案：显式转换
@Value("#{T(Integer).parseInt(systemProperties['server.port'])}")
private int port;
```

### 3. 空指针异常
```java
// 不安全的表达式
@Value("#{user.address.street}")

// 更安全的表达式
@Value("#{user?.address?.street ?: 'Unknown'}")
```

### 调试技巧
```java
// 使用 ExpressionParser 手动测试表达式
ExpressionParser parser = new SpelExpressionParser();
Expression exp = parser.parseExpression("'Hello World'.bytes.length");
int value = (Integer) exp.getValue(); // 11
```

---

## 面试重点

### 必须掌握的基础知识
1. SpEL 的基本语法 `#{}` 和默认值操作符 `?:`
2. 各种运算符：算术、关系、逻辑、三元等
3. 对象属性访问和方法调用
4. 集合选择和投影操作

### 高级知识点
1. 编译模式对性能的影响
2. 在 Spring Security 中的运用
3. 自定义 EvaluationContext 和 PropertyAccessor
4. 与第三方库集成（如 Thymeleaf）

### 常见面试题
Q: SpEL 与 JSP EL 有什么区别？
A: SpEL 是 Spring 框架的一部分，功能更加强大，支持方法调用、类型操作、集合选择等功能，而 JSP EL 主要用于页面展示。

Q: 如何在 SpEL 中处理 null 值？
A: 可以使用 Elvis 操作符 `?:` 设置默认值，或者使用安全导航操作符 `?.` 避免空指针异常。

Q: SpEL 的编译模式有哪些？各有什么优缺点？
A: 有三种模式：OFF（默认）、IMMEDIATE（立即编译）和 MIXED（混合）。编译可以提升性能，但可能在某些复杂表达式上出现问题。

Q: SpEL 在 Spring 生态系统中的应用场景有哪些？
A: 主要应用于注解值注入、安全控制、配置管理、模板引擎等方面。