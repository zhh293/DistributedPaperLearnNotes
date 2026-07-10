# API 设计与网关安全架构设计文档

## 一、问题背景

### 1.1 要解决的核心问题

API 是现代软件系统对外提供能力的唯一契约，也是最容易被忽视规范、最容易成为安全攻击入口的一环。API 设计与安全防护要解决的核心问题可以归纳为四类：

1. **接口契约的一致性与可理解性问题**：如果 API 设计没有统一的规范（URI 风格混乱、状态码使用随意、字段命名不一致），会导致调用方（前端、第三方开发者、其他微服务）频繁误用接口，联调成本高，且接口一旦发布很难在不破坏兼容性的前提下修改。
2. **接口重复调用导致的数据错误问题**：网络环境不可靠，客户端超时重试、消息队列的"至少一次"投递语义、用户重复点击提交按钮，都可能导致同一个业务请求被多次执行。如果接口不具备幂等性，会直接导致重复下单、重复扣款、重复发货等严重业务问题。
3. **恶意流量与接口滥用问题**：开放的 API 天然暴露在公网攻击面之下，面临暴力破解、恶意爬虫、接口刷单、参数篡改、重放攻击等安全威胁，如果没有完善的签名验证、防重放、限流机制，系统会被恶意流量拖垮甚至造成直接的经济损失。
4. **身份认证与权限校验的规模化问题**：随着接入的客户端类型（Web、App、第三方开放平台、内部微服务）越来越多，如何在网关层统一、高效地完成身份认证和基础安全校验，同时不给每个业务接口增加重复的样板代码，是网关安全架构设计的核心课题。

### 1.2 典型场景

- **开放平台 API**：面向第三方开发者开放的接口（如支付网关、物流查询接口），必须通过签名机制验证调用方身份的真实性，防止密钥泄露后被恶意调用方肆意滥用。
- **移动端/前端 API**：面向 App、小程序、Web 前端的接口需要设计合理的版本化策略，应对客户端版本碎片化、灰度发布的兼容性问题。
- **交易类接口**：下单、支付、退款等涉及资金和库存变动的接口，必须具备严格的幂等性保证，任何重复请求都不能导致业务状态的重复变更。
- **高频访问接口**：秒杀、抢购等场景下的接口面临瞬时流量洪峰，需要多维度限流保护后端系统不被压垮。
- **内部微服务间调用**：虽然不直接暴露给外部用户，但同样需要基础的身份认证（服务身份而非用户身份）和调用链路的安全防护，防止内网横向渗透攻击。

### 1.3 不解决的后果

- **接口滥用导致资损**：没有幂等设计的支付接口，一次网络抖动导致的客户端重试可能造成用户被重复扣款，引发大量客诉甚至监管处罚。
- **恶意攻击导致服务不可用**：没有限流和签名验证的开放接口，容易被恶意脚本高频调用（如撞库攻击、薅羊毛脚本），耗尽后端资源导致正常用户无法访问。
- **数据泄露与篡改风险**：缺乏参数签名和加密传输，攻击者可以通过抓包篡改请求参数（如修改订单金额、修改用户 ID 访问他人数据），造成严重的安全事故。
- **接口版本混乱导致维护成本失控**：没有版本化策略的 API，一旦需要变更字段含义或调整业务逻辑，会直接破坏所有存量客户端的正常使用，被迫要求所有客户端同步升级，这在拥有海量存量用户的产品中几乎是不可接受的。

---

## 二、整体架构设计

### 2.1 API 网关在整体架构中的位置

```
客户端（Web/App/第三方开发者/其他微服务）
        │
        ▼
┌───────────────────────────────────────────┐
│                API 网关                     │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐ │
│  │身份认证 │→│签名验签│→│限流熔断│→│路由转发│ │
│  └────────┘ └────────┘ └────────┘ └──────┘ │
└───────────────────────────────────────────┘
        │
        ▼
   后端微服务集群（订单/支付/用户/商品等服务）
```

网关作为所有外部流量的统一入口，承担了本应由每个业务接口重复实现的横切关注点（Cross-Cutting Concerns）：身份认证、签名验证、限流熔断、日志审计、协议转换，让业务服务可以专注于核心业务逻辑本身。

### 2.2 组件职责划分

| 组件 | 职责 |
|---|---|
| **接口契约层** | 定义 URI 规范、请求/响应格式、状态码语义、版本化策略 |
| **认证过滤器** | 校验 Token 有效性、解析用户身份信息 |
| **签名验证过滤器** | 校验请求签名合法性，防止参数篡改和非授权调用 |
| **防重放过滤器** | 基于 nonce + timestamp 防止请求被截获后重放 |
| **限流器** | 多维度（API/用户/IP）限流保护后端服务 |
| **参数校验器** | 统一校验请求参数的合法性，拦截明显非法请求 |
| **熔断器** | 下游服务异常时快速失败，防止故障扩散 |
| **幂等控制层**（通常在业务服务内） | 保证同一逻辑请求多次执行的最终效果与执行一次相同 |

### 2.3 设计权衡

- **网关层校验 vs 业务层校验**：通用的、无需业务上下文的校验（Token 有效性、签名、限流）放在网关层统一处理；依赖具体业务状态的校验（如"订单是否已支付"）必须放在业务服务内部，网关不应该也无法承担业务逻辑判断。
- **强一致幂等 vs 最终一致幂等**：涉及资金的接口必须保证强一致的幂等性（利用数据库唯一约束等强一致手段）；非核心的业务动作（如日志记录、消息通知）可以接受基于时间窗口的近似幂等。
- **安全性与性能的平衡**：签名验证、加解密都会引入额外的计算开销，需要选择合适的算法（如 HMAC-SHA256 而非更慢的非对称加密）在安全强度和性能之间取得平衡。

---

## 三、核心链路设计

### 3.1 RESTful API 设计原则详解

#### 3.1.1 URI 设计规范

URI 应该表达"资源"而非"动作"，用 HTTP 方法表达对资源的操作类型：

```
反例：URI中包含动词，且大小写/复数形式不统一
GET  /getUserInfo?id=123
POST /api/DeleteOrder
GET  /queryOrderList

正例：URI只包含名词化的资源标识，用HTTP方法表达操作语义，统一使用小写连字符
GET    /users/123              # 获取指定用户
GET    /orders                 # 获取订单列表
POST   /orders                 # 创建订单
GET    /orders/{orderId}       # 获取指定订单详情
PUT    /orders/{orderId}       # 全量更新指定订单
PATCH  /orders/{orderId}       # 部分更新指定订单
DELETE /orders/{orderId}       # 删除指定订单
GET    /users/{userId}/orders  # 获取指定用户下的订单列表（嵌套资源表达从属关系）
```

#### 3.1.2 HTTP 方法语义与状态码规范

| HTTP方法 | 语义 | 幂等性 | 安全性（不修改资源） |
|---|---|---|---|
| GET | 查询资源 | 是 | 是 |
| POST | 创建资源 / 执行非幂等操作 | 否 | 否 |
| PUT | 全量替换资源 | 是 | 否 |
| PATCH | 部分更新资源 | 视实现而定 | 否 |
| DELETE | 删除资源 | 是（重复删除同一资源，效果与删除一次相同） | 否 |

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        Order order = orderService.findById(orderId);
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build(); // 404: 资源不存在
        }
        return ResponseEntity.ok(OrderConverter.toResponse(order)); // 200: 成功返回数据
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody @Valid CreateOrderRequest request) {
        Order order = orderService.create(request);
        // 201 Created，并在Location头中返回新创建资源的URI，符合REST规范对创建操作的语义约定
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.getId()))
                .body(OrderConverter.toResponse(order));
    }

    @PutMapping("/{orderId}")
    public ResponseEntity<Void> updateOrder(@PathVariable String orderId, @RequestBody UpdateOrderRequest request) {
        orderService.update(orderId, request);
        return ResponseEntity.noContent().build(); // 204: 更新成功但无返回体
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteOrder(@PathVariable String orderId) {
        orderService.delete(orderId);
        return ResponseEntity.noContent().build();
    }
}
```

常用状态码语义：`200 OK`（成功）、`201 Created`（创建成功）、`204 No Content`（成功但无内容）、`400 Bad Request`（参数错误）、`401 Unauthorized`（未认证）、`403 Forbidden`（已认证但无权限）、`404 Not Found`（资源不存在）、`409 Conflict`（资源冲突，如重复创建）、`429 Too Many Requests`（触发限流）、`500 Internal Server Error`（服务端异常）。

#### 3.1.3 分页、过滤、排序设计

```java
// 统一的分页查询参数设计：page/size表达分页，支持基于游标的分页应对深分页性能问题
@GetMapping
public PageResponse<OrderSummary> listOrders(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status,      // 过滤条件
        @RequestParam(required = false) String sortBy,       // 排序字段
        @RequestParam(defaultValue = "desc") String sortOrder) {

    if (size > 100) {
        throw new IllegalArgumentException("page size must not exceed 100"); // 防止恶意请求超大分页拖垮数据库
    }
    OrderQuery query = OrderQuery.builder()
            .page(page).size(size).status(status)
            .sortBy(sortBy).sortOrder(sortOrder)
            .build();
    return orderService.list(query);
}

// 响应体统一封装分页元信息，调用方无需猜测字段含义
public class PageResponse<T> {
    private List<T> data;
    private int page;
    private int size;
    private long total;
    private boolean hasMore;
}
```

深分页场景（如 `page=10000`）传统 `OFFSET/LIMIT` 会导致数据库全表扫描前面所有跳过的记录，性能急剧下降，生产环境对深分页场景应改用基于游标（Cursor-based Pagination）的方案，即传递上一页最后一条记录的排序字段值作为下一页查询的起点，利用索引直接定位，避免 `OFFSET` 扫描。

### 3.2 API 版本化策略

#### 3.2.1 URI 版本化

```java
// 版本号直接嵌入URI路径中，是最直观、最常见的版本化方案
@RestController
@RequestMapping("/api/v1/orders")
public class OrderControllerV1 { /* ... */ }

