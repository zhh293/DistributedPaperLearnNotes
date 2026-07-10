# RPC框架与服务治理架构设计

## 一、问题背景

### 1.1 为什么需要RPC框架

在微服务架构下，一个完整的业务系统被拆分为多个独立部署的服务，服务之间需要通过网络进行通信。最原始的方式是通过HTTP RESTful接口互相调用，但这种方式存在诸多问题：

1. **开发效率低**：每次调用都需要手动构造HTTP请求、处理序列化/反序列化、管理连接等，大量重复代码。
2. **性能不足**：HTTP/1.1协议文本格式冗余大，短连接模式下TCP握手开销高，难以满足内网高频调用场景。
3. **治理能力缺失**：缺乏统一的服务发现、负载均衡、熔断限流等治理功能，每个团队"重复造轮子"。
4. **运维困难**：缺少监控告警、调用链路追踪、灰度发布等运维手段，故障定位效率低。

RPC（Remote Procedure Call，远程过程调用）框架的核心目标是：**让远程调用像本地方法调用一样简单**。通过代理模式屏蔽网络通信细节，通过高效的序列化协议提升性能，通过注册中心实现服务的自动发现与治理。

### 1.2 不解决的后果

如果没有统一的RPC框架和服务治理体系，随着服务数量增长到数千甚至上万级别，会面临以下严重问题：

- **系统雪崩**：一个下游服务超时会导致上游线程池耗尽，进而级联影响整个调用链。
- **发布困难**：无法做到灰度发布，每次上线都是全量发布，风险极高。
- **故障定位慢**：调用链路涉及几十个服务，没有链路追踪系统难以定位问题根源。
- **资源浪费**：各团队分别开发通信组件，质量参差不齐，研发效率低下。

### 1.3 典型业务场景

以一次外卖下单流程为例：用户下单请求从网关进入后，需要依次调用用户服务（校验身份）、商品服务（查询商品）、库存服务（扣减库存）、优惠券服务（核销优惠券）、订单服务（创建订单）、支付服务（发起支付）。这条调用链路涉及6个以上的微服务，每个环节都需要可靠的RPC通信和完善的服务治理。

---

## 二、整体架构设计

### 2.1 分层架构

一个成熟的RPC框架通常采用分层架构设计，参考Dubbo的十层架构思想，核心分层如下：

```
+-----------------------------------------------------------+
|                   Service层（业务接口）                      |
+-----------------------------------------------------------+
|                   Config层（配置管理）                       |
+-----------------------------------------------------------+
|                   Proxy层（服务代理）                        |
+-----------------------------------------------------------+
|                  Registry层（注册中心）                      |
+-----------------------------------------------------------+
|                  Cluster层（集群路由）                       |
+-----------------------------------------------------------+
|                  Monitor层（监控统计）                       |
+-----------------------------------------------------------+
|                 Protocol层（远程调用协议）                    |
+-----------------------------------------------------------+
|                 Exchange层（信息交换）                       |
+-----------------------------------------------------------+
|                 Transport层（网络传输）                      |
+-----------------------------------------------------------+
|                 Serialize层（序列化）                        |
+-----------------------------------------------------------+
```

各层职责说明：

| 层次 | 职责 | 关键设计 |
|------|------|----------|
| **Service层** | 业务接口定义与实现 | IDL或Java Interface |
| **Config层** | 配置管理，XML/注解/API配置 | ServiceConfig, ReferenceConfig |
| **Proxy层** | 透明代理，生成客户端Stub | JDK动态代理/Javassist/ByteBuddy |
| **Registry层** | 服务注册与发现 | ZooKeeper/Nacos/Consul/自研AP存储 |
| **Cluster层** | 集群容错与路由 | Failover/Failfast/负载均衡/路由规则 |
| **Monitor层** | 调用统计与监控 | 调用次数、耗时、成功率统计上报 |
| **Protocol层** | RPC协议封装 | Dubbo协议/gRPC/Thrift |
| **Exchange层** | Request-Response模型 | 同步转异步，Future模式 |
| **Transport层** | 网络传输 | Netty NIO框架 |
| **Serialize层** | 数据序列化 | Hessian2/Protobuf/Thrift/JSON |

### 2.2 核心组件交互

```
                        ┌──────────────┐
                        │  Registry    │
                        │  注册中心     │
                        └──────┬───────┘
                    ②注册 │        │ ③订阅
                    ┌─────┘        └─────┐
                    │                    │
              ┌─────▼─────┐       ┌──────▼─────┐
              │  Provider  │       │  Consumer   │
              │  服务提供方 │◄──────│  服务消费方  │
              └─────┬──────┘  ④调用 └──────┬──────┘
                    │                      │
                    └──────┐    ┌──────────┘
                           │    │
                     ┌─────▼────▼─────┐
                     │    Monitor      │
                     │    监控中心      │
                     └────────────────┘
```

交互流程：
1. **①启动**：Provider启动时，将自身地址、权重、状态等元信息注册到Registry。
2. **②注册**：Provider通过本地Agent代理将信息注册到注册中心，注册中心持久化存储。
3. **③订阅**：Consumer启动时向Registry订阅所需服务，获取Provider列表并缓存本地。
4. **④调用**：Consumer根据负载均衡策略选择一个Provider发起RPC调用。
5. **⑤监控**：调用数据异步上报到Monitor用于统计和告警。

### 2.3 设计权衡

**注册中心的CP与AP选择**：

| 维度 | CP模型（ZooKeeper） | AP模型（自研KV/Nacos AP模式） |
|------|---------------------|-------------------------------|
| 一致性 | 强一致，数据写入后所有节点可见 | 最终一致，存在短暂不一致窗口 |
| 可用性 | Leader选举期间不可用（几十秒） | 高可用，单节点故障不影响服务 |
| 性能 | 写入需过半节点确认，性能受限 | 写入即返回，性能高 |
| 适用场景 | 服务数较少（< 万级） | 服务数万级以上的大规模场景 |

在大规模微服务场景下，注册中心通常从CP型（如ZooKeeper）演进为AP型，因为：
- 注册中心短暂不一致（秒级）对业务影响可控——Consumer本地有缓存。
- 但注册中心不可用会导致新节点无法注册、下线节点无法感知，影响更严重。

---

## 三、核心链路设计

### 3.1 服务注册与发现

#### 3.1.1 注册流程

```
Provider启动
    │
    ▼
初始化服务实例（绑定端口、注册Handler）
    │
    ▼
构建注册元信息（IP、Port、Weight、Status、Protocol、Version）
    │
    ▼
通过本地SideAgent代理注册到注册中心
    │
    ▼
注册中心持久化存储节点信息
    │
    ▼
注册中心通知所有订阅方有新节点上线
```

Provider注册的元数据结构：

```java
public class ServiceInstance {
    private String serviceName;    // 服务名，如 com.example.OrderService
    private String host;           // IP地址
    private int port;              // 端口号
    private int weight;            // 权重（用于负载均衡）
    private String protocol;       // 协议：thrift/grpc/http
    private String version;        // 服务版本
    private String group;          // 服务分组
    private ServiceStatus status;  // 状态：ALIVE/STARTING/DEAD/DISABLED
    private Map<String, String> metadata; // 扩展元数据
    private long lastHeartbeat;    // 最后心跳时间
    
    public enum ServiceStatus {
        ALIVE,      // 正常运行，可接收流量
        STARTING,   // 启动中，不接收流量
        DEAD,       // 未启动
        DISABLED    // 手动禁用
    }
}
```

#### 3.1.2 发现流程

Consumer发现服务的流程采用**推拉结合**的模式：

```java
public class ServiceDiscovery {
    
    // 本地缓存的服务列表
    private final ConcurrentMap<String, List<ServiceInstance>> localCache = 
        new ConcurrentHashMap<>();
    
    /**
     * 订阅服务 - 拉取初始列表 + 注册Watcher监听变更
     */
    public List<ServiceInstance> subscribe(String serviceName) {
        // 1. 首次全量拉取
        List<ServiceInstance> instances = registryClient.fetchInstances(serviceName);
        localCache.put(serviceName, instances);
        
        // 2. 注册Watcher，监听节点变更事件
        registryClient.watch(serviceName, event -> {
            // 增量更新本地缓存
            switch (event.getType()) {
                case NODE_ADDED:
                    addInstance(serviceName, event.getInstance());
                    break;
                case NODE_REMOVED:
                    removeInstance(serviceName, event.getInstance());
                    break;
                case NODE_UPDATED:
                    updateInstance(serviceName, event.getInstance());
                    break;
            }
        });
        
        return instances;
    }
    
    /**
     * 获取服务实例列表（从本地缓存读取）
     */
    public List<ServiceInstance> getInstances(String serviceName) {
        List<ServiceInstance> instances = localCache.get(serviceName);
        if (instances == null || instances.isEmpty()) {
            // 缓存未命中，降级到直接查询注册中心
            instances = registryClient.fetchInstances(serviceName);
            localCache.put(serviceName, instances);
        }
        return instances;
    }
}
```

#### 3.1.3 健康检查机制

注册中心需要通过健康检查机制及时发现不健康的节点：

**方案一：客户端心跳（主动上报）**
- Provider定期（如每5秒）向注册中心发送心跳
- 注册中心如果超过15秒未收到心跳，标记节点为不健康
- 超过30秒未收到心跳，从注册列表中移除

**方案二：服务端探测（被动检测）**
- 独立的Scanner组件定期对所有Provider节点进行TCP端口探测
- 发现节点不健康时降低其权重，而不是立刻移除（避免网络抖动导致误判）
- 权重逐步衰减：100% -> 50% -> 20% -> 0%（移除）

```java
public class HealthChecker {
    
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(4);
    
    /**
     * 健康检查逻辑 - 对每个节点进行TCP端口探测
     */
    public void startHealthCheck(List<ServiceInstance> instances) {
        scheduler.scheduleAtFixedRate(() -> {
            for (ServiceInstance instance : instances) {
                boolean healthy = tcpProbe(instance.getHost(), instance.getPort(), 3000);
                if (!healthy) {
                    // 连续3次探测失败才标记为不健康
                    int failCount = instance.incrementFailCount();
                    if (failCount >= 3) {
                        // 渐进式降权
                        int currentWeight = instance.getWeight();
                        int newWeight = Math.max(0, currentWeight / 2);
                        instance.setWeight(newWeight);
                        
                        if (newWeight == 0) {
                            // 权重降为0，通知注册中心移除
                            registryClient.unregister(instance);
                        }
                    }
                } else {
                    instance.resetFailCount();
                    // 恢复权重（缓慢恢复，避免流量突增）
                    instance.setWeight(Math.min(instance.getOriginalWeight(), 
                        instance.getWeight() + 10));
                }
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    
    private boolean tcpProbe(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

#### 3.1.4 注册中心高可用架构

注册中心本身也需要高可用设计，典型的架构演进路径：

**第一阶段：基于ZooKeeper**
- 使用ZK的临时节点存储服务实例
- 利用ZK的Watch机制实现变更推送
- 问题：ZK的Watch是一次性的，写入性能受限于Paxos/ZAB协议

**第二阶段：增加Controller缓存层**
- 在ZK前面增加Controller层，承担读请求和数据推送
- ZK只负责数据存储和一致性保证
- Controller从ZK全量同步数据，推送给Consumer

**第三阶段：替换为AP型存储**
- 底层存储从CP型的ZK替换为AP型的KV存储
- 支持更大规模的服务注册（百万级节点）
- 通过最终一致性保证数据收敛

### 3.2 序列化与协议设计

#### 3.2.1 序列化协议对比

| 协议 | 格式 | 性能 | 可读性 | 跨语言 | 体积 | 适用场景 |
|------|------|------|--------|--------|------|----------|
| **Protobuf** | 二进制 | 极高 | 差 | 优秀 | 最小 | 高性能内部通信 |
| **Thrift** | 二进制 | 高 | 差 | 优秀 | 小 | 跨语言RPC调用 |
| **Hessian2** | 二进制 | 高 | 差 | Java为主 | 较小 | Java生态内部通信 |
| **JSON** | 文本 | 低 | 好 | 优秀 | 大 | 对外API/调试 |
| **Kryo** | 二进制 | 极高 | 差 | 仅Java | 最小 | Java内部高性能场景 |

#### 3.2.2 Thrift IDL定义示例

```thrift
namespace java com.example.order

// 订单状态枚举
enum OrderStatus {
    CREATED = 0,
    PAID = 1,
    DELIVERING = 2,
    COMPLETED = 3,
    CANCELLED = 4
}

// 订单数据结构
struct Order {
    1: required i64 orderId,
    2: required i64 userId,
    3: required string shopName,
    4: required double totalPrice,
    5: optional OrderStatus status = OrderStatus.CREATED,
    6: optional i64 createTime,
    7: optional map<string, string> extraInfo
}

// 创建订单请求
struct CreateOrderRequest {
    1: required i64 userId,
    2: required list<i64> productIds,
    3: optional string couponCode
}

// 创建订单响应
struct CreateOrderResponse {
    1: required i32 code,
    2: optional string message,
    3: optional Order order
}

