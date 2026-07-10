# 多租户SaaS架构系统设计

## 一、系统概述

多租户SaaS架构是企业级云服务的基础设施，通过单一应用实例为多个租户（Tenant）提供隔离的服务环境。系统的核心设计参考了业界领先的多租户平台架构实践，采用Org → Workspace两级隔离模型，支持SaaS、专属云、私有化多种交付模式。

### 1.1 核心挑战

多租户SaaS架构面临以下核心技术挑战。第一，数据隔离：不同租户的数据必须严格隔离，防止跨租户数据泄露，同时兼顾查询性能。第二，资源隔离：单个租户的流量突增不能影响其他租户的服务质量，需要实现CPU、内存、连接池等多维度资源隔离。第三，定制化需求：不同租户的业务场景差异大，系统需要支持配置驱动的定制化能力，而非为每个租户维护独立代码分支。第四，计费计量：需要精确计量每个租户的资源使用量，作为计费依据。第五，弹性伸缩：系统需要根据租户规模动态调整资源分配，支持租户级别的弹性扩缩容。

### 1.2 设计目标

系统设计目标包括：Org → Workspace两级租户隔离体系，支持组织级和空间级两层隔离粒度；平台级流控能力，防止单用户或单租户打爆系统资源；按租户维度的资源消耗可视化与成本归属；支持SaaS、专属云、私有化多种交付模式；沙箱级别隔离，防止数据出域；多活路由与故障熔断能力，保障高可用。

### 1.3 隔离模型设计

参考业界多租户平台的两级隔离架构，系统采用Org → Workspace两级隔离模型。Org（组织）是最高层级的租户实体，对应一个企业或组织。Workspace（工作空间）是Org内的子隔离单元，对应组织内的不同团队或项目。这种两级模型既保证了组织间数据的完全隔离，又允许组织内不同空间有一定的独立性。

```java
/**
 * 租户层级模型
 * 采用Org → Workspace两级隔离架构
 */
public enum TenantLevel {
    ORG,         // 组织级隔离
    WORKSPACE    // 工作空间级隔离
}

/**
 * 租户实体
 * 支持两级隔离：Org和Workspace
 */
@Data
public class Tenant {
    private Long tenantId;
    private String tenantCode;
    private String tenantName;
    private TenantLevel level;
    private Long parentTenantId;     // 父租户ID（Workspace的父是Org）
    private TenantStatus status;
    private TenantDelivery delivery; // 交付模式
    private Long createTime;
    private Long updateTime;
    
    public enum TenantStatus {
        CREATING,    // 创建中
        ACTIVE,      // 正常
        FROZEN,      // 冻结
        CANCELING,   // 注销中
        CANCELED     // 已注销
    }
    
    public enum TenantDelivery {
        SAAS,          // SaaS多租户共享
        DEDICATED,     // 专属云
        PRIVATE        // 私有化
    }
}
```

---

## 二、整体架构

系统采用五层架构设计，从上到下依次为接入层、租户识别层、业务服务层、数据隔离层和基础设施层。

```java
┌─────────────────────────────────────────────────────┐
│                    接入层                              │
│    API网关 │ 认证鉴权 │ 请求路由 │ 限流入口             │
├─────────────────────────────────────────────────────┤
│                    租户识别层                          │
│    Token解析 │ 租户上下文 │ 权限校验 │ 流控检查          │
├─────────────────────────────────────────────────────┤
│                    业务服务层                          │
│    业务服务集群 │ 配置中心 │ 定制化引擎 │ 计量采集        │
├─────────────────────────────────────────────────────┤
│                    数据隔离层                          │
│    MyBatis拦截器 │ 数据路由 │ 缓存隔离 │ MQ隔离         │
├─────────────────────────────────────────────────────┤
│                    基础设施层                          │
│    多租户DB │ Redis隔离 │ 对象存储 │ 监控告警          │
└─────────────────────────────────────────────────────┘
```

接入层负责统一接收所有HTTP请求，通过API网关进行认证鉴权和请求路由。租户识别层是核心，从请求中提取租户标识，构建租户上下文，执行租户级别的权限校验和流控检查。业务服务层承载具体业务逻辑，所有业务服务共享同一套代码，通过配置中心读取租户差异化配置。数据隔离层通过MyBatis拦截器自动在SQL中注入租户ID条件，确保数据查询不会跨租户。基础设施层提供多租户数据库、Redis命名空间隔离、对象存储租户前缀隔离等底层支持。

---

## 三、租户上下文管理

### 3.1 ThreadLocal租户上下文

租户上下文是整个多租户架构的核心。系统使用ThreadLocal在请求线程内传递租户信息，确保所有业务代码都能获取当前请求的租户标识。这是多租户架构的基础设施，所有后续的数据隔离、资源隔离、流控都依赖于此。

```java
/**
 * 租户上下文
 * 使用ThreadLocal在请求线程内传递租户信息
 * 
 * 设计模式：线程局部存储模式（Thread Local Storage）
 * 每个请求线程拥有独立的租户上下文副本，线程间隔离
 */
public class TenantContext {
    
    private static final ThreadLocal<TenantInfo> CONTEXT = new ThreadLocal<>();
    
    /**
     * 设置租户上下文
     */
    public static void set(TenantInfo tenantInfo) {
        CONTEXT.set(tenantInfo);
    }
    
    /**
     * 获取当前租户信息
     */
    public static TenantInfo get() {
        TenantInfo info = CONTEXT.get();
        if (info == null) {
            throw new TenantException("Tenant context not initialized");
        }
        return info;
    }
    
    /**
     * 获取当前租户ID
     */
    public static Long getTenantId() {
        return get().getTenantId();
    }
    
    /**
     * 获取当前Org ID
     */
    public static Long getOrgId() {
        return get().getOrgId();
    }
    
    /**
     * 获取当前Workspace ID
     */
    public static Long getWorkspaceId() {
        return get().getWorkspaceId();
    }
    
    /**
     * 清除上下文
     * 必须在请求结束时调用，防止内存泄漏和线程复用导致的上下文污染
     */
    public static void clear() {
        CONTEXT.remove();
    }
    
    /**
     * 租户信息
     */
    @Data
    @Builder
    public static class TenantInfo {
        private Long orgId;
        private Long workspaceId;
        private Long tenantId;        // 当前生效的租户ID（Org或Workspace）
        private TenantLevel level;    // 当前隔离级别
        private String tenantCode;
        private Tenant.TenantDelivery delivery;
    }
}
```

### 3.2 租户上下文拦截器

系统通过Spring MVC拦截器在请求入口处解析租户标识并初始化租户上下文。租户标识从请求头的Authorization Token中解析，经过JWT解码获取租户信息。

```java
/**
 * 租户上下文拦截器
 * 在请求入口处解析租户信息，初始化ThreadLocal上下文
 * 
 * 算法说明：
 * 1. 从请求头提取Authorization Token
 * 2. JWT解码获取orgId、workspaceId
 * 3. 查询租户状态，校验是否为ACTIVE
 * 4. 构建TenantInfo，设置到ThreadLocal
 * 5. 请求结束后清除ThreadLocal
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor {
    
    @Resource
    private TenantService tenantService;
    
    @Resource
    private JwtTokenService jwtTokenService;
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
            HttpServletResponse response, Object handler) {
        // 1. 提取Token
        String token = extractToken(request);
        if (token == null) {
            response.setStatus(401);
            return false;
        }
        
        // 2. JWT解码
        JwtPayload payload = jwtTokenService.decode(token);
        if (payload == null) {
            response.setStatus(401);
            return false;
        }
        
        // 3. 查询租户信息
        Tenant tenant = tenantService.findByTenantCode(payload.getTenantCode());
        if (tenant == null || tenant.getStatus() != Tenant.TenantStatus.ACTIVE) {
            response.setStatus(403);
            return false;
        }
        
        // 4. 构建租户上下文
        TenantContext.TenantInfo info = TenantContext.TenantInfo.builder()
            .orgId(payload.getOrgId())
            .workspaceId(payload.getWorkspaceId())
            .tenantId(determineTenantId(payload, tenant))
            .level(tenant.getLevel())
            .tenantCode(tenant.getTenantCode())
            .delivery(tenant.getDelivery())
            .build();
        
        TenantContext.set(info);
        
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request,
            HttpServletResponse response, Object handler, Exception ex) {
        // 清除ThreadLocal，防止线程复用导致的上下文泄漏
        TenantContext.clear();
    }
    
    /**
     * 确定生效的租户ID
     * 如果有workspaceId则使用workspaceId，否则使用orgId
     */
    private Long determineTenantId(JwtPayload payload, Tenant tenant) {
        if (payload.getWorkspaceId() != null) {
            return payload.getWorkspaceId();
        }
        return payload.getOrgId();
    }
    
    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }
}
```

---

## 四、数据隔离方案

### 4.1 三种隔离模式对比

多租户数据隔离有三种经典模式，各有优劣。独立数据库模式为每个租户创建独立的数据库实例，隔离性最强但成本最高，适合对数据安全要求极高的租户。共享数据库独立Schema模式在同一个数据库实例中为每个租户创建独立的Schema，隔离性中等，成本适中。共享数据库共享Schema模式所有租户共享同一数据库同一Schema，通过tenant_id字段区分，成本最低但隔离性最弱，需要严格的SQL拦截保障。

系统采用混合模式：默认使用共享数据库共享Schema模式（成本低），对有特殊安全需求的租户支持切换到独立数据库模式（专属云交付）。这种设计参考了多租户平台支持SaaS/专属云/私有化多种交付模式的架构。

```java
/**
 * 数据隔离策略
 * 策略模式：根据租户交付模式选择不同的数据隔离策略
 */
public interface DataIsolationStrategy {
    
    /**
     * 获取数据源
     */
    DataSource getDataSource(Long tenantId);
    
    /**
     * 是否需要SQL注入租户ID
     */
    boolean needTenantIdFilter(Long tenantId);
    
    /**
     * 获取隔离模式
     */
    String getIsolationMode();
}

/**
 * 共享数据库共享Schema策略
 * 所有租户共享同一数据库，通过tenant_id字段隔离
 */
@Component
public class SharedSchemaStrategy implements DataIsolationStrategy {
    
    @Resource
    private DataSource sharedDataSource;
    
    @Override
    public DataSource getDataSource(Long tenantId) {
        return sharedDataSource;
    }
    
    @Override
    public boolean needTenantIdFilter(Long tenantId) {
        return true;  // 需要SQL注入tenant_id
    }
    
    @Override
    public String getIsolationMode() {
        return "SHARED_SCHEMA";
    }
}

/**
 * 独立数据库策略
 * 为专属云租户提供独立数据库实例
 */
@Component
public class IsolatedDbStrategy implements DataIsolationStrategy {
    
    @Resource
    private Map<Long, DataSource> tenantDataSources = new ConcurrentHashMap<>();
    
    @Resource
    private DataSourceConfigService dataSourceConfigService;
    
    @Override
    public DataSource getDataSource(Long tenantId) {
        return tenantDataSources.computeIfAbsent(tenantId, this::createDataSource);
    }
    
    @Override
    public boolean needTenantIdFilter(Long tenantId) {
        return false;  // 独立数据库不需要tenant_id过滤
    }
    
    @Override
    public String getIsolationMode() {
        return "ISOLATED_DB";
    }
    
    private DataSource createDataSource(Long tenantId) {
        DataSourceConfig config = dataSourceConfigService.getConfig(tenantId);
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(config.getJdbcUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setMaximumPoolSize(20);
        ds.setPoolName("tenant-" + tenantId + "-pool");
        return ds;
    }
}

/**
 * 数据隔离策略工厂
 * 根据租户交付模式选择对应的隔离策略
 */
@Service
public class DataIsolationStrategyFactory {
    
    @Resource
    private SharedSchemaStrategy sharedSchemaStrategy;
    
    @Resource
    private IsolatedDbStrategy isolatedDbStrategy;
    
    public DataIsolationStrategy getStrategy(Tenant.TenantDelivery delivery) {
        switch (delivery) {
            case SAAS:
                return sharedSchemaStrategy;
            case DEDICATED:
            case PRIVATE:
                return isolatedDbStrategy;
            default:
                return sharedSchemaStrategy;
        }
    }
}
```

### 4.2 MyBatis拦截器实现数据隔离