@RestController
@RequestMapping("/api/v2/orders")
public class OrderControllerV2 { /* ... 新版本可能返回不同的字段结构 ... */ }
```

- 优点：直观清晰，可以直接通过 URL 区分版本，网关层路由规则简单，便于灰度和监控（可以直接按 URI 前缀统计不同版本的流量占比）。
- 缺点：URI 语义上代表的应该是"资源"而非"版本"，从严格 RESTful 理念角度看不够纯粹；同一资源存在多个 URI 表示。

#### 3.2.2 Header 版本化

```java
// 版本号通过自定义请求头传递，URI保持稳定
@GetMapping("/orders/{orderId}")
public ResponseEntity<?> getOrder(@PathVariable String orderId,
                                    @RequestHeader(value = "API-Version", defaultValue = "1") int version) {
    if (version >= 2) {
        return ResponseEntity.ok(buildV2Response(orderId));
    }
    return ResponseEntity.ok(buildV1Response(orderId));
}
```

- 优点：URI 保持稳定，符合"URI 代表资源本身"的 RESTful 理念，同一资源的不同版本表示视为同一资源的不同"表述形式"（Representation），更贴近 HTTP 内容协商（Content Negotiation）的设计思想。
- 缺点：版本信息不直观，调用方容易遗漏设置请求头；不便于直接在浏览器或简单工具中调试和查看不同版本效果。

#### 3.2.3 Query 参数版本化

```java
// 通过查询参数指定版本，实现简单，但容易与业务查询参数混淆，实践中较少作为主要方案单独使用
@GetMapping("/orders/{orderId}")
public ResponseEntity<?> getOrder(@PathVariable String orderId,
                                    @RequestParam(defaultValue = "1") int v) {
    // ...
}
```

**综合对比与选型建议**：对外开放的公共 API（尤其是需要长期维护多版本共存、涉及第三方开发者接入的开放平台）推荐使用 URI 版本化，因为其可读性和调试友好性最好；企业内部微服务之间的调用，因为客户端和服务端通常由同一团队或紧密协作的团队维护，版本升级的推进相对可控，可以采用 Header 版本化配合契约测试来保证兼容性演进。无论采用哪种方案，版本升级都应遵循"新增字段不影响旧版本、废弃字段先标记不删除、给足客户端迁移窗口期"的兼容性原则，尽量减少真正需要发布新大版本的频率。

### 3.3 接口幂等设计的完整方案

幂等性设计需要覆盖客户端到服务端的完整链路，根据业务场景选择合适的实现方案。

#### 3.3.1 方案一：幂等 Token 机制（适用于"创建型"操作，如提交订单）

核心思路：客户端在真正提交业务请求之前，先向服务端申请一个一次性 Token；提交业务请求时携带该 Token；服务端利用 Redis 的原子操作校验并消费该 Token，确保同一个 Token 只能成功使用一次。

```java
// 第一步：客户端下单前，先获取幂等Token
@RestController
public class IdempotentTokenController {

    private final StringRedisTemplate redisTemplate;

    @GetMapping("/orders/idempotent-token")
    public String getIdempotentToken(@RequestHeader("userId") String userId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = "idempotent:token:" + userId + ":" + token;
        // 设置合理过期时间（如10分钟），避免Token永久占用Redis内存
        redisTemplate.opsForValue().set(key, "1", 10, TimeUnit.MINUTES);
        return token;
    }
}

// 第二步：提交订单请求时携带Token，服务端原子性校验并消费
@Service
public class OrderIdempotentService {

    private final StringRedisTemplate redisTemplate;

    // 使用Lua脚本保证"检查Token是否存在"和"删除Token"这两个操作的原子性，避免并发场景下的竞态条件
    private static final String CHECK_AND_DELETE_SCRIPT =
            "if redis.call('get', KEYS[1]) then " +
            "  redis.call('del', KEYS[1]); " +
            "  return 1; " +
            "else " +
            "  return 0; " +
            "end";

    public boolean checkAndConsumeToken(String userId, String token) {
        String key = "idempotent:token:" + userId + ":" + token;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(CHECK_AND_DELETE_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(key));
        return result != null && result == 1L;
    }
}

@PostMapping("/orders")
public ResponseEntity<?> createOrder(@RequestHeader("Idempotent-Token") String token,
                                       @RequestHeader("userId") String userId,
                                       @RequestBody CreateOrderRequest request) {
    if (!orderIdempotentService.checkAndConsumeToken(userId, token)) {
        // Token不存在或已被消费，说明是重复提交，直接拒绝，不再执行下单逻辑
        throw new DuplicateSubmitException("duplicate request, please retry with a new token");
    }
    Order order = orderService.create(request);
    return ResponseEntity.ok(order);
}
```

- **算法核心**：Lua 脚本保证"查询 + 删除"这个复合操作在 Redis 单线程模型下的原子性，避免网络往返之间可能出现的并发窗口期问题（如果分成两次独立的 Redis 命令调用，两个并发请求可能都通过了"Token 存在"的检查，都执行了业务逻辑）。
- **数据流转**：客户端请求 Token → 服务端生成并写入 Redis（携带过期时间）→ 客户端携带 Token 提交业务请求 → 服务端原子性校验并消费 → 校验通过则执行业务逻辑，否则直接拒绝。

#### 3.3.2 方案二：唯一索引机制（适用于有天然业务唯一键的场景）

```java
// 利用数据库唯一索引，从存储层面兜底保证幂等，即使应用层的幂等判断存在漏洞也有最后一道防线
@Service
public class PaymentService {

    public PaymentResult pay(PaymentRequest request) {
        // request_no 是客户端生成的业务请求唯一标识，数据库对该字段建立唯一索引
        try {
            PaymentRecord record = new PaymentRecord();
            record.setRequestNo(request.getRequestNo());
            record.setOrderId(request.getOrderId());
            record.setAmount(request.getAmount());
            paymentRecordMapper.insert(record); // 唯一索引冲突时会抛出DuplicateKeyException
        } catch (DuplicateKeyException e) {
            // 说明该请求已经处理过，直接查询已有结果返回，而不是报错给调用方
            PaymentRecord existing = paymentRecordMapper.selectByRequestNo(request.getRequestNo());
            return PaymentResult.from(existing);
        }
        return doActualPayment(request);
    }
}
```

数据库唯一索引方案的优势在于**由存储引擎本身的 ACID 特性提供最强的一致性保证**，即使应用层因为并发编程失误引入了竞态条件漏洞，数据库层面的唯一约束依然能够兜底防止脏数据产生，是幂等设计中"纵深防御"思想的典型体现——不应该只依赖单一层面的幂等保证。

#### 3.3.3 方案三：状态机幂等（适用于有明确状态流转的业务，如订单状态变更）

```java
// 利用状态机的状态流转约束，天然实现幂等：只有满足前置状态的更新才会生效，重复调用不会产生副作用
@Service
public class OrderStatusService {

    public boolean markAsPaid(String orderId) {
        // UPDATE语句本身携带状态前置条件，重复执行时因为status已经不是PENDING，条件不满足，影响行数为0
        int affectedRows = orderMapper.updateStatus(orderId, OrderStatus.PAID.getCode(), OrderStatus.PENDING.getCode());
        if (affectedRows == 0) {
            // 影响行数为0说明订单已经是PAID状态（或处于其他不满足条件的状态），是重复调用，直接返回成功（幂等语义）
            log.info("order {} already paid or in unexpected state, skip", orderId);
            return true;
        }
        return true;
    }
}
```

```sql
-- 对应的SQL：只有当前状态是PENDING时才能更新为PAID，天然具备幂等性，无需额外的Token或去重表
UPDATE orders SET status = #{newStatus}, update_time = now()
WHERE id = #{orderId} AND status = #{expectedOldStatus}
```

状态机幂等方案的精妙之处在于**将幂等性判断融入到业务状态流转规则本身**，不需要额外维护 Token 或去重记录，SQL 的 `WHERE` 条件既是业务规则的体现，也天然承担了幂等控制的职责，是最经济高效的幂等实现方式，但仅适用于具有明确、单向状态流转特征的业务场景。

### 3.4 API 安全防护全链路设计

#### 3.4.1 签名算法：HMAC-SHA256 完整实现

签名机制用于验证请求确实来自持有密钥的合法调用方，且请求参数在传输过程中未被篡改。

```java
public class SignatureUtils {

    // 签名生成算法：将所有请求参数按key字典序排序后拼接，加上密钥进行HMAC-SHA256摘要计算
    public static String sign(Map<String, String> params, String secretKey) {
        // 第一步：按参数名字典序排序，保证签名双方（客户端签名、服务端验签）使用完全一致的拼接顺序
        TreeMap<String, String> sortedParams = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if ("sign".equals(entry.getKey())) {
                continue; // 排除sign字段本身参与签名计算
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("&");
        }
        sb.append("key=").append(secretKey); // 拼接密钥，密钥本身不出现在请求参数中，只用于摘要计算

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash).toUpperCase();
        } catch (Exception e) {
            throw new RuntimeException("sign generation failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
```

```java
// 服务端验签过滤器：网关层统一拦截，验签失败的请求不会到达业务服务
@Component
public class SignatureVerifyFilter extends OncePerRequestFilter {

    private final AppKeyService appKeyService; // 根据appId查询对应的密钥

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> params = extractAllParams(request);
        String appId = params.get("appId");
        String clientSign = params.get("sign");

        String secretKey = appKeyService.getSecretKey(appId);
        if (secretKey == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        String serverSign = SignatureUtils.sign(params, secretKey);
        if (!serverSign.equals(clientSign)) {
            log.warn("signature verification failed, appId={}", appId);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return; // 验签失败直接拒绝，不放行到后续业务逻辑
        }
        chain.doFilter(request, response);
    }
}
```

选择 HMAC-SHA256 而非非对称加密（如 RSA）的原因：HMAC 是对称密钥摘要算法，计算速度比非对称加密快 1~2 个数量级，且安全强度对于"验证调用方身份+防篡改"这个场景已经足够，非对称加密通常用于更高安全等级的场景（如需要不共享密钥的多方验证）或需要数字签名不可抵赖性的场景。

#### 3.4.2 防重放攻击：nonce + timestamp 组合方案

签名机制本身无法防止"重放攻击"——攻击者截获一个完全合法的历史请求（包括其合法签名），原样重新发送，服务端验签依然会通过。需要引入时间戳和随机数的组合方案：

```java
@Component
public class ReplayAttackPreventFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private static final long TIMESTAMP_VALID_WINDOW_MILLIS = 5 * 60 * 1000; // 5分钟有效窗口

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String timestampStr = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");

        if (timestampStr == null || nonce == null) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }

        long timestamp = Long.parseLong(timestampStr);
        long now = System.currentTimeMillis();
        // 第一层防护：时间戳必须在合理的时间窗口内，超出窗口的请求（无论是过期重放还是时钟严重偏移）直接拒绝
        if (Math.abs(now - timestamp) > TIMESTAMP_VALID_WINDOW_MILLIS) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }

        // 第二层防护：nonce必须是本窗口期内首次出现，利用Redis的SETNX原子操作实现"首次可用、后续拒绝"
        String nonceKey = "nonce:" + nonce;
        Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(
                nonceKey, "1", TIMESTAMP_VALID_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        if (Boolean.FALSE.equals(firstSeen)) {
            // nonce已经存在，说明这是一次重放请求（相同的nonce在有效期内被重复使用）
            log.warn("replay attack detected, nonce={}", nonce);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return;
        }

        chain.doFilter(request, response);
    }
}
```

- **算法核心**：`timestamp` 限定了"请求必须在一个较短的时间窗口内送达"，`nonce`（随机数，客户端每次请求生成一个新的随机值）配合 Redis `SETNX` 原子操作限定了"同一个 nonce 在有效窗口期内只能被使用一次"，两者组合彻底堵死了重放攻击的空间——即使攻击者截获了完整的请求（包括合法签名），只要超出时间窗口或 nonce 已被使用过，重放请求都会被拒绝。
- **数据流转**：客户端生成 `timestamp` + `nonce` 参与签名计算 → 服务端验签通过后，再校验 `timestamp` 时效性 → 校验 `nonce` 是否首次出现（写入 Redis 并设置与时间窗口一致的过期时间，过期后自动清理，避免 Redis 内存无限增长）。

#### 3.4.3 限流策略：滑动窗口 + 令牌桶组合方案

**多维度限流的必要性**：单一维度的限流无法覆盖所有滥用场景，例如只按 IP 限流无法防御通过代理池分散 IP 的恶意请求，只按用户限流无法防御未登录状态下的恶意扫描，实践中需要 API 维度、用户维度、IP 维度组合使用。

```java
// 基于Redis + Lua实现的滑动窗口限流算法，相比固定窗口能避免"窗口边界瞬时流量翻倍"的问题
public class SlidingWindowRateLimiter {