// 订单服务接口
service OrderService {
    CreateOrderResponse createOrder(1: CreateOrderRequest request),
    Order getOrder(1: i64 orderId),
    list<Order> listOrders(1: i64 userId, 2: i32 page, 3: i32 pageSize)
}
```

#### 3.2.3 RPC协议帧设计

一个RPC协议帧的典型结构：

```
+--------+--------+--------+--------+--------+--------+--------+
| Magic  | Version| MsgType| Status | RequestId       | BodyLen |
| 2bytes | 1byte  | 1byte  | 1byte  | 8bytes          | 4bytes  |
+--------+--------+--------+--------+-----------------+---------+
|                      Body (变长)                               |
|        序列化后的请求/响应数据                                   |
+---------------------------------------------------------------+
```

```java
public class RpcProtocol {
    // 魔数：用于快速识别RPC协议包
    public static final short MAGIC = (short) 0xDABB;
    
    private short magic;         // 魔数标识
    private byte version;        // 协议版本
    private byte messageType;    // 消息类型：0-Request, 1-Response, 2-Heartbeat
    private byte status;         // 状态码：0-OK, 1-Timeout, 2-ServerError
    private long requestId;      // 请求ID（用于匹配请求和响应）
    private int bodyLength;      // 消息体长度
    private byte[] body;         // 消息体
    
    /**
     * 编码：将协议对象编码为字节流
     */
    public byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(17 + body.length);
        buffer.putShort(magic);
        buffer.put(version);
        buffer.put(messageType);
        buffer.put(status);
        buffer.putLong(requestId);
        buffer.putInt(bodyLength);
        buffer.put(body);
        return buffer.array();
    }
    
    /**
     * 解码：从字节流中解码协议对象
     */
    public static RpcProtocol decode(ByteBuffer buffer) {
        RpcProtocol protocol = new RpcProtocol();
        protocol.magic = buffer.getShort();
        if (protocol.magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic number");
        }
        protocol.version = buffer.get();
        protocol.messageType = buffer.get();
        protocol.status = buffer.get();
        protocol.requestId = buffer.getLong();
        protocol.bodyLength = buffer.getInt();
        protocol.body = new byte[protocol.bodyLength];
        buffer.get(protocol.body);
        return protocol;
    }
}
```

#### 3.2.4 Request-Response关联机制

RPC通信基于TCP长连接，一个连接上会同时发送多个请求。通过requestId将请求和响应进行关联：

```java
public class RpcClient {
    
    // 请求ID到Future的映射，用于异步转同步
    private final ConcurrentMap<Long, CompletableFuture<RpcResponse>> pendingRequests = 
        new ConcurrentHashMap<>();
    
    // 原子递增的请求ID生成器
    private final AtomicLong requestIdGenerator = new AtomicLong(0);
    
    /**
     * 发送RPC请求（同步转异步）
     */
    public RpcResponse sendRequest(RpcRequest request, int timeoutMs) {
        long requestId = requestIdGenerator.incrementAndGet();
        request.setRequestId(requestId);
        
        // 1. 创建Future并注册到pendingRequests
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        
        try {
            // 2. 通过Netty Channel发送请求
            channel.writeAndFlush(request);
            
            // 3. 等待响应（带超时）
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcTimeoutException("RPC call timeout: " + timeoutMs + "ms");
        } finally {
            // 4. 移除pending请求
            pendingRequests.remove(requestId);
        }
    }
    
    /**
     * 接收响应时的回调处理
     */
    public void handleResponse(RpcResponse response) {
        long requestId = response.getRequestId();
        CompletableFuture<RpcResponse> future = pendingRequests.get(requestId);
        if (future != null) {
            future.complete(response);
        }
    }
}
```

### 3.3 网络通信模型

#### 3.3.1 基于Netty的NIO通信

RPC框架底层通信通常基于Netty实现，利用其高性能的NIO模型：

```java
public class RpcServer {
    
    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    
    public void start() throws InterruptedException {
        // Boss线程组：负责接收客户端连接（通常1个线程）
        bossGroup = new NioEventLoopGroup(1);
        // Worker线程组：负责处理I/O读写（通常CPU核心数*2）
        workerGroup = new NioEventLoopGroup(Runtime.getRuntime().availableProcessors() * 2);
        
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .option(ChannelOption.SO_BACKLOG, 1024)      // TCP连接队列大小
            .childOption(ChannelOption.SO_KEEPALIVE, true) // 开启TCP心跳
            .childOption(ChannelOption.TCP_NODELAY, true)  // 关闭Nagle算法
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    
                    // 1. 空闲检测：60秒无读写则触发事件
                    pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                    
                    // 2. 协议解码器：处理TCP粘包/拆包
                    pipeline.addLast(new LengthFieldBasedFrameDecoder(
                        65535, 13, 4, 0, 0));  // maxLen, offset, lengthFieldLen
                    
                    // 3. RPC协议编解码器
                    pipeline.addLast(new RpcDecoder());
                    pipeline.addLast(new RpcEncoder());
                    
                    // 4. 心跳处理器
                    pipeline.addLast(new HeartbeatHandler());
                    
                    // 5. 业务处理器：将请求分发到业务线程池
                    pipeline.addLast(new RpcServerHandler(businessExecutor));
                }
            });
        
        ChannelFuture future = bootstrap.bind(port).sync();
        System.out.println("RPC Server started on port: " + port);
    }
}
```

#### 3.3.2 线程模型

```
┌─────────────────────────────────────────────────────┐
│                     RPC Server                       │
│                                                      │
│  ┌──────────┐   ┌──────────────────────────────┐    │
│  │ Boss线程  │   │      Worker线程组              │    │
│  │ (1个)     │   │  ┌────────┐ ┌────────┐       │    │
│  │ 接收连接  │──▶│  │Worker-1│ │Worker-2│ ...   │    │
│  └──────────┘   │  │ I/O读写│ │ I/O读写│       │    │
│                  │  └───┬────┘ └───┬────┘       │    │
│                  └──────┼──────────┼─────────────┘    │
│                         │          │                  │
│                  ┌──────▼──────────▼──────────────┐   │
│                  │      Business线程池              │   │
│                  │  ┌──────┐ ┌──────┐ ┌──────┐   │   │
│                  │  │Biz-1 │ │Biz-2 │ │Biz-N │   │   │
│                  │  │业务   │ │业务   │ │业务   │   │   │
│                  │  │处理   │ │处理   │ │处理   │   │   │
│                  │  └──────┘ └──────┘ └──────┘   │   │
│                  └───────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

关键设计点：
- **Boss线程**：只负责Accept新连接，不处理任何I/O读写
- **Worker线程**：处理I/O读写（编解码），不执行耗时业务逻辑
- **Business线程池**：执行实际的业务逻辑，与I/O线程解耦，避免阻塞

```java
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {
    
    // 业务线程池：min=10, max=256, queue=0（CallerRunsPolicy）
    private final ExecutorService businessExecutor;
    
    public RpcServerHandler(ExecutorService executor) {
        this.businessExecutor = executor;
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcRequest request) {
        // 将业务处理提交到业务线程池，不阻塞Worker线程
        businessExecutor.submit(() -> {
            try {
                // 反射调用目标方法
                Object result = invokeMethod(request);
                
                RpcResponse response = new RpcResponse();
                response.setRequestId(request.getRequestId());
                response.setStatus(RpcStatus.OK);
                response.setResult(result);
                
                // 写回响应（回到Worker线程）
                ctx.writeAndFlush(response);
            } catch (Exception e) {
                RpcResponse errorResponse = new RpcResponse();
                errorResponse.setRequestId(request.getRequestId());
                errorResponse.setStatus(RpcStatus.SERVER_ERROR);
                errorResponse.setErrorMessage(e.getMessage());
                ctx.writeAndFlush(errorResponse);
            }
        });
    }
}
```

#### 3.3.3 连接池管理

Consumer端通常为每个Provider维护一个连接池：

```java
public class ConnectionPool {
    
    private final String remoteAddress;
    private final int minConnections;     // 最小连接数
    private final int maxConnections;     // 最大连接数
    private final int idleTimeoutMs;      // 空闲超时时间
    
    // 可用连接队列
    private final LinkedBlockingQueue<Channel> availableChannels;
    // 所有活跃连接
    private final Set<Channel> activeChannels = ConcurrentHashMap.newKeySet();
    
    /**
     * 获取一个可用连接
     */
    public Channel acquire(int timeoutMs) throws Exception {
        // 1. 尝试从可用队列中获取
        Channel channel = availableChannels.poll();
        
        if (channel != null && channel.isActive()) {
            return channel;
        }
        
        // 2. 如果没有可用连接且未达上限，创建新连接
        if (activeChannels.size() < maxConnections) {
            channel = createNewConnection();
            activeChannels.add(channel);
            return channel;
        }
        
        // 3. 达到上限，等待可用连接
        channel = availableChannels.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (channel == null) {
            throw new RuntimeException("Connection pool exhausted");
        }
        return channel;
    }
    
    /**
     * 归还连接到池中
     */
    public void release(Channel channel) {
        if (channel.isActive()) {
            availableChannels.offer(channel);
        } else {
            activeChannels.remove(channel);
        }
    }
    
    /**
     * 定时清理空闲连接
     */
    private void cleanIdleConnections() {
        long now = System.currentTimeMillis();
        Iterator<Channel> iter = availableChannels.iterator();
        while (iter.hasNext()) {
            Channel ch = iter.next();
            Long lastActiveTime = ch.attr(LAST_ACTIVE_TIME).get();
            if (now - lastActiveTime > idleTimeoutMs 
                && activeChannels.size() > minConnections) {
                iter.remove();
                activeChannels.remove(ch);
                ch.close();
            }
        }
    }
}
```

### 3.4 负载均衡算法

负载均衡是RPC框架Cluster层的核心组件，决定了请求被路由到哪个Provider实例。

#### 3.4.1 加权随机算法（Weighted Random）

**算法思路**：根据每个Provider的权重，按比例随机选择。权重越高，被选中的概率越大。

```java
public class WeightedRandomLoadBalancer implements LoadBalancer {
    
    private final Random random = new Random();
    
    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        if (instances.isEmpty()) {
            throw new RuntimeException("No available instances");
        }
        
        // 1. 计算总权重
        int totalWeight = 0;
        boolean sameWeight = true;
        int firstWeight = instances.get(0).getWeight();
        
        for (ServiceInstance instance : instances) {
            totalWeight += instance.getWeight();
            if (instance.getWeight() != firstWeight) {
                sameWeight = false;
            }
        }
        
        // 2. 如果权重都相同，直接随机选择（优化）
        if (sameWeight) {
            return instances.get(random.nextInt(instances.size()));
        }
        
        // 3. 加权随机：生成[0, totalWeight)的随机数，落在哪个区间选哪个
        int offset = random.nextInt(totalWeight);
        for (ServiceInstance instance : instances) {
            offset -= instance.getWeight();
            if (offset < 0) {
                return instance;
            }
        }
        
        // 兜底（理论上不会走到这里）
        return instances.get(0);
    }
}
```

**原理图解**：假设有3个节点，权重分别为A=5, B=3, C=2，总权重=10。
```
|---A(5)---|--B(3)--|C(2)|
0         5        8    10

随机数=3 -> 落在A区间 -> 选A
随机数=6 -> 落在B区间 -> 选B
随机数=9 -> 落在C区间 -> 选C
```

#### 3.4.2 平滑加权轮询算法（Smooth Weighted Round-Robin）

**算法思路**：Nginx使用的算法。每个节点有两个权重值——固定权重(weight)和当前权重(currentWeight)。每次选择currentWeight最大的节点，选中后该节点currentWeight减去总权重，所有节点currentWeight加上各自的weight。

这种算法的优点是：**不会出现连续选中同一节点的情况**，流量分配更均匀。

```java
public class SmoothWeightedRoundRobinLoadBalancer implements LoadBalancer {
    
    // 每个节点的当前权重
    private final ConcurrentMap<String, AtomicInteger> currentWeights = 
        new ConcurrentHashMap<>();
    
    @Override
    public synchronized ServiceInstance select(List<ServiceInstance> instances) {
        // 1. 计算总权重
        int totalWeight = instances.stream()
            .mapToInt(ServiceInstance::getWeight)
            .sum();
        
        // 2. 所有节点的currentWeight加上各自的weight
        ServiceInstance maxInstance = null;
        int maxCurrentWeight = Integer.MIN_VALUE;
        
        for (ServiceInstance instance : instances) {
            String key = instance.getAddress();
            AtomicInteger cw = currentWeights.computeIfAbsent(key, 
                k -> new AtomicInteger(0));
            
            // currentWeight += weight
            int newCw = cw.addAndGet(instance.getWeight());
            
            // 3. 找到currentWeight最大的节点
            if (newCw > maxCurrentWeight) {
                maxCurrentWeight = newCw;
                maxInstance = instance;
            }
        }
        
        // 4. 选中的节点 currentWeight -= totalWeight
        String selectedKey = maxInstance.getAddress();
        currentWeights.get(selectedKey).addAndGet(-totalWeight);
        
        return maxInstance;
    }
}
```

**执行过程举例**：A(weight=5), B(weight=1), C(weight=1), totalWeight=7

| 轮次 | 选择前currentWeight | 选中 | 选择后currentWeight |
|------|---------------------|------|---------------------|
| 1 | A=5, B=1, C=1 | A | A=-2, B=1, C=1 |
| 2 | A=3, B=2, C=2 | A | A=-4, B=2, C=2 |
| 3 | A=1, B=3, C=3 | B或C | A=1, B=-4, C=3 |
| 4 | A=6, B=-3, C=4 | A | A=-1, B=-3, C=4 |
| ... | ... | ... | ... |

#### 3.4.3 一致性哈希算法（Consistent Hash）