对于共享Schema模式，系统通过MyBatis拦截器自动在SQL的WHERE条件中注入tenant_id，确保查询不会跨租户。这是数据隔离的核心防线。

```java
/**
 * 多租户MyBatis拦截器
 * 自动在SQL中注入tenant_id条件，防止跨租户数据访问
 * 
 * 算法说明：
 * 1. 拦截MyBatis的query和update操作
 * 2. 使用JSqlParser解析SQL为AST
 * 3. 在WHERE条件中添加tenant_id = ? 条件
 * 4. 对于INSERT语句，在字段中添加tenant_id
 * 5. 对带有@IgnoreTenant注解的方法跳过拦截
 * 
 * 设计模式：拦截器模式 + AST操作
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query", 
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update", 
        args = {MappedStatement.class, Object.class})
})
public class TenantSqlInterceptor implements Interceptor {
    
    private static final String TENANT_ID_COLUMN = "tenant_id";
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 检查是否跳过租户过滤
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        if (shouldIgnoreTenant(ms)) {
            return invocation.proceed();
        }
        
        // 2. 获取当前租户ID
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return invocation.proceed();
        }
        
        // 3. 获取原始SQL和参数
        Object parameter = invocation.getArgs()[1];
        BoundSql boundSql = ms.getBoundSql(parameter);
        String originalSql = boundSql.getSql();
        
        // 4. 使用JSqlParser解析并改写SQL
        String newSql = injectTenantCondition(originalSql, tenantId);
        
        // 5. 替换BoundSql中的SQL
        reflectSetSql(boundSql, newSql);
        
        return invocation.proceed();
    }
    
    /**
     * 使用JSqlParser在SQL中注入tenant_id条件
     * 
     * 算法说明：
     * 1. 解析SQL为AST
     * 2. 判断SQL类型（SELECT/UPDATE/DELETE/INSERT）
     * 3. 对于SELECT/UPDATE/DELETE：在WHERE中添加AND tenant_id = ?
     * 4. 对于INSERT：在字段列表和VALUES中添加tenant_id
     * 5. 重新生成SQL字符串
     */
    private String injectTenantCondition(String sql, Long tenantId) {
        Statement statement = CCJSqlParserUtil.parse(sql);
        
        if (statement instanceof Select) {
            Select select = (Select) statement;
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            
            // 获取或创建WHERE条件
            Expression where = plainSelect.getWhere();
            EqualsTo tenantCondition = new EqualsTo();
            tenantCondition.setLeftExpression(new Column(TENANT_ID_COLUMN));
            tenantCondition.setRightExpression(new LongValue(tenantId));
            
            if (where == null) {
                plainSelect.setWhere(tenantCondition);
            } else {
                AndExpression and = new AndExpression(where, tenantCondition);
                plainSelect.setWhere(and);
            }
            
        } else if (statement instanceof Update) {
            Update update = (Update) statement;
            Expression where = update.getWhere();
            EqualsTo tenantCondition = new EqualsTo();
            tenantCondition.setLeftExpression(new Column(TENANT_ID_COLUMN));
            tenantCondition.setRightExpression(new LongValue(tenantId));
            
            if (where == null) {
                update.setWhere(tenantCondition);
            } else {
                AndExpression and = new AndExpression(where, tenantCondition);
                update.setWhere(and);
            }
            
        } else if (statement instanceof Delete) {
            Delete delete = (Delete) statement;
            Expression where = delete.getWhere();
            EqualsTo tenantCondition = new EqualsTo();
            tenantCondition.setLeftExpression(new Column(TENANT_ID_COLUMN));
            tenantCondition.setRightExpression(new LongValue(tenantId));
            
            if (where == null) {
                delete.setWhere(tenantCondition);
            } else {
                AndExpression and = new AndExpression(where, tenantCondition);
                delete.setWhere(and);
            }
        }
        
        return statement.toString();
    }
    
    /**
     * 检查是否跳过租户过滤
     * 带有@IgnoreTenant注解的Mapper方法不注入tenant_id
     */
    private boolean shouldIgnoreTenant(MappedStatement ms) {
        String mapperMethodId = ms.getId();
        try {
            String className = mapperMethodId.substring(0, mapperMethodId.lastIndexOf("."));
            String methodName = mapperMethodId.substring(mapperMethodId.lastIndexOf(".") + 1);
            Class<?> mapperClass = Class.forName(className);
            Method[] methods = mapperClass.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().equals(methodName) 
                    && method.isAnnotationPresent(IgnoreTenant.class)) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }
    
    private void reflectSetSql(BoundSql boundSql, String newSql) throws Exception {
        Field field = boundSql.getClass().getDeclaredField("sql");
        field.setAccessible(true);
        field.set(boundSql, newSql);
    }
}
```

---

## 五、资源隔离与限流

### 5.1 租户级限流

参考多租户平台的Token/消息限流能力，系统实现了基于Redis令牌桶算法的租户级限流。令牌桶算法的核心思想是以固定速率向桶中添加令牌，请求消耗令牌，桶满则丢弃多余令牌，桶空则拒绝请求。这种算法允许一定程度的突发流量，同时保证长期平均速率受限。

```java
/**
 * 租户级限流服务
 * 基于Redis令牌桶算法实现多维度限流
 * 
 * 限流维度：
 * 1. 租户级QPS限制：防止单租户打爆系统
 * 2. 租户级并发限制：限制单租户并发请求数
 * 3. API级限制：不同API有不同的限流配额
 * 
 * 算法：Redis令牌桶
 * - 固定速率向桶中添加令牌
 * - 请求消耗令牌
 * - 桶满丢弃多余令牌，桶空拒绝请求
 */
@Service
public class TenantRateLimiter {
    
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    
    /** 默认QPS限制 */
    @Value("${tenant.rate.default.qps:100}")
    private int defaultQps;
    
    /** 默认并发限制 */
    @Value("${tenant.rate.default.concurrent:50}")
    private int defaultConcurrent;
    
    /**
     * 令牌桶限流检查
     * 
     * 算法说明（Redis Lua脚本实现原子操作）：
     * 1. 获取桶中当前令牌数和上次更新时间
     * 2. 计算从上次到现在新增的令牌数：elapsed * rate
     * 3. 更新令牌数 = min(capacity, current + newTokens)
     * 4. 如果令牌数 >= 1，消耗1个令牌，返回允许
     * 5. 否则返回拒绝
     */
    private static final String TOKEN_BUCKET_LUA = 
        "local key = KEYS[1] " +
        "local capacity = tonumber(ARGV[1]) " +
        "local rate = tonumber(ARGV[2]) " +
        "local now = tonumber(ARGV[3]) " +
        "local bucket = redis.call('hmget', key, 'tokens', 'timestamp') " +
        "local tokens = tonumber(bucket[1]) or capacity " +
        "local last = tonumber(bucket[2]) or now " +
        "local delta = math.max(0, now - last) " +
        "tokens = math.min(capacity, tokens + delta * rate / 1000) " +
        "if tokens >= 1 then " +
        "    tokens = tokens - 1 " +
        "    redis.call('hmset', key, 'tokens', tokens, 'timestamp', now) " +
        "    redis.call('expire', key, 60) " +
        "    return 1 " +
        "else " +
        "    redis.call('hmset', key, 'tokens', tokens, 'timestamp', now) " +
        "    redis.call('expire', key, 60) " +
        "    return 0 " +
        "end";
    
    /**
     * 检查租户QPS限流
     */
    public boolean checkQpsLimit(Long tenantId, String apiPath) {
        TenantConfig config = getTenantConfig(tenantId);
        int qpsLimit = config.getQpsLimit() != null ? config.getQpsLimit() : defaultQps;
        
        String key = "rate:qps:" + tenantId + ":" + apiPath;
        long now = System.currentTimeMillis();
        
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(TOKEN_BUCKET_LUA, Long.class);
        Long result = redisTemplate.execute(script, 
            Collections.singletonList(key), 
            String.valueOf(qpsLimit), 
            String.valueOf((double) qpsLimit / 1000),
            String.valueOf(now));
        
        return result != null && result == 1;
    }
    
    /**
     * 检查租户并发限制
     * 使用Redis INCR/DECR实现并发计数
     */
    public boolean checkConcurrentLimit(Long tenantId) {
        TenantConfig config = getTenantConfig(tenantId);
        int concurrentLimit = config.getConcurrentLimit() != null 
            ? config.getConcurrentLimit() : defaultConcurrent;
        
        String key = "rate:concurrent:" + tenantId;
        Long current = redisTemplate.opsForValue().increment(key);
        if (current == 1) {
            redisTemplate.expire(key, 30, TimeUnit.SECONDS);  // 30秒过期，防止泄漏
        }
        
        if (current > concurrentLimit) {
            redisTemplate.opsForValue().decrement(key);
            return false;
        }
        
        return true;
    }
    
    /**
     * 释放并发计数
     * 请求完成后调用
     */
    public void releaseConcurrent(Long tenantId) {
        String key = "rate:concurrent:" + tenantId;
        redisTemplate.opsForValue().decrement(key);
    }
    
    private TenantConfig getTenantConfig(Long tenantId) {
        // 从缓存查询租户限流配置
        String configKey = "tenant:config:" + tenantId;
        String configJson = redisTemplate.opsForValue().get(configKey);
        if (configJson != null) {
            return JSON.parseObject(configJson, TenantConfig.class);
        }
        return new TenantConfig();  // 返回默认配置
    }
}
```

### 5.2 容器级资源隔离

对于专属云和私有化交付的租户，系统通过容器资源配额实现CPU和内存的硬隔离。使用Docker/Cgroups的资源配置能力，为每个租户的容器设置资源上限。

```java
/**
 * 租户资源配置
 * 管理租户的容器资源配额
 */
@Data
public class TenantResourceQuota {
    private Long tenantId;
    private int cpuLimit;       // CPU核心数限制（毫核，如1000=1核）
    private int memoryLimit;    // 内存限制（MB）
    private int diskLimit;      // 磁盘限制（GB）
    private int networkLimit;   // 网络带宽限制（Mbps）
}

/**
 * 容器资源管理服务
 * 为租户的容器设置Cgroups资源限制
 */
@Service
public class ContainerResourceService {
    
    @Resource
    private ContainerRuntimeClient containerClient;
    
    /**
     * 为租户创建资源受限的容器
     * 
     * 算法说明：
     * 1. 查询租户资源配额
     * 2. 构建容器资源配置
     * 3. 创建容器，设置Cgroups限制
     * 4. 记录资源分配信息
     */
    public String createTenantContainer(Long tenantId, String image, 
            Map<String, String> env) {
        TenantResourceQuota quota = getQuota(tenantId);
        
        ContainerCreateRequest request = ContainerCreateRequest.builder()
            .image(image)
            .env(env)
            .cpuQuota(quota.getCpuLimit() * 1000)  // 转换为微秒
            .memoryLimit(quota.getMemoryLimit() * 1024 * 1024L)  // 转换为字节
            .diskQuota(quota.getDiskLimit() * 1024 * 1024 * 1024L)
            .networkRateLimit(quota.getNetworkLimit())
            .label("tenant-id", String.valueOf(tenantId))
            .build();
        
        return containerClient.createAndStart(request);
    }
    
    private TenantResourceQuota getQuota(Long tenantId) {
        // 查询租户资源配额配置
        return quotaRepository.findByTenantId(tenantId);
    }
}
```

---

## 六、多租户计费计量

### 6.1 用量采集与计量模型

参考多租户平台的Token用量可视化与成本归属能力，系统实现了多维度的用量采集和计量模型。计量维度包括API调用次数、存储使用量、网络流量、计算资源使用时长等。

