# Java 编码规范架构设计文档

## 一、问题背景

### 1.1 要解决的核心问题

代码是团队协作最核心的"产品"，也是最长周期的资产——一个业务系统的生命周期通常是数年甚至十几年，而这期间会有大量不同背景、不同经验水平的工程师参与维护。编码规范要解决的核心问题是：

1. **可读性与可维护性问题**：代码被阅读的次数远远多于被编写的次数（业界经验比例通常认为是 10:1 甚至更高）。如果每个人按照自己的喜好命名、组织代码结构，团队协作成本会指数级上升，新人接手代码的学习曲线会变得异常陡峭。
2. **隐蔽 Bug 与生产事故问题**：很多严重的线上事故并非源于复杂的架构缺陷，而是源于一些"看起来能编译通过但语义有问题"的代码，比如 `==` 比较包装类型、`SimpleDateFormat` 的线程安全问题、`equals`/`hashCode` 未正确重写导致集合行为异常、异常被吞掉导致问题无法排查等。
3. **并发安全问题**：多线程编程本身反直觉，如果没有统一的并发规范约束（如何正确使用 `volatile`、如何设计线程池参数、如何避免死锁），团队中经验不足的成员很容易写出偶发性、难以复现的并发 Bug，这类问题往往要到高并发生产环境才会暴露，且排查成本极高。
4. **安全漏洞问题**：SQL 注入、XSS、敏感信息硬编码/明文存储等安全问题，往往源于编码时的疏忽而非架构设计缺陷，需要通过强制性规范在编码阶段就予以杜绝。

### 1.2 典型场景

- **大规模团队协作开发**：多个团队共同维护一个大型代码库，缺乏统一规范会导致代码风格割裂，Code Review 效率低下，甚至同一个团队不同成员写的代码"看起来像出自不同公司"。
- **新人快速融入**：规范文档和自动化检查工具（Checkstyle/PMD/SonarQube）能帮助新人快速理解"什么是团队认可的代码写法"，减少因为不了解隐性约定而反复被 Code Review 打回的情况。
- **高并发系统开发**：交易、支付、库存等对并发安全性要求极高的系统，任何一处不规范的并发代码都可能导致资损。
- **长期维护的遗留系统**：随着时间推移，代码会经历无数次迭代和人员更替，如果早期没有建立好规范，技术债会越滚越大，最终导致"没人敢改"的僵化代码。

### 1.3 不解决的后果

- **隐性 Bug 频发**：例如包装类型 `==` 比较在 `-128~127` 之外的值会因为不走缓存池而返回 `false`，这种问题在测试阶段很难被发现，往往是在生产环境的极端数据下才会暴露。
- **并发问题导致资损**：错误的双重检查锁定（Double-Check Locking）实现、线程池参数设置不当导致的任务堆积或线程耗尽，都可能在高并发场景下直接导致业务逻辑错误或服务雪崩。
- **代码可读性差导致维护成本飙升**：命名混乱、方法过长、职责不清晰的代码，会让后续维护者需要花费远超编写者的时间去理解代码意图，进而影响需求交付速度。
- **安全漏洞被恶意利用**：SQL 注入、敏感信息泄露等问题一旦被攻击者利用，会造成数据泄露、资金损失等重大安全事故。
- **测试覆盖不足导致回归问题**：没有规范化的单元测试要求，重构和迭代时容易引入回归 Bug，且难以在早期发现。

---

## 二、整体架构设计

### 2.1 编码规范的分层体系

一套完整的编码规范体系通常包含以下几个层次：

| 层次 | 内容 | 落地方式 |
|---|---|---|
| **设计原则层** | 指导思想，如清晰表达优先、最小权限、默认安全 | 文档 + 团队共识 + Code Review |
| **命名与格式层** | 命名规则、代码格式、注释规范 | IDE 插件（阿里巴巴 Java 开发手册插件类似工具）+ Checkstyle |
| **语言特性使用层** | 并发、异常、集合、字符串处理等具体语言特性的正确用法 | 静态代码扫描（PMD/SpotBugs/SonarQube） |
| **工程实践层** | 日志、测试、安全相关的工程规范 | CI/CD 流水线中的质量门禁（Quality Gate） |

### 2.2 规范落地的工具链架构

```
开发阶段：IDE 插件实时提示（编码规范插件 + 自动格式化模板）
    ↓
提交阶段：Git Hook（pre-commit）触发本地静态检查，格式不合规直接拦截提交
    ↓
CI 阶段：流水线运行 Checkstyle + PMD + SpotBugs + 单元测试覆盖率检查
    ↓
Code Review 阶段：人工评审业务逻辑合理性、架构设计问题（工具无法覆盖的部分）
    ↓
质量门禁：SonarQube 等平台设置阻断性规则（如新增代码覆盖率<80%则禁止合并）
```

### 2.3 设计权衡