**算法思路**：将所有Provider节点映射到一个0~2^32的哈希环上，对请求的key（如用户ID）计算哈希值，在环上顺时针查找最近的节点。为了解决数据倾斜问题，引入**虚拟节点**。

```java
public class ConsistentHashLoadBalancer implements LoadBalancer {
    
    // 虚拟节点倍数
    private static final int VIRTUAL_NODE_NUM = 160;
    
    // 哈希环：key=哈希值, value=实际节点
    private final TreeMap<Long, ServiceInstance> hashRing = new TreeMap<>();
    
    /**
     * 构建哈希环
     */
    public void buildHashRing(List<ServiceInstance> instances) {
        hashRing.clear();
        for (ServiceInstance instance : instances) {
            // 每个实际节点创建 VIRTUAL_NODE_NUM 个虚拟节点
            for (int i = 0; i < VIRTUAL_NODE_NUM; i++) {
                // 使用Ketama哈希算法，确保分布均匀
                String virtualKey = instance.getAddress() + "#VN" + i;
                long hash = ketamaHash(virtualKey);
                hashRing.put(hash, instance);
            }
        }
    }
    
    /**
     * 根据请求key选择节点
     */
    public ServiceInstance selectByKey(String requestKey) {
        long hash = ketamaHash(requestKey);
        
        // 在TreeMap中找到第一个 >= hash 的节点
        Map.Entry<Long, ServiceInstance> entry = hashRing.ceilingEntry(hash);
        
        // 如果没有找到（hash值大于环上最大值），则取环上第一个节点
        if (entry == null) {
            entry = hashRing.firstEntry();
        }
        
        return entry.getValue();
    }
    
    /**
     * Ketama哈希算法 - 基于MD5实现
     * 特点：分布均匀，碰撞率低
     */
    private long ketamaHash(String key) {
        byte[] digest = MessageDigest.getInstance("MD5").digest(key.getBytes());
        // 取前4个字节组合为long值
        return ((long)(digest[3] & 0xFF) << 24)
             | ((long)(digest[2] & 0xFF) << 16)
             | ((long)(digest[1] & 0xFF) << 8)
             | (digest[0] & 0xFF);
    }
    
    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        // 一致性哈希通常需要一个key来决定路由
        // 可以从RPC上下文中获取路由key
        String routeKey = RpcContext.getContext().getAttachment("routeKey");
        if (routeKey == null) {
            // 没有路由key，降级到随机
            return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        }
        
        // 检查是否需要重建哈希环
        buildHashRingIfNeeded(instances);
        
        return selectByKey(routeKey);
    }
}
```

**一致性哈希的优势**：
- 节点增减时，只影响相邻节点的请求分配，大部分请求仍路由到原来的节点
- 特别适合有状态服务或需要亲和性的场景（如缓存代理）

#### 3.4.4 最少活跃调用数算法（Least Active）

**算法思路**：记录每个Provider当前正在处理的请求数（活跃数），优先选择活跃数最少的节点。活跃数相同时，按权重随机。这种算法能自动感知慢节点，将流量导向更快的节点。

```java
public class LeastActiveLoadBalancer implements LoadBalancer {
    
    // 每个节点的活跃请求数
    private final ConcurrentMap<String, AtomicInteger> activeCountMap = 
        new ConcurrentHashMap<>();
    
    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        int minActive = Integer.MAX_VALUE;
        List<ServiceInstance> candidates = new ArrayList<>();
        
        // 1. 找到最小活跃数的所有节点
        for (ServiceInstance instance : instances) {
            int active = getActiveCount(instance);
            if (active < minActive) {
                minActive = active;
                candidates.clear();
                candidates.add(instance);
            } else if (active == minActive) {
                candidates.add(instance);
            }
        }
        
        // 2. 多个候选时按权重随机
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return weightedRandom(candidates);
    }
    
    /**
     * 调用前增加活跃数
     */
    public void onCallStart(ServiceInstance instance) {
        activeCountMap.computeIfAbsent(instance.getAddress(), 
            k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * 调用完成后减少活跃数
     */
    public void onCallComplete(ServiceInstance instance) {
        AtomicInteger count = activeCountMap.get(instance.getAddress());
        if (count != null) {
            count.decrementAndGet();
        }
    }
}
```

#### 3.4.5 自适应负载均衡

基于P99延迟和错误率的自适应负载均衡，实时感知每个节点的健康度：

```java
public class AdaptiveLoadBalancer implements LoadBalancer {
    
    /**
     * 计算每个节点的得分，得分越高越优先
     * 得分 = weight * successRate / avgResponseTime
     */
    @Override
    public ServiceInstance select(List<ServiceInstance> instances) {
        double maxScore = -1;
        ServiceInstance bestInstance = null;
        
        for (ServiceInstance instance : instances) {
            NodeMetrics metrics = getMetrics(instance);
            
            // 成功率（最近1分钟）
            double successRate = metrics.getSuccessRate();
            // 平均响应时间（最近1分钟，毫秒）
            double avgRt = Math.max(metrics.getAvgResponseTime(), 1.0);
            // 配置权重
            int weight = instance.getWeight();
            
            // 综合得分
            double score = weight * successRate / avgRt;
            
            if (score > maxScore) {
                maxScore = score;
                bestInstance = instance;
            }
        }
        
        return bestInstance;
    }
}
```

### 3.5 服务路由与流量管理

#### 3.5.1 路由设计总览

RPC路由按目标不同分为两大类：

| 类别 | 路由类型 | 实现方式 | 适用场景 |
|------|----------|----------|----------|
| **逻辑路由（标签路由）** | SET路由 | 按逻辑分组打标签 | 生产环境隔离 |
| | 泳道路由 | 流量染色传播 | 测试环境隔离 |
| | LiteSet路由 | 轻量级分组 | 灰度发布 |
| **物理路由** | 同机房路由 | 按IP段判断 | 降低延迟 |
| | 同城路由 | 按地域判断 | 容灾兜底 |

#### 3.5.2 SET化路由

SET化是将整个调用链路按逻辑分组，使得流量在同一个SET内闭环流转，实现业务级别的隔离：

```
SET-A (北京)                SET-B (上海)
┌─────────────────┐        ┌─────────────────┐
│ Gateway-A       │        │ Gateway-B       │
│    │            │        │    │            │
│    ▼            │        │    ▼            │
│ OrderService-A  │        │ OrderService-B  │
│    │            │        │    │            │
│    ▼            │        │    ▼            │
│ PayService-A    │        │ PayService-B    │
│    │            │        │    │            │
│    ▼            │        │    ▼            │
│ DB-A            │        │ DB-B            │
└─────────────────┘        └─────────────────┘
```

```java
public class SetRouter implements Router {
    
    @Override
    public List<ServiceInstance> route(List<ServiceInstance> instances, 
                                       RpcInvocation invocation) {
        // 从请求上下文中获取SET标签
        String setTag = invocation.getAttachment("SET_TAG");
        
        if (setTag == null || setTag.isEmpty()) {
            // 没有SET标签，返回所有节点
            return instances;
        }
        
        // 筛选相同SET标签的节点
        List<ServiceInstance> sameSetInstances = instances.stream()
            .filter(i -> setTag.equals(i.getMetadata().get("SET_TAG")))
            .collect(Collectors.toList());
        
        if (!sameSetInstances.isEmpty()) {
            return sameSetInstances;
        }
        
        // 如果同SET节点全部不可用，根据配置决定是否降级到其他SET
        if (isSetFallbackEnabled()) {
            return instances; // 降级到全部节点
        }
        
        // 严格SET隔离模式：不降级，直接返回空
        return Collections.emptyList();
    }
}
```

#### 3.5.3 泳道路由（流量染色）

泳道路由通过在请求入口处对流量打标（染色），标记在整个调用链中透传，确保请求始终在指定泳道内流转：

```java
public class SwimLaneRouter implements Router {
    
    @Override
    public List<ServiceInstance> route(List<ServiceInstance> instances, 
                                       RpcInvocation invocation) {
        // 从请求上下文（通过RPC框架的隐式传参机制透传）获取泳道标记
        String laneTag = invocation.getAttachment("LANE_TAG");
        
        if (laneTag == null) {
            // 没有泳道标记，走主干(baseline)
            return filterByLane(instances, "baseline");
        }
        
        // 筛选指定泳道的节点
        List<ServiceInstance> laneInstances = filterByLane(instances, laneTag);
        
        if (!laneInstances.isEmpty()) {
            return laneInstances;
        }
        
        // 泳道内没有可用节点，回落到主干
        return filterByLane(instances, "baseline");
    }
    
    private List<ServiceInstance> filterByLane(List<ServiceInstance> instances, 
                                               String lane) {
        return instances.stream()
            .filter(i -> lane.equals(i.getMetadata().getOrDefault("LANE", "baseline")))
            .collect(Collectors.toList());
    }
}
```

**流量染色传播机制**：

```java
public class LaneContextFilter implements Filter {
    
    /**
     * Consumer端Filter：调用前将泳道标记注入RPC上下文
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        // 从ThreadLocal中获取当前线程的泳道标记
        String laneTag = LaneContext.getCurrentLane();
        
        if (laneTag != null) {
            // 设置到RPC隐式传参中，由框架自动传递给下游
            invocation.setAttachment("LANE_TAG", laneTag);
        }
        
        return invoker.invoke(invocation);
    }
}
```

#### 3.5.4 同机房优先路由

```java
public class LocalFirstRouter implements Router {
    
    @Override
    public List<ServiceInstance> route(List<ServiceInstance> instances, 
                                       RpcInvocation invocation) {
        String localIdc = SystemInfo.getCurrentIdc();  // 当前机房
        String localCity = SystemInfo.getCurrentCity(); // 当前城市
        
        // 优先级1：同机房节点
        List<ServiceInstance> sameIdcInstances = instances.stream()
            .filter(i -> localIdc.equals(i.getMetadata().get("IDC")))
            .collect(Collectors.toList());
        if (!sameIdcInstances.isEmpty()) {
            return sameIdcInstances;
        }
        
        // 优先级2：同城市节点
        List<ServiceInstance> sameCityInstances = instances.stream()
            .filter(i -> localCity.equals(i.getMetadata().get("CITY")))
            .collect(Collectors.toList());
        if (!sameCityInstances.isEmpty()) {
            return sameCityInstances;
        }
        
        // 优先级3：所有节点
        return instances;
    }
}
```

### 3.6 RPC调用完整链路

一次完整的RPC调用链路如下：

```
Consumer端                                          Provider端
    │                                                    │
    │  1.业务代码调用接口方法                               │
    ▼                                                    │
[Proxy层] 动态代理拦截调用                                │
    │                                                    │
    ▼                                                    │
[Cluster层] 路由+负载均衡选择Provider                     │
    │  ├── SET路由过滤                                    │
    │  ├── 同机房优先过滤                                  │
    │  └── 加权随机/轮询选择                               │
    ▼                                                    │
[Protocol层] 构造RpcRequest                              │
    │  ├── 设置serviceName, methodName, args              │
    │  └── 生成唯一requestId                              │
    ▼                                                    │
[Filter链] 执行Consumer端Filter                          │
    │  ├── 链路追踪Filter（注入traceId/spanId）            │
    │  ├── 泳道染色Filter（注入LANE_TAG）                  │
    │  └── 监控统计Filter（记录调用开始时间）               │
    ▼                                                    │
[Serialize层] 序列化请求                                  │
    │  └── Thrift/Protobuf/Hessian序列化                  │
    ▼                                                    │
[Transport层] 通过Netty发送                               │
    │  └── 编码为协议帧 -> 写入Channel                     │
    │                                                    │
    │ ============= 网络传输 (TCP) ===============        │
    │                                                    │
    │                                                    ▼
    │                                          [Transport层] Netty接收
    │                                              │  └── 解码协议帧
    │                                              ▼
    │                                          [Serialize层] 反序列化
    │                                              │
    │                                              ▼
    │                                          [Filter链] Provider端Filter
    │                                              │  ├── 鉴权Filter
    │                                              │  ├── 限流Filter
    │                                              │  └── 监控Filter
    │                                              ▼
    │                                          [业务线程池] 反射调用目标方法
    │                                              │
    │                                              ▼
    │                                          构造RpcResponse
    │                                              │
    │ ============= 网络传输 (TCP) =============== │
    │                                              │
    ▼                                              │
[Transport层] 接收响应                               │
    │                                                │
    ▼                                                │
[Serialize层] 反序列化响应                            │
    │                                                │
    ▼                                                │
通过requestId匹配CompletableFuture                   │
    │                                                │
    ▼                                                │
[Filter链] 后置Filter                                │
    │  └── 监控Filter记录耗时、结果                    │
    ▼                                                │
返回结果给业务代码                                     │
```

---

## 四、服务治理

### 4.1 熔断机制

#### 4.1.1 熔断器状态机

熔断器采用经典的三态模型（Closed/Open/Half-Open），参考Hystrix的设计：

```
         ┌─────────────────────────────┐
         │         Closed（关闭）       │
         │   正常放行所有请求           │
         │   统计失败率                 │
         └──────────┬──────────────────┘
                    │ 失败率超过阈值
                    ▼
         ┌─────────────────────────────┐
         │         Open（打开）         │
         │   拒绝所有请求，直接降级     │
         │   启动冷却计时器             │
         └──────────┬──────────────────┘
                    │ 冷却时间结束
                    ▼
         ┌─────────────────────────────┐
         │       Half-Open（半开）      │
         │   放行少量试探请求           │
         │                             │
         │   ┌──试探成功──► Closed     │
         │   └──试探失败──► Open       │
         └─────────────────────────────┘
```

