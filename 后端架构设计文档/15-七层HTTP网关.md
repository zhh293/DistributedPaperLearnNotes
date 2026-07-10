# 七层HTTP网关

## 一、问题背景

### 1.1 为什么需要七层网关

在大规模微服务架构中，后端服务通常由数千个HTTP服务节点组成，日访问量可达数千亿级别，峰值QPS高达数百万。面对如此庞大的流量规模，系统需要一个统一的流量入口来解决以下问题：

- **服务发现与路由**：客户端不可能直接感知后端成百上千个服务地址的变化，需要统一的入口进行请求分发
- **负载均衡**：需要将请求均匀分配到多个服务实例，避免单点过载
- **协议支持**：需要统一处理HTTP/HTTPS/HTTP2/WebSocket等多种协议
- **安全管控**：需要在入口层面进行统一的鉴权、限流、熔断等安全控制
- **可观测性**：需要在流量入口进行统一的监控打点和链路追踪

### 1.2 四层与七层负载的区别

七层负载均衡（Layer 7 Switch）是一种集成了路由及通信能力的网络设备，可以根据OSI七层协议的内容进行高效的转发、处理和拦截。与四层负载均衡相比：

| 维度 | 四层负载均衡 | 七层负载均衡 |
|------|------------|------------|
| 工作层次 | 传输层（TCP/UDP） | 应用层（HTTP） |
| 路由依据 | IP地址+端口号 | URL路径、Header、Cookie等 |
| 性能 | 极高（内核态转发） | 较高（用户态处理） |
| 功能丰富度 | 较少 | 丰富（URL路由、内容改写等） |
| 典型代表 | LVS、DPVS | Nginx、OpenResty |
| 典型QPS | 千万级 | 百万级 |

**为什么有了四层负载还需要七层负载？** 四层负载基于IP和端口转发，无法理解HTTP协议语义，无法做到基于URL、Header等信息的精细化路由。而在微服务场景下，精细化路由（如灰度发布、泳道隔离、SET路由）是不可或缺的能力，必须在七层完成。

### 1.3 业界对标

七层网关在业界有丰富的对标产品：

- **Nginx Plus**：配置动态化加载、模块动态化加载能力突出，能极大减少reload次数
- **Kong**：基于OpenResty的API网关，plugin开发友好，支持serverless和报文转换
- **AWS ELB/ALB**：集群管理能力出众，新建集群、扩缩容等操作能秒级自动化完成
- **Envoy**：Cloud-Native Proxy，xDS协议动态配置，Service Mesh的核心数据面组件

## 二、整体架构设计

### 2.1 架构总览

七层HTTP网关的整体架构分为三大平面：**控制面**、**数据面**和**管理面**。

```
┌─────────────────────────────────────────────────────┐
│                     管理面                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ 站点管理  │  │ 路由配置  │  │ 流量调度  │          │
│  └──────────┘  └──────────┘  └──────────┘          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ 证书管理  │  │ 监控告警  │  │ 发布管理  │          │
│  └──────────┘  └──────────┘  └──────────┘          │
└────────────────────┬────────────────────────────────┘
                     │ 配置下发
┌────────────────────▼────────────────────────────────┐
│                     控制面                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ 配置中心  │  │ 服务注册  │  │ 数据同步  │          │
│  │ (Tethys) │  │ (Registry)│  │ (Manager)│          │
│  └──────────┘  └──────────┘  └──────────┘          │
└────────────────────┬────────────────────────────────┘
                     │ 动态配置
┌────────────────────▼────────────────────────────────┐
│                     数据面                           │
│  ┌──────────────────────────────────────────────┐   │
│  │              OpenResty (Nginx + Lua)          │   │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌───────┐ │   │
│  │  │SSL终结 │ │请求路由│ │负载均衡│ │精细化 │ │   │
│  │  │        │ │        │ │        │ │分流   │ │   │
│  │  └────────┘ └────────┘ └────────┘ └───────┘ │   │
│  │  ┌────────┐ ┌────────┐ ┌────────┐ ┌───────┐ │   │
│  │  │限流    │ │健康检查│ │监控打点│ │访问   │ │   │
│  │  │降级    │ │        │ │        │ │控制   │ │   │
│  │  └────────┘ └────────┘ └────────┘ └───────┘ │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 2.2 数据面引擎选型

数据面引擎基于**OpenResty**构建，OpenResty = Nginx + LuaJIT + Lua生态库。选型理由：

1. **高性能**：继承Nginx的事件驱动和异步非阻塞IO模型，单机可达到数十万QPS
2. **可编程**：通过Lua脚本可在Nginx各个处理阶段注入自定义逻辑
3. **热更新**：Lua脚本修改无需reload Nginx进程，支持动态配置变更
4. **生态丰富**：拥有cosocket、lua-resty-http、lua-resty-redis等丰富的Lua库

```java
/**
 * 七层网关核心配置模型
 * 描述一个站点(Site)的完整配置
 */
public class GatewayConfig {
    
    /** 站点ID，全局唯一标识 */
    private String siteId;
    
    /** 域名列表，一个站点可绑定多个域名 */
    private List<String> domains;
    
    /** SSL证书配置 */
    private SslConfig sslConfig;
    
    /** 路由规则列表，按优先级排序 */
    private List<RouteRule> routeRules;
    
    /** 负载均衡配置 */
    private LoadBalanceConfig loadBalanceConfig;
    
    /** 限流配置 */
    private RateLimitConfig rateLimitConfig;
    
    /** 流量调度策略 */
    private TrafficPolicy trafficPolicy;
    
    /** 全局指令配置 */
    private Map<String, String> directives;
}

/**
 * 路由规则
 * 定义请求如何映射到后端服务
 */
public class RouteRule {
    
    /** 匹配路径，支持前缀匹配和精确匹配 */
    private String locationPattern;
    
    /** 匹配类型：PREFIX(前缀匹配)、EXACT(精确匹配)、REGEX(正则匹配) */
    private MatchType matchType;
    
    /** 后端服务标识(appkey) */
    private String upstreamAppKey;
    
    /** 超时配置 */
    private TimeoutConfig timeout;
    
    /** 重试策略 */
    private RetryPolicy retryPolicy;
    
    /** 请求改写规则 */
    private RewriteRule rewriteRule;
}
```

### 2.3 Lua统一框架

为了管理数据面的各种扩展逻辑，需要构建一个Lua统一框架，在Nginx各处理阶段（init_by_lua、rewrite_by_lua、access_by_lua、header_filter_by_lua、body_filter_by_lua、log_by_lua）注入插件化的处理逻辑：

```java
/**
 * Lua插件生命周期管理
 * 对应Nginx各处理阶段的插件注册和调度
 */
public class LuaPluginManager {
    
    /** 按阶段注册的插件链 */
    private Map<NginxPhase, List<LuaPlugin>> phasePlugins;
    
    /**
     * 注册插件到指定阶段
     */
    public void registerPlugin(NginxPhase phase, LuaPlugin plugin, int priority) {
        phasePlugins.computeIfAbsent(phase, k -> new ArrayList<>())
                    .add(plugin);
        // 按优先级排序
        phasePlugins.get(phase).sort(Comparator.comparingInt(LuaPlugin::getPriority));
    }
    
    /**
     * 在指定阶段执行插件链
     * 支持短路：任一插件返回REJECT则中断执行
     */
    public PluginResult executePhase(NginxPhase phase, RequestContext context) {
        List<LuaPlugin> plugins = phasePlugins.getOrDefault(phase, Collections.emptyList());
        for (LuaPlugin plugin : plugins) {
            if (!plugin.isEnabled(context)) {
                continue;
            }
            PluginResult result = plugin.execute(context);
            if (result.getAction() == PluginAction.REJECT) {
                return result; // 短路返回
            }
        }
        return PluginResult.CONTINUE;
    }
}

/**
 * Nginx处理阶段枚举
 */