- **规范的严格程度与开发效率的平衡**：过于严苛的规范（如强制 100% 覆盖率、禁止一切魔法值）会拖慢开发效率，应区分"阻断性规则"（must，如安全漏洞、明显 Bug）与"建议性规则"（should，如命名风格偏好）。
- **自动化检查与人工评审的分工**：格式、命名、已知反模式（anti-pattern）应交给工具自动检查；业务逻辑合理性、架构设计取舍应由人工 Code Review 承担，工具无法替代人的判断。
- **规范的可演进性**：规范不是一成不变的教条，需要随着语言新特性（如 Java 8 的 Lambda、Java 17 的 Record）、团队实践的成熟而持续迭代。

---

## 三、核心链路设计：七大设计原则详解

### 3.1 原则一：清晰表达优先于炫技

代码首先是写给人看的，其次才是给机器执行的。晦涩难懂的"聪明写法"看似高效，实际上会大幅增加团队的理解和维护成本。

**反例：**

```java
// 反例：过度使用三元表达式嵌套和位运算技巧，牺牲可读性换取"简洁"
public int calc(int a, int b, int c) {
    return (a > b ? (a > c ? a : c) : (b > c ? b : c)) ^ ((a & b) | (b & c));
}
```

**正例：**

```java
// 正例：拆分成有意义的中间变量和方法，表达清晰的业务意图
public int findMax(int a, int b, int c) {
    int maxOfAB = Math.max(a, b);
    return Math.max(maxOfAB, c);
}
```

再举一个更贴近业务的例子：

```java
// 反例：条件判断嵌套过深，且缺乏对判断意图的说明
public boolean checkOrder(Order order) {
    if (order != null) {
        if (order.getStatus() == 1) {
            if (order.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                if (order.getUser() != null && order.getUser().isActive()) {
                    return true;
                }
            }
        }
    }
    return false;
}

// 正例：卫语句（Guard Clause）提前返回，每个条件配合语义清晰的方法名
public boolean isValidOrder(Order order) {
    if (order == null) {
        return false;
    }
    if (!isPendingStatus(order)) {
        return false;
    }
    if (!hasPositiveAmount(order)) {
        return false;
    }
    return hasActiveUser(order);
}

private boolean isPendingStatus(Order order) {
    return order.getStatus() == OrderStatus.PENDING.getCode();
}

private boolean hasPositiveAmount(Order order) {
    return order.getAmount().compareTo(BigDecimal.ZERO) > 0;
}

private boolean hasActiveUser(Order order) {
    return order.getUser() != null && order.getUser().isActive();
}
```

### 3.2 原则二：不过度设计（YAGNI 原则）

"You Aren't Gonna Need It"——不要为了假想中"未来可能需要"的扩展性，引入当下不需要的复杂度。过度设计会增加理解成本，且很多"预留的扩展点"最终从未被使用。

**反例：**

```java
// 反例：为一个只有一种实现、短期内不会有变化的场景，过早引入策略模式+工厂模式+抽象工厂
public interface PriceCalculateStrategy {
    BigDecimal calculate(Order order);
}

public class DefaultPriceCalculateStrategy implements PriceCalculateStrategy {
    @Override
    public BigDecimal calculate(Order order) {
        return order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    }
}

public interface PriceCalculateStrategyFactory {
    PriceCalculateStrategy create(String type);
}

public class PriceCalculateStrategyFactoryImpl implements PriceCalculateStrategyFactory {
    @Override
    public PriceCalculateStrategy create(String type) {
        // 目前只有一种实现，未来是否真的会扩展也未可知
        return new DefaultPriceCalculateStrategy();
    }
}
```

**正例：**

```java
// 正例：需求明确只有一种计算方式时，直接实现，等到真正出现第二种计算方式的需求时再重构为策略模式
public class PriceCalculator {
    public BigDecimal calculate(Order order) {
        return order.getUnitPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    }
}
```

判断是否过度设计的经验法则：**只有当同一类需求确定会出现第三次重复（Rule of Three）时，才考虑抽象成通用组件**；仅出现一次或两次的相似逻辑，直接复制或简单复用即可，过早抽象反而会导致后续需求变化时抽象层被反复打破重建。

### 3.3 原则三：问题清零，不留技术债的"暂时"方案

任何已知问题（TODO、FIXME、临时绕过的 Bug）都应该有明确的跟踪和清零计划，而不是无限期搁置。

**反例：**

```java
public void processPayment(PaymentRequest request) {
    // TODO: 这里偶尔会出现金额为负数的情况，先加个判断绕过，后面再查原因
    if (request.getAmount().compareTo(BigDecimal.ZERO) < 0) {
        request.setAmount(BigDecimal.ZERO); // 静默修正数据，掩盖了真正的问题根源
    }
    doPay(request);
}
```

这种"临时绕过"的写法看似解决了眼前的异常，实际上掩盖了上游数据错误的根本原因，问题会在未来以更隐蔽的形式重新出现（例如金额被静默清零导致业务对账不平）。

**正例：**