```java
public class CircuitBreaker {
    
    private enum State { CLOSED, OPEN, HALF_OPEN }
    
    private volatile State state = State.CLOSED;
    private final int failureThreshold;        // 失败率阈值（如50%）
    private final int requestVolumeThreshold;  // 统计窗口内最少请求数
    private final long sleepWindowMs;          // 冷却时间（毫秒）
    private final SlidingWindowCounter counter; // 滑动窗口计数器
    private volatile long openTimestamp;        // 打开时的时间戳
    
    /**
     * 判断请求是否允许通过
     */
    public boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                // 检查冷却时间是否到期
                if (System.currentTimeMillis() - openTimestamp >= sleepWindowMs) {
                    state = State.HALF_OPEN;
                    return true; // 放行第一个试探请求
                }
                return false;
            case HALF_OPEN:
                // 半开状态只允许一个请求通过
                return false;
            default:
                return true;
        }
    }
    
    /**
     * 记录请求结果
     */
    public void recordResult(boolean success) {
        if (state == State.HALF_OPEN) {
            if (success) {
                // 试探成功，恢复到关闭状态
                state = State.CLOSED;
                counter.reset();
            } else {
                // 试探失败，重新打开
                state = State.OPEN;
                openTimestamp = System.currentTimeMillis();
            }
            return;
        }
        
        // Closed状态下统计
        counter.record(success);
        
        // 检查是否需要打开熔断器
        if (counter.getTotalCount() >= requestVolumeThreshold) {
            double failureRate = counter.getFailureRate();
            if (failureRate >= failureThreshold / 100.0) {
                state = State.OPEN;
                openTimestamp = System.currentTimeMillis();
            }
        }
    }
}
```

#### 4.1.2 滑动窗口计数器

```java
public class SlidingWindowCounter {
    
    private final int windowSize;     // 窗口大小（秒）
    private final int bucketCount;    // 桶数量
    private final long bucketDurationMs; // 每个桶的时间跨度
    private final AtomicLong[] successCounts;
    private final AtomicLong[] failureCounts;
    private volatile long currentBucketStart;
    
    public SlidingWindowCounter(int windowSizeSeconds, int bucketCount) {
        this.windowSize = windowSizeSeconds;
        this.bucketCount = bucketCount;
        this.bucketDurationMs = windowSizeSeconds * 1000L / bucketCount;
        this.successCounts = new AtomicLong[bucketCount];
        this.failureCounts = new AtomicLong[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            successCounts[i] = new AtomicLong(0);
            failureCounts[i] = new AtomicLong(0);
        }
    }
    
    private int getCurrentBucketIndex() {
        long now = System.currentTimeMillis();
        return (int)((now / bucketDurationMs) % bucketCount);
    }
    
    public void record(boolean success) {
        int index = getCurrentBucketIndex();
        if (success) {
            successCounts[index].incrementAndGet();
        } else {
            failureCounts[index].incrementAndGet();
        }
    }
    
    public double getFailureRate() {
        long totalSuccess = 0, totalFailure = 0;
        for (int i = 0; i < bucketCount; i++) {
            totalSuccess += successCounts[i].get();
            totalFailure += failureCounts[i].get();
        }
        long total = totalSuccess + totalFailure;
        return total == 0 ? 0 : (double) totalFailure / total;
    }
}
```

### 4.2 降级策略

```java
public class DegradeHandler {
    
    /**
     * 降级策略枚举
     */
    public enum DegradeStrategy {
        FALLBACK_METHOD,    // 执行降级方法
        RETURN_DEFAULT,     // 返回默认值
        THROW_EXCEPTION,    // 抛出异常
        RETURN_CACHE        // 返回缓存值
    }
    
    /**
     * 执行降级逻辑
     */
    public Object degrade(RpcInvocation invocation, DegradeStrategy strategy) {
        switch (strategy) {
            case FALLBACK_METHOD:
                // 调用用户定义的fallback方法
                return invokeFallbackMethod(invocation);
                
            case RETURN_DEFAULT:
                // 返回返回类型的默认值
                Class<?> returnType = invocation.getReturnType();
                return getDefaultValue(returnType);
                
            case RETURN_CACHE:
                // 从本地缓存中获取上次成功的结果
                return localCache.get(invocation.getCacheKey());
                
            case THROW_EXCEPTION:
            default:
                throw new RpcDegradeException("Service degraded");
        }
    }
    
    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == String.class) return "";
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        if (Map.class.isAssignableFrom(type)) return Collections.emptyMap();
        return null;
    }
}
```

### 4.3 集群容错策略

```java
public interface ClusterInvoker {
    Result invoke(Invocation invocation) throws RpcException;
}

/**
 * Failover - 失败自动重试（默认策略）
 * 适用于读操作（幂等操作）
 */
public class FailoverClusterInvoker implements ClusterInvoker {
    
    private final int retries; // 重试次数（不含首次调用）
    
    @Override
    public Result invoke(Invocation invocation) {
        List<ServiceInstance> instances = directory.list(invocation);
        int totalTries = retries + 1;
        
        RpcException lastException = null;
        Set<ServiceInstance> tried = new HashSet<>();
        
        for (int i = 0; i < totalTries; i++) {
            // 每次重试重新做负载均衡，排除已尝试的节点
            ServiceInstance instance = loadBalance.select(instances, tried);
            tried.add(instance);
            
            try {
                return doInvoke(instance, invocation);
            } catch (RpcException e) {
                lastException = e;
                // 记录日志，继续重试
            }
        }
        throw lastException;
    }
}

/**
 * Failfast - 快速失败，不重试
 * 适用于写操作（非幂等操作）
 */
public class FailfastClusterInvoker implements ClusterInvoker {
    @Override
    public Result invoke(Invocation invocation) {
        ServiceInstance instance = loadBalance.select(directory.list(invocation));
        try {
            return doInvoke(instance, invocation);
        } catch (RpcException e) {
            throw e; // 直接抛出，不重试
        }
    }
}

/**
 * Failsafe - 安全失败，异常直接忽略
 * 适用于日志记录、监控上报等非关键操作
 */
public class FailsafeClusterInvoker implements ClusterInvoker {
    @Override
    public Result invoke(Invocation invocation) {
        try {
            ServiceInstance instance = loadBalance.select(directory.list(invocation));
            return doInvoke(instance, invocation);
        } catch (RpcException e) {
            log.warn("Failsafe: ignoring exception", e);
            return new RpcResult(null); // 返回空结果
        }
    }
}

/**
 * Failback - 失败后异步重试
 * 适用于消息通知等最终一致性场景
 */
public class FailbackClusterInvoker implements ClusterInvoker {
    
    private final ScheduledExecutorService retryExecutor = 
        Executors.newScheduledThreadPool(2);
    private final ConcurrentLinkedQueue<Invocation> failedInvocations = 
        new ConcurrentLinkedQueue<>();
    
    @Override
    public Result invoke(Invocation invocation) {
        try {
            ServiceInstance instance = loadBalance.select(directory.list(invocation));
            return doInvoke(instance, invocation);
        } catch (RpcException e) {
            // 加入失败队列，后续异步重试
            failedInvocations.offer(invocation);
            scheduleRetry();
            return new RpcResult(null);
        }
    }
    
    private void scheduleRetry() {
        retryExecutor.schedule(() -> {
            Invocation invocation = failedInvocations.poll();
            if (invocation != null) {
                try {
                    invoke(invocation);
                } catch (Exception e) {
                    // 重新加入队列
                    failedInvocations.offer(invocation);
                    scheduleRetry();
                }
            }
        }, 5, TimeUnit.SECONDS);
    }
}
```

---

## 五、Service Mesh演进

### 5.1 传统SDK模式的痛点

传统的RPC框架以SDK形式嵌入业务进程中，存在以下问题：

1. **中间件与业务耦合**：治理能力（路由、限流、熔断等）和业务代码在物理上耦合。中间件引入Bug需要所有业务配合升级，新特性发布也依赖业务逐个升级。

2. **多语言支持困难**：主力技术栈以Java为主（占比80%+），但还有Go、C++、Python等语言。为每种语言建设完善的治理生态成本极高。

3. **治理体系分散**：不同语言、不同框架的治理能力参差不齐，无法统一协同管理。

### 5.2 Service Mesh架构

```
┌─────────────────────────────────────────────────┐
│                  控制面 (Control Plane)           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ Pilot    │  │ Citadel  │  │ Galley   │      │
│  │ 流量管理 │  │ 安全管理 │  │ 配置管理 │      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘      │
│       │             │             │              │
└───────┼─────────────┼─────────────┼──────────────┘
        │xDS协议      │             │
┌───────┼─────────────┼─────────────┼──────────────┐
│       ▼             ▼             ▼  数据面       │
│  ┌──────────────────────────────────────┐        │
│  │              Sidecar (Envoy)          │        │
│  │  ┌─────────┐ ┌─────────┐ ┌────────┐ │        │
│  │  │服务发现 │ │负载均衡 │ │熔断限流│ │        │
│  │  └─────────┘ └─────────┘ └────────┘ │        │
│  └──────────┬───────────────────────────┘        │
│             │ 流量拦截(iptables)                  │
│  ┌──────────▼───────────────┐                    │
│  │    Business App          │                    │
│  │    (轻量SDK/无SDK)       │                    │
│  └──────────────────────────┘                    │
└──────────────────────────────────────────────────┘
```

### 5.3 Mesh化改造路径

从传统SDK架构向Mesh架构的迁移并非一蹴而就，通常采用渐进式方案：

1. **第一阶段 - SDK瘦身**：将SDK中的重逻辑（路由规则计算、服务列表管理）下沉到本地SgAgent进程
2. **第二阶段 - Sidecar代理**：将网络流量拦截到Sidecar，实现流量代理
3. **第三阶段 - 统一控制面**：建设统一的控制面，对接所有Sidecar，实现集中化流量管理

```java
// Mesh模式下的SDK极简化
public class MeshRpcClient {
    
    // 所有请求发往本地Sidecar
    private static final String SIDECAR_ADDRESS = "127.0.0.1";
    private static final int SIDECAR_PORT = 15001;
    
    public RpcResponse call(String serviceName, String method, Object[] args) {
        RpcRequest request = new RpcRequest();
        request.setServiceName(serviceName);
        request.setMethodName(method);
        request.setArgs(args);
        
        // SDK只负责序列化和发送到本地Sidecar
        // 路由、负载均衡、熔断等全部由Sidecar处理
        return sendToSidecar(request);
    }
}
```

---

## 六、异常处理与容错机制

### 6.1 超时处理

```java
public class TimeoutHandler {
    
    /**
     * 多级超时设置（优先级从高到低）
     * 1. 方法级超时
     * 2. 接口级超时
     * 3. Consumer端全局超时
     * 4. Provider端全局超时（作为兜底）
     */
    public int getTimeout(RpcInvocation invocation) {
        // 1. 方法级
        Integer methodTimeout = getMethodTimeout(invocation);
        if (methodTimeout != null) return methodTimeout;
        
        // 2. 接口级
        Integer interfaceTimeout = getInterfaceTimeout(invocation);
        if (interfaceTimeout != null) return interfaceTimeout;
        
        // 3. Consumer全局
        Integer consumerTimeout = getConsumerGlobalTimeout();
        if (consumerTimeout != null) return consumerTimeout;
        
        // 4. 默认超时 3000ms
        return 3000;
    }
}
```

### 6.2 幂等性保证

```java
public class IdempotentFilter implements Filter {
    
    /**
     * Provider端的幂等去重
     * 基于requestId进行去重，防止重试导致重复执行
     */
    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        String requestId = invocation.getAttachment("requestId");
        
        // 检查是否已经处理过该请求
        Result cachedResult = idempotentCache.get(requestId);
        if (cachedResult != null) {
            return cachedResult; // 直接返回缓存的结果
        }
        
        // 执行业务逻辑
        Result result = invoker.invoke(invocation);
        
        // 缓存结果（设置过期时间，如5分钟）
        idempotentCache.put(requestId, result, 5, TimeUnit.MINUTES);
        
        return result;
    }
}
```

### 6.3 优雅上下线

```java
public class GracefulShutdown {
    
    /**
     * 优雅下线流程
     */
    public void shutdown() {
        // 1. 从注册中心注销服务节点
        registry.unregister(serviceInfo);
        
        // 2. 等待一段时间，让Consumer感知到节点下线
        //    （注册中心推送有延迟）
        Thread.sleep(3000);
        
        // 3. 标记服务为不可用（拒绝新请求）
        serverStatus = ServiceStatus.SHUTTING_DOWN;
        
        // 4. 等待正在处理的请求完成（最多等30秒）
        long deadline = System.currentTimeMillis() + 30_000;
        while (activeRequestCount.get() > 0 
               && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        
        // 5. 关闭网络连接和线程池
        server.close();
        businessExecutor.shutdown();
    }
    
    /**
     * 优雅上线流程
     */
    public void startup() {
        // 1. 启动服务，初始化资源
        server.start();
        
        // 2. 预热（如JIT编译、缓存加载）
        warmUp();
        
        // 3. 健康检查通过后注册到注册中心
        if (healthCheck.isHealthy()) {
            registry.register(serviceInfo);
        }
    }
}
```

---

## 七、性能优化

### 7.1 序列化优化

