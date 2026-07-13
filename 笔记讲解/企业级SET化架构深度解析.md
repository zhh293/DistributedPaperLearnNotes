
## 十六、Oceanus网关层路由实现

Oceanus是美团的统一API网关，也是SET化流量路由的第一道关卡。所有外部请求（来自APP、小程序、H5）首先到达Oceanus，由Oceanus根据分片规则决定请求应该被路由到哪个SET。

### 16.1 Oceanus在SET化架构中的位置

```
Oceanus在SET化架构中的位置

  用户请求（APP/小程序/H5）
       │
       ▼
  ┌─────────┐
  │   DNS   │  ← 根据地理位置返回就近的Oceanus IP
  └────┬────┘
       │
       ▼
  ┌─────────┐
  │  CDN    │  ← 静态资源加速
  └────┬────┘
       │
       ▼
  ┌─────────┐
  │ Oceanus │  ← 网关层：SET路由决策
  │  网关   │     1. 解析请求参数
  └────┬────┘     2. 计算分片规则
       │         3. 路由到对应SET
       │
   ┌───┼───┐
   ▼   ▼   ▼
SET-A SET-B SET-C
  │     │     │
  ▼     ▼     ▼
内部服务链路...
```

### 16.2 DNS层面的路由

DNS是流量路由的最外层。美团的DNS解析会根据用户的地理位置返回就近的Oceanus集群IP。

**DNS路由策略**：

```
DNS基于地理位置的路由

北京用户 -> DNS解析 -> 北京机房Oceanus IP
上海用户 -> DNS解析 -> 上海机房Oceanus IP
深圳用户 -> DNS解析 -> 深圳机房Oceanus IP

特殊情况：
- 如果用户在北京，但北京机房故障，DNS可以返回上海机房IP（容灾切换）
- DNS支持基于运营商的路由（电信用户走电信线路）
```

**DNS配置示例**：

```nginx
; BIND DNS配置示例
; 基于地理位置的DNS解析（使用GeoDNS）

; 北京区域
gateway.sankuai.com IN A 10.1.1.1   ; 北京永丰机房

; 上海区域
gateway.sankuai.com IN A 10.2.1.1   ; 上海嘉定机房

; 深圳区域
gateway.sankuai.com IN A 10.3.1.1   ; 深圳南山机房

; 容灾：当某个区域故障时，可以通过DNS切换
; 例如北京故障时，北京用户的解析结果指向上海IP
```

**DNS路由的局限性**：
- DNS解析有缓存（TTL），切换时会有延迟
- 用户可能使用公共DNS（如8.8.8.8），地理位置不一定准确
- DNS只能做粗略的地域路由，不能做精细的SET路由（比如按用户ID路由）

因此，DNS只是"第一层引导"，精细的SET路由需要在Oceanus网关层完成。

### 16.3 Oceanus网关层的SET路由

Oceanus网关层是SET路由的核心决策点。它根据请求中的信息（如用户ID、城市ID、设备ID）计算分片规则，然后将请求转发到对应的SET。

**Oceanus路由处理流程**：

```
Oceanus路由处理流程

请求进入
  │
  ▼
┌─────────────────┐
│ 1. 解析请求参数  │  ← 从Header、Cookie、Body、URL参数中提取路由信息
│   (UID, CityID,  │
│    DeviceID等)   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 2. 路由规则匹配  │  ← 根据URL Path、AppKey匹配路由规则
│   (规则引擎)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 3. 计算分片规则  │  ← 根据配置的分片算法计算SET ID
│   (SET ID)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ 4. SET可用性检查 │  ← 检查目标SET是否健康可用
│   (健康检查)     │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
健康可用    不可用
    │         │
    ▼         ▼
转发请求   Fallback到
到目标SET  互备SET
```

**Oceanus路由配置示例**：

```yaml
# Oceanus SET路由配置（简化示例）
routers:
  - name: waimai_order_router
    # 匹配条件：外卖订单相关API
    match:
      path: /api/waimai/order/**
      appKey: com.sankuai.waimai
    
    # 路由策略
    route:
      # 分片维度：用户ID
      shardKey: userId
      
      # 分片算法：取模
      algorithm: modulo
      shardCount: 8
      
      # SET映射：每2个分片对应1个SET
      setMapping:
        - setId: "SET-BJ-01"
          shards: [0, 1]
          endpoints: ["http://10.1.1.10:8080", "http://10.1.1.11:8080"]
        - setId: "SET-SH-01"
          shards: [2, 3]
          endpoints: ["http://10.2.1.10:8080", "http://10.2.1.11:8080"]
        - setId: "SET-SZ-01"
          shards: [4, 5]
          endpoints: ["http://10.3.1.10:8080", "http://10.3.1.11:8080"]
        - setId: "SET-CD-01"
          shards: [6, 7]
          endpoints: ["http://10.4.1.10:8080", "http://10.4.1.11:8080"]
    
    # Fallback策略
    fallback:
      enabled: true
      # 当SET不可用时，路由到互备SET
      backupSet:
        "SET-BJ-01": "SET-SH-01"
        "SET-SH-01": "SET-BJ-01"
        "SET-SZ-01": "SET-CD-01"
        "SET-CD-01": "SET-SZ-01"

  - name: waimai_lbs_router
    # 到家场景的LBS路由
    match:
      path: /api/waimai/delivery/**
    route:
      # 分片维度：城市ID
      shardKey: cityId
      # 使用映射表
      algorithm: lookup
      mapping:
        110000: "SET-BJ-01"   # 北京
        310000: "SET-SH-01"   # 上海
        440300: "SET-SZ-01"   # 深圳
        510100: "SET-CD-01"   # 成都
```

### 16.4 UID路由（用户ID路由）

UID路由是金服场景的核心路由方式。请求中带有付款方ID（用户ID），Oceanus根据UID计算分片并路由到对应SET。

**UID路由的实现**：

```java
@Component
public class UidSetRouter implements SetRouter {
    
    @Autowired
    private SetConfiguration setConfig;
    
    @Override
    public RouteResult route(HttpRequest request) {
        // 1. 从请求中提取UID
        String uidStr = extractUid(request);
        if (uidStr == null) {
            throw new RouteException("Missing UID in request");
        }
        long uid = Long.parseLong(uidStr);
        
        // 2. 计算分片ID
        int shardCount = setConfig.getShardCount();
        int shardId = (int) (Math.abs(uid) % shardCount);
        
        // 3. 根据分片ID查找SET
        String setId = setConfig.getSetByShardId(shardId);
        
        // 4. 检查SET健康状态
        if (!setConfig.isSetHealthy(setId)) {
            // SET不健康，fallback到互备SET
            String backupSetId = setConfig.getBackupSet(setId);
            log.warn("SET {} is unhealthy, fallback to {}", setId, backupSetId);
            setId = backupSetId;
        }
        
        // 5. 返回路由结果
        return RouteResult.builder()
            .setId(setId)
            .shardId(shardId)
            .endpoint(setConfig.getEndpoint(setId))
            .build();
    }
    
    private String extractUid(HttpRequest request) {
        // 优先从Header获取
        String uid = request.getHeader("X-User-Id");
        if (uid != null) return uid;
        
        // 从Cookie获取
        uid = request.getCookie("uid");
        if (uid != null) return uid;
        
        // 从Body/Query参数获取
        uid = request.getParameter("userId");
        if (uid != null) return uid;
        
        // 从Token解析
        String token = request.getHeader("Authorization");
        if (token != null) {
            return parseUidFromToken(token);
        }
        
        return null;
    }
}
```

### 16.5 自定义路由规则

除了标准的UID和LBS路由，Oceanus还支持业务自定义路由规则。业务方可以通过配置或代码注入自定义的路由逻辑。

**自定义路由规则示例**（到家场景的骑手路由）：

```java
/**
 * 骑手路由规则：根据骑手当前所在城市路由到对应SET
 * 骑手的城市可能动态变化（跨城配送场景），需要特殊处理
 */
@Component
public class RiderSetRouter implements CustomSetRouter {
    
    @Autowired
    private RiderLocationService riderLocationService;
    
    @Override
    public RouteResult route(HttpRequest request) {
        String riderId = request.getParameter("riderId");
        
        // 查询骑手当前位置
        RiderLocation location = riderLocationService.getRiderLocation(riderId);
        
        // 根据当前位置的城市ID路由
        int cityId = location.getCityId();
        String setId = cityToSetMapping.get(cityId);
        
        return RouteResult.builder()
            .setId(setId)
            .shardId(cityId)
            .build();
    }
}
```

### 16.6 路由纠错机制

路由纠错是SET化架构的关键短板。理想状态下，当请求被错误路由到不匹配的SET时，系统应该能够检测并纠正。

**路由纠错的实现思路**：

```
全链路路由校验

请求 -> Oceanus -> 网关校验
                  │
                  ▼
            ┌──────────┐
            │ 路由校验  │  ← 校验UID与SET是否匹配
            │ 拦截器   │
            └────┬────┘
                 │
            ┌────┴────┐
            ▼         ▼
         匹配       不匹配
            │         │
            ▼         ▼
         继续处理   纠正路由
                     │
                     ▼
               重定向到正确SET
                     │
                     ▼
               下游服务再次校验
                     │
                     ▼
               记录纠错日志
```

**Oceanus路由校验代码示例**：

```java
@Component
public class RouteValidateFilter implements GatewayFilter {
    
    @Override
    public void filter(HttpRequest request, HttpResponse response, FilterChain chain) {
        // 1. 获取请求中的UID
        String uidStr = request.getHeader("X-User-Id");
        if (uidStr == null) {
            chain.doFilter(request, response);
            return;
        }
        long uid = Long.parseLong(uidStr);
        
        // 2. 计算UID应该路由到的SET
        int expectedShardId = (int) (Math.abs(uid) % 8);
        String expectedSetId = setConfig.getSetByShardId(expectedShardId);
        
        // 3. 获取当前请求实际路由到的SET
        String actualSetId = request.getHeader("X-Target-Set");
        
        // 4. 校验
        if (!expectedSetId.equals(actualSetId)) {
            log.error("Route mismatch! UID={} should go to SET={}, but routed to SET={}", 
                uid, expectedSetId, actualSetId);
            
            // 5. 纠正路由
            request.setHeader("X-Target-Set", expectedSetId);
            request.setHeader("X-Route-Corrected", "true");
            
            // 6. 记录监控
            metrics.counter("route_correction").tag("from", actualSetId)
                                              .tag("to", expectedSetId)
                                              .increment();
        }
        
        chain.doFilter(request, response);
    }
}
```

### 16.7 LBS路由详解

到家业务的LBS路由是SET化路由中最复杂的场景之一。它需要考虑用户位置、商家位置、骑手位置的多重关系。

**LBS路由的决策因素**：

| 因素 | 说明 | 优先级 |
|------|------|--------|
| 用户城市 | 用户当前所在城市 | 最高 |
| 用户GPS | 用户精确经纬度 | 高 |
| 商家城市 | 商家所在城市 | 中 |
| 历史订单 | 用户历史订单的城市 | 低 |

**LBS路由代码示例**：

```java
@Component
public class LbsSetRouter {
    
    public String routeByLocation(HttpRequest request) {
        // 1. 优先使用用户城市ID
        Integer cityId = request.getIntParameter("cityId");
        if (cityId != null && cityToSetMapping.containsKey(cityId)) {
            return cityToSetMapping.get(cityId);
        }
        
        // 2. 使用GPS坐标反查城市
        String lat = request.getParameter("lat");
        String lng = request.getParameter("lng");
        if (lat != null && lng != null) {
            cityId = geoService.getCityByCoordinate(Double.parseDouble(lat), 
                                                     Double.parseDouble(lng));
            if (cityId != null && cityToSetMapping.containsKey(cityId)) {
                return cityToSetMapping.get(cityId);
            }
        }
        
        // 3. 使用用户ID兜底（保证路由到某个SET）
        String uid = request.getHeader("X-User-Id");
        if (uid != null) {
            return routeByUid(Long.parseLong(uid));
        }
        
        // 4. 默认路由到中心SET
        return "SET-CENTER";
    }
}
```

## 十七、OCTO RPC框架路由实现

OCTO是美团自研的RPC框架，基于MTThrift扩展。在SET化架构中，OCTO承担了服务间调用的路由职责——确保RPC调用优先在同一个SET内完成。

### 17.1 OCTO在SET化架构中的角色

```
OCTO RPC路由在SET化架构中的位置

SET-A内部的服务调用链

用户请求
  │
  ▼
┌──────────┐
│ 服务A    │  ←  Oceanus已将请求路由到SET-A
│ (Gateway)│     服务A带有SET-A的标签
└────┬─────┘
     │
     │ OCTO RPC调用
     │ 服务A -> 服务B
     ▼
┌──────────┐
│ 服务B    │  ← OCTO路由发现：优先找SET-A内的服务B实例
│ (Order)  │     如果SET-A内有服务B实例，就在SET-A内调用
└────┬─────┘     如果SET-A内没有，就fallback到中心集群
     │
     │ OCTO RPC调用
     │ 服务B -> 服务C
     ▼
┌──────────┐
│ 服务C    │  ← 同样在SET-A内调用
│ (Payment)│
└────┬─────┘
     │
     ▼
    DB (SET-A内的数据库)
```

### 17.2 OCTO的标签路由机制

OCTO通过给服务实例打标签（Tags）来实现路由。每个服务实例在注册到OCTO时，会带上自己的标签信息。

**服务实例标签示例**：

```java
// 服务启动时注册到OCTO，带上SET标签
@Service
public class OrderServiceBootstrap {
    
    @Value("${set.id:SET-CENTER}")
    private String setId;
    
    @Value("${set.flowTagId:}")
    private String flowTagId;
    
    @Value("${set.groupTags:}")
    private String groupTags;
    
    @PostConstruct
    public void registerService() {
        ServiceInstance instance = ServiceInstance.builder()
            .serviceName("order-service")
            .ip(getLocalIp())
            .port(8080)
            .tags(Tags.builder()
                .set("SET", setId)           // SET标签
                .set("FLOW_TAG", flowTagId)   // LiteSET标签
                .set("GROUP_TAGS", groupTags) // 业务分组标签
                .build())
            .build();
        
        consulRegistry.register(instance);
    }
}
```

**OCTO注册中心的服务实例数据结构**：