```java
public void processPayment(PaymentRequest request) {
    if (request.getAmount().compareTo(BigDecimal.ZERO) < 0) {
        // 明确拒绝非法数据，快速失败，倒逼上游修复数据源头问题
        throw new IllegalArgumentException(
                "payment amount must not be negative, orderId=" + request.getOrderId()
                        + ", amount=" + request.getAmount());
    }
    doPay(request);
}
```

团队应建立机制：所有 `TODO`/`FIXME` 注释必须关联具体的跟踪工单号，并纳入技术债看板定期清理，禁止无主、无跟踪计划的"临时代码"长期存在于主干分支。

### 3.4 原则四：尽早捕获错误（Fail Fast）

错误发现得越早，修复成本越低。应该在参数入口、编译期、单元测试阶段就尽可能拦截问题，而不是让错误数据流转到系统深处才暴露。

**反例：**

```java
// 反例：不做参数校验，让空指针异常在后续任意一处随机抛出，调用栈层级已经很深，难以定位问题源头
public void createOrder(OrderRequest request) {
    BigDecimal total = request.getUnitPrice().multiply(request.getQuantity()); // 可能NPE
    orderRepository.save(buildOrder(request, total));
}
```

**正例：**

```java
// 正例：入口处立即校验，明确的异常信息直接指出问题所在，避免异常在深层调用栈中以NPE的形式随机出现
public void createOrder(OrderRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(request.getUnitPrice(), "unitPrice must not be null");
    if (request.getQuantity() == null || request.getQuantity() <= 0) {
        throw new IllegalArgumentException("quantity must be positive, actual=" + request.getQuantity());
    }
    BigDecimal total = request.getUnitPrice().multiply(BigDecimal.valueOf(request.getQuantity()));
    orderRepository.save(buildOrder(request, total));
}
```

同样的思路也体现在优先使用编译期检查代替运行期检查：能用泛型约束的地方不用 `Object` + 强转，能用枚举约束取值范围的地方不用魔法字符串。

### 3.5 原则五：默认安全（Secure by Default）

系统的默认配置和默认行为应该是最安全的选项，任何"放开限制"的操作都应该是显式的、有意识的决定，而不是默认行为。

**反例：**

```java
// 反例：反序列化时默认信任所有类型，给反序列化漏洞（如Fastjson反序列化漏洞）留下攻击面
ObjectMapper mapper = new ObjectMapper();
mapper.enableDefaultTyping(); // 默认开启多态类型反序列化，存在被构造恶意Payload攻击的风险
```

**正例：**

```java
// 正例：默认不开启多态类型反序列化，如确有需要，通过白名单机制显式声明允许的类型
ObjectMapper mapper = new ObjectMapper();
// 仅在明确需要且经过安全评审的场景下，通过白名单方式限定可反序列化的类
PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
        .allowIfSubType("com.example.trusted.package.")
        .build();
mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL);
```

### 3.6 原则六：最小权限原则（Least Privilege）

代码、接口、数据库账号等各类主体应该只被授予完成其职责所必需的最小权限，而不是"图方便"授予过大的权限范围。

**反例：**

```java
// 反例：所有服务共用一个拥有DDL权限的数据库账号，任何一个服务的SQL注入漏洞都可能导致整个数据库结构被破坏
// jdbc:mysql://host:3306/db?user=root&password=root123
```

**正例：**

```java
// 正例：为不同服务分配仅具备其所需最小权限的账号（如订单服务只有order库的增删改查权限，无DDL和其他库权限）
// jdbc:mysql://host:3306/order_db?user=order_service_readwrite&password=***
```

在代码层面同样适用：类的成员变量和方法默认使用 `private`，只有确实需要暴露给外部时才逐步放开到 `protected`/`public`；工具类的构造方法应显式声明为 `private` 防止被实例化。

```java
// 正例：工具类通过private构造方法防止被误实例化，体现最小权限/最小暴露原则
public final class DateUtils {
    private DateUtils() {
        throw new AssertionError("no instance");
    }

    public static String format(LocalDateTime time) {
        return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
```

### 3.7 原则七：最小暴露原则（Minimal Exposure）

模块之间应该只暴露必要的接口，隐藏内部实现细节，避免调用方对实现细节产生不必要的依赖。

**反例：**

```java
// 反例：直接暴露内部使用的可变集合，调用方可以绕过封装随意修改内部状态
public class OrderService {
    private final List<Order> orders = new ArrayList<>();

    public List<Order> getOrders() {
        return orders; // 调用方拿到引用后可以直接 orders.clear() 破坏内部状态
    }
}
```

**正例：**

```java
// 正例：返回不可变视图或防御性拷贝，内部状态不会被外部意外修改
public class OrderService {
    private final List<Order> orders = new ArrayList<>();

    public List<Order> getOrders() {
        return Collections.unmodifiableList(new ArrayList<>(orders));
    }
}
```

---

## 四、命名规范

### 4.1 类命名