    private final StringRedisTemplate redisTemplate;

    private static final String SLIDING_WINDOW_SCRIPT =
            "local key = KEYS[1] " +
            "local now = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local limit = tonumber(ARGV[3]) " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " + // 移除窗口外的过期记录
            "local count = redis.call('ZCARD', key) " +               // 统计当前窗口内的请求数
            "if count < limit then " +
            "  redis.call('ZADD', key, now, now .. '-' .. math.random()) " + // 记录本次请求（用有序集合的score表示时间）
            "  redis.call('EXPIRE', key, math.ceil(window / 1000)) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end";

    public boolean tryAcquire(String limitKey, long windowMillis, int limit) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(SLIDING_WINDOW_SCRIPT, Long.class);
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(script, Collections.singletonList(limitKey),
                String.valueOf(now), String.valueOf(windowMillis), String.valueOf(limit));
        return result != null && result == 1L;
    }
}

// 组合多维度限流的过滤器：任意一个维度触发限流即拒绝请求
@Component
public class MultiDimensionRateLimitFilter extends OncePerRequestFilter {

    private final SlidingWindowRateLimiter rateLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiPath = request.getRequestURI();
        String userId = request.getHeader("userId");
        String clientIp = getClientIp(request);

        // API维度限流：保护单个接口不被过载（如某个热点查询接口设置每秒最多10000次）
        if (!rateLimiter.tryAcquire("rate:api:" + apiPath, 1000, 10000)) {
            reject(response, "API_RATE_LIMIT_EXCEEDED");
            return;
        }
        // 用户维度限流：防止单个用户高频调用（如每个用户每分钟最多请求60次）
        if (userId != null && !rateLimiter.tryAcquire("rate:user:" + userId, 60000, 60)) {
            reject(response, "USER_RATE_LIMIT_EXCEEDED");
            return;
        }
        // IP维度限流：防止单个IP高频调用，兼顾防止未登录场景下的恶意扫描（如每个IP每分钟最多请求200次）
        if (!rateLimiter.tryAcquire("rate:ip:" + clientIp, 60000, 200)) {
            reject(response, "IP_RATE_LIMIT_EXCEEDED");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.getWriter().write("{\"error\":\"" + reason + "\"}");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        return (ip == null || ip.isEmpty()) ? request.getRemoteAddr() : ip.split(",")[0].trim();
    }
}
```

滑动窗口相比固定窗口计数器的优势：固定窗口在两个相邻窗口的边界处可能出现流量突刺（如限制"每分钟100次"，恶意请求可以在第 59 秒内发送 100 次，紧接着在第 61 秒再发送 100 次，实际 2 秒内涌入 200 次请求却未触发限流），而滑动窗口通过有序集合精确记录每次请求的时间戳，`ZREMRANGEBYSCORE` 持续清理窗口外的记录，保证任意时刻回溯的时间窗口内请求计数都是精确的，从根本上消除了窗口边界效应。

对于更细粒度的流量整形需求（如允许一定程度的突发流量但整体速率受控），可以在滑动窗口之上叠加令牌桶算法：以固定速率向桶中添加令牌，请求需要消耗令牌才能通过，桶的容量决定了能够容忍的突发流量上限，这在保护下游资源的同时也给正常的突发业务流量留出了弹性空间。

### 3.5 Token 认证体系设计

#### 3.5.1 双 Token 机制：Access Token + Refresh Token

```java
public class TokenService {

    private static final long ACCESS_TOKEN_EXPIRE_MINUTES = 30;   // 短期有效，降低泄露风险
    private static final long REFRESH_TOKEN_EXPIRE_DAYS = 30;     // 长期有效，减少用户重新登录频率

    // Access Token：用于日常接口调用的身份凭证，有效期短，即使泄露影响窗口也有限
    public String generateAccessToken(String userId) {
        return Jwts.builder()
                .setSubject(userId)
                .claim("type", "access")
                .setIssuedAt(new Date())
                .setExpiration(Date.from(Instant.now().plus(ACCESS_TOKEN_EXPIRE_MINUTES, ChronoUnit.MINUTES)))
                .signWith(SignatureAlgorithm.HS256, getSigningKey())
                .compact();
    }

    // Refresh Token：仅用于换取新的Access Token，不直接用于接口调用，有效期长，需要额外的存储和吊销机制
    public String generateRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString();
        // Refresh Token存储在服务端（Redis），可以随时主动吊销，而JWT类型的Access Token一旦签发在有效期内无法主动失效
        redisTemplate.opsForValue().set("refresh_token:" + refreshToken, userId,
                REFRESH_TOKEN_EXPIRE_DAYS, TimeUnit.DAYS);
        return refreshToken;
    }

    // 使用Refresh Token换取新的Access Token，并实施Refresh Token轮换（Rotation）机制
    public TokenPair refreshAccessToken(String oldRefreshToken) {
        String userId = redisTemplate.opsForValue().get("refresh_token:" + oldRefreshToken);
        if (userId == null) {
            throw new UnauthorizedException("invalid or expired refresh token");
        }
        // 轮换：旧Refresh Token立即失效，签发新的Refresh Token，缩小Token泄露后的可利用窗口
        redisTemplate.delete("refresh_token:" + oldRefreshToken);
        String newAccessToken = generateAccessToken(userId);
        String newRefreshToken = generateRefreshToken(userId);
        return new TokenPair(newAccessToken, newRefreshToken);
    }
}
```

**双 Token 机制的设计动机**：如果只使用单一长期有效的 Token，一旦泄露，攻击者可以在很长时间内冒充用户身份；如果只使用短期 Token，用户需要频繁重新登录，体验很差。双 Token 机制通过"短期 Access Token 承担日常调用，长期 Refresh Token 只用于换取新 Access Token 且可随时吊销"的分工，在安全性和用户体验之间取得平衡。Refresh Token 轮换机制（每次使用后立即作废并签发新的）进一步降低了 Refresh Token 本身被截获重放的风险——攻击者即使截获了一次 Refresh Token，只要合法用户先一步完成了一次正常的刷新操作，攻击者手中的 Token 就会失效。

#### 3.5.2 JWT 的结构与 Java 实现

JWT（JSON Web Token）由三部分组成：`Header.Payload.Signature`，均使用 Base64URL 编码。

```java
public class JwtStructureDemo {

    public String buildToken(String userId, List<String> roles) {
        // Header: {"alg": "HS256", "typ": "JWT"} —— 声明签名算法和Token类型
        // Payload: 携带用户身份和自定义声明（Claims），注意Payload只是Base64编码而非加密，不能存放敏感信息
        // Signature: HMACSHA256(base64UrlEncode(header) + "." + base64UrlEncode(payload), secretKey)
        return Jwts.builder()
                .setHeaderParam("typ", "JWT")
                .setSubject(userId)
                .claim("roles", roles)
                .setIssuedAt(new Date())
                .setExpiration(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)))
                .signWith(SignatureAlgorithm.HS256, "secretKeyShouldBeLongEnough".getBytes())
                .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .setSigningKey("secretKeyShouldBeLongEnough".getBytes())
                    .parseClaimsJws(token) // 内部会自动校验Signature并校验exp是否过期
                    .getBody();
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("token expired");
        } catch (SignatureException e) {
            throw new UnauthorizedException("invalid token signature");
        }
    }
}
```

JWT 的核心优势是**自包含（Self-Contained）**——服务端验证 Token 时无需查询数据库或缓存，只需通过签名验证即可确认 Token 未被篡改，这使得 JWT 天然适合分布式、无状态的微服务架构（任意一个服务节点都能独立完成验证，无需共享 Session 存储）。但也正因为自包含的特性，JWT **一旦签发在有效期内无法主动吊销**（除非引入额外的黑名单机制），这是设计双 Token 体系、将 Access Token 有效期设置得较短的重要原因之一。

### 3.6 API 网关安全组件的责任链设计

网关中的多个安全组件（鉴权、参数校验、限流、熔断）通过责任链模式（Chain of Responsibility）组织，请求依次经过每个过滤器，任意一环拦截即终止后续处理：

```java
public abstract class GatewayFilter {
    protected GatewayFilter next;

    public GatewayFilter setNext(GatewayFilter next) {
        this.next = next;
        return next;
    }

    public abstract FilterResult doFilter(GatewayRequest request);

    protected FilterResult proceed(GatewayRequest request) {
        return next != null ? next.doFilter(request) : FilterResult.pass();
    }
}