```json
{
  "serviceName": "order-service",
  "instances": [
    {
      "ip": "10.1.1.10",
      "port": 8080,
      "weight": 100,
      "tags": {
        "SET": "SET-BJ-01",
        "FLOW_TAG": "",
        "GROUP_TAGS": "",
        "REGION": "BJ",
        "AZ": "YF"
      },
      "healthStatus": "HEALTHY"
    },
    {
      "ip": "10.1.1.11",
      "port": 8080,
      "weight": 100,
      "tags": {
        "SET": "SET-BJ-01",
        "FLOW_TAG": "",
        "GROUP_TAGS": "",
        "REGION": "BJ",
        "AZ": "YF"
      },
      "healthStatus": "HEALTHY"
    },
    {
      "ip": "10.2.1.10",
      "port": 8080,
      "weight": 100,
      "tags": {
        "SET": "SET-SH-01",
        "FLOW_TAG": "",
        "GROUP_TAGS": "",
        "REGION": "SH",
        "AZ": "JD"
      },
      "healthStatus": "HEALTHY"
    }
  ]
}
```

### 17.3 SET标签路由的默认规则

OCTO的默认路由规则是：优先路由到与当前请求所在SET标签相同的服务节点。

**路由规则实现**：

```java
public class SetTagRouter implements Router {
    
    @Override
    public List<Invoker> route(List<Invoker> invokers, Invocation invocation) {
        // 1. 从请求上下文中获取当前SET标签
        String currentSet = RpcContext.getContext().getAttachment("SET");
        
        if (currentSet == null || currentSet.isEmpty()) {
            // 没有SET标签，返回所有可用节点（中心集群调用）
            return filterHealthy(invokers);
        }
        
        // 2. 优先选择同SET的实例
        List<Invoker> sameSetInvokers = invokers.stream()
            .filter(invoker -> currentSet.equals(invoker.getTag("SET")))
            .filter(invoker -> invoker.isHealthy())
            .collect(Collectors.toList());
        
        if (!sameSetInvokers.isEmpty()) {
            return sameSetInvokers;
        }
        
        // 3. 同SET没有可用实例，fallback到中心集群
        List<Invoker> centerInvokers = invokers.stream()
            .filter(invoker -> "SET-CENTER".equals(invoker.getTag("SET")))
            .filter(invoker -> invoker.isHealthy())
            .collect(Collectors.toList());
        
        if (!centerInvokers.isEmpty()) {
            log.warn("No available invokers in SET {}, fallback to SET-CENTER", currentSet);
            return centerInvokers;
        }
        
        // 4. 实在没有可用实例，返回所有健康实例（兜底）
        return filterHealthy(invokers);
    }
}
```

### 17.4 权重路由

OCTO支持基于权重的路由，这在灰度发布和流量控制时非常有用。

**权重路由的应用场景**：
- **灰度发布**：新版本服务实例权重设为10%，老版本90%，逐步切换
- **SET流量调整**：某个SET容量不足，减少该SET的权重，让其他SET分担
- **机房流量调整**：某个机房网络不稳定，降低该机房的权重

**权重路由配置**：

```yaml
# OCTO权重路由配置
service:
  name: order-service
  
  # 实例权重配置
  instances:
    - ip: 10.1.1.10
      port: 8080
      weight: 100
      tags:
        SET: "SET-BJ-01"
        version: "v1.0"
    
    - ip: 10.1.1.11
      port: 8080
      weight: 100
      tags:
        SET: "SET-BJ-01"
        version: "v1.0"
    
    - ip: 10.1.1.20
      port: 8080
      weight: 10    # 新版本权重低，灰度发布
      tags:
        SET: "SET-BJ-01"
        version: "v2.0"  # 新版本

# 动态权重调整（通过配置中心下发）
routeRules:
  - match:
      SET: "SET-BJ-01"
    weight: 80   # 将北京SET权重降到80%，其他SET分担20%
  - match:
      SET: "SET-SH-01"
    weight: 120  # 上海SET权重提高到120%
```

**权重路由算法**：

```java
public class WeightedRouter {
    
    public Invoker select(List<Invoker> invokers, Invocation invocation) {
        // 计算总权重
        int totalWeight = 0;
        for (Invoker invoker : invokers) {
            totalWeight += invoker.getWeight();
        }
        
        // 随机选择（基于权重）
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        
        int currentWeight = 0;
        for (Invoker invoker : invokers) {
            currentWeight += invoker.getWeight();
            if (random < currentWeight) {
                return invoker;
            }
        }
        
        return invokers.get(invokers.size() - 1);
    }
}
```

### 17.5 Fallback机制

Fallback是SET化路由中的关键机制。当同SET内没有可用的服务实例时，OCTO需要决定如何fallback。

**Fallback策略优先级**：

```
Fallback策略（按优先级排序）

1. 同SET调用（最高优先级）
   └── 同SET内有可用实例？
       ├── 是 -> 调用同SET实例
       └── 否 -> 进入Fallback

2. 同LiteSET调用（次高优先级）
   └── 同LiteSET内有可用实例？
       ├── 是 -> 调用同LiteSET实例
       └── 否 -> 继续Fallback

3. 同业务分组调用
   └── 同业务分组内有可用实例？
       ├── 是 -> 调用同业务分组实例
       └── 否 -> 继续Fallback

4. 同城市/同机房调用（物理路由）
   └── 同城/同机房有可用实例？
       ├── 是 -> 调用同城/同机房实例
       └── 否 -> 继续Fallback

5. 中心集群调用（SET-CENTER）
   └── 中心集群有可用实例？
       ├── 是 -> 调用中心集群实例
       └── 否 -> 继续Fallback

6. 任意可用实例（兜底）
   └── 返回所有健康实例
```

**Fallback配置示例**：

```java
@Configuration
public class OCTOFallbackConfig {
    
    @Bean
    public FallbackChain fallbackChain() {
        return FallbackChain.builder()
            .addFallback(new SameSetFallback())      // 同SET
            .addFallback(new SameFlowTagFallback())   // 同LiteSET
            .addFallback(new SameGroupFallback())     // 同业务分组
            .addFallback(new SameRegionFallback())    // 同城
            .addFallback(new SameAzFallback())        // 同机房
            .addFallback(new CenterSetFallback())     // 中心集群
            .addFallback(new AnyAvailableFallback())  // 兜底
            .build();
    }
}
```

### 17.6 拦截器链（Interceptor Chain）

OCTO提供了拦截器链机制，允许业务方在RPC调用的不同阶段插入自定义逻辑。

**拦截器链的调用阶段**：

```
RPC调用拦截器链

请求发送端
  │
  ├─► 1. 请求构建拦截器（Request Build）
  │      └── 设置SET标签、染色标记、TraceID等
  │
  ├─► 2. 路由选择拦截器（Route Select）
  │      └── 自定义路由逻辑、灰度逻辑、AB测试逻辑
  │
  ├─► 3. 负载均衡拦截器（Load Balance）
  │      └── 加权随机、一致性哈希、最少连接等
  │
  ├─► 4. 请求发送拦截器（Request Send）
  │      └── 限流、熔断、超时设置、重试逻辑
  │
  ▼
网络传输
  │
  ▼
请求接收端
  │
  ├─► 5. 请求接收拦截器（Request Receive）
  │      └── 鉴权、权限校验、请求解码
  │
  ├─► 6. 业务执行拦截器（Business Execute）
  │      └── 业务逻辑
  │
  ├─► 7. 响应发送拦截器（Response Send）
  │      └── 响应编码、监控打点
  │
  ▼
返回响应
```

**SET标签透传拦截器实现**：

```java
/**
 * SET标签透传拦截器
 * 确保请求中的SET标签在整个RPC调用链中传递
 */
@Component
public class SetTagPassThroughInterceptor implements RequestInterceptor {
    
    @Override
    public void intercept(Request request, Invocation invocation) {
        // 1. 从当前RPC上下文中获取SET标签
        String currentSet = RpcContext.getContext().getAttachment("SET");
        
        if (currentSet != null) {
            // 2. 将SET标签写入请求的Attachment中，随RPC调用传递到下游
            request.setAttachment("SET", currentSet);
            
            // 3. 同时设置LiteSET标签
            String flowTag = RpcContext.getContext().getAttachment("FLOW_TAG");
            if (flowTag != null) {
                request.setAttachment("FLOW_TAG", flowTag);
            }
            
            // 4. 记录路由日志
            if (log.isDebugEnabled()) {
                log.debug("Passing SET tag to downstream: set={}, service={}, method={}", 
                    currentSet, 
                    invocation.getServiceName(), 
                    invocation.getMethodName());
            }
        }
    }
}
```

**路由校验拦截器**：

```java
/**
 * 路由校验拦截器
 * 在请求接收端校验：请求是否被路由到了正确的SET
 */
@Component
public class RouteValidateInterceptor implements ReceiveInterceptor {
    
    @Value("${set.id}")
    private String localSetId;
    
    @Override
    public void intercept(Request request, Invocation invocation) {
        // 1. 获取请求中的目标SET标签
        String targetSet = request.getAttachment("SET");
        
        if (targetSet == null) {
            // 没有SET标签，可能是中心集群调用，允许通过
            return;
        }
        
        // 2. 校验：请求中的SET标签是否与本机SET一致
        if (!targetSet.equals(localSetId)) {
            log.error("Route validation failed! Request SET={}, but local SET={}. " +
                "Service={}, Method={}, Client={}", 
                targetSet, localSetId,
                invocation.getServiceName(),
                invocation.getMethodName(),
                request.getClientIp());
            
            // 3. 记录监控指标
            metrics.counter("route_validate_error")
                .tag("expected_set", targetSet)
                .tag("actual_set", localSetId)
                .increment();
            
            // 4. 根据策略决定是否拒绝请求
            if (isStrictMode()) {
                throw new RouteException("Route mismatch: request SET=" + targetSet 
                    + ", but local SET=" + localSetId);
            }
        }
    }
}
```

### 17.7 泳道路由

泳道（Swimlane）是SET化架构中的逻辑隔离机制，与LiteSET类似但定位不同。泳道用于全链路灰度发布、压测、业务隔离。

**泳道路由的实现**：

```java
/**
 * 泳道路由拦截器
 * 根据请求中的泳道标记，路由到对应泳道的服务实例
 */
@Component
public class SwimlaneRouterInterceptor implements RouteInterceptor {
    
    @Override
    public List<Invoker> intercept(List<Invoker> invokers, Invocation invocation) {
        // 1. 获取请求中的泳道标记
        String swimlane = RpcContext.getContext().getAttachment("SWIMLANE");
        
        if (swimlane == null || swimlane.isEmpty()) {
            // 没有泳道标记，不干预路由
            return invokers;
        }
        
        // 2. 优先选择同泳道的实例
        List<Invoker> swimlaneInvokers = invokers.stream()
            .filter(invoker -> swimlane.equals(invoker.getTag("SWIMLANE")))
            .filter(invoker -> invoker.isHealthy())
            .collect(Collectors.toList());
        
        if (!swimlaneInvokers.isEmpty()) {
            return swimlaneInvokers;
        }
        
        // 3. 泳道内没有可用实例，fallback到同SET
        String currentSet = RpcContext.getContext().getAttachment("SET");
        if (currentSet != null) {
            List<Invoker> setInvokers = invokers.stream()
                .filter(invoker -> currentSet.equals(invoker.getTag("SET")))
                .filter(invoker -> invoker.isHealthy())
                .collect(Collectors.toList());
            
            if (!setInvokers.isEmpty()) {
                return setInvokers;
            }
        }
        
        return invokers;
    }
}
```

**泳道标记的传递方式**：

```java
// 在Oceanus网关层设置泳道标记
public class SwimlaneGatewayFilter implements GatewayFilter {
    
    @Override
    public void filter(HttpRequest request, HttpResponse response) {
        // 1. 根据规则判断请求是否属于某个泳道
        String swimlane = determineSwimlane(request);
        
        if (swimlane != null) {
            // 2. 设置泳道标记到HTTP Header
            request.setHeader("X-Swimlane", swimlane);
            
            // 3. 设置到RPC上下文中，确保后续RPC调用都能获取到
            RpcContext.getContext().setAttachment("SWIMLANE", swimlane);
            
            // 4. 记录日志
            log.info("Request assigned to swimlane: {}", swimlane);
        }
    }
    
    private String determineSwimlane(HttpRequest request) {
        // 规则1：根据用户ID尾号（灰度发布）
        String uid = request.getHeader("X-User-Id");
        if (uid != null) {
            long uidLong = Long.parseLong(uid);
            if (uidLong % 100 < 5) {  // 5%的用户进入灰度泳道
                return "swimlane-gray-5pct";
            }
        }
        
        // 规则2：根据Header中的标记（压测场景）
        String testTag = request.getHeader("X-Test-Tag");
        if ("pressure-test".equals(testTag)) {
            return "swimlane-pressure-test";
        }
        
        // 规则3：根据业务线
        String bizLine = request.getHeader("X-Biz-Line");
        if (bizLine != null) {
            return "swimlane-" + bizLine;
        }
        
        return null;
    }
}
```

## 十八、数据库SET化方案

数据库是SET化架构中最复杂的部分。数据必须按分片维度切分，每个SET拥有独立的数据库集群，SET之间需要数据同步。这一节详细讲解美团的数据库SET化方案。

### 18.1 数据库SET化的核心挑战

```
数据库SET化的核心挑战

┌─────────────────────────────────────────────────────┐
│ 1. 数据分片：如何按维度把数据切分到不同SET的数据库？      │
│ 2. 跨SET同步：SET之间如何同步数据，保证容灾互备？         │
│ 3. 读写一致性：SET内读写一致性 vs SET间最终一致性          │
│ 4. 主从切换：容灾时如何快速切换主库？                   │
│ 5. 扩展性：新增SET时如何迁移数据？                      │
│ 6. 全局ID：跨SET的分布式ID如何生成？                    │
└─────────────────────────────────────────────────────┘
```

### 18.2 Zebra分库分表

Zebra是美团自研的数据库中间件，基于MySQL协议，提供分库分表、读写分离、SQL解析、动态数据源切换等能力。

**Zebra在SET化架构中的角色**：

```
Zebra在SET化架构中的位置

应用层
  │
  │ 标准SQL
  ▼
┌─────────┐
│  Zebra  │  ← 数据库中间件
│ 中间件  │     1. SQL解析
└────┬────┘     2. 路由计算（根据分片键）
     │         3. SQL改写（分库分表后的表名）
     │         4. 连接池管理
     ▼         5. 结果集合并
  MySQL协议
     │
     ▼
┌─────────┐
│  SET-A  │     ┌─────────┐
│  DB集群  │     │  SET-B  │
│  (主+从) │     │  DB集群  │
└─────────┘     └─────────┘
```

**Zebra分库分表配置示例**：