- 采用 UpperCamelCase（帕斯卡命名法），名词或名词短语，能清晰表达职责。
- 接口的实现类通常以 `Impl` 结尾；异常类以 `Exception` 结尾；测试类以被测类名 + `Test` 结尾。

```java
// 正例
public class OrderService { }
public interface PaymentGateway { }
public class AlipayPaymentGateway implements PaymentGateway { }
public class OrderNotFoundException extends RuntimeException { }
public class OrderServiceTest { }

// 反例：缩写不清晰、动词化命名、无意义命名
public class OrdSvc { }        // 不知道Svc是什么缩写
public class ProcessOrder { }  // 类名不应该是动词形式
public class Manager { }       // 完全无法表达职责
public class Data1 { }         // 无意义的数字后缀
```

### 4.2 方法命名

- 采用 lowerCamelCase，动词或动宾短语开头，清晰表达行为意图。
- 布尔返回值的方法用 `is`/`has`/`can`/`should` 开头；获取值用 `get`；转换用 `to`/`as`；构造对象用 `create`/`build`/`of`。

```java
// 正例
public boolean isValid(Order order) { ... }
public boolean hasPermission(User user, String resource) { ... }
public Order getOrderById(Long orderId) { ... }
public OrderDTO toDTO(Order order) { ... }
public static Order of(OrderRequest request) { ... }

// 反例
public boolean valid(Order order) { ... }        // 布尔方法缺少is/has前缀，语义不清晰
public Order order(Long id) { ... }              // 名词作为方法名，无法表达是查询还是其他操作
public void data(Order order) { ... }            // 完全无法理解该方法的作用
```

### 4.3 变量与常量命名

```java
// 正例：变量lowerCamelCase，常量全大写下划线分隔，含义清晰
private static final int MAX_RETRY_COUNT = 3;
private static final long DEFAULT_TIMEOUT_MILLIS = 5000L;
private List<Order> pendingOrders;
private Map<Long, User> userIdToUserMap; // 清晰表达Map的key-value含义

// 反例
private static final int a = 3;              // 常量命名无意义
private List<Order> list;                    // 变量名过于宽泛，无法表达业务含义
private Map<Long, User> map1;                // 无法看出key/value的类型语义
private int flag;                            // flag类命名应说明具体标记什么状态
```

### 4.4 包命名与枚举命名

```java
// 正例：包名全小写，按业务域反向域名+功能模块组织
package com.example.order.service;
package com.example.order.domain.model;

// 正例：枚举名UpperCamelCase，枚举值全大写下划线分隔，并携带描述性字段
public enum OrderStatus {
    PENDING(1, "待支付"),
    PAID(2, "已支付"),
    SHIPPED(3, "已发货"),
    COMPLETED(4, "已完成"),
    CANCELLED(5, "已取消");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}

// 反例：使用魔法数字代替枚举，语义完全丢失，且没有集中管理取值范围
public class Order {
    private int status; // 1、2、3分别代表什么？调用方需要去翻代码或文档才能理解
}
```

---

## 五、并发规范

### 5.1 线程安全的基本原则

- 优先使用不可变对象（Immutable Object），不可变对象天然线程安全，无需任何同步措施。
- 能用局部变量就不用共享的成员变量；确需共享可变状态时，明确该状态的同步策略并在注释中说明。
- 避免在多线程环境下共享可变的 `SimpleDateFormat`、`ArrayList` 等非线程安全类。

```java
// 反例：SimpleDateFormat 非线程安全，作为静态变量在多线程中共享使用，高并发下会出现日期解析结果错乱
public class DateFormatUtil {
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    public static String format(Date date) {
        return FORMAT.format(date); // 多线程并发调用时，内部Calendar状态被交叉修改，产生错误结果
    }
}

// 正例一：使用线程安全的 java.time 包（Java 8+），DateTimeFormatter 是不可变且线程安全的
public class DateFormatUtil {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public static String format(LocalDate date) {
        return date.format(FORMATTER);
    }
}

// 正例二：如必须使用SimpleDateFormat，通过ThreadLocal隔离，每个线程持有独立实例
public class DateFormatUtil {
    private static final ThreadLocal<SimpleDateFormat> FORMAT_HOLDER =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    public static String format(Date date) {
        return FORMAT_HOLDER.get().format(date);
    }
}
```

### 5.2 锁粒度控制

锁的粒度应尽可能小，只保护真正需要互斥的临界区，避免把无关的耗时操作（如远程调用、IO）包含在同步块内。