| 序列化方案 | 序列化大小(bytes) | 序列化耗时(ns) | 反序列化耗时(ns) | 是否需要IDL |
|-----------|------------------|---------------|-----------------|------------|
| JSON | 216 | 1200 | 1500 | 否 |
| Hessian2 | 148 | 800 | 900 | 否 |
| Thrift | 98 | 400 | 450 | 是 |
| Protobuf | 89 | 300 | 350 | 是 |
| Kryo | 95 | 350 | 380 | 否 |

**选择建议**：
- 追求极致性能：Protobuf（需要IDL管理成本）
- 平衡性能与易用性：Hessian2（无需IDL，性能不错）
- 调试便利性优先：JSON（可读性好，性能最差）

### 7.2 连接池优化

```java
public class ConnectionPoolConfig {
    // 最小连接数：保持的最小空闲连接
    private int minIdle = 2;
    
    // 最大连接数：单个Provider的最大连接数
    private int maxTotal = 10;
    
    // 获取连接超时：从连接池获取连接的最大等待时间
    private int borrowTimeout = 3000;
    
    // 空闲连接检测间隔
    private int idleCheckInterval = 60_000;
    
    // 空闲连接超时：超时后关闭多余的空闲连接
    private int idleTimeout = 300_000;
    
    // 连接最大存活时间：防止连接老化
    private int maxLifetime = 1800_000;
}
```

### 7.3 异步调用

```java
public class AsyncRpcExample {
    
    /**
     * CompletableFuture异步调用
     */
    public CompletableFuture<OrderInfo> getOrderAsync(long orderId) {
        RpcRequest request = buildRequest("OrderService", "getOrder", orderId);
        
        return rpcClient.asyncCall(request)
            .thenApply(response -> deserialize(response, OrderInfo.class))
            .exceptionally(e -> {
                log.error("Async RPC failed", e);
                return defaultOrderInfo(); // 降级
            });
    }
    
    /**
     * 并行调用多个服务
     */
    public AggregatedResult parallelCall(long userId) {
        CompletableFuture<UserInfo> userFuture = userService.getUserAsync(userId);
        CompletableFuture<List<Order>> orderFuture = orderService.getOrdersAsync(userId);
        CompletableFuture<AccountInfo> accountFuture = accountService.getAccountAsync(userId);
        
        // 等待所有调用完成
        return CompletableFuture.allOf(userFuture, orderFuture, accountFuture)
            .thenApply(v -> {
                AggregatedResult result = new AggregatedResult();
                result.setUser(userFuture.join());
                result.setOrders(orderFuture.join());
                result.setAccount(accountFuture.join());
                return result;
            }).join();
    }
}
```

---

## 八、最佳实践与总结

### 8.1 设计原则

1. **分层架构**：Protocol / Cluster / Proxy / Transport / Serialize 各层职责分离，通过SPI机制实现扩展
2. **面向接口编程**：负载均衡、路由、序列化等核心组件都定义接口，便于替换和扩展
3. **防御性设计**：任何外部调用都要设置超时时间，任何重试都要考虑幂等性
4. **渐进式降级**：熔断 → 降级 → 兜底数据，层层保护

### 8.2 常见陷阱

1. **超时传播问题**：A调用B，B调用C。如果A超时3秒，B应将剩余时间传递给C，而不是重新设置3秒。否则整个链路的实际超时可能远超预期。

```java
// 错误做法：每个环节独立设置超时
result = serviceB.call(request, 3000); // 不管上游还剩多少时间

// 正确做法：传递剩余超时时间
long remaining = deadline - System.currentTimeMillis();
result = serviceB.call(request, Math.max(remaining, 0));
```

2. **重试风暴**：A重试3次调B，B重试3次调C，实际C承受9倍流量。建议：只在最外层重试，内层快速失败。

3. **线程池隔离不足**：所有RPC调用共用一个线程池，一个慢接口可能耗尽所有线程，影响其他接口。应该对关键接口使用独立的线程池。

4. **序列化兼容性**：新增字段没问题，但删除或修改字段可能导致反序列化失败。建议使用Thrift/Protobuf等有向前兼容能力的协议。

### 8.3 演进方向

1. **Service Mesh**：将治理能力从SDK下沉到Sidecar，实现业务与基础设施解耦
2. **Proxyless gRPC**：利用xDS协议直接在SDK中对接控制面，兼顾性能和治理能力
3. **自适应治理**：基于实时监控数据自动调整负载均衡权重、限流阈值、熔断参数
4. **全链路灰度**：通过流量染色实现全调用链路的灰度控制
5. **可观测性增强**：Metrics + Tracing + Logging 三位一体的观测体系

---

## 九、全链路实战案例

本章节通过三个完整实战案例，将前面各章节的理论设计串联为可运行的代码实现。每个案例均包含完整的Java代码、异常处理、日志输出和幂等控制。

### 案例一：一次RPC调用的全链路

**场景描述**：Consumer调用Provider的`OrderService.createOrder`方法，完整经历动态代理拦截 -> 序列化 -> 网络传输 -> 服务端反序列化 -> 方法反射执行 -> 结果序列化返回 -> Consumer反序列化拿结果的全过程。

```
Consumer端                                              Provider端
    │                                                       │
    │  1.业务代码: orderService.createOrder(req)              │
    ▼                                                       │
[Proxy层] JdkProxyHandler.invoke()                          │
    │  ├── 构造RpcInvocation                                │
    │  └── 生成唯一requestId（幂等Key）                      │
    ▼                                                       │
[Serialize层] Hessian2序列化                                │
    │  └── RpcRequest -> byte[]                             │
    ▼                                                       │
[Transport层] Netty Channel.writeAndFlush()                 │
    │  └── 协议帧编码（Magic+RequestId+Body）                │
    │                                                       │
    │  ============ TCP网络传输 ===============              │
    │                                                       ▼
    │                                          [Transport层] 解码协议帧
    │                                              │
    │                                              ▼
    │                                          [Serialize层] Hessian2反序列化
    │                                              │
    │                                              ▼
    │                                          [Filter链] 幂等去重
    │                                              │  └── requestId查缓存
    │                                              ▼
    │                                          [业务线程池] 反射执行createOrder
    │                                              │
    │                                              ▼
    │                                          构造RpcResponse -> 序列化
    │  ============ TCP网络传输 ===============              │
    ▼                                                       │
[Transport层] 接收响应 -> 解码                                │
    │                                                       │
    ▼                                                       │
[Serialize层] 反序列化响应                                    │
    │                                                       │
    ▼                                                       │
通过requestId匹配Future -> 返回结果给业务代码                  │
```