public class AuthenticationFilter extends GatewayFilter {
    @Override
    public FilterResult doFilter(GatewayRequest request) {
        if (!tokenService.isValid(request.getToken())) {
            return FilterResult.reject(401, "unauthorized");
        }
        return proceed(request); // 通过则交给链条中的下一个过滤器
    }
}

public class ParamValidationFilter extends GatewayFilter {
    @Override
    public FilterResult doFilter(GatewayRequest request) {
        if (!paramValidator.validate(request)) {
            return FilterResult.reject(400, "invalid parameters");
        }
        return proceed(request);
    }
}

public class RateLimitFilter extends GatewayFilter {
    @Override
    public FilterResult doFilter(GatewayRequest request) {
        if (!rateLimiter.tryAcquire(request)) {
            return FilterResult.reject(429, "rate limit exceeded");
        }
        return proceed(request);
    }
}

public class CircuitBreakerFilter extends GatewayFilter {
    @Override
    public FilterResult doFilter(GatewayRequest request) {
        if (circuitBreaker.isOpen(request.getTargetService())) {
            return FilterResult.reject(503, "service unavailable, circuit open");
        }
        return proceed(request);
    }
}

// 组装责任链：认证 -> 参数校验 -> 限流 -> 熔断 -> 路由转发，各司其职，顺序可根据实际需要调整
public class GatewayFilterChainBuilder {
    public GatewayFilter build() {
        GatewayFilter authFilter = new AuthenticationFilter();
        GatewayFilter paramFilter = new ParamValidationFilter();
        GatewayFilter rateLimitFilter = new RateLimitFilter();
        GatewayFilter circuitBreakerFilter = new CircuitBreakerFilter();

        authFilter.setNext(paramFilter).setNext(rateLimitFilter).setNext(circuitBreakerFilter);
        return authFilter;
    }
}
```

责任链模式的价值在于**每个过滤器只关注自己的单一职责，新增或调整安全策略时只需要增删链条中的节点，不需要改动其他过滤器的代码**，符合开闭原则；同时链条的执行顺序本身也蕴含了性能优化的考量——应该把开销小、拦截命中率高的校验（如 Token 有效性这种内存/缓存级别的快速判断）放在链条前面，把开销较大的校验（如需要调用下游服务判断的熔断状态）放在后面，尽早拒绝不合法的请求，减少不必要的资源消耗。

---

## 四、异常处理与容错机制

### 4.1 统一异常处理

网关和业务服务都应该有统一的异常处理机制，避免将内部实现细节（堆栈信息、SQL 语句片段）暴露给客户端：

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_PARAM", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", "authentication required"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        String traceId = MDC.get("traceId");
        log.error("unexpected error, traceId={}", traceId, e); // 详细堆栈只记录在服务端日志
        // 对外只暴露通用错误信息和traceId，具体原因由排查人员通过traceId查日志定位，避免泄露实现细节
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("SYSTEM_ERROR", "system busy, traceId=" + traceId));
    }
}
```

### 4.2 签名校验失败与重放攻击的处置

- **签名校验失败**：应统一返回 `401`，不应该在错误信息中提示"应该如何构造正确的签名"（如提示"缺少某个具体参数"），避免向攻击者泄露过多信息帮助其调整攻击策略。
- **高频签名失败告警**：如果同一 `appId` 或 IP 在短时间内连续出现签名校验失败，应触发安全告警甚至临时封禁，因为这通常意味着存在暴力破解密钥或参数篡改的攻击尝试。

### 4.3 幂等场景下的失败重试处理

```java
// 幂等Token方案下，如果业务逻辑执行到一半失败，需要考虑Token是否应该被"归还"以支持客户端重试
@PostMapping("/orders")
public ResponseEntity<?> createOrder(@RequestHeader("Idempotent-Token") String token,
                                       @RequestBody CreateOrderRequest request) {
    if (!idempotentService.checkAndConsumeToken(request.getUserId(), token)) {
        throw new DuplicateSubmitException("duplicate request");
    }
    try {
        Order order = orderService.create(request);
        return ResponseEntity.ok(order);
    } catch (Exception e) {
        // 业务执行失败，如果失败原因是系统性错误（非业务规则拒绝），应考虑将Token返还，避免客户端无法重试
        if (isSystemError(e)) {
            idempotentService.restoreToken(request.getUserId(), token);
        }
        throw e;
    }
}
```

这里需要区分两类失败：**业务规则拒绝**（如库存不足）通常不应该返还 Token，因为重试的结果大概率仍然是失败；**系统性错误**（如数据库连接超时）则应该考虑返还 Token 或引导客户端使用相同 Token 重试，因为这类失败往往是瞬时的，重试可能成功，同时利用同一 Token 重试也保证了不会因为客户端换新 Token 重试而意外绕过幂等保护。

### 4.4 限流触发后的降级与用户体验

被限流的请求不应该只是冷冰冰地返回错误，而应该结合业务场景设计合理的降级体验：

- **返回缓存的旧数据**：查询类接口被限流时，可以降级返回上一次缓存的结果，而非直接报错。
- **排队机制**：秒杀等场景可以将超出瞬时处理能力的请求放入排队队列，异步告知用户排队进度，而非直接拒绝。
- **明确的重试提示**：返回 `Retry-After` 响应头，告知客户端合理的重试等待时间，避免客户端立即重试加剧限流压力。

---

## 五、性能优化

### 5.1 签名验证的性能优化

- **算法选择**：HMAC-SHA256 的计算开销远低于 RSA 等非对称加密算法，在同等安全要求下应优先选择对称摘要算法。
- **密钥缓存**：`appId` 对应的密钥应该缓存在本地内存或分布式缓存中，避免每次验签都查询数据库。

### 5.2 限流组件的性能优化

- **本地缓存 + 分布式限流的组合**：纯粗粒度限流（如接口级别的总量控制）可以在网关每个节点本地做一次快速的粗粒度拦截，减少对 Redis 的请求量；精确限流（如用户级别的精确计数）再走 Redis 集中式判断，兼顾性能和精确度。
- **Lua 脚本原子化**：将"读取计数、判断、写入"等多步操作封装为一个 Lua 脚本在 Redis 端原子执行，避免多次网络往返带来的延迟叠加和并发竞态问题。

### 5.3 网关整体性能指标参考

一个经过合理优化的网关集群，各安全组件的性能开销大致如下（供参考，实际数值因硬件和实现细节而异）：

- Token 校验（本地缓存命中）：亚毫秒级。
- HMAC-SHA256 签名验证：微秒级。
- Redis 滑动窗口限流判断（含网络 RTT）：1~3 毫秒。
- 整体网关层引入的额外延迟：控制在 5~10 毫秒以内是比较合理的目标，超过这个范围需要重新审视各组件的实现效率。

### 5.4 API 设计对性能的影响

- **避免 N+1 查询模式的接口设计**：如获取订单列表后前端再逐个调用获取每个订单的商品详情，应该提供批量接口一次性返回，减少客户端与服务端之间的网络往返次数。
- **合理的字段裁剪**：支持客户端按需指定返回字段（Field Selection）或使用 GraphQL 这类支持精确取数的方案，减少不必要的数据传输和序列化开销，对移动端弱网场景尤其重要。

---

## 六、最佳实践与总结

### 6.1 设计原则总结

1. **API 应该表达业务意图，而非技术实现细节**：URI 设计面向资源，参数和字段命名使用业务语言，让 API 契约本身就是最好的文档。
2. **安全防护应该纵深布局**：签名验证、防重放、限流、幂等控制应该在网关层和业务层协同配合，任何单一环节都不应该被视为唯一防线。
3. **幂等性设计要与业务语义匹配**：创建型操作用 Token 机制，有唯一键的场景用数据库约束兜底，有状态流转的场景利用状态机本身实现幂等，选择与业务特征最契合的方案而非一刀切。
4. **版本演进要以兼容性为前提**：新增字段不影响旧客户端解析，废弃字段给足迁移窗口期，是长期维护开放 API 的基本原则。

### 6.2 常见陷阱

- **只做了签名验证而忽视防重放**：签名能证明"请求来自合法调用方且参数未被篡改"，但无法防止合法请求被截获后原样重放，必须配合 `timestamp`+`nonce` 使用。
- **幂等 Token 有效期设置不合理**：过短会导致用户思考时间稍长就无法提交（Token 过期）；过长会导致 Redis 中堆积大量长期未消费的 Token 占用内存。
- **限流阈值一刀切**：不同接口的承载能力差异巨大，应根据压测结果为每个接口设置符合其实际承载能力的限流阈值，而非全局统一一个数值。
- **把所有校验都放在网关层**：网关层不应该承担依赖具体业务状态的校验逻辑（如"当前用户是否有权限操作这个具体订单"，这需要查询订单归属关系），这类校验应该下沉到业务服务内部完成，网关只负责与业务无关的通用安全校验。
- **JWT Payload 中存放敏感信息**：JWT 的 Payload 只是 Base64 编码，任何人都可以解码查看内容，绝不能在其中存放密码、身份证号等敏感信息。

### 6.3 演进方向

- **零信任架构（Zero Trust）理念的引入**：不再默认信任内网调用，每一次服务间调用都要经过身份认证和授权校验，进一步收窄攻击面。
- **API 网关与服务网格能力的融合**：越来越多的横切关注点（限流、熔断、认证）下沉到基础设施层（如 Sidecar 代理），业务代码进一步减负，安全策略的调整可以通过配置下发而无需重新发布业务服务。
- **风险自适应的动态防护策略**：结合实时风控和异常检测能力，对请求特征进行综合评分，对高风险请求动态提升验证强度（如触发二次验证），对低风险的常规请求保持顺畅的用户体验，实现安全性和体验的动态平衡，而非静态的一刀切规则。

API 设计与网关安全的本质，是在"开放能力供合法调用方高效使用"和"防止恶意方滥用系统能力"之间找到平衡点。良好的 API 设计降低了合法调用方的使用门槛和误用概率，完善的网关安全体系则构筑起抵御恶意流量的纵深防线，两者相辅相成，共同支撑起一个既开放又安全的系统对外服务能力。

---

## 七、全链路实战案例

前面章节分别拆解了 API 设计、幂等、签名、防重放、限流等单点能力，本节以三个贯穿客户端到服务端的完整业务场景为例，串联多个组件，展示一次真实请求从进入网关到最终返回的全部处理过程，每个案例均包含完整可运行的 Java 代码、异常处理、日志埋点与幂等控制。

### 7.1 案例一：RESTful API 版本管理全链路