```java
// 反例：锁粒度过大，把耗时的远程调用也包含在同步块内，导致所有线程串行等待，并发性能急剧下降
public class InventoryService {
    private final Object lock = new Object();

    public void deduct(Long skuId, int count) {
        synchronized (lock) {
            int stock = queryStockFromRemote(skuId); // 远程调用耗时且与锁保护的临界区无关
            if (stock >= count) {
                updateStock(skuId, stock - count);
            }
            sendNotification(skuId); // 通知操作也不需要在锁内执行
        }
    }
}

// 正例：只锁定真正需要互斥的本地状态更新，远程调用和通知操作放到锁外执行
public class InventoryService {
    private final ConcurrentHashMap<Long, Object> lockMap = new ConcurrentHashMap<>();

    public void deduct(Long skuId, int count) {
        int stock = queryStockFromRemote(skuId); // 锁外执行，不阻塞其他sku的处理
        Object lock = lockMap.computeIfAbsent(skuId, k -> new Object());
        boolean success;
        synchronized (lock) {
            success = tryUpdateStock(skuId, stock, count); // 只有本地状态更新的临界区才需要加锁
        }
        if (success) {
            sendNotification(skuId); // 锁外执行
        }
    }
}
```

### 5.3 线程池的正确使用

- 禁止使用 `Executors` 工具类快速创建线程池（如 `newFixedThreadPool`、`newCachedThreadPool`），因为其内部使用无界队列（`LinkedBlockingQueue`）或无界线程数（`Integer.MAX_VALUE`），在任务积压或激增场景下容易导致 OOM。
- 应通过 `ThreadPoolExecutor` 构造函数显式指定核心线程数、最大线程数、有界队列容量和拒绝策略。

```java
// 反例：使用 Executors 快捷方法，队列无界，任务持续堆积会耗尽内存
ExecutorService executor = Executors.newFixedThreadPool(10);

// 正例：显式指定所有参数，有界队列 + 明确的拒绝策略，配合具名线程工厂便于问题排查
ThreadPoolExecutor executor = new ThreadPoolExecutor(
        10,                                  // 核心线程数：常驻线程数量，根据CPU密集/IO密集场景调整
        20,                                  // 最大线程数：应对突发流量的弹性上限
        60L, TimeUnit.SECONDS,               // 空闲线程存活时间：超过核心线程数的部分闲置多久后回收
        new ArrayBlockingQueue<>(1000),      // 有界队列：防止任务无限堆积导致OOM
        new ThreadFactoryBuilder().setNameFormat("order-process-pool-%d").build(), // 具名线程，便于jstack排查
        new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略：队列满时由调用者线程执行，起到自然限流作用
);
```

线程池参数设置的基本方法论：CPU 密集型任务核心线程数设置为 `CPU核数+1`；IO 密集型任务核心线程数可设置为 `CPU核数 * (1 + 平均等待时间/平均计算时间)`，具体数值需要结合压测数据调整。

### 5.4 volatile 的正确使用与 Double-Check Locking 分析

`volatile` 保证的是**可见性**和**禁止指令重排序**，而非原子性。对于"读-改-写"这类复合操作（如 `count++`），`volatile` 无法保证原子性，仍需要 `synchronized` 或 `AtomicInteger` 等 CAS 类工具。

**Double-Check Locking（DCL）单例模式为什么必须使用 volatile：**

```java
// 反例：缺少 volatile，在多线程环境下可能返回一个"未完全初始化"的对象引用
public class Singleton {
    private static Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {                  // 第一次检查
            synchronized (Singleton.class) {
                if (instance == null) {           // 第二次检查
                    instance = new Singleton();   // 关键问题所在
                }
            }
        }
        return instance;
    }
}

// 正例：使用 volatile 禁止指令重排序，确保对象初始化完成后才能被其他线程看到
public class Singleton {
    private static volatile Singleton instance;

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}
```

**为什么 `volatile` 在这里是必需的**：`instance = new Singleton()` 这行代码并非原子操作，JVM 在字节码层面会拆解为三个步骤：①分配内存空间；②调用构造函数初始化对象；③将 `instance` 引用指向刚分配的内存地址。在没有 `volatile` 约束的情况下，JIT 编译器和 CPU 出于优化目的可能对②③两步进行指令重排序，变成"先把引用指向内存地址，再执行构造函数"。如果线程 A 执行到重排序后的"指向地址"这一步时被线程 B 抢占，线程 B 检查 `instance != null` 会直接返回这个**尚未完成构造的半成品对象**，进而引发空指针异常或者读取到字段的默认零值这类隐蔽 Bug。`volatile` 通过插入内存屏障（写屏障 StoreStore，禁止②③重排序）从根本上杜绝了这种可能性。这个案例是理解 Java 内存模型（JMM）中"重排序"和"可见性"概念的经典范例，也是 Code Review 中最容易被忽视但后果严重的并发陷阱之一。

### 5.5 避免死锁的规范

```java
// 反例：两个方法以不同顺序获取相同的两把锁，存在死锁风险
public class TransferService {
    public void transfer(Account from, Account to, BigDecimal amount) {
        synchronized (from) {
            synchronized (to) { // 如果另一个线程反向执行 transfer(to, from, ...)，会形成循环等待
                from.debit(amount);
                to.credit(amount);
            }
        }
    }
}

// 正例：按照固定的全局顺序获取锁（如按账户ID大小排序），破坏死锁四要素中的"循环等待"条件
public class TransferService {
    public void transfer(Account from, Account to, BigDecimal amount) {
        Account first = from.getId() < to.getId() ? from : to;
        Account second = from.getId() < to.getId() ? to : from;
        synchronized (first) {
            synchronized (second) {
                from.debit(amount);
                to.credit(amount);
            }
        }
    }
}
```

