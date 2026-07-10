# SET化治理

## 一、问题背景

### 1.1 业务规模增长带来的挑战

随着大型互联网企业核心业务体量的持续增长，传统的单一大集群分布式架构面临严峻挑战：

**扩展性问题**
- 数据库主库连接数有限，无法支持服务的无限水平扩展
- 单个数据库集群的slave数量存在上限（如MySQL集群不能超过20个slave），单集群存在容量上限
- 单机房（AZ）整体资源存在上限，AZ本身成为系统瓶颈

**容灾问题**
- 核心服务或数据库故障会影响全网所有用户，导致整个业务不可用
- 无法快速切换和恢复，故障影响面巨大
- 缺乏同城和异地容灾能力，AZ级或Region级故障可能导致业务长时间中断

**性能问题**
- 跨AZ/Region调用带来网络时延，影响用户体验
- 大集群内部调用链路复杂，难以保证请求在同城范围内闭环

### 1.2 SET化架构的提出

SET化架构是将业务的核心系统按某种数据特征维度进行垂直划分，每个SET（单元）是一个能完成核心业务功能的独立单元。SET化架构的本质是AKF立方体中Z轴拆分的高级形式，将全站服务和数据统一拆分，达成全站的水平扩展能力。

SET化架构具有以下核心特征：
- SET是逻辑上独立的服务链路，业务请求在SET内部流转，实现流量的切分与隔离
- SET化架构支持SET间的流量调度，以及SET的灵活扩展
- 支持全链路SET化，也支持业务层面定制SET化覆盖的具体服务环节
- 每个SET包含业务应用以及消息队列、数据库等基础组件，流量进入SET内可完成所有业务逻辑

### 1.3 SET化的核心目标

- **容灾能力**：支持同城和异地容灾，AZ级故障RPO<1min、RTO<1min，Region级故障快速切换
- **扩展能力**：提供快速建站能力，建站时长从3个月缩短至1周，支持AZ+级水平扩展
- **运维效率**：实现运维职责解耦，物理层面运维工作收敛至运维团队，业务仅需关注容量规划
- **资源成本**：数据副本数量2~3，应用冗余倍数不超过2，保持合理的资源成本

### 1.4 行业演进趋势

系统部署架构随规模演进：单AZ → 同城多AZ → 异地多AZ。容灾能力从单活演进到同城主备、同城双活、同城多活、异地多活。扩展能力沿AKF Scale Cube的XYZ轴演进：X轴（增加副本）→ Y轴（功能拆分）→ Z轴（数据分割）。

SET化是Z轴拆分的高级形式，将数据和业务逻辑划分为相似的"切片"，提高系统对外服务能力的性价比，增加切片内链路流转的效率。

## 二、整体架构设计

### 2.1 SET化架构总体设计

SET化架构涉及应用、数据和流量三大要素，整体架构如下：

```
┌─────────────────────────────────────────────────────────────────────┐
│                          流量入口层                                   │
│   DNS分流 → 七层网关(反向代理) → 路由计算 → SET流量分发              │
├─────────────────────────────────────────────────────────────────────┤
│                          SET单元层                                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │   SET-A     │  │   SET-B     │  │  中心SET     │                 │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │                 │
│  │ │ 业务服务 │ │  │ │ 业务服务 │ │  │ │ 全量服务 │ │                 │
│  │ │ (分片1) │ │  │ │ (分片2) │ │  │ │ (未分片) │ │                 │
│  │ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │                 │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │                 │
│  │ │  数据库  │ │  │ │  数据库  │ │  │ │  数据库  │ │                 │
│  │ │ (分片1) │ │  │ │ (分片2) │ │  │ │ (全量)  │ │                 │
│  │ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │                 │
│  │ ┌─────────┐ │  │ ┌─────────┐ │  │             │                 │
│  │ │ 消息队列 │ │  │ │ 消息队列 │ │  │             │                 │
│  │ └─────────┘ │  │ └─────────┘ │  │             │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
├─────────────────────────────────────────────────────────────────────┤
│                          数据同步层                                   │
│   双向数据同步(DRC) → 数据校验 → 冲突检测与解决                       │
├─────────────────────────────────────────────────────────────────────┤
│                          管控平台层                                   │
│   路由规则管理 → 容灾切换控制台 → 监控大盘 → 建站自动化               │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 核心概念定义

| 概念 | 说明 |
|------|------|
| SET | 软件系统架构中的逻辑单元，包含业务应用和基础组件，承担一部分流量 |
| 中心SET | 包含所有服务的SET，未开启SET化的服务只部署在中心SET |
| SET服务 | 打开了SET开关的服务，需部署在所有SET中 |
| 非SET服务 | 未打开SET开关的服务，只部署在中心SET |
| 分片(Shard) | 按数据特征维度划分的数据子集，每个SET保存一部分分片数据 |
| 二级映射 | 参数→分片ID→SET的路由计算方式 |
| LiteSet | 基于SET底层能力构建的生产环境链路粒度流量隔离方案，支持fallback |
| 容灾切换 | 故障时将流量和数据切换到备用SET的过程 |
| RPO | Recovery Point Objective，故障恢复点目标（数据丢失量） |
| RTO | Recovery Time Objective，故障恢复时间目标 |

### 2.3 SET类型划分

根据业务特点和服务职责，SET化架构中将服务分为不同类型：

**可分片服务（R服务）**
- 业务数据可按某种维度拆分的服务
- 需要部署在所有SET中
- 每个SET只保存该服务对应分片的数据
- 流量在SET内闭环调用

**不可分片服务（G服务/中心服务）**
- 业务数据无法拆分或拆分成本过高的服务
- 只部署在中心SET
- 所有SET的流量都调用中心SET的对应服务
- 需要尽量减少此类服务的调用量

**只读副本服务（C服务）**
- 中心服务的只读副本，部署在每个有SET的机房
- 为SET提供本地查询服务，减少跨机房读请求
- 数据由中心SET异步同步

### 2.4 分片维度选择

不同业务线根据自身特点选择不同的分片维度：

| 分片维度 | 适用场景 | 优势 | 劣势 |
|----------|----------|------|------|
| 用户ID取模 | 电商交易类业务 | 分片均匀，扩展性好 | 无法实现地域就近访问 |
| LBS（地理位置） | 外卖、配送等强位置业务 | 用户就近访问，时延低 | 分片间量不平均且不稳定 |
| 付款方ID | 支付类业务 | 交易闭环在单个SET | 业务改造复杂度高 |
| 租户ID | SaaS类业务 | 租户间天然隔离 | 大租户可能成为热点 |

### 2.5 路由架构对比

| 对比项 | 中心化路由 | 去中心化路由 |
|--------|-----------|-------------|
| 路由计算位置 | SET入口网关集中计算 | 每个微服务节点本地计算 |
| 路由信息存储 | 中心路由服务维护映射数据 | 各节点通过配置中心同步规则 |
| 业务侵入性 | 低，业务无需感知路由 | 高，接口需包含分片参数 |
| 流量纠错 | 缺乏纠错能力 | 各层组件可纠错 |
| 典型代表 | 外卖到家平台 | 蚂蚁、淘宝、京东 |
| 适用场景 | LBS分片，映射数据量大 | 取模分片，规则简单 |

## 三、核心链路设计

### 3.1 流量路由系统

#### 3.1.1 路由计算模型

SET化流量路由采用二级映射模型：参数 → 分片ID → SET。第一层映射（参数→分片ID）由业务实现，相对稳定；第二层映射（分片ID→SET）由路由规则管理系统统一管理，可灵活调整。

```java
/**
 * SET路由计算核心
 * 二级映射：参数 → 分片ID → SET
 */
public class SetRouter {

    private final ShardCalculator shardCalculator;
    private final SetMappingManager setMappingManager;

    /**
     * 路由计算：根据请求参数计算目标SET
     * @param request 包含分片参数的请求
     * @return 目标SET信息
     */
    public SetInfo route(RoutingRequest request) {
        // 第一级映射：参数 → 分片ID
        String shardId = shardCalculator.calculate(request.getShardKey());
        if (shardId == null) {
            // 无法计算分片ID，路由到中心SET
            return SetInfo.CENTER;
        }

        // 第二级映射：分片ID → SET
        SetInfo setInfo = setMappingManager.getSetByShardId(shardId);
        if (setInfo == null) {
            log.warn("No SET mapping for shard: {}, falling back to center", shardId);
            return SetInfo.CENTER;
        }

        return setInfo;
    }
}

/**
 * 分片计算器
 * 根据不同的分片策略计算分片ID
 */
public abstract class ShardCalculator {
    public abstract String calculate(String shardKey);
}

/**
 * LBS分片计算器（基于地理位置）
 * 用户/门店按城市维度划分
 */
public class LbsShardCalculator extends ShardCalculator {

    private final RoutingServiceClient routingServiceClient;

    @Override
    public String calculate(String shardKey) {
        // shardKey可能是userId或poiId
        // 通过路由服务查询对应的regionId（城市ID）
        String regionId = routingServiceClient.getRegionId(shardKey);
        return regionId;
    }
}

/**
 * 取模分片计算器
 * 用户ID取模作为分片ID
 */
public class ModShardCalculator extends ShardCalculator {

    private final int shardCount; // 例如100个分片

    @Override
    public String calculate(String shardKey) {
        // 将用户ID取模得到分片号
        int shardNum = Math.abs(shardKey.hashCode()) % shardCount;
        return String.valueOf(shardNum);
    }
}
```

#### 3.1.2 HTTP流量分发

```java
/**
 * HTTP流量SET分发
 * 在七层网关层实现，根据请求参数计算目标SET并转发
 */
public class HttpSetDispatcher implements GatewayFilter {

    private final SetRouter setRouter;
    private final RoutingServiceClient routingServiceClient;

    /**
     * 处理HTTP请求的SET分发
     */
    @Override
    public void filter(HttpServletRequest request, HttpServletResponse response,
                       FilterChain chain) {
        // 1. 从请求参数中提取regionId（客户端登录时缓存并携带）
        String regionId = request.getParameter("regionId");

        SetInfo targetSet;
        if (regionId != null) {
            // 客户端已携带路由信息，直接映射
            targetSet = setMappingManager.getSetByRegionId(regionId);
        } else {
            // 客户端未携带路由信息，查询路由服务
            String userId = extractUserId(request);
            targetSet = setRouter.route(RoutingRequest.of(userId));
        }

        if (targetSet == null) {
            targetSet = SetInfo.CENTER;
        }

        // 2. 设置SET转发头
        request.setHeader("X-Target-Set", targetSet.getName());

        // 3. 转发到目标SET
        String upstream = targetSet.getUpstreamAddress();
        proxyPass(request, response, upstream);
    }
}
```

#### 3.1.3 RPC流量路由

```java
/**
 * RPC流量SET路由
 * 在RPC框架层实现，基于服务实例的SET标签进行路由
 */
public class SetRoutingPlugin implements RpcRoutingPlugin {

    private final SetRouter setRouter;

    /**
     * RPC调用时的SET路由
     * 根据下游服务是否开启SET开关决定路由策略
     */
    @Override
    public List<ServiceInstance> route(RpcInvocation invocation,
                                        List<ServiceInstance> candidates) {
        // 获取当前请求的SET上下文
        SetContext setContext = SetContextHolder.get();

        if (setContext == null || setContext.getSetName() == null) {
            // 无SET上下文，调用中心SET
            return filterBySetName(candidates, SetInfo.CENTER.getName());
        }

        String targetSet = setContext.getSetName();
        String appkey = invocation.getAppkey();

        // 检查下游服务是否开启了SET开关
        if (isSetEnabled(appkey)) {
            // SET服务：调用同SET的实例
            List<ServiceInstance> setInstances = filterBySetName(candidates, targetSet);
            if (!setInstances.isEmpty()) {
                return setInstances;
            }
            // 同SET无可用实例，调用失败（SET路由不支持fallback）
            log.error("No available instances for SET: {}, appkey: {}", targetSet, appkey);
            throw new NoInstanceAvailableException("SET routing failed, no fallback");
        } else {
            // 非SET服务：调用中心SET的实例
            return filterBySetName(candidates, SetInfo.CENTER.getName());
        }
    }

    /**
     * 判断服务是否开启了SET开关
     */
    private boolean isSetEnabled(String appkey) {
        ServiceConfig config = configManager.getServiceConfig(appkey);
        return config != null && config.isSetEnabled();
    }

    private List<ServiceInstance> filterBySetName(List<ServiceInstance> instances,
                                                    String setName) {
        return instances.stream()
                .filter(inst -> setName.equals(inst.getMetadata().get("SET")))
                .collect(Collectors.toList());
    }
}
```

### 3.2 路由信息全链路透传

#### 3.2.1 问题描述

在中心化路由架构中，路由服务是流量分发的核心依赖。一次请求链路中路由服务被重复调用多次（极端情况一次请求调用100+次路由服务），存在两个关键问题：
1. **路由服务容量问题**：路由服务调用量增长速率远大于业务量增长速率
2. **单点强依赖问题**：路由服务宕机会导致整个SET化架构瘫痪

#### 3.2.2 路由信息复用方案

```java
/**
 * 路由信息全链路透传
 * 利用分布式链路追踪Context实现路由信息的链路级复用
 */