```java
/**
 * 用量采集服务
 * 采集租户的资源使用量，作为计费依据
 * 
 * 采集维度：
 * 1. API调用次数：每次API请求记录一次
 * 2. 存储用量：定时扫描统计
 * 3. 网络流量：网络层统计
 * 4. 计算资源：容器监控数据
 * 
 * 设计模式：异步采集+批量写入
 * 使用消息队列解耦采集和写入，避免影响业务请求性能
 */
@Service
public class UsageCollector {
    
    @Resource
    private RocketMQTemplate mqTemplate;
    
    /**
     * 记录API调用
     * 在API网关层拦截，异步上报到消息队列
     */
    public void recordApiCall(Long tenantId, String apiPath, 
            long responseTime, boolean success) {
        UsageEvent event = new UsageEvent();
        event.setEventId(IdGenerator.nextId());
        event.setTenantId(tenantId);
        event.setEventType(UsageEventType.API_CALL);
        event.setApiPath(apiPath);
        event.setResponseTime(responseTime);
        event.setSuccess(success);
        event.setTimestamp(System.currentTimeMillis());
        
        // 异步发送到消息队列
        mqTemplate.asyncSend("usage-topic", event, new SendCallback() {
            @Override
            public void onSuccess(SendResult result) {}
            @Override
            public void onException(Throwable e) {
                log.error("Usage event send failed", e);
            }
        });
    }
    
    /**
     * 定时采集存储用量
     * 每小时执行一次，扫描租户的存储使用情况
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void collectStorageUsage() {
        List<Long> activeTenantIds = tenantService.findAllActiveTenantIds();
        
        for (Long tenantId : activeTenantIds) {
            long storageBytes = calculateStorageUsage(tenantId);
            
            UsageEvent event = new UsageEvent();
            event.setEventId(IdGenerator.nextId());
            event.setTenantId(tenantId);
            event.setEventType(UsageEventType.STORAGE);
            event.setStorageBytes(storageBytes);
            event.setTimestamp(System.currentTimeMillis());
            
            mqTemplate.send("usage-topic", event);
        }
    }
    
    /**
     * 计算租户存储用量
     */
    private long calculateStorageUsage(Long tenantId) {
        // 查询数据库中租户数据大小
        long dbSize = tenantMapper.calculateTenantDataSize(tenantId);
        
        // 查询对象存储中租户文件大小
        long objectSize = objectStorageService.getTenantUsage(tenantId);
        
        return dbSize + objectSize;
    }
}

/**
 * 用量事件类型
 */
public enum UsageEventType {
    API_CALL,       // API调用
    STORAGE,        // 存储用量
    NETWORK,        // 网络流量
    COMPUTE         // 计算资源
}
```

### 6.2 计量聚合与账单生成

```java
/**
 * 计量聚合服务
 * 将原始用量事件聚合为按小时/天/月维度的统计数据
 * 
 * 算法说明：
 * 1. 消费用量事件消息队列
 * 2. 按租户+小时维度聚合
 * 3. 写入计量统计表
 * 4. 每月初生成月度账单
 */
@Service
@RocketMQMessageListener(topic = "usage-topic", consumerGroup = "usage-consumer")
public class UsageAggregator implements RocketMQListener<UsageEvent> {
    
    @Resource
    private UsageStatsRepository statsRepository;
    
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    
    @Override
    public void onMessage(UsageEvent event) {
        // 按小时维度聚合
        long hourTimestamp = event.getTimestamp() / (3600 * 1000) * (3600 * 1000);
        String statsKey = "usage:stats:" + event.getTenantId() 
            + ":" + event.getEventType() 
            + ":" + hourTimestamp;
        
        // 使用Redis Hash累加
        redisTemplate.opsForHash().increment(statsKey, "count", 1);
        if (event.getStorageBytes() > 0) {
            redisTemplate.opsForHash().increment(statsKey, "bytes", event.getStorageBytes());
        }
        redisTemplate.expire(statsKey, 7, TimeUnit.DAYS);
        
        // 每小时持久化一次到MySQL
        // 使用定时任务将Redis中的统计数据批量写入MySQL
    }
    
    /**
     * 生成月度账单
     * 每月1日凌晨执行，生成上月账单
     */
    @Scheduled(cron = "0 0 0 1 * ?")
    public void generateMonthlyBill() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        List<Long> tenantIds = tenantService.findAllActiveTenantIds();
        
        for (Long tenantId : tenantIds) {
            Bill bill = generateBill(tenantId, lastMonth);
            billRepository.save(bill);
        }
    }
    
    private Bill generateBill(Long tenantId, LocalDate month) {
        // 查询月度用量统计
        UsageStats stats = statsRepository.getMonthlyStats(tenantId, month);
        
        // 查询计费规则
        PricingRule rule = pricingRuleService.getRule(tenantId);
        
        // 计算费用
        BigDecimal apiCost = BigDecimal.valueOf(stats.getApiCallCount())
            .multiply(rule.getApiCallPrice());
        BigDecimal storageCost = BigDecimal.valueOf(stats.getStorageBytes())
            .divide(BigDecimal.valueOf(1024 * 1024 * 1024), 4, RoundingMode.HALF_UP)
            .multiply(rule.getStoragePricePerGB());
        
        Bill bill = new Bill();
        bill.setTenantId(tenantId);
        bill.setBillMonth(month.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        bill.setApiCallCount(stats.getApiCallCount());
        bill.setStorageBytes(stats.getStorageBytes());
        bill.setApiCost(apiCost);
        bill.setStorageCost(storageCost);
        bill.setTotalCost(apiCost.add(storageCost));
        bill.setCreateTime(System.currentTimeMillis());
        
        return bill;
    }
}
```

---

## 七、租户定制化

### 7.1 配置驱动的业务定制

不同租户的业务场景差异大，系统通过配置驱动的方式实现定制化，而非为每个租户维护独立代码分支。租户配置存储在配置中心，支持热更新。

```java
/**
 * 租户配置服务
 * 管理租户级别的业务配置，支持热更新
 * 
 * 设计模式：配置中心模式
 * 所有租户差异化行为通过配置控制，而非代码分支
 */
@Service
public class TenantConfigService {
    
    @Resource
    private TenantConfigRepository configRepository;
    
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    
    /**
     * 获取租户配置
     * 优先从Redis缓存读取，缓存未命中则查询数据库
     */
    public TenantConfig getConfig(Long tenantId) {
        String cacheKey = "tenant:config:" + tenantId;
        String json = redisTemplate.opsForValue().get(cacheKey);
        
        if (json != null) {
            return JSON.parseObject(json, TenantConfig.class);
        }
        
        TenantConfig config = configRepository.findByTenantId(tenantId);
        if (config == null) {
            config = TenantConfig.defaultConfig();
        }
        
        redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(config), 
            5, TimeUnit.MINUTES);
        
        return config;
    }
    
    /**
     * 更新租户配置
     * 更新后清除缓存，支持热生效
     */
    public void updateConfig(Long tenantId, TenantConfig newConfig) {
        newConfig.setTenantId(tenantId);
        newConfig.setUpdateTime(System.currentTimeMillis());
        configRepository.save(newConfig);
        
        // 清除缓存
        redisTemplate.delete("tenant:config:" + tenantId);
        
        // 发布配置变更事件，通知各服务节点
        publishConfigChangeEvent(tenantId);
    }
    
    private void publishConfigChangeEvent(Long tenantId) {
        ConfigChangeEvent event = new ConfigChangeEvent();
        event.setTenantId(tenantId);
        event.setTimestamp(System.currentTimeMillis());
        // 通过MQ广播配置变更
        mqTemplate.send("config-change-topic", event);
    }
}

/**
 * 租户配置
 */
@Data
public class TenantConfig {
    private Long tenantId;
    
    // 限流配置
    private Integer qpsLimit;
    private Integer concurrentLimit;
    
    // 功能开关
    private Boolean enableExport;
    private Boolean enableAdvancedAnalytics;
    private Boolean enableSso;
    
    // UI定制
    private String themeColor;
    private String logoUrl;
    private String customDomain;
    
    // 业务规则
    private Integer dataRetentionDays;
    private Integer maxUsers;
    private Integer maxProjects;
    
    public static TenantConfig defaultConfig() {
        TenantConfig config = new TenantConfig();
        config.setQpsLimit(100);
        config.setConcurrentLimit(50);
        config.setEnableExport(true);
        config.setEnableAdvancedAnalytics(false);
        config.setDataRetentionDays(90);
        config.setMaxUsers(100);
        config.setMaxProjects(10);
        return config;
    }
}
```

### 7.2 SPI扩展点机制

对于需要深度定制的场景，系统使用SPI（Service Provider Interface）机制支持租户级别的扩展实现。系统定义标准接口，租户可以提供自己的实现类，通过Java SPI机制动态加载。

```java
/**
 * SPI扩展点接口
 * 租户可以实现此接口提供自定义业务逻辑
 */
public interface TenantExtension {
    
    /**
     * 获取扩展点名称
     */
    String getName();
    
    /**
     * 判断是否适用于指定租户
     */
    boolean supports(Long tenantId);
    
    /**
     * 执行扩展逻辑
     */
    Object execute(ExtensionContext context);
}

/**
 * 扩展点管理器
 * 使用SPI机制加载所有扩展实现，按租户匹配执行
 * 
 * 设计模式：策略模式 + SPI服务发现
 */
@Service
public class ExtensionManager {
    
    private List<TenantExtension> extensions;
    
    @PostConstruct
    public void init() {
        // 通过Java SPI加载所有扩展实现
        extensions = new ArrayList<>();
        ServiceLoader<TenantExtension> loader = ServiceLoader.load(TenantExtension.class);
        for (TenantExtension ext : loader) {
            extensions.add(ext);
        }
    }
    
    /**
     * 执行租户扩展
     */
    public Object executeExtension(String extensionName, ExtensionContext context) {
        Long tenantId = TenantContext.getTenantId();
        
        return extensions.stream()
            .filter(e -> e.getName().equals(extensionName) && e.supports(tenantId))
            .findFirst()
            .map(e -> e.execute(context))
            .orElse(null);
    }
}
```

---

## 八、租户生命周期管理

### 8.1 租户状态机

租户的生命周期通过状态机模式管理，包括创建、开通、冻结、解冻、注销等状态流转。每个状态转换都有前置条件和后置操作。

```java
/**
 * 租户状态机
 * 管理租户从创建到注销的完整生命周期
 * 
 * 状态流转：
 * CREATING → ACTIVE → FROZEN → ACTIVE → CANCELING → CANCELED
 * 
 * 设计模式：状态机模式
 * 每个状态有独立的处理逻辑，状态转换由事件触发
 */
@Service
public class TenantStateMachine {
    
    private static final Map<Tenant.TenantStatus, Set<Tenant.TenantStatus>> TRANSITIONS = new HashMap<>();
    
    static {
        // 定义合法的状态转换
        TRANSITIONS.put(Tenant.TenantStatus.CREATING, 
            Set.of(Tenant.TenantStatus.ACTIVE));
        TRANSITIONS.put(Tenant.TenantStatus.ACTIVE, 
            Set.of(Tenant.TenantStatus.FROZEN, Tenant.TenantStatus.CANCELING));
        TRANSITIONS.put(Tenant.TenantStatus.FROZEN, 
            Set.of(Tenant.TenantStatus.ACTIVE, Tenant.TenantStatus.CANCELING));
        TRANSITIONS.put(Tenant.TenantStatus.CANCELING, 
            Set.of(Tenant.TenantStatus.CANCELED));
    }
    
    @Resource
    private TenantService tenantService;
    
    @Resource
    private TenantProvisionService provisionService;
    
    /**
     * 状态转换
     */
    public void transition(Long tenantId, Tenant.TenantStatus targetState) {
        Tenant tenant = tenantService.findById(tenantId);
        Tenant.TenantStatus current = tenant.getStatus();
        
        // 校验状态转换合法性
        if (!TRANSITIONS.getOrDefault(current, Set.of()).contains(targetState)) {
            throw new TenantException(
                "Invalid state transition: " + current + " → " + targetState);
        }
        
        // 执行前置操作
        beforeTransition(tenant, targetState);
        
        // 更新状态
        tenant.setStatus(targetState);
        tenant.setUpdateTime(System.currentTimeMillis());
        tenantService.save(tenant);
        
        // 执行后置操作
        afterTransition(tenant, targetState);
    }
    
    private void beforeTransition(Tenant tenant, Tenant.TenantStatus target) {
        switch (target) {
            case ACTIVE:
                // 从CREATING到ACTIVE：执行租户初始化
                if (tenant.getStatus() == Tenant.TenantStatus.CREATING) {
                    provisionService.provision(tenant);
                }
                break;
            case FROZEN:
                // 冻结前：断开所有活跃连接
                break;
            case CANCELING:
                // 注销前：检查是否有未完成业务
                break;
        }
    }
    
    private void afterTransition(Tenant tenant, Tenant.TenantStatus target) {
        switch (target) {
            case FROZEN:
                // 冻结后：拒绝所有请求
                break;
            case CANCELED:
                // 注销后：清理租户数据
                provisionService.deprovision(tenant);
                break;
        }
    }
}

/**
 * 租户开通服务
 * 负责新租户的资源初始化
 */
@Service
public class TenantProvisionService {
    
    @Resource
    private DataSource sharedDataSource;
    
    @Resource
    private RedisTemplate<String, String> redisTemplate;
    
    /**
     * 租户开通
     * 算法说明：
     * 1. 创建租户默认配置
     * 2. 初始化租户数据空间（创建默认数据）
     * 3. 分配资源配额
     * 4. 发送开通通知
     */
    public void provision(Tenant tenant) {
        // 1. 创建默认配置
        TenantConfig config = TenantConfig.defaultConfig();
        config.setTenantId(tenant.getTenantId());
        configRepository.save(config);
        
        // 2. 初始化默认数据
        initDefaultData(tenant);
        
        // 3. 分配资源配额
        TenantResourceQuota quota = new TenantResourceQuota();
        quota.setTenantId(tenant.getTenantId());
        quota.setCpuLimit(1000);   // 1核
        quota.setMemoryLimit(2048); // 2GB
        quota.setDiskLimit(50);     // 50GB
        quotaRepository.save(quota);
        
        // 4. 清除配置缓存
        redisTemplate.delete("tenant:config:" + tenant.getTenantId());
    }
    
    /**
     * 租户注销
     * 算法说明：
     * 1. 备份租户数据
     * 2. 删除租户数据
     * 3. 释放资源配额
     * 4. 清除缓存
     */
    public void deprovision(Tenant tenant) {
        // 1. 备份数据
        backupTenantData(tenant);
        
        // 2. 删除数据
        tenantMapper.deleteByTenantId(tenant.getTenantId());
        
        // 3. 释放资源
        quotaRepository.deleteByTenantId(tenant.getTenantId());
        
        // 4. 清除缓存
        redisTemplate.delete("tenant:config:" + tenant.getTenantId());
    }
    
    private void initDefaultData(Tenant tenant) {
        // 创建默认管理员账号
        // 创建默认项目空间
        // 初始化默认权限角色
    }
    
    private void backupTenantData(Tenant tenant) {
        // 导出租户数据到对象存储
    }
}
```