---

## 六、异常处理规范

### 6.1 异常分类：Checked vs Unchecked

- **Checked Exception（受检异常）**：继承自 `Exception`（非 `RuntimeException`），编译器强制要求调用方显式处理（`try-catch` 或继续声明抛出）。适用于"调用方可以合理预期并有能力恢复"的场景，如文件不存在、网络连接失败。
- **Unchecked Exception（非受检异常）**：继承自 `RuntimeException`，编译器不强制处理。适用于"编程错误"或"调用方通常无法恢复、应该让程序快速失败"的场景，如参数非法、空指针、数组越界。

业界的实践趋势是**倾向于更多使用 Unchecked Exception**，因为 Checked Exception 容易导致方法签名被大量 `throws` 声明污染，且很多调用方为了绕过编译检查会写出 `catch (Exception e) {}` 这种吞掉异常的反模式代码。

### 6.2 异常处理反模式与正例

```java
// 反例一：吞掉异常，问题被彻底掩盖，后续排查时完全没有线索
public void process() {
    try {
        doSomething();
    } catch (Exception e) {
        // 什么都不做，异常信息永远丢失
    }
}

// 反例二：仅打印堆栈但不做任何处理，且使用System.out而非日志框架，生产环境无法采集
public void process() {
    try {
        doSomething();
    } catch (Exception e) {
        e.printStackTrace();
    }
}

// 反例三：捕获异常后丢弃原始异常信息，破坏异常链，导致无法追溯根因
public void process() {
    try {
        doSomething();
    } catch (Exception e) {
        throw new BusinessException("处理失败"); // 原始异常e被丢弃，丢失了根因堆栈
    }
}

// 正例：记录完整上下文信息，保留异常链，根据异常类型做出恰当处理
public void process() {
    try {
        doSomething();
    } catch (BusinessException e) {
        log.warn("business rule violation, orderId={}", orderId, e);
        throw e; // 业务异常直接向上抛出，由统一异常处理器转换为友好的错误响应
    } catch (IOException e) {
        log.error("io error while processing order, orderId={}", orderId, e);
        // 通过异常链（cause）保留原始异常信息，便于根因排查
        throw new SystemException("system error, please retry later", e);
    }
}
```

### 6.3 异常信息规范

异常信息应包含**足够定位问题的上下文**：出错的业务标识（订单号、用户 ID）、关键参数值、期望值与实际值的对比，而不是笼统的"处理失败"。

```java
// 反例：异常信息毫无信息量
throw new IllegalArgumentException("参数错误");

// 正例：包含具体的字段名、期望范围、实际值，排查问题时无需反复看代码或加日志复现
throw new IllegalArgumentException(
        String.format("quantity must be in range [1, %d], actual=%d, orderId=%s",
                MAX_QUANTITY, quantity, orderId));
```

### 6.4 try-catch-finally 最佳实践

```java
// 正例：资源释放使用 try-with-resources，自动处理关闭异常，避免资源泄漏
public String readFile(String path) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(Paths.get(path))) {
        return reader.lines().collect(Collectors.joining("\n"));
    }
    // 无需手动在finally中关闭reader，且reader.close()抛出的异常会被正确抑制/传播（Suppressed Exception）
}

// 反例：手动管理资源释放，finally块本身还可能抛出异常掩盖原始异常，且容易遗漏空指针判断
public String readFile(String path) throws IOException {
    BufferedReader reader = null;
    try {
        reader = Files.newBufferedReader(Paths.get(path));
        return reader.lines().collect(Collectors.joining("\n"));
    } finally {
        reader.close(); // 如果reader为null，或close本身抛异常，都会产生新的问题掩盖原始异常
    }
}
```

**finally 中不应包含 return 语句**，这会导致 `try`/`catch` 块中的 `return`/异常被静默吞掉，是极易引发诡异 Bug 的写法：

```java
// 反例：finally中的return会覆盖try块中的返回值和异常，导致业务逻辑异常被完全掩盖
public int getValue() {
    try {
        return 1;
    } finally {
        return 2; // 无论try块发生什么（包括抛出异常），最终都返回2，异常被彻底吞掉
    }
}
```

---

## 七、日志规范

### 7.1 日志级别的正确使用

| 级别 | 使用场景 |
|---|---|
| ERROR | 影响系统正常运行的错误，需要人工介入排查（如下游服务调用失败且重试耗尽、数据一致性被破坏） |
| WARN | 潜在的问题或异常情况，但不影响当前流程继续（如降级、使用了兜底默认值、重试中） |
| INFO | 关键业务流程的里程碑节点（如订单创建成功、支付完成），用于业务链路追溯 |
| DEBUG | 用于开发调试的详细信息，生产环境通常关闭，问题排查时可临时开启 |