```java
// ======================== 1. 业务接口定义 ========================

public interface OrderService {
    /**
     * 创建订单
     * @param request 创建订单请求
     * @return 创建订单响应
     */
    CreateOrderResponse createOrder(CreateOrderRequest request);
}

// 创建订单请求
public class CreateOrderRequest implements Serializable {
    private long userId;
    private List<Long> productIds;
    private String couponCode;
    // getter/setter省略
}

// 创建订单响应
public class CreateOrderResponse implements Serializable {
    private int code;
    private String message;
    private long orderId;
    // getter/setter省略
}

// ======================== 2. RPC请求/响应对象 ========================

public class RpcRequest implements Serializable {
    private String requestId;        // 请求唯一ID（用于幂等去重）
    private String serviceName;      // 服务名
    private String methodName;       // 方法名
    private Class<?>[] parameterTypes;
    private Object[] args;           // 方法参数
    private Map<String, String> attachments = new HashMap<>(); // 隐式传参
    // getter/setter省略
}

public class RpcResponse implements Serializable {
    private String requestId;
    private int status;              // 0=OK, 1=TIMEOUT, 2=SERVER_ERROR
    private Object result;
    private String errorMessage;
    // getter/setter省略
}

// ======================== 3. 客户端动态代理 ========================

public class RpcClientProxy implements InvocationHandler {

    private static final Logger log = LoggerFactory.getLogger(RpcClientProxy.class);

    private final RpcClient rpcClient;
    private final String serviceName;
    private final int timeoutMs;

    public RpcClientProxy(RpcClient rpcClient, String serviceName, int timeoutMs) {
        this.rpcClient = rpcClient;
        this.serviceName = serviceName;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 创建代理对象
     */
    @SuppressWarnings("unchecked")
    public static <T> T create(RpcClient rpcClient, String serviceName,
                                Class<T> interfaceClass, int timeoutMs) {
        return (T) Proxy.newProxyInstance(
            interfaceClass.getClassLoader(),
            new Class<?>[]{interfaceClass},
            new RpcClientProxy(rpcClient, serviceName, timeoutMs)
        );
    }

    /**
     * 动态代理拦截：将本地方法调用转换为远程RPC调用
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 1. 过滤Object类方法
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        // 2. 构造RpcRequest
        RpcRequest request = new RpcRequest();
        // 生成唯一requestId，作为幂等Key
        request.setRequestId(generateRequestId(serviceName, method.getName(), args));
        request.setServiceName(serviceName);
        request.setMethodName(method.getName());
        request.setParameterTypes(method.getParameterTypes());
        request.setArgs(args);

        // 注入链路追踪ID
        request.getAttachments().put("traceId", TraceContext.getTraceId());

        log.info("[Consumer] RPC调用开始 | service={} method={} requestId={} traceId={}",
                serviceName, method.getName(), request.getRequestId(),
                request.getAttachments().get("traceId"));

        long startTime = System.currentTimeMillis();

        try {
            // 3. 发送RPC请求并等待响应
            RpcResponse response = rpcClient.sendRequest(request, timeoutMs);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[Consumer] RPC调用完成 | requestId={} status={} elapsed={}ms",
                    request.getRequestId(), response.getStatus(), elapsed);

            // 4. 处理响应
            if (response.getStatus() == 0) {
                return response.getResult();
            } else if (response.getStatus() == 1) {
                throw new RpcTimeoutException("RPC调用超时: " + response.getErrorMessage());
            } else {
                throw new RpcException("RPC服务端异常: " + response.getErrorMessage());
            }
        } catch (RpcTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[Consumer] RPC调用超时 | service={} method={} requestId={} elapsed={}ms",
                    serviceName, method.getName(), request.getRequestId(), elapsed, e);
            throw e;
        } catch (RpcException e) {
            log.error("[Consumer] RPC调用失败 | service={} method={} requestId={}",
                    serviceName, method.getName(), request.getRequestId(), e);
            throw e;
        } catch (Exception e) {
            log.error("[Consumer] RPC调用未知异常 | service={} method={} requestId={}",
                    serviceName, method.getName(), request.getRequestId(), e);
            throw new RpcException("RPC调用未知异常", e);
        }
    }

    /**
     * 生成幂等requestId
     * 策略：serviceName + methodName + 参数哈希
     * 同一请求无论重试多少次，requestId不变，Provider端可据此去重
     */
    private String generateRequestId(String serviceName, String methodName, Object[] args) {
        String argHash = Arrays.hashCode(args) + "";
        return serviceName + "#" + methodName + "#" + argHash + "#" + System.nanoTime();
    }
}

// ======================== 4. 客户端网络通信 ========================

public class RpcClient {

    private static final Logger log = LoggerFactory.getLogger(RpcClient.class);

    private final Channel channel;
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    // requestId -> Future，用于异步转同步
    private final ConcurrentMap<String, CompletableFuture<RpcResponse>> pendingRequests =
        new ConcurrentHashMap<>();

    // 幂等控制：记录已发送的请求，防止业务层重复发送
    private final Cache<String, Boolean> sentRequestCache =
        Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

    public RpcClient(String host, int port) throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(new LengthFieldBasedFrameDecoder(65535, 0, 4, 0, 4))
                        .addLast(new RpcResponseDecoder())
                        .addLast(new RpcRequestEncoder())
                        .addLast(new RpcResponseHandler(pendingRequests));
                }
            });
        ChannelFuture future = bootstrap.connect(host, port).sync();
        this.channel = future.channel();
        log.info("[Consumer] RpcClient连接成功 | remote={}:{}", host, port);
    }

    /**
     * 发送RPC请求（同步等待响应）
     */
    public RpcResponse sendRequest(RpcRequest request, int timeoutMs) {
        String requestId = request.getRequestId();

        // 幂等检查：如果同一个requestId已经在飞行中，复用同一个Future
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        CompletableFuture<RpcResponse> existing = pendingRequests.putIfAbsent(requestId, future);
        if (existing != null) {
            log.info("[Consumer] 请求复用（幂等）| requestId={}", requestId);
            future = existing;
        }

        try {
            // 序列化请求
            byte[] body = Hessian2Serializer.serialize(request);

            // 构造协议帧并发送
            ByteBuf buf = channel.alloc().buffer(4 + body.length);
            buf.writeInt(body.length);
            buf.writeBytes(body);
            channel.writeAndFlush(buf);

            log.debug("[Consumer] 请求已发送 | requestId={} bodySize={}bytes", requestId, body.length);

            // 等待响应，带超时
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时后移除pending请求，防止内存泄漏
            pendingRequests.remove(requestId);
            RpcResponse timeoutResp = new RpcResponse();
            timeoutResp.setRequestId(requestId);
            timeoutResp.setStatus(1);
            timeoutResp.setErrorMessage("请求超时: " + timeoutMs + "ms");
            log.warn("[Consumer] 请求超时 | requestId={} timeout={}ms", requestId, timeoutMs);
            return timeoutResp;
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            log.error("[Consumer] 请求发送失败 | requestId={}", requestId, e);
            RpcResponse errorResp = new RpcResponse();
            errorResp.setRequestId(requestId);
            errorResp.setStatus(2);
            errorResp.setErrorMessage(e.getMessage());
            return errorResp;
        }
    }
}

// Netty响应处理器
@ChannelHandler.Sharable
public class RpcResponseHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(RpcResponseHandler.class);
    private final ConcurrentMap<String, CompletableFuture<RpcResponse>> pendingRequests;

    public RpcResponseHandler(ConcurrentMap<String, CompletableFuture<RpcResponse>> pendingRequests) {
        this.pendingRequests = pendingRequests;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        // 反序列化响应
        int len = buf.readInt();
        byte[] data = new byte[len];
        buf.readBytes(data);

        RpcResponse response = Hessian2Serializer.deserialize(data, RpcResponse.class);

        // 通过requestId匹配Future并完成
        CompletableFuture<RpcResponse> future = pendingRequests.remove(response.getRequestId());
        if (future != null) {
            future.complete(response);
            log.debug("[Consumer] 收到响应 | requestId={} status={}",
                    response.getRequestId(), response.getStatus());
        } else {
            log.warn("[Consumer] 收到孤儿响应（Future已过期）| requestId={}",
                    response.getRequestId());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[Consumer] Netty通道异常", cause);
        ctx.close();
    }
}

// ======================== 5. 服务端处理 ========================

public class RpcServerHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(RpcServerHandler.class);

    // 服务名 -> 服务实现实例
    private final Map<String, Object> serviceMap;
    // 业务线程池
    private final ExecutorService businessExecutor;
    // 幂等缓存：requestId -> 已处理的响应结果
    private final Cache<String, RpcResponse> idempotentCache =
        Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(50_000)
                .build();

    public RpcServerHandler(Map<String, Object> serviceMap, ExecutorService businessExecutor) {
        this.serviceMap = serviceMap;
        this.businessExecutor = businessExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
        // 1. 反序列化请求
        int len = buf.readInt();
        byte[] data = new byte[len];
        buf.readBytes(data);

        RpcRequest request;
        try {
            request = Hessian2Serializer.deserialize(data, RpcRequest.class);
        } catch (Exception e) {
            log.error("[Provider] 请求反序列化失败", e);
            return;
        }

        log.info("[Provider] 收到请求 | service={} method={} requestId={} traceId={}",
                request.getServiceName(), request.getMethodName(),
                request.getRequestId(), request.getAttachments().get("traceId"));

        // 2. 提交到业务线程池执行（不阻塞I/O线程）
        businessExecutor.submit(() -> {
            RpcResponse response;

            try {
                // 3. 幂等检查：如果该requestId已处理过，直接返回缓存结果
                response = idempotentCache.getIfPresent(request.getRequestId());
                if (response != null) {
                    log.info("[Provider] 幂等命中，返回缓存结果 | requestId={}",
                            request.getRequestId());
                } else {
                    // 4. 反射调用目标方法
                    response = invokeService(request);

                    // 5. 缓存结果用于幂等去重
                    idempotentCache.put(request.getRequestId(), response);
                }
            } catch (Exception e) {
                log.error("[Provider] 方法执行异常 | requestId={}", request.getRequestId(), e);
                response = new RpcResponse();
                response.setRequestId(request.getRequestId());
                response.setStatus(2);
                response.setErrorMessage(e.getMessage());
            }

            // 6. 序列化响应并写回
            try {
                byte[] body = Hessian2Serializer.serialize(response);
                ByteBuf respBuf = ctx.alloc().buffer(4 + body.length);
                respBuf.writeInt(body.length);
                respBuf.writeBytes(body);
                ctx.writeAndFlush(respBuf);

                log.info("[Provider] 响应已发送 | requestId={} status={}",
                        request.getRequestId(), response.getStatus());
            } catch (Exception e) {
                log.error("[Provider] 响应序列化失败 | requestId={}", request.getRequestId(), e);
            }
        });
    }

    /**
     * 反射调用目标方法
     */
    private RpcResponse invokeService(RpcRequest request) throws Exception {
        Object serviceBean = serviceMap.get(request.getServiceName());
        if (serviceBean == null) {
            throw new RuntimeException("服务不存在: " + request.getServiceName());
        }

        Class<?> serviceClass = serviceBean.getClass();
        Method method = serviceClass.getMethod(
            request.getMethodName(), request.getParameterTypes());

        log.debug("[Provider] 反射调用 | service={} method={}",
                request.getServiceName(), request.getMethodName());

        // 执行方法
        Object result = method.invoke(serviceBean, request.getArgs());

        // 构造成功响应
        RpcResponse response = new RpcResponse();
        response.setRequestId(request.getRequestId());
        response.setStatus(0);
        response.setResult(result);
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[Provider] 通道异常", cause);
        ctx.close();
    }
}

// ======================== 6. 服务端业务实现 ========================

public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Override
    public CreateOrderResponse createOrder(CreateOrderRequest request) {
        log.info("[Provider-OrderService] 创建订单 | userId={} productIds={}",
                request.getUserId(), request.getProductIds());

        try {
            // 模拟业务处理：扣减库存、创建订单记录
            long orderId = System.currentTimeMillis();
            Thread.sleep(10); // 模拟DB操作耗时

            CreateOrderResponse response = new CreateOrderResponse();
            response.setCode(0);
            response.setMessage("success");
            response.setOrderId(orderId);
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Provider-OrderService] 创建订单被中断", e);
            CreateOrderResponse resp = new CreateOrderResponse();
            resp.setCode(-1);
            resp.setMessage("服务被中断");
            return resp;
        } catch (Exception e) {
            log.error("[Provider-OrderService] 创建订单异常", e);
            CreateOrderResponse resp = new CreateOrderResponse();
            resp.setCode(-1);
            resp.setMessage("创建订单失败: " + e.getMessage());
            return resp;
        }
    }
}

// ======================== 7. 完整调用示例 ========================

public class FullLinkExample {

    private static final Logger log = LoggerFactory.getLogger(FullLinkExample.class);

    public static void main(String[] args) {
        // --- Provider端启动 ---
        Map<String, Object> serviceMap = new HashMap<>();
        serviceMap.put("com.example.OrderService", new OrderServiceImpl());

        ExecutorService bizPool = new ThreadPoolExecutor(
            10, 256, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(0),  // queue=0，CallerRunsPolicy
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // 启动Netty服务端（伪代码，省略Bootstrap配置）
        // RpcServer.start(8080, new RpcServerHandler(serviceMap, bizPool));

        // --- Consumer端调用 ---
        try {
            RpcClient rpcClient = new RpcClient("127.0.0.1", 8080);

            // 通过动态代理创建远程服务引用
            OrderService orderService = RpcClientProxy.create(
                rpcClient, "com.example.OrderService", OrderService.class, 3000);

            // 像调用本地方法一样调用远程服务
            CreateOrderRequest request = new CreateOrderRequest();
            request.setUserId(10001L);
            request.setProductIds(Arrays.asList(1L, 2L, 3L));
            request.setCouponCode("SAVE10");

            // 设置链路追踪上下文
            TraceContext.setTraceId("trace-0001");

            CreateOrderResponse response = orderService.createOrder(request);

            log.info("[Consumer] 订单创建结果 | code={} orderId={} message={}",
                    response.getCode(), response.getOrderId(), response.getMessage());
        } catch (Exception e) {
            log.error("[Consumer] 全链路调用失败", e);
        }
    }
}
```

### 案例二：服务注册与发现全链路

**场景描述**：Provider启动时注册到注册中心，通过心跳保活维持注册状态。当Provider扩容或宕机时，注册中心推送变更通知，Consumer更新本地缓存并执行故障摘除。

```
Provider启动                Registry注册中心              Consumer
    │                            │                          │
    │  ①注册服务元信息 ──────────►│                          │
    │                            │  持久化存储               │
    │                            │                          │
    │  ②定时心跳（5s间隔）──────►│                          │
    │                            │  更新lastHeartbeat        │
    │                            │                          │
    │                            │  ③推送变更通知 ──────────►│
    │                            │  (NODE_ADDED/REMOVED)     │
    │                            │                          │  ④更新本地缓存
    │                            │                          │
    │  ╳ Provider宕机             │                          │
    │  心跳停止                   │                          │
    │                            │  ⑤检测心跳超时            │
    │                            │  标记DEAD                 │
    │                            │  ⑥推送NODE_REMOVED ──────►│
    │                            │                          │  ⑦故障摘除
    │                            │                          │  从缓存移除节点
```