---

## 九、数据安全与合规

### 9.1 数据加密

多租户环境中，数据安全至关重要。系统实现了传输层加密（TLS）、存储层加密（透明数据加密TDE）、应用层加密（敏感字段加密）三层加密体系。

```java
/**
 * 租户数据加密服务
 * 三层加密：传输层TLS + 存储层TDE + 应用层字段加密
 * 
 * 应用层加密算法：
 * - AES-256-GCM：对称加密，用于敏感数据加密
 * - 每个租户独立的加密密钥
 * - 密钥轮换：每90天自动轮换
 */
@Service
public class TenantEncryptionService {
    
    @Resource
    private KeyManagementService keyService;
    
    /**
     * 加密敏感数据
     * 使用租户专属密钥进行AES-256-GCM加密
     * 
     * @param tenantId 租户ID
     * @param plaintext 明文数据
     * @return Base64编码的密文
     */
    public String encrypt(Long tenantId, String plaintext) {
        SecretKey key = keyService.getTenantKey(tenantId);
        
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            
            // IV + 密文拼接
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    /**
     * 解密数据
     */
    public String decrypt(Long tenantId, String ciphertext) {
        SecretKey key = keyService.getTenantKey(tenantId);
        
        try {
            byte[] data = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOf(data, 12);
            byte[] encrypted = Arrays.copyOfRange(data, 12, data.length);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
```

### 9.2 审计日志

所有租户操作都记录审计日志，支持安全审计和合规要求。

```java
/**
 * 租户审计日志服务
 * 记录所有跨租户操作和数据访问行为
 */
@Service
public class TenantAuditService {
    
    @Resource
    private AuditLogRepository auditRepository;
    
    /**
     * 记录审计日志
     */
    public void audit(String action, String resource, String detail) {
        AuditLog log = new AuditLog();
        log.setId(IdGenerator.nextId());
        log.setTenantId(TenantContext.getTenantId());
        log.setUserId(SecurityContext.getUserId());
        log.setAction(action);
        log.setResource(resource);
        log.setDetail(detail);
        log.setClientIp(RequestContext.getClientIp());
        log.setTimestamp(System.currentTimeMillis());
        
        auditRepository.save(log);
    }
    
    /**
     * 查询审计日志
     */
    public Page<AuditLog> query(Long tenantId, AuditQuery query, int page, int size) {
        return auditRepository.findByTenantId(tenantId, query, PageRequest.of(page, size));
    }
}
```

---

## 十、系统总结

多租户SaaS架构系统的核心设计包括以下方面。隔离模型方面，采用Org → Workspace两级隔离架构，支持SaaS、专属云、私有化三种交付模式，通过混合数据隔离策略（共享Schema + 独立数据库）平衡成本与安全性。数据隔离方面，使用MyBatis拦截器自动在SQL中注入tenant_id条件，结合JSqlParser的AST操作实现透明的数据隔离，对业务代码零侵入。资源隔离方面，基于Redis令牌桶算法实现租户级QPS和并发限流，通过Cgroups容器资源配额实现CPU和内存硬隔离。计费计量方面，通过异步采集+批量写入模式收集用量数据，按租户+时间维度聚合统计，支持按API调用次数和存储用量计费。定制化方面，采用配置驱动+SPI扩展点双模式，配置驱动覆盖大部分差异化需求，SPI扩展点支持深度定制。生命周期管理方面，使用状态机模式管理租户从创建到注销的完整生命周期，每个状态转换都有前置校验和后置操作。数据安全方面，实现传输层TLS、存储层TDE、应用层AES-256-GCM三层加密体系，每个租户独立加密密钥，支持密钥自动轮换。

---

## 十一、全链路实战案例

本章从工程落地视角，选取三个最具代表性的端到端场景，给出可直接映射到生产代码的完整实现，包括入参校验、幂等控制、事务边界、异常处理与结构化日志埋点。三个案例分别覆盖"租户从0到1开通"、"请求在运行时如何被路由与隔离"、"租户套餐变更的资源联动"，串联起本文前十章涉及的上下文管理、数据隔离、限流、计费、定制化与生命周期管理能力。

### 11.1 案例一：租户注册与环境初始化全链路

#### 11.1.1 业务场景与链路概览

企业客户在官网提交入驻申请后，系统需要完成"注册请求接收 → 参数与唯一性校验 → 租户记录创建（CREATING状态）→ 分配租户编码与初始配置 → 初始化数据空间（Schema/默认数据）→ 分配资源配额 → 开通成功回调与通知 → 状态流转为ACTIVE"的完整链路。整个链路涉及多个下游依赖（DB、Redis、对象存储、消息队列、通知服务），任意环节失败都可能导致"半开通"的脏数据租户，因此该链路的设计重点是**幂等**与**失败可重试、可回滚**。

链路时序：`RegisterController → TenantRegisterService（幂等校验+分布式锁）→ TenantService（创建CREATING记录，本地事务）→ TenantProvisionService（初始化Schema/默认数据/配额，可重试）→ TenantStateMachine（CREATING→ACTIVE）→ MQ异步通知（欢迎邮件/回调）`。

#### 11.1.2 幂等注册请求与状态流转控制

注册接口天然存在重复提交风险（用户重复点击、网关重试、客户端超时重发），系统采用"业务唯一键（企业邮箱/统一社会信用代码）+ Redis分布式锁 + 数据库唯一索引"三重幂等保障。

```java
/**
 * 租户注册请求DTO
 */
@Data
public class TenantRegisterRequest {

    @NotBlank(message = "企业名称不能为空")
    @Size(max = 128, message = "企业名称长度不能超过128")
    private String companyName;

    @NotBlank(message = "联系邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String contactEmail;

    @NotBlank(message = "统一社会信用代码不能为空")
    @Pattern(regexp = "^[0-9A-Z]{18}$", message = "统一社会信用代码格式不正确")
    private String socialCreditCode;

    @NotNull(message = "交付模式不能为空")
    private Tenant.TenantDelivery delivery;

    /**
     * 客户端生成的幂等键，防止网络重试导致重复注册
     * 建议由前端在表单展示时生成一次UUID并全程复用
     */
    @NotBlank(message = "幂等键不能为空")
    private String idempotentKey;
}

/**
 * 租户注册服务
 * 核心目标：保证同一企业/同一幂等键的重复请求只会成功注册一次
 *
 * 幂等设计：
 * 1. 请求级幂等：基于idempotentKey的Redis SETNX，拦截短时间内的重复点击/重试
 * 2. 业务级幂等：基于socialCreditCode的数据库唯一索引，防止不同幂等键但相同企业的重复注册
 * 3. 分布式锁：基于socialCreditCode加锁，防止并发请求同时通过唯一性校验后重复插入
 */
@Slf4j
@Service
public class TenantRegisterService {

    private static final String IDEMPOTENT_KEY_PREFIX = "tenant:register:idem:";
    private static final String LOCK_KEY_PREFIX = "tenant:register:lock:";
    private static final long LOCK_WAIT_MS = 3000L;
    private static final long LOCK_LEASE_MS = 10000L;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TenantService tenantService;

    @Resource
    private TenantProvisionService provisionService;

    @Resource
    private TenantStateMachine tenantStateMachine;

    @Resource
    private RocketMQTemplate mqTemplate;

    /**
     * 租户注册主流程
     *
     * @param request 注册请求
     * @return 注册结果（包含租户ID、租户编码）
     */
    public TenantRegisterResult register(TenantRegisterRequest request) {
        String traceId = MDC.get("traceId");
        log.info("[TenantRegister] 收到注册请求, idempotentKey={}, company={}, traceId={}",
            request.getIdempotentKey(), request.getCompanyName(), traceId);

        // 1. 请求级幂等校验：短时间内相同idempotentKey直接返回已有结果，不重复处理
        String idemKey = IDEMPOTENT_KEY_PREFIX + request.getIdempotentKey();
        Boolean firstSeen = redisTemplate.opsForValue()
            .setIfAbsent(idemKey, "PROCESSING", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(firstSeen)) {
            String cached = redisTemplate.opsForValue().get(idemKey);
            log.warn("[TenantRegister] 检测到重复请求, idempotentKey={}, cachedStatus={}",
                request.getIdempotentKey(), cached);
            if (cached != null && cached.startsWith("SUCCESS:")) {
                Long existTenantId = Long.valueOf(cached.substring("SUCCESS:".length()));
                Tenant existTenant = tenantService.findById(existTenantId);
                return TenantRegisterResult.of(existTenant);
            }
            throw new TenantException("请求正在处理中，请勿重复提交");
        }

        // 2. 业务级幂等：基于统一社会信用代码加分布式锁，防止并发重复注册
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.getSocialCreditCode());
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.error("[TenantRegister] 获取分布式锁失败, socialCreditCode={}",
                    request.getSocialCreditCode());
                throw new TenantException("系统繁忙，请稍后重试");
            }

            // 3. 数据库唯一性兜底校验（唯一索引 + 先查后插）
            Tenant existing = tenantService.findBySocialCreditCode(request.getSocialCreditCode());
            if (existing != null) {
                log.warn("[TenantRegister] 企业已注册, socialCreditCode={}, existTenantId={}",
                    request.getSocialCreditCode(), existing.getTenantId());
                redisTemplate.opsForValue().set(idemKey,
                    "SUCCESS:" + existing.getTenantId(), 24, TimeUnit.HOURS);
                return TenantRegisterResult.of(existing);
            }

            // 4. 创建租户主记录（CREATING状态），本地事务保证记录落库
            Tenant tenant = createTenantRecord(request);
            log.info("[TenantRegister] 租户主记录创建成功, tenantId={}, tenantCode={}",
                tenant.getTenantId(), tenant.getTenantCode());

            // 5. 执行开通与状态流转（内部包含重试与补偿）
            try {
                tenantStateMachine.transition(tenant.getTenantId(), Tenant.TenantStatus.ACTIVE);
            } catch (Exception e) {
                log.error("[TenantRegister] 租户开通失败，标记为待人工介入, tenantId={}",
                    tenant.getTenantId(), e);
                // 开通失败不直接抛异常给用户，转入异步补偿队列重试，避免用户侧看到"注册失败"
                publishProvisionRetryEvent(tenant.getTenantId(), 0);
            }

            // 6. 异步发送欢迎通知（不阻塞主流程，失败不影响注册结果）
            sendWelcomeNotificationSafely(tenant);

            // 7. 更新幂等标记为最终成功结果，供后续重复请求直接返回
            redisTemplate.opsForValue().set(idemKey,
                "SUCCESS:" + tenant.getTenantId(), 24, TimeUnit.HOURS);

            return TenantRegisterResult.of(tenant);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[TenantRegister] 加锁被中断, socialCreditCode={}",
                request.getSocialCreditCode(), e);
            redisTemplate.delete(idemKey);
            throw new TenantException("系统繁忙，请稍后重试", e);
        } catch (TenantException e) {
            // 业务异常：清除PROCESSING标记，允许用户修正后重新提交
            redisTemplate.delete(idemKey);
            throw e;
        } catch (Exception e) {
            log.error("[TenantRegister] 注册流程发生未知异常, request={}", request, e);
            redisTemplate.delete(idemKey);
            throw new TenantException("注册失败，请稍后重试", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 创建租户主记录
     * 独立本地事务，确保CREATING记录要么完全写入要么回滚，不与后续开通逻辑共用事务
     */
    @Transactional(rollbackFor = Exception.class)
    public Tenant createTenantRecord(TenantRegisterRequest request) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(IdGenerator.nextId());
        tenant.setTenantCode(generateTenantCode(request.getCompanyName()));
        tenant.setTenantName(request.getCompanyName());
        tenant.setLevel(TenantLevel.ORG);
        tenant.setStatus(Tenant.TenantStatus.CREATING);
        tenant.setDelivery(request.getDelivery());
        tenant.setCreateTime(System.currentTimeMillis());
        tenant.setUpdateTime(System.currentTimeMillis());

        try {
            tenantService.save(tenant);
        } catch (DuplicateKeyException e) {
            // 数据库唯一索引兜底：极端并发下锁未生效也不会产生脏数据
            log.error("[TenantRegister] 数据库唯一索引冲突, socialCreditCode={}",
                request.getSocialCreditCode(), e);
            throw new TenantException("企业已注册，请勿重复提交", e);
        }
        return tenant;
    }

    /**
     * 生成租户编码：企业名拼音首字母 + 时间戳后6位 + 2位随机数，保证可读且低冲突
     */
    private String generateTenantCode(String companyName) {
        String suffix = String.valueOf(System.currentTimeMillis()).substring(7);
        int random = ThreadLocalRandom.current().nextInt(10, 99);
        return "T" + suffix + random;
    }

    /**
     * 发布开通重试事件，交由异步补偿消费者处理，最多重试3次
     */
    private void publishProvisionRetryEvent(Long tenantId, int retryCount) {
        ProvisionRetryEvent event = new ProvisionRetryEvent();
        event.setTenantId(tenantId);
        event.setRetryCount(retryCount);
        event.setTimestamp(System.currentTimeMillis());
        try {
            mqTemplate.syncSend("tenant-provision-retry-topic", event);
        } catch (Exception mqEx) {
            log.error("[TenantRegister] 开通重试事件投递失败, tenantId={}", tenantId, mqEx);
        }
    }

    private void sendWelcomeNotificationSafely(Tenant tenant) {
        try {
            WelcomeNotifyEvent event = new WelcomeNotifyEvent();
            event.setTenantId(tenant.getTenantId());
            event.setTenantCode(tenant.getTenantCode());
            mqTemplate.asyncSend("tenant-welcome-topic", event, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    log.info("[TenantRegister] 欢迎通知投递成功, tenantId={}", tenant.getTenantId());
                }
                @Override
                public void onException(Throwable e) {
                    log.warn("[TenantRegister] 欢迎通知投递失败, tenantId={}", tenant.getTenantId(), e);
                }
            });
        } catch (Exception e) {
            // 通知失败不影响主流程
            log.warn("[TenantRegister] 欢迎通知发送异常, tenantId={}", tenant.getTenantId(), e);
        }
    }
}

/**
 * 开通重试事件
 */
@Data
public class ProvisionRetryEvent {
    private Long tenantId;
    private int retryCount;
    private long timestamp;
}

/**
 * 注册结果
 */
@Data
public class TenantRegisterResult {
    private Long tenantId;
    private String tenantCode;
    private String status;

    public static TenantRegisterResult of(Tenant tenant) {
        TenantRegisterResult result = new TenantRegisterResult();
        result.setTenantId(tenant.getTenantId());
        result.setTenantCode(tenant.getTenantCode());
        result.setStatus(tenant.getStatus().name());
        return result;
    }
}
```