### 7.2 日志规范代码示例

```java
// 反例：使用字符串拼接而非占位符，即使日志级别被关闭也会执行字符串拼接，浪费性能；且直接打印整个对象可能暴露敏感字段
log.debug("process order: " + order.toString());

// 正例：使用占位符延迟拼接（日志级别关闭时不会执行拼接开销），且脱敏后再打印
log.debug("process order, orderId={}, amount={}", order.getId(), order.getAmount());

// 敏感信息脱敏示例
public class LogMaskUtils {
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }
}

// 使用示例：日志中永远不应出现明文的手机号、身份证号、银行卡号、密码等敏感信息
log.info("user registered, phone={}", LogMaskUtils.maskPhone(user.getPhone()));
```

### 7.3 异步日志与日志聚合

- **异步日志**：日志框架（Logback/Log4j2）应配置 `AsyncAppender`，将日志写入操作从业务线程中剥离，避免磁盘 IO 阻塞主流程；但异步日志存在应用崩溃时缓冲区日志丢失的风险，需要在性能与可靠性之间权衡（可通过配置合理的队列大小和丢弃策略折中）。
- **结构化日志与链路追踪集成**：日志中应携带 `traceId`（配合 MDC 机制），便于在集中式日志平台中按照一次请求的完整链路串联所有相关日志。

```java
public class TraceIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(traceId); // 请求结束后必须清理，避免线程池复用线程导致traceId串号
        }
    }
}
// logback.xml 中的 pattern 配置：%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] %-5level %logger - %msg%n
```

---

## 八、测试规范

### 8.1 单元测试 AAA 模式

Arrange（准备）-Act（执行）-Assert（断言）三段式结构，让测试用例的意图一目了然。

```java
public class OrderServiceTest {

    @Test
    public void shouldThrowExceptionWhenAmountIsNegative() {
        // Arrange: 准备测试数据和被测对象
        OrderService orderService = new OrderService();
        OrderRequest request = new OrderRequest();
        request.setAmount(BigDecimal.valueOf(-100));

        // Act & Assert: 执行并断言（对于异常场景，执行与断言通常合并）
        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must not be negative");
    }

    @Test
    public void shouldCreateOrderSuccessfullyWithValidRequest() {
        // Arrange
        OrderService orderService = new OrderService();
        OrderRequest request = buildValidRequest();

        // Act
        Order result = orderService.createOrder(request);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PENDING.getCode());
    }

    private OrderRequest buildValidRequest() {
        OrderRequest request = new OrderRequest();
        request.setAmount(BigDecimal.valueOf(100));
        request.setQuantity(2);
        return request;
    }
}
```

### 8.2 Mock 的正确使用

```java
public class PaymentServiceTest {

    @Mock
    private PaymentGateway paymentGateway; // Mock外部依赖，隔离测试单元，避免真实调用第三方支付接口

    @InjectMocks
    private PaymentService paymentService;

    @Test
    public void shouldRetryWhenGatewayTimeout() {
        // Arrange: 模拟第一次调用超时，第二次调用成功，验证重试逻辑
        when(paymentGateway.pay(any()))
                .thenThrow(new TimeoutException("gateway timeout"))
                .thenReturn(PaymentResult.success());

        // Act
        PaymentResult result = paymentService.payWithRetry(buildPaymentRequest());

        // Assert
        assertThat(result.isSuccess()).isTrue();
        verify(paymentGateway, times(2)).pay(any()); // 验证确实重试了一次
    }
}
```

### 8.3 覆盖率标准与测试分层

- **覆盖率不是唯一目标**：单纯追求行覆盖率可能导致"为了覆盖而覆盖"的低质量测试（如没有断言的空跑测试）。应关注**分支覆盖率**和**核心业务逻辑的覆盖完整性**，一般核心模块建议不低于 80%。
- **测试金字塔**：单元测试（数量最多，成本最低，聚焦单个类/方法）→ 集成测试（验证多个组件协作，如数据库、消息队列的实际交互）→ 端到端测试（数量最少，验证完整业务流程，成本最高）。三层比例大致遵循 70%/20%/10% 的经验分布，避免"倒金字塔"（过度依赖端到端测试导致测试跑得慢、定位问题难）。

```java
// 集成测试示例：使用内嵌数据库/Testcontainers验证真实的数据库交互行为
@SpringBootTest
@Testcontainers
public class OrderRepositoryIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private OrderRepository orderRepository;

    @Test
    public void shouldPersistAndRetrieveOrder() {
        Order order = new Order();
        order.setAmount(BigDecimal.valueOf(100));
        orderRepository.save(order);

        Order loaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(loaded.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }
}
```

---

## 九、安全规范

### 9.1 SQL 注入防护