**业务场景**：订单查询接口经历了从 v1 到 v2 的字段结构升级（v2 新增了优惠明细字段，且将金额字段从"元"调整为"分"存储以避免精度问题），需要同时兼容存量 v1 客户端和已升级的 v2 客户端，并逐步引导 v1 客户端完成迁移。

**全链路结构**：客户端请求 → API 版本路由（网关层解析版本号）→ 版本兼容处理（服务层按版本适配响应结构）→ 废弃版本迁移提示（响应头告警 + 埋点统计）→ 响应返回。

#### 7.1.1 版本路由：网关层统一解析版本号

版本号来源优先级为 URI 路径 > 请求头，兼容两种版本化策略共存的过渡期：

```java
/**
 * API版本上下文：贯穿一次请求生命周期的版本信息载体，避免在每一层都重复解析版本号
 */
public class ApiVersionContext {

    private static final ThreadLocal<Integer> VERSION_HOLDER = new ThreadLocal<>();

    public static void set(int version) {
        VERSION_HOLDER.set(version);
    }

    public static int get() {
        Integer version = VERSION_HOLDER.get();
        return version == null ? 1 : version; // 未显式指定版本时默认视为v1，保证存量客户端不受影响
    }

    public static void clear() {
        VERSION_HOLDER.remove(); // 必须在请求结束时清理，防止线程池复用导致的版本信息串号
    }
}

/**
 * 版本路由过滤器：统一从URI或请求头中解析API版本，写入线程上下文，供后续Controller/Service层读取
 */
@Component
@Order(1) // 版本解析必须在所有业务过滤器之前完成，后续鉴权、限流等环节可能需要按版本差异化处理
public class ApiVersionRouteFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiVersionRouteFilter.class);
    private static final Pattern URI_VERSION_PATTERN = Pattern.compile("^/api/v(\\d+)/.*");
    private static final int MAX_SUPPORTED_VERSION = 2;
    private static final int MIN_SUPPORTED_VERSION = 1;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get("traceId");
        try {
            int version = resolveVersion(request);
            if (version < MIN_SUPPORTED_VERSION || version > MAX_SUPPORTED_VERSION) {
                log.warn("unsupported api version requested, traceId={}, uri={}, version={}",
                        traceId, request.getRequestURI(), version);
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":\"UNSUPPORTED_VERSION\",\"message\":\"api version "
                        + version + " is not supported, supported range: [" + MIN_SUPPORTED_VERSION
                        + "," + MAX_SUPPORTED_VERSION + "]\"}");
                return;
            }
            ApiVersionContext.set(version);
            log.info("api version resolved, traceId={}, uri={}, version={}", traceId, request.getRequestURI(), version);
            chain.doFilter(request, response);
        } catch (NumberFormatException e) {
            // 版本号格式非法（如请求头传了非数字字符串），属于客户端参数错误，不应作为系统异常处理
            log.warn("invalid api version format, traceId={}, uri={}", traceId, request.getRequestURI(), e);
            response.setStatus(HttpStatus.BAD_REQUEST.value());
        } finally {
            ApiVersionContext.clear(); // 无论成功失败都要清理，防止ThreadLocal内存泄漏
        }
    }

    private int resolveVersion(HttpServletRequest request) {
        Matcher matcher = URI_VERSION_PATTERN.matcher(request.getRequestURI());
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1)); // URI中的版本号优先级最高
        }
        String headerVersion = request.getHeader("API-Version");
        if (headerVersion != null && !headerVersion.isEmpty()) {
            return Integer.parseInt(headerVersion.trim());
        }
        return MIN_SUPPORTED_VERSION; // 两者都未指定时，兜底为最早版本，保证向后兼容
    }
}
```

- **算法核心**：版本解析结果通过 `ThreadLocal` 在一次请求内传递，避免每一层都重新解析 URI 或请求头；`finally` 块中强制清理 `ThreadLocal`，是使用 `ThreadLocal` 时防止内存泄漏和线程复用串号的强制性规范。
- **数据流转**：客户端请求 → 过滤器解析版本号（URI 优先，其次请求头，都缺失则兜底为 v1）→ 版本号写入 `ApiVersionContext` → 放行给 Controller，Controller/Service 层通过 `ApiVersionContext.get()` 读取版本号做差异化处理。

#### 7.1.2 版本兼容处理：服务层按版本适配响应结构

```java
/**
 * 订单查询响应的版本适配器：将内部统一的领域模型转换为不同版本客户端期望的响应结构
 * 核心原则：内部领域模型（Order）只维护一份，版本差异只体现在转换层，避免业务逻辑因版本分叉而重复
 */
@Component
public class OrderResponseVersionAdapter {

    private static final Logger log = LoggerFactory.getLogger(OrderResponseVersionAdapter.class);

    public Object adapt(Order order, int version) {
        switch (version) {
            case 1:
                return toV1Response(order);
            case 2:
                return toV2Response(order);
            default:
                // 理论上网关层已经拦截了非法版本，这里是防御性兜底，一旦触发说明版本路由与适配器出现了不一致
                log.error("unexpected version reached adapter, version={}, orderId={}", version, order.getId());
                throw new IllegalStateException("unsupported version in adapter: " + version);
        }
    }

    private OrderResponseV1 toV1Response(Order order) {
        OrderResponseV1 resp = new OrderResponseV1();
        resp.setOrderId(order.getId());
        resp.setAmount(order.getAmountInCents() / 100.0); // v1约定金额单位为"元"，需要从内部统一的"分"换算
        resp.setStatus(order.getStatus().name());
        return resp;
    }

    private OrderResponseV2 toV2Response(Order order) {
        OrderResponseV2 resp = new OrderResponseV2();
        resp.setOrderId(order.getId());
        resp.setAmountInCents(order.getAmountInCents()); // v2约定金额单位为"分"，与内部存储保持一致，避免精度损失
        resp.setStatus(order.getStatus().name());
        resp.setDiscountDetails(order.getDiscountDetails()); // v2新增字段，v1客户端不会感知，不影响其解析
        return resp;
    }
}

public class OrderResponseV1 {
    private String orderId;
    private double amount;
    private String status;
    // getter/setter省略
}

public class OrderResponseV2 {
    private String orderId;
    private long amountInCents;
    private String status;
    private List<DiscountDetail> discountDetails;
    // getter/setter省略
}
```

#### 7.1.3 废弃版本迁移引导：响应头告警 + 埋点统计

```java
/**
 * 废弃版本告警组件：对已标记为废弃（deprecated）但仍在服务的版本，在响应中附加迁移提示，
 * 并异步记录调用方信息，供运营/研发同学统计存量v1流量占比，评估下线时机
 */
@Component
public class DeprecatedVersionNoticeService {

    private static final Logger log = LoggerFactory.getLogger(DeprecatedVersionNoticeService.class);
    private static final Set<Integer> DEPRECATED_VERSIONS = Set.of(1); // v1已进入废弃倒计时，v2为当前推荐版本
    private static final String SUNSET_DATE = "2025-12-31"; // 计划下线日期，遵循HTTP Sunset header语义

    private final StringRedisTemplate redisTemplate;
    private final ThreadPoolTaskExecutor asyncExecutor;

    public DeprecatedVersionNoticeService(StringRedisTemplate redisTemplate, ThreadPoolTaskExecutor asyncExecutor) {
        this.redisTemplate = redisTemplate;
        this.asyncExecutor = asyncExecutor;
    }

    public void notifyIfDeprecated(HttpServletResponse response, int version, String appId) {
        if (!DEPRECATED_VERSIONS.contains(version)) {
            return;
        }
        // 遵循HTTP标准的Deprecation/Sunset响应头规范，客户端可以据此在日志中主动感知即将下线的版本
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", SUNSET_DATE);
        response.setHeader("Link", "</docs/migration/v1-to-v2>; rel=\"deprecation\"");

        // 埋点统计放到异步线程池执行，避免因为Redis抖动拖慢主链路的响应耗时
        asyncExecutor.execute(() -> {
            try {
                String dayKey = "deprecated:v" + version + ":" + LocalDate.now();
                redisTemplate.opsForHyperLogLog().add(dayKey, appId); // 用HyperLogLog统计当日调用该废弃版本的去重appId数
                redisTemplate.expire(dayKey, 90, TimeUnit.DAYS); // 保留90天用于趋势分析，避免无限增长
            } catch (Exception e) {
                // 埋点失败不应该影响主链路，只记录日志用于后续排查监控管道是否异常
                log.warn("record deprecated version usage failed, version={}, appId={}", version, appId, e);
            }
        });
        log.info("deprecated version invoked, version={}, appId={}, sunsetDate={}", version, appId, SUNSET_DATE);
    }
}
```

#### 7.1.4 Controller 整合与统一异常处理

```java
@RestController
@RequestMapping("/api/{version}/orders")
public class OrderVersionedController {

    private static final Logger log = LoggerFactory.getLogger(OrderVersionedController.class);

    private final OrderQueryService orderQueryService;
    private final OrderResponseVersionAdapter versionAdapter;
    private final DeprecatedVersionNoticeService deprecatedVersionNoticeService;

    public OrderVersionedController(OrderQueryService orderQueryService,
                                     OrderResponseVersionAdapter versionAdapter,
                                     DeprecatedVersionNoticeService deprecatedVersionNoticeService) {
        this.orderQueryService = orderQueryService;
        this.versionAdapter = versionAdapter;
        this.deprecatedVersionNoticeService = deprecatedVersionNoticeService;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable String orderId,
                                       @RequestHeader(value = "appId", required = false) String appId,
                                       HttpServletResponse response) {
        int version = ApiVersionContext.get();
        String traceId = MDC.get("traceId");
        log.info("query order request received, traceId={}, orderId={}, version={}, appId={}",
                traceId, orderId, version, appId);
        try {
            Order order = orderQueryService.findById(orderId);
            if (order == null) {
                log.warn("order not found, traceId={}, orderId={}", traceId, orderId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("ORDER_NOT_FOUND", "order does not exist: " + orderId));
            }
            Object versionedResponse = versionAdapter.adapt(order, version);
            deprecatedVersionNoticeService.notifyIfDeprecated(response, version, appId);
            return ResponseEntity.ok(versionedResponse);
        } catch (IllegalStateException e) {
            // 版本适配器内部一致性异常，属于系统配置错误而非客户端问题，需要按500处理并高优排查
            log.error("version adapt failed, traceId={}, orderId={}, version={}", traceId, orderId, version, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("VERSION_ADAPT_ERROR", "system busy, traceId=" + traceId));
        } catch (Exception e) {
            log.error("query order failed unexpectedly, traceId={}, orderId={}, version={}", traceId, orderId, version, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("SYSTEM_ERROR", "system busy, traceId=" + traceId));
        }
    }
}
```