```xml
<!-- Zebra数据源配置 -->
<bean id="shardingDataSource" class="com.sankuai.Zebra.ds.ZebraDataSource">
    <property name="refKey" value="order_db_set_a" />
    <property name="ruleFile" value="classpath:Zebra-rule.xml" />
</bean>

<!-- Zebra路由规则配置 (Zebra-rule.xml) -->
<router>
    <!-- 分库规则 -->
    <dbRule>
        <columns>user_id</columns>  <!-- 分库键 -->
        <expression>user_id % 8</expression>  <!-- 取模分库 -->
    </dbRule>
    
    <!-- 分表规则 -->
    <tableRule>
        <tableName>order</tableName>
        <columns>order_id</columns>  <!-- 分表键 -->
        <expression>order_id % 16</expression>  <!-- 取模分表 -->
    </tableRule>
    
    <!-- 数据源配置 -->
    <dataSources>
        <dataSource id="db0" master="jdbc:mysql://db0-master:3306/order" 
                    slave="jdbc:mysql://db0-slave:3306/order" />
        <dataSource id="db1" master="jdbc:mysql://db1-master:3306/order" 
                    slave="jdbc:mysql://db1-slave:3306/order" />
        <!-- ... db2-db7 -->
    </dataSources>
</router>
```

**Zebra的SQL路由示例**：

```sql
-- 业务代码中写的SQL（完全透明）
SELECT * FROM order WHERE user_id = 1234567 AND order_id = 8901234;

-- Zebra解析后的路由：
-- 1. 分库：user_id = 1234567 -> 1234567 % 8 = 7 -> db7
-- 2. 分表：order_id = 8901234 -> 8901234 % 16 = 2 -> order_2
-- 3. SQL改写：SELECT * FROM db7.order_2 WHERE user_id = 1234567 AND order_id = 8901234;

-- 插入操作同样透明
INSERT INTO order (user_id, order_id, status, amount) 
VALUES (1234567, 8901234, 'CREATED', 100.00);
-- Zebra自动路由到db7.order_2

-- 跨分片查询（Zebra自动处理）
SELECT * FROM order WHERE user_id IN (1234567, 7654321);
-- 1234567 -> db7, 7654321 -> 7654321 % 8 = 1 -> db1
-- Zebra会并行查询db7和db1，然后合并结果
```

**Zebra的SET化支持**：

Zebra在SET化架构中支持动态数据源切换。当应用从一个SET切换到另一个SET时（容灾切换），Zebra可以通过配置中心动态切换数据源，不需要重启应用。

```java
// Zebra动态数据源切换
public class ZebraSetSwitchService {
    
    @Autowired
    private ZebraDataSource shardingDataSource;
    
    /**
     * SET容灾切换时，切换数据源
     */
    public void switchDataSource(String newSetId) {
        // 从配置中心获取新SET的数据源配置
        DataSourceConfig config = configCenter.getDataSourceConfig(newSetId);
        
        // 动态切换Zebra数据源
        shardingDataSource.updateDataSource(config);
        
        log.info("Zebra data source switched to SET: {}", newSetId);
    }
}
```

### 18.3 TiDB联邦方案

除了传统的MySQL分库分表，美团部分业务也在探索使用TiDB作为SET化的数据库方案。

**TiDB在SET化中的优势**：
- 原生分布式，不需要分库分表中间件
- 支持跨区域部署（Region级别的数据分布）
- 自动水平扩展，添加节点即可扩展容量
- 强一致性保证（Raft协议）

**TiDB联邦架构**：

```
TiDB联邦架构（SET化）

        SET-A（北京）                SET-B（上海）
        ┌─────────────┐             ┌─────────────┐
        │  TiDB Server │             │  TiDB Server │
        │   (SQL层)   │             │   (SQL层)   │
        └──────┬──────┘             └──────┬──────┘
               │                           │
        ┌──────┴──────┐             ┌──────┴──────┐
        ▼             ▼             ▼             ▼
   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
   │TiKV Region│   │TiKV Region│   │TiKV Region│   │TiKV Region│
   │ (北京数据) │   │ (上海数据副本)│   │ (北京数据副本)│   │ (上海数据) │
   │  Leader  │   │  Follower │   │  Follower │   │  Leader  │
   └─────────┘   └─────────┘   └─────────┘   └─────────┘
        │                           │
        └──────────┬────────────────┘
                   │
              ┌─────────┐
              │   PD    │  ← Placement Driver，全局调度
              │ (元数据) │
              └─────────┘
```

**TiDB的Placement Rules配置**：

```sql
-- TiDB Placement Rules：控制数据在哪些节点/区域存储
-- 实现SET化的数据分布

-- 为北京数据配置Leader在北京，Follower在上海
CREATE PLACEMENT POLICY set_a_policy PRIMARY_REGION="bj" REGIONS="bj,sh";

-- 为上海数据配置Leader在上海，Follower在北京
CREATE PLACEMENT POLICY set_b_policy PRIMARY_REGION="sh" REGIONS="sh,bj";

-- 创建表时指定Placement Policy
CREATE TABLE order_bj (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT,
    ...
) PLACEMENT POLICY set_a_policy;

CREATE TABLE order_sh (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT,
    ...
) PLACEMENT POLICY set_b_policy;
```

**TiDB方案的局限性**：
- TiDB跨区域延迟较大（Raft协议需要多数派确认），不适合对延迟敏感的在线交易场景
- 目前主要用于分析型业务和次要业务，核心交易链路仍以MySQL为主
- SET间数据隔离不如MySQL硬分片彻底

### 18.4 MGR跨机房复制

MGR（MySQL Group Replication）是MySQL官方提供的组复制协议，支持多主写入和强一致性。金服在SET化中采用了MGR方案。

**MGR的核心特性**：
- **多主模式**：多个节点可以同时写入
- **强一致性**：基于Paxos的组通信，确保数据一致性
- **自动故障检测**：节点故障自动检测并移除
- **自动恢复**：故障节点恢复后自动重新加入组

**MGR在SET化中的应用**：

```
MGR跨机房复制（金服同城场景）

        北京Region
        ┌─────────────────────────────────────┐
        │                                     │
        │  ┌─────────┐      ┌─────────┐      │
        │  │ MGR节点1 │◄────►│ MGR节点2 │      │  ← 同城多主
        │  │ (AZ-YF) │      │ (AZ-ZF) │      │
        │  │  Primary │      │  Primary │      │
        │  └────┬────┘      └────┬────┘      │
        │       │                │            │
        │       └──────┬─────────┘            │
        │              │                       │
        │              ▼                       │
        │         ┌─────────┐                  │
        │         │  MGR组   │                  │
        │         │(Group)  │                  │
        │         └─────────┘                  │
        │                                     │
        └─────────────────────────────────────┘
        
        跨Region同步（DTS）
        
        上海Region
        ┌─────────────────────────────────────┐
        │                                     │
        │  ┌─────────┐      ┌─────────┐      │
        │  │ MGR节点3 │◄────►│ MGR节点4 │      │
        │  │ (AZ-JD) │      │ (AZ-PJ) │      │
        │  │  Primary │      │  Primary │      │
        │  └─────────┘      └─────────┘      │
        │                                     │
        └─────────────────────────────────────┘
```

**MGR配置示例**：

```ini
# my.cnf MGR配置

[mysqld]
# Group Replication配置
server_id = 1
binlog_format = ROW
log_bin = mysql-bin
binlog_checksum = CRC32

plugin_load_add = group_replication.so
group_replication_group_name = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
group_replication_start_on_boot = off
group_replication_local_address = "10.1.1.10:33061"
group_replication_group_seeds = "10.1.1.10:33061,10.1.1.11:33061,10.2.1.10:33061"
group_replication_bootstrap_group = off

# 多主模式
group_replication_single_primary_mode = OFF
group_replication_enforce_update_everywhere_checks = ON
```

**MGR vs 传统主从复制**：

| 特性 | 传统主从复制 | MGR |
|------|------------|-----|
| 写入节点 | 单主 | 多主（可选） |
| 一致性 | 异步/半同步，可能丢数据 | 组内强一致 |
| 故障切换 | 手动或MHA | 自动 |
| 延迟 | 异步复制，延迟不确定 | 组通信确认，延迟可控 |
| 性能 | 高 | 略低（需要组通信确认） |
| 适用场景 | 读多写少 | 写多、强一致性要求 |

### 18.5 主从切换机制

主从切换是容灾的关键环节。SET化架构中，当主SET故障时，需要快速将互备SET提升为主SET。

**主从切换的触发条件**：
- 主SET的数据库主库故障（硬件故障、网络故障、数据损坏）
- 主SET的机房故障（城市级故障）
- 主动演练（断网演练）

**主从切换流程**：

```
主从切换流程（RTO目标：2-3分钟）

T+0s    检测到主SET故障
        │
        ▼
T+10s   确认故障（避免误切换）
        │
        ▼
T+20s   发送停写指令（停止主SET的写入）
        │
        ▼
T+30s   确认停写完成（或强制停写）
        │
        ▼
T+60s   检查数据同步状态（确认RPO）
        │  └── 如果同步延迟 < 目标RPO，继续切换
        │  └── 如果同步延迟 > 目标RPO，决策是否继续
        │
        ▼
T+90s   将互备SET提升为主SET
        │  └── 切换数据库主从关系
        │  └── 切换Oceanus路由
        │  └── 切换OCTO服务标签
        │
        ▼
T+120s  验证切换结果
        │  └── 检查新主SET是否健康
        │  └── 检查业务流量是否恢复
        │
        ▼
T+180s  切换完成，恢复业务写入
```

**主从切换脚本示例**：

```bash
#!/bin/bash
# SET主从切换脚本

SET_ID=$1          # 故障SET ID
BACKUP_SET_ID=$2   # 互备SET ID

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# 1. 停写（主SET）
log "Step 1: Stop write on SET ${SET_ID}"
curl -X POST "http://gateway-admin/api/v1/set/${SET_ID}/stop-write"

# 2. 等待停写完成
log "Step 2: Wait for write stop"
sleep 30

# 3. 检查数据同步延迟
log "Step 3: Check replication lag"
LAG=$(mysql -h ${BACKUP_SET_ID}-db-master -e "SHOW SLAVE STATUS\G" | grep "Seconds_Behind_Master" | awk '{print $2}')
log "Replication lag: ${LAG} seconds"

if [ "$LAG" -gt "30" ]; then
    log "WARNING: Replication lag > 30s, may lose data"
    # 发送告警，等待人工确认
fi

# 4. 提升互备SET为主
log "Step 4: Promote backup SET ${BACKUP_SET_ID} to primary"

# 4.1 停止从库复制
mysql -h ${BACKUP_SET_ID}-db-master -e "STOP SLAVE;"

# 4.2 重置从库状态
mysql -h ${BACKUP_SET_ID}-db-master -e "RESET SLAVE ALL;"

# 4.3 将互备SET数据库设为主库
mysql -h ${BACKUP_SET_ID}-db-master -e "SET GLOBAL read_only = OFF;"

# 4.4 切换Oceanus路由
log "Step 4.4: Switch Oceanus route"
curl -X POST "http://gateway-admin/api/v1/route/switch" \
    -H "Content-Type: application/json" \
    -d "{\"from_set\":\"${SET_ID}\",\"to_set\":\"${BACKUP_SET_ID}\"}"

# 4.5 切换OCTO服务标签
log "Step 4.5: Switch OCTO service tags"
curl -X POST "http://consul-admin/api/v1/service/switch-set" \
    -H "Content-Type: application/json" \
    -d "{\"from_set\":\"${SET_ID}\",\"to_set\":\"${BACKUP_SET_ID}\"}"

# 5. 验证
log "Step 5: Verify switch"
sleep 30

# 5.1 检查数据库可写
mysql -h ${BACKUP_SET_ID}-db-master -e "CREATE TABLE __switch_test (id INT); DROP TABLE __switch_test;"
if [ $? -eq 0 ]; then
    log "Database write test passed"
else
    log "ERROR: Database write test failed"
    exit 1
fi

# 5.2 检查业务流量
TRAFFIC=$(curl -s "http://monitor/api/v1/traffic?set=${BACKUP_SET_ID}&duration=1m" | jq '.qps')
log "Current traffic QPS: ${TRAFFIC}"

log "Switch completed: ${SET_ID} -> ${BACKUP_SET_ID}"
```

### 18.6 全局ID生成

SET化后，每个SET有独立的数据库，传统的自增ID会冲突。需要全局唯一的ID生成方案。

**美团的全局ID方案——Leaf**：

Leaf是美团开源的分布式ID生成系统，支持号段模式和Snowflake模式。

**Leaf号段模式**：

```java
// Leaf号段模式原理
// 1. 数据库中预分配号段
// 2. 应用从Leaf服务批量获取ID号段
// 3. 应用本地自增，用完后再获取下一段

// 数据库表
CREATE TABLE leaf_alloc (
    biz_tag VARCHAR(128) NOT NULL PRIMARY KEY,  -- 业务标签
    max_id BIGINT NOT NULL,                       -- 当前最大ID
    step INT NOT NULL,                            -- 步长（号段大小）
    description VARCHAR(256)
);

// 初始化
INSERT INTO leaf_alloc (biz_tag, max_id, step, description) 
VALUES ('order_id', 0, 1000, '订单ID');

// 每次获取号段时：
// UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = 'order_id';
// 返回 max_id - step 到 max_id 之间的号段
```

**Leaf Snowflake模式**：

```java
// Leaf Snowflake模式（64位ID）
// 0 | 0000000000 0000000000 0000000000 0000000000 0 | 00000 | 00000 | 000000000000
// 符号位 | 41位时间戳（毫秒） | 5位数据中心ID | 5位机器ID | 12位序列号

// 在SET化架构中的适配：
// 数据中心ID = SET ID（例如SET-BJ-01 = 1, SET-SH-01 = 2）
// 机器ID = 服务实例ID（0-31）

public class SetLeafIdGenerator {
    
    private final long setId;      // SET ID（5位，0-31）
    private final long workerId;   // 机器ID（5位，0-31）
    
    public SetLeafIdGenerator(long setId, long workerId) {
        if (setId > 31 || setId < 0) {
            throw new IllegalArgumentException("setId can't be greater than 31 or less than 0");
        }
        if (workerId > 31 || workerId < 0) {
            throw new IllegalArgumentException("workerId can't be greater than 31 or less than 0");
        }
        this.setId = setId;
        this.workerId = workerId;
    }
    
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        
        // 组合ID
        return ((timestamp - START_TIMESTAMP) << 22)
            | (setId << 17)
            | (workerId << 12)
            | sequence;
    }
}

// 生成的ID结构：
// 高22位：时间戳差值
// 中间5位：SET ID（确保不同SET生成的ID不冲突）
// 中间5位：机器ID
// 低12位：序列号
```

**Leaf在SET化中的部署**：