```java
// 反例：字符串拼接SQL，存在严重的SQL注入风险
public List<User> findByName(String name) {
    String sql = "SELECT * FROM user WHERE name = '" + name + "'";
    // 若 name 传入 "' OR '1'='1"，会导致整表数据被查出
    return jdbcTemplate.query(sql, new UserRowMapper());
}

// 正例：使用参数化查询（PreparedStatement），SQL结构与数据完全分离
public List<User> findByName(String name) {
    String sql = "SELECT * FROM user WHERE name = ?";
    return jdbcTemplate.query(sql, new Object[]{name}, new UserRowMapper());
}
```

MyBatis 中同理，必须使用 `#{}` 参数占位符（预编译绑定），禁止对外部输入使用 `${}` 直接字符串替换：

```xml
<!-- 反例：${} 直接做字符串拼接，等同于SQL注入 -->
<select id="findByName" resultType="User">
    SELECT * FROM user WHERE name = '${name}'
</select>

<!-- 正例：#{} 使用预编译占位符 -->
<select id="findByName" resultType="User">
    SELECT * FROM user WHERE name = #{name}
</select>
```

### 9.2 XSS 防护

```java
// 正例：对用户输入的富文本内容进行HTML转义或白名单过滤后再展示，防止脚本注入
public class XssUtils {
    public static String escapeHtml(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
```

对于富文本编辑场景（允许部分 HTML 标签），应使用成熟的白名单过滤库（如 OWASP Java HTML Sanitizer）而非自行编写正则表达式，正则表达式很难覆盖所有绕过 XSS 过滤的边界情况。

### 9.3 CSRF 防护

- 服务端为每个会话生成一次性的 CSRF Token，前端在提交表单/敏感操作请求时携带该 Token，服务端校验通过后才处理请求。
- 关键操作（转账、密码修改）应结合 `SameSite=Strict` Cookie 属性和二次验证机制，进一步降低跨站请求伪造的风险。

### 9.4 敏感数据处理

```java
// 反例：密码明文存储，一旦数据库泄露，所有用户密码直接暴露
user.setPassword(rawPassword);

// 正例：使用加盐哈希算法（如BCrypt），即使数据库泄露也无法直接还原明文密码
public class PasswordUtils {
    public static String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(12)); // 12为计算成本因子，越大越难暴力破解
    }

    public static boolean matches(String rawPassword, String encodedPassword) {
        return BCrypt.checkpw(rawPassword, encodedPassword);
    }
}
```

敏感配置信息（数据库密码、第三方 API 密钥）禁止硬编码在代码中或明文提交到代码仓库，应通过配置中心的加密存储能力或专门的密钥管理服务进行管理，代码中只保留密钥的引用标识。

---

## 十、最佳实践与总结

### 10.1 设计原则回顾

1. 代码首先服务于人的理解，其次才是机器的执行——清晰表达永远优先于炫技式的"聪明写法"。
2. 复杂度应该与实际需求匹配，警惕过度设计带来的维护负担，遵循"Rule of Three"再决定是否抽象。
3. 已知问题要么立即修复，要么建立明确的跟踪计划，杜绝无主的"临时方案"长期存活。
4. 错误应该在最早的环节被发现和拦截，参数校验、类型系统、单元测试都是尽早捕获错误的手段。
5. 安全性和权限控制应该是默认选项，任何放宽限制的行为都应该是显式的、经过评审的决定。

### 10.2 常见陷阱清单

- 包装类型使用 `==` 比较而非 `equals`（超出 `-128~127` 缓存范围时会得到意外结果）。
- 在循环中进行字符串拼接（应使用 `StringBuilder`），大数据量下会产生大量临时对象。
- `equals` 与 `hashCode` 未同时重写，导致对象在 `HashMap`/`HashSet` 中的行为异常。
- 使用可变对象作为 `HashMap` 的 Key，Key 的哈希值在放入后发生变化会导致该条目永久无法被查找到。
- 浮点数直接做精确比较或用于金额计算（应使用 `BigDecimal` 并指定精确的舍入模式）。
- 集合遍历过程中直接调用集合的 `remove` 方法而非使用 `Iterator.remove()`，触发 `ConcurrentModificationException`。
- 线程池使用默认的 `Executors` 快捷方法而非显式参数化的 `ThreadPoolExecutor`。

### 10.3 演进方向

- 随着 Java 版本演进（Record、Sealed Class、Pattern Matching、虚拟线程 Virtual Thread），编码规范也需要与时俱进，例如虚拟线程的普及会大幅改变"线程池参数如何设置"这类传统并发规范的适用边界。
- 静态代码分析工具与 AI 辅助 Code Review 的结合，能够在编码阶段就实时发现更多潜在问题，规范的执行会越来越自动化，人工评审逐步聚焦在工具无法覆盖的架构设计和业务逻辑合理性判断上。
- 规范文档本身应该是活文档（Living Document），跟随团队实践反馈持续修订，而不是一份写完就不再更新的静态制度。

编码规范的本质不是限制自由，而是通过一套经过验证的最佳实践集合，把团队中每个人踩过的坑沉淀下来，转化为大家共同遵守的约定，从而降低协作成本、减少隐蔽 Bug、提升系统的整体可靠性和安全性。