public enum NginxPhase {
    INIT,              // 进程初始化
    SSL_CERTIFICATE,   // SSL证书选择
    REWRITE,           // URL重写
    ACCESS,            // 访问控制
    CONTENT,           // 内容生成
    HEADER_FILTER,     // 响应头过滤
    BODY_FILTER,       // 响应体过滤
    LOG                // 日志记录
}
```

## 三、核心链路设计

### 3.1 请求处理全链路

一个HTTP请求经过七层网关的完整处理链路如下：

```
客户端请求 → DNS解析 → 四层负载(LVS/DPVS) → 七层网关(OpenResty)
                                                    │
                                    ┌───────────────┼───────────────┐
                                    ▼               ▼               ▼
                              SSL终结/握手    域名匹配(server块)   请求路由
                                    │               │               │
                                    ▼               ▼               ▼
                              流量染色检测    Location匹配    精细化分流
                                    │               │               │
                                    ▼               ▼               ▼
                              SET/泳道路由    负载均衡选节点    健康检查
                                    │               │               │
                                    ▼               ▼               ▼
                              限流/熔断判断    代理转发至后端    监控打点
                                    │                               │
                                    ▼                               ▼
                              响应返回客户端 ◄────────────── 后端响应处理
```

### 3.2 服务注册与发现

七层网关与服务注册中心集成，实现HTTP服务节点的自动注册和发现。服务节点通过注册SDK上报自身的元数据信息：

```java
/**
 * HTTP服务注册信息
 * 服务启动时上报到注册中心
 */
public class HttpServiceRegistration {
    
    /** 服务标识，全局唯一 */
    private String appKey;
    
    /** 节点IP地址 */
    private String ip;
    
    /** 服务端口 */
    private int port;
    
    /** 节点权重，影响负载均衡 */
    private int weight;
    
    /** 泳道标识 */
    private String swimlane;
    
    /** SET标识 */
    private String cell;
    
    /** 环境标识 */
    private String env;
    
    /** 节点状态：ALIVE、DEAD、STARTING、STOPPED */
    private NodeStatus status;
    
    /** 扩展元数据 */
    private Map<String, String> metadata;
}

/**
 * 服务发现客户端
 * 负责从注册中心拉取服务节点列表并维护本地缓存
 */
public class ServiceDiscoveryClient {
    
    private final ConcurrentHashMap<String, List<ServiceNode>> nodeCache = new ConcurrentHashMap<>();
    
    /** 注册中心地址 */
    private final String registryAddress;
    
    /** 本地文件缓存路径，容灾使用 */
    private final String localCachePath;
    
    /**
     * 获取指定服务的可用节点列表
     * 优先从内存缓存获取，缓存未命中则从注册中心拉取
     */
    public List<ServiceNode> getAvailableNodes(String appKey) {
        List<ServiceNode> nodes = nodeCache.get(appKey);
        if (nodes == null || nodes.isEmpty()) {
            nodes = fetchFromRegistry(appKey);
            nodeCache.put(appKey, nodes);
        }
        // 过滤掉不可用节点
        return nodes.stream()
                .filter(node -> node.getStatus() == NodeStatus.ALIVE)
                .collect(Collectors.toList());
    }
    
    /**
     * 订阅服务变更通知
     * 注册中心节点变更时推送增量更新
     */
    public void subscribe(String appKey, ServiceChangeListener listener) {
        // 基于长连接或长轮询接收变更通知
        // 变更时更新本地缓存并通知数据面
    }
    
    /**
     * 启动定时全量同步任务
     * 防止增量推送丢失导致数据不一致
     */
    public void startPeriodicSync(long intervalMs) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            for (String appKey : nodeCache.keySet()) {
                List<ServiceNode> freshNodes = fetchFromRegistry(appKey);
                nodeCache.put(appKey, freshNodes);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }
}
```

### 3.3 负载均衡策略

网关支持多种负载均衡策略，根据不同的业务场景选择合适的策略：

```java
/**
 * 负载均衡策略接口
 */
public interface LoadBalancer {
    
    /**
     * 从候选节点列表中选择一个目标节点
     * @param candidates 候选节点列表（已经过路由规则筛选）
     * @param request 当前请求上下文
     * @return 选中的目标节点
     */
    ServiceNode select(List<ServiceNode> candidates, RequestContext request);
}

/**
 * 加权轮询（Weighted Round Robin）
 * 默认策略，按权重比例分配请求
 */
public class WeightedRoundRobinBalancer implements LoadBalancer {
    
    /** 每个upstream的当前权重状态 */
    private final ConcurrentHashMap<String, AtomicInteger> currentWeights = new ConcurrentHashMap<>();
    
    @Override
    public ServiceNode select(List<ServiceNode> candidates, RequestContext request) {
        if (candidates.isEmpty()) {
            throw new NoAvailableNodeException("No available nodes");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        
        int totalWeight = candidates.stream().mapToInt(ServiceNode::getWeight).sum();
        int maxWeight = -1;
        ServiceNode selected = null;
        
        for (ServiceNode node : candidates) {
            // 平滑加权轮询算法
            int currentWeight = currentWeights
                .computeIfAbsent(node.getAddress(), k -> new AtomicInteger(0))
                .addAndGet(node.getWeight());
            
            if (currentWeight > maxWeight) {
                maxWeight = currentWeight;
                selected = node;
            }
        }
        
        // 选中节点的当前权重减去总权重
        currentWeights.get(selected.getAddress()).addAndGet(-totalWeight);
        
        return selected;
    }
}

/**
 * IP Hash负载均衡
 * 同一IP的请求始终路由到同一后端节点，保持会话亲和性
 */
public class IpHashBalancer implements LoadBalancer {
    
    @Override
    public ServiceNode select(List<ServiceNode> candidates, RequestContext request) {
        String clientIp = request.getClientIp();
        int hash = consistentHash(clientIp);
        int index = Math.abs(hash) % candidates.size();
        return candidates.get(index);
    }
    
    private int consistentHash(String key) {
        // MurmurHash3算法，分布更均匀
        int h = 0;
        for (char c : key.toCharArray()) {
            h = 31 * h + c;
        }
        return h;
    }
}

/**
 * 最小响应时间负载均衡
 * 选择历史平均响应时间最短的节点
 */
public class LeastResponseTimeBalancer implements LoadBalancer {
    
    /** 节点响应时间滑动窗口统计 */
    private final ConcurrentHashMap<String, SlidingWindowStats> nodeStats = new ConcurrentHashMap<>();
    
    @Override
    public ServiceNode select(List<ServiceNode> candidates, RequestContext request) {
        return candidates.stream()
            .min(Comparator.comparingDouble(node -> {
                SlidingWindowStats stats = nodeStats.get(node.getAddress());
                return stats != null ? stats.getAvgResponseTime() : 0.0;
            }))
            .orElse(candidates.get(0));
    }
    
    /**
     * 上报节点响应时间
     */
    public void recordResponseTime(String nodeAddress, long responseTimeMs) {
        nodeStats.computeIfAbsent(nodeAddress, k -> new SlidingWindowStats(60))
                 .record(responseTimeMs);
    }
}
```

### 3.4 健康检查机制

健康检查分为**主动健康检查**和**被动健康检查**两种模式：

```java
/**
 * 主动健康检查器
 * 定期向后端服务节点发送探测请求
 */
public class ActiveHealthChecker {
    
    /** 检查间隔，默认10秒 */
    private long checkIntervalMs = 10_000;
    
    /** 连续失败次数阈值，超过后标记为不健康 */
    private int unhealthyThreshold = 3;
    
    /** 连续成功次数阈值，超过后恢复为健康 */
    private int healthyThreshold = 2;
    
    /** 健康检查路径 */
    private String healthCheckPath = "/health";
    
    /** 超时时间 */
    private long timeoutMs = 3_000;
    