```java
// ======================== 1. 注册中心客户端（Provider端） ========================

public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);

    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(2);
    private final RegistryStore registryStore; // 注册中心存储接口

    // 心跳配置
    private static final long HEARTBEAT_INTERVAL_MS = 5_000;  // 心跳间隔5秒
    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;   // 15秒无心跳标记不健康
    private static final long HEARTBEAT_REMOVE_MS = 30_000;    // 30秒无心跳移除节点

    /**
     * Provider启动时注册服务
     */
    public void register(ServiceInstance instance) {
        try {
            // 1. 构建注册元信息
            instance.setStatus(ServiceInstance.ServiceStatus.ALIVE);
            instance.setLastHeartbeat(System.currentTimeMillis());

            // 2. 写入注册中心
            registryStore.register(instance);

            log.info("[Registry] 服务注册成功 | service={} address={}:{} weight={}",
                    instance.getServiceName(), instance.getHost(),
                    instance.getPort(), instance.getWeight());

            // 3. 启动定时心跳
            startHeartbeat(instance);

        } catch (Exception e) {
            log.error("[Registry] 服务注册失败 | service={}", instance.getServiceName(), e);
            throw new RuntimeException("服务注册失败", e);
        }
    }

    /**
     * 启动定时心跳保活
     */
    private void startHeartbeat(ServiceInstance instance) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                instance.setLastHeartbeat(System.currentTimeMillis());
                registryStore.heartbeat(instance);
                log.debug("[Registry] 心跳上报成功 | service={} address={}:{}",
                        instance.getServiceName(), instance.getHost(), instance.getPort());
            } catch (Exception e) {
                log.warn("[Registry] 心跳上报失败 | service={} address={}:{}",
                        instance.getServiceName(), instance.getHost(), instance.getPort(), e);
                // 心跳失败时尝试重新注册
                tryReRegister(instance);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 心跳失败时的重新注册
     */
    private void tryReRegister(ServiceInstance instance) {
        int retryCount = 0;
        while (retryCount < 3) {
            try {
                Thread.sleep(2000);
                registryStore.register(instance);
                log.info("[Registry] 重新注册成功 | service={} retryCount={}",
                        instance.getServiceName(), retryCount);
                return;
            } catch (Exception e) {
                retryCount++;
                log.error("[Registry] 重新注册失败 | retryCount={}", retryCount, e);
            }
        }
        log.error("[Registry] 重新注册彻底失败 | service={}", instance.getServiceName());
    }

    /**
     * Provider优雅下线时注销服务
     */
    public void unregister(ServiceInstance instance) {
        try {
            registryStore.unregister(instance);
            scheduler.shutdown();
            log.info("[Registry] 服务注销成功 | service={}", instance.getServiceName());
        } catch (Exception e) {
            log.error("[Registry] 服务注销失败 | service={}", instance.getServiceName(), e);
        }
    }
}

// ======================== 2. 注册中心存储与服务变更通知 ========================

public class RegistryStore {

    private static final Logger log = LoggerFactory.getLogger(RegistryStore.class);

    // 服务名 -> 实例列表（注册中心核心存储）
    private final ConcurrentMap<String, CopyOnWriteArrayList<ServiceInstance>> serviceStore =
        new ConcurrentHashMap<>();

    // 服务名 -> 订阅者列表（变更通知回调）
    private final ConcurrentMap<String, List<Consumer<ServiceChangeEvent>>> subscribers =
        new ConcurrentHashMap<>();

    // 心跳超时检测调度器
    private final ScheduledExecutorService healthScheduler =
        Executors.newScheduledThreadPool(2);

    public RegistryStore() {
        // 启动心跳超时检测任务
        startHeartbeatCheck();
    }

    /**
     * 注册服务实例
     */
    public void register(ServiceInstance instance) {
        serviceStore.computeIfAbsent(
            instance.getServiceName(),
            k -> new CopyOnWriteArrayList<>()
        ).addIfAbsent(instance);

        log.info("[RegistryStore] 节点注册 | service={} address={}:{}",
                instance.getServiceName(), instance.getHost(), instance.getPort());

        // 通知所有订阅者：新节点上线
        notifySubscribers(instance.getServiceName(),
            new ServiceChangeEvent(instance, ServiceChangeEvent.Type.NODE_ADDED));
    }

    /**
     * 心跳更新
     */
    public void heartbeat(ServiceInstance instance) {
        CopyOnWriteArrayList<ServiceInstance> instances =
            serviceStore.get(instance.getServiceName());
        if (instances != null) {
            for (ServiceInstance inst : instances) {
                if (inst.getAddress().equals(instance.getAddress())) {
                    inst.setLastHeartbeat(System.currentTimeMillis());
                    if (inst.getStatus() != ServiceInstance.ServiceStatus.ALIVE) {
                        inst.setStatus(ServiceInstance.ServiceStatus.ALIVE);
                        // 节点恢复，通知订阅者
                        notifySubscribers(instance.getServiceName(),
                            new ServiceChangeEvent(inst, ServiceChangeEvent.Type.NODE_UPDATED));
                    }
                    break;
                }
            }
        }
    }

    /**
     * 注销服务实例
     */
    public void unregister(ServiceInstance instance) {
        CopyOnWriteArrayList<ServiceInstance> instances =
            serviceStore.get(instance.getServiceName());
        if (instances != null) {
            instances.removeIf(inst -> inst.getAddress().equals(instance.getAddress()));
        }

        log.info("[RegistryStore] 节点注销 | service={} address={}:{}",
                instance.getServiceName(), instance.getHost(), instance.getPort());

        notifySubscribers(instance.getServiceName(),
            new ServiceChangeEvent(instance, ServiceChangeEvent.Type.NODE_REMOVED));
    }

    /**
     * 全量拉取服务实例列表
     */
    public List<ServiceInstance> fetchInstances(String serviceName) {
        CopyOnWriteArrayList<ServiceInstance> instances = serviceStore.get(serviceName);
        if (instances == null) {
            return Collections.emptyList();
        }
        return instances.stream()
            .filter(i -> i.getStatus() == ServiceInstance.ServiceStatus.ALIVE)
            .collect(Collectors.toList());
    }

    /**
     * 订阅服务变更
     */
    public void subscribe(String serviceName, Consumer<ServiceChangeEvent> listener) {
        subscribers.computeIfAbsent(serviceName, k -> new CopyOnWriteArrayList<>())
                   .add(listener);
        log.info("[RegistryStore] 订阅服务变更 | service={}", serviceName);
    }

    /**
     * 推送变更通知给所有订阅者
     */
    private void notifySubscribers(String serviceName, ServiceChangeEvent event) {
        List<Consumer<ServiceChangeEvent>> listeners = subscribers.get(serviceName);
        if (listeners == null) return;

        for (Consumer<ServiceChangeEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("[RegistryStore] 变更通知回调异常 | service={}", serviceName, e);
            }
        }
    }

    /**
     * 心跳超时检测：定期扫描所有节点，超时则标记不健康或移除
     */
    private void startHeartbeatCheck() {
        healthScheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            for (Map.Entry<String, CopyOnWriteArrayList<ServiceInstance>> entry :
                 serviceStore.entrySet()) {
                String serviceName = entry.getKey();
                Iterator<ServiceInstance> iter = entry.getValue().iterator();

                while (iter.hasNext()) {
                    ServiceInstance inst = iter.next();
                    long elapsed = now - inst.getLastHeartbeat();

                    if (elapsed > HEARTBEAT_REMOVE_MS) {
                        // 超过30秒无心跳，移除节点
                        iter.remove();
                        log.warn("[RegistryStore] 心跳超时移除节点 | service={} address={}:{} elapsed={}ms",
                                serviceName, inst.getHost(), inst.getPort(), elapsed);
                        notifySubscribers(serviceName,
                            new ServiceChangeEvent(inst, ServiceChangeEvent.Type.NODE_REMOVED));
                    } else if (elapsed > HEARTBEAT_TIMEOUT_MS
                               && inst.getStatus() == ServiceInstance.ServiceStatus.ALIVE) {
                        // 超过15秒无心跳，标记不健康（但仍保留在列表中）
                        inst.setStatus(ServiceInstance.ServiceStatus.DEAD);
                        log.warn("[RegistryStore] 心跳超时标记不健康 | service={} address={}:{} elapsed={}ms",
                                serviceName, inst.getHost(), inst.getPort(), elapsed);
                        notifySubscribers(serviceName,
                            new ServiceChangeEvent(inst, ServiceChangeEvent.Type.NODE_UPDATED));
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private static final long HEARTBEAT_TIMEOUT_MS = 15_000;
    private static final long HEARTBEAT_REMOVE_MS = 30_000;
}

// ======================== 3. 服务变更事件 ========================

public class ServiceChangeEvent {
    public enum Type { NODE_ADDED, NODE_REMOVED, NODE_UPDATED }

    private final ServiceInstance instance;
    private final Type type;

    public ServiceChangeEvent(ServiceInstance instance, Type type) {
        this.instance = instance;
        this.type = type;
    }

    public ServiceInstance getInstance() { return instance; }
    public Type getType() { return type; }
}

// ======================== 4. Consumer端服务发现与缓存管理 ========================

public class ServiceDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ServiceDiscovery.class);

    // 本地缓存：服务名 -> 可用实例列表
    private final ConcurrentMap<String, CopyOnWriteArrayList<ServiceInstance>> localCache =
        new ConcurrentHashMap<>();

    // 故障节点隔离记录：address -> 连续失败次数
    private final ConcurrentMap<String, AtomicInteger> failureCounter =
        new ConcurrentHashMap<>();

    // 幂等控制：防止重复处理同一个变更事件
    private final Cache<String, Boolean> processedEvents =
        Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

    private final RegistryStore registryStore;
    private final Lock updateLock = new ReentrantLock();

    // 故障摘除阈值
    private static final int FAIL_THRESHOLD = 3;

    public ServiceDiscovery(RegistryStore registryStore) {
        this.registryStore = registryStore;
    }

    /**
     * 订阅服务：全量拉取 + 注册变更监听
     */
    public List<ServiceInstance> subscribe(String serviceName) {
        // 1. 首次全量拉取
        List<ServiceInstance> instances = registryStore.fetchInstances(serviceName);
        localCache.put(serviceName, new CopyOnWriteArrayList<>(instances));

        log.info("[Discovery] 首次拉取服务列表 | service={} instanceCount={}",
                serviceName, instances.size());
        instances.forEach(i -> log.info("[Discovery]   - {}:{} weight={}",
                i.getHost(), i.getPort(), i.getWeight()));

        // 2. 注册变更监听
        registryStore.subscribe(serviceName, event -> {
            String eventKey = serviceName + "#" + event.getType() + "#"
                            + event.getInstance().getAddress();
            // 幂等控制：同一事件只处理一次
            if (processedEvents.asMap().putIfAbsent(eventKey, true) != null) {
                log.debug("[Discovery] 重复事件已跳过 | eventKey={}", eventKey);
                return;
            }
            handleServiceChange(serviceName, event);
        });

        return instances;
    }

    /**
     * 处理服务变更事件
     */
    private void handleServiceChange(String serviceName, ServiceChangeEvent event) {
        updateLock.lock();
        try {
            CopyOnWriteArrayList<ServiceInstance> instances =
                localCache.get(serviceName);
            if (instances == null) {
                instances = new CopyOnWriteArrayList<>();
                localCache.put(serviceName, instances);
            }

            ServiceInstance inst = event.getInstance();
            String address = inst.getAddress();

            switch (event.getType()) {
                case NODE_ADDED:
                    // 新节点上线，添加到本地缓存
                    instances.addIfAbsent(inst);
                    // 清除该节点的故障计数
                    failureCounter.remove(address);
                    log.info("[Discovery] 节点上线已加入缓存 | service={} address={} 当前节点数={}",
                            serviceName, address, instances.size());
                    break;

                case NODE_REMOVED:
                    // 节点下线，从本地缓存移除（故障摘除）
                    instances.removeIf(i -> i.getAddress().equals(address));
                    failureCounter.remove(address);
                    log.warn("[Discovery] 节点下线已从缓存摘除 | service={} address={} 当前节点数={}",
                            serviceName, address, instances.size());
                    break;

                case NODE_UPDATED:
                    // 节点状态变更（如权重调整、标记不健康）
                    instances.removeIf(i -> i.getAddress().equals(address));
                    if (inst.getStatus() == ServiceInstance.ServiceStatus.ALIVE) {
                        instances.addIfAbsent(inst);
                        log.info("[Discovery] 节点状态更新 | service={} address={} status=ALIVE",
                                serviceName, address);
                    } else {
                        log.warn("[Discovery] 节点标记不健康已摘除 | service={} address={} status={}",
                                serviceName, address, inst.getStatus());
                    }
                    break;
            }

            // 打印当前可用节点列表
            log.info("[Discovery] 当前可用节点列表 | service={} count={}",
                    serviceName, instances.size());
            for (ServiceInstance i : instances) {
                log.info("[Discovery]   - {}:{} weight={} status={}",
                        i.getHost(), i.getPort(), i.getWeight(), i.getStatus());
            }
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * 获取可用实例（从本地缓存读取）
     */
    public List<ServiceInstance> getInstances(String serviceName) {
        CopyOnWriteArrayList<ServiceInstance> instances = localCache.get(serviceName);
        if (instances == null || instances.isEmpty()) {
            // 缓存未命中，降级到直接查询注册中心
            log.warn("[Discovery] 本地缓存为空，降级查询注册中心 | service={}", serviceName);
            List<ServiceInstance> fresh = registryStore.fetchInstances(serviceName);
            if (!fresh.isEmpty()) {
                localCache.put(serviceName, new CopyOnWriteArrayList<>(fresh));
                return fresh;
            }
            throw new RuntimeException("无可用服务实例: " + serviceName);
        }
        return new ArrayList<>(instances);
    }

    /**
     * 主动故障摘除：调用失败时上报，连续失败超过阈值则摘除
     */
    public void reportFailure(String serviceName, String address) {
        AtomicInteger count = failureCounter.computeIfAbsent(address, k -> new AtomicInteger(0));
        int failCount = count.incrementAndGet();

        log.warn("[Discovery] 调用失败上报 | service={} address={} 连续失败次数={}",
                serviceName, address, failCount);

        if (failCount >= FAIL_THRESHOLD) {
            // 超过阈值，主动摘除
            CopyOnWriteArrayList<ServiceInstance> instances = localCache.get(serviceName);
            if (instances != null) {
                instances.removeIf(i -> i.getAddress().equals(address));
            }
            log.error("[Discovery] 故障节点主动摘除 | service={} address={} 连续失败={}",
                    serviceName, address, failCount);
        }
    }

    /**
     * 调用成功时重置故障计数
     */
    public void reportSuccess(String serviceName, String address) {
        failureCounter.remove(address);
    }
}

// ======================== 5. 完整注册与发现示例 ========================

public class RegistryDiscoveryExample {

    private static final Logger log = LoggerFactory.getLogger(RegistryDiscoveryExample.class);

    public static void main(String[] args) throws Exception {
        // 1. 初始化注册中心
        RegistryStore registryStore = new RegistryStore();

        // 2. Provider启动并注册
        RegistryClient registryClient = new RegistryClient(registryStore);

        ServiceInstance provider1 = new ServiceInstance();
        provider1.setServiceName("com.example.OrderService");
        provider1.setHost("10.0.0.1");
        provider1.setPort(8080);
        provider1.setWeight(100);
        registryClient.register(provider1);

        ServiceInstance provider2 = new ServiceInstance();
        provider2.setServiceName("com.example.OrderService");
        provider2.setHost("10.0.0.2");
        provider2.setPort(8080);
        provider2.setWeight(100);
        registryClient.register(provider2);

        // 3. Consumer订阅服务
        ServiceDiscovery discovery = new ServiceDiscovery(registryStore);
        List<ServiceInstance> instances = discovery.subscribe("com.example.OrderService");

        log.info("[Main] Consumer获取到服务列表 | count={}", instances.size());

        // 4. 模拟Provider宕机（注销）
        Thread.sleep(2000);
        log.info("[Main] 模拟Provider2宕机...");
        registryClient.unregister(provider2);

        // 5. 观察Consumer缓存是否自动更新
        Thread.sleep(1000);
        List<ServiceInstance> currentInstances =
            discovery.getInstances("com.example.OrderService");
        log.info("[Main] Provider2宕机后，Consumer缓存中剩余节点数={}", currentInstances.size());

        // 6. 模拟调用失败导致主动摘除
        log.info("[Main] 模拟Provider1连续调用失败...");
        discovery.reportFailure("com.example.OrderService", "10.0.0.1:8080");
        discovery.reportFailure("com.example.OrderService", "10.0.0.1:8080");
        discovery.reportFailure("com.example.OrderService", "10.0.0.1:8080");

        currentInstances = discovery.getInstances("com.example.OrderService");
        log.info("[Main] 主动摘除后，Consumer缓存中剩余节点数={}", currentInstances.size());
    }
}
```

### 案例三：RPC超时重试与故障转移全链路

**场景描述**：Consumer调用Provider时发生超时，触发重试策略。重试时切换到其他健康节点（负载均衡切换），对连续失败的节点执行故障摘除。当所有节点都不可用时，执行降级兜底返回默认值或缓存数据。

```
Consumer调用
    │
    ▼
选择节点A（负载均衡）
    │
    ├── 调用节点A ──► 超时（3s）
    │                   │
    │                   ▼
    │              记录失败，连续失败计数+1
    │                   │
    │                   ▼
    │              连续失败≥3次？──► 是 ──► 故障摘除节点A
    │                   │                         │
    │                   否                        │
    │                   │                         │
    ▼                   ▼                         │
重试（排除节点A）                                 │
    │                                             │
    ▼                                             │
选择节点B（负载均衡切换）                          │
    │                                             │
    ├── 调用节点B ──► 成功                        │
    │                   │                         │
    │                   ▼                         │
    │              返回结果                       │
    │                                             │
    └── 调用节点B ──► 也超时                      │
                        │                        │
                        ▼                        │
                   重试次数耗尽？                  │
                        │                        │
                   是 ──► 降级兜底               │
                        │  返回默认值/缓存        │
                        │  记录告警日志           │
```