```
Leaf部署（每个SET独立部署）

SET-A部署：
  Leaf Server -> SET-A的MySQL（leaf_alloc表）
  SET ID配置为1
  
SET-B部署：
  Leaf Server -> SET-B的MySQL（leaf_alloc表）
  SET ID配置为2

两个SET的Leaf独立运行，通过Snowflake的SET ID位保证全局唯一
```

---

## 十四、SET化架构中的全局ID生成

### 14.1 为什么SET化需要全局ID

在SET化架构中，数据被分散到多个SET中。如果每个SET独立生成自增ID，就会出现ID冲突的问题。例如：
- SET-A 的订单表生成了订单ID = 100001
- SET-B 的订单表也生成了订单ID = 100001

当这两个SET的数据需要聚合或同步时，ID冲突会导致严重问题。因此，SET化架构需要一个**全局唯一的ID生成方案**。

### 14.2 美团Leaf方案在SET化中的适配

美团内部使用 Leaf 作为全局ID生成服务。在SET化架构中，Leaf 的部署方式有两种：

**方案一：每个SET独立部署Leaf（推荐）**
- 每个SET内部署独立的 Leaf Server
- 通过 Snowflake 算法的 `setId` 位保证不同SET生成的ID全局唯一
- 优势：无单点，各SET自治，不依赖跨SET网络

**方案二：中心Leaf服务**
- 所有SET共享一个中心的 Leaf 服务集群
- 优势：ID连续性更好，管理更简单
- 劣势：中心服务成为瓶颈和单点，跨SET调用增加延迟

### 14.3 SET化全局ID的生成规则

```java
public class SetGlobalIdGenerator {
    
    // SET ID（每个SET唯一，由运维分配）
    private final long setId;
    
    // 机器ID（SET内唯一）
    private final long workerId;
    
    // 起始时间戳（2020-01-01）
    private static final long START_TIMESTAMP = 1577836800000L;
    
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        
        // 时间戳差值（22位，约支持48年）
        long timePart = (timestamp - START_TIMESTAMP) << 22;
        
        // SET ID（5位，支持32个SET）
        long setPart = (setId & 0x1F) << 17;
        
        // 机器ID（5位，支持32台机器）
        long workerPart = (workerId & 0x1F) << 12;
        
        // 序列号（12位，每毫秒4096个）
        long sequence = getSequence();
        
        return timePart | setPart | workerPart | sequence;
    }
}
```

生成的ID结构保证了：
- 时间戳在高位，ID天然有序
- SET ID在中间，不同SET的ID不会冲突
- 同一毫秒内，同一机器最多生成4096个ID

---

## 十五、SET化架构中的数据一致性挑战

### 15.1 跨SET数据一致性的本质困难

SET化架构将数据分散到多个SET中，每个SET独立处理自己的分片数据。这种设计在带来扩展性和容灾能力的同时，也引入了数据一致性的挑战。

**核心矛盾**：
- SET内需要强一致性（同机房内延迟低，可以做到）
- SET间只能做到最终一致性（异地延迟高，强一致代价太大）

### 15.2 数据同步的延迟问题

SET间的数据同步通过 DTS（Data Transmission Service）实现。同步延迟受限于：

| 因素 | 影响 | 典型值 |
|------|------|--------|
| 网络延迟 | 异地机房间的网络RTT | 10-50ms |
| 专线带宽 | 跨地域数据传输的带宽限制 | 1-10Gbps |
| 数据量 | 单次同步的数据量大小 | 随业务波动 |
| 数据库负载 | 主库Binlog生成速度 | 高负载时延迟增大 |
| 同步链路健康 | 是否有网络抖动或中断 | 偶尔发生 |

正常情况下，SET间数据同步延迟在 **100ms-1s** 之间。在高峰期或网络异常时，延迟可能达到 **5-10s** 甚至更高。

### 15.3 数据冲突的检测与解决

当两个SET同时修改同一条数据时（虽然理论上不应该发生，因为数据按分片归属），可能会出现写冲突。

**冲突检测**：
- 基于 binlog 的 row format，可以检测到同一行数据的两次修改
- 通过时间戳和版本号判断是否存在冲突

**冲突解决策略**：

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| 最后写入者胜出（LWW） | 以时间戳最新的为准 | 大多数场景，简单有效 |
| 版本号优先 | 版本号大的覆盖版本号小的 | 需要严格顺序保证的场景 |
| 业务层合并 | 由业务逻辑决定如何合并 | 复杂数据结构（如购物车） |
| 拒绝写入 | 发现冲突时拒绝后写入的请求 | 金融级强一致场景 |

### 15.4 读一致性的保障

**方案一：SET内强一致 + SET间最终一致**
- 这是美团SET化架构的主要策略
- 用户请求在自己所在的SET内读写，保证强一致
- SET间数据通过异步同步，保证最终一致

**方案二：读主库（牺牲性能换一致性）**
- 对于跨SET的读请求，直接读取数据所属SET的主库
- 劣势：跨机房延迟大，性能差

**方案三：缓存一致性（Cache-Aside）**
- 每个SET有自己的缓存（Squirrel）
- 数据变更时，先更新主库，再删除缓存
- 跨SET读取时，如果缓存未命中，从主库读取

---

## 十六、SET化架构中的消息队列方案

### 16.1 Mafka在SET化中的角色

Mafka（美团自研消息队列）在SET化架构中承担着重要的异步解耦和数据同步职责。

**SET内消息流转**：
```
SET-A 内：
  服务A ──► Mafka Topic-A ──► 服务B
  
  特点：消息完全在SET内流转，不跨SET
  优势：延迟低，吞吐高，不依赖跨SET网络
```

**跨SET消息同步**：
```
SET-A ──► Mafka Topic-A ──► DTS ──► Mafka Topic-A' ──► SET-B

  特点：通过DTS将SET-A的消息同步到SET-B的Mafka
  劣势：增加延迟，需要处理消息顺序和重复问题
```

### 16.2 Mafka Topic的SET化策略

| Topic类型 | 策略 | 说明 |
|-----------|------|------|
| SET内Topic | 每个SET独立Topic | 如order-events-set-a, order-events-set-b |
| 全局Topic | 中心集群部署 | 如全局配置变更通知 |
| 跨SET同步Topic | 双向复制 | 如SET-A的订单变更同步到SET-B |

### 16.3 消息顺序性保障

在SET化架构中，消息顺序性需要特别关注：

- **SET内消息顺序**：Mafka的分区机制保证同一分区内消息有序
- **跨SET消息顺序**：通过DTS同步时，可能由于网络延迟导致顺序错乱
- **解决方案**：
  1. 业务层保证幂等性，不依赖消息顺序
  2. 关键业务使用全局有序Topic（牺牲性能）
  3. 在消息中携带时间戳/版本号，消费者按时间戳处理

---

## 十七、SET化架构中的缓存方案

### 17.1 Squirrel在SET化中的部署

Squirrel（Squirrel Cluster）在SET化架构中的部署方式：

**方案一：SET内独立缓存（推荐）**
```
SET-A：Squirrel Cluster-A（3主3从，部署在SET-A机房）
SET-B：Squirrel Cluster-B（3主3从，部署在SET-B机房）

特点：
- 每个SET有自己的缓存集群
- 缓存数据按SET分片，不跨SET共享
- 优势：延迟低，无跨SET依赖
```

**方案二：跨SET缓存同步**
```
SET-A Squirrel ──► redis-keeper-service ──► SET-B Squirrel

特点：
- 通过keeper-service将缓存数据同步到互备SET
- 劣势：增加延迟，缓存同步的实时性不如数据库同步
```

### 17.2 缓存一致性策略

SET化架构中的缓存一致性策略：

```
SET内缓存一致性（Cache-Aside）：
  1. 读：先读Squirrel缓存，未命中则读DB，回填缓存
  2. 写：先更新DB，再删除缓存
  3. 延迟双删：删除缓存后，等待一段时间再次删除（防止旧数据回填）

跨SET缓存一致性：
  1. 本SET数据变更 → 删除本SET缓存
  2. 数据库同步到互备SET → 互备SET的缓存自动失效（通过binlog监听）
```

### 17.3 本地缓存（Caffeine）在SET化中的使用

在SET化架构中，本地缓存（如Caffeine、Guava Cache）可以作为分布式缓存的前置层：

```
请求流程：
  本地缓存（Caffeine）─未命中─► 分布式缓存（Squirrel）─未命中─► DB

优势：
1. 本地缓存延迟 < 1ms，远快于分布式缓存
2. 减少跨SET的网络调用
3. 降低分布式缓存的负载

劣势：
1. 本地缓存的数据可能不一致（各节点缓存独立）
2. 需要设置较短的TTL，保证数据新鲜度
```

---

## 十八、SET化架构中的测试与验证

### 18.1 SET化改造前的测试策略

在进行SET化改造前，需要完成以下测试：

| 测试类型 | 测试内容 | 通过标准 |
|----------|----------|----------|
| 单元测试 | 分片算法正确性 | 不同输入的路由结果符合预期 |
| 集成测试 | 服务间调用链路 | SET内调用正常，跨SET调用有fallback |
| 数据迁移测试 | 数据分片正确性 | 数据按分片维度正确分布 |
| 性能测试 | SET内延迟、吞吐量 | SET内延迟 < 5ms，吞吐满足业务需求 |
| 容灾测试 | 单SET故障切换 | 故障SET流量能在2-3分钟内切换到互备SET |

### 18.2 容灾演练的详细步骤

以金服断网演练为例，详细步骤如下：

**Step 1：演练准备**
- 确定演练目标：验证同城SET互备的容灾能力
- 确定演练范围：哪些服务、哪些数据参与演练
- 通知相关团队：业务方、DBA、SRE、监控团队
- 准备回滚方案：一旦演练出现问题，如何快速恢复

**Step 2：演练执行**
- 切换前检查：确认数据同步延迟在正常范围
- 停止故障SET的写入：确保没有新数据写入故障SET
- 等待数据同步完成：确认互备SET的数据与故障SET一致
- 切换流量：将流量从故障SET切换到互备SET
- 验证业务：确认业务在互备SET上正常运行

**Step 3：演练验证**
- 检查业务核心指标：订单量、支付成功率等
- 检查数据一致性：抽样对比故障SET和互备SET的数据
- 检查监控告警：确认没有异常告警

**Step 4：演练恢复**
- 恢复故障SET的网络
- 数据反向同步：将互备SET的新数据同步回故障SET
- 流量回切：将流量切换回故障SET
- 验证恢复：确认业务在故障SET上正常运行

**Step 5：演练复盘**
- 记录演练过程中的问题和发现
- 更新容灾SOP和预案
- 制定改进措施，排期修复

### 18.3 混沌工程在SET化中的应用

混沌工程（Chaos Engineering）是验证系统容灾能力的有效手段。在SET化架构中，可以进行的混沌实验：

| 实验类型 | 实验内容 | 预期结果 |
|----------|----------|----------|
| 网络延迟 | 增加SET间网络延迟到100ms | 业务仍正常运行，延迟略有增加 |
| 网络丢包 | 随机丢弃SET间1%的数据包 | 数据同步自动重试，最终一致 |
| 单点故障 | 随机kill一个SET内的服务实例 | 流量自动路由到同SET其他实例 |
| 数据库故障 | 模拟主库宕机 | 自动切换到从库，业务无感知 |
| 全SET故障 | 模拟整个SET不可用 | 流量在2-3分钟内切换到互备SET |

---

## 十九、SET化架构中的监控与可视化

### 19.1 SET级别监控指标

| 指标类别 | 指标名 | 说明 | 告警阈值 |
|----------|--------|------|----------|
| 流量 | SET入口QPS | 每个SET的入口流量 | 波动 > 30% |
| 流量 | 跨SET调用QPS | 跨SET的RPC调用量 | > 100/min |
| 延迟 | SET内P99延迟 | SET内服务调用P99延迟 | > 100ms |
| 延迟 | 跨SETP99延迟 | 跨SET服务调用P99延迟 | > 50ms |
| 错误 | SET错误率 | SET内服务错误率 | > 0.1% |
| 数据 | 数据同步延迟 | SET间数据同步延迟 | > 5s |
| 容量 | SET资源使用率 | CPU、内存、磁盘使用率 | > 80% |
| 容灾 | SET健康状态 | SET内关键服务健康状态 | 不健康 |

### 19.2 监控大盘设计

```
SET化监控大盘
├── 流量面板
│   ├── 各SET入口QPS趋势
│   ├── 跨SET调用占比
│   └── 流量分布热力图
├── 延迟面板
│   ├── SET内平均延迟
│   ├── SET内P99延迟
│   ├── 跨SET平均延迟
│   └── 跨SETP99延迟
├── 错误面板
│   ├── 各SET错误率趋势
│   ├── 跨SET调用错误率
│   └── 错误类型分布
├── 数据面板
│   ├── SET间数据同步延迟
│   ├── 数据同步吞吐量
│   └── 数据一致性校验结果
├── 容量面板
│   ├── 各SET资源使用率
│   ├── SET容量饱和度
│   └── 扩容预警
└── 容灾面板
    ├── SET健康状态
    ├── 容灾切换历史
    └── 演练状态
```

### 19.3 全链路追踪在SET化中的应用

CAT（美团全链路监控）在SET化架构中的追踪：

```
用户请求链路（带SET标签）：

  [用户北京] ──► [Oceanus网关 SET-A] ──► [订单服务 SET-A] ──► [支付服务 SET-A]
     │               │ SET-A                    │ SET-A                 │ SET-A
     │               │ 标签: set=A              │ 标签: set=A           │ 标签: set=A
     │               │                          │                       │
     │               │ 路由决策: 用户北京 ──► SET-A                    │
     │               │                                                          │
     │               └──────────────────────────────────────────────────────┘
     │                                SET内闭环，无跨SET调用
```

通过CAT的全链路追踪，可以：
- 识别跨SET调用（异常链路高亮）
- 统计SET内闭环率
- 分析SET间调用延迟

---

## 二十、SET化架构中的数据库设计

### 20.1 数据库的SET化改造

数据库是SET化改造中最复杂的部分。改造的核心是将数据按分片维度拆分到不同SET的数据库中。

**改造前（单库）**：
```
MySQL Cluster（中心）
├── 订单库（order）
│   └── 订单表（orders）- 10亿行
├── 用户库（user）
│   └── 用户表（users）- 5亿行
└── 商品库（product）
    └── 商品表（products）- 1亿行
```

**改造后（按SET分片）**：
```
SET-A（北京）                     SET-B（上海）
├── 订单库（order）               ├── 订单库（order）
│   └── 订单表（orders）- 5亿行    │   └── 订单表（orders）- 5亿行
│   （北京用户订单）                │   （上海用户订单）
├── 用户库（user）                ├── 用户库（user）
│   └── 用户表（users）- 2.5亿行   │   └── 用户表（users）- 2.5亿行
│   （北京用户）                    │   （上海用户）
└── 商品库（product）              └── 商品库（product）
    └── 商品表（products）- 1亿行      └── 商品表（products）- 1亿行
    （全量商品，通过同步）              （全量商品，通过同步）
```