#### 11.1.3 环境初始化的可重试实现

`TenantProvisionService.provision()` 涉及多个外部依赖，任一环节失败都需要支持从失败点重试，而非从头再来。系统将初始化拆分为独立子步骤，每个子步骤幂等且带有明确的进度标记，配合MQ消费者实现"至少一次执行、幂等落地"的最终一致性。

```java
/**
 * 租户环境初始化重试消费者
 * 消费ProvisionRetryEvent，对失败的开通步骤进行有限次重试
 *
 * 幂等设计：
 * 1. 每个子步骤在执行前检查ProvisionProgress记录，已完成的步骤直接跳过
 * 2. 使用tenantId作为幂等键更新进度表，MQ重复投递不会重复执行已完成步骤
 * 3. 超过最大重试次数后转入人工告警，不再自动重试，避免无限循环消耗资源
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "tenant-provision-retry-topic",
    consumerGroup = "tenant-provision-retry-consumer")
public class TenantProvisionRetryConsumer implements RocketMQListener<ProvisionRetryEvent> {

    private static final int MAX_RETRY_COUNT = 3;

    @Resource
    private TenantProvisionService provisionService;

    @Resource
    private TenantStateMachine tenantStateMachine;

    @Resource
    private AlertService alertService;

    @Resource
    private RocketMQTemplate mqTemplate;

    @Override
    public void onMessage(ProvisionRetryEvent event) {
        Long tenantId = event.getTenantId();
        log.info("[ProvisionRetry] 开始处理开通重试, tenantId={}, retryCount={}",
            tenantId, event.getRetryCount());

        try {
            Tenant tenant = provisionService.getTenant(tenantId);
            if (tenant == null) {
                log.error("[ProvisionRetry] 租户不存在，丢弃事件, tenantId={}", tenantId);
                return;
            }
            if (tenant.getStatus() == Tenant.TenantStatus.ACTIVE) {
                log.info("[ProvisionRetry] 租户已处于ACTIVE状态，无需重试, tenantId={}", tenantId);
                return;
            }

            provisionService.provisionWithProgress(tenant);
            tenantStateMachine.transition(tenantId, Tenant.TenantStatus.ACTIVE);

            log.info("[ProvisionRetry] 租户开通重试成功, tenantId={}", tenantId);

        } catch (Exception e) {
            int nextRetryCount = event.getRetryCount() + 1;
            log.error("[ProvisionRetry] 开通重试第{}次失败, tenantId={}",
                nextRetryCount, tenantId, e);

            if (nextRetryCount >= MAX_RETRY_COUNT) {
                log.error("[ProvisionRetry] 已达最大重试次数，转人工介入, tenantId={}", tenantId);
                alertService.alert("TENANT_PROVISION_FAILED",
                    "租户开通失败，已达最大重试次数：tenantId=" + tenantId);
                return;
            }

            ProvisionRetryEvent retryEvent = new ProvisionRetryEvent();
            retryEvent.setTenantId(tenantId);
            retryEvent.setRetryCount(nextRetryCount);
            retryEvent.setTimestamp(System.currentTimeMillis());
            // 延迟消息：RocketMQ延迟级别，避免立即重试造成雪崩
            Message<ProvisionRetryEvent> message = MessageBuilder.withPayload(retryEvent).build();
            mqTemplate.syncSend("tenant-provision-retry-topic", message, 3000, 3);
        }
    }
}
```

```java
/**
 * 租户开通服务（带进度控制的增强版本）
 * 在原有provision()基础上，拆分子步骤并记录进度，支持从任意失败点续跑
 */
@Slf4j
@Service
public class TenantProvisionServiceEx {

    @Resource
    private TenantProvisionProgressRepository progressRepository;

    @Resource
    private TenantConfigRepository configRepository;

    @Resource
    private TenantResourceQuotaRepository quotaRepository;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 带进度控制的租户开通
     * 子步骤：INIT_CONFIG → INIT_DATA → INIT_QUOTA → DONE
     */
    @Transactional(rollbackFor = Exception.class)
    public void provisionWithProgress(Tenant tenant) {
        Long tenantId = tenant.getTenantId();
        TenantProvisionProgress progress = progressRepository.findByTenantId(tenantId);
        if (progress == null) {
            progress = new TenantProvisionProgress();
            progress.setTenantId(tenantId);
            progress.setStep(ProvisionStep.INIT_CONFIG);
        }

        try {
            if (progress.getStep().ordinal() <= ProvisionStep.INIT_CONFIG.ordinal()) {
                initConfigIdempotent(tenant);
                progress.setStep(ProvisionStep.INIT_DATA);
                progressRepository.save(progress);
                log.info("[Provision] 配置初始化完成, tenantId={}", tenantId);
            }

            if (progress.getStep().ordinal() <= ProvisionStep.INIT_DATA.ordinal()) {
                initDefaultDataIdempotent(tenant);
                progress.setStep(ProvisionStep.INIT_QUOTA);
                progressRepository.save(progress);
                log.info("[Provision] 默认数据初始化完成, tenantId={}", tenantId);
            }

            if (progress.getStep().ordinal() <= ProvisionStep.INIT_QUOTA.ordinal()) {
                initQuotaIdempotent(tenant);
                progress.setStep(ProvisionStep.DONE);
                progressRepository.save(progress);
                log.info("[Provision] 资源配额初始化完成, tenantId={}", tenantId);
            }

            redisTemplate.delete("tenant:config:" + tenantId);

        } catch (Exception e) {
            log.error("[Provision] 开通步骤{}执行失败, tenantId={}", progress.getStep(), tenantId, e);
            throw new TenantException("租户环境初始化失败于步骤：" + progress.getStep(), e);
        }
    }

    /**
     * 幂等初始化配置：使用insert ignore / upsert语义，重复调用不会产生重复记录
     */
    private void initConfigIdempotent(Tenant tenant) {
        TenantConfig existing = configRepository.findByTenantId(tenant.getTenantId());
        if (existing != null) {
            return;
        }
        TenantConfig config = TenantConfig.defaultConfig();
        config.setTenantId(tenant.getTenantId());
        configRepository.save(config);
    }

    private void initDefaultDataIdempotent(Tenant tenant) {
        // 通过"是否已存在默认管理员账号"判断是否已初始化，避免重复创建
        if (tenantMapper.existsDefaultAdmin(tenant.getTenantId())) {
            return;
        }
        tenantMapper.createDefaultAdmin(tenant.getTenantId());
        tenantMapper.createDefaultWorkspace(tenant.getTenantId());
        tenantMapper.initDefaultRoles(tenant.getTenantId());
    }

    private void initQuotaIdempotent(Tenant tenant) {
        TenantResourceQuota existing = quotaRepository.findByTenantId(tenant.getTenantId());
        if (existing != null) {
            return;
        }
        TenantResourceQuota quota = new TenantResourceQuota();
        quota.setTenantId(tenant.getTenantId());
        quota.setCpuLimit(1000);
        quota.setMemoryLimit(2048);
        quota.setDiskLimit(50);
        quota.setNetworkLimit(100);
        quotaRepository.save(quota);
    }
}

/**
 * 开通步骤枚举，顺序即执行顺序
 */
public enum ProvisionStep {
    INIT_CONFIG,
    INIT_DATA,
    INIT_QUOTA,
    DONE
}

/**
 * 开通进度记录
 */
@Data
public class TenantProvisionProgress {
    private Long tenantId;
    private ProvisionStep step;
    private long updateTime;
}
```

该案例的关键工程要点：第一，三层幂等（Redis请求级幂等、分布式锁、数据库唯一索引）逐层收窄并发窗口，即使某一层失效也有兜底；第二，将"注册"与"开通"解耦，注册主记录写入成功即对用户可见，开通失败转异步重试，避免用户长时间等待或看到误导性失败提示；第三，开通流程按步骤记录进度，重试时从失败步骤续跑而非全部重来，每个子步骤自身也做了幂等判断（先查询是否已存在再写入），双重保障不产生脏数据。