```java
// ======================== 1. 重试容错调用器 ========================

public class FailoverInvoker implements ClusterInvoker {

    private static final Logger log = LoggerFactory.getLogger(FailoverInvoker.class);

    private final int maxRetries;          // 最大重试次数（不含首次调用）
    private final int timeoutMs;           // 单次调用超时时间
    private final ServiceDiscovery discovery;
    private final LoadBalancer loadBalancer;
    private final DegradeHandler degradeHandler;

    // 幂等控制：记录已重试的requestId集合
    private final Cache<String, Integer> retryCountCache =
        Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .build();

    public FailoverInvoker(int maxRetries, int timeoutMs,
                           ServiceDiscovery discovery, LoadBalancer loadBalancer,
                           DegradeHandler degradeHandler) {
        this.maxRetries = maxRetries;
        this.timeoutMs = timeoutMs;
        this.discovery = discovery;
        this.loadBalancer = loadBalancer;
        this.degradeHandler = degradeHandler;
    }

    /**
     * 执行RPC调用，包含超时重试、故障转移、降级兜底全链路
     */
    @Override
    public Result invoke(Invocation invocation) {
        String requestId = invocation.getAttachment("requestId");
        String serviceName = invocation.getServiceName();

        // 幂等控制：统计同一requestId的重试次数
        int currentRetry = retryCountCache.asMap()
            .compute(requestId, (k, v) -> v == null ? 0 : v + 1);

        if (currentRetry > maxRetries) {
            log.error("[Failover] 重试次数耗尽，执行降级 | requestId={} retryCount={}/{}",
                    requestId, currentRetry, maxRetries);
            return degradeHandler.degrade(invocation);
        }

        // 获取可用节点列表
        List<ServiceInstance> instances;
        try {
            instances = discovery.getInstances(serviceName);
        } catch (Exception e) {
            log.error("[Failover] 获取服务列表失败，执行降级 | service={}", serviceName, e);
            return degradeHandler.degrade(invocation);
        }

        if (instances.isEmpty()) {
            log.error("[Failover] 无可用节点，执行降级 | service={}", serviceName);
            return degradeHandler.degrade(invocation);
        }

        // 记录已尝试的节点（重试时排除）
        Set<String> triedAddresses = new HashSet<>();
        RpcException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 过滤掉已尝试的节点
            List<ServiceInstance> candidates = instances.stream()
                .filter(i -> !triedAddresses.contains(i.getAddress()))
                .collect(Collectors.toList());

            if (candidates.isEmpty()) {
                log.warn("[Failover] 所有节点已尝试完毕，无更多候选 | requestId={}", requestId);
                break;
            }

            // 负载均衡选择节点
            ServiceInstance instance = loadBalancer.select(candidates);
            triedAddresses.add(instance.getAddress());

            log.info("[Failover] 第{}次尝试调用 | requestId={} service={} address={}:{} timeout={}ms",
                    attempt + 1, requestId, serviceName,
                    instance.getHost(), instance.getPort(), timeoutMs);

            long startTime = System.currentTimeMillis();

            try {
                // 执行RPC调用（带超时控制）
                Result result = doInvoke(instance, invocation, timeoutMs);

                long elapsed = System.currentTimeMillis() - startTime;

                if (result.isSuccess()) {
                    // 调用成功，重置故障计数
                    discovery.reportSuccess(serviceName, instance.getAddress());
                    log.info("[Failover] 调用成功 | requestId={} address={} elapsed={}ms attempt={}",
                            requestId, instance.getAddress(), elapsed, attempt + 1);
                    return result;
                } else {
                    // 业务层返回失败
                    log.warn("[Failover] 调用返回失败 | requestId={} address={} error={}",
                            requestId, instance.getAddress(), result.getErrorMessage());
                    discovery.reportFailure(serviceName, instance.getAddress());
                    lastException = new RpcException(result.getErrorMessage());
                }
            } catch (RpcTimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.warn("[Failover] 调用超时 | requestId={} address={} elapsed={}ms attempt={}/{}",
                        requestId, instance.getAddress(), elapsed, attempt + 1, maxRetries + 1);
                discovery.reportFailure(serviceName, instance.getAddress());
                lastException = e;
            } catch (RpcException e) {
                log.warn("[Failover] 调用异常 | requestId={} address={} error={}",
                        requestId, instance.getAddress(), e.getMessage());
                discovery.reportFailure(serviceName, instance.getAddress());
                lastException = e;
            } catch (Exception e) {
                log.error("[Failover] 调用未知异常 | requestId={} address={}",
                        requestId, instance.getAddress(), e);
                discovery.reportFailure(serviceName, instance.getAddress());
                lastException = new RpcException("未知异常", e);
            }
        }

        // 所有重试都失败，执行降级兜底
        log.error("[Failover] 所有节点调用均失败，执行降级 | requestId={} service={} triedNodes={}",
                requestId, serviceName, triedAddresses);

        // 记录告警日志（供运维监控）
        log.error("[ALERT] RPC全链路失败 | service={} requestId={} maxRetries={} lastError={}",
                serviceName, requestId, maxRetries,
                lastException != null ? lastException.getMessage() : "unknown");

        return degradeHandler.degrade(invocation);
    }

    /**
     * 执行单次RPC调用
     */
    private Result doInvoke(ServiceInstance instance, Invocation invocation,
                            int timeoutMs) {
        try {
            // 构造RPC请求
            RpcRequest request = new RpcRequest();
            request.setRequestId(invocation.getAttachment("requestId"));
            request.setServiceName(invocation.getServiceName());
            request.setMethodName(invocation.getMethodName());
            request.setParameterTypes(invocation.getParameterTypes());
            request.setArgs(invocation.getArgs());

            // 发送到目标节点（带超时）
            RpcResponse response = rpcClient.sendToInstance(instance, request, timeoutMs);

            if (response.getStatus() == 0) {
                return new Result(true, response.getResult(), null);
            } else if (response.getStatus() == 1) {
                throw new RpcTimeoutException(response.getErrorMessage());
            } else {
                return new Result(false, null, response.getErrorMessage());
            }
        } catch (RpcTimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new RpcException("调用异常: " + e.getMessage(), e);
        }
    }

    private final RpcClient rpcClient; // 注入的RPC客户端
}

// ======================== 2. 降级处理器 ========================

public class DegradeHandler {

    private static final Logger log = LoggerFactory.getLogger(DegradeHandler.class);

    // 本地缓存：方法签名 -> 上次成功结果（用于降级时返回缓存值）
    private final Cache<String, Object> lastSuccessCache =
        Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(5_000)
                .build();

    /**
     * 执行降级逻辑
     */
    public Result degrade(Invocation invocation) {
        String methodKey = invocation.getServiceName() + "#" + invocation.getMethodName();
        Class<?> returnType = invocation.getReturnType();

        log.warn("[Degrade] 开始执行降级策略 | method={} returnType={}",
                methodKey, returnType.getSimpleName());

        // 策略1: 尝试返回上次成功的结果（缓存兜底）
        Object cachedResult = lastSuccessCache.getIfPresent(methodKey);
        if (cachedResult != null) {
            log.info("[Degrade] 降级策略: 返回缓存值 | method={}", methodKey);
            return new Result(true, cachedResult, "degraded-from-cache");
        }

        // 策略2: 返回类型默认值
        Object defaultValue = getDefaultValue(returnType);
        log.info("[Degrade] 降级策略: 返回默认值 | method={} defaultValue={}",
                methodKey, defaultValue);
        return new Result(true, defaultValue, "degraded-default-value");
    }

    /**
     * 更新缓存（调用成功时调用）
     */
    public void updateCache(String methodKey, Object result) {
        lastSuccessCache.put(methodKey, result);
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0;
        if (type == String.class) return "";
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        if (Map.class.isAssignableFrom(type)) return Collections.emptyMap();
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
}

// ======================== 3. Result与Invocation对象 ========================

public class Result {
    private final boolean success;
    private final Object value;
    private final String errorMessage;

    public Result(boolean success, Object value, String errorMessage) {
        this.success = success;
        this.value = value;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public Object getValue() { return value; }
    public String getErrorMessage() { return errorMessage; }
}

public class Invocation {
    private String serviceName;
    private String methodName;
    private Class<?>[] parameterTypes;
    private Object[] args;
    private Class<?> returnType;
    private Map<String, String> attachments = new HashMap<>();

    // getter/setter省略
    public String getAttachment(String key) { return attachments.get(key); }
    public void setAttachment(String key, String value) { attachments.put(key, value); }
    public String getServiceName() { return serviceName; }
    public String getMethodName() { return methodName; }
    public Class<?>[] getParameterTypes() { return parameterTypes; }
    public Object[] getArgs() { return args; }
    public Class<?> getReturnType() { return returnType; }
}

// ======================== 4. 异常定义 ========================

public class RpcException extends RuntimeException {
    public RpcException(String message) { super(message); }
    public RpcException(String message, Throwable cause) { super(message, cause); }
}

public class RpcTimeoutException extends RpcException {
    public RpcTimeoutException(String message) { super(message); }
}

// ======================== 5. 完整重试与故障转移示例 ========================

public class FailoverExample {

    private static final Logger log = LoggerFactory.getLogger(FailoverExample.class);

    public static void main(String[] args) {
        // 1. 初始化注册中心和服务发现
        RegistryStore registryStore = new RegistryStore();
        ServiceDiscovery discovery = new ServiceDiscovery(registryStore);

        // 注册3个Provider节点
        for (int i = 1; i <= 3; i++) {
            ServiceInstance inst = new ServiceInstance();
            inst.setServiceName("com.example.OrderService");
            inst.setHost("10.0.0." + i);
            inst.setPort(8080);
            inst.setWeight(100);
            registryStore.register(inst);
        }

        discovery.subscribe("com.example.OrderService");

        // 2. 初始化降级处理器和负载均衡器
        DegradeHandler degradeHandler = new DegradeHandler();
        LoadBalancer loadBalancer = new WeightedRandomLoadBalancer();

        // 3. 初始化Failover调用器
        //    最大重试2次，单次超时3000ms
        FailoverInvoker invoker = new FailoverInvoker(
            2, 3000, discovery, loadBalancer, degradeHandler
        );

        // 4. 构造调用请求
        Invocation invocation = new Invocation();
        invocation.setServiceName("com.example.OrderService");
        invocation.setMethodName("createOrder");
        invocation.setParameterTypes(new Class[]{CreateOrderRequest.class});
        invocation.setArgs(new Object[]{buildOrderRequest()});
        invocation.setReturnType(CreateOrderResponse.class);
        invocation.setAttachment("requestId", "req-0001-" + System.nanoTime());

        log.info("[Main] ===== 开始RPC调用（超时重试+故障转移）=====");
        log.info("[Main] 可用节点数: {}", discovery.getInstances("com.example.OrderService").size());

        // 5. 执行调用
        Result result = invoker.invoke(invocation);

        if (result.isSuccess()) {
            log.info("[Main] 调用最终成功 | result={} source={}",
                    result.getValue(), result.getErrorMessage());
        } else {
            log.error("[Main] 调用最终失败 | error={}", result.getErrorMessage());
        }

        // 6. 模拟所有节点都不可用，触发降级
        log.info("[Main] ===== 模拟所有节点不可用 =====");

        // 主动对每个节点上报3次失败，触发全部摘除
        for (int i = 1; i <= 3; i++) {
            String addr = "10.0.0." + i + ":8080";
            for (int j = 0; j < 3; j++) {
                discovery.reportFailure("com.example.OrderService", addr);
            }
        }

        log.info("[Main] 故障摘除后可用节点数: {}",
                discovery.getInstances("com.example.OrderService").size());

        // 再次调用，应该触发降级兜底
        Invocation invocation2 = new Invocation();
        invocation2.setServiceName("com.example.OrderService");
        invocation2.setMethodName("createOrder");
        invocation2.setParameterTypes(new Class[]{CreateOrderRequest.class});
        invocation2.setArgs(new Object[]{buildOrderRequest()});
        invocation2.setReturnType(CreateOrderResponse.class);
        invocation2.setAttachment("requestId", "req-0002-" + System.nanoTime());

        log.info("[Main] ===== 调用（预期降级兜底）=====");
        Result result2 = invoker.invoke(invocation2);

        if (result2.isSuccess()) {
            log.info("[Main] 降级兜底成功 | result={} source={}",
                    result2.getValue(), result2.getErrorMessage());
        } else {
            log.error("[Main] 降级兜底也失败 | error={}", result2.getErrorMessage());
        }
    }

    private static CreateOrderRequest buildOrderRequest() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setUserId(10001L);
        req.setProductIds(Arrays.asList(1L, 2L, 3L));
        req.setCouponCode("SAVE10");
        return req;
    }
}
```

**三个案例串联说明**：

| 案例 | 核心链路 | 关键设计 | 异常处理 | 幂等控制 |
|------|----------|----------|----------|----------|
| 案例一 | 一次RPC调用的完整生命周期 | 动态代理屏蔽网络细节，requestId关联请求-响应 | 超时/服务端异常/未知异常三层捕获 | Provider端基于requestId去重，重复请求直接返回缓存结果 |
| 案例二 | 服务注册到故障摘除的治理链路 | 推拉结合的变更通知，渐进式健康检查 | 心跳失败自动重注册，变更通知回调异常隔离 | Consumer端对变更事件幂等去重，防止重复处理 |
| 案例三 | 超时重试到降级兜底的容错链路 | Failover重试排除已试节点，负载均衡自动切换 | 超时/业务失败/未知异常分类处理，逐级降级 | requestId级别的重试次数控制，防止重试风暴 |

---

## 参考

- Apache Dubbo官方文档
- gRPC官方文档
- Envoy/Istio Service Mesh架构
- Netty源码
- 《微服务架构设计模式》
- 《分布式系统：概念与设计》