**幂等说明**：查询类接口（GET）天然满足幂等语义，无需额外的幂等控制，本案例的重点在于版本路由与兼容处理，是"读路径"全链路的典型代表；如果该链路涉及写操作（如按版本发布的下单接口），应在此基础上叠加 7.2 节或原文 3.3 节的幂等方案。

**全链路小结**：客户端请求携带版本标识 → `ApiVersionRouteFilter` 统一解析版本并做合法性校验（非法版本直接拒绝，不透传到业务层）→ `OrderVersionedController` 从线程上下文取版本号驱动业务查询 → `OrderResponseVersionAdapter` 将统一的内部领域模型转换为对应版本的响应结构 → `DeprecatedVersionNoticeService` 对废弃版本异步告警并埋点统计，为后续下线决策提供数据支撑 → 最终按版本差异化的响应体返回给客户端。整条链路中版本判断逻辑只集中在路由和适配两处，业务查询逻辑（`OrderQueryService`）完全不感知版本差异，避免了版本分支污染核心业务代码。

---

### 7.2 案例二：API 防重放攻击全链路

**业务场景**：开放平台的资金类接口（如余额转账）面向第三方调用方开放，必须防止请求被截获后原样重放导致重复扣款，需要在原文 3.4.1、3.4.2 节签名与防重放机制的基础上，补全从客户端组装请求到服务端逐层校验放行的完整代码闭环。

**全链路结构**：请求签名（客户端组装参数并计算签名）→ 时间戳校验（服务端校验请求时效性）→ Nonce 防重放（服务端校验随机数唯一性）→ 签名验证（服务端重新计算签名并比对）→ 请求放行（进入业务逻辑）。

#### 7.2.1 客户端：组装请求并计算签名（模拟调用方视角）

```java
/**
 * 开放平台客户端SDK的请求组装示例：展示调用方应如何正确构造一个带签名、防重放要素的请求
 * 实际生产环境该逻辑由SDK封装，此处展示核心步骤供服务端联调参考
 */
public class OpenApiRequestBuilder {

    private final String appId;
    private final String secretKey;

    public OpenApiRequestBuilder(String appId, String secretKey) {
        this.appId = appId;
        this.secretKey = secretKey;
    }

    public Map<String, String> buildSignedRequest(Map<String, String> bizParams) {
        Map<String, String> params = new HashMap<>(bizParams);
        params.put("appId", appId);
        params.put("timestamp", String.valueOf(System.currentTimeMillis())); // 每次请求生成新的时间戳
        params.put("nonce", UUID.randomUUID().toString().replace("-", "")); // 每次请求生成新的随机数，杜绝重复
        String sign = SignatureUtils.sign(params, secretKey); // 复用原文3.4.1节的签名算法，保证客户端与服务端一致
        params.put("sign", sign);
        return params;
    }
}
```

#### 7.2.2 服务端：时间戳、Nonce、签名的三段式校验过滤器

将时间戳校验、Nonce 防重放、签名验证整合为一条完整的责任链，任意一环失败立即拒绝并记录审计日志，全部通过才放行进入业务逻辑（为避免与原文 3.4.2 节 `ReplayAttackPreventFilter` 同名类冲突，本案例中的过滤器命名为 `AntiReplaySignatureFilter`，是三项校验能力的完整整合实现）：

```java
@Component
@Order(2) // 排在版本路由之后、限流之前：先确认请求身份合法，再消耗限流配额，避免非法请求也占用限流名额
public class AntiReplaySignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AntiReplaySignatureFilter.class);
    private static final long TIMESTAMP_VALID_WINDOW_MILLIS = 5 * 60 * 1000; // 5分钟有效窗口，与客户端约定一致
    private static final String SIGN_FAIL_COUNTER_PREFIX = "sign:fail:count:";
    private static final int SIGN_FAIL_ALERT_THRESHOLD = 10; // 5分钟内连续验签失败超过10次触发告警

    private final StringRedisTemplate redisTemplate;
    private final AppKeyService appKeyService;
    private final SecurityAlertService securityAlertService;

    public AntiReplaySignatureFilter(StringRedisTemplate redisTemplate,
                                      AppKeyService appKeyService,
                                      SecurityAlertService securityAlertService) {
        this.redisTemplate = redisTemplate;
        this.appKeyService = appKeyService;
        this.securityAlertService = securityAlertService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只对开放平台的资金类接口生效，内部管理接口走独立的鉴权链路，避免误伤
        return !request.getRequestURI().startsWith("/openapi/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get("traceId");
        Map<String, String> params = extractAllParams(request);
        String appId = params.get("appId");

        try {
            // 第一步：基础参数完整性校验，缺失关键字段直接拒绝，不进入后续任何计算
            if (isBlank(appId) || isBlank(params.get("timestamp"))
                    || isBlank(params.get("nonce")) || isBlank(params.get("sign"))) {
                log.warn("missing required security params, traceId={}, appId={}", traceId, appId);
                reject(response, HttpStatus.BAD_REQUEST, "MISSING_SECURITY_PARAMS", "missing required security fields");
                return;
            }

            // 第二步：时间戳有效性校验，拦截过期请求或时钟严重偏移的请求
            if (!checkTimestamp(params.get("timestamp"))) {
                log.warn("timestamp out of valid window, traceId={}, appId={}, timestamp={}",
                        traceId, appId, params.get("timestamp"));
                reject(response, HttpStatus.BAD_REQUEST, "TIMESTAMP_EXPIRED", "request timestamp out of valid window");
                return;
            }

            // 第三步：Nonce防重放校验，利用Redis SETNX保证同一nonce在窗口期内只能消费一次
            String nonce = params.get("nonce");
            if (!checkAndMarkNonce(nonce)) {
                log.warn("replay attack detected, traceId={}, appId={}, nonce={}", traceId, appId, nonce);
                reject(response, HttpStatus.BAD_REQUEST, "REPLAY_DETECTED", "duplicate nonce detected");
                return;
            }

            // 第四步：签名验证，验证请求确实来自持有密钥的合法调用方且参数未被篡改
            String secretKey = appKeyService.getSecretKey(appId);
            if (secretKey == null) {
                log.warn("unknown appId, traceId={}, appId={}", traceId, appId);
                reject(response, HttpStatus.UNAUTHORIZED, "UNKNOWN_APP_ID", "appId not registered");
                return;
            }
            String clientSign = params.get("sign");
            String serverSign = SignatureUtils.sign(params, secretKey);
            if (!serverSign.equals(clientSign)) {
                handleSignFailure(appId, traceId);
                reject(response, HttpStatus.UNAUTHORIZED, "SIGNATURE_INVALID", "signature verification failed");
                return;
            }

            // 全部校验通过：清零该appId的连续失败计数，放行进入业务逻辑
            redisTemplate.delete(SIGN_FAIL_COUNTER_PREFIX + appId);
            log.info("anti-replay signature check passed, traceId={}, appId={}", traceId, appId);
            chain.doFilter(request, response);
        } catch (Exception e) {
            // 校验链路自身发生非预期异常（如Redis连接异常），按系统错误处理，不能因为安全组件故障而误放行请求
            log.error("anti-replay filter internal error, traceId={}, appId={}", traceId, appId, e);
            reject(response, HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_CHECK_ERROR", "system busy, traceId=" + traceId);
        }
    }

    private boolean checkTimestamp(String timestampStr) {
        try {
            long timestamp = Long.parseLong(timestampStr);
            return Math.abs(System.currentTimeMillis() - timestamp) <= TIMESTAMP_VALID_WINDOW_MILLIS;
        } catch (NumberFormatException e) {
            return false; // 时间戳格式非法，一律视为校验不通过
        }
    }

    private boolean checkAndMarkNonce(String nonce) {
        String nonceKey = "nonce:" + nonce;
        Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(
                nonceKey, "1", TIMESTAMP_VALID_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(firstSeen);
    }

    // 连续验签失败计数与告警：识别可能的密钥暴力破解或参数篡改攻击尝试
    private void handleSignFailure(String appId, String traceId) {
        String counterKey = SIGN_FAIL_COUNTER_PREFIX + appId;
        Long failCount = redisTemplate.opsForValue().increment(counterKey);
        if (failCount != null && failCount == 1L) {
            redisTemplate.expire(counterKey, TIMESTAMP_VALID_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
        }
        log.warn("signature verification failed, traceId={}, appId={}, failCount={}", traceId, appId, failCount);
        if (failCount != null && failCount >= SIGN_FAIL_ALERT_THRESHOLD) {
            // 触发安全告警，交由安全团队或自动化风控介入（如临时封禁该appId）
            securityAlertService.alertSuspiciousActivity(appId, "CONTINUOUS_SIGN_FAILURE", failCount);
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private Map<String, String> extractAllParams(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                result.put(key, values[0]);
            }
        });
        return result;
    }
}
```

#### 7.2.3 安全告警服务与业务接口的幂等兜底

```java
/**
 * 安全告警服务：连续验签失败达到阈值时触发，与具体告警渠道（短信/大象/邮件）解耦
 */
@Service
public class SecurityAlertService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAlertService.class);

    public void alertSuspiciousActivity(String appId, String reason, long failCount) {
        try {
            // 实际生产环境应对接告警平台API，此处以日志形式模拟告警动作，避免示例代码引入外部依赖
            log.error("[SECURITY ALERT] suspicious activity detected, appId={}, reason={}, failCount={}",
                    appId, reason, failCount);
        } catch (Exception e) {
            // 告警本身失败不应该影响主链路的拒绝逻辑，只记录日志，避免告警组件故障放大为可用性问题
            log.warn("send security alert failed, appId={}, reason={}", appId, reason, e);
        }
    }
}

/**
 * 转账业务接口：签名和防重放已在网关过滤器层完成，业务层依然叠加幂等控制作为纵深防御的最后一道防线，
 * 因为nonce防重放解决的是"同一物理请求不被重复处理"，而幂等键解决的是"同一笔业务语义不被重复执行"，
 * 两者维度不同（例如客户端因超时主动生成新nonce重试同一笔转账，nonce防重放无法识别，必须依赖业务幂等键）
 */
@RestController
@RequestMapping("/openapi/v1/transfers")
public class TransferController {

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<?> transfer(@RequestBody @Valid TransferRequest request) {
        String traceId = MDC.get("traceId");
        log.info("transfer request received, traceId={}, requestNo={}, amount={}",
                traceId, request.getRequestNo(), request.getAmountInCents());
        try {
            TransferResult result = transferService.transfer(request);
            log.info("transfer completed, traceId={}, requestNo={}, status={}",
                    traceId, request.getRequestNo(), result.getStatus());
            return ResponseEntity.ok(result);
        } catch (DuplicateKeyException e) {
            // requestNo唯一索引冲突，说明是重复请求，查询已有结果幂等返回，而非报错
            log.info("duplicate transfer request detected, traceId={}, requestNo={}", traceId, request.getRequestNo());
            TransferResult existing = transferService.findByRequestNo(request.getRequestNo());
            return ResponseEntity.ok(existing);
        } catch (InsufficientBalanceException e) {
            log.warn("transfer rejected due to insufficient balance, traceId={}, requestNo={}",
                    traceId, request.getRequestNo());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("INSUFFICIENT_BALANCE", e.getMessage()));
        } catch (Exception e) {
            log.error("transfer failed unexpectedly, traceId={}, requestNo={}", traceId, request.getRequestNo(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("SYSTEM_ERROR", "system busy, traceId=" + traceId));
        }
    }
}
```