---

### 11.2 案例二：租户请求的路由与数据隔离全链路

#### 11.2.1 业务场景与链路概览

租户开通后，所有业务请求都要经过"网关鉴权 → 租户上下文构建 → 限流检查 → 数据源路由 → SQL隔离改写 → 业务处理 → 响应与上下文清理"的运行时链路。相比案例一的低频写操作，本案例是高QPS的读写热路径，设计重点是**性能**（不能每次请求都全量查库）、**正确性**（绝不能发生跨租户数据泄露）与**可观测性**（异常时能快速定位是哪个租户的请求出了问题）。

链路时序：`TenantContextInterceptor（解析Token+缓存查询租户状态）→ TenantRateLimiter（QPS/并发限流）→ RoutingDataSourceContextHolder（选择数据源）→ TenantSqlInterceptor（SQL改写）→ Mapper执行 → Controller返回 → afterCompletion清理上下文与并发计数`。

#### 11.2.2 增强版租户识别与路由拦截器

在案例基础章节（3.2节）的拦截器上，补充异常处理、限流集成、数据源路由与全链路日志埋点，形成生产可用的完整实现。

```java
/**
 * 生产级租户请求路由拦截器
 * 在基础TenantContextInterceptor之上，补充：
 * 1. 租户状态本地缓存（Caffeine）+ Redis二级缓存，降低状态查询开销
 * 2. 限流检查前置，超限请求不进入业务层
 * 3. 数据源路由与MDC日志上下文绑定，便于全链路问题排查
 * 4. 统一异常处理，避免上下文未清理导致的线程复用污染
 */
@Slf4j
@Component
public class TenantRoutingInterceptor implements HandlerInterceptor {

    @Resource
    private TenantService tenantService;

    @Resource
    private JwtTokenService jwtTokenService;

    @Resource
    private TenantRateLimiter rateLimiter;

    /** 租户状态本地缓存，缓解Redis/DB压力，TTL 30秒 */
    private final Cache<Long, Tenant> tenantStatusCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build();

    @Override
    public boolean preHandle(HttpServletRequest request,
            HttpServletResponse response, Object handler) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("traceId", traceId);
        long startTime = System.currentTimeMillis();

        try {
            // 1. 提取并解码Token
            String token = extractToken(request);
            if (token == null) {
                log.warn("[TenantRouting] 缺失Authorization Token, uri={}, traceId={}",
                    request.getRequestURI(), traceId);
                writeErrorResponse(response, 401, "UNAUTHORIZED", "缺少认证信息");
                return false;
            }

            JwtPayload payload;
            try {
                payload = jwtTokenService.decode(token);
            } catch (Exception e) {
                log.warn("[TenantRouting] Token解析失败, traceId={}", traceId, e);
                writeErrorResponse(response, 401, "INVALID_TOKEN", "认证信息无效");
                return false;
            }

            // 2. 查询租户状态（本地缓存 → Redis → DB 三级查询链路）
            Long orgId = payload.getOrgId();
            Tenant tenant = getTenantWithCache(orgId);
            if (tenant == null) {
                log.warn("[TenantRouting] 租户不存在, orgId={}, traceId={}", orgId, traceId);
                writeErrorResponse(response, 404, "TENANT_NOT_FOUND", "租户不存在");
                return false;
            }
            if (tenant.getStatus() == Tenant.TenantStatus.FROZEN) {
                log.warn("[TenantRouting] 租户已被冻结，拒绝请求, tenantId={}, traceId={}",
                    tenant.getTenantId(), traceId);
                writeErrorResponse(response, 403, "TENANT_FROZEN", "账户已冻结，请联系客服");
                return false;
            }
            if (tenant.getStatus() != Tenant.TenantStatus.ACTIVE) {
                log.warn("[TenantRouting] 租户状态异常, tenantId={}, status={}, traceId={}",
                    tenant.getTenantId(), tenant.getStatus(), traceId);
                writeErrorResponse(response, 403, "TENANT_NOT_ACTIVE", "账户状态异常");
                return false;
            }

            // 3. 构建租户上下文
            Long effectiveTenantId = payload.getWorkspaceId() != null
                ? payload.getWorkspaceId() : orgId;
            TenantContext.TenantInfo info = TenantContext.TenantInfo.builder()
                .orgId(orgId)
                .workspaceId(payload.getWorkspaceId())
                .tenantId(effectiveTenantId)
                .level(tenant.getLevel())
                .tenantCode(tenant.getTenantCode())
                .delivery(tenant.getDelivery())
                .build();
            TenantContext.set(info);
            MDC.put("tenantId", String.valueOf(effectiveTenantId));
            MDC.put("tenantCode", tenant.getTenantCode());

            // 4. 限流检查：并发限制 + QPS限制，任一超限即拒绝
            if (!rateLimiter.checkConcurrentLimit(effectiveTenantId)) {
                log.warn("[TenantRouting] 租户并发超限, tenantId={}, traceId={}",
                    effectiveTenantId, traceId);
                writeErrorResponse(response, 429, "CONCURRENT_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试");
                return false;
            }
            if (!rateLimiter.checkQpsLimit(effectiveTenantId, request.getRequestURI())) {
                log.warn("[TenantRouting] 租户QPS超限, tenantId={}, uri={}, traceId={}",
                    effectiveTenantId, request.getRequestURI(), traceId);
                rateLimiter.releaseConcurrent(effectiveTenantId);
                writeErrorResponse(response, 429, "QPS_LIMIT_EXCEEDED", "请求过于频繁，请稍后重试");
                return false;
            }

            // 5. 数据源路由：根据交付模式切换共享库/独立库
            RoutingDataSourceContextHolder.setDataSourceKey(
                resolveDataSourceKey(tenant.getDelivery(), effectiveTenantId));

            request.setAttribute("rateLimitAcquired", true);
            request.setAttribute("tenantIdForRelease", effectiveTenantId);

            log.info("[TenantRouting] 请求进入, method={}, uri={}, tenantId={}, cost={}ms",
                request.getMethod(), request.getRequestURI(), effectiveTenantId,
                System.currentTimeMillis() - startTime);

            return true;

        } catch (Exception e) {
            // 兜底异常处理：任何未预期异常都不能让请求带着脏上下文进入业务层
            log.error("[TenantRouting] 拦截器处理异常, uri={}, traceId={}",
                request.getRequestURI(), traceId, e);
            TenantContext.clear();
            RoutingDataSourceContextHolder.clearDataSourceKey();
            writeErrorResponse(response, 500, "INTERNAL_ERROR", "系统繁忙，请稍后重试");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
            HttpServletResponse response, Object handler, Exception ex) {
        try {
            if (ex != null) {
                log.error("[TenantRouting] 请求处理异常结束, uri={}, tenantId={}",
                    request.getRequestURI(), MDC.get("tenantId"), ex);
            }
            // 释放并发限流计数，必须成对出现，否则会导致计数泄漏
            Object acquired = request.getAttribute("rateLimitAcquired");
            if (Boolean.TRUE.equals(acquired)) {
                Long tenantId = (Long) request.getAttribute("tenantIdForRelease");
                rateLimiter.releaseConcurrent(tenantId);
            }
        } catch (Exception e) {
            log.error("[TenantRouting] afterCompletion清理异常", e);
        } finally {
            // 无论成功失败，ThreadLocal与MDC必须清理，防止线程池复用导致上下文串租户
            TenantContext.clear();
            RoutingDataSourceContextHolder.clearDataSourceKey();
            MDC.clear();
        }
    }

    /**
     * 三级缓存查询租户状态：本地缓存 → Redis → 数据库
     * 本地缓存TTL短（30秒），保证冻结等状态变更能较快生效，同时大幅降低DB压力
     */
    private Tenant getTenantWithCache(Long orgId) {
        Tenant cached = tenantStatusCache.getIfPresent(orgId);
        if (cached != null) {
            return cached;
        }
        try {
            Tenant tenant = tenantService.findById(orgId);
            if (tenant != null) {
                tenantStatusCache.put(orgId, tenant);
            }
            return tenant;
        } catch (Exception e) {
            log.error("[TenantRouting] 查询租户状态失败, orgId={}", orgId, e);
            throw new TenantException("查询租户信息失败", e);
        }
    }

    private String resolveDataSourceKey(Tenant.TenantDelivery delivery, Long tenantId) {
        if (delivery == Tenant.TenantDelivery.SAAS) {
            return "shared";
        }
        return "isolated_" + tenantId;
    }

    private String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private void writeErrorResponse(HttpServletResponse response, int status,
            String errorCode, String message) {
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            Map<String, Object> body = new HashMap<>();
            body.put("code", errorCode);
            body.put("message", message);
            body.put("traceId", MDC.get("traceId"));
            response.getWriter().write(JSON.toJSONString(body));
        } catch (IOException e) {
            log.error("[TenantRouting] 写入错误响应失败", e);
        }
    }
}
```

#### 11.2.3 动态数据源路由与跨租户访问兜底防护

数据源路由基于Spring的`AbstractRoutingDataSource`实现，并在最终执行层增加"越权访问检测"作为最后一道防线，即使SQL拦截器出现遗漏，也能在数据返回前拦截跨租户数据。

```java
/**
 * 数据源路由上下文
 * 使用ThreadLocal保存当前请求应使用的数据源标识
 */
public class RoutingDataSourceContextHolder {

    private static final ThreadLocal<String> DS_KEY = new ThreadLocal<>();

    public static void setDataSourceKey(String key) {
        DS_KEY.set(key);
    }

    public static String getDataSourceKey() {
        return DS_KEY.get();
    }

    public static void clearDataSourceKey() {
        DS_KEY.remove();
    }
}

/**
 * 动态路由数据源
 * 根据ThreadLocal中的数据源标识，动态决定实际执行的数据源
 */
@Slf4j
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        String key = RoutingDataSourceContextHolder.getDataSourceKey();
        if (key == null) {
            log.warn("[DataSourceRouting] 未设置数据源标识，回退到默认共享库");
            return "shared";
        }
        return key;
    }
}

/**
 * 越权访问兜底校验器
 * 作为SQL拦截器之外的最后一道防线：在结果集返回业务层之前，
 * 对携带tenant_id字段的结果做二次校验，发现跨租户数据立即阻断并告警
 *
 * 设计动机：MyBatis拦截器基于SQL文本解析，理论上存在个别Mapper遗漏
 * @IgnoreTenant误用、或原生SQL未被正确改写的风险，需要运行时兜底
 */
@Slf4j
@Component
public class CrossTenantAccessGuard {

    @Resource
    private AlertService alertService;

    /**
     * 校验查询结果是否存在跨租户数据
     *
     * @param results 查询结果集，元素需实现TenantAware接口暴露tenant_id
     * @param expectedTenantId 期望的租户ID（当前上下文租户）
     * @param mapperId 触发校验的Mapper方法标识，用于问题定位
     */
    public <T extends TenantAware> void guard(List<T> results, Long expectedTenantId,
            String mapperId) {
        if (results == null || results.isEmpty() || expectedTenantId == null) {
            return;
        }
        for (T item : results) {
            if (item.getTenantId() != null && !item.getTenantId().equals(expectedTenantId)) {
                log.error("[CrossTenantGuard] 检测到跨租户数据访问！mapperId={}, "
                        + "expectedTenantId={}, actualTenantId={}",
                    mapperId, expectedTenantId, item.getTenantId());
                alertService.alert("CROSS_TENANT_DATA_LEAK",
                    String.format("mapperId=%s, expectedTenantId=%s, actualTenantId=%s",
                        mapperId, expectedTenantId, item.getTenantId()));
                throw new TenantSecurityException(
                    "检测到跨租户数据访问异常，请求已阻断，mapperId=" + mapperId);
            }
        }
    }
}

/**
 * 租户感知接口，需要参与越权校验的实体需实现此接口
 */
public interface TenantAware {
    Long getTenantId();
}

/**
 * 跨租户安全异常，属于严重安全问题，需要单独的异常类型便于监控告警分类
 */
public class TenantSecurityException extends RuntimeException {
    public TenantSecurityException(String message) {
        super(message);
    }
}
```

#### 11.2.4 业务层调用示例：读多写少场景下的幂等更新

以"更新租户下某个项目的配置"为例，展示在完整路由与隔离链路保护下，业务代码如何叠加操作级幂等（防止表单重复提交导致的数据错乱），并配合结构化日志记录关键字段，便于问题回溯。