### 20.2 商品等全局数据的处理

对于商品、门店等全局数据（所有用户都需要访问），SET化有两种处理方式：

**方案一：每个SET存储全量副本（读多写少场景）**
- 每个SET的数据库中都有一份完整的商品表
- 商品变更时，通过DTS同步到所有SET
- 优势：SET内读取商品数据无需跨SET
- 劣势：数据同步延迟，占用存储空间

**方案二：中心库存储，SET内缓存（读少写多场景）**
- 商品数据存储在中心库（N+1部署）
- 每个SET缓存热点商品数据
- 优势：数据一致性容易保证
- 劣势：跨SET读取商品数据，延迟增加

### 20.3 数据库连接池的SET化配置

在SET化架构中，数据库连接池需要按SET配置：

```yaml
# 数据库连接池配置（按SET）
database:
  set-a:
    jdbc-url: jdbc:mysql://order-db-set-a:3306/order
    username: order_user
    password: ***
    max-pool-size: 100
    min-pool-size: 10
  set-b:
    jdbc-url: jdbc:mysql://order-db-set-b:3306/order
    username: order_user
    password: ***
    max-pool-size: 100
    min-pool-size: 10
```

Zebra（数据库中间件）在SET化中的角色：
- 根据当前请求的SET标签，自动路由到对应SET的数据库
- 屏蔽底层数据库分片细节，业务代码无需关心数据在哪个SET

---

## 二十一、SET化架构中的网关设计

### 21.1 Oceanus网关的SET路由

Oceanus是美团的多活网关，在SET化架构中承担着流量入口和SET路由的关键职责。

**Oceanus的SET路由流程**：

```
用户请求到达
  │
  ▼
Oceanus网关
  │
  ├── 解析请求参数（用户ID、城市、设备等）
  │
  ├── 查询路由规则（根据用户ID或城市确定SET）
  │
  ├── 为请求打上SET标签（如 set=set-a）
  │
  └── 将请求转发到对应SET的接入服务
```

**Oceanus路由规则配置示例**：

```json
{
  "routeRules": [
    {
      "name": "到家LBS路由",
      "match": {
        "path": "/waimai/**",
        "method": ["GET", "POST"]
      },
      "route": {
        "type": "lbs",
        "dimension": "city",
        "mapping": {
          "北京": "set-a",
          "上海": "set-b",
          "广州": "set-c",
          "深圳": "set-d"
        }
      }
    },
    {
      "name": "金服UID路由",
      "match": {
        "path": "/payment/**"
      },
      "route": {
        "type": "hash",
        "dimension": "payer_id",
        "shardCount": 4,
        "mapping": {
          "0": "set-a",
          "1": "set-b",
          "2": "set-c",
          "3": "set-d"
        }
      }
    }
  ]
}
```

### 21.2 DNS在SET化中的角色

DNS是SET化流量路由的第一层：

```
用户请求：api.sankuai.com
  │
  ▼
DNS解析（根据用户地理位置返回不同IP）
  │
  ├── 北京用户 ──► 北京VIP（指向SET-A接入层）
  ├── 上海用户 ──► 上海VIP（指向SET-B接入层）
  └── 广州用户 ──► 广州VIP（指向SET-C接入层）
```

DNS路由的优势：
- 用户就近访问，延迟最低
- 天然实现流量隔离
- 无需修改应用代码

DNS路由的劣势：
- DNS缓存可能导致切换不及时
- 无法处理用户跨地域移动的场景（如北京用户到上海出差）
- 粒度较粗，只能按地域分

### 21.3 用户移动场景的处理

当用户从一个地域移动到另一个地域时（如北京用户到上海出差），可能出现DNS路由到错误SET的情况。

**解决方案**：

1. **LBS纠偏（Oceanus层）**
   - Oceanus根据请求中的GPS或IP信息，判断用户实际位置
   - 如果用户实际位置与DNS路由的SET不一致，Oceanus可以重新路由到正确的SET

2. **用户数据双写（金服场景）**
   - 付款方ID路由，不受用户地理位置影响
   - 用户在北京或上海，付款方ID不变，始终路由到同一个SET

3. **数据聚合层（到店场景）**
   - 用户搜索门店时，从多个SET聚合数据
   - 通过中心搜索服务（ES）聚合各SET的门店数据

---

## 二十二、SET化架构中的PaaS组件支持

### 22.1 Lion配置中心的SET化支持

Lion（配置中心）支持按SET隔离配置：

```
AppKey: com.sankuai.waimai.order
Group: default
Key: db.host

SET-A的值：db-host-set-a.sankuai.com
SET-B的值：db-host-set-b.sankuai.com
```

Lion客户端根据当前服务所在的SET，自动获取对应SET的配置值。

### 22.2 Crane任务调度的SET化支持

Crane（任务调度）支持按SET调度任务：

```java
@Crane("com.sankuai.order.daily-report")
@SetRoute("set-a")  // 只在SET-A执行
public void generateDailyReport() {
    // 生成日报
}
```

也可以配置任务在多个SET同时执行：

```java
@Crane("com.sankuai.order.daily-report")
@SetRoute({"set-a", "set-b"})  // 在SET-A和SET-B都执行
public void generateDailyReport() {
    // 每个SET生成自己的日报
}
```

### 22.3 CAT监控的SET化支持

CAT（全链路监控）支持按SET维度统计监控指标：

```
CAT监控大盘（按SET筛选）：
- SET-A：QPS=10000, P99=50ms, 错误率=0.01%
- SET-B：QPS=8000, P99=45ms, 错误率=0.02%
- SET-C：QPS=12000, P99=55ms, 错误率=0.015%
```

通过CAT的SET维度监控，可以：
- 对比各SET的性能差异
- 识别某个SET的异常
- 评估SET间的负载均衡情况

---

## 二十三、SET化改造的实战经验

### 23.1 到家业务的SET化改造历程

到家（外卖、配送）业务是美团最早进行SET化改造的业务之一。改造历程：

| 阶段 | 时间 | 里程碑 | 关键决策 |
|------|------|--------|----------|
| 调研 | 2017Q1 | 完成SET化技术方案设计 | 选择LBS分片，城市作为分片维度 |
| 试点 | 2017Q2 | 选定北京作为试点SET | 先验证技术可行性 |
| 扩展 | 2017Q3-Q4 | 扩展到上海、广州、深圳 | 逐步增加SET，验证扩展性 |
| 完善 | 2018 | 完成4个主要SET+2个小SET | 异地互备，数据同步 |
| 优化 | 2019+ | 持续优化SET内闭环率 | 减少跨SET调用，优化路由 |

### 23.2 金服业务的SET化改造历程

金服（支付、账务）业务的SET化改造与到家不同，因为支付没有地域属性：

| 阶段 | 时间 | 里程碑 | 关键决策 |
|------|------|--------|----------|
| 调研 | 2018Q1 | 完成金服SET化方案 | 选择付款方ID作为分片维度 |
| 试点 | 2018Q2 | 北京同城双SET试点 | 同城一对SET互备 |
| 优化 | 2019-2020 | 断网演练，验证容灾 | 验证RPO=0 |
| 扩展 | 2021-2022 | 推进异地SET建设 | 计划上海金融专区SET |
| 成熟 | 2023-2025 | 常态化断网演练 | 2025年完成3次直接断网演练 |

### 23.3 SET化改造的常见问题

**问题一：分片维度选择错误**
- 现象：初期选择用户ID作为分片维度，但外卖订单还涉及商家ID、骑手ID，导致跨SET查询频繁
- 解决：改为按城市（LBS）分片，因为外卖天然具有地理属性

**问题二：SET内闭环率不足**
- 现象：改造后发现仍有大量跨SET调用，延迟高
- 解决：识别跨SET调用链路，将中心服务下沉到SET内，或增加SET内缓存

**问题三：数据同步延迟导致不一致**
- 现象：用户在一个SET下单，立即切换到另一个SET查看订单，发现订单不存在
- 解决：增加数据同步监控，优化同步链路；业务层增加"数据同步中"的友好提示

**问题四：容灾切换时数据丢失**
- 现象：SET故障切换时，部分新数据未同步到互备SET
- 解决：切换前确认数据同步延迟在可接受范围；推进MGR半同步复制

---

## 二十四、大禹平台：SET化的统一管控

### 24.1 大禹平台的定位

大禹平台是美团基础架构团队建设的SET化统一管控平台，目标是实现SET化的"四个无感知"。

```
大禹平台功能架构：

┌─────────────────────────────────────────┐
│           大禹平台（大禹）               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ SET管理   │ │ 路由管控  │ │ 一键建站  │ │
│  │ · 创建SET │ │ · 路由规则│ │ · 自动化  │ │
│  │ · 删除SET │ │ · 灰度发布│ │ · 模板化  │ │
│  │ · SET克隆 │ │ · 流量染色│ │ · 快速拉起│ │
│  └──────────┘ └──────────┘ └──────────┘ │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │ 容灾管理  │ │ 监控可视化│ │ 权限管理  │ │
│  │ · 容灾切换│ │ · SET大盘 │ │ · 角色权限│ │
│  │ · 断网演练│ │ · 链路追踪│ │ · 审批流  │ │
│  │ · 故障恢复│ │ · 告警管理│ │ · 审计日志│ │
│  └──────────┘ └──────────┘ └──────────┘ │
└─────────────────────────────────────────┘
```

### 24.2 大禹平台的核心能力

**SET克隆**：
- 通过服务编排自动化复制SET的全套配置
- 从现有SET复制出新的SET，包括：服务配置、数据库配置、缓存配置、路由规则等
- 目标：将新建SET的周期从3个月缩短到天级

**路由统一管控**：
- 将散落在各业务方中的路由逻辑统一到大禹平台
- 提供标准化的路由规则配置界面
- 支持路由规则的全链路可视化

**容灾演练平台化**：
- 提供断网演练的标准化流程
- 自动执行演练步骤，记录演练结果
- 生成演练报告，跟踪改进措施

### 24.3 大禹平台与SET化的关系

大禹平台不是替代SET化，而是SET化的管理和控制层：
- SET化是架构能力（怎么分片、怎么路由、怎么容灾）
- 大禹平台是管理平台（怎么创建SET、怎么配置路由、怎么演练容灾）

两者相辅相成，共同实现SET化的"四个无感知"目标。

---

## 二十五、业界单元化架构深度对比

### 25.1 阿里淘宝的单元化架构

淘宝的单元化架构是业界最成熟的方案之一，特点：

- **三地四单元**：杭州、上海、深圳三地，每个地域有多个单元
- **无中心SET**：所有单元对等，没有中心集群
- **XDL（淘宝分布式层）**：自研的分布式数据层，实现数据分片和同步
- **UMP（淘宝单元化平台）**：统一的单元化管理平台

| 维度 | 淘宝 | 到家业务 |
|------|------|----------|
| 分片维度 | 买家ID | LBS（城市） |
| 单元数量 | 多地多单元 | 两地四SET+小SET |
| 中心服务 | 无中心，完全对等 | 有中心服务（低频） |
| 数据同步 | 单元间双向同步 | SET间双向同步 |
| 容灾能力 | 异地多活 | 异地互备 |
| 成熟度 | 第一梯队，最成熟 | 第二梯队，持续演进 |

### 25.2 蚂蚁金服的单元化架构

蚂蚁金服的单元化架构是金融级容灾的标杆：

- **三地五中心**：杭州、上海、深圳三地，五个数据中心
- **金融级容灾**：RPO=0，RTO<1分钟
- **LDC（逻辑数据中心）**：蚂蚁的单元化架构名称
- **OceanBase**：自研分布式数据库，支持单元化部署

| 维度 | 蚂蚁金服 | 美团金服 |
|------|----------|--------|
| 分片维度 | 付款方ID | 付款方ID |
| 单元数量 | 三地五中心 | 同城双SET（规划中异地） |
| 数据库 | OceanBase（自研） | MySQL MGR + TiDB |
| 容灾级别 | 金融级（RPO=0） | 准金融级（追求RPO=0） |
| 断网演练 | 常态化，频繁 | 2025年开始常态化 |
| 成熟度 | 第一梯队，金融级 | 第二梯队，持续演进 |

### 25.3 京东的SET化架构

京东的SET化定位与美团有所不同：

- **零售业务SET化**：主要针对商品、库存、订单等零售核心链路
- **计划演进为全站方案**：目前仅零售业务SET化，未来计划扩展到全站
- **自研分布式数据库**：JD-OS（基于MySQL的分布式数据库）

### 25.4 饿了么的单元化架构

饿了么作为美团的子公司，参考了淘宝和美团的方案：

- **异地双活**：仅实现异地双活，扩展性较差
- **参考淘宝和美团**：结合了两者的方案，但规模较小
- **成熟度**：第三梯队，还在建设中

---

## 二十六、SET化架构中的安全性考虑

### 26.1 数据隔离与权限控制

SET化架构天然具有数据隔离的优势：
- SET-A的用户数据不会存储在SET-B的数据库中
- 即使SET-B被攻击，SET-A的数据仍然是安全的

但需要注意：
- **全局数据的安全**：如商品、门店数据，每个SET都有副本，需要保证所有副本的安全
- **跨SET调用的安全**：跨SET调用需要认证和授权，防止未授权访问
- **数据同步的安全**：SET间数据同步通过专线，需要加密传输

### 26.2 容灾切换中的安全

容灾切换时，需要注意：
- **数据完整性**：切换前确认数据同步完成，防止数据丢失
- **数据一致性**：切换后验证数据一致性，防止脏数据
- **回滚安全**：如果切换失败，需要安全回滚，防止数据双写

### 26.3 合规与审计

SET化架构中的合规要求：
- **数据本地化**：某些业务要求数据存储在特定地域（如金融数据不出城）
- **审计日志**：所有SET的操作都有审计日志，便于合规检查
- **数据脱敏**：跨SET同步时，敏感数据需要脱敏

---

## 二十七、SET化架构的成本分析

### 27.1 SET化的资源成本

SET化架构的资源成本比传统N+1架构更高：

| 资源类型 | N+1架构 | SET化架构 | 差异 |
|----------|---------|-----------|------|
| 服务器 | 1.5N | 2N | +33% |
| 数据库 | 1.5N | 2N+ | +33%+ |
| 网络带宽 | 同城带宽 | 同城+跨城带宽 | +50%+ |
| 存储 | 单份数据 | 多份副本 | +100% |
| 运维人力 | 较低 | 较高 | +50% |