**幂等说明**：本案例的幂等控制体现在两个层次——网关层的 `nonce` 防止"同一个物理 HTTP 请求"被截获重放；业务层 `requestNo` 唯一索引（原文 3.3.2 节方案）防止"同一笔转账业务语义"被重复提交（即使 nonce 不同，例如客户端超时后用新 nonce 重新发起了同一笔转账）。二者互补，缺一不可，这也是"安全防护应该纵深布局"这一设计原则的直接体现。

**全链路小结**：客户端组装业务参数并附加 `appId`/`timestamp`/`nonce`，计算签名后发起请求 → `AntiReplaySignatureFilter` 依次完成参数完整性校验、时间戳时效性校验、`nonce` 唯一性校验（Redis `SETNX` 原子操作）、签名重算比对，任意一步失败立即以精确的错误码拒绝并记录日志，连续失败超过阈值触发 `SecurityAlertService` 安全告警 → 全部通过后请求进入 `TransferController`，业务层依据 `requestNo` 唯一索引进行二次幂等兜底 → 最终返回转账结果或幂等命中的历史结果。

---

### 7.3 案例三：API 限流与配额管理全链路

**业务场景**：开放平台按套餐向第三方调用方售卖 API 调用配额（如"每日 10 万次调用"），需要在每次请求时完成 API Key 认证、配额余量查询、限流判定（瞬时速率保护）、配额扣减（长周期总量控制）、临近配额上限时的告警通知，是限流（保护系统瞬时承载能力）与配额（保护商业规则约定的总量）两套机制协同工作的典型场景。

**全链路结构**：API Key 认证 → 配额查询 → 限流判定 → 配额扣减 → 配额告警。

#### 7.3.1 API Key 认证：解析并校验调用方身份

```java
/**
 * API Key上下文：认证通过后的调用方身份信息，供后续配额、限流环节复用，避免重复查询
 */
public class ApiKeyPrincipal {
    private final String apiKey;
    private final String appId;
    private final String planCode; // 套餐编码，决定配额上限
    private final int dailyQuotaLimit;
    private final int qpsLimit;

    public ApiKeyPrincipal(String apiKey, String appId, String planCode, int dailyQuotaLimit, int qpsLimit) {
        this.apiKey = apiKey;
        this.appId = appId;
        this.planCode = planCode;
        this.dailyQuotaLimit = dailyQuotaLimit;
        this.qpsLimit = qpsLimit;
    }
    // getter省略
    public String getApiKey() { return apiKey; }
    public String getAppId() { return appId; }
    public String getPlanCode() { return planCode; }
    public int getDailyQuotaLimit() { return dailyQuotaLimit; }
    public int getQpsLimit() { return qpsLimit; }
}

/**
 * API Key认证服务：校验Key的有效性并加载其套餐配置，结果缓存在本地+Redis两级，减少数据库压力
 */
@Service
public class ApiKeyAuthService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthService.class);

    private final ApiKeyMapper apiKeyMapper; // 数据库兜底查询
    private final StringRedisTemplate redisTemplate;
    private final Cache<String, ApiKeyPrincipal> localCache; // Caffeine本地缓存，减少高频请求下的Redis访问

    public ApiKeyAuthService(ApiKeyMapper apiKeyMapper, StringRedisTemplate redisTemplate) {
        this.apiKeyMapper = apiKeyMapper;
        this.redisTemplate = redisTemplate;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.MINUTES) // 本地缓存有效期短，保证套餐变更（如升级套餐）能较快生效
                .build();
    }

    public ApiKeyPrincipal authenticate(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new UnauthorizedException("api key is missing");
        }
        ApiKeyPrincipal cached = localCache.getIfPresent(apiKey);
        if (cached != null) {
            return cached;
        }
        ApiKeyPrincipal principal = loadFromRedisOrDb(apiKey);
        if (principal == null) {
            log.warn("invalid or revoked api key, apiKey={}", maskKey(apiKey));
            throw new UnauthorizedException("invalid or revoked api key");
        }
        localCache.put(apiKey, principal);
        return principal;
    }

    private ApiKeyPrincipal loadFromRedisOrDb(String apiKey) {
        String redisKey = "apikey:info:" + apiKey;
        Map<Object, Object> cached = redisTemplate.opsForHash().entries(redisKey);
        if (!cached.isEmpty()) {
            return buildFromMap(apiKey, cached);
        }
        // Redis未命中，回源数据库查询，并回填Redis，减少下次访问的数据库压力
        ApiKeyRecord record = apiKeyMapper.selectByApiKey(apiKey);
        if (record == null || !record.isActive()) {
            return null; // Key不存在或已被吊销
        }
        ApiKeyPrincipal principal = new ApiKeyPrincipal(apiKey, record.getAppId(), record.getPlanCode(),
                record.getDailyQuotaLimit(), record.getQpsLimit());
        Map<String, String> toCache = new HashMap<>();
        toCache.put("appId", record.getAppId());
        toCache.put("planCode", record.getPlanCode());
        toCache.put("dailyQuotaLimit", String.valueOf(record.getDailyQuotaLimit()));
        toCache.put("qpsLimit", String.valueOf(record.getQpsLimit()));
        redisTemplate.opsForHash().putAll(redisKey, toCache);
        redisTemplate.expire(redisKey, 10, TimeUnit.MINUTES);
        return principal;
    }

    private ApiKeyPrincipal buildFromMap(String apiKey, Map<Object, Object> map) {
        return new ApiKeyPrincipal(apiKey, (String) map.get("appId"), (String) map.get("planCode"),
                Integer.parseInt((String) map.get("dailyQuotaLimit")), Integer.parseInt((String) map.get("qpsLimit")));
    }

    // 日志脱敏：完整Key不应该出现在日志中，避免日志泄露导致Key被冒用
    private String maskKey(String apiKey) {
        if (apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}
```

#### 7.3.2 配额查询、限流判定与配额扣减的原子化实现

配额（长周期总量，如"每日 10 万次"）与限流（短周期瞬时速率，如"每秒 100 次"）是两个独立维度，必须分别判断：限流保护的是系统瞬时承载能力，配额保护的是商业套餐约定的总量上限，即使 QPS 远未达到限流阈值，配额耗尽后仍应拒绝请求。

```java
/**
 * 配额与限流服务：使用Lua脚本将"限流判断"与"配额扣减"整合为一次原子操作，
 * 避免两次独立的Redis调用之间出现并发窗口期导致配额超扣
 */
@Service
public class QuotaRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(QuotaRateLimitService.class);

    // Lua脚本一次性完成：1)滑动窗口限流判断 2)配额余量检查 3)配额扣减，三步全部通过才放行，任意一步失败都不产生副作用
    private static final String QUOTA_AND_RATE_LIMIT_SCRIPT =
            "local rateLimitKey = KEYS[1] " +
            "local quotaKey = KEYS[2] " +
            "local now = tonumber(ARGV[1]) " +
            "local qpsWindow = tonumber(ARGV[2]) " +
            "local qpsLimit = tonumber(ARGV[3]) " +
            "local dailyQuotaLimit = tonumber(ARGV[4]) " +
            "local quotaExpireSeconds = tonumber(ARGV[5]) " +
            // 第一步：滑动窗口限流判断
            "redis.call('ZREMRANGEBYSCORE', rateLimitKey, 0, now - qpsWindow) " +
            "local currentQps = redis.call('ZCARD', rateLimitKey) " +
            "if currentQps >= qpsLimit then " +
            "  return {0, 'RATE_LIMITED', currentQps} " +
            "end " +
            // 第二步：配额余量检查（配额key不存在时视为0已用量）
            "local used = tonumber(redis.call('GET', quotaKey) or '0') " +
            "if used >= dailyQuotaLimit then " +
            "  return {0, 'QUOTA_EXCEEDED', used} " +
            "end " +
            // 第三步：两项检查都通过，才真正记录限流窗口和扣减配额，保证判断与扣减的原子性
            "redis.call('ZADD', rateLimitKey, now, now .. '-' .. math.random()) " +
            "redis.call('EXPIRE', rateLimitKey, math.ceil(qpsWindow / 1000)) " +
            "local newUsed = redis.call('INCR', quotaKey) " +
            "if newUsed == 1 then " +
            "  redis.call('EXPIRE', quotaKey, quotaExpireSeconds) " + // 首次扣减时设置过期时间，保证每日自然重置
            "end " +
            "return {1, 'OK', newUsed}";

    private final StringRedisTemplate redisTemplate;

    public QuotaRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public QuotaCheckResult checkAndConsume(ApiKeyPrincipal principal) {
        String rateLimitKey = "rate:apikey:" + principal.getApiKey();
        String quotaKey = "quota:daily:" + principal.getApiKey() + ":" + LocalDate.now();
        // 配额key的过期时间设置为到当日结束的剩余秒数，保证每天0点自然重置，无需额外的定时任务清理
        long secondsUntilMidnight = Duration.between(LocalDateTime.now(),
                LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();

        DefaultRedisScript<List> script = new DefaultRedisScript<>(QUOTA_AND_RATE_LIMIT_SCRIPT, List.class);
        List<?> result;
        try {
            result = redisTemplate.execute(script, Arrays.asList(rateLimitKey, quotaKey),
                    String.valueOf(System.currentTimeMillis()),
                    "1000", // QPS滑动窗口为1秒
                    String.valueOf(principal.getQpsLimit()),
                    String.valueOf(principal.getDailyQuotaLimit()),
                    String.valueOf(secondsUntilMidnight));
        } catch (Exception e) {
            // Redis执行异常（如连接超时），是否放行需要结合业务风险权衡：这里选择保守拒绝，避免限流组件故障期间配额被无限透支
            log.error("quota/rate-limit script execution failed, apiKey={}", principal.getAppId(), e);
            throw new ServiceUnavailableException("quota service temporarily unavailable");
        }

        long passed = ((Number) result.get(0)).longValue();
        String reason = (String) result.get(1);
        long currentValue = ((Number) result.get(2)).longValue();

        if (passed == 1) {
            return QuotaCheckResult.allowed(currentValue, principal.getDailyQuotaLimit());
        }
        log.warn("request rejected, appId={}, reason={}, currentValue={}", principal.getAppId(), reason, currentValue);
        return QuotaCheckResult.rejected(reason, currentValue, principal.getDailyQuotaLimit());
    }
}

public class QuotaCheckResult {
    private final boolean allowed;
    private final String rejectReason;
    private final long currentUsage;
    private final long limit;

    private QuotaCheckResult(boolean allowed, String rejectReason, long currentUsage, long limit) {
        this.allowed = allowed;
        this.rejectReason = rejectReason;
        this.currentUsage = currentUsage;
        this.limit = limit;
    }

    public static QuotaCheckResult allowed(long currentUsage, long limit) {
        return new QuotaCheckResult(true, null, currentUsage, limit);
    }

    public static QuotaCheckResult rejected(String reason, long currentUsage, long limit) {
        return new QuotaCheckResult(false, reason, currentUsage, limit);
    }

    public boolean isAllowed() { return allowed; }
    public String getRejectReason() { return rejectReason; }
    public long getCurrentUsage() { return currentUsage; }
    public long getLimit() { return limit; }
}
```