```java
/**
 * 项目配置更新服务
 * 运行于TenantRoutingInterceptor建立的租户上下文之内，
 * 所有数据库操作自动被TenantSqlInterceptor注入tenant_id条件
 */
@Slf4j
@Service
public class ProjectConfigService {

    @Resource
    private ProjectConfigMapper projectConfigMapper;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 更新项目配置
     * 幂等设计：基于"projectId + 请求版本号(version)"的乐观锁机制，
     * 避免并发更新互相覆盖，同时天然具备幂等语义（相同version的重复请求不会重复生效）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateProjectConfig(Long projectId, ProjectConfigUpdateRequest request) {
        Long tenantId = TenantContext.getTenantId();
        log.info("[ProjectConfig] 开始更新项目配置, tenantId={}, projectId={}, version={}",
            tenantId, projectId, request.getExpectedVersion());

        // 操作级幂等：同一(tenantId, projectId, requestId)在5分钟内只处理一次
        String idemKey = "project:config:update:" + tenantId + ":" + projectId
            + ":" + request.getRequestId();
        Boolean firstSeen = redisTemplate.opsForValue()
            .setIfAbsent(idemKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(firstSeen)) {
            log.warn("[ProjectConfig] 检测到重复的配置更新请求, tenantId={}, projectId={}, requestId={}",
                tenantId, projectId, request.getRequestId());
            return;
        }

        try {
            // 乐观锁更新：WHERE条件包含version，UPDATE影响行数为0说明版本已变化
            int updated = projectConfigMapper.updateWithVersion(
                projectId, request.getNewConfig(), request.getExpectedVersion());

            if (updated == 0) {
                log.warn("[ProjectConfig] 乐观锁冲突，配置已被其他请求修改, tenantId={}, projectId={}",
                    tenantId, projectId);
                throw new TenantException("配置已被修改，请刷新后重试");
            }

            log.info("[ProjectConfig] 项目配置更新成功, tenantId={}, projectId={}", tenantId, projectId);

        } catch (TenantException e) {
            redisTemplate.delete(idemKey);
            throw e;
        } catch (Exception e) {
            log.error("[ProjectConfig] 项目配置更新异常, tenantId={}, projectId={}",
                tenantId, projectId, e);
            redisTemplate.delete(idemKey);
            throw new TenantException("配置更新失败", e);
        }
    }
}

/**
 * 项目配置更新请求
 */
@Data
public class ProjectConfigUpdateRequest {
    private String requestId;       // 客户端幂等键
    private String newConfig;       // 新配置内容（JSON）
    private Integer expectedVersion; // 乐观锁版本号
}
```

该案例的关键工程要点：第一，租户状态查询采用"本地缓存 → Redis → DB"三级链路，将高频路径的状态校验开销降到最低，同时通过短TTL保证冻结等安全相关状态变更能及时生效；第二，限流检查前置于业务逻辑且严格保证`acquire`与`release`成对出现，`afterCompletion`中做兜底释放，避免因业务异常导致并发计数泄漏、限流器逐渐失效；第三，除了SQL拦截器的主防线外，增加了结果集层面的越权访问兜底校验，形成纵深防御；第四，ThreadLocal上下文与MDC日志上下文的清理放在`finally`块中，确保线程池复用场景下不会发生上下文串租户的严重问题。

---

### 11.3 案例三：租户套餐升降级全链路

#### 11.3.1 业务场景与链路概览

租户在使用过程中会发起套餐升级（如从基础版升级到专业版）或降级操作，涉及"发起变更请求 → 校验当前资源使用量是否满足降级条件 → 支付/合同确认（升级场景）→ 事务性更新套餐与配额 → 联动调整限流阈值、资源配额、功能开关 → 记录变更流水 → 异步下发到网关/容器编排系统生效 → 通知租户管理员"的链路。该链路的设计重点是**多资源变更的一致性**（配置、配额、限流、功能开关必须同时生效，不能出现部分生效的中间态）与**降级时的业务安全**（不能让降级直接破坏超出新套餐限制的存量数据）。

链路时序：`PlanChangeController → PlanChangeService（幂等校验+前置校验）→ 本地事务（更新套餐记录+配额+配置）→ 发布PlanChangedEvent → 多个订阅者异步生效（限流器刷新/容器资源调整/网关缓存失效）→ 通知服务`。

#### 11.3.2 套餐模型与变更前置校验

```java
/**
 * 套餐定义
 */
@Data
public class TenantPlan {
    private Long planId;
    private String planCode;      // BASIC / PRO / ENTERPRISE
    private String planName;
    private Integer qpsLimit;
    private Integer concurrentLimit;
    private Integer maxUsers;
    private Integer maxProjects;
    private Integer dataRetentionDays;
    private Integer cpuLimit;
    private Integer memoryLimit;
    private Integer diskLimit;
    private BigDecimal monthlyPrice;
    private Set<String> enabledFeatures;   // 该套餐启用的功能开关集合
}

/**
 * 套餐变更请求
 */
@Data
public class PlanChangeRequest {

    @NotNull(message = "目标套餐不能为空")
    private String targetPlanCode;

    @NotBlank(message = "幂等键不能为空")
    private String requestId;

    /** 升级场景下的支付/合同凭证号，降级场景可为空 */
    private String paymentOrderId;

    private String operatorId;
}

/**
 * 套餐变更记录，用于审计与问题回溯
 */
@Data
public class PlanChangeLog {
    private Long id;
    private Long tenantId;
    private String fromPlanCode;
    private String toPlanCode;
    private String changeType;   // UPGRADE / DOWNGRADE
    private String status;       // PROCESSING / SUCCESS / FAILED / ROLLED_BACK
    private String operatorId;
    private String failReason;
    private long createTime;
    private long updateTime;
}
```

```java
/**
 * 套餐变更服务
 * 核心目标：多资源（套餐记录、配额、限流配置、功能开关）变更保持强一致，
 * 降级操作必须先校验现有资源用量，避免"降级后立即超限"的不安全状态
 *
 * 幂等设计：
 * 1. 基于requestId的Redis幂等标记，防止重复提交
 * 2. 基于tenantId的分布式锁，防止同一租户并发发起多次套餐变更
 * 3. 变更记录（PlanChangeLog）落库时使用requestId唯一索引兜底
 */
@Slf4j
@Service
public class PlanChangeService {

    private static final String LOCK_KEY_PREFIX = "tenant:plan:change:lock:";
    private static final String IDEM_KEY_PREFIX = "tenant:plan:change:idem:";

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private TenantPlanRepository planRepository;

    @Resource
    private TenantService tenantService;

    @Resource
    private TenantResourceQuotaRepository quotaRepository;

    @Resource
    private TenantConfigRepository configRepository;

    @Resource
    private PlanChangeLogRepository changeLogRepository;

    @Resource
    private TenantUsageQueryService usageQueryService;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private PaymentService paymentService;

    /**
     * 套餐变更主流程
     */
    public PlanChangeResult changePlan(Long tenantId, PlanChangeRequest request) {
        log.info("[PlanChange] 收到套餐变更请求, tenantId={}, targetPlan={}, requestId={}",
            tenantId, request.getTargetPlanCode(), request.getRequestId());

        // 1. 幂等校验
        String idemKey = IDEM_KEY_PREFIX + tenantId + ":" + request.getRequestId();
        Boolean firstSeen = redisTemplate.opsForValue()
            .setIfAbsent(idemKey, "PROCESSING", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(firstSeen)) {
            String cached = redisTemplate.opsForValue().get(idemKey);
            log.warn("[PlanChange] 检测到重复的套餐变更请求, tenantId={}, requestId={}, cached={}",
                tenantId, request.getRequestId(), cached);
            if (cached != null && cached.startsWith("SUCCESS")) {
                return PlanChangeResult.success(tenantId, request.getTargetPlanCode());
            }
            throw new TenantException("套餐变更正在处理中，请勿重复提交");
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + tenantId);
        boolean locked = false;
        PlanChangeLog changeLog = null;

        try {
            locked = lock.tryLock(3000, 15000, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.error("[PlanChange] 获取租户套餐变更锁失败, tenantId={}", tenantId);
                throw new TenantException("套餐变更处理中，请稍后重试");
            }

            Tenant tenant = tenantService.findById(tenantId);
            if (tenant == null || tenant.getStatus() != Tenant.TenantStatus.ACTIVE) {
                throw new TenantException("租户状态异常，无法变更套餐");
            }

            TenantPlan currentPlan = planRepository.findCurrentPlan(tenantId);
            TenantPlan targetPlan = planRepository.findByCode(request.getTargetPlanCode());
            if (targetPlan == null) {
                throw new TenantException("目标套餐不存在：" + request.getTargetPlanCode());
            }
            if (currentPlan != null && currentPlan.getPlanCode().equals(targetPlan.getPlanCode())) {
                throw new TenantException("目标套餐与当前套餐相同，无需变更");
            }

            boolean isUpgrade = isUpgrade(currentPlan, targetPlan);
            String changeType = isUpgrade ? "UPGRADE" : "DOWNGRADE";

            // 2. 创建变更记录（PROCESSING），用于审计和问题追溯
            changeLog = createChangeLog(tenantId, currentPlan, targetPlan, changeType, request);

            // 3. 升级需要校验支付凭证；降级需要校验现有资源用量是否超出目标套餐限制
            if (isUpgrade) {
                validatePayment(tenantId, request, targetPlan);
            } else {
                validateDowngradeSafety(tenantId, targetPlan);
            }

            // 4. 事务性更新套餐、配额、配置
            applyPlanChangeTransactional(tenant, targetPlan);

            // 5. 更新变更记录为成功
            changeLog.setStatus("SUCCESS");
            changeLog.setUpdateTime(System.currentTimeMillis());
            changeLogRepository.update(changeLog);

            // 6. 发布套餐变更事件，触发限流器/容器资源/网关缓存等异步生效动作
            eventPublisher.publishEvent(new PlanChangedEvent(tenantId,
                currentPlan == null ? null : currentPlan.getPlanCode(),
                targetPlan.getPlanCode()));

            redisTemplate.opsForValue().set(idemKey, "SUCCESS", 24, TimeUnit.HOURS);

            log.info("[PlanChange] 套餐变更成功, tenantId={}, {} -> {}",
                tenantId, currentPlan == null ? "NONE" : currentPlan.getPlanCode(),
                targetPlan.getPlanCode());

            return PlanChangeResult.success(tenantId, targetPlan.getPlanCode());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisTemplate.delete(idemKey);
            throw new TenantException("系统繁忙，请稍后重试", e);
        } catch (TenantException e) {
            log.warn("[PlanChange] 套餐变更被拒绝, tenantId={}, reason={}", tenantId, e.getMessage());
            markChangeLogFailed(changeLog, e.getMessage());
            redisTemplate.delete(idemKey);
            throw e;
        } catch (Exception e) {
            log.error("[PlanChange] 套餐变更发生未知异常, tenantId={}", tenantId, e);
            markChangeLogFailed(changeLog, "系统异常：" + e.getMessage());
            redisTemplate.delete(idemKey);
            throw new TenantException("套餐变更失败，请稍后重试", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 事务性应用套餐变更：套餐记录、资源配额、租户配置三者在同一本地事务中更新，
     * 保证不会出现"配额已更新但配置未更新"的中间态
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyPlanChangeTransactional(Tenant tenant, TenantPlan targetPlan) {
        Long tenantId = tenant.getTenantId();

        // 更新套餐记录
        planRepository.updateCurrentPlan(tenantId, targetPlan.getPlanCode());

        // 更新资源配额
        TenantResourceQuota quota = quotaRepository.findByTenantId(tenantId);
        if (quota == null) {
            quota = new TenantResourceQuota();
            quota.setTenantId(tenantId);
        }
        quota.setCpuLimit(targetPlan.getCpuLimit());
        quota.setMemoryLimit(targetPlan.getMemoryLimit());
        quota.setDiskLimit(targetPlan.getDiskLimit());
        quotaRepository.save(quota);

        // 更新租户配置（限流阈值、功能开关、业务规则）
        TenantConfig config = configRepository.findByTenantId(tenantId);
        if (config == null) {
            config = TenantConfig.defaultConfig();
            config.setTenantId(tenantId);
        }
        config.setQpsLimit(targetPlan.getQpsLimit());
        config.setConcurrentLimit(targetPlan.getConcurrentLimit());
        config.setMaxUsers(targetPlan.getMaxUsers());
        config.setMaxProjects(targetPlan.getMaxProjects());
        config.setDataRetentionDays(targetPlan.getDataRetentionDays());
        config.setEnableAdvancedAnalytics(
            targetPlan.getEnabledFeatures().contains("ADVANCED_ANALYTICS"));
        config.setEnableSso(targetPlan.getEnabledFeatures().contains("SSO"));
        config.setUpdateTime(System.currentTimeMillis());
        configRepository.save(config);
    }

    /**
     * 降级安全校验：检查当前资源使用量是否超出目标套餐限制
     * 任一维度超限则拒绝降级，要求租户先清理数据或联系客服人工处理
     */
    private void validateDowngradeSafety(Long tenantId, TenantPlan targetPlan) {
        TenantUsageSnapshot usage = usageQueryService.getCurrentUsage(tenantId);

        List<String> violations = new ArrayList<>();
        if (usage.getUserCount() > targetPlan.getMaxUsers()) {
            violations.add(String.format("用户数(%d)超过目标套餐上限(%d)",
                usage.getUserCount(), targetPlan.getMaxUsers()));
        }
        if (usage.getProjectCount() > targetPlan.getMaxProjects()) {
            violations.add(String.format("项目数(%d)超过目标套餐上限(%d)",
                usage.getProjectCount(), targetPlan.getMaxProjects()));
        }
        if (usage.getStorageBytes() > (long) targetPlan.getDiskLimit() * 1024 * 1024 * 1024) {
            violations.add("存储用量超过目标套餐上限");
        }

        if (!violations.isEmpty()) {
            String reason = String.join("；", violations);
            log.warn("[PlanChange] 降级校验未通过, tenantId={}, targetPlan={}, violations={}",
                tenantId, targetPlan.getPlanCode(), reason);
            throw new TenantException("当前资源用量超出目标套餐限制，无法降级：" + reason);
        }
    }

    /**
     * 升级支付校验：调用支付服务确认支付订单有效且金额匹配
     */
    private void validatePayment(Long tenantId, PlanChangeRequest request, TenantPlan targetPlan) {
        if (request.getPaymentOrderId() == null) {
            throw new TenantException("升级套餐需提供有效的支付凭证");
        }
        PaymentOrder order;
        try {
            order = paymentService.getOrder(request.getPaymentOrderId());
        } catch (Exception e) {
            log.error("[PlanChange] 查询支付订单失败, tenantId={}, paymentOrderId={}",
                tenantId, request.getPaymentOrderId(), e);
            throw new TenantException("支付凭证校验失败，请稍后重试", e);
        }
        if (order == null || !"PAID".equals(order.getStatus())) {
            throw new TenantException("支付未完成，无法升级套餐");
        }
        if (order.getAmount().compareTo(targetPlan.getMonthlyPrice()) < 0) {
            log.error("[PlanChange] 支付金额与套餐价格不匹配, tenantId={}, paid={}, expected={}",
                tenantId, order.getAmount(), targetPlan.getMonthlyPrice());
            throw new TenantException("支付金额与套餐价格不匹配");
        }
    }

    private boolean isUpgrade(TenantPlan currentPlan, TenantPlan targetPlan) {
        if (currentPlan == null) {
            return true;
        }
        return targetPlan.getMonthlyPrice().compareTo(currentPlan.getMonthlyPrice()) > 0;
    }

    private PlanChangeLog createChangeLog(Long tenantId, TenantPlan currentPlan,
            TenantPlan targetPlan, String changeType, PlanChangeRequest request) {
        PlanChangeLog log = new PlanChangeLog();
        log.setId(IdGenerator.nextId());
        log.setTenantId(tenantId);
        log.setFromPlanCode(currentPlan == null ? "NONE" : currentPlan.getPlanCode());
        log.setToPlanCode(targetPlan.getPlanCode());
        log.setChangeType(changeType);
        log.setStatus("PROCESSING");
        log.setOperatorId(request.getOperatorId());
        log.setCreateTime(System.currentTimeMillis());
        log.setUpdateTime(System.currentTimeMillis());
        try {
            changeLogRepository.save(log);
        } catch (DuplicateKeyException e) {
            // requestId唯一索引兜底：极端场景下Redis幂等失效，数据库层仍能拦截重复变更
            log.setStatus("FAILED");
            throw new TenantException("检测到重复的套餐变更请求", e);
        }
        return log;
    }

    private void markChangeLogFailed(PlanChangeLog changeLog, String reason) {
        if (changeLog == null) {
            return;
        }
        try {
            changeLog.setStatus("FAILED");
            changeLog.setFailReason(reason);
            changeLog.setUpdateTime(System.currentTimeMillis());
            changeLogRepository.update(changeLog);
        } catch (Exception e) {
            log.error("[PlanChange] 更新变更失败记录时发生异常, tenantId={}", changeLog.getTenantId(), e);
        }
    }
}

/**
 * 套餐变更事件
 */
@Data
@AllArgsConstructor
public class PlanChangedEvent {
    private Long tenantId;
    private String fromPlanCode;
    private String toPlanCode;
}

/**
 * 套餐变更结果
 */
@Data
public class PlanChangeResult {
    private Long tenantId;
    private String currentPlanCode;
    private String status;

    public static PlanChangeResult success(Long tenantId, String planCode) {
        PlanChangeResult result = new PlanChangeResult();
        result.setTenantId(tenantId);
        result.setCurrentPlanCode(planCode);
        result.setStatus("SUCCESS");
        return result;
    }
}
```