### 27.2 SET化的收益

虽然成本更高，但SET化带来了以下收益：

| 收益类型 | 说明 | 价值 |
|----------|------|------|
| 容灾能力 | 单机房故障不影响全网 | 避免P0故障，减少损失 |
| 扩展性 | 新增SET即可扩展容量 | 支持业务持续增长 |
| 隔离性 | 单个SET故障不影响其他SET | 减少故障影响面 |
| 性能 | SET内闭环，延迟低 | 提升用户体验 |
| 灵活性 | 按地域/业务线灵活部署 | 支持差异化策略 |

### 27.3 成本优化方向

1. **SET合并**：对于小业务，可以考虑多个业务共用一个SET，降低资源成本
2. **LiteSET替代**：对于不需要物理隔离的业务，使用LiteSET（逻辑隔离），成本更低
3. **冷热数据分离**：冷数据存储到低成本存储，减少内存和SSD成本
4. **弹性伸缩**：非高峰期缩容，高峰期扩容，按需使用资源

---

## 二十八、SET化架构的未来演进

### 28.1 当前挑战

SET化架构当前面临的主要挑战：

1. **同城容灾缺失**：到家SET化缺少同城容灾，AZ级故障需要异地切换
2. **路由纠错不足**：缺少全链路路由正确性校验和自动纠错
3. **建站成本高**：新建SET需要3个月，缺少一键建站能力
4. **数据同步复杂**：SET间数据同步呈网状结构，与业务强耦合
5. **运维靠业务**：SET化逻辑与业务代码耦合，Avatar化不足

### 28.2 演进方向

根据美团基础技术部的规划，SET化架构的演进方向：

1. **路由统一化**：大禹平台统一管控所有路由逻辑
2. **一键建站**：将新建SET周期从3个月缩短到天级
3. **路由纠错**：全链路路由正确性校验和自动纠错
4. **容灾常态化**：常态化断网演练，验证RPO/RTO
5. **数据同步标准化**：标准化SET间数据同步链路，解耦业务
6. **同城容灾建设**：为到家业务增加同城容灾能力
7. **Serverless化**：SET内的服务支持Serverless部署，按需扩缩容

### 28.3 长期愿景

美团SET化架构的长期愿景：
- **完全无感知**：业务方完全不感知SET的存在，像使用单体系统一样使用分布式系统
- **全自动容灾**：故障自动检测、自动切换、自动恢复，无需人工干预
- **全球部署**：支持海外部署，如东南亚、欧洲等地的SET建设
- **多云支持**：SET可以部署在不同的云厂商，实现多云容灾

---

## 二十九、面试常见问题与解答

### 29.1 基础概念类

**Q1：什么是SET化架构？**
A：SET化架构是将业务系统按某种数据特征维度（如地域、用户ID）进行垂直划分，每个SET是一个能完成核心业务功能的独立单元。SET内包含全部核心服务和数据，SET间通过数据同步实现互备容灾。

**Q2：SET化和N+1架构有什么区别？**
A：N+1架构是同城多活，通过多机房部署实现同城容灾；SET化是异地多活，通过单元化部署实现异地容灾。SET化的资源成本更高，但容灾能力更强，扩展性更好。

**Q3：什么是SET内闭环？**
A：SET内闭环是指用户请求在SET内部完成全链路处理，包括服务调用、数据库读写、缓存操作、消息队列交互等，不跨SET或跨机房调用。这是降低延迟、提高稳定性的核心设计原则。

**Q4：到家和金服的分片维度为什么不同？**
A：到家业务（外卖、配送）天然具有地理属性，用户在北京下单，商家和骑手也在北京，因此按城市（LBS）分片最自然。金服业务（支付）没有地域属性，一个北京用户可能给上海商家付款，因此按付款方ID分片更合理。

**Q5：什么是LiteSET？**
A：LiteSET是轻量级SET方案，通过流量染色实现逻辑隔离，而不是物理隔离。它适用于不需要完整容灾能力，但需要业务隔离的场景，如全链路灰度发布、业务线隔离等。

### 29.2 技术实现类

**Q6：SET间的数据同步是如何实现的？**
A：主要通过DTS（数据传输服务）实现，基于MySQL binlog的增量同步。一个SET的主库数据变更通过binlog解析，同步到互备SET的从库。同步是异步的，存在短暂延迟。

**Q7：SET化架构如何解决脑裂问题？**
A：主要通过三机房部署保证任何机房内的主节点数量不超过总数的一半。当某个机房断网时，机房内部由于主节点数量不足法定人数，会主动拒绝写请求，从而避免脑裂。两机房部署时引入见证者节点打破平局。

**Q8：容灾切换时如何保证数据不丢失？**
A：金服业务通过MGR半同步复制和RDS强一致方案，追求RPO=0。到家业务通过确认数据同步延迟在可接受范围内后切换，允许短暂的RPO。切换前需要停止写入，确保没有新数据产生。

**Q9：SET化架构中的全局数据（如商品）如何处理？**
A：有两种方案：1）每个SET存储全量副本，通过DTS同步变更；2）中心库存储，SET内缓存热点数据。选择哪种方案取决于数据的读写比例和业务特点。

**Q10：Oceanus网关如何确定请求应该路由到哪个SET？**
A：Oceanus根据请求中的分片键（如用户ID、城市）和路由规则，计算请求应该归属的SET。路由规则可以是LBS（基于地理位置）或哈希（基于用户ID取模）。

### 29.3 设计决策类

**Q11：为什么SET化架构偏向AP而不是CP？**
A：SET间异地延迟在10-20ms以上，如果采用强一致同步（每次写操作都跨SET确认），会严重影响写入性能。因此SET化选择SET内强一致、SET间最终一致的策略，日常运行时偏向AP，容灾切换时偏向CP。

**Q12：SET化改造的最大难点是什么？**
A：数据分片和路由逻辑的改造。需要将原有数据按分片维度拆分，修改所有涉及数据路由的代码，确保请求路由到正确的SET。同时需要处理数据迁移、同步、一致性等复杂问题。

**Q13：如何评估一个业务是否适合SET化？**
A：评估维度：1）业务规模是否足够大（日订单量千万级以上）；2）是否有异地容灾需求；3）数据是否天然可分片（如地域、用户ID）；4）是否有足够的资源投入；5）业务是否可接受最终一致性。

**Q14：SET化架构中的中心服务是什么？**
A：中心服务是不需要SET化部署的低频全局服务，如全局配置管理、运营后台、全局搜索等。这些服务以N+1方式部署，SET内的服务调用中心服务时会跨SET，但由于调用频率低，不会造成显著延迟。

**Q15：大禹平台在SET化中扮演什么角色？**
A：大禹平台是SET化的统一管控平台，提供SET管理、路由管控、一键建站、容灾管理、监控可视化等能力。它是SET化的"控制面"，将SET化的管理和运维工作平台化、自动化。

---

## 三十、最终总结

### 30.1 SET化的核心价值

SET化架构是美团应对业务体量爆发式增长的核心架构方案。它通过三个核心手段解决了传统架构的瓶颈：

1. **分片**：将超大系统按维度切分为多个独立SET，每个SET处理自己的数据分片，实现水平扩展
2. **路由**：通过网关和RPC框架将流量路由到正确的SET，保证请求在SET内闭环处理
3. **容灾**：SET间两两互备，数据双向同步，单个SET故障时可快速切换到互备SET

### 30.2 架构演进的启示

SET化架构的演进给我们以下启示：

1. **架构是业务驱动的**：不是因为技术酷才做SET化，而是因为业务规模到了必须做的时候
2. **没有银弹**：SET化解决了扩展和容灾，但引入了数据同步、跨SET调用、运维复杂度等新问题
3. **平台化是趋势**：从业务方自己维护SET化逻辑，到大禹平台统一管控，体现了平台化的大趋势
4. **容灾需要演练**：只有常态化断网演练，才能真正验证容灾能力，发现问题

### 30.3 给新同学的建议

如果你是第一次接触SET化，建议按以下顺序学习：

1. 理解传统架构的瓶颈（为什么需要SET化）
2. 理解SET化的核心概念（分片、路由、闭环、互备）
3. 学习到家和金服的SET化案例（不同业务的不同选择）
4. 了解SET化面临的挑战（数据同步、路由纠错、运维复杂度）
5. 关注大禹平台的演进（路由统一化、一键建站、容灾常态化）

记住：**理解SET化不仅是理解一种架构，更是理解分布式系统设计的核心思想——分而治之。**

---

## 三十一、附录A：核心术语表

| 术语 | 英文 | 含义 |
|------|------|------|
| SET | SET | 单元，完成核心业务功能的独立部署单元 |
| LBS | Location Based Service | 基于地理位置的服务，到家SET化的分片维度 |
| DTS | Data Transmission Service | 数据传输服务，用于SET间数据同步 |
| MGR | MySQL Group Replication | MySQL组复制，提供强一致性数据复制 |
| RPO | Recovery Point Objective | 恢复点目标，数据丢失容忍度 |
| RTO | Recovery Time Objective | 恢复时间目标，故障恢复时间 |
| CAP | CAP Theorem | 一致性、可用性、分区容忍性定理 |
| AP | Available + Partition-tolerant | 可用性和分区容忍性优先 |
| CP | Consistent + Partition-tolerant | 一致性和分区容忍性优先 |
| Oceanus | Oceanus | 美团多活网关 |
| OCTO | OCTO | 美团RPC框架 |
| CAT | Central Application Tracking | 美团全链路监控 |
| Lion | Lion | 美团配置中心 |
| Crane | Crane | 美团任务调度 |
| Zebra | Zebra | 美团数据库中间件 |
| Squirrel | Squirrel | 美团分布式缓存 |
| Mafka | Mafka | 美团消息队列 |
| LiteSET | LiteSET | 轻量级SET，通过流量染色实现逻辑隔离 |
| 大禹 | 大禹 | 大禹平台，SET化统一管控平台 |
| N+1 | N+1 | N个主数据中心加1个备份中心 |
| AZ | Availability Zone | 可用区，同一地域内电力和网络相互独立的物理区域 |
| BG | Business Group | 事业群，美团内部业务组织单位 |
| DC | Data Center | 数据中心 |
| PaaS | Platform as a Service | 平台即服务 |
| IaaS | Infrastructure as a Service | 基础设施即服务 |
| SaaS | Software as a Service | 软件即服务 |
| QPS | Queries Per Second | 每秒查询数 |
| TPS | Transactions Per Second | 每秒事务数 |
| P99 | 99th Percentile | 99分位延迟 |
| TP99 | 99th Percentile | 同P99 |
| IDL | Interface Definition Language | 接口定义语言 |
| RPC | Remote Procedure Call | 远程过程调用 |
| SOA | Service Oriented Architecture | 面向服务架构 |
| Microservices | Microservices | 微服务架构 |
| DevOps | DevOps | 开发运维一体化 |
| SRE | Site Reliability Engineering | 站点可靠性工程 |
| DBA | Database Administrator | 数据库管理员 |
| SOP | Standard Operating Procedure | 标准操作流程 |
| HA | High Availability | 高可用性 |
| DR | Disaster Recovery | 灾难恢复 |
| SLA | Service Level Agreement | 服务等级协议 |
| MTBF | Mean Time Between Failures | 平均故障间隔时间 |
| MTTR | Mean Time To Recovery | 平均恢复时间 |

---

## 三十二、附录B：SET化架构参考图

### B.1 到家SET化架构全景图

```
                    用户（全国）
                        │
           ┌────────────┼────────────┐
           │            │            │
           ▼            ▼            ▼
      [DNS北京]     [DNS上海]     [DNS广州]
           │            │            │
           ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │ 北京入口  │ │ 上海入口  │ │ 广州入口  │
     │Oceanus   │ │Oceanus   │ │Oceanus   │
     │ 多活网关  │ │ 多活网关  │ │ 多活网关  │
     └────┬─────┘ └────┬─────┘ └────┬─────┘
          │            │            │
          ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │  SET-A   │ │  SET-B   │ │  SET-C   │
     │ (北京)    │ │ (上海)    │ │ (广州)    │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │订单服务│ │ │ │订单服务│ │ │ │订单服务│ │
     │ │商家服务│ │ │ │商家服务│ │ │ │商家服务│ │
     │ │骑手服务│ │ │ │骑手服务│ │ │ │骑手服务│ │
     │ │支付服务│ │ │ │支付服务│ │ │ │支付服务│ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │订单DB │ │ │ │订单DB │ │ │ │订单DB │ │
     │ │用户DB │ │ │ │用户DB │ │ │ │用户DB │ │
     │ │商家DB │ │ │ │商家DB │ │ │ │商家DB │ │
     │ │商品DB │ │ │ │商品DB │ │ │ │商品DB │ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     │ (副本)    │ │ (副本)    │ │ (副本)    │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │Squirrel │ │ │ │Squirrel │ │ │ │Squirrel │ │
     │ │MQ    │ │ │ │MQ    │ │ │ │MQ    │ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     └──────────┘ └──────────┘ └──────────┘
          │            │            │
          └────────────┼────────────┘
                       │
                       ▼
              ┌──────────────┐
              │   中心服务     │
              │ · 全局配置    │
              │ · 运营后台    │
              │ · 搜索服务    │
              │ · 账号服务    │
              └──────────────┘
```

### B.2 金服SET化架构全景图

```
                    用户（全国）
                        │
           ┌────────────┼────────────┐
           │            │            │
           ▼            ▼            ▼
      [DNS北京]     [DNS上海]     [DNS广州]
           │            │            │
           ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │ 北京入口  │ │ 上海入口  │ │ 广州入口  │
     │Oceanus   │ │Oceanus   │ │Oceanus   │
     │ 多活网关  │ │ 多活网关  │ │ 多活网关  │
     └────┬─────┘ └────┬─────┘ └────┬─────┘
          │            │            │
          ▼            ▼            ▼
     ┌──────────┐ ┌──────────┐ ┌──────────┐
     │  SET-A   │ │  SET-B   │ │  SET-C   │
     │ (北京)    │ │ (上海)    │ │ (深圳)    │
     │          │ │          │ │          │
     │ 付款方ID  │ │ 付款方ID  │ │ 付款方ID  │
     │ 0-33%    │ │ 34-66%   │ │ 67-100%  │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │支付服务│ │ │ │支付服务│ │ │ │支付服务│ │
     │ │账务服务│ │ │ │账务服务│ │ │ │账务服务│ │
     │ │风控服务│ │ │ │风控服务│ │ │ │风控服务│ │
     │ │结算服务│ │ │ │结算服务│ │ │ │结算服务│ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │支付DB │ │ │ │支付DB │ │ │ │支付DB │ │
     │ │账务DB │ │ │ │账务DB │ │ │ │账务DB │ │
     │ │用户DB │ │ │ │用户DB │ │ │ │用户DB │ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     │ (MGR)    │ │ (MGR)    │ │ (MGR)    │
     │          │ │          │ │          │
     │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │
     │ │Squirrel │ │ │ │Squirrel │ │ │ │Squirrel │ │
     │ │MQ    │ │ │ │MQ    │ │ │ │MQ    │ │
     │ └──────┘ │ │ └──────┘ │ │ └──────┘ │
     └──────────┘ └──────────┘ └──────────┘
          │            │            │
          └────────────┼────────────┘
                       │
                       ▼
              ┌──────────────┐
              │   中心服务     │
              │ · 全局配置    │
              │ · 运营后台    │
              │ · 反洗钱      │
              │ · 监管报送    │
              └──────────────┘
```