- **算法核心**：将"限流判断"和"配额检查+扣减"合并进同一个 Lua 脚本，依托 Redis 单线程模型保证多步操作的原子性——如果分成多次独立的 Redis 调用（先查限流、再查配额、最后扣减），高并发场景下多个请求可能都通过了配额检查但都执行了扣减，导致实际用量超过套餐上限（超扣问题）。
- **数据流转**：请求到达 → 计算当日配额 key（按 `apiKey + 日期` 维度，天然按天隔离和过期）→ Lua 脚本内先做滑动窗口 QPS 判断，不通过直接返回 `RATE_LIMITED`；通过后检查配额余量，不足返回 `QUOTA_EXCEEDED`；两项都通过才真正写入限流窗口记录并对配额计数器执行 `INCR`，首次扣减时设置精确到当日结束的过期时间实现自动重置。

#### 7.3.3 配额告警：临近上限时主动通知调用方

```java
/**
 * 配额告警服务：配额使用率达到预设阈值（如80%、95%）时触发一次性告警，避免同一阈值区间内重复告警造成骚扰
 */
@Service
public class QuotaAlertService {

    private static final Logger log = LoggerFactory.getLogger(QuotaAlertService.class);
    // 告警阈值从低到高排列，命中后逐级触发，避免遗漏中间档位
    private static final int[] ALERT_THRESHOLDS_PERCENT = {80, 95, 100};

    private final StringRedisTemplate redisTemplate;
    private final NotificationClient notificationClient; // 对接短信/邮件/开放平台站内信等具体通知渠道

    public QuotaAlertService(StringRedisTemplate redisTemplate, NotificationClient notificationClient) {
        this.redisTemplate = redisTemplate;
        this.notificationClient = notificationClient;
    }

    public void checkAndAlert(ApiKeyPrincipal principal, long currentUsage, long limit) {
        if (limit <= 0) {
            return; // 防御性判断，避免除零异常
        }
        int usagePercent = (int) Math.floor(currentUsage * 100.0 / limit);
        for (int threshold : ALERT_THRESHOLDS_PERCENT) {
            if (usagePercent >= threshold) {
                tryAlertOnce(principal, threshold, currentUsage, limit);
            }
        }
    }

    // 利用Redis SETNX保证同一appId、同一天、同一阈值档位只告警一次，避免每次请求都重复发送通知
    private void tryAlertOnce(ApiKeyPrincipal principal, int threshold, long currentUsage, long limit) {
        String alertMarkKey = "quota:alert:" + principal.getApiKey() + ":" + LocalDate.now() + ":" + threshold;
        Boolean firstAlert = redisTemplate.opsForValue().setIfAbsent(alertMarkKey, "1", 1, TimeUnit.DAYS);
        if (!Boolean.TRUE.equals(firstAlert)) {
            return; // 该阈值今日已经告警过，跳过
        }
        try {
            String message = String.format("appId=%s 的API配额已使用 %d/%d（%d%%），请关注套餐余量",
                    principal.getAppId(), currentUsage, limit, threshold);
            notificationClient.send(principal.getAppId(), message);
            log.info("quota alert sent, appId={}, threshold={}, currentUsage={}, limit={}",
                    principal.getAppId(), threshold, currentUsage, limit);
        } catch (Exception e) {
            // 告警通知发送失败不应该影响主请求链路，只记录日志，可以考虑后续接入重试队列
            log.warn("send quota alert failed, appId={}, threshold={}", principal.getAppId(), threshold, e);
        }
    }
}
```

#### 7.3.4 网关过滤器整合：五段式全链路串联

```java
/**
 * API Key认证 + 配额限流一体化过滤器：串联本节前三步能力，形成完整的请求准入判断链路
 */
@Component
@Order(3) // 排在签名防重放之后：先确认请求本身合法可信，再消耗配额和限流名额，避免非法请求也计入配额消耗
public class ApiKeyQuotaFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyQuotaFilter.class);

    private final ApiKeyAuthService apiKeyAuthService;
    private final QuotaRateLimitService quotaRateLimitService;
    private final QuotaAlertService quotaAlertService;
    private final ThreadPoolTaskExecutor asyncExecutor;

    public ApiKeyQuotaFilter(ApiKeyAuthService apiKeyAuthService,
                              QuotaRateLimitService quotaRateLimitService,
                              QuotaAlertService quotaAlertService,
                              ThreadPoolTaskExecutor asyncExecutor) {
        this.apiKeyAuthService = apiKeyAuthService;
        this.quotaRateLimitService = quotaRateLimitService;
        this.quotaAlertService = quotaAlertService;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/openapi/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get("traceId");
        String apiKey = request.getHeader("X-Api-Key");

        // 第一步：API Key认证
        ApiKeyPrincipal principal;
        try {
            principal = apiKeyAuthService.authenticate(apiKey);
        } catch (UnauthorizedException e) {
            log.warn("api key authentication failed, traceId={}, reason={}", traceId, e.getMessage());
            reject(response, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", e.getMessage());
            return;
        }

        try {
            // 第二步（配额查询）+ 第三步（限流判定）+ 第四步（配额扣减）：三者在Lua脚本中原子化完成
            QuotaCheckResult checkResult = quotaRateLimitService.checkAndConsume(principal);
            if (!checkResult.isAllowed()) {
                HttpStatus status = "RATE_LIMITED".equals(checkResult.getRejectReason())
                        ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
                response.setHeader("X-Quota-Used", String.valueOf(checkResult.getCurrentUsage()));
                response.setHeader("X-Quota-Limit", String.valueOf(checkResult.getLimit()));
                log.warn("request rejected by quota/rate-limit, traceId={}, appId={}, reason={}",
                        traceId, principal.getAppId(), checkResult.getRejectReason());
                String message = "RATE_LIMITED".equals(checkResult.getRejectReason())
                        ? "request rate too high, please retry later"
                        : "daily quota exceeded, please upgrade your plan or wait for reset";
                reject(response, status, checkResult.getRejectReason(), message);
                return;
            }

            // 第五步：配额告警，异步执行不阻塞主链路响应
            asyncExecutor.execute(() ->
                    quotaAlertService.checkAndAlert(principal, checkResult.getCurrentUsage(), checkResult.getLimit()));

            // 附加配额余量信息到响应头，便于调用方在客户端自行监控用量，减少事后才发现配额耗尽的情况
            response.setHeader("X-Quota-Used", String.valueOf(checkResult.getCurrentUsage()));
            response.setHeader("X-Quota-Limit", String.valueOf(checkResult.getLimit()));
            request.setAttribute("apiKeyPrincipal", principal); // 供后续业务Controller读取调用方身份，无需重复认证
            chain.doFilter(request, response);
        } catch (ServiceUnavailableException e) {
            log.error("quota service unavailable, traceId={}, appId={}", traceId, principal.getAppId(), e);
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "QUOTA_SERVICE_UNAVAILABLE", "system busy, traceId=" + traceId);
        } catch (Exception e) {
            log.error("api key quota filter internal error, traceId={}, appId={}", traceId, principal.getAppId(), e);
            reject(response, HttpStatus.INTERNAL_SERVER_ERROR, "SYSTEM_ERROR", "system busy, traceId=" + traceId);
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
```

**幂等说明**：配额扣减本身通过 Lua 脚本中的 `INCR` 保证原子递增，不存在并发场景下的超扣风险；但配额扣减动作与"业务是否真正调用成功"是两个独立的问题——本案例中的配额消耗遵循"先扣减后放行"策略（即请求一旦通过限流和配额检查即计入消耗，无论后续业务逻辑是否执行成功），这是开放平台配额计费的常见约定（类比"通话时长按接通计费而非按内容有效性计费"）。如果业务要求"仅业务成功才计入配额"，则需要将配额扣减后移至业务逻辑执行成功之后，并额外处理扣减失败的补偿逻辑，需要根据具体商业规则选择合适的扣减时机。

**全链路小结**：请求携带 `X-Api-Key` 到达 → `ApiKeyAuthService` 完成身份认证并加载套餐配置（本地缓存 → Redis → 数据库三级查找，逐级兜底）→ `QuotaRateLimitService` 通过一个 Lua 脚本原子化完成限流判定与配额查询扣减，任意一项不满足都不会产生副作用地直接拒绝 → 检查通过后异步触发 `QuotaAlertService` 按 80%/95%/100% 阈值分级告警（同一阈值当天只告警一次，避免通知骚扰）→ 请求最终携带调用方身份信息放行给业务 Controller，并在响应头中回传实时配额余量供客户端自行监控。