public class RoutingContextPropagator {

    private static final String ROUTING_INFO_KEY = "set.routing.info";

    /**
     * 在入口服务中设置路由信息到TraceContext
     */
    public void setRoutingInfo(String userId, String setName, String regionId) {
        RoutingInfo info = RoutingInfo.builder()
                .userId(userId)
                .setName(setName)
                .regionId(regionId)
                .timestamp(System.currentTimeMillis())
                .build();

        // 放入链路追踪上下文，全链路透传
        TraceContext.put(ROUTING_INFO_KEY, JsonUtils.toJson(info));
    }

    /**
     * 在下游服务中复用路由信息
     * 先从TraceContext获取，获取不到再查询路由服务
     */
    public RoutingInfo getRoutingInfo(String shardKey) {
        // 1. 优先从TraceContext复用
        String cached = TraceContext.get(ROUTING_INFO_KEY);
        if (cached != null) {
            RoutingInfo info = JsonUtils.fromJson(cached, RoutingInfo.class);
            if (isValid(info)) {
                return info;
            }
        }

        // 2. 降级查询路由服务
        return queryRoutingService(shardKey);
    }

    /**
     * 路由插件封装（业务无感知）
     */
    public SetInfo resolveSet(String shardKey) {
        RoutingInfo info = getRoutingInfo(shardKey);
        if (info != null && info.getSetName() != null) {
            // 复用链路中的路由信息，设置SET上下文
            SetContextHolder.set(info.getSetName());
            return SetInfo.of(info.getSetName());
        }
        return SetInfo.CENTER;
    }

    private boolean isValid(RoutingInfo info) {
        return info != null
                && info.getSetName() != null
                && System.currentTimeMillis() - info.getTimestamp() < 60000; // 60秒有效期
    }
}
```

#### 3.2.3 入口服务路由信息解析

```java
/**
 * 入口服务Filter
 * 解析客户端透传的路由信息，放入TraceContext供全链路复用
 */
public class RoutingEntryFilter implements Filter {

    private final RoutingContextPropagator propagator;

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response,
                         FilterChain chain) {
        // 解析客户端携带的regionId（客户端登录时缓存）
        String regionId = request.getParameter("regionId");
        String setName = request.getHeader("X-Set-Name");

        if (regionId != null || setName != null) {
            // 将路由信息作为容灾信息放入TraceContext
            propagator.setRoutingInfo(
                    extractUserId(request),
                    setName != null ? setName : resolveSetByRegionId(regionId),
                    regionId
            );
        }

        chain.doFilter(request, response);
    }
}
```

### 3.3 路由容灾机制

#### 3.3.1 路由服务容灾总体方案

当路由服务不可用时，需要通过多层容灾机制保障流量分发正常：

```
路由信息获取优先级：
1. TraceContext复用（全链路透传）
2. 客户端缓存路由信息（C端）
3. 本地Bitmap缓存（B端）
4. 路由服务查询（正常模式）
```

#### 3.3.2 C端容灾：客户端缓存

```java
/**
 * C端路由容灾
 * 客户端登录时获取并缓存regionId，后续请求携带该参数
 * 路由服务宕机时，入口服务解析客户端携带的路由信息实现容灾
 */
public class ClientRouteFallback {

    /**
     * 客户端登录时获取路由信息
     */
    public LoginResponse login(LoginRequest request) {
        // 1. 正常登录逻辑
        LoginResponse response = authService.login(request);

        // 2. 查询路由服务获取regionId
        try {
            String regionId = routingService.getRegionIdByUserId(request.getUserId());
            response.setRegionId(regionId);
        } catch (Exception e) {
            log.warn("Routing service unavailable during login", e);
            // 路由服务不可用，客户端使用上次缓存的regionId
        }

        return response;
    }
}
```

#### 3.3.3 B端容灾：RoaringBitmap本地缓存

```java
/**
 * B端路由容灾
 * 在业务服务本地构建RoaringBitmap缓存，路由服务宕机时使用本地缓存
 */
public class BitmapRouteFallback {

    // Map<SetName, RoaringBitmap>：每个SET对应一个门店ID位图
    private volatile Map<String, RoaringBitmap> poiSetBitmapCache;
    private final ConfigWatcher configWatcher;
    private final ObjectStorageClient objectStorage;

    /**
     * 初始化本地缓存
     * 监听配置中心的缓存版本号变化，从对象存储拉取最新缓存数据
     */
    public void init() {
        configWatcher.watch("route.bitmap.version", (newVersion) -> {
            log.info("Route bitmap version changed: {}", newVersion);
            reloadCache(newVersion);
        });
    }

    /**
     * 重新加载缓存
     */
    private void reloadCache(String version) {
        try {
            // 1. 从对象存储下载序列化的Bitmap数据
            byte[] data = objectStorage.download("route-cache/bitmap_" + version + ".dat");

            // 2. 反序列化为Map<SetName, RoaringBitmap>
            Map<String, RoaringBitmap> newCache = deserializeBitmapCache(data);

            // 3. 原子替换缓存
            this.poiSetBitmapCache = newCache;
            log.info("Route bitmap cache reloaded, sets={}", newCache.size());
        } catch (Exception e) {
            log.error("Failed to reload route bitmap cache", e);
        }
    }