---

## 三十三、附录C：SET化改造Checklist

### C.1 改造前Checklist

- [ ] 确定业务是否适合SET化（规模、容灾需求、可分片性）
- [ ] 确定分片维度（LBS/用户ID/付款方ID/订单ID等）
- [ ] 确定SET数量和部署地域
- [ ] 设计SET间互备关系（两两互备）
- [ ] 设计SET间数据同步方案（DTS、MGR、Binlog）
- [ ] 评估中心服务清单（哪些服务不需要SET化）
- [ ] 设计SET内闭环方案（减少跨SET调用）
- [ ] 设计容灾切换方案（RPO/RTO目标）
- [ ] 准备测试环境（至少2个SET）
- [ ] 准备数据迁移方案（历史数据拆分）
- [ ] 准备回滚方案（万一失败如何恢复）

### C.2 改造中Checklist

- [ ] 修改数据库表结构（增加SET分片键）
- [ ] 修改数据访问层（按分片键路由到对应SET）
- [ ] 修改服务间调用（识别并处理跨SET调用）
- [ ] 修改缓存层（SET内缓存独立）
- [ ] 修改消息队列（SET内Topic独立）
- [ ] 配置Oceanus路由规则
- [ ] 配置OCTO RPC路由规则
- [ ] 配置Lion按SET隔离配置
- [ ] 配置CAT按SET监控
- [ ] 进行单元测试（分片算法正确性）
- [ ] 进行集成测试（服务间调用链路）
- [ ] 进行性能测试（SET内延迟、吞吐量）
- [ ] 进行容灾测试（SET切换）

### C.3 上线后Checklist

- [ ] 监控SET内闭环率（目标 > 99%）
- [ ] 监控跨SET调用量和延迟
- [ ] 监控数据同步延迟（目标 < 1s）
- [ ] 监控各SET资源使用率
- [ ] 监控SET错误率
- [ ] 准备容灾切换SOP
- [ ] 定期进行断网演练（至少每季度一次）
- [ ] 定期评估SET是否需要扩容
- [ ] 持续优化SET内闭环率
- [ ] 持续优化跨SET调用链路

---

## 三十四、附录D：SET化故障排查手册

### D.1 常见故障类型

| 故障类型 | 现象 | 排查方法 | 解决方案 |
|----------|------|----------|----------|
| 路由错误 | 请求被路由到错误的SET | 检查Oceanus路由规则；检查请求参数 | 修正路由规则；检查参数提取逻辑 |
| 数据不一致 | 不同SET数据不一致 | 检查DTS同步状态；检查binlog | 修复DTS同步；手动同步数据 |
| 数据同步延迟 | SET间数据同步延迟高 | 检查DTS监控；检查网络带宽；检查数据库负载 | 优化DTS配置；扩容网络带宽；优化数据库 |
| 跨SET调用过多 | 跨SET调用量超过预期 | 检查CAT链路追踪；检查服务调用关系 | 优化服务部署；下沉服务到SET内 |
| SET内闭环率低 | 闭环率低于目标 | 检查CAT链路追踪；识别跨SET调用点 | 优化服务部署；增加SET内缓存 |
| 单SET故障 | 某个SET完全不可用 | 检查SET内服务状态；检查数据库状态；检查网络 | 触发容灾切换；切换到互备SET |
| 容灾切换失败 | 切换后业务不正常 | 检查数据同步延迟；检查SET配置一致性 | 回滚切换；修复数据同步问题 |
| 数据库脑裂 | 两个SET都认为自己是主 | 检查MGR状态；检查网络分区 | 手动干预；选择数据更完整的SET作为主 |

### D.2 紧急处理流程

**Step 1：确认故障现象**
- 查看监控大盘，确认故障SET和故障范围
- 确认是单SET故障还是全网故障
- 确认故障开始时间

**Step 2：通知相关人员**
- 通知业务方
- 通知DBA、SRE、基础架构团队
- 创建故障群，统一信息出口

**Step 3：尝试快速恢复**
- 如果是单SET故障，尝试重启SET内服务
- 如果是数据库故障，尝试切换主库
- 如果是网络故障，检查网络配置

**Step 4：触发容灾切换（如果快速恢复失败）**
- 确认数据同步延迟在可接受范围
- 执行容灾切换SOP
- 验证业务在互备SET上正常运行

**Step 5：持续监控**
- 监控切换后的业务指标
- 监控数据一致性
- 准备回滚方案（如果切换后出现问题）

**Step 6：故障复盘**
- 记录故障时间线
- 分析故障根因
- 制定改进措施
- 更新SOP和预案

---

## 三十五、附录E：SET化相关美团内部资源

| 资源类型 | 资源名称 | 链接/位置 | 说明 |
|----------|----------|-----------|------|
| 技术文档 | SET化架构设计文档 | docs.sankuai.com/article/12345 | 美团内部技术文档 |
| 技术文档 | 到家SET化方案 | docs.sankuai.com/article/12345 | 到家业务SET化详细方案 |
| 技术文档 | 金服SET化方案 | docs.sankuai.com/article/12345 | 金服业务SET化详细方案 |
| 技术文档 | 大禹平台设计 | docs.sankuai.com/article/12345 | 大禹平台技术设计文档 |
| 培训材料 | SET化架构培训 | 美团内部培训平台 | 新同学入门培训 |
| 代码库 | Oceanus网关 | 美团内部代码库 | 多活网关源码 |
| 代码库 | OCTO RPC | 美团内部代码库 | RPC框架源码 |
| 代码库 | DTS | 美团内部代码库 | 数据传输服务源码 |
| 平台 | 大禹平台 | traffic.sankuai.com | SET化管理平台 |
| 平台 | Lion配置中心 | apollo.mws.sankuai.com | 配置管理平台 |
| 平台 | CAT监控 | cat.mws.sankuai.com | 全链路监控平台 |
| 平台 | Zebra数据库中间件 | 美团内部平台 | 数据库中间件 |
| 平台 | Squirrel缓存 | 美团内部平台 | 分布式缓存平台 |
| 平台 | Crane调度 | 美团内部平台 | 任务调度平台 |
| 平台 | Mafka消息队列 | 美团内部平台 | 消息队列平台 |

---

## 三十六、附录F：推荐阅读与参考资料

### F.1 技术博客文章

1. 《外卖平台SET化架构实践》
2. 《美团金服异地多活架构演进》
3. 《美团大禹平台：SET化统一管控》
4. 《美团Oceanus多活网关设计》
5. 《美团OCTO RPC框架演进》
6. 《美团DTS数据传输服务》
7. 《美团MySQL MGR实践》
8. 《美团TiDB在SET化中的应用》

### F.2 业界参考资料

1. 阿里巴巴：《淘宝单元化架构实践》
2. 蚂蚁金服：《LDC单元化架构》
3. 京东：《京东零售SET化架构》
4. Google：《Spanner: Google's Globally Distributed Database》
5. Amazon：《Dynamo: Amazon's Highly Available Key-value Store》
6. Netflix：《Netflix Chaos Engineering》
7. CAP Theorem：《Brewer's CAP Theorem》
8. Raft Consensus：《In Search of an Understandable Consensus Algorithm》

### F.3 书籍推荐

1. 《Designing Data-Intensive Applications》— Martin Kleppmann
2. 《Building Microservices》— Sam Newman
3. 《Cloud Native Patterns》— Cornelia Davis
4. 《Site Reliability Engineering》— Google SRE Team
5. 《The Art of Scalability》— Martin L. Abbott
6. 《Microservices Patterns》— Chris Richardson
7. 《Database Internals》— Alex Petrov
8. 《Distributed Systems》— Maarten van Steen

---

## 三十七、附录G：SET化演进历史大事记

| 时间 | 事件 | 意义 |
|------|------|------|
| 2016 | 到家业务启动SET化调研 | 美团SET化元年 |
| 2017 Q1 | 到家SET化技术方案确定 | 选择LBS分片维度 |
| 2017 Q2 | 到家北京SET试点上线 | 首个SET化单元 |
| 2017 Q3-Q4 | 到家扩展到上海、广州、深圳 | 四SET格局初步形成 |
| 2018 | 金服启动SET化调研 | 支付业务开始SET化探索 |
| 2018 | 到家完成4主SET+2小SET | 到家SET化体系基本成熟 |
| 2019 | 金服同城双SET试点 | 金服SET化开始落地 |
| 2020 | 金服断网演练开始 | 容灾能力开始验证 |
| 2021 | 到家持续优化SET内闭环率 | 架构优化深化 |
| 2022 | 金服推进异地SET建设 | 异地容灾能力增强 |
| 2023 | 大禹平台开始建设 | SET化管理平台化 |
| 2024 | 到家日常SET容灾切换1次 | 容灾切换常态化 |
| 2025 | 金服完成3次直接断网演练 | 金融级容灾能力验证 |
| 2025 | 建设上海金融专区SET | 金服异地容灾能力提升 |
| 2025-2026 | 大禹平台持续推进 | 路由统一化、一键建站 |

---

## 三十八、附录H：SET化相关核心指标参考

| 指标 | 目标值 | 说明 |
|------|--------|------|
| SET内闭环率 | > 99% | 请求在SET内完成全链路处理的比例 |
| 跨SET调用延迟 | < 50ms P99 | 跨SET RPC调用的延迟 |
| 数据同步延迟 | < 1s P99 | SET间数据同步的延迟 |
| 容灾切换RPO | 0-10s | 恢复点目标，数据丢失容忍度 |
| 容灾切换RTO | < 3min | 恢复时间目标，故障恢复时间 |
| 断网演练频率 | 每季度1次 | 容灾演练的频率 |
| SET资源冗余 | 30% | 每个SET预留的资源冗余 |
| 单SET容量上限 | 设计容量的80% | 触发扩容的阈值 |
| 数据库主从同步延迟 | < 100ms | 主从数据同步延迟 |
| MGR半同步超时 | 10s | 半同步复制超时时间 |

---

## 三十九、附录I：SET化技术决策记录

### I.1 为什么到家选择LBS而不是用户ID作为分片维度？

**决策背景**：
到家业务（外卖、配送）涉及用户、商家、骑手三方。如果选择用户ID作为分片维度，那么用户下单后，商家和骑手的数据查询需要跨SET。

**决策分析**：
- 用户ID分片：用户订单和商家订单分布在不同SET，查询商家订单需要跨SET
- LBS（城市）分片：用户、商家、骑手都在同一城市，订单数据集中在同一SET
- 结论：LBS分片天然实现三方数据的本地闭环

**决策结果**：选择LBS（城市）作为分片维度

### I.2 为什么金服选择付款方ID而不是订单ID作为分片维度？

**决策背景**：
金服业务（支付）的核心是付款和账务。如果选择订单ID作为分片维度，那么同一用户的多次付款可能分布在不同SET，不利于用户账务查询。

**决策分析**：
- 订单ID分片：同一用户的多次付款分布在不同SET，用户查询账单需要跨SET
- 付款方ID分片：同一用户的所有付款和账务都在同一SET，用户账单查询在SET内完成
- 结论：付款方ID分片保证用户级数据闭环

**决策结果**：选择付款方ID作为分片维度

### I.3 为什么到家缺少同城容灾？

**决策背景**：
同城容灾（同一城市多可用区）可以应对可用区级故障，但建设成本高。

**决策分析**：
- 建设同城容灾：需要每个城市部署多个可用区，成本增加50%+
- 不建设同城容灾：可用区级故障需要异地切换，RTO较长（分钟级）
- 权衡：到家业务对RTO要求不是金融级（分钟级可接受），优先控制成本

**决策结果**：到家暂时不建设同城容灾，依赖异地互备。但规划中考虑增加同城容灾。

### I.4 为什么金服追求RPO=0？

**决策背景**：
金融数据对一致性要求极高，任何数据丢失都可能导致严重的财务问题。

**决策分析**：
- RPO=0：数据零丢失，金融级标准
- RPO>0：允许少量数据丢失，成本较低
- 监管要求：金融支付业务通常要求数据零丢失

**决策结果**：金服通过MGR半同步复制和RDS强一致方案，追求RPO=0。

---

## 四十、附录J：美团内部技术体系关联图

```
美团基础技术体系
│
├── 中间件平台（INF）
│   ├── OCTO（RPC框架）
│   ├── Mafka（消息队列）
│   ├── Squirrel（分布式缓存）
│   ├── Cellar（持久化KV）
│   ├── Zebra（数据库中间件）
│   ├── Lion（配置中心）
│   ├── Crane（任务调度）
│   ├── Oceanus（多活网关）
│   └── ...
│
├── 起源（DP）
│   ├── MySQL（关系数据库）
│   ├── TiDB（分布式数据库）
│   ├── Hive（离线数仓）
│   ├── Mafka（大数据消息队列）
│   ├── Flink（实时计算）
│   ├── Elasticsearch（搜索引擎）
│   └── ...
│
├── Avatar（SRE）
│   ├── CAT（全链路监控）
│   ├── 天网（告警平台）
│   ├── 堡垒机（运维安全）
│   ├── 发布平台（CI/CD）
│   └── ...
│
├── 容器平台（PaaS）
│   ├── Hulk（容器编排）
│   ├── Docker（容器运行时）
│   ├── 镜像仓库
│   └── ...
│
└── SET化平台（大禹）
    ├── SET管理
    ├── 路由管控
    ├── 一键建站
    ├── 容灾管理
    └── 监控可视化
```

---

## 四十一、附录K：SET化架构下的微服务治理

### K.1 服务注册与发现的SET化适配

在SET化架构中，服务注册与发现需要考虑SET维度：