#### 11.3.3 变更事件的异步生效与失败补偿

套餐变更的核心数据（套餐记录、配额、配置）已在本地事务中保证一致，但限流器缓存刷新、容器资源实际调整、网关侧缓存失效属于跨系统操作，采用事件驱动的方式异步生效，并对失败场景做补偿重试与告警。

```java
/**
 * 套餐变更事件监听器
 * 使用Spring事务事件监听器，确保只有在applyPlanChangeTransactional的事务提交成功后，
 * 才会触发下游的异步生效动作，避免"事务回滚了但下游已经生效"的不一致
 */
@Slf4j
@Component
public class PlanChangedEventListener {

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private ContainerResourceService containerResourceService;

    @Resource
    private TenantResourceQuotaRepository quotaRepository;

    @Resource
    private RocketMQTemplate mqTemplate;

    @Resource
    private NotificationService notificationService;

    @Resource
    private AlertService alertService;

    /**
     * 事务提交后才执行：清除限流配置缓存，使新的QPS/并发阈值立即生效
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanChangedRefreshRateLimitConfig(PlanChangedEvent event) {
        String cacheKey = "tenant:config:" + event.getTenantId();
        try {
            redisTemplate.delete(cacheKey);
            log.info("[PlanChangedListener] 限流配置缓存已清除, tenantId={}, {} -> {}",
                event.getTenantId(), event.getFromPlanCode(), event.getToPlanCode());
        } catch (Exception e) {
            // 缓存清除失败不阻塞主流程，最坏情况下缓存TTL到期后自动生效（5分钟），记录告警便于关注
            log.error("[PlanChangedListener] 限流配置缓存清除失败, tenantId={}", event.getTenantId(), e);
            alertService.alert("PLAN_CHANGE_CACHE_REFRESH_FAILED",
                "租户" + event.getTenantId() + "套餐变更后限流缓存刷新失败，将在TTL到期后自动生效");
        }
    }

    /**
     * 事务提交后才执行：调整容器实际资源配额（仅专属云/私有化交付需要）
     * 该操作涉及外部容器编排系统调用，失败需要重试，因此投递到MQ异步处理
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanChangedAdjustContainerResource(PlanChangedEvent event) {
        try {
            mqTemplate.syncSend("tenant-resource-adjust-topic", event);
            log.info("[PlanChangedListener] 容器资源调整任务已投递, tenantId={}", event.getTenantId());
        } catch (Exception e) {
            log.error("[PlanChangedListener] 容器资源调整任务投递失败, tenantId={}",
                event.getTenantId(), e);
            alertService.alert("PLAN_CHANGE_RESOURCE_ADJUST_MQ_FAILED",
                "租户" + event.getTenantId() + "套餐变更后容器资源调整任务投递失败，需人工介入");
        }
    }

    /**
     * 事务提交后才执行：异步通知租户管理员套餐变更结果
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanChangedNotifyAdmin(PlanChangedEvent event) {
        try {
            notificationService.notifyPlanChanged(event.getTenantId(),
                event.getFromPlanCode(), event.getToPlanCode());
        } catch (Exception e) {
            // 通知失败属于低优先级问题，仅记录日志不告警，不影响套餐变更本身
            log.warn("[PlanChangedListener] 套餐变更通知发送失败, tenantId={}", event.getTenantId(), e);
        }
    }
}

/**
 * 容器资源调整消费者
 * 消费tenant-resource-adjust-topic，实际调用容器编排系统调整CPU/内存限制
 *
 * 幂等设计：调整前先查询容器当前实际配额，若已等于目标配额则跳过，
 * 避免消息重复投递导致的重复调用（部分容器编排API对相同配置的重复调用非幂等）
 */
@Slf4j
@Service
@RocketMQMessageListener(topic = "tenant-resource-adjust-topic",
    consumerGroup = "tenant-resource-adjust-consumer")
public class TenantResourceAdjustConsumer implements RocketMQListener<PlanChangedEvent> {

    private static final int MAX_RETRY = 5;

    @Resource
    private ContainerResourceService containerResourceService;

    @Resource
    private TenantResourceQuotaRepository quotaRepository;

    @Resource
    private AlertService alertService;

    @Override
    public void onMessage(PlanChangedEvent event) {
        Long tenantId = event.getTenantId();
        log.info("[ResourceAdjust] 开始处理容器资源调整, tenantId={}, toPlan={}",
            tenantId, event.getToPlanCode());

        try {
            TenantResourceQuota targetQuota = quotaRepository.findByTenantId(tenantId);
            if (targetQuota == null) {
                log.error("[ResourceAdjust] 未找到租户资源配额记录，跳过, tenantId={}", tenantId);
                return;
            }

            TenantResourceQuota actualQuota = containerResourceService.getActualQuota(tenantId);
            if (actualQuota != null && quotaEquals(actualQuota, targetQuota)) {
                log.info("[ResourceAdjust] 容器实际配额已与目标一致，跳过调整, tenantId={}", tenantId);
                return;
            }

            containerResourceService.updateResourceQuota(tenantId, targetQuota);
            log.info("[ResourceAdjust] 容器资源调整成功, tenantId={}, cpu={}, memory={}",
                tenantId, targetQuota.getCpuLimit(), targetQuota.getMemoryLimit());

        } catch (Exception e) {
            log.error("[ResourceAdjust] 容器资源调整失败, tenantId={}", tenantId, e);
            // RocketMQ消费失败会按照消费者组配置自动重试，这里仅在达到框架最大重试后做人工告警
            alertService.alert("CONTAINER_RESOURCE_ADJUST_FAILED",
                "租户" + tenantId + "容器资源调整失败：" + e.getMessage());
            throw new TenantException("容器资源调整失败，等待MQ重试", e);
        }
    }

    private boolean quotaEquals(TenantResourceQuota a, TenantResourceQuota b) {
        return Objects.equals(a.getCpuLimit(), b.getCpuLimit())
            && Objects.equals(a.getMemoryLimit(), b.getMemoryLimit())
            && Objects.equals(a.getDiskLimit(), b.getDiskLimit());
    }
}
```

该案例的关键工程要点：第一，升级与降级采用不同的前置校验策略，升级校验支付凭证的有效性与金额匹配，降级则校验现有资源用量是否超出目标套餐限制，从业务层面阻止"降级后立即超限"的不安全状态；第二，套餐记录、资源配额、租户配置三者的核心变更被收敛到同一个本地事务`applyPlanChangeTransactional`中，保证不会出现部分生效的中间态；第三，跨系统的异步生效动作（限流缓存刷新、容器资源调整、通知）统一使用`@TransactionalEventListener(phase = AFTER_COMMIT)`，确保只有在核心事务真正提交后才会触发，避免事务回滚但下游已生效的不一致；第四，容器资源调整消费者在执行前先对比目标配额与容器实际配额，只有存在差异才真正调用外部编排系统，使消费者具备幂等性，能够安全应对MQ的至少一次投递语义。</new_string>