    /** 节点健康状态 */
    private final ConcurrentHashMap<String, HealthStatus> healthStatuses = new ConcurrentHashMap<>();
    
    /**
     * 执行一次健康检查
     */
    public void performCheck(ServiceNode node) {
        HealthStatus status = healthStatuses.computeIfAbsent(
            node.getAddress(), k -> new HealthStatus());
        
        try {
            // 发送HTTP GET请求到健康检查端点
            HttpResponse response = httpClient.get(
                "http://" + node.getAddress() + healthCheckPath, timeoutMs);
            
            if (response.getStatusCode() == 200) {
                status.recordSuccess();
                if (status.getConsecutiveSuccesses() >= healthyThreshold) {
                    status.markHealthy();
                    // 通知负载均衡器恢复该节点
                    notifyNodeRecovered(node);
                }
            } else {
                handleFailure(node, status, "HTTP " + response.getStatusCode());
            }
        } catch (Exception e) {
            handleFailure(node, status, e.getMessage());
        }
    }
    
    private void handleFailure(ServiceNode node, HealthStatus status, String reason) {
        status.recordFailure();
        if (status.getConsecutiveFailures() >= unhealthyThreshold) {
            status.markUnhealthy();
            // 通知负载均衡器摘除该节点
            notifyNodeRemoved(node, reason);
        }
    }
}

/**
 * 被动健康检查器
 * 基于实际请求的响应状态判断节点健康状况
 */
public class PassiveHealthChecker {
    
    /** 滑动窗口内的错误率阈值 */
    private double errorRateThreshold = 0.5;
    
    /** 滑动窗口大小（秒） */
    private int windowSizeSeconds = 30;
    
    /** 最小请求数（窗口内请求数少于此值不做判断） */
    private int minRequests = 10;
    
    /** 摘除后的恢复等待时间 */
    private long recoveryWaitMs = 30_000;
    
    private final ConcurrentHashMap<String, SlidingWindowCounter> errorCounters = new ConcurrentHashMap<>();
    
    /**
     * 记录一次请求结果
     */
    public void recordResult(String nodeAddress, boolean success) {
        SlidingWindowCounter counter = errorCounters.computeIfAbsent(
            nodeAddress, k -> new SlidingWindowCounter(windowSizeSeconds));
        
        counter.recordTotal();
        if (!success) {
            counter.recordError();
        }
        
        // 检查是否需要摘除
        if (counter.getTotalCount() >= minRequests) {
            double errorRate = counter.getErrorRate();
            if (errorRate >= errorRateThreshold) {
                // 触发摘除，并设置定时恢复
                triggerCircuitBreak(nodeAddress, errorRate);
            }
        }
    }
}
```

### 3.5 动态配置管理

七层网关的配置变更是一个高频操作，传统的Nginx需要reload才能生效新配置，这会导致：
- 已建立的长连接（如WebSocket）被断开
- reload期间短暂的性能下降
- 大规模集群的reload耗时长

因此需要实现**动态配置**能力，让配置变更无需reload即可生效：

```java
/**
 * 动态配置管理器
 * 支持配置的实时下发、灰度发布和回滚
 */
public class DynamicConfigManager {
    
    /** 配置版本号 */
    private final AtomicLong version = new AtomicLong(0);
    
    /** 当前生效的配置 */
    private volatile GatewayConfig currentConfig;
    
    /** 配置历史，支持回滚 */
    private final LinkedList<ConfigSnapshot> configHistory = new LinkedList<>();
    
    /** 最大保留的历史版本数 */
    private static final int MAX_HISTORY_SIZE = 50;
    
    /**
     * 应用新配置
     * 支持灰度发布：先在部分机器生效，验证无误后再全量
     */
    public ConfigApplyResult applyConfig(GatewayConfig newConfig, GrayPolicy grayPolicy) {
        // 1. 配置校验
        ValidationResult validation = validateConfig(newConfig);
        if (!validation.isValid()) {
            return ConfigApplyResult.fail(validation.getErrors());
        }
        
        // 2. 保存当前配置到历史
        configHistory.addFirst(new ConfigSnapshot(version.get(), currentConfig));
        if (configHistory.size() > MAX_HISTORY_SIZE) {
            configHistory.removeLast();
        }
        
        // 3. 根据灰度策略决定生效范围
        long newVersion = version.incrementAndGet();
        if (grayPolicy.isFullRelease()) {
            // 全量发布
            this.currentConfig = newConfig;
            notifyAllNodes(newConfig, newVersion);
        } else {
            // 灰度发布：先推送到灰度组
            notifyGrayNodes(newConfig, newVersion, grayPolicy.getGrayGroups());
        }
        
        return ConfigApplyResult.success(newVersion);
    }
    
    /**
     * 配置回滚
     * 回滚到指定版本的配置
     */
    public ConfigApplyResult rollback(long targetVersion) {
        ConfigSnapshot snapshot = configHistory.stream()
            .filter(s -> s.getVersion() == targetVersion)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Version " + targetVersion + " not found in history"));
        
        this.currentConfig = snapshot.getConfig();
        long newVersion = version.incrementAndGet();
        notifyAllNodes(currentConfig, newVersion);
        
        return ConfigApplyResult.success(newVersion);
    }
    