**OCTO注册中心的数据结构**：
```
服务注册表（Service Registry）
├── 服务名：com.sankuai.order.service
│   ├── SET-A实例列表
│   │   ├── 10.0.1.101:8080 (weight=100, healthy=true)
│   │   ├── 10.0.1.102:8080 (weight=100, healthy=true)
│   │   └── 10.0.1.103:8080 (weight=100, healthy=true)
│   ├── SET-B实例列表
│   │   ├── 10.0.2.101:8080 (weight=100, healthy=true)
│   │   ├── 10.0.2.102:8080 (weight=100, healthy=true)
│   │   └── 10.0.2.103:8080 (weight=100, healthy=true)
│   ├── SET-C实例列表
│   │   ├── 10.0.3.101:8080 (weight=100, healthy=true)
│   │   ├── 10.0.3.102:8080 (weight=100, healthy=true)
│   │   └── 10.0.3.103:8080 (weight=100, healthy=true)
│   └── 中心服务实例列表（无SET标签）
│       ├── 10.0.0.101:8080 (weight=100, healthy=true)
│       └── 10.0.0.102:8080 (weight=100, healthy=true)
```

**服务发现的SET化逻辑**：
```java
public List<Instance> discover(String serviceName, String currentSet) {
    // 1. 获取当前SET的服务实例（优先）
    List<Instance> setInstances = registry.getInstances(serviceName, currentSet);
    
    if (!setInstances.isEmpty() && hasHealthyInstance(setInstances)) {
        // SET内有健康实例，优先使用SET内实例
        return filterHealthy(setInstances);
    }
    
    // 2. SET内无健康实例，降级到中心服务实例
    List<Instance> centerInstances = registry.getInstances(serviceName, "center");
    if (!centerInstances.isEmpty()) {
        return filterHealthy(centerInstances);
    }
    
    // 3. 中心服务也无实例，跨SET调用（最后的手段）
    log.warn("No instances found in set={}, fallback to cross-set call", currentSet);
    return findCrossSetInstances(serviceName, currentSet);
}
```

### K.2 负载均衡的SET化策略

SET化架构中的负载均衡策略：

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| SET优先 | 优先选择同SET实例 | 默认策略，保证SET内闭环 |
| 同地域优先 | 优先选择同地域SET实例 | 跨SET但同地域时 |
| 权重轮询 | 按权重轮询所有实例 | 中心服务，无SET标签 |
| 最小连接数 | 选择当前连接数最少的实例 | 长连接场景 |
| 一致性哈希 | 按分片键哈希选择实例 | 需要保证同一请求落到同一实例 |

### K.3 熔断降级的SET化策略

在SET化架构中，熔断降级需要考虑SET维度：

```java
@HystrixCommand(
    commandProperties = {
        @HystrixProperty(name = "circuitBreaker.enabled", value = "true"),
        @HystrixProperty(name = "circuitBreaker.requestVolumeThreshold", value = "20"),
        @HystrixProperty(name = "circuitBreaker.errorThresholdPercentage", value = "50"),
        @HystrixProperty(name = "circuitBreaker.sleepWindowInMilliseconds", value = "5000")
    },
    fallbackMethod = "fallbackBySet"
)
public Order getOrder(String orderId) {
    // 正常调用
}

// SET化降级：SET内服务不可用时，返回缓存数据或默认值
public Order fallbackBySet(String orderId, Throwable ex) {
    String currentSet = SetContext.getCurrentSet();
    log.warn("SET={} service unavailable, fallback for orderId={}", currentSet, orderId);
    
    // 1. 尝试从本地缓存获取
    Order cachedOrder = localCache.get(orderId);
    if (cachedOrder != null) {
        return cachedOrder;
    }
    
    // 2. 返回默认值（空订单）
    return Order.empty(orderId);
}
```

---

## 四十二、附录L：SET化与云原生架构的结合

### L.1 Hulk在SET化中的角色

美团内部容器平台基于Hulk，在SET化架构中：

- **Namespace隔离**：每个SET对应一个K8s Namespace，实现资源和网络的隔离
- **Label标识**：Pod和Service通过Label标记所属SET（如 `set=set-a`）
- **亲和性调度**：服务优先调度到同SET的节点，保证SET内闭环
- **网络策略**：通过NetworkPolicy限制跨SET的Pod间通信

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: order-service-xxx
  labels:
    app: order-service
    set: set-a          # SET标签
    version: v1.0.0
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:
        nodeSelectorTerms:
        - matchExpressions:
          - key: set
            operator: In
            values:
            - set-a          # 优先调度到SET-A节点
  containers:
  - name: order-service
    image: registry.sankuai.com/order-service:v1.0.0
```

### L.2 服务网格（Service Mesh）与SET化

如果引入Service Mesh（如Istio），可以实现更细粒度的SET化流量控制：

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: VirtualService
metadata:
  name: order-service-vs
spec:
  hosts:
  - order-service
  http:
  - match:
    - headers:
        x-set-id:
          exact: set-a
    route:
    - destination:
        host: order-service
        subset: set-a
  - match:
    - headers:
        x-set-id:
          exact: set-b
    route:
    - destination:
        host: order-service
        subset: set-b
```

---

## 四十三、附录M：SET化架构下的数据治理

### M.1 数据质量监控

SET化架构中，数据质量监控需要关注：

| 监控维度 | 指标 | 说明 |
|----------|------|------|
| 数据完整性 | 各SET数据量分布 | 检查数据是否均匀分布到各SET |
| 数据一致性 | SET间数据差异率 | 检查互备SET的数据差异 |
| 数据时效性 | 数据同步延迟 | 检查SET间数据同步的延迟 |
| 数据准确性 | 数据校验失败率 | 检查数据校验规则的失败率 |

### M.2 数据治理工具

- **数据血缘追踪**：追踪数据在各SET间的流转路径
- **数据质量规则**：定义各SET的数据质量校验规则
- **数据治理大盘**：可视化各SET的数据质量指标
- **数据治理告警**：数据质量异常时自动告警

---

## 四十四、附录N：SET化架构下的安全架构

### N.1 数据安全

SET化架构的数据安全策略：

- **数据隔离**：各SET数据物理隔离，降低数据泄露风险
- **数据加密**：SET间数据传输使用TLS加密
- **数据脱敏**：跨SET同步时，敏感数据自动脱敏
- **数据审计**：所有SET的数据操作都有审计日志

### N.2 网络安全

- **网络隔离**：各SET使用独立的网络VPC/VLAN
- **防火墙**：SET间通信通过防火墙控制，只允许必要的端口和协议
- **DDoS防护**：各SET独立部署DDoS防护，避免单SET故障影响全网

### N.3 访问安全

- **身份认证**：跨SET调用需要身份认证和授权
- **API网关**：SET间的API调用通过API网关统一管控
- **限流熔断**：SET间调用需要限流和熔断，防止级联故障

---

## 四十五、附录O：SET化架构下的性能优化

### O.1 性能优化方向

SET化架构中的性能优化方向：

| 优化方向 | 优化手段 | 预期效果 |
|----------|----------|----------|
| SET内闭环 | 减少跨SET调用 | 延迟降低50%+ |
| 本地缓存 | 增加Caffeine/Guava Cache | 热点数据延迟 < 1ms |
| 数据库优化 | 索引优化、SQL优化、分库分表 | 数据库延迟降低30% |
| 网络优化 | 连接池优化、TCP参数优化 | 网络延迟降低20% |
| 序列化优化 | 使用Protobuf/Kryo替代JSON | 序列化延迟降低50% |

### O.2 性能压测方法

SET化架构的性能压测需要考虑：

1. **单SET压测**：验证单个SET的容量上限
2. **全链路压测**：验证所有SET同时运行的性能
3. **容灾切换压测**：验证容灾切换期间的性能影响
4. **跨SET调用压测**：验证跨SET调用的性能瓶颈

---

## 四十六、附录P：SET化架构下的容量规划

### P.1 容量规划方法

SET化架构的容量规划方法：

1. **业务增长预测**：预测未来1-3年的业务增长趋势
2. **单SET容量评估**：评估单个SET能承载的业务量
3. **SET数量规划**：根据业务量和单SET容量，确定SET数量
4. **资源冗余规划**：预留30%的资源冗余，应对突发流量
5. **扩容规划**：制定扩容SOP，确保扩容过程不影响业务

### P.2 容量规划示例

```
业务：到家外卖
当前状况：
- 日均订单：1000万单
- 峰值QPS：50000
- 当前SET数：4个（SET-A到SET-D）
- 单SET峰值QPS：12500
- 单SET容量上限：20000 QPS

1年后预测：
- 日均订单：1500万单（增长50%）
- 峰值QPS：75000（增长50%）

容量规划：
- 当前总容量：4 * 20000 = 80000 QPS
- 1年后需求：75000 QPS
- 预留30%冗余：75000 * 1.3 = 97500 QPS
- 需要SET数：97500 / 20000 = 4.875 → 5个SET
- 结论：需要新增1个SET（SET-E）
```

---

## 四十七、附录Q：SET化架构下的异地多活进阶

### Q.1 从异地互备到异地多活

SET化架构的演进路线：

| 阶段 | 架构 | 特点 | 成熟度 |
|------|------|------|--------|
| 阶段一 | 单中心 | 所有服务在一个数据中心 | 基础 |
| 阶段二 | 同城双活 | 同城两个机房，数据同步 | 初级 |
| 阶段三 | 异地互备 | 异地两个SET，数据双向同步 | 中级 |
| 阶段四 | 异地多活 | 多个异地SET，同时处理流量 | 高级 |
| 阶段五 | 全球多活 | 全球多个SET，就近处理 | 顶级 |

### Q.2 异地多活的技术挑战

异地多活比异地互备更难：

- **数据冲突**：多个SET同时写入同一份数据，冲突概率大增
- **网络延迟**：异地延迟更高，数据同步更慢
- **一致性保证**：需要更复杂的一致性协议（如Paxos、Raft）
- **监控复杂度**：需要监控多个SET的实时状态，自动调度流量

### Q.3 美团异地多活规划

美团的异地多活规划：

- **短期（2025-2026）**：金服推进异地多活，到家优化异地互备
- **中期（2026-2028）**：到家业务推进异地多活，新增海外SET
- **长期（2028+）**：全球多活，支持东南亚、欧洲等海外SET

---

## 四十八、附录R：SET化架构下的AI与智能化

### R.1 智能流量调度

基于AI的智能流量调度：

- **实时负载感知**：根据各SET的实时负载，动态调整流量分配
- **预测性调度**：根据历史数据预测流量高峰，提前调度
- **异常检测**：自动检测SET异常，自动切换流量

### R.2 智能容灾决策

基于AI的智能容灾决策：

- **故障预测**：通过机器学习预测SET故障，提前切换
- **自动切换**：故障发生时，自动决策是否切换、切换到哪个SET
- **智能回滚**：切换后自动监控，异常时自动回滚

### R.3 智能容量管理

基于AI的智能容量管理：

- **容量预测**：预测未来容量需求，自动触发扩容
- **弹性伸缩**：根据实时负载，自动扩缩容
- **成本优化**：在满足SLA的前提下，优化资源成本

---

## 四十九、附录S：SET化架构与行业趋势

### S.1 云原生与SET化

云原生技术（Hulk、Service Mesh、Serverless）与SET化架构的结合：

- **容器化部署**：每个SET的服务容器化部署，快速拉起
- **Service Mesh**：实现细粒度的SET间流量控制
- **Serverless**：SET内的服务Serverless化，按需扩缩容
- **GitOps**：SET配置通过Git管理，自动化部署

### S.2 边缘计算与SET化

边缘计算与SET化架构的结合：

- **边缘SET**：在边缘节点部署轻量级SET，就近处理用户请求
- **边缘缓存**：在边缘节点缓存热点数据，降低延迟
- **边缘计算**：在边缘节点执行计算密集型任务，降低中心负载

### S.3 5G与SET化

5G技术与SET化架构的结合：

- **低延迟**：5G的低延迟特性，使得SET间通信更快
- **大带宽**：5G的大带宽特性，使得SET间数据同步更快
- **边缘接入**：5G的边缘接入特性，使得边缘SET更可行

---

## 五十、附录T：最终总结与展望

### T.1 SET化架构的核心价值总结

SET化架构是美团应对业务规模爆发式增长、保障高可用性的核心架构方案。它的核心价值在于：

1. **水平扩展**：通过新增SET即可线性扩展系统容量
2. **异地容灾**：通过SET间互备，实现异地容灾，保障业务连续性
3. **故障隔离**：单个SET故障不影响其他SET，降低故障影响面
4. **就近服务**：用户就近访问SET，降低延迟，提升体验
5. **灵活部署**：按地域、业务线灵活部署，支持差异化策略

### T.2 架构演进的关键经验

从美团SET化架构的演进中，我们可以学到：

1. **架构是演进而非设计的**：SET化架构不是一次性设计出来的，而是在业务增长过程中逐步演进出来的
2. **业务驱动架构**：架构演进的动力来自业务需求，而非技术理想
3. **平台化是趋势**：从业务方自己维护SET化逻辑，到平台统一管控，是架构演进的必然趋势
4. **容灾需要演练**：只有常态化演练，才能真正验证容灾能力，发现问题
5. **没有银弹**：SET化解决了扩展和容灾，但引入了数据同步、跨SET调用等新问题，需要持续优化

### T.3 未来展望

SET化架构的未来发展方向：

1. **智能化**：AI驱动的智能流量调度、智能容灾、智能容量管理
2. **云原生化**：与Hulk、Service Mesh、Serverless深度融合
3. **全球化**：支持海外部署，实现全球多活
4. **多云化**：支持多云部署，实现多云容灾
5. **无感知化**：业务方完全不感知SET的存在，像使用单体系统一样使用分布式系统

### T.4 给架构师的建议

如果你正在设计或改造一个大型分布式系统，以下是一些建议：

1. **尽早考虑扩展性**：系统架构设计时，尽早考虑未来的扩展性，避免后期大规模重构
2. **数据分片是关键**：数据分片是分布式系统扩展的核心，分片维度的选择至关重要
3. **容灾不是可选项**：对于大型系统，容灾能力是必备能力，不是可选项
4. **平台化优于定制化**：将架构能力平台化，降低业务方的使用成本
5. **演练验证一切**：容灾能力只有通过演练才能验证，不能仅仅停留在纸面设计
6. **持续优化**：架构演进是一个持续的过程，需要持续优化和改进

---

**最终版本：V3.0**
**总行数：3000+ 行**
**涵盖章节：50 章 + 20 附录**
**最后更新：2024 年**

**文档作者：基于美团内部技术分享、架构文档、技术博客整理**
**面向读者：美团新同学、对分布式架构感兴趣的工程师、架构师**

**声明：本文档基于美团公开技术分享和内部资料整理，仅供学习参考。具体实现细节可能随时间演进，以美团内部最新文档为准。**

**祝你在分布式架构的学习之路上，越走越远，越走越深。**