    /**
     * 从本地缓存查询门店对应的SET
     * 遍历所有SET的Bitmap，判断poiId是否存在于某个SET的Bitmap中
     */
    public String getSetByPoiId(Long poiId) {
        Map<String, RoaringBitmap> cache = this.poiSetBitmapCache;
        if (cache == null || cache.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, RoaringBitmap> entry : cache.entrySet()) {
            if (entry.getValue().contains(poiId.intValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 缓存数据结构构建
     * 每日筛选前一天的日活门店，构建poiId-SetName映射
     */
    public void buildAndUploadCache() {
        // 1. 查询日活门店列表
        List<PoiRouteMapping> mappings = routingService.getActivePoiMappings();

        // 2. 按SET分组构建Bitmap
        Map<String, RoaringBitmap> bitmapMap = new HashMap<>();
        for (PoiRouteMapping mapping : mappings) {
            String setName = mapping.getSetName();
            bitmapMap.computeIfAbsent(setName, k -> new RoaringBitmap())
                    .add(mapping.getPoiId().intValue());
        }

        // 3. 序列化并上传到对象存储
        byte[] serialized = serializeBitmapCache(bitmapMap);
        String version = String.valueOf(System.currentTimeMillis());
        objectStorage.upload("route-cache/bitmap_" + version + ".dat", serialized);

        // 4. 更新配置中心版本号
        configManager.set("route.bitmap.version", version);

        log.info("Route bitmap cache built: sets={}, totalPois={}",
                bitmapMap.size(), mappings.size());
    }

    /**
     * 缓存正确性校验
     * 常态化校验本地缓存与路由服务数据的一致性
     */
    public void verifyCacheConsistency() {
        Map<String, RoaringBitmap> cache = this.poiSetBitmapCache;
        int mismatchCount = 0;
        int totalChecked = 0;

        // 随机抽样校验
        List<Long> samplePoiIds = routingService.getRandomSamplePoiIds(1000);
        for (Long poiId : samplePoiIds) {
            String expectedSet = routingService.getSetByPoiId(poiId);
            String actualSet = getSetByPoiId(poiId);

            if (!Objects.equals(expectedSet, actualSet)) {
                mismatchCount++;
                log.warn("Cache mismatch: poiId={}, expected={}, actual={}",
                        poiId, expectedSet, actualSet);
            }
            totalChecked++;
        }

        double mismatchRate = (double) mismatchCount / totalChecked;
        if (mismatchRate > 0.01) {
            alertManager.sendAlert("路由缓存不一致率超过1%: " + mismatchRate);
        }

        log.info("Cache verification completed: checked={}, mismatch={}, rate={}",
                totalChecked, mismatchCount, mismatchRate);
    }
}
```

### 3.4 SET路由规则体系

#### 3.4.1 SET路由规则

```java
/**
 * SET路由规则执行器
 */
public class SetRoutingRule {

    /**
     * SET路由规则执行
     * 规则1: SET路由只控制当前这一次调用，不影响后续调用
     * 规则2: 下游开启SET开关 → 调用同SET节点（支持自定义规则）
     * 规则3: 下游未开启SET开关 → 调用中心SET节点
     * 规则4: 选定的下游不可用 → 调用失败（不支持fallback）
     */
    public ServiceInstance route(SetContext context, String targetAppkey,
                                  List<ServiceInstance> candidates) {
        // 规则2: 检查下游是否开启SET开关
        if (isSetEnabled(targetAppkey)) {
            String currentSet = context.getSetName();

            // 2a: 检查是否有用户自定义路由规则
            CustomRouteRule customRule = getCustomRouteRule(targetAppkey);
            if (customRule != null) {
                String targetSet = customRoute.route(context, targetAppkey);
                return selectInstance(candidates, targetSet);
            }

            // 2b: 默认规则 - 调用同SET节点
            return selectInstance(candidates, currentSet);
        }

        // 规则3: 下游未开启SET开关 → 调用中心SET
        return selectInstance(candidates, SetInfo.CENTER.getName());
    }

    private ServiceInstance selectInstance(List<ServiceInstance> candidates, String setName) {
        List<ServiceInstance> filtered = candidates.stream()
                .filter(inst -> setName.equals(inst.getSetTag()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            // SET路由不支持fallback，直接失败
            throw new SetRoutingException("No available instance in SET: " + setName);
        }

        // 负载均衡选择
        return loadBalancer.select(filtered);
    }
}
```

#### 3.4.2 LiteSet路由规则

```java
/**
 * LiteSet路由规则执行器
 * 基于流量染色标记进行路由，支持fallback到中心
 */
public class LiteSetRoutingRule {

    /**
     * LiteSet路由规则执行
     * 规则1: 优先选择与流量标记相同的LiteSet内的服务
     * 规则2: 下游不可用时fallback到中心LiteSet（仅非中心LiteSet）
     * 规则3: 流量标记一旦设置，下游所有调用都携带该标记
     */
    public ServiceInstance route(FlowContext context, String targetAppkey,
                                  List<ServiceInstance> candidates) {
        String cellTag = context.getCellTag(); // 流量染色标记

        if (cellTag != null && !cellTag.isEmpty()) {
            // 优先选择同标记的LiteSet实例
            List<ServiceInstance> sameTagInstances = candidates.stream()
                    .filter(inst -> cellTag.equals(inst.getCellTag()))
                    .collect(Collectors.toList());

            if (!sameTagInstances.isEmpty()) {
                return loadBalancer.select(sameTagInstances);
            }

            // 同标记实例不存在或故障 → fallback到中心
            if (!isCenter(cellTag)) {
                List<ServiceInstance> centerInstances = candidates.stream()
                        .filter(inst -> isCenter(inst.getCellTag()))
                        .collect(Collectors.toList());

                if (!centerInstances.isEmpty()) {
                    log.warn("LiteSet fallback to center: appkey={}, tag={}",
                            targetAppkey, cellTag);
                    return loadBalancer.select(centerInstances);
                }
            }
        }

        // 无标记或中心标记 → 调用中心实例
        return selectCenterInstance(candidates);
    }
}
```

#### 3.4.3 路由优先级体系

```java
/**
 * 路由优先级管理
 * 标签路由（逻辑路由）优先于地理位置路由（物理路由）
 */
public class RoutingPriorityManager {

    private static final int RPC_CUSTOM_ROUTE_PRIORITY = 0;  // 最高
    private static final int SET_ROUTE_PRIORITY = 10;
    private static final int LITESET_ROUTE_PRIORITY = 20;
    private static final int SWIMLANE_ROUTE_PRIORITY = 30;
    private static final int SAME_RACK_PRIORITY = 40;
    private static final int SAME_CITY_PRIORITY = 50;

    /**
     * 按优先级依次应用路由规则
     */
    public List<ServiceInstance> applyRoutingRules(RpcInvocation invocation,
                                                     List<ServiceInstance> candidates) {
        List<RoutingRule> rules = Arrays.asList(
            new RpcCustomRouteRule(),      // RPC自定义分流（最高优先级）
            new SetRoutingRule(),          // SET路由
            new LiteSetRoutingRule(),      // LiteSet路由
            new SwimlaneRoutingRule(),     // 泳道路由
            new SameRackRouteRule(),       // 同机房优先
            new SameCityRouteRule()        // 同城市优先
        );

        List<ServiceInstance> filtered = candidates;
        for (RoutingRule rule : rules) {
            filtered = rule.apply(invocation, filtered);
            if (filtered.size() == 1) {
                break; // 已筛选到唯一实例
            }
        }

        return filtered;
    }
}
```

### 3.5 数据同步与容灾

#### 3.5.1 数据同步架构

```java
/**
 * SET间数据同步管理
 * 支持双向数据同步，保障SET间数据一致性
 */
public class DataSyncManager {

    private final DataReplicator replicator;
    private final ConflictResolver conflictResolver;

    /**
     * 双向数据同步
     * SET-A ↔ SET-B 互为容灾，数据双向同步
     */
    public void startBidirectionalSync(String setA, String setB) {
        // A→B 同步
        replicator.startSync(setA, setB, SyncDirection.FORWARD, syncConfig -> {
            syncConfig.setSyncMode(SyncMode.ASYNC);
            syncConfig.setConflictResolution(ConflictResolution.LAST_WRITE_WINS);
            syncConfig.setRetryTimes(3);
            syncConfig.setBatchSize(1000);
        });

        // B→A 同步
        replicator.startSync(setB, setA, SyncDirection.FORWARD, syncConfig -> {
            syncConfig.setSyncMode(SyncMode.ASYNC);
            syncConfig.setConflictResolution(ConflictResolution.LAST_WRITE_WINS);
            syncConfig.setRetryTimes(3);
            syncConfig.setBatchSize(1000);
        });
    }

    /**
     * 数据校验
     * 定期校验SET间数据一致性
     */
    public DataCheckResult verifyDataConsistency(String setA, String setB) {
        DataCheckResult result = new DataCheckResult();

        // 分表分批校验
        List<TableInfo> tables = getTablesToCheck();
        for (TableInfo table : tables) {
            // 获取两边的数据快照
            Map<String, Record> dataA = getDataSnapshot(setA, table);
            Map<String, Record> dataB = getDataSnapshot(setB, table);

            // 对比差异
            DataDiff diff = compareData(dataA, dataB);
            result.addTableResult(table.getName(), diff);

            if (diff.hasConflicts()) {
                // 自动修复冲突
                conflictResolver.resolve(setA, setB, table, diff);
            }
        }

        return result;
    }
}
```

#### 3.5.2 容灾切换

```java
/**
 * 容灾切换管理器
 * 支持分片粒度的流量和存储切换
 */
public class FailoverManager {

    private final SetMappingManager setMappingManager;
    private final TrafficSwitchManager trafficSwitchManager;
    private final DataSwitchManager dataSwitchManager;

    /**
     * SET级容灾切换
     * 将故障SET的流量切换到容灾SET
     */
    public FailoverResult failoverSet(String failedSet, String standbySet) {
        log.warn("Starting failover: {} → {}", failedSet, standbySet);

        FailoverResult result = new FailoverResult();
        long startTime = System.currentTimeMillis();

        // 1. 流量切换：更新路由映射，将故障SET的分片指向容灾SET
        List<ShardMapping> shards = setMappingManager.getShardsBySet(failedSet);
        for (ShardMapping shard : shards) {
            trafficSwitchManager.switchShard(shard.getShardId(), failedSet, standbySet);
            result.addSwitchedShard(shard.getShardId());
        }

        // 2. 数据切换：提升容灾SET的数据为主
        dataSwitchManager.promoteToPrimary(standbySet, shards);

        // 3. 验证切换结果
        boolean verified = verifyFailover(standbySet, shards);
        result.setVerified(verified);

        long duration = System.currentTimeMillis() - startTime;
        result.setDurationMs(duration);
        result.setRto(duration);

        log.warn("Failover completed: {} → {}, duration={}ms, shards={}, verified={}",
                failedSet, standbySet, duration, shards.size(), verified);

        // 发送告警通知
        alertManager.sendFailoverAlert(failedSet, standbySet, result);

        return result;
    }

    /**
     * 分片粒度容灾切换
     * 支持更细粒度的切换，降低切换风险
     */
    public FailoverResult failoverShard(String shardId, String fromSet, String toSet) {
        log.info("Shard-level failover: shard={}, {} → {}",
                shardId, fromSet, toSet);

        // 1. 切换单个分片的流量
        trafficSwitchManager.switchShard(shardId, fromSet, toSet);

        // 2. 切换单个分片的数据
        dataSwitchManager.promoteShard(shardId, toSet);

        // 3. 验证
        boolean verified = verifyShardFailover(shardId, toSet);

        return FailoverResult.builder()
                .switchedShards(Collections.singletonList(shardId))
                .verified(verified)
                .build();
    }

    /**
     * 阶梯式容灾切换
     * 先切换一个小城市验证，再分批次切换剩余城市
     */
    public FailoverResult stagedFailover(String failedSet, String standbySet,
                                          FailoverPlan plan) {
        FailoverResult result = new FailoverResult();

        // 第一批：切换一个小城市验证
        String testShard = plan.getTestShard();
        FailoverResult testResult = failoverShard(testShard, failedSet, standbySet);
        if (!testResult.isVerified()) {
            log.error("Test shard failover failed, aborting");
            return testResult;
        }
        result.merge(testResult);

        // 等待观察期
        sleep(plan.getObservationTimeMs());

        // 后续批次：分批切换剩余分片
        for (List<String> batch : plan.getRemainingBatches()) {
            for (String shardId : batch) {
                FailoverResult batchResult = failoverShard(shardId, failedSet, standbySet);
                result.merge(batchResult);
            }
            sleep(plan.getBatchIntervalMs());
        }

        return result;
    }
}
```

### 3.6 建站自动化

```java
/**
 * SET建站自动化管理器
 * 目标：将建站时长从3个月缩短至1周
 */
public class SetBootstrapManager {

    private final ResourceProvisioner resourceProvisioner;
    private final DeploymentManager deploymentManager;
    private final DataMigrationManager dataMigrationManager;
    private final ConfigManager configManager;

    /**
     * 一键建站流程
     */
    public BootstrapResult bootstrapSet(SetBootstrapRequest request) {
        String setName = request.getSetName();
        String datacenter = request.getDatacenter();
        log.info("Starting SET bootstrap: name={}, dc={}", setName, datacenter);

        BootstrapResult result = new BootstrapResult();

        // 1. 资源申请（机器、存储、网络）
        ResourceAllocation resources = resourceProvisioner.provision(
                setName, datacenter, request.getServiceList());
        result.setResources(resources);

        // 2. 基础组件部署（数据库、消息队列、缓存等）
        deployInfrastructure(setName, datacenter, resources);

        // 3. 服务部署
        for (ServiceDeploymentConfig svcConfig : request.getServiceList()) {
            if (svcConfig.isSetEnabled()) {
                // SET服务：部署到新SET
                deploymentManager.deployToSet(setName, svcConfig);
            }
            // 非SET服务不需要部署到新SET
        }

        // 4. 数据同步配置
        String standbyFor = request.getStandbyFor();
        if (standbyFor != null) {
            // 新SET作为standbyFor的容灾SET，建立双向数据同步
            dataMigrationManager.setupBidirectionalSync(setName, standbyFor);
        }

        // 5. 路由配置更新
        // 将新SET的分片映射关系注册到路由规则管理系统
        configManager.updateSetMapping(request.getShardMappings());

        // 6. 健康检查
        boolean healthy = healthCheck(setName);
        result.setHealthy(healthy);

        // 7. 灰度导入流量
        if (healthy && request.isAutoImportTraffic()) {
            gradualTrafficImport(setName, request.getTrafficPlan());
        }

        log.info("SET bootstrap completed: name={}, healthy={}", setName, healthy);
        return result;
    }

    /**
     * 渐进式流量导入
     */
    private void gradualTrafficImport(String setName, TrafficImportPlan plan) {
        for (double percentage : plan.getSteps()) {
            // 按比例导入流量
            trafficManager.adjustTraffic(setName, percentage);
            log.info("Traffic imported to {}: {}%", setName, percentage * 100);

            // 观察期
            sleep(plan.getStepIntervalMs());

            // 检查健康状态
            if (!healthCheck(setName)) {
                log.error("Health check failed during traffic import, rolling back");
                trafficManager.rollbackTraffic(setName);
                return;
            }
        }
    }
}
```

## 四、异常处理

### 4.1 路由服务不可用

```java
/**
 * 路由服务不可用时的降级处理
 */
public class RoutingServiceUnavailableHandler {

    /**
     * 多层降级策略
     */
    public SetInfo handleRoutingServiceDown(String shardKey, RequestContext context) {
        log.warn("Routing service unavailable, activating fallback");

        // 层级1: TraceContext复用
        SetInfo cached = getFromTraceContext(context);
        if (cached != null) {
            log.info("Using routing info from TraceContext");
            return cached;
        }

        // 层级2: 客户端携带的路由信息（C端）
        SetInfo clientCached = getFromClientRequest(context);
        if (clientCached != null) {
            log.info("Using routing info from client cache");
            return clientCached;
        }

        // 层级3: 本地Bitmap缓存（B端）
        SetInfo bitmapCached = getFromBitmapCache(shardKey);
        if (bitmapCached != null) {
            log.info("Using routing info from local bitmap cache");
            return bitmapCached;
        }

        // 层级4: 降级到中心SET
        log.error("All routing fallbacks failed, routing to center SET");
        return SetInfo.CENTER;
    }
}
```

### 4.2 SET内服务不可用

```java
/**
 * SET内服务不可用时的处理
 * SET路由不支持fallback，需要通过容灾切换解决
 */
public class SetServiceUnavailableHandler {

    /**
     * 检测到SET内服务不可用时的处理
     */
    public void handleSetServiceDown(String setName, String appkey) {
        log.error("Service {} in SET {} is unavailable", appkey, setName);

        // 1. 检查是否需要触发容灾切换
        SetHealthStatus health = setHealthMonitor.getHealthStatus(setName);
        if (health.getUnhealthyServiceRatio() > 0.5) {
            // 超过50%服务不可用，触发SET级容灾切换
            String standbySet = setMappingManager.getStandbySet(setName);
            failoverManager.failoverSet(setName, standbySet);
        } else {
            // 部分服务不可用，触发告警但不切换
            alertManager.sendAlert(String.format(
                "SET %s service %s unavailable, health ratio: %.2f",
                setName, appkey, health.getHealthyRatio()));
        }
    }
}
```

### 4.3 数据同步异常

```java
/**
 * 数据同步异常处理
 */
public class DataSyncExceptionHandler {

    /**
     * 数据同步延迟超阈值
     */
    public void handleSyncLag(String sourceSet, String targetSet, long lagMs) {
        if (lagMs > 60000) { // 超过60秒
            log.error("Data sync lag too high: {} → {}, lag={}ms",
                    sourceSet, targetSet, lagMs);
            alertManager.sendCriticalAlert(
                String.format("数据同步延迟超阈值: %s → %s, 延迟%dms",
                    sourceSet, targetSet, lagMs));
        } else if (lagMs > 10000) { // 超过10秒
            log.warn("Data sync lag warning: {} → {}, lag={}ms",
                    sourceSet, targetSet, lagMs);
        }
    }

    /**
     * 数据冲突检测与解决
     */
    public void handleDataConflict(String setA, String setB,
                                    ConflictRecord conflict) {
        log.warn("Data conflict detected: table={}, key={}, sets={}-{}",
                conflict.getTable(), conflict.getKey(), setA, setB);

        // 根据冲突解决策略处理
        switch (conflict.getResolutionStrategy()) {
            case LAST_WRITE_WINS:
                resolveByTimestamp(conflict);
                break;
            case SOURCE_PRIORITY:
                resolveByPriority(conflict, setA);
                break;
            case MANUAL_REVIEW:
                queueForManualReview(conflict);
                break;
        }
    }
}
```

### 4.4 容灾切换失败

```java
/**
 * 容灾切换失败处理
 */
public class FailoverFailureHandler {

    /**
     * 容灾切换失败时的回滚
     */
    public void handleFailoverFailure(String failedSet, String standbySet,
                                       FailoverResult result) {
        log.error("Failover failed: {} → {}, rolling back", failedSet, standbySet);

        // 1. 回滚流量切换
        for (String shardId : result.getSwitchedShards()) {
            try {
                trafficSwitchManager.rollbackShard(shardId, standbySet, failedSet);
            } catch (Exception e) {
                log.error("Traffic rollback failed for shard: {}", shardId, e);
            }
        }

        // 2. 回滚数据切换
        try {
            dataSwitchManager.rollbackPromotion(standbySet, result.getSwitchedShards());
        } catch (Exception e) {
            log.error("Data rollback failed", e);
        }

        // 3. 发送紧急告警
        alertManager.sendEmergencyAlert(String.format(
            "容灾切换失败并回滚: %s → %s, 需人工介入", failedSet, standbySet));
    }
}
```

## 五、性能优化

### 5.1 路由信息缓存优化

```java
/**
 * 路由信息多级缓存
 */
public class RoutingCacheManager {

    // L1: 本地内存缓存（毫秒级访问）
    private final Cache<String, SetInfo> localCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // L2: 分布式缓存（Redis）
    private final RedisClient redisCache;

    // L3: 路由服务
    private final RoutingServiceClient routingService;

    public SetInfo getSetInfo(String shardKey) {
        // L1: 本地缓存
        SetInfo cached = localCache.getIfPresent(shardKey);
        if (cached != null) {
            return cached;
        }

        // L2: Redis缓存
        try {
            cached = redisCache.get("route:" + shardKey);
            if (cached != null) {
                localCache.put(shardKey, cached);
                return cached;
            }
        } catch (Exception e) {
            log.warn("Redis cache access failed", e);
        }

        // L3: 路由服务
        cached = routingService.getSetByShardKey(shardKey);
        if (cached != null) {
            localCache.put(shardKey, cached);
            try {
                redisCache.set("route:" + shardKey, cached, 300);
            } catch (Exception ignored) {}
        }
        return cached;
    }
}
```

### 5.2 RoaringBitmap缓存优化

```java
/**
 * RoaringBitmap路由缓存优化
 * 特点：内存占用极小（月活门店仅4.3M），查询QPS可达125万
 */
public class OptimizedBitmapCache {

    // 使用转置结构：Map<SetName, RoaringBitmap>
    // 查询时遍历各SET的Bitmap判断poiId是否存在
    private volatile Map<String, RoaringBitmap> bitmapCache;

    /**
     * 批量查询优化
     * 减少遍历次数
     */
    public Map<Long, String> batchGetSets(List<Long> poiIds) {
        Map<Long, String> results = new HashMap<>();
        Map<String, RoaringBitmap> cache = this.bitmapCache;

        for (Long poiId : poiIds) {
            for (Map.Entry<String, RoaringBitmap> entry : cache.entrySet()) {
                if (entry.getValue().contains(poiId.intValue())) {
                    results.put(poiId, entry.getKey());
                    break;
                }
            }
        }
        return results;
    }

    /**
     * 并行查询优化
     * 利用多线程并行遍历各SET的Bitmap
     */
    public String parallelGetSet(Long poiId) {
        Map<String, RoaringBitmap> cache = this.bitmapCache;

        return cache.entrySet().parallelStream()
                .filter(entry -> entry.getValue().contains(poiId.intValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取缓存内存占用统计
     */
    public CacheStats getCacheStats() {
        long totalBytes = 0;
        int totalPois = 0;
        for (Map.Entry<String, RoaringBitmap> entry : bitmapCache.entrySet()) {
            totalBytes += entry.getValue().getSizeInBytes();
            totalPois += entry.getValue().getCardinality();
        }
        return CacheStats.builder()
                .memoryBytes(totalBytes)
                .memoryMB(totalBytes / 1024.0 / 1024.0)
                .totalPois(totalPois)
                .setCount(bitmapCache.size())
                .build();
    }
}
```

### 5.3 同机房优先路由

```java
/**
 * 同机房优先路由优化
 * 减少跨机房调用，降低网络延迟
 */
public class SameDatacenterRouteRule implements RoutingRule {

    @Override
    public List<ServiceInstance> apply(RpcInvocation invocation,
                                        List<ServiceInstance> candidates) {
        String localDc = DatacenterContext.getCurrentDatacenter();

        // 优先选择同机房实例
        List<ServiceInstance> sameDc = candidates.stream()
                .filter(inst -> localDc.equals(inst.getDatacenter()))
                .collect(Collectors.toList());

        if (!sameDc.isEmpty()) {
            return sameDc;
        }

        // 同机房不可用，选择同城市
        String localCity = DatacenterContext.getCurrentCity();
        List<ServiceInstance> sameCity = candidates.stream()
                .filter(inst -> localCity.equals(inst.getCity()))
                .collect(Collectors.toList());

        if (!sameCity.isEmpty()) {
            return sameCity;
        }

        // 全局选择
        return candidates;
    }
}
```

### 5.4 流量闭环优化

```java
/**
 * SET内流量闭环优化
 * 尽可能保证请求在SET内闭环，减少跨SET调用
 */
public class TrafficClosureOptimizer {

    /**
     * 分析流量闭环率
     */
    public ClosureReport analyzeClosureRate(String setName, TimeRange range) {
        List<CallRecord> records = callTraceAnalyzer.getRecords(setName, range);

        long totalCalls = records.size();
        long intraSetCalls = records.stream()
                .filter(r -> setName.equals(r.getTargetSet()))
                .count();

        double closureRate = (double) intraSetCalls / totalCalls;

        // 分析跨SET调用的服务
        Map<String, Long> crossSetCalls = records.stream()
                .filter(r -> !setName.equals(r.getTargetSet()))
                .collect(Collectors.groupingBy(
                        CallRecord::getTargetAppkey,
                        Collectors.counting()));

        return ClosureReport.builder()
                .setName(setName)
                .closureRate(closureRate)
                .totalCalls(totalCalls)
                .intraSetCalls(intraSetCalls)
                .crossSetCalls(crossSetCalls)
                .build();
    }

    /**
     * 优化建议生成
     */
    public List<OptimizationSuggestion> generateSuggestions(ClosureReport report) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        if (report.getClosureRate() < 0.95) {
            suggestions.add(new OptimizationSuggestion(
                "流量闭环率低于95%，建议检查跨SET调用是否合理"));

            // 分析高频跨SET调用的服务
            report.getCrossSetCalls().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .forEach(entry -> {
                        suggestions.add(new OptimizationSuggestion(
                            String.format("服务 %s 跨SET调用 %d 次，建议评估是否需要SET化",
                                entry.getKey(), entry.getValue())));
                    });
        }

        return suggestions;
    }
}
```

## 六、最佳实践

### 6.1 SET化改造最佳实践

1. **分片维度选择**：根据业务特点选择分片维度。LBS类业务（外卖、配送）适合按地域分片，交易类业务适合按用户ID取模分片。分片维度一旦确定很难更改，需要慎重选择。

2. **渐进式SET化**：不追求一步到位的完全SET化，可以先SET化核心链路服务，低频服务暂时保留在中心SET。基于成本等因素评估SET化的深度。

3. **数据分片方式**：硬分片（每分片一张表）可以将数据切换能力下沉至数据库，但要求全站统一数据分片，业务改造成本较高。软分片（分库分表中间件）灵活性更高但切换能力较弱。

4. **中心SET设计**：中心SET包含所有服务，非SET服务只部署在中心SET。中心SET的存在使得SET化改造可以渐进式进行，未SET化的服务可以正常被调用。

5. **SET开关控制**：以SET开关为准而非实际部署情况。即使下游服务在当前SET有部署，如果未开启SET开关，也不会被调用。

### 6.2 路由设计最佳实践

1. **路由信息全链路透传**：首次获取路由信息后放入TraceContext全链路复用，避免重复调用路由服务。一次请求链路中路由服务只需调用一次。

2. **多层容灾**：建立TraceContext复用 → 客户端缓存 → 本地Bitmap缓存 → 中心SET的四级容灾体系，确保路由服务不可用时业务不受影响。

3. **路由规则与分片逻辑解耦**：业务只需维护相对稳定的分片逻辑（参数→分片ID），路由规则（分片ID→SET）由统一的管控系统管理，可灵活调整。

4. **去中心化路由演进**：从中心化路由向去中心化路由演进，每个微服务负责计算分片ID并路由流量，具备流量纠错能力，避免单点故障。

5. **路由优先级明确**：标签路由（SET→LiteSet→泳道）优先于地理位置路由（同机房→同城市），自定义路由优先于默认路由。

### 6.3 容灾演练最佳实践

1. **常态化演练**：定期在生产环境进行断网演练和容量演练，验证容灾SOP的有效性。

2. **阶梯式切换**：容灾切换时先切换一个小城市验证，确认无误后分批次切换剩余城市，降低切换风险。

3. **RPO/RTO量化**：明确各业务的RPO和RTO目标，通过演练验证实际值是否达标。目标RPO<1min，RTO<1min。

4. **数据一致性校验**：容灾切换前后进行数据一致性校验，确保切换不丢失数据。

5. **互备数据存储隔离**：互备SET的数据存储应进行隔离，避免复用对方集群导致读写放大。

### 6.4 建站自动化最佳实践

1. **一键建站**：将建站流程自动化，从资源申请到服务部署、数据同步、路由配置全流程自动化，建站时长从3个月缩短至1周。

2. **灰度导入流量**：新SET建站完成后渐进式导入流量，先导入小比例流量验证，逐步扩大到全量。

3. **健康检查贯穿全程**：建站过程中每个阶段都进行健康检查，发现问题及时回滚。

4. **PaaS组件原生SET化**：PaaS组件应具备SET化原生能力，避免成为SET扩展的瓶颈。

5. **运维职责解耦**：将物理层面的运维工作（部署、数据同步、扩缩容）收敛至运维团队，业务团队只需关注容量规划。

### 6.5 监控体系最佳实践

1. **全局可视化**：建立面向SET化的监控体系，提供SET级、分片级、服务级的全局可视化能力。

2. **流量闭环率监控**：监控各SET的流量闭环率，目标闭环率>95%，对高频跨SET调用进行告警。

3. **路由服务监控**：监控路由服务的QPS、延迟、可用率，设置容量预警阈值。

4. **数据同步监控**：监控SET间数据同步延迟、冲突率，延迟超阈值自动告警。

5. **容灾能力监控**：持续监控各SET的健康状态、容量水位，确保容灾切换能力随时可用。

## 七、全链路实战案例

本章节结合三个典型场景，给出SET化治理的全链路实战代码，覆盖用户请求路由、SET扩容、SET容灾切换三大核心流程。每个案例均包含完整的异常处理、日志记录与幂等控制设计，可作为工程落地参考。

### 7.1 案例一：用户请求的SET路由全链路

#### 7.1.1 场景说明

用户下单请求从网关进入后，需要依次完成：解析SET标识 → 计算路由并转发到目标SET → SET内闭环完成下单业务处理 → 当遇到无法分片的中心化服务（如风控名单服务）时，触发跨SET调用兜底。整个链路要求幂等，避免网络重试导致重复下单。

```java
/**
 * 案例一：全链路请求上下文
 * 贯穿网关、路由、SET内处理、跨SET兜底调用的统一上下文对象
 */
public class SetRequestContext {

    private final String traceId;          // 全链路追踪ID，用于日志串联
    private final String requestId;        // 客户端幂等请求ID
    private final String userId;
    private volatile String setName;       // 计算得到的目标SET
    private volatile String shardId;       // 分片ID

    public SetRequestContext(String traceId, String requestId, String userId) {
        this.traceId = traceId;
        this.requestId = requestId;
        this.userId = userId;
    }

    // getter/setter省略
    public String getTraceId() { return traceId; }
    public String getRequestId() { return requestId; }
    public String getUserId() { return userId; }
    public String getSetName() { return setName; }
    public void setSetName(String setName) { this.setName = setName; }
    public String getShardId() { return shardId; }
    public void setShardId(String shardId) { this.shardId = shardId; }
}
```

**第一步：请求到达网关，解析SET标识**

```java
/**
 * 网关层入口Filter
 * 职责：生成/透传traceId、解析客户端携带的SET标识、异常兜底为中心SET
 */
public class OrderGatewayEntryFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(OrderGatewayEntryFilter.class);

    private final SetRouteService setRouteService;

    public OrderGatewayEntryFilter(SetRouteService setRouteService) {
        this.setRouteService = setRouteService;
    }

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        // 1. 生成/透传traceId，贯穿全链路日志
        String traceId = Optional.ofNullable(request.getHeader("X-Trace-Id"))
                .orElse(UUID.randomUUID().toString());
        // 2. 幂等请求ID，客户端下单按钮点击时生成，重试时保持不变
        String requestId = request.getHeader("X-Request-Id");
        String userId = request.getParameter("userId");

        MDC.put("traceId", traceId);
        try {
            if (requestId == null || requestId.isEmpty()) {
                log.error("[traceId={}] Missing X-Request-Id, reject request, userId={}",
                        traceId, userId);
                writeError(response, 400, "缺少幂等请求ID");
                return;
            }
            if (userId == null || userId.isEmpty()) {
                log.error("[traceId={}] Missing userId, reject request", traceId);
                writeError(response, 400, "缺少userId");
                return;
            }

            SetRequestContext ctx = new SetRequestContext(traceId, requestId, userId);

            // 3. 解析SET标识：优先取客户端携带的regionId，其次实时计算
            String regionId = request.getParameter("regionId");
            try {
                String setName = setRouteService.resolveSetName(userId, regionId);
                ctx.setSetName(setName);
                log.info("[traceId={}] Resolved SET for userId={}, regionId={}, set={}",
                        traceId, userId, regionId, setName);
            } catch (Exception e) {
                // 路由解析异常，兜底到中心SET，保证请求不中断
                log.error("[traceId={}] Resolve SET failed, fallback to CENTER, userId={}",
                        traceId, userId, e);
                ctx.setSetName(SetConstants.CENTER_SET);
            }

            request.setAttribute("setRequestContext", ctx);
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\"}", code, msg));
    }
}
```

**第二步：路由计算并转发到目标SET**

```java
/**
 * SET路由计算服务
 * 二级映射：userId/regionId → shardId → setName
 * 提供多级容灾能力，避免路由服务单点故障影响下单主链路
 */
public class SetRouteService {

    private static final Logger log = LoggerFactory.getLogger(SetRouteService.class);

    private final RoutingServiceClient routingServiceClient;
    private final BitmapRouteFallback bitmapRouteFallback;

    public SetRouteService(RoutingServiceClient routingServiceClient,
                            BitmapRouteFallback bitmapRouteFallback) {
        this.routingServiceClient = routingServiceClient;
        this.bitmapRouteFallback = bitmapRouteFallback;
    }

    /**
     * 解析目标SET
     * @throws SetRouteException 全部路由手段均失效时抛出，由调用方兜底到中心SET
     */
    public String resolveSetName(String userId, String regionId) {
        // 1. 客户端已携带regionId，直接二级映射，避免调用路由服务
        if (regionId != null && !regionId.isEmpty()) {
            String setName = routingServiceClient.getSetByRegionIdLocal(regionId);
            if (setName != null) {
                return setName;
            }
        }

        // 2. 调用中心路由服务实时计算（带超时保护）
        try {
            String setName = routingServiceClient.getSetByUserId(userId, 200 /* ms超时 */);
            if (setName != null) {
                return setName;
            }
        } catch (RoutingServiceTimeoutException | RoutingServiceUnavailableException e) {
            log.warn("Routing service unavailable, try local bitmap cache, userId={}", userId, e);
        }

        // 3. 路由服务不可用，走本地Bitmap缓存兜底
        String bitmapSet = bitmapRouteFallback.getSetByUserId(userId);
        if (bitmapSet != null) {
            return bitmapSet;
        }

        // 4. 所有手段均失效
        throw new SetRouteException("Cannot resolve SET for userId=" + userId);
    }
}

/** 路由解析异常 */
class SetRouteException extends RuntimeException {
    public SetRouteException(String message) {
        super(message);
    }
}
```

**第三步：转发到目标SET，SET内闭环处理下单业务（含幂等控制）**

```java
/**
 * 订单服务（SET服务）
 * 部署在每个SET内，处理路由转发进来的下单请求
 * 通过Redis分布式锁 + 数据库唯一索引双重保障幂等
 */
public class OrderSetService {

    private static final Logger log = LoggerFactory.getLogger(OrderSetService.class);

    private final RedisClient redisClient;
    private final OrderDao orderDao;
    private final InventoryService inventoryService; // SET内本地库存服务
    private final RiskCenterFallbackClient riskCenterFallbackClient; // 跨SET兜底调用

    public OrderSetService(RedisClient redisClient, OrderDao orderDao,
                            InventoryService inventoryService,
                            RiskCenterFallbackClient riskCenterFallbackClient) {
        this.redisClient = redisClient;
        this.orderDao = orderDao;
        this.inventoryService = inventoryService;
        this.riskCenterFallbackClient = riskCenterFallbackClient;
    }

    /**
     * SET内闭环下单
     * 步骤：幂等校验 → 分布式锁 → 风控校验(跨SET兜底) → 扣库存 → 创建订单 → 释放锁
     */
    public OrderResult createOrder(SetRequestContext ctx, CreateOrderRequest request) {
        String traceId = ctx.getTraceId();
        String requestId = ctx.getRequestId();
        String lockKey = "order:lock:" + requestId;

        // 1. 幂等前置校验：数据库中已有该requestId对应的订单直接返回，不重复处理
        Order existOrder = orderDao.findByRequestId(requestId);
        if (existOrder != null) {
            log.info("[traceId={}] Duplicate request detected, requestId={}, return existing order={}",
                    traceId, requestId, existOrder.getOrderId());
            return OrderResult.success(existOrder.getOrderId());
        }

        // 2. 分布式锁防止并发重复提交（同一requestId的并发请求）
        boolean locked = false;
        try {
            locked = redisClient.tryLock(lockKey, 5000 /* ms */);
            if (!locked) {
                log.warn("[traceId={}] Failed to acquire lock, requestId={}, possible concurrent retry",
                        traceId, requestId);
                throw new OrderConcurrentException("下单请求处理中，请勿重复提交");
            }

            // 双重检查，防止获取锁前另一个线程已完成写入
            existOrder = orderDao.findByRequestId(requestId);
            if (existOrder != null) {
                return OrderResult.success(existOrder.getOrderId());
            }

            // 3. 风控校验：风控名单服务是不可分片的中心服务（G服务），需跨SET兜底调用
            RiskCheckResult riskResult;
            try {
                riskResult = riskCenterFallbackClient.checkRisk(ctx, request.getUserId());
            } catch (Exception e) {
                // 风控服务异常时的兜底策略：降级为放行 + 异步补偿校验，避免影响主链路可用性
                log.error("[traceId={}] Risk check failed, degrade to pass-through with async compensation",
                        traceId, e);
                riskResult = RiskCheckResult.degradedPass();
                scheduleAsyncRiskRecheck(ctx, request);
            }

            if (riskResult.isRejected()) {
                log.warn("[traceId={}] Order rejected by risk control, userId={}, reason={}",
                        traceId, ctx.getUserId(), riskResult.getReason());
                throw new OrderRiskRejectedException(riskResult.getReason());
            }

            // 4. SET内闭环：扣减本SET内的库存（本地事务）
            boolean deducted = inventoryService.deduct(request.getSkuId(), request.getQuantity());
            if (!deducted) {
                log.warn("[traceId={}] Inventory deduct failed, skuId={}, qty={}",
                        traceId, request.getSkuId(), request.getQuantity());
                throw new InventoryInsufficientException("库存不足");
            }

            // 5. 创建订单（requestId作为唯一索引，DB层再兜底一次幂等）
            Order order = new Order();
            order.setOrderId(generateOrderId(ctx.getShardId()));
            order.setRequestId(requestId);
            order.setUserId(ctx.getUserId());
            order.setSkuId(request.getSkuId());
            order.setQuantity(request.getQuantity());
            order.setSetName(ctx.getSetName());
            order.setStatus(OrderStatus.CREATED);

            try {
                orderDao.insert(order);
            } catch (DuplicateKeyException e) {
                // requestId唯一索引冲突，说明并发场景下已被其他线程/实例写入，直接查回已有订单
                log.warn("[traceId={}] Duplicate key on insert, requestId={}, fetching existing order",
                        traceId, requestId);
                Order dup = orderDao.findByRequestId(requestId);
                // 需要回滚已扣减的库存，避免重复扣减
                inventoryService.rollback(request.getSkuId(), request.getQuantity());
                return OrderResult.success(dup.getOrderId());
            }

            log.info("[traceId={}] Order created successfully, orderId={}, set={}",
                    traceId, order.getOrderId(), ctx.getSetName());
            return OrderResult.success(order.getOrderId());

        } finally {
            if (locked) {
                redisClient.unlock(lockKey);
            }
        }
    }

    private String generateOrderId(String shardId) {
        // 订单号中携带分片ID，便于后续按分片路由查询订单，避免跨SET查询
        return shardId + "-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private void scheduleAsyncRiskRecheck(SetRequestContext ctx, CreateOrderRequest request) {
        // 提交到异步任务队列，事后补偿风控校验，若发现风险则冻结订单
        AsyncTaskExecutor.submit(() -> {
            try {
                riskCenterFallbackClient.recheckAndFreezeIfNeeded(ctx, request);
            } catch (Exception e) {
                log.error("[traceId={}] Async risk recheck failed, requestId={}",
                        ctx.getTraceId(), ctx.getRequestId(), e);
            }
        });
    }
}
```

**第四步：跨SET调用兜底（调用中心化风控服务）**

```java
/**
 * 跨SET调用兜底客户端
 * 场景：风控名单服务是全局唯一的中心服务(G服务)，无法SET化，
 * 所有SET在下单链路上都需要跨SET调用中心SET的风控服务
 * 兜底策略：超时/异常时降级放行 + 熔断保护，避免中心服务故障拖垮所有SET
 */
public class RiskCenterFallbackClient {

    private static final Logger log = LoggerFactory.getLogger(RiskCenterFallbackClient.class);

    private final RiskCenterRpcClient rpcClient; // 调用中心SET的RPC客户端
    private final CircuitBreaker circuitBreaker; // 熔断器

    public RiskCenterFallbackClient(RiskCenterRpcClient rpcClient, CircuitBreaker circuitBreaker) {
        this.rpcClient = rpcClient;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 风控校验，带熔断和超时保护
     */
    public RiskCheckResult checkRisk(SetRequestContext ctx, String userId) {
        String traceId = ctx.getTraceId();

        if (circuitBreaker.isOpen()) {
            // 熔断器已打开，直接走降级逻辑，不再请求中心服务
            log.warn("[traceId={}] Risk center circuit breaker OPEN, degrade directly", traceId);
            return RiskCheckResult.degradedPass();
        }

        try {
            // 强制路由到中心SET，不走同SET路由
            RiskCheckResponse resp = rpcClient.checkWithTimeout(
                    RiskCheckRequest.of(userId), 150 /* ms超时，控制主链路时延 */);
            circuitBreaker.recordSuccess();

            if (resp == null) {
                log.warn("[traceId={}] Risk center returned null response, treat as pass", traceId);
                return RiskCheckResult.degradedPass();
            }
            return resp.isHit()
                    ? RiskCheckResult.rejected(resp.getReason())
                    : RiskCheckResult.passed();

        } catch (RpcTimeoutException e) {
            circuitBreaker.recordFailure();
            log.error("[traceId={}] Risk center call timeout, userId={}", traceId, userId, e);
            throw e; // 交由上层做降级决策
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("[traceId={}] Risk center call failed, userId={}", traceId, userId, e);
            throw e;
        }
    }

    /**
     * 异步补偿校验：主链路降级放行后，事后重新校验，命中风险则冻结订单
     * 该方法本身需要幂等：同一订单只能被冻结一次
     */
    public void recheckAndFreezeIfNeeded(SetRequestContext ctx, CreateOrderRequest request) {
        RiskCheckResponse resp = rpcClient.checkWithTimeout(
                RiskCheckRequest.of(request.getUserId()), 1000);
        if (resp != null && resp.isHit()) {
            log.warn("[traceId={}] Async recheck hit risk, freezing order for requestId={}",
                    ctx.getTraceId(), ctx.getRequestId());
            rpcClient.freezeOrderIfExists(ctx.getRequestId(), resp.getReason());
        }
    }
}
```

#### 7.1.2 链路小结

| 阶段 | 关键动作 | 异常兜底 |
|------|---------|---------|
| 请求到达 | 生成traceId、校验requestId幂等键 | 缺失关键参数直接拒绝 |
| 解析SET标识 | 客户端regionId优先，其次路由服务 | 路由异常兜底到中心SET |
| 路由到目标SET | 二级映射计算setName | 路由服务超时走本地Bitmap缓存 |
| SET内闭环处理 | 分布式锁+DB唯一索引双重幂等，本地扣库存 | 唯一索引冲突回滚库存并返回已有订单 |
| 跨SET调用兜底 | 熔断器保护的中心风控调用 | 超时/异常降级放行+异步补偿校验 |

### 7.2 案例二：SET扩容全链路

#### 7.2.1 场景说明

当某个SET的流量或数据量接近容量上限时，需要新建一个SET承接部分分片，整体流程为：新建SET → 数据迁移（全量+增量）→ 流量切换（灰度）→ 验证 → 旧SET数据与资源回收。全流程需要支持幂等重试（例如迁移任务中断后重跑不会重复迁移或产生脏数据）。

```java
/**
 * SET扩容任务上下文
 * 记录扩容任务的状态机，支持任务中断后按状态恢复，避免重复执行
 */
public class SetExpansionTask {

    public enum Phase {
        INIT, SET_CREATED, FULL_MIGRATION_DONE, INCREMENTAL_SYNCING,
        TRAFFIC_SWITCHING, VERIFIED, OLD_SET_RECYCLED, FAILED
    }

    private final String taskId;           // 扩容任务唯一ID，用于幂等
    private final String newSetName;
    private final String sourceSetName;
    private final List<String> shardsToMove;
    private volatile Phase phase;

    public SetExpansionTask(String taskId, String newSetName, String sourceSetName,
                             List<String> shardsToMove) {
        this.taskId = taskId;
        this.newSetName = newSetName;
        this.sourceSetName = sourceSetName;
        this.shardsToMove = shardsToMove;
        this.phase = Phase.INIT;
    }

    public String getTaskId() { return taskId; }
    public String getNewSetName() { return newSetName; }
    public String getSourceSetName() { return sourceSetName; }
    public List<String> getShardsToMove() { return shardsToMove; }
    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }
}
```

**第一步：新建SET**

```java
/**
 * SET扩容编排器
 * 以任务状态机驱动整个扩容流程，每一步均可幂等重入
 */
public class SetExpansionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SetExpansionOrchestrator.class);

    private final ExpansionTaskRepository taskRepository; // 任务状态持久化
    private final SetBootstrapManager bootstrapManager;
    private final DataMigrationExecutor migrationExecutor;
    private final TrafficSwitchExecutor trafficSwitchExecutor;
    private final SetVerificationService verificationService;
    private final OldSetRecycleExecutor recycleExecutor;
    private final AlertManager alertManager;

    public SetExpansionOrchestrator(ExpansionTaskRepository taskRepository,
                                     SetBootstrapManager bootstrapManager,
                                     DataMigrationExecutor migrationExecutor,
                                     TrafficSwitchExecutor trafficSwitchExecutor,
                                     SetVerificationService verificationService,
                                     OldSetRecycleExecutor recycleExecutor,
                                     AlertManager alertManager) {
        this.taskRepository = taskRepository;
        this.bootstrapManager = bootstrapManager;
        this.migrationExecutor = migrationExecutor;
        this.trafficSwitchExecutor = trafficSwitchExecutor;
        this.verificationService = verificationService;
        this.recycleExecutor = recycleExecutor;
        this.alertManager = alertManager;
    }

    /**
     * 执行/续跑扩容任务
     * 幂等设计：任务状态持久化在DB，重复调用会根据当前phase跳过已完成的步骤
     */
    public void executeExpansion(String taskId) {
        SetExpansionTask task = taskRepository.load(taskId);
        if (task == null) {
            log.error("Expansion task not found, taskId={}", taskId);
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        log.info("[taskId={}] Resume expansion at phase={}", taskId, task.getPhase());

        try {
            switch (task.getPhase()) {
                case INIT:
                    doCreateSet(task);
                    // fallthrough 继续执行下一阶段
                case SET_CREATED:
                    doFullMigration(task);
                case FULL_MIGRATION_DONE:
                case INCREMENTAL_SYNCING:
                    doIncrementalSyncAndCutover(task);
                case TRAFFIC_SWITCHING:
                    doVerify(task);
                case VERIFIED:
                    doRecycleOldSet(task);
                case OLD_SET_RECYCLED:
                    log.info("[taskId={}] Expansion already completed", taskId);
                    break;
                case FAILED:
                    log.warn("[taskId={}] Task previously failed, please check manually before retry", taskId);
                    break;
                default:
                    throw new IllegalStateException("未知阶段: " + task.getPhase());
            }
        } catch (Exception e) {
            task.setPhase(SetExpansionTask.Phase.FAILED);
            taskRepository.save(task);
            log.error("[taskId={}] Expansion failed at some phase", taskId, e);
            alertManager.sendCriticalAlert(
                    String.format("SET扩容任务失败: taskId=%s, newSet=%s, error=%s",
                            taskId, task.getNewSetName(), e.getMessage()));
            throw new SetExpansionException("SET扩容失败: " + e.getMessage(), e);
        }
    }

    /** 阶段一：新建SET（资源申请+基础组件部署+服务部署） */
    private void doCreateSet(SetExpansionTask task) {
        log.info("[taskId={}] Phase INIT -> creating new SET: {}", task.getTaskId(), task.getNewSetName());

        // 幂等：建站前先检查目标SET是否已存在，避免重复申请资源
        if (bootstrapManager.setExists(task.getNewSetName())) {
            log.warn("[taskId={}] SET {} already exists, skip creation", task.getTaskId(), task.getNewSetName());
        } else {
            SetBootstrapRequest request = SetBootstrapRequest.builder()
                    .setName(task.getNewSetName())
                    .datacenter(resolveDatacenterFor(task.getShardsToMove()))
                    .autoImportTraffic(false) // 扩容场景手动控制流量切换节奏
                    .build();

            BootstrapResult result = bootstrapManager.bootstrapSet(request);
            if (!result.isHealthy()) {
                throw new SetExpansionException("新SET建站后健康检查未通过: " + task.getNewSetName());
            }
        }

        task.setPhase(SetExpansionTask.Phase.SET_CREATED);
        taskRepository.save(task);
        log.info("[taskId={}] New SET created and healthy: {}", task.getTaskId(), task.getNewSetName());
    }

    /** 阶段二：全量数据迁移 */
    private void doFullMigration(SetExpansionTask task) {
        log.info("[taskId={}] Phase SET_CREATED -> full data migration for shards={}",
                task.getTaskId(), task.getShardsToMove());

        for (String shardId : task.getShardsToMove()) {
            // 幂等：每个分片的迁移进度独立记录，已完成的分片跳过
            if (migrationExecutor.isFullMigrationDone(task.getTaskId(), shardId)) {
                log.info("[taskId={}] Shard {} full migration already done, skip", task.getTaskId(), shardId);
                continue;
            }
            try {
                migrationExecutor.fullMigrate(task.getSourceSetName(), task.getNewSetName(), shardId);
                migrationExecutor.markFullMigrationDone(task.getTaskId(), shardId);
                log.info("[taskId={}] Shard {} full migration completed", task.getTaskId(), shardId);
            } catch (Exception e) {
                log.error("[taskId={}] Shard {} full migration failed", task.getTaskId(), shardId, e);
                throw new SetExpansionException("分片全量迁移失败: " + shardId, e);
            }
        }

        task.setPhase(SetExpansionTask.Phase.FULL_MIGRATION_DONE);
        taskRepository.save(task);
    }

    /** 阶段三：开启增量同步，追平数据后进行流量切换 */
    private void doIncrementalSyncAndCutover(SetExpansionTask task) {
        log.info("[taskId={}] Phase FULL_MIGRATION_DONE -> incremental sync & cutover", task.getTaskId());

        task.setPhase(SetExpansionTask.Phase.INCREMENTAL_SYNCING);
        taskRepository.save(task);

        // 1. 开启增量同步（基于binlog的DRC链路），追平全量迁移期间产生的增量数据
        migrationExecutor.startIncrementalSync(task.getSourceSetName(), task.getNewSetName(),
                task.getShardsToMove());

        // 2. 等待增量同步延迟收敛到阈值以内，最多等待5分钟
        boolean caughtUp = waitForSyncCatchUp(task, 5 * 60 * 1000L);
        if (!caughtUp) {
            throw new SetExpansionException("增量同步长时间未追平，终止扩容: " + task.getTaskId());
        }

        // 3. 灰度切换流量：先切一个分片验证，再切剩余分片
        task.setPhase(SetExpansionTask.Phase.TRAFFIC_SWITCHING);
        taskRepository.save(task);

        String pilotShard = task.getShardsToMove().get(0);
        trafficSwitchExecutor.switchShardTraffic(pilotShard, task.getSourceSetName(),
                task.getNewSetName(), true /* stopWriteOnSource */);
        log.info("[taskId={}] Pilot shard {} traffic switched to {}", task.getTaskId(), pilotShard, task.getNewSetName());

        sleep(60_000); // 观察期

        for (String shardId : task.getShardsToMove()) {
            if (shardId.equals(pilotShard)) {
                continue;
            }
            trafficSwitchExecutor.switchShardTraffic(shardId, task.getSourceSetName(),
                    task.getNewSetName(), true);
            log.info("[taskId={}] Shard {} traffic switched to {}", task.getTaskId(), shardId, task.getNewSetName());
        }

        taskRepository.save(task);
    }

    /** 阶段四：切换后验证 */
    private void doVerify(SetExpansionTask task) {
        log.info("[taskId={}] Phase TRAFFIC_SWITCHING -> verification", task.getTaskId());

        VerificationResult result = verificationService.verify(task.getNewSetName(), task.getShardsToMove());
        if (!result.isPassed()) {
            log.error("[taskId={}] Verification failed: {}", task.getTaskId(), result.getFailureReasons());
            // 验证失败，回滚流量到源SET，保障业务不受影响
            for (String shardId : task.getShardsToMove()) {
                trafficSwitchExecutor.switchShardTraffic(shardId, task.getNewSetName(),
                        task.getSourceSetName(), true);
            }
            throw new SetExpansionException("扩容验证失败并已回滚流量: " + result.getFailureReasons());
        }

        task.setPhase(SetExpansionTask.Phase.VERIFIED);
        taskRepository.save(task);
        log.info("[taskId={}] Verification passed", task.getTaskId());
    }

    /** 阶段五：旧SET数据与资源回收 */
    private void doRecycleOldSet(SetExpansionTask task) {
        log.info("[taskId={}] Phase VERIFIED -> recycling old SET data for shards={}",
                task.getTaskId(), task.getShardsToMove());

        try {
            // 保留观察期（如7天）后再回收，此处假设观察期已通过外部调度触发
            recycleExecutor.recycleShardData(task.getSourceSetName(), task.getShardsToMove());
            task.setPhase(SetExpansionTask.Phase.OLD_SET_RECYCLED);
            taskRepository.save(task);
            log.info("[taskId={}] Old SET data recycled successfully, expansion completed", task.getTaskId());
        } catch (Exception e) {
            // 回收失败不影响业务可用性（流量已切走），记录告警等待人工处理即可
            log.error("[taskId={}] Old SET recycle failed, requires manual cleanup", task.getTaskId(), e);
            alertManager.sendAlert(String.format(
                    "SET扩容回收阶段失败，需人工清理: taskId=%s, sourceSet=%s",
                    task.getTaskId(), task.getSourceSetName()));
        }
    }

    private boolean waitForSyncCatchUp(SetExpansionTask task, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            long lagMs = migrationExecutor.getSyncLagMs(task.getSourceSetName(), task.getNewSetName());
            log.info("[taskId={}] Incremental sync lag={}ms", task.getTaskId(), lagMs);
            if (lagMs < 1000) { // 延迟收敛到1秒以内视为追平
                return true;
            }
            sleep(5000);
        }
        return false;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String resolveDatacenterFor(List<String> shardsToMove) {
        // 根据分片的地理位置维度选择建站机房，简化实现
        return "dc-" + shardsToMove.get(0);
    }
}

/** SET扩容自定义异常 */
class SetExpansionException extends RuntimeException {
    public SetExpansionException(String message) { super(message); }
    public SetExpansionException(String message, Throwable cause) { super(message, cause); }
}
```

**数据迁移执行器（含幂等标记）**

```java
/**
 * 数据迁移执行器
 * 全量迁移使用分片级幂等标记（迁移完成标记落库），避免任务重跑时重复迁移
 */
public class DataMigrationExecutor {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationExecutor.class);

    private final MigrationProgressDao progressDao;
    private final DataReplicator replicator;

    public DataMigrationExecutor(MigrationProgressDao progressDao, DataReplicator replicator) {
        this.progressDao = progressDao;
        this.replicator = replicator;
    }

    public boolean isFullMigrationDone(String taskId, String shardId) {
        MigrationProgress progress = progressDao.find(taskId, shardId);
        return progress != null && progress.isFullDone();
    }

    /**
     * 全量迁移：按主键分页拉取源SET数据写入目标SET
     * 使用UPSERT语义，即使中途失败重跑也不会产生重复数据
     */
    public void fullMigrate(String sourceSet, String targetSet, String shardId) {
        long lastId = progressDao.getLastMigratedId(sourceSet, shardId);
        int batchSize = 2000;

        while (true) {
            List<Record> batch = replicator.readBatch(sourceSet, shardId, lastId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            try {
                // upsert写入，requestId/主键冲突时更新而非报错，天然幂等
                replicator.upsertBatch(targetSet, batch);
            } catch (Exception e) {
                log.error("Full migration batch write failed, sourceSet={}, targetSet={}, shardId={}, lastId={}",
                        sourceSet, targetSet, shardId, lastId, e);
                throw e; // 中断迁移，保留进度供下次重跑
            }
            lastId = batch.get(batch.size() - 1).getId();
            // 记录迁移进度，支持断点续传
            progressDao.updateLastMigratedId(sourceSet, shardId, lastId);
        }

        progressDao.markFullDone(sourceSet, shardId);
    }

    /** 开启基于DRC的增量同步 */
    public void startIncrementalSync(String sourceSet, String targetSet, List<String> shardIds) {
        for (String shardId : shardIds) {
            replicator.startSync(sourceSet, targetSet, SyncDirection.FORWARD, cfg -> {
                cfg.setSyncMode(SyncMode.ASYNC);
                cfg.setShardId(shardId);
                cfg.setConflictResolution(ConflictResolution.SOURCE_PRIORITY);
                cfg.setRetryTimes(5);
            });
            log.info("Incremental sync started: {} -> {}, shard={}", sourceSet, targetSet, shardId);
        }
    }

    public long getSyncLagMs(String sourceSet, String targetSet) {
        return replicator.getSyncLagMs(sourceSet, targetSet);
    }

    public void markFullMigrationDone(String taskId, String shardId) {
        progressDao.markTaskShardDone(taskId, shardId);
    }
}
```

**流量切换执行器**

```java
/**
 * 流量切换执行器
 * 切换前先对源SET该分片置为"只读"，避免切换窗口期数据双写不一致
 */
public class TrafficSwitchExecutor {

    private static final Logger log = LoggerFactory.getLogger(TrafficSwitchExecutor.class);

    private final SetMappingManager setMappingManager;
    private final DataSwitchManager dataSwitchManager;

    public TrafficSwitchExecutor(SetMappingManager setMappingManager,
                                  DataSwitchManager dataSwitchManager) {
        this.setMappingManager = setMappingManager;
        this.dataSwitchManager = dataSwitchManager;
    }

    /**
     * 切换单个分片流量
     * 幂等：切换前检查当前路由指向，已经指向目标SET则直接跳过
     */
    public void switchShardTraffic(String shardId, String fromSet, String toSet, boolean stopWriteOnSource) {
        String currentSet = setMappingManager.getSetByShardId(shardId);
        if (toSet.equals(currentSet)) {
            log.info("Shard {} already routed to {}, skip switch", shardId, toSet);
            return;
        }

        try {
            if (stopWriteOnSource) {
                // 短暂只读窗口，确保迁移过程中源SET不再产生新的增量数据
                dataSwitchManager.setReadOnly(fromSet, shardId, true);
            }

            // 等待最后一批增量同步完成（重试3次，每次等待1秒）
            for (int i = 0; i < 3; i++) {
                if (dataSwitchManager.isFullyCaughtUp(fromSet, toSet, shardId)) {
                    break;
                }
                Thread.sleep(1000);
            }

            // 更新路由映射，指向新SET
            setMappingManager.updateShardMapping(shardId, toSet);
            log.info("Shard {} route switched: {} -> {}", shardId, fromSet, toSet);

        } catch (Exception e) {
            log.error("Traffic switch failed for shard={}, {} -> {}, rolling back read-only flag",
                    shardId, fromSet, toSet, e);
            if (stopWriteOnSource) {
                dataSwitchManager.setReadOnly(fromSet, shardId, false);
            }
            throw new SetExpansionException("流量切换失败: shard=" + shardId, e);
        } finally {
            if (stopWriteOnSource) {
                dataSwitchManager.setReadOnly(fromSet, shardId, false);
            }
        }
    }
}
```

#### 7.2.2 链路小结

| 阶段 | 关键动作 | 幂等/异常保障 |
|------|---------|---------------|
| 新建SET | 资源申请、基础组件部署、服务部署 | 建站前检查SET是否已存在 |
| 数据迁移 | 全量分页迁移+断点续传，UPSERT写入 | 进度落库，重跑跳过已完成分片；写入失败保留进度 |
| 流量切换 | 分片粒度灰度切换，先导入试点分片观察 | 切换前检查当前路由，已切换则跳过；短暂只读窗口防止双写 |
| 验证 | 切换后数据/流量一致性校验 | 验证失败自动回滚流量到源SET |
| 旧SET回收 | 观察期后清理源SET数据 | 回收失败仅告警，不影响已切走的业务流量 |

### 7.3 案例三：SET容灾切换全链路

#### 7.3.1 场景说明

当某个SET发生故障（如AZ级机房故障）时，需要完成：故障检测 → 流量切走（切到备SET）→ 备SET接管数据写入 → 数据一致性修复（补偿故障期间未同步完成的数据）→ 故障SET恢复后重新纳管。全程要求幂等，避免重复触发切换或数据修复导致状态错乱。

```java
/**
 * 容灾切换任务状态机
 * 持久化在DB，支持切换过程中断后继续执行，防止重复切换
 */
public class SetFailoverTask {

    public enum Phase {
        DETECTED, TRAFFIC_SWITCHED, STANDBY_PROMOTED,
        DATA_REPAIRED, RECOVERED, ABORTED
    }

    private final String taskId;          // 幂等键：同一故障事件只生成一个taskId
    private final String failedSet;
    private final String standbySet;
    private volatile Phase phase;
    private volatile long detectedAtMs;

    public SetFailoverTask(String taskId, String failedSet, String standbySet) {
        this.taskId = taskId;
        this.failedSet = failedSet;
        this.standbySet = standbySet;
        this.phase = Phase.DETECTED;
        this.detectedAtMs = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public String getFailedSet() { return failedSet; }
    public String getStandbySet() { return standbySet; }
    public Phase getPhase() { return phase; }
    public void setPhase(Phase phase) { this.phase = phase; }
    public long getDetectedAtMs() { return detectedAtMs; }
}
```

**第一步：SET故障检测**

```java
/**
 * SET健康探测器
 * 多维度健康探测：服务可用率、数据库连通性、消息队列积压
 * 检测到故障后生成幂等的容灾任务，避免同一故障重复触发多个切换任务
 */
public class SetHealthDetector {

    private static final Logger log = LoggerFactory.getLogger(SetHealthDetector.class);

    private final SetHealthMonitor setHealthMonitor;
    private final FailoverTaskRepository failoverTaskRepository;
    private final SetFailoverOrchestrator failoverOrchestrator;
    private final AlertManager alertManager;

    // 连续探测失败计数，避免网络抖动误判
    private final Map<String, AtomicInteger> consecutiveFailureCount = new ConcurrentHashMap<>();
    private static final int FAILURE_THRESHOLD = 3;

    public SetHealthDetector(SetHealthMonitor setHealthMonitor,
                              FailoverTaskRepository failoverTaskRepository,
                              SetFailoverOrchestrator failoverOrchestrator,
                              AlertManager alertManager) {
        this.setHealthMonitor = setHealthMonitor;
        this.failoverTaskRepository = failoverTaskRepository;
        this.failoverOrchestrator = failoverOrchestrator;
        this.alertManager = alertManager;
    }

    /**
     * 定时调度执行（如每10秒一次）
     */
    public void detectAndTriggerFailover(String setName) {
        SetHealthStatus health;
        try {
            health = setHealthMonitor.getHealthStatus(setName);
        } catch (Exception e) {
            log.error("Health check itself failed for SET {}, treat as inconclusive, skip this round",
                    setName, e);
            return;
        }

        boolean unhealthy = health.getUnhealthyServiceRatio() > 0.5
                || health.isDatabaseUnreachable()
                || health.getMqBacklog() > 1_000_000;

        AtomicInteger counter = consecutiveFailureCount.computeIfAbsent(setName, k -> new AtomicInteger(0));

        if (!unhealthy) {
            if (counter.get() > 0) {
                log.info("SET {} recovered before threshold reached, reset counter", setName);
            }
            counter.set(0);
            return;
        }

        int failures = counter.incrementAndGet();
        log.warn("SET {} unhealthy check #{}, unhealthyRatio={}, dbUnreachable={}, mqBacklog={}",
                setName, failures, health.getUnhealthyServiceRatio(),
                health.isDatabaseUnreachable(), health.getMqBacklog());

        if (failures < FAILURE_THRESHOLD) {
            return; // 未达到连续失败阈值，暂不触发切换，避免抖动误判
        }

        // 幂等：同一SET在同一故障窗口内只创建一个容灾任务
        SetFailoverTask existingTask = failoverTaskRepository.findActiveTaskBySet(setName);
        if (existingTask != null) {
            log.info("Failover task already in progress for SET {}, taskId={}, skip re-trigger",
                    setName, existingTask.getTaskId());
            return;
        }

        String standbySet = setHealthMonitor.getStandbySet(setName);
        if (standbySet == null) {
            log.error("No standby SET configured for {}, cannot auto-failover, alert only", setName);
            alertManager.sendCriticalAlert("SET " + setName + " 故障但未配置容灾SET，需人工介入");
            return;
        }

        String taskId = "failover-" + setName + "-" + (System.currentTimeMillis() / 60000); // 每分钟粒度去重
        SetFailoverTask task = new SetFailoverTask(taskId, setName, standbySet);
        failoverTaskRepository.save(task);

        log.error("SET {} confirmed unhealthy after {} consecutive checks, triggering failover to {}, taskId={}",
                setName, failures, standbySet, taskId);
        alertManager.sendCriticalAlert(String.format(
                "检测到SET故障，自动触发容灾切换: failedSet=%s, standbySet=%s, taskId=%s",
                setName, standbySet, taskId));

        try {
            failoverOrchestrator.executeFailover(taskId);
        } catch (Exception e) {
            log.error("Failover orchestration threw exception for taskId={}", taskId, e);
        } finally {
            counter.set(0); // 无论成功失败都重置计数，避免重复触发同一任务（任务级幂等已由taskRepository保障）
        }
    }
}
```

**第二步至第五步：切流量、备SET接管、数据一致性修复、故障SET恢复**

```java
/**
 * 容灾切换编排器
 * 与SET扩容编排器类似，采用状态机驱动，保证每一步可幂等重入
 */
public class SetFailoverOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SetFailoverOrchestrator.class);

    private final FailoverTaskRepository taskRepository;
    private final TrafficSwitchManager trafficSwitchManager;
    private final DataSwitchManager dataSwitchManager;
    private final DataConsistencyRepairService repairService;
    private final SetRecoveryService recoveryService;
    private final AlertManager alertManager;

    public SetFailoverOrchestrator(FailoverTaskRepository taskRepository,
                                    TrafficSwitchManager trafficSwitchManager,
                                    DataSwitchManager dataSwitchManager,
                                    DataConsistencyRepairService repairService,
                                    SetRecoveryService recoveryService,
                                    AlertManager alertManager) {
        this.taskRepository = taskRepository;
        this.trafficSwitchManager = trafficSwitchManager;
        this.dataSwitchManager = dataSwitchManager;
        this.repairService = repairService;
        this.recoveryService = recoveryService;
        this.alertManager = alertManager;
    }

    /**
     * 执行/续跑容灾切换任务
     */
    public void executeFailover(String taskId) {
        SetFailoverTask task = taskRepository.load(taskId);
        if (task == null) {
            log.error("Failover task not found, taskId={}", taskId);
            return;
        }

        log.warn("[taskId={}] Resume failover at phase={}, failedSet={}, standbySet={}",
                taskId, task.getPhase(), task.getFailedSet(), task.getStandbySet());

        try {
            switch (task.getPhase()) {
                case DETECTED:
                    doSwitchTraffic(task);
                case TRAFFIC_SWITCHED:
                    doPromoteStandby(task);
                case STANDBY_PROMOTED:
                    doRepairDataConsistency(task);
                case DATA_REPAIRED:
                    doScheduleRecovery(task);
                case RECOVERED:
                    log.info("[taskId={}] Failover already fully recovered", taskId);
                    break;
                case ABORTED:
                    log.warn("[taskId={}] Task previously aborted, manual check required", taskId);
                    break;
                default:
                    throw new IllegalStateException("未知阶段: " + task.getPhase());
            }
        } catch (Exception e) {
            task.setPhase(SetFailoverTask.Phase.ABORTED);
            taskRepository.save(task);
            log.error("[taskId={}] Failover aborted due to exception", taskId, e);
            alertManager.sendCriticalAlert(String.format(
                    "容灾切换任务异常终止，需人工介入: taskId=%s, failedSet=%s", taskId, task.getFailedSet()));
            throw new SetFailoverException("容灾切换失败: " + e.getMessage(), e);
        }
    }

    /** 阶段一：流量切走 —— 将故障SET的所有分片流量切到备SET */
    private void doSwitchTraffic(SetFailoverTask task) {
        String taskId = task.getTaskId();
        log.warn("[taskId={}] Phase DETECTED -> switching traffic {} -> {}",
                taskId, task.getFailedSet(), task.getStandbySet());

        List<ShardMapping> shards = trafficSwitchManager.getShardsBySet(task.getFailedSet());
        List<String> switched = new ArrayList<>();
        try {
            for (ShardMapping shard : shards) {
                String currentSet = trafficSwitchManager.getCurrentSet(shard.getShardId());
                if (task.getStandbySet().equals(currentSet)) {
                    // 幂等：已经切换过的分片跳过
                    log.info("[taskId={}] Shard {} already switched, skip", taskId, shard.getShardId());
                    switched.add(shard.getShardId());
                    continue;
                }
                trafficSwitchManager.switchShard(shard.getShardId(), task.getFailedSet(), task.getStandbySet());
                switched.add(shard.getShardId());
                log.warn("[taskId={}] Shard {} traffic switched to standby", taskId, shard.getShardId());
            }
        } catch (Exception e) {
            log.error("[taskId={}] Traffic switch partially failed, switched so far={}", taskId, switched, e);
            throw new SetFailoverException("流量切换阶段失败", e);
        }

        task.setPhase(SetFailoverTask.Phase.TRAFFIC_SWITCHED);
        taskRepository.save(task);
        log.warn("[taskId={}] All shard traffic switched to standby set {}, total={}",
                taskId, task.getStandbySet(), switched.size());
    }

    /** 阶段二：备SET接管 —— 提升备SET数据为主，接受写入 */
    private void doPromoteStandby(SetFailoverTask task) {
        String taskId = task.getTaskId();
        log.warn("[taskId={}] Phase TRAFFIC_SWITCHED -> promoting standby {} to primary",
                taskId, task.getStandbySet());

        // 幂等：先检查备SET是否已是主
        if (dataSwitchManager.isPrimary(task.getStandbySet())) {
            log.info("[taskId={}] Standby {} already promoted to primary, skip", taskId, task.getStandbySet());
        } else {
            List<ShardMapping> shards = trafficSwitchManager.getShardsBySet(task.getFailedSet());
            dataSwitchManager.promoteToPrimary(task.getStandbySet(), shards);
        }

        task.setPhase(SetFailoverTask.Phase.STANDBY_PROMOTED);
        taskRepository.save(task);
        log.warn("[taskId={}] Standby {} promoted to primary, now accepting writes", taskId, task.getStandbySet());
    }

    /** 阶段三：数据一致性修复 —— 补偿故障切换窗口期未同步的数据 */
    private void doRepairDataConsistency(SetFailoverTask task) {
        String taskId = task.getTaskId();
        log.warn("[taskId={}] Phase STANDBY_PROMOTED -> repairing data consistency", taskId);

        RepairResult result = repairService.repair(task.getFailedSet(), task.getStandbySet(),
                task.getDetectedAtMs());

        if (!result.isFullyRepaired()) {
            log.error("[taskId={}] Data repair incomplete, unresolved records={}",
                    taskId, result.getUnresolvedCount());
            alertManager.sendAlert(String.format(
                    "容灾数据修复未完全闭环，剩余%d条记录需人工核查: taskId=%s",
                    result.getUnresolvedCount(), taskId));
            // 不阻塞主流程，未闭环记录进入人工核查队列
        }

        task.setPhase(SetFailoverTask.Phase.DATA_REPAIRED);
        taskRepository.save(task);
        log.warn("[taskId={}] Data consistency repair completed, repaired={}, unresolved={}",
                taskId, result.getRepairedCount(), result.getUnresolvedCount());
    }

    /** 阶段四：调度故障SET恢复检测（异步，不阻塞容灾主流程收敛） */
    private void doScheduleRecovery(SetFailoverTask task) {
        String taskId = task.getTaskId();
        log.info("[taskId={}] Phase DATA_REPAIRED -> scheduling recovery watch for {}",
                taskId, task.getFailedSet());

        recoveryService.scheduleRecoveryWatch(task);

        task.setPhase(SetFailoverTask.Phase.RECOVERED);
        taskRepository.save(task);
        log.info("[taskId={}] Failover main flow completed, RTO={}ms",
                taskId, System.currentTimeMillis() - task.getDetectedAtMs());
    }
}

/** 容灾切换自定义异常 */
class SetFailoverException extends RuntimeException {
    public SetFailoverException(String message) { super(message); }
    public SetFailoverException(String message, Throwable cause) { super(message, cause); }
}
```

**数据一致性修复服务**

```java
/**
 * 数据一致性修复服务
 * 修复对象：故障SET在被检测到故障到流量真正切走这段窗口期内，
 * 可能存在已提交到故障SET但未来得及同步到备SET的数据
 * 幂等设计：以记录的业务唯一键（如requestId/orderId）做UPSERT修复，重复执行不产生副作用
 */
public class DataConsistencyRepairService {

    private static final Logger log = LoggerFactory.getLogger(DataConsistencyRepairService.class);

    private final DataReplicator replicator;
    private final RepairRecordDao repairRecordDao;

    public DataConsistencyRepairService(DataReplicator replicator, RepairRecordDao repairRecordDao) {
        this.replicator = replicator;
        this.repairRecordDao = repairRecordDao;
    }

    /**
     * 修复入口
     * @param failedSet 故障SET
     * @param standbySet 已接管的备SET
     * @param windowStartMs 故障检测时间点，作为修复扫描的起始时间
     */
    public RepairResult repair(String failedSet, String standbySet, long windowStartMs) {
        int repaired = 0;
        int unresolved = 0;

        List<TableInfo> tables = getTablesToRepair();
        for (TableInfo table : tables) {
            try {
                RepairResult tableResult = repairTable(failedSet, standbySet, table, windowStartMs);
                repaired += tableResult.getRepairedCount();
                unresolved += tableResult.getUnresolvedCount();
            } catch (Exception e) {
                log.error("Repair table {} failed for {} -> {}", table.getName(), failedSet, standbySet, e);
                unresolved += 1; // 至少记录一次异常，避免静默丢失
                repairRecordDao.markTableRepairFailed(failedSet, standbySet, table.getName(), e.getMessage());
            }
        }

        return RepairResult.builder()
                .repairedCount(repaired)
                .unresolvedCount(unresolved)
                .fullyRepaired(unresolved == 0)
                .build();
    }

    private RepairResult repairTable(String failedSet, String standbySet, TableInfo table, long windowStartMs) {
        // 1. 尝试从故障SET读取窗口期内的变更记录（若故障SET数据库仍可只读访问）
        List<Record> pendingRecords;
        try {
            pendingRecords = replicator.readChangesSince(failedSet, table, windowStartMs);
        } catch (Exception e) {
            // 故障SET完全不可访问（如整机房断电），改用binlog归档/消息队列积压数据兜底
            log.warn("Cannot read directly from failed set {}, fallback to binlog archive for table {}",
                    failedSet, table.getName(), e);
            pendingRecords = replicator.readChangesFromBinlogArchive(failedSet, table, windowStartMs);
        }

        int repaired = 0;
        int unresolved = 0;
        for (Record record : pendingRecords) {
            try {
                // 幂等修复：以业务唯一键做UPSERT，若备SET已存在更新时间更晚的记录则跳过（避免覆盖新数据）
                boolean applied = replicator.upsertIfNewer(standbySet, table, record);
                if (applied) {
                    repaired++;
                    repairRecordDao.markRepaired(failedSet, standbySet, table.getName(), record.getId());
                } else {
                    log.info("Record {} in table {} already up-to-date on standby, skip", record.getId(), table.getName());
                }
            } catch (Exception e) {
                unresolved++;
                log.error("Failed to repair record {} in table {}, queued for manual review",
                        record.getId(), table.getName(), e);
                repairRecordDao.queueForManualReview(failedSet, standbySet, table.getName(), record.getId(), e.getMessage());
            }
        }

        return RepairResult.builder().repairedCount(repaired).unresolvedCount(unresolved).build();
    }

    private List<TableInfo> getTablesToRepair() {
        // 返回需要参与容灾一致性修复的核心业务表清单
        return TableRegistry.getFailoverCriticalTables();
    }
}
```

**故障SET恢复服务**

```java
/**
 * 故障SET恢复服务
 * 定期探测故障SET是否恢复健康，恢复后将其重新纳管为备SET（而非直接抢回流量）
 * 避免"恢复抖动"：故障SET刚恢复即被重新导入流量后又再次故障
 */
public class SetRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SetRecoveryService.class);