    /**
     * 配置校验
     * 确保新配置的正确性，防止错误配置导致线上故障
     */
    private ValidationResult validateConfig(GatewayConfig config) {
        List<String> errors = new ArrayList<>();
        
        // 校验域名格式
        for (String domain : config.getDomains()) {
            if (!isValidDomain(domain)) {
                errors.add("Invalid domain: " + domain);
            }
            // 域名不能包含大写字母
            if (!domain.equals(domain.toLowerCase())) {
                errors.add("Domain must be lowercase: " + domain);
            }
        }
        
        // 校验路由规则
        for (RouteRule rule : config.getRouteRules()) {
            if (rule.getUpstreamAppKey() == null || rule.getUpstreamAppKey().isEmpty()) {
                errors.add("Route rule missing upstream appkey: " + rule.getLocationPattern());
            }
        }
        
        // 校验SSL证书有效性
        if (config.getSslConfig() != null) {
            if (config.getSslConfig().isExpired()) {
                errors.add("SSL certificate is expired");
            }
        }
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

### 3.6 精细化分流

精细化分流是七层网关的核心能力之一，它允许根据请求的各种属性（Header、Cookie、URL参数、IP等）将流量路由到不同的后端服务组。这是实现灰度发布、SET路由、泳道隔离等高级功能的基础：

```java
/**
 * 流量调度策略
 * 定义请求如何被分流到不同的后端服务组
 */
public class TrafficScheduleStrategy {
    
    /** 策略ID */
    private String strategyId;
    
    /** 策略名称 */
    private String name;
    
    /** 匹配条件列表，按优先级排序 */
    private List<TrafficMatchRule> matchRules;
    
    /** 默认后端（所有规则都不匹配时的fallback） */
    private String defaultUpstream;
    
    /**
     * 执行分流决策
     */
    public TrafficDecision decide(RequestContext request) {
        for (TrafficMatchRule rule : matchRules) {
            if (rule.matches(request)) {
                return new TrafficDecision(rule.getTargetUpstream(), rule.getRuleName());
            }
        }
        return new TrafficDecision(defaultUpstream, "default");
    }
}

/**
 * 流量匹配规则
 */
public class TrafficMatchRule {
    
    /** 规则名称 */
    private String ruleName;
    
    /** 匹配条件（AND关系） */
    private List<MatchCondition> conditions;
    
    /** 目标后端 */
    private String targetUpstream;
    
    /** 流量比例（0-100），用于按比例分流 */
    private int trafficPercentage;
    
    public boolean matches(RequestContext request) {
        // 所有条件都满足才算匹配
        boolean allMatch = conditions.stream().allMatch(c -> c.evaluate(request));
        
        // 如果设置了流量比例，还需要概率判断
        if (allMatch && trafficPercentage < 100) {
            int hash = Math.abs(request.getTraceId().hashCode() % 100);
            return hash < trafficPercentage;
        }
        
        return allMatch;
    }
}

/**
 * 匹配条件
 */
public class MatchCondition {
    
    /** 匹配字段来源 */
    private FieldSource source; // HEADER, COOKIE, QUERY_PARAM, CLIENT_IP, URL_PATH
    
    /** 字段名称 */
    private String fieldName;
    
    /** 匹配操作符 */
    private MatchOperator operator; // EQUALS, CONTAINS, REGEX, IN, PREFIX
    
    /** 匹配值 */
    private String value;
    
    public boolean evaluate(RequestContext request) {
        String actualValue = extractField(request);
        if (actualValue == null) {
            return false;
        }
        
        switch (operator) {
            case EQUALS:
                return value.equals(actualValue);
            case CONTAINS:
                return actualValue.contains(value);
            case REGEX:
                return Pattern.compile(value).matcher(actualValue).matches();
            case IN:
                Set<String> valueSet = new HashSet<>(Arrays.asList(value.split(",")));
                return valueSet.contains(actualValue);
            case PREFIX:
                return actualValue.startsWith(value);
            default:
                return false;
        }
    }
    
    private String extractField(RequestContext request) {
        switch (source) {
            case HEADER:
                return request.getHeader(fieldName);
            case COOKIE:
                return request.getCookie(fieldName);
            case QUERY_PARAM:
                return request.getQueryParam(fieldName);
            case CLIENT_IP:
                return request.getClientIp();
            case URL_PATH:
                return request.getPath();
            default:
                return null;
        }
    }
}
```

### 3.7 限流与过载保护

网关层的限流是保护后端服务免受流量洪峰冲击的第一道防线：

```java
/**
 * 网关限流器
 * 支持多维度限流：站点级、Location级、服务级
 */
public class GatewayRateLimiter {
    
    /** 限流桶存储（使用共享内存在多Worker间共享） */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    
    /**
     * 判断请求是否被限流
     * @return true表示允许通过，false表示被限流
     */
    public boolean tryAcquire(String dimension, String key, RateLimitConfig config) {
        String bucketKey = dimension + ":" + key;
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey, 
            k -> new TokenBucket(config.getRate(), config.getBurst()));
        
        return bucket.tryConsume(1);
    }
    
    /**
     * 站点级限流
     * 保护站点下所有服务不被单一来源打挂
     */
    public boolean checkSiteLimit(String siteId, RequestContext request) {
        RateLimitConfig config = getSiteLimitConfig(siteId);
        if (config == null || !config.isEnabled()) {
            return true;
        }
        return tryAcquire("site", siteId, config);
    }
    
    /**
     * Location级限流
     * 精确控制某个API路径的访问速率
     */
    public boolean checkLocationLimit(String siteId, String location, RequestContext request) {
        String key = siteId + location;
        RateLimitConfig config = getLocationLimitConfig(key);
        if (config == null || !config.isEnabled()) {
            return true;
        }
        return tryAcquire("location", key, config);
    }
}

/**
 * 令牌桶算法实现
 */
public class TokenBucket {
    
    private final double rate;        // 令牌生成速率（个/秒）
    private final double capacity;    // 桶容量（突发上限）
    private double tokens;            // 当前令牌数
    private long lastRefillTime;      // 上次填充时间
    
    public TokenBucket(double rate, double capacity) {
        this.rate = rate;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillTime = System.nanoTime();
    }
    
    public synchronized boolean tryConsume(int numTokens) {
        refill();
        if (tokens >= numTokens) {
            tokens -= numTokens;
            return true;
        }
        return false;
    }
    
    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTime) / 1_000_000_000.0;
        double newTokens = elapsed * rate;
        tokens = Math.min(capacity, tokens + newTokens);
        lastRefillTime = now;
    }
}
```

### 3.8 SSL/TLS终结

七层网关作为SSL终结点，负责处理所有的TLS握手和加解密操作：

```java
/**
 * SSL证书管理器
 * 支持SNI（Server Name Indication）多域名证书
 */
public class SslCertificateManager {
    
    /** 域名到证书的映射 */
    private final ConcurrentHashMap<String, SslCertificate> certMap = new ConcurrentHashMap<>();
    
    /** 通配符证书列表 */
    private final List<SslCertificate> wildcardCerts = new CopyOnWriteArrayList<>();
    
    /**
     * 根据SNI域名选择证书
     * 在ssl_certificate_by_lua阶段调用
     */
    public SslCertificate selectCertificate(String sniHostname) {
        // 1. 精确匹配
        SslCertificate cert = certMap.get(sniHostname);
        if (cert != null && !cert.isExpired()) {
            return cert;
        }
        
        // 2. 通配符匹配
        for (SslCertificate wildcardCert : wildcardCerts) {
            if (wildcardCert.matchesDomain(sniHostname) && !wildcardCert.isExpired()) {
                return wildcardCert;
            }
        }
        
        // 3. 使用默认证书
        return certMap.get("_default_");
    }
    
    /**
     * 动态更新证书
     * 支持证书热更新，无需reload
     */
    public void updateCertificate(String domain, String certPem, String keyPem) {
        SslCertificate newCert = SslCertificate.parse(certPem, keyPem);
        
        // 验证证书有效性
        newCert.validate();
        
        // 检查证书即将过期告警
        if (newCert.daysUntilExpiry() < 30) {
            alertCertExpiring(domain, newCert.daysUntilExpiry());
        }
        
        if (domain.startsWith("*.")) {
            wildcardCerts.add(newCert);
        } else {
            certMap.put(domain, newCert);
        }
    }
}
```

### 3.9 监控打点与可观测性

网关层的监控打点是整个系统可观测性的基础，需要采集API粒度的性能数据：

```java
/**
 * 网关监控数据采集器
 * 在log_by_lua阶段采集每次请求的监控数据
 */
public class GatewayMetricsCollector {
    
    /** 指标存储（共享内存） */
    private final MetricsStore metricsStore;
    
    /**
     * 采集一次请求的监控指标
     */
    public void collect(RequestContext context, ResponseContext response) {
        String siteId = context.getSiteId();
        String location = context.getMatchedLocation();
        String upstreamAppKey = context.getUpstreamAppKey();
        
        // 1. 请求计数
        metricsStore.incrementCounter(buildMetricKey(siteId, location, "request_total"));
        
        // 2. 响应状态码分布
        int statusCode = response.getStatusCode();
        String statusGroup = statusCode / 100 + "xx"; // 2xx, 3xx, 4xx, 5xx
        metricsStore.incrementCounter(
            buildMetricKey(siteId, location, "response_" + statusGroup));
        
        // 3. 响应时间统计
        long latencyMs = response.getLatencyMs();
        metricsStore.recordHistogram(
            buildMetricKey(siteId, location, "latency"), latencyMs);
        
        // 4. 上游响应时间
        long upstreamLatencyMs = response.getUpstreamLatencyMs();
        metricsStore.recordHistogram(
            buildMetricKey(siteId, location, "upstream_latency"), upstreamLatencyMs);
        
        // 5. 请求/响应体大小
        metricsStore.recordHistogram(
            buildMetricKey(siteId, location, "request_size"), context.getRequestSize());
        metricsStore.recordHistogram(
            buildMetricKey(siteId, location, "response_size"), response.getResponseSize());
    }
    
    /**
     * 支持的告警指标
     */
    public enum AlertMetric {
        AVG_LATENCY("平均延迟"),
        TP90("TP90延迟"),
        TP95("TP95延迟"),
        TP99("TP99延迟"),
        TOTAL_REQUEST_COUNT("总请求数"),
        ERROR_5XX_COUNT("5XX错误数"),
        ERROR_4XX_COUNT("4XX错误数"),
        ERROR_RATE("错误率"),
        QPS("每秒请求数");
        
        private final String description;
        AlertMetric(String description) { this.description = description; }
    }
}
```

### 3.10 集群管理与发布

大规模七层网关集群的管理是一个复杂的运维工程问题：

```java
/**
 * 网关集群管理器
 * 负责集群的创建、扩缩容、配置发布等运维操作
 */
public class ClusterManager {
    
    /**
     * 灰度发布配置
     * 分批次将配置推送到集群节点
     */
    public ReleaseResult grayRelease(String clusterId, GatewayConfig config, 
                                      GrayReleasePolicy policy) {
        List<ClusterNode> allNodes = getClusterNodes(clusterId);
        List<List<ClusterNode>> batches = splitIntoBatches(allNodes, policy.getBatchCount());
        
        for (int i = 0; i < batches.size(); i++) {
            List<ClusterNode> batch = batches.get(i);
            
            // 推送配置到当前批次
            pushConfigToBatch(batch, config);
            
            // 等待观察期
            Thread.sleep(policy.getObservationPeriodMs());
            
            // 检查监控指标是否正常
            HealthCheckResult healthResult = checkBatchHealth(batch);
            if (!healthResult.isHealthy()) {
                // 健康检查失败，回滚已发布的节点
                rollbackBatch(batch, getPreviousConfig(clusterId));
                return ReleaseResult.fail(
                    "Batch " + i + " health check failed: " + healthResult.getReason());
            }
            
            // 可选：需要人工确认后继续下一批
            if (policy.isManualConfirmRequired() && i < batches.size() - 1) {
                waitForManualConfirm(clusterId, i);
            }
        }
        
        return ReleaseResult.success();
    }
    
    /**
     * 一键截流
     * 紧急情况下将所有流量切断，保护后端服务
     */
    public void emergencyCutoff(String siteId) {
        // 在网关层直接返回503，不再转发到后端
        DynamicConfigManager configManager = getConfigManager(siteId);
        GatewayConfig config = configManager.getCurrentConfig();
        config.setCutoff(true);
        config.setCutoffResponse(new CutoffResponse(503, "Service temporarily unavailable"));
        configManager.applyConfig(config, GrayPolicy.fullRelease());
    }
}
```

## 四、异常处理

### 4.1 后端超时处理

```java
/**
 * 超时配置与重试策略
 */
public class TimeoutAndRetryConfig {
    
    /** 连接超时 */
    private long connectTimeoutMs = 3_000;
    
    /** 读取超时 */
    private long readTimeoutMs = 30_000;
    
    /** 发送超时 */
    private long sendTimeoutMs = 10_000;
    
    /** 最大重试次数 */
    private int maxRetries = 2;
    
    /** 可重试的状态码 */
    private Set<Integer> retryableStatusCodes = new HashSet<>(Arrays.asList(502, 503, 504));
    
    /** 可重试的错误类型 */
    private Set<String> retryableErrors = new HashSet<>(Arrays.asList(
        "connect_timeout", "connect_refused", "reset"));
    
    /** 重试时是否切换到不同的后端节点 */
    private boolean retryNextUpstream = true;
}
```

### 4.2 控制面容灾

当控制面（配置中心、注册中心）不可用时，数据面必须能够独立运行：

```java
/**
 * 数据面容灾策略
 * 控制面不可用时的降级方案
 */
public class DataPlaneFallbackStrategy {
    
    /** 本地配置文件缓存 */
    private final String localConfigCachePath;
    
    /** 本地节点列表缓存 */
    private final String localNodeCachePath;
    
    /**
     * 控制面不可用时使用本地缓存
     */
    public GatewayConfig getConfigWithFallback() {
        try {
            return fetchFromControlPlane();
        } catch (Exception e) {
            // 控制面不可用，使用本地文件缓存
            return loadFromLocalCache(localConfigCachePath);
        }
    }
    
    /**
     * 定期将控制面配置持久化到本地
     */
    public void persistConfigToLocal(GatewayConfig config) {
        // 写入本地文件，供容灾时使用
        String json = JsonUtils.toJson(config);
        FileUtils.writeString(localConfigCachePath, json);
    }
}
```

## 五、性能优化

### 5.1 连接复用

```java
/**
 * 后端连接池管理
 * 复用TCP连接减少握手开销
 */
public class UpstreamConnectionPool {
    
    /** 单机最大空闲连接数 */
    private int maxIdleConnections = 64;
    
    /** 空闲连接超时时间 */
    private long idleTimeoutMs = 60_000;
    
    /** 是否开启HTTP长连接 */
    private boolean keepAliveEnabled = true;
    
    /** 单个连接最大请求数 */
    private int maxRequestsPerConnection = 1000;
}
```

### 5.2 HTTP/2支持

七层网关支持HTTP/2协议可以带来显著的带宽节约和性能提升：

- **多路复用**：单一连接上并行发送多个请求
- **头部压缩**：HPACK算法压缩HTTP头部
- **服务器推送**：主动推送资源到客户端
- 实测数据表明，接入HTTP/2协议后端到端页面加载性能提升30%，带宽成本节约30%

### 5.3 共享内存优化

在OpenResty中，Lua Worker之间通过共享内存（lua_shared_dict）进行数据共享。需要注意：

- 共享内存的读写锁竞争是性能瓶颈，需要控制读写频率
- SET映射关系等数据如果过多会撑爆共享内存，需要定期清理
- 统计模块的共享内存读写锁问题会影响整体性能

### 5.4 DPDK与硬件加速

在追求极致性能的场景下，可以探索：

- **DPDK**：绕过内核协议栈直接在用户态处理网络包，提升转发性能
- **硬件加速卡**：SSL加速卡分担TLS握手和加解密的CPU开销
- **压缩算法优化**：使用更高效的压缩算法减少带宽消耗

## 六、最佳实践

### 6.1 站点配置规范

1. **域名规范**：域名必须全小写，不能包含大写字母（大写字母会导致访问控制失效）
2. **一个Location只绑定一个SET策略**：避免策略冲突导致路由异常
3. **集群选择**：根据业务流量等级选择合适的集群，避免超大集群的爆炸半径
4. **证书管理**：证书过期前30天告警，避免证书过期导致服务不可用

### 6.2 变更安全

1. **高危操作审批**：站点发布、站点删除等高危操作必须经过审批流
2. **灰度发布**：动态策略变更支持灰度发布和回滚
3. **变更管控**：核心变更流程接入变更管控系统，高峰期预检、新人卡控
4. **一致性巡检**：定期巡检站点配置的一致性，主动感知线上异常

### 6.3 稳定性保障

1. **SET动态降级**：支持appkey、location维度的SET降级开关
2. **故障演练**：定期进行控制面组件故障演练（中间层服务、数据库从节点宕机等）
3. **强依赖解耦**：数据面应减少对外部组件的强依赖，核心流程闭环
4. **限流保护**：网关自身也需要限流保护，防止被上游打挂

### 6.4 容器化部署

七层网关集群应推进容器化部署，实现：

- 声明式配置管理
- 镜像发布（不可变基础设施）
- 自动化扩缩容
- 降低问题爆炸半径（通过容器化进行集群拆分）

## 七、全链路实战案例

前面几章从架构设计、核心链路到异常处理和性能优化，系统梳理了七层网关的技术要点。本章通过三个贯穿全链路的实战案例，将站点配置、路由匹配、SET/泳道分流、限流熔断、证书管理等能力串联起来，展示从请求进入网关到最终转发后端的完整处理过程。

### 7.1 案例一：HTTPS请求接入与路由全链路

#### 7.1.1 场景描述

某电商平台的七层网关需要处理来自客户端的HTTPS请求，从TLS握手、域名匹配、Location路由到最终转发后端服务，要求：

- 支持基于SNI的多证书选择，不同域名使用不同证书；
- 域名匹配后进行Location前缀/精确/正则路由匹配，选择合适的后端服务；
- 路由匹配失败或证书不存在时返回明确的错误码，并记录日志便于排查；
- 整个过程记录关键节点耗时，用于后续性能分析。

```
HTTPS请求接入全链路：

  客户端发起TLS连接
        │
        ▼
  ① SNI证书选择（按域名匹配证书）── 无匹配证书 ──▶ 使用默认证书或拒绝连接
        │ 匹配成功
        ▼
  ② TLS握手完成，解析HTTP请求
        │
        ▼
  ③ 域名匹配（server块匹配站点配置）── 无匹配站点 ──▶ 返回404
        │ 匹配成功
        ▼
  ④ Location路由匹配（前缀/精确/正则）── 无匹配规则 ──▶ 返回404
        │ 匹配成功
        ▼
  ⑤ 转发至后端服务（结合负载均衡选择实例）
        │
        ▼
  ⑥ 记录访问日志与耗时指标 ──▶ 返回响应
```

#### 7.1.2 完整实现代码

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTPS请求接入全链路处理器
 *
 * 负责处理从TLS握手到路由匹配、转发后端的完整流程，
 * 涵盖SNI证书选择、域名匹配、Location匹配、负载均衡等核心环节。
 */
public class HttpsRequestFullChainProcessor {

    private static final Logger logger = LoggerFactory.getLogger(
        HttpsRequestFullChainProcessor.class);

    /** 域名 -> 证书配置 */
    private final Map<String, SslConfig> certificateStore = new ConcurrentHashMap<>();

    /** 站点配置表：域名 -> GatewayConfig */
    private final Map<String, GatewayConfig> siteConfigStore;

    private final LoadBalancer loadBalancer;
    private final BackendInvoker backendInvoker;
    private final MetricsCollector metricsCollector;

    public HttpsRequestFullChainProcessor(
            Map<String, GatewayConfig> siteConfigStore,
            LoadBalancer loadBalancer,
            BackendInvoker backendInvoker,
            MetricsCollector metricsCollector) {
        this.siteConfigStore = siteConfigStore;
        this.loadBalancer = loadBalancer;
        this.backendInvoker = backendInvoker;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 全链路请求处理入口
     *
     * @param sniHost 客户端TLS握手携带的SNI域名
     * @param request 完成TLS握手后解析出的HTTP请求
     * @return 网关最终返回给客户端的响应
     */
    public GatewayResponse process(String sniHost, GatewayRequest request) {
        String traceId = generateTraceId();
        long startTime = System.currentTimeMillis();
        logger.info("[TraceId={}] 接收HTTPS请求: sniHost={}, path={}",
            traceId, sniHost, request.getPath());

        try {
            // ========== 第①步：SNI证书选择 ==========
            SslConfig sslConfig = selectCertificate(sniHost);
            if (sslConfig == null) {
                logger.warn("[TraceId={}] SNI证书选择失败, 使用默认证书: " 
                    + "sniHost={}", traceId, sniHost);
                sslConfig = getDefaultCertificate();
            }
            logger.info("[TraceId={}] 证书选择完成: certId={}",
                traceId, sslConfig.getCertId());

            // ========== 第②③步：域名匹配站点配置 ==========
            String host = request.getHeader("Host");
            GatewayConfig site = matchSite(host);
            if (site == null) {
                logger.warn("[TraceId={}] 域名无匹配站点: host={}",
                    traceId, host);
                metricsCollector.recordNoRouteMatch(host);
                return buildErrorResponse(404, "SITE_NOT_FOUND",
                    "域名未配置站点", traceId);
            }

            // ========== 第④步：Location路由匹配 ==========
            RouteRule matchedRule = matchLocation(
                site.getRouteRules(), request.getPath());
            if (matchedRule == null) {
                logger.warn("[TraceId={}] Location匹配失败: " 
                    + "host={}, path={}", traceId, host, request.getPath());
                return buildErrorResponse(404, "LOCATION_NOT_FOUND",
                    "请求路径未匹配任何路由规则", traceId);
            }
            logger.info("[TraceId={}] Location匹配成功: pattern={}, " 
                + "upstream={}", traceId, matchedRule.getLocationPattern(),
                matchedRule.getUpstreamAppKey());

            // ========== 第⑤步：负载均衡选择实例并转发 ==========
            ServiceInstance instance;
            try {
                instance = loadBalancer.select(
                    matchedRule.getUpstreamAppKey(),
                    site.getLoadBalanceConfig());
            } catch (NoAvailableInstanceException e) {
                logger.error("[TraceId={}] 无可用后端实例: upstream={}",
                    traceId, matchedRule.getUpstreamAppKey());
                return buildErrorResponse(502, "NO_AVAILABLE_INSTANCE",
                    "后端服务暂不可用", traceId);
            }

            BackendResponse backendResponse = backendInvoker.invoke(
                instance, request, matchedRule.getTimeout());

            // ========== 第⑥步：记录日志与指标 ==========
            long costMs = System.currentTimeMillis() - startTime;
            logger.info("[TraceId={}] 请求处理完成: status={}, costMs={}",
                traceId, backendResponse.getStatusCode(), costMs);
            metricsCollector.recordRequestLatency(
                site.getSiteId(), matchedRule.getLocationPattern(), costMs);

            return buildSuccessResponse(backendResponse, traceId);

        } catch (Exception e) {
            logger.error("[TraceId={}] 请求处理异常: {}",
                traceId, e.getMessage(), e);
            metricsCollector.recordException(sniHost, e.getClass().getSimpleName());
            return buildErrorResponse(500, "INTERNAL_ERROR",
                "网关内部错误", traceId);
        }
    }

    private SslConfig selectCertificate(String sniHost) {
        return Optional.ofNullable(certificateStore.get(sniHost))
            .orElseGet(() -> matchWildcardCertificate(sniHost));
    }

    private SslConfig matchWildcardCertificate(String sniHost) {
        // 支持*.example.com这类泛域名证书匹配
        String wildcard = "*." + sniHost.substring(sniHost.indexOf('.') + 1);
        return certificateStore.get(wildcard);
    }

    private SslConfig getDefaultCertificate() {
        return certificateStore.get("default");
    }

    private GatewayConfig matchSite(String host) {
        return siteConfigStore.get(host.toLowerCase());
    }

    private RouteRule matchLocation(List<RouteRule> rules, String path) {
        // 精确匹配优先，其次前缀匹配，最后正则匹配
        return rules.stream()
            .filter(r -> r.getMatchType() == MatchType.EXACT && r.getLocationPattern().equals(path))
            .findFirst()
            .orElseGet(() -> rules.stream()
                .filter(r -> r.getMatchType() == MatchType.PREFIX && path.startsWith(r.getLocationPattern()))
                .findFirst()
                .orElseGet(() -> rules.stream()
                    .filter(r -> r.getMatchType() == MatchType.REGEX && path.matches(r.getLocationPattern()))
                    .findFirst()
                    .orElse(null)));
    }

    private String generateTraceId() {
        return "TRACE-" + System.nanoTime();
    }

    private GatewayResponse buildErrorResponse(int code, String errorCode,
            String message, String traceId) {
        return GatewayResponse.builder()
            .statusCode(code).errorCode(errorCode)
            .message(message).traceId(traceId).build();
    }

    private GatewayResponse buildSuccessResponse(
            BackendResponse backendResponse, String traceId) {
        return GatewayResponse.builder()
            .statusCode(backendResponse.getStatusCode())
            .body(backendResponse.getBody())
            .traceId(traceId).build();
    }
}
```

### 7.2 案例二：SET/泳道灰度分流全链路

#### 7.2.1 场景描述

某支付平台在网关层实现精细化分流，将带有特定灰度标记的流量路由到灰度SET节点，其余流量走中心集群，要求：

- 优先识别请求Header中的灰度标记（如`X-Gray-Tag`）；
- 结合SET开关判断当前请求是否应该进入SET内节点；
- SET路由不支持fallback（保证隔离性），灰度路由支持fallback回中心链路；
- 分流决策结果需要记录，便于问题排查和链路可视化。

```
SET/泳道灰度分流全链路：

  请求到达网关
       │
       ▼
  ① 提取流量染色标记（X-Gray-Tag / X-Cell-Id）
       │
       ▼
  ② 判断目标服务是否开启SET开关
       │
       ├── 未开启 ──▶ 路由到中心集群节点
       │
       └── 已开启 ──▶ ③ 匹配当前请求所属SET
                          │
                          ├── 命中SET内节点 ──▶ 转发（不支持fallback）
                          │
                          └── 未命中 ──▶ ④ 判断是否为灰度LiteSet流量
                                             │
                                             ├── 是 ──▶ 转发至灰度节点，
                                             │          失败时fallback回中心
                                             │
                                             └── 否 ──▶ 转发到中心集群节点
```

#### 7.2.2 完整实现代码

```java
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SET/泳道灰度分流全链路处理器
 *
 * 负责根据流量染色标记完成SET路由与LiteSet灰度路由的分流决策，
 * SET路由强隔离不支持fallback，灰度路由支持失败回退到中心集群。
 */
public class GrayRoutingFullChainProcessor {

    private static final Logger logger = LoggerFactory.getLogger(
        GrayRoutingFullChainProcessor.class);

    private final Map<String, Boolean> setSwitchStore;
    private final ServiceDiscoveryClient serviceDiscoveryClient;
    private final MetricsCollector metricsCollector;

    public GrayRoutingFullChainProcessor(
            Map<String, Boolean> setSwitchStore,
            ServiceDiscoveryClient serviceDiscoveryClient,
            MetricsCollector metricsCollector) {
        this.setSwitchStore = setSwitchStore;
        this.serviceDiscoveryClient = serviceDiscoveryClient;
        this.metricsCollector = metricsCollector;
    }

    /**
     * 分流决策入口
     *
     * @param upstreamAppKey 目标后端服务标识
     * @param context 流量上下文，包含灰度标记、cellId等
     * @return 最终选定的服务实例
     */
    public ServiceInstance route(String upstreamAppKey, TrafficContext context) {
        String traceId = context.getTraceId();

        // ========== 第①②步：判断SET开关 ==========
        boolean setEnabled = setSwitchStore.getOrDefault(upstreamAppKey, false);
        List<ServiceInstance> allInstances =
            serviceDiscoveryClient.getInstances(upstreamAppKey);

        if (!setEnabled) {
            logger.info("[TraceId={}] 目标服务未开启SET, 路由到中心集群: " 
                + "upstream={}", traceId, upstreamAppKey);
            return selectFromCenter(allInstances, traceId);
        }

        // ========== 第③步：SET路由匹配（不支持fallback） ==========
        String cellId = context.getCellId();
        if (cellId != null) {
            List<ServiceInstance> setInstances = allInstances.stream()
                .filter(inst -> cellId.equals(inst.getCell()))
                .collect(Collectors.toList());
            if (!setInstances.isEmpty()) {
                logger.info("[TraceId={}] 命中SET内节点: cellId={}, " 
                    + "instanceCount={}", traceId, cellId, setInstances.size());
                metricsCollector.recordRouteDecision(
                    upstreamAppKey, "SET_HIT", cellId);
                return loadBalanceSelect(setInstances);
            }
            // SET路由不支持fallback，未命中直接抛异常，交由上层决定失败策略
            logger.error("[TraceId={}] SET路由未命中且不支持fallback: " 
                + "cellId={}, upstream={}", traceId, cellId, upstreamAppKey);
            metricsCollector.recordRouteDecision(
                upstreamAppKey, "SET_MISS_NO_FALLBACK", cellId);
            throw new NoAvailableInstanceException(
                "SET内无可用节点且不支持fallback: cellId=" + cellId);
        }

        // ========== 第④步：LiteSet灰度路由（支持fallback） ==========
        String grayTag = context.getColorTags().get("X-Gray-Tag");
        if (grayTag != null) {
            List<ServiceInstance> grayInstances = allInstances.stream()
                .filter(inst -> grayTag.equals(inst.getLiteSetTag()))
                .collect(Collectors.toList());
            if (!grayInstances.isEmpty()) {
                logger.info("[TraceId={}] 命中灰度LiteSet节点: grayTag={}",
                    traceId, grayTag);
                metricsCollector.recordRouteDecision(
                    upstreamAppKey, "LITESET_HIT", grayTag);
                return loadBalanceSelect(grayInstances);
            }
            logger.warn("[TraceId={}] 灰度LiteSet未命中, fallback回中心集群: " 
                + "grayTag={}", traceId, grayTag);
            metricsCollector.recordRouteDecision(
                upstreamAppKey, "LITESET_FALLBACK", grayTag);
        }

        // 默认路由到中心集群
        return selectFromCenter(allInstances, traceId);
    }

    private ServiceInstance selectFromCenter(
            List<ServiceInstance> allInstances, String traceId) {
        List<ServiceInstance> centerInstances = allInstances.stream()
            .filter(inst -> inst.getCell() == null || "center".equals(inst.getCell()))
            .collect(Collectors.toList());
        if (centerInstances.isEmpty()) {
            logger.error("[TraceId={}] 中心集群无可用节点", traceId);
            throw new NoAvailableInstanceException("中心集群无可用节点");
        }
        return loadBalanceSelect(centerInstances);
    }

    private ServiceInstance loadBalanceSelect(List<ServiceInstance> instances) {
        // 简化为加权随机选择，生产环境可替换为加权轮询/最少活跃调用等策略
        int totalWeight = instances.stream()
            .mapToInt(ServiceInstance::getWeight).sum();
        int rand = (int) (Math.random() * totalWeight);
        int cursor = 0;
        for (ServiceInstance instance : instances) {
            cursor += instance.getWeight();
            if (rand < cursor) {
                return instance;
            }
        }
        return instances.get(0);
    }
}
```

### 7.3 案例三：网关限流熔断与优雅降级全链路

#### 7.3.1 场景描述

某内容平台的网关在大促期间需要对突发流量进行自我保护，避免被上游流量打垮，同时在后端服务异常时提供优雅降级，要求：

- 网关自身设置全局限流阈值，超出阈值直接拒绝并返回友好提示；
- 针对单个后端appkey设置独立的熔断器，连续失败达到阈值后自动熔断；
- 熔断期间对该服务的请求走降级逻辑（返回缓存数据或默认兜底响应）；
- 所有限流、熔断、降级事件都要有完整日志和指标记录，便于告警和事后分析。

```
网关限流熔断降级全链路：

  请求到达网关
       │
       ▼
  ① 网关自身全局限流判断 ── 超限 ──▶ 直接拒绝(503) + 记录限流指标
       │ 通过
       ▼
  ② 查询目标后端appkey的熔断器状态
       │
       ├── OPEN（熔断中）──▶ ③ 执行降级逻辑（缓存/兜底响应）
       │
       └── CLOSED/HALF_OPEN ──▶ ④ 正常转发请求到后端
                                      │
                          ┌───────────┴───────────┐
                          ▼                       ▼
                    调用成功                  调用失败
                          │                       │
                          ▼                       ▼
                  熔断器记录成功            熔断器记录失败
                  （HALF_OPEN时可能关闭）    （达到阈值时转为OPEN）
```

#### 7.3.2 完整实现代码

```java
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 网关限流熔断降级全链路处理器
 *
 * 先进行网关自身的全局限流保护，再针对每个后端appkey维护独立熔断器，
 * 熔断期间自动切换到降级逻辑，避免故障扩散。
 */
public class GatewayCircuitBreakerFullChainProcessor {

    private static final Logger logger = LoggerFactory.getLogger(
        GatewayCircuitBreakerFullChainProcessor.class);

    /** 网关自身QPS限制 */
    private final GlobalRateLimiter globalRateLimiter;

    /** appkey -> 熔断器 */
    private final Map<String, CircuitBreakerState> circuitBreakers =
        new ConcurrentHashMap<>();

    private final BackendInvoker backendInvoker;
    private final FallbackCacheStore fallbackCacheStore;
    private final MetricsCollector metricsCollector;

    /** 熔断触发的连续失败次数阈值 */
    private static final int FAILURE_THRESHOLD = 20;

    /** 熔断冷却时间（毫秒），冷却后进入HALF_OPEN试探 */
    private static final long OPEN_COOLDOWN_MS = 30_000L;

    public GatewayCircuitBreakerFullChainProcessor(
            GlobalRateLimiter globalRateLimiter,
            BackendInvoker backendInvoker,
            FallbackCacheStore fallbackCacheStore,
            MetricsCollector metricsCollector) {
        this.globalRateLimiter = globalRateLimiter;
        this.backendInvoker = backendInvoker;
        this.fallbackCacheStore = fallbackCacheStore;
        this.metricsCollector = metricsCollector;
    }

    public GatewayResponse process(String upstreamAppKey, GatewayRequest request) {
        String traceId = request.getTraceId();

        // ========== 第①步：网关自身全局限流 ==========
        if (!globalRateLimiter.tryAcquire()) {
            logger.warn("[TraceId={}] 网关触发全局限流, 直接拒绝",traceId);
            metricsCollector.recordGatewaySelfProtection("RATE_LIMIT");
            return buildErrorResponse(503, "GATEWAY_OVERLOAD",
                "网关繁忙，请稍后重试", traceId);
        }

        // ========== 第②步：查询熔断器状态 ==========
        CircuitBreakerState breaker = circuitBreakers.computeIfAbsent(
            upstreamAppKey, k -> new CircuitBreakerState());

        if (breaker.isOpen()) {
            if (breaker.shouldAttemptReset(OPEN_COOLDOWN_MS)) {
                logger.info("[TraceId={}] 熔断器冷却完成, 转为HALF_OPEN试探: " 
                    + "upstream={}", traceId, upstreamAppKey);
                breaker.transitionToHalfOpen();
            } else {
                // ========== 第③步：熔断中，执行降级 ==========
                logger.warn("[TraceId={}] 熔断器OPEN, 执行降级: upstream={}",
                    traceId, upstreamAppKey);
                metricsCollector.recordCircuitBreakerFallback(upstreamAppKey);
                return executeFallback(upstreamAppKey, request, traceId);
            }
        }

        // ========== 第④步：正常转发请求 ==========
        try {
            BackendResponse backendResponse = backendInvoker.invoke(
                upstreamAppKey, request);
            breaker.recordSuccess();
            if (breaker.getState() == CircuitState.HALF_OPEN
                    && breaker.getConsecutiveSuccess() >= 5) {
                logger.info("[TraceId={}] HALF_OPEN试探成功次数达标, " 
                    + "熔断器关闭: upstream={}", traceId, upstreamAppKey);
                breaker.close();
            }
            // 成功响应同步更新降级缓存，供未来熔断时兜底使用
            fallbackCacheStore.put(upstreamAppKey, request.getPath(),
                backendResponse.getBody());
            return buildSuccessResponse(backendResponse, traceId);

        } catch (Exception e) {
            int failures = breaker.recordFailure();
            logger.error("[TraceId={}] 后端调用失败: upstream={}, " 
                + "consecutiveFailures={}, error={}",
                traceId, upstreamAppKey, failures, e.getMessage());

            if (failures >= FAILURE_THRESHOLD) {
                breaker.open();
                logger.error("[TraceId={}] 连续失败达到阈值, 熔断器打开: " 
                    + "upstream={}", traceId, upstreamAppKey);
                metricsCollector.recordCircuitBreakerOpen(upstreamAppKey);
            }
            return executeFallback(upstreamAppKey, request, traceId);
        }
    }

    /**
     * 降级逻辑：优先返回缓存的最近成功响应，无缓存则返回默认兜底数据
     */
    private GatewayResponse executeFallback(
            String upstreamAppKey, GatewayRequest request, String traceId) {
        String cachedBody = fallbackCacheStore.get(
            upstreamAppKey, request.getPath());
        if (cachedBody != null) {
            logger.info("[TraceId={}] 降级命中缓存数据: upstream={}",
                traceId, upstreamAppKey);
            return GatewayResponse.builder()
                .statusCode(200).body(cachedBody)
                .degraded(true).traceId(traceId).build();
        }
        logger.warn("[TraceId={}] 降级无缓存可用, 返回默认兜底响应: " 
            + "upstream={}", traceId, upstreamAppKey);
        return GatewayResponse.builder()
            .statusCode(200).body("{\"code\":0,\"data\":null,\"degraded\":true}")
            .degraded(true).traceId(traceId).build();
    }

    private GatewayResponse buildErrorResponse(int code, String errorCode,
            String message, String traceId) {
        return GatewayResponse.builder()
            .statusCode(code).errorCode(errorCode)
            .message(message).traceId(traceId).build();
    }

    private GatewayResponse buildSuccessResponse(
            BackendResponse backendResponse, String traceId) {
        return GatewayResponse.builder()
            .statusCode(backendResponse.getStatusCode())
            .body(backendResponse.getBody())
            .traceId(traceId).build();
    }

    /**
     * 熔断器状态机：CLOSED -> OPEN -> HALF_OPEN -> CLOSED/OPEN
     */
    private static class CircuitBreakerState {
        private volatile CircuitState state = CircuitState.CLOSED;
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicInteger consecutiveSuccess = new AtomicInteger(0);
        private final AtomicLong openTimestamp = new AtomicLong(0);

        boolean isOpen() {
            return state == CircuitState.OPEN;
        }

        CircuitState getState() {
            return state;
        }

        int getConsecutiveSuccess() {
            return consecutiveSuccess.get();
        }

        int recordFailure() {
            consecutiveSuccess.set(0);
            return consecutiveFailures.incrementAndGet();
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            consecutiveSuccess.incrementAndGet();
        }

        void open() {
            state = CircuitState.OPEN;
            openTimestamp.set(System.currentTimeMillis());
        }

        void close() {
            state = CircuitState.CLOSED;
            consecutiveFailures.set(0);
            consecutiveSuccess.set(0);
        }

        void transitionToHalfOpen() {
            state = CircuitState.HALF_OPEN;
            consecutiveSuccess.set(0);
        }

        boolean shouldAttemptReset(long cooldownMs) {
            return System.currentTimeMillis() - openTimestamp.get() >= cooldownMs;
        }
    }

    private enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
}
```

### 7.4 三个案例的协同关系

三个案例覆盖了七层网关在生产环境中最核心的三条能力线，实际运行时相互配合、共同构成完整的流量处理闭环：

| 协同维度 | 案例一（接入路由） | 案例二（灰度分流） | 案例三（限流熔断降级） |
|---------|-------------------|-------------------|----------------------|
| 触发时机 | 每个请求必经的入口链路 | 路由阶段按流量染色决策 | 转发前后的自我保护机制 |
| 核心目标 | 正确路由到目标后端 | 隔离与灰度验证 | 稳定性兜底 |
| 失败处理 | 返回404/502明确错误码 | SET不支持fallback，灰度支持fallback | 触发降级返回缓存或兜底数据 |
| 日志追踪 | TraceId贯穿域名/路由匹配 | 记录分流决策依据（cellId/grayTag） | 记录熔断状态变更与降级事件 |
| 指标监控 | 路由耗时、404率 | SET命中率、灰度fallback率 | 限流拒绝数、熔断次数、降级次数 |

在实际处理流程中，一次请求通常先经过案例一完成路由匹配，再由案例二决定具体路由到中心集群、SET节点还是灰度节点，最终在案例三的限流熔断保护下完成对后端的调用。三者共同保障了七层网关在高并发场景下的正确性、灵活性与稳定性。</new_string>
</invoke>