    private final SetHealthMonitor setHealthMonitor;
    private final SetMappingManager setMappingManager;
    private final DataSyncManager dataSyncManager;
    private final AlertManager alertManager;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public SetRecoveryService(SetHealthMonitor setHealthMonitor,
                               SetMappingManager setMappingManager,
                               DataSyncManager dataSyncManager,
                               AlertManager alertManager) {
        this.setHealthMonitor = setHealthMonitor;
        this.setMappingManager = setMappingManager;
        this.dataSyncManager = dataSyncManager;
        this.alertManager = alertManager;
    }

    /**
     * 调度恢复观察任务：每分钟探测一次故障SET健康状态，最长观察24小时
     * 幂等：以failedSet为key去重，避免重复调度多个观察任务
     */
    private final Set<String> watchingSet = ConcurrentHashMap.newKeySet();

    public void scheduleRecoveryWatch(SetFailoverTask task) {
        String failedSet = task.getFailedSet();
        if (!watchingSet.add(failedSet)) {
            log.info("Recovery watch already scheduled for {}, skip duplicate scheduling", failedSet);
            return;
        }

        AtomicInteger healthyStreak = new AtomicInteger(0);
        long deadline = System.currentTimeMillis() + 24 * 3600 * 1000L;

        ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (System.currentTimeMillis() > deadline) {
                    log.error("SET {} did not recover within 24h, stop auto-watch, manual intervention required",
                            failedSet);
                    alertManager.sendAlert("SET " + failedSet + " 24小时未自动恢复，需人工介入");
                    watchingSet.remove(failedSet);
                    futureHolder[0].cancel(false);
                    return;
                }

                SetHealthStatus health = setHealthMonitor.getHealthStatus(failedSet);
                boolean healthy = health.getUnhealthyServiceRatio() < 0.05 && !health.isDatabaseUnreachable();

                if (healthy) {
                    int streak = healthyStreak.incrementAndGet();
                    log.info("SET {} healthy check streak={}", failedSet, streak);
                    if (streak >= 10) { // 连续10次（约10分钟）健康才认为真正恢复
                        recoverAsStandby(task);
                        watchingSet.remove(failedSet);
                        futureHolder[0].cancel(false);
                    }
                } else {
                    if (healthyStreak.get() > 0) {
                        log.warn("SET {} health streak reset due to flapping", failedSet);
                    }
                    healthyStreak.set(0);
                }
            } catch (Exception e) {
                log.error("Recovery watch check failed for {}", failedSet, e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 将恢复健康的原故障SET重新纳管为standbySet的备份角色，
     * 并重建数据同步链路，而不是立即抢回原有流量
     */
    private void recoverAsStandby(SetFailoverTask task) {
        String recoveredSet = task.getFailedSet();
        String currentPrimary = task.getStandbySet();

        log.warn("SET {} confirmed recovered, re-registering as standby for {}", recoveredSet, currentPrimary);

        try {
            // 1. 清空/校验恢复SET上的陈旧数据，避免脏数据污染同步
            dataSyncManager.resetForResync(recoveredSet);

            // 2. 重建从当前主SET到恢复SET的数据同步（单向，恢复SET追数据）
            dataSyncManager.startBidirectionalSync(currentPrimary, recoveredSet);

            // 3. 更新SET角色元数据：recoveredSet变为currentPrimary的备
            setMappingManager.updateSetRole(recoveredSet, SetRole.STANDBY_OF, currentPrimary);

            log.warn("SET {} re-registered as standby of {}, resync in progress", recoveredSet, currentPrimary);
            alertManager.sendAlert(String.format(
                    "故障SET %s 已恢复健康并重新纳管为 %s 的备SET，数据回补同步中", recoveredSet, currentPrimary));
        } catch (Exception e) {
            log.error("Failed to re-register recovered SET {} as standby", recoveredSet, e);
            alertManager.sendCriticalAlert("故障SET恢复纳管失败，需人工核实: " + recoveredSet);
        }
    }
}
```

#### 7.3.2 链路小结

| 阶段 | 关键动作 | 幂等/异常保障 |
|------|---------|---------------|
| 故障检测 | 多维度健康探测，连续N次失败才判定故障 | 按分钟粒度生成taskId去重，避免抖动重复触发 |
| 流量切走 | 分片粒度切换到备SET | 切换前检查当前路由指向，已切换分片自动跳过 |
| 备SET接管 | 提升备SET为主，接受写入 | 提升前检查是否已是主，避免重复提升 |
| 数据一致性修复 | 按业务唯一键UPSERT修复窗口期数据 | 直连读取失败自动降级到binlog归档兜底；未闭环记录进入人工核查队列 |
| 故障SET恢复 | 连续健康检查通过后重新纳管为备SET | 恢复前清空陈旧数据防止脏数据回流；24小时未恢复转人工 |

### 7.4 三个案例的共性设计原则

1. **状态机驱动**：扩容与容灾切换均采用持久化状态机（Phase）驱动，任务中断后可根据当前阶段续跑，避免重复执行已完成的步骤。
2. **幂等键先行**：无论是用户请求（requestId）、迁移任务（taskId+shardId）还是容灾任务（分钟粒度taskId），都在最开始就确定幂等键，并在数据写入层（唯一索引/UPSERT）做兜底。
3. **多级降级**：路由解析、跨SET调用、数据修复均设计了多级降级策略（本地缓存→中心兜底、直连读取→binlog归档），避免单点故障导致主链路阻塞。
4. **日志与告警贯穿全链路**：所有关键动作均携带traceId/taskId记录结构化日志，关键异常路径触发告警，便于问题定位和事后审计。
5. **验证与回滚闭环**：扩容和容灾切换在关键节点（流量切换后）都设计了验证步骤，验证失败时具备明确的回滚路径。</new_string>

