# 微服务架构与领域驱动设计（DDD）架构设计文档

## 一、问题背景

### 1.1 要解决的核心问题

随着业务规模的增长，传统的单体应用架构会逐渐暴露出一系列结构性问题：

1. **业务复杂度与代码腐化的矛盾**：单体应用早期开发效率很高，但随着业务需求不断叠加，代码库会变成一个巨大的"泥球"（Big Ball of Mud）——模块边界模糊、职责交叉、任何一处修改都可能引发意料之外的连锁影响，团队逐渐"不敢改代码"。
2. **技术模型与业务模型的割裂**：很多系统的代码结构直接照搬数据库表结构（贫血模型），业务逻辑散落在 Service 层的各种工具方法中，代码读起来看不出真正的业务规则和领域概念，新人难以通过代码理解业务全貌。
3. **团队协作与发布节奏的耦合**：单体应用所有团队共享同一个代码库和部署单元，任何一个模块的发布都需要整体回归测试和统一部署窗口，团队之间互相阻塞，无法独立按照自己的节奏迭代和上线。
4. **技术选型与扩展能力的僵化**：单体应用中所有模块必须使用统一的技术栈和资源规格，即使某个模块（如订单查询）承受的流量是其他模块（如后台报表）的百倍，也无法针对性地单独扩容，造成资源浪费或扩容不足。
5. **服务拆分的边界难题**：即使决定拆分微服务，如果没有一套系统化的方法论指导"从哪里切"，很容易陷入按照技术层次（如拆成"用户服务""订单服务"看似清晰实则边界混乱）或按照数据表简单拆分的误区，最终演变成"分布式单体"——服务数量增多了，但服务之间强耦合、频繁互相调用，反而背负了微服务的复杂性却没有获得微服务的收益。

### 1.2 典型场景

- **电商平台从单体到微服务的演进**：早期一个 Java 单体应用承载商品、订单、库存、支付、用户等所有业务，随着交易量增长，需要拆分成独立服务，分别由不同团队维护、独立部署、按需扩容。
- **多团队并行开发同一系统**：大型系统需要几十上百名工程师同时开发，如果继续使用单体架构，代码合并冲突和联调成本会急剧上升，需要通过服务边界划分实现团队的独立作战。
- **复杂业务规则的建模**：金融、供应链等领域存在大量复杂的业务规则（利率计算、库存分配策略、风控规则），如果不用 DDD 的建模方法梳理清楚领域概念，代码会充斥着大量难以理解的 if-else 判断逻辑。
- **遗留系统的渐进式改造**：老系统技术债深重但业务仍在正常运转，无法进行"推倒重来"式的重构，需要一套稳妥的渐进式演进路径。

### 1.3 不解决的后果

- **需求交付速度持续下降**：随着代码复杂度累积，每个新需求的开发周期会越来越长，团队大量时间花在"理解现有代码在干什么"而非"实现新功能"上。
- **回归测试成本失控**：单体应用任何小改动都可能波及全局，每次发布都需要全量回归测试，发布周期被迫拉长，业务响应速度变慢。
- **技术债滚雪球式增长**：贫血模型下业务逻辑散落各处，团队为了"不出错"倾向于复制粘贴而非重构复用，代码重复度越来越高，可维护性持续恶化。
- **微服务拆分失败演变为分布式单体**：如果不基于领域边界拆分，服务之间会产生大量同步调用和数据强依赖，拆分后不仅没有获得独立部署的收益，反而增加了网络延迟、分布式事务、服务治理等新的复杂度，是"为了拆分而拆分"的反面案例。

---

## 二、整体架构设计

### 2.1 DDD 核心概念详解

领域驱动设计（Domain-Driven Design）由 Eric Evans 在其 2003 年的著作中系统化提出，核心思想是**让代码结构直接反映业务领域的概念和规则，用统一语言（Ubiquitous Language）消除业务专家与技术人员之间的沟通鸿沟**。

| 概念 | 定义 | 示例 |
|---|---|---|
| **领域（Domain）** | 一个组织所从事的业务范围以及其中的业务规则 | 电商平台的整体业务领域 |
| **子域（Subdomain）** | 领域内可以进一步划分的问题空间 | 交易子域、商品子域、物流子域、营销子域 |
| **核心域（Core Domain）** | 决定企业核心竞争力、最需要投入精力打磨的子域 | 电商平台的交易撮合、定价策略 |
| **通用域（Generic Subdomain）** | 各行业普遍存在、可以直接采购成熟方案或复用通用组件的子域 | 权限认证、消息通知 |
| **支撑域（Supporting Subdomain）** | 支撑核心业务但不构成竞争力的子域 | 数据字典管理、后台配置管理 |
| **限界上下文（Bounded Context）** | 一个明确的边界，边界内的领域模型和统一语言保持一致，边界外可能对同一概念有不同的理解 | "商品"在商品中心上下文中包含 SKU、库存属性；在营销上下文中则关注促销规则、优惠券适用范围 |
| **聚合（Aggregate）** | 一组具有强一致性边界的领域对象集合，对外只能通过聚合根访问 | "订单"聚合包含订单主体、订单明细行、优惠信息 |
| **聚合根（Aggregate Root）** | 聚合的唯一入口，负责维护聚合内部的一致性约束 | 订单（Order）是聚合根，订单明细（OrderItem）不能脱离订单单独被外部修改 |
| **实体（Entity）** | 具有唯一标识（ID），生命周期内状态可变，但身份不变 | 订单、用户 |
| **值对象（Value Object）** | 没有唯一标识，通过属性值来判断相等性，不可变 | 地址、金额、时间区间 |
| **领域事件（Domain Event）** | 领域内发生的、值得被记录和通知的业务事实 | 订单已创建、支付已完成 |
| **仓储（Repository）** | 为聚合提供类似集合的访问接口，屏蔽底层持久化细节 | OrderRepository |
| **工厂（Factory）** | 封装复杂对象/聚合的创建逻辑，确保创建出的对象满足业务不变式 | OrderFactory |
| **领域服务（Domain Service）** | 不属于任何单一实体或值对象、但属于领域层的业务逻辑，通常涉及多个聚合的协调 | 库存分配策略计算 |
| **应用服务（Application Service）** | 编排领域对象完成一个用例，不包含业务规则本身，只负责流程编排和事务边界控制 | 下单流程的编排 |

### 2.2 四层架构的演进

**传统三层架构：** `Controller → Service → DAO`

这种架构下，Service 层往往同时承担了"业务规则"和"流程编排"两种职责，随着业务复杂度上升，Service 类会变得越来越臃肿（"上帝类"反模式），业务规则和技术实现细节（如事务管理、数据库访问）紧密耦合在一起，难以复用和测试。

**DDD 四层架构：**

```
┌─────────────────────────────────────────────┐
│  接口层 (Interfaces / User Interface)         │  对外暴露的入口：REST Controller、RPC接口、消息消费者
├─────────────────────────────────────────────┤
│  应用层 (Application)                         │  编排领域对象完成具体用例，管理事务边界，不包含业务规则
├─────────────────────────────────────────────┤
│  领域层 (Domain)                              │  核心业务逻辑：实体、值对象、聚合根、领域服务、领域事件
├─────────────────────────────────────────────┤
│  基础层 (Infrastructure)                      │  技术实现细节：持久化、消息队列、缓存、第三方服务调用
└─────────────────────────────────────────────┘
```

**各层职责与代码示例：**

```java
// ============ 接口层：只做协议转换和参数校验，不包含业务逻辑 ============
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;

    public OrderController(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    @PostMapping
    public OrderResponse createOrder(@RequestBody @Valid CreateOrderRequest request) {
        // 接口层负责DTO与应用层入参的转换，不涉及任何业务规则判断
        CreateOrderCommand command = OrderRequestConverter.toCommand(request);
        OrderResult result = orderApplicationService.createOrder(command);
        return OrderResponseConverter.toResponse(result);
    }
}

// ============ 应用层：编排领域对象，控制事务边界，不包含业务规则本身 ============
@Service
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final OrderFactory orderFactory;
    private final InventoryDomainService inventoryDomainService; // 跨聚合协调的领域服务
    private final DomainEventPublisher eventPublisher;

    public OrderApplicationService(OrderRepository orderRepository, OrderFactory orderFactory,
                                    InventoryDomainService inventoryDomainService,
                                    DomainEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.orderFactory = orderFactory;
        this.inventoryDomainService = inventoryDomainService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public OrderResult createOrder(CreateOrderCommand command) {
        // 1. 调用领域服务完成跨聚合的库存校验（业务规则本身在领域服务/领域层内部）
        inventoryDomainService.checkAndReserve(command.getSkuId(), command.getQuantity());

        // 2. 通过工厂创建聚合根，确保创建出的对象一开始就满足业务不变式
        Order order = orderFactory.create(command);

        // 3. 调用聚合根自身的业务方法（业务规则封装在Order实体内部，不在应用层）
        order.confirm();

        // 4. 持久化聚合
        orderRepository.save(order);

        // 5. 发布领域事件，交给订阅方异步处理后续流程（如通知、积分、物流）
        eventPublisher.publish(new OrderCreatedEvent(order.getId(), order.getUserId(), order.getTotalAmount()));

        return OrderResult.from(order);
    }
}

// ============ 领域层：核心业务规则，与任何技术框架无关 ============
public class Order {
    private OrderId id;
    private UserId userId;
    private OrderStatus status;
    private final List<OrderItem> items = new ArrayList<>(); // 聚合内部实体，只能通过聚合根访问
    private Money totalAmount;

    // 聚合根内部的业务方法，封装了状态流转的规则，而非任由外部随意setStatus
    public void confirm() {
        if (this.status != OrderStatus.CREATED) {
            throw new IllegalOrderStateException("only CREATED order can be confirmed, current=" + status);
        }
        if (this.items.isEmpty()) {
            throw new IllegalOrderStateException("order must contain at least one item");
        }
        this.status = OrderStatus.CONFIRMED;
    }

    public void cancel(String reason) {
        if (this.status == OrderStatus.SHIPPED || this.status == OrderStatus.COMPLETED) {
            throw new IllegalOrderStateException("shipped or completed order cannot be cancelled");
        }
        this.status = OrderStatus.CANCELLED;
    }

    // 添加明细行时同步校验并重新计算总金额，保证聚合内部数据的一致性由聚合根统一维护
    public void addItem(OrderItem item) {
        this.items.add(item);
        this.totalAmount = this.totalAmount.add(item.getSubtotal());
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items); // 只读暴露，外部不能绕过聚合根直接修改明细
    }
}

// ============ 基础层：技术实现细节，实现领域层定义的接口 ============
@Repository
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderMapper orderMapper; // MyBatis Mapper
    private final OrderConverter orderConverter;

    public OrderRepositoryImpl(OrderMapper orderMapper, OrderConverter orderConverter) {
        this.orderMapper = orderMapper;
        this.orderConverter = orderConverter;
    }

    @Override
    public void save(Order order) {
        OrderPO po = orderConverter.toPO(order); // 领域对象转换为持久化对象
        orderMapper.upsert(po);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        OrderPO po = orderMapper.selectById(id.getValue());
        return Optional.ofNullable(po).map(orderConverter::toDomain);
    }
}
```

### 2.3 严格分层 vs 松散分层

- **严格分层架构（Strict Layered Architecture）**：每一层只能依赖其直接下方的一层，接口层不能跳过应用层直接访问领域层，应用层不能跳过领域层直接操作基础层。这种约束保证了架构的清晰度，但在一些简单场景下会显得繁琐（例如一个简单的查询操作也要经过应用层转发）。
- **松散分层架构（Relaxed Layered Architecture）**：允许上层依赖任意下方的层，例如接口层可以直接调用基础层的某些查询服务（典型场景是 CQRS 中的查询侧，直接绕过领域层从读库取数据，因为查询不涉及业务规则和一致性约束）。

**选择建议**：写操作（涉及业务规则校验、状态流转、一致性保证）应严格遵循分层，确保业务规则统一收敛在领域层；读操作（尤其是复杂的报表类查询、多表关联查询）可以采用松散分层甚至独立的查询模型（CQRS），直接从基础层的查询服务或专用的读模型中获取数据，避免为了"纯粹的分层"而强行把简单查询也包装成一堆没有实际业务价值的领域对象。

### 2.4 依赖倒置原则（DIP）与领域层的独立性

DDD 架构的核心工程手段是依赖倒置：**领域层不依赖基础层，而是领域层定义接口（如仓储接口），由基础层实现这些接口**，这样领域层可以保持对具体技术实现（用的是 MySQL 还是 MongoDB，用的是 RabbitMQ 还是 Kafka）的完全无感知，最大化业务逻辑的可测试性和可移植性。

```java
// 领域层定义仓储接口，只表达"领域需要什么能力"，不关心具体如何实现
package com.example.order.domain.repository;

public interface OrderRepository {
    void save(Order order);
    Optional<Order> findById(OrderId id);
    List<Order> findByUserId(UserId userId);
}

// 基础层实现该接口，依赖方向是 基础层 -> 领域层（依赖倒置），而非领域层依赖基础层
package com.example.order.infrastructure.persistence;

@Repository
public class OrderRepositoryImpl implements com.example.order.domain.repository.OrderRepository {
    // 具体实现依赖MyBatis/JPA等技术框架，这些细节对领域层完全不可见
}
```

依赖倒置带来的直接好处：**领域层可以在完全不启动 Spring 容器、不连接真实数据库的情况下进行纯粹的单元测试**，因为领域层的业务逻辑代码不依赖任何具体的基础设施实现，只依赖自己定义的抽象接口，测试时用内存实现或 Mock 替代即可。

---

## 三、核心链路设计

### 3.1 领域事件驱动设计：从产生到最终一致性

领域事件是连接聚合之间、限界上下文之间的核心手段，能够实现业务逻辑的解耦，同时保证跨聚合操作的最终一致性。

#### 3.1.1 事件发布流程

```java
// 第一步：领域事件的定义，是一个不可变的值对象，携带事件发生时必要的上下文信息
public class OrderCreatedEvent implements DomainEvent {
    private final String eventId;
    private final OrderId orderId;
    private final UserId userId;
    private final Money totalAmount;
    private final LocalDateTime occurredAt;

    public OrderCreatedEvent(OrderId orderId, UserId userId, Money totalAmount) {
        this.eventId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.occurredAt = LocalDateTime.now();
    }
    // getters...
}

// 第二步：聚合根在状态变化时收集领域事件，而不是立即发送（延迟到事务提交后统一发布）
public abstract class AggregateRoot {
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        this.pendingEvents.add(event);
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return events;
    }
}

public class Order extends AggregateRoot {
    public void confirm() {
        // ...业务规则校验...
        this.status = OrderStatus.CONFIRMED;
        registerEvent(new OrderCreatedEvent(this.id, this.userId, this.totalAmount)); // 只是收集，不立即发送
    }
}

// 第三步：应用层在事务提交成功后统一发布事件（本质是"事务性发件箱"模式的简化版）
@Service
public class OrderApplicationService {

    @Transactional
    public OrderResult createOrder(CreateOrderCommand command) {
        Order order = orderFactory.create(command);
        order.confirm();
        orderRepository.save(order); // 领域对象状态与事件在同一个本地事务中落库

        // 关键设计：将事件先保存到"事件表"中，与业务数据在同一个本地事务提交，保证原子性
        List<DomainEvent> events = order.pullEvents();
        eventStore.saveAll(events);

        return OrderResult.from(order);
    }
}

// 第四步：独立的事件转发器异步轮询事件表，将未发布的事件投递到消息队列，实现最终一致性
@Component
public class EventDispatcher {

    @Scheduled(fixedDelay = 500)
    public void dispatchPendingEvents() {
        List<DomainEvent> pendingEvents = eventStore.findUnpublished(100);
        for (DomainEvent event : pendingEvents) {
            try {
                messageProducer.send(buildTopic(event), event);
                eventStore.markPublished(event.getEventId()); // 发送成功后标记，避免重复投递
            } catch (Exception e) {
                log.error("failed to dispatch event: {}", event.getEventId(), e);
                // 保留未发布状态，下次调度继续重试，配合失败次数上限和告警
            }
        }
    }
}
```

**这个流程解决的核心问题**：如果直接在业务方法内部同步调用消息队列 SDK 发送事件，会面临"业务数据落库成功但消息发送失败"或者"消息发送成功但业务事务回滚"这两种数据不一致的场景（本地事务与消息发送天然是两个无法原子绑定的操作）。通过"事件与业务数据在同一本地事务中落库 → 独立异步任务保证投递"的模式（业界称为事务性发件箱 Transactional Outbox Pattern），把"跨系统的分布式一致性问题"转化为"本地数据库事务的原子性问题 + 消息投递的可靠重试机制"，从而实现最终一致性。

#### 3.1.2 领域事件的订阅与处理

```java
// 库存上下文订阅订单创建事件，完成自己领域内的业务逻辑，两个上下文之间通过事件解耦，互不感知对方内部实现
@Component
public class InventoryEventHandler {

    @KafkaListener(topics = "order-created-topic", groupId = "inventory-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        // 幂等性保证：同一事件可能因为消息重试被重复消费，需要基于eventId做幂等判断
        if (processedEventRepository.exists(event.getEventId())) {
            return;
        }
        inventoryDomainService.deductStock(event.getOrderId(), event.getItems());
        processedEventRepository.markProcessed(event.getEventId());
    }
}
```

事件驱动架构下，一个业务动作（如"创建订单"）触发的下游影响（扣减库存、发送通知、增加积分、更新报表）都通过独立的事件订阅者异步完成，各限界上下文之间不再有直接的同步调用依赖，任何一个下游处理逻辑的故障都不会影响订单创建这个核心链路的成功率，这是 DDD 架构下实现"高内聚低耦合"的关键实践。

### 3.2 微服务拆分策略详解

#### 3.2.1 拆分前的架构问题诊断

拆分之前，首先要诊断单体架构的具体问题所在：

```
单体应用典型结构（拆分前）：
┌───────────────────────────────────────────┐
│              Single Deployment Unit         │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│  │ UserCtrl │ │OrderCtrl │ │ProductCtrl │  │
│  └──────────┘ └──────────┘ └────────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│  │UserService│ │OrderService│ │ProductService│  <- 所有Service互相调用，边界模糊
│  └──────────┘ └──────────┘ └────────────┘  │
│  ┌───────────────────────────────────────┐ │
│  │         单一数据库（所有表混杂）          │ │
│  └───────────────────────────────────────┘ │
└───────────────────────────────────────────┘
```

#### 3.2.2 拆分策略一：按业务能力拆分（Business Capability）

将系统按照"对外提供的业务能力"划分，每个服务对应一个独立的业务功能闭环。例如电商系统按业务能力可拆分为：用户服务（注册登录、个人信息管理）、商品服务（商品信息、类目管理）、库存服务（库存扣减、库存查询）、订单服务（下单、订单查询）、支付服务（支付渠道对接、支付状态管理）。

#### 3.2.3 拆分策略二：按子域拆分（Subdomain-Driven）

这是更贴近 DDD 方法论的拆分方式，先通过事件风暴（Event Storming）等协作建模方法梳理出领域内的所有限界上下文，再评估每个上下文的战略地位（核心域/支撑域/通用域），核心域应该获得最多的建模精力和最优秀的工程资源投入，通用域则优先考虑复用现成方案而非自研。

```
事件风暴梳理出的限界上下文示例（电商场景）：
- 交易上下文（核心域）：下单、支付、订单状态流转 —— 值得投入最多精力精细化建模
- 商品上下文（核心域）：商品信息、类目、SKU、库存
- 营销上下文（核心域）：优惠券、促销规则、满减计算
- 物流上下文（支撑域）：发货、物流轨迹跟踪
- 用户中心上下文（通用域）：注册登录、身份认证 —— 可考虑采购成熟的身份认证方案
- 消息通知上下文（通用域）：短信、站内信、推送 —— 通用能力，多业务线可共享
```

#### 3.2.4 拆分策略三：按团队边界拆分（Conway's Law 视角）

康威定律指出："设计系统的组织，其产生的设计等价于组织的沟通结构。"如果拆分出的服务边界与团队组织边界不匹配（例如一个服务需要三个不同团队共同维护），会导致跨团队协调成本高企，服务名义上独立但实际上发布节奏仍然被互相绑定。理想情况下，每个微服务应该由一个"两个披萨"规模的团队（通常 5~9 人）独立负责需求设计、开发、测试、部署、运维的全生命周期。

#### 3.2.5 拆分前后架构对比

| 维度 | 拆分前（单体） | 拆分后（微服务） |
|---|---|---|
| 部署单元 | 一个整体，任何修改都要整体重新部署 | 每个服务独立部署，互不影响 |
| 数据库 | 共享一个数据库实例，表之间可以任意 JOIN | 每个服务拥有独立的数据库（Database per Service），跨服务查询必须通过接口调用或事件同步的方式 |
| 团队协作 | 所有团队修改同一代码库，合并冲突频繁 | 每个团队独立维护自己的代码库，通过 API 契约协作 |
| 技术栈 | 统一技术栈 | 可根据服务特点选择不同技术栈（如报表服务用列式存储，交易服务用行式关系型数据库） |
| 扩容粒度 | 整体扩容，资源利用率低 | 按服务独立扩容，热点服务可单独增加资源 |
| 故障隔离 | 一个模块的资源耗尽可能拖垮整个应用 | 单个服务故障通过熔断降级隔离，不会直接波及全局（但需要额外的容错设计） |
| 复杂度来源 | 复杂度集中在代码内部（模块耦合） | 复杂度转移到服务间通信、分布式事务、服务治理等基础设施层面 |

### 3.3 单体到微服务的渐进式演进路径

#### 3.3.1 绞杀者模式（Strangler Fig Pattern）

借鉴绞杀榕（一种缠绕寄主树生长、最终替代寄主的植物）的生长方式：在旧系统外部搭建一层门面（Facade/网关），新功能直接在新服务中实现，旧功能逐步从单体中抽取并迁移到新服务，通过门面层动态路由决定一个请求应该由旧系统还是新服务处理，直到旧系统的所有功能都被替换完毕，最后安全地下线旧系统。

```java
// 绞杀者模式中的路由门面示例：根据功能开关判断请求应该转发给旧系统还是新拆分出的服务
@RestController
public class StranglerFacadeController {

    private final LegacyOrderClient legacyOrderClient; // 调用旧单体系统
    private final OrderServiceClient newOrderServiceClient; // 调用新拆分出的订单微服务
    private final FeatureToggleService featureToggleService;

    @GetMapping("/orders/{id}")
    public OrderResponse getOrder(@PathVariable String id) {
        // 通过灰度开关控制流量比例，逐步将流量从旧系统切换到新服务，可随时回滚
        if (featureToggleService.isEnabled("order-service-migration", id)) {
            return newOrderServiceClient.getOrder(id);
        }
        return legacyOrderClient.getOrder(id);
    }
}
```

绞杀者模式的核心优势是**风险可控、可随时回滚**：每次只迁移一小块功能，通过灰度流量验证新服务的正确性和稳定性后再逐步扩大迁移范围，避免"推倒重来"式重构的高风险（大爆炸式重写往往伴随需求理解偏差、新老系统数据不一致等严重问题，业界有大量因为重写周期过长导致项目失败的教训）。

#### 3.3.2 分支模式（Branch by Abstraction）

在代码层面，先引入一层抽象接口，让新旧两套实现可以在同一个代码库中并存并通过开关切换，等新实现经过充分验证后再删除旧实现，避免了长期维护一个独立的迁移专用分支（避免分支长期存在导致的困难合并）。

```java
// 抽象接口层，屏蔽新旧实现的差异
public interface InventoryDeductionStrategy {
    void deduct(String skuId, int quantity);
}

// 旧实现：直接操作单体数据库
public class LegacyInventoryDeductionStrategy implements InventoryDeductionStrategy {
    @Override
    public void deduct(String skuId, int quantity) {
        legacyInventoryDao.updateStock(skuId, quantity);
    }
}

// 新实现：调用拆分后的独立库存服务
public class NewInventoryDeductionStrategy implements InventoryDeductionStrategy {
    @Override
    public void deduct(String skuId, int quantity) {
        inventoryServiceClient.deductStock(skuId, quantity);
    }
}

// 通过配置中心动态切换策略实现，两套实现代码同时存在于代码库中，通过开关灰度切换
@Configuration
public class InventoryStrategyConfig {
    @Bean
    public InventoryDeductionStrategy inventoryDeductionStrategy(FeatureToggleService toggleService) {
        return toggleService.isEnabled("new-inventory-service")
                ? new NewInventoryDeductionStrategy()
                : new LegacyInventoryDeductionStrategy();
    }
}
```

### 3.4 工程结构标准化：Maven 多模块设计

一个遵循 DDD 分层理念的标准工程结构，通常按照 Maven 多模块（每个模块对应一层，模块之间的依赖关系严格遵循分层约束）组织：

```
order-service/                          # 聚合父工程
├── order-api/                          # API模块：定义对外的RPC/HTTP接口契约、DTO，供其他服务依赖
│   └── src/main/java/.../api/
│       ├── OrderFacade.java            # 接口定义
│       └── dto/CreateOrderRequestDTO.java
├── order-application/                  # 应用层：用例编排，依赖domain，不依赖infrastructure的具体实现
│   └── src/main/java/.../application/
│       ├── OrderApplicationService.java
│       └── command/CreateOrderCommand.java
├── order-domain/                       # 领域层：核心业务模型，不依赖任何其他模块（最内核、最稳定）
│   └── src/main/java/.../domain/
│       ├── model/Order.java
│       ├── model/OrderItem.java
│       ├── repository/OrderRepository.java   # 只定义接口
│       └── service/InventoryDomainService.java
├── order-infrastructure/               # 基础层：实现domain定义的接口，依赖具体技术框架
│   └── src/main/java/.../infrastructure/
│       ├── persistence/OrderRepositoryImpl.java
│       ├── persistence/OrderPO.java
│       ├── mq/OrderEventProducer.java
│       └── rpc/InventoryServiceClient.java
├── order-starter/                      # 启动模块：Spring Boot主类、配置文件、接口层Controller
│   └── src/main/java/.../starter/
│       ├── OrderServiceApplication.java
│       └── controller/OrderController.java
└── pom.xml                             # 父POM，统一管理各模块依赖版本
```

**模块间依赖方向的约束（严格遵循依赖倒置）**：

```
order-starter  ──依赖──>  order-application, order-infrastructure, order-api
order-infrastructure  ──依赖──>  order-domain（实现domain定义的接口）
order-application  ──依赖──>  order-domain
order-domain  ──依赖──>  （无任何依赖，是最内核、最稳定的模块，甚至不依赖Spring框架）
```

这种模块划分通过 Maven 的模块依赖关系在**编译期**就强制了架构约束——如果有人试图在 `order-domain` 模块中引入 MyBatis 或 Spring Data JPA 的依赖，编译时就会因为模块依赖关系被破坏而失败（前提是 `order-domain` 的 `pom.xml` 中根本没有声明这些技术框架的依赖），从而在工程层面而非仅靠人工 Code Review 保证了领域层的独立性和纯粹性。

### 3.5 对象模型：PO/DO/DTO/VO 的转换链路

DDD 架构下，同一个业务概念在不同层次会有不同的对象表示，职责边界清晰是避免"贫血模型"和"层次穿透"的关键：

| 对象类型 | 全称 | 所属层 | 职责 |
|---|---|---|---|
| **PO** | Persistent Object | 基础层 | 与数据库表结构一一映射，仅用于持久化，不包含业务逻辑 |
| **DO** | Domain Object | 领域层 | 即前文的实体/聚合根，包含业务规则和状态流转逻辑 |
| **DTO** | Data Transfer Object | 应用层/接口层 | 用于跨进程/跨服务边界传输数据，只是数据容器，无业务逻辑 |
| **VO** | View Object / Value Object | 接口层 或 领域层 | 接口层的VO是面向前端展示需求组装的对象；领域层的VO（值对象）是DDD概念中不可变、无唯一标识的对象 |

```java
// PO：与数据库表结构一一对应，字段类型贴近数据库列类型（如金额用long存分为单位，避免精度问题）
public class OrderPO {
    private Long id;
    private Long userId;
    private Integer status;
    private Long totalAmountCents; // 数据库中以分为单位存储，避免浮点数精度问题
    private Date createTime;
    private Date updateTime;
    // getters/setters，无业务逻辑
}

// DO（领域实体/聚合根）：包含业务规则，使用领域语言的类型（如Money值对象而非裸的long）
public class Order {
    private OrderId id;
    private UserId userId;
    private OrderStatus status; // 使用枚举而非裸int，类型安全
    private Money totalAmount;  // 值对象封装金额及其运算规则

    public void confirm() { /* 业务规则 */ }
}

// DTO：用于服务间/前后端传输，字段可能是多个领域对象组合而成，服务于特定的用例场景
public class OrderResultDTO {
    private String orderId;
    private String statusDesc; // 直接是可读的状态描述，而非原始状态码，方便前端展示
    private BigDecimal totalAmount;
    private List<OrderItemDTO> items;
}

// 转换器：负责各层对象之间的显式转换，避免层次之间直接互相依赖对方的对象类型
public class OrderConverter {

    public Order toDomain(OrderPO po) {
        Order order = new Order();
        order.setId(new OrderId(po.getId()));
        order.setUserId(new UserId(po.getUserId()));
        order.setStatus(OrderStatus.fromCode(po.getStatus()));
        order.setTotalAmount(Money.ofCents(po.getTotalAmountCents()));
        return order;
    }

    public OrderPO toPO(Order order) {
        OrderPO po = new OrderPO();
        po.setId(order.getId() == null ? null : order.getId().getValue());
        po.setUserId(order.getUserId().getValue());
        po.setStatus(order.getStatus().getCode());
        po.setTotalAmountCents(order.getTotalAmount().toCents());
        return po;
    }

    public OrderResultDTO toDTO(Order order) {
        OrderResultDTO dto = new OrderResultDTO();
        dto.setOrderId(order.getId().getValue().toString());
        dto.setStatusDesc(order.getStatus().getDesc());
        dto.setTotalAmount(order.getTotalAmount().toBigDecimal());
        return dto;
    }
}
```

**为什么要坚持多层对象转换而不是"一个对象打天下"**：如果直接把 PO 当作 DO 使用（很多贫血模型的项目就是这样做的），会导致持久化框架的注解（如 `@TableName`、`@Column`）侵入到本应保持纯粹的领域层，且数据库字段的变化会直接影响业务逻辑代码，两者的变化原因和节奏完全不同（数据库表结构的调整通常出于性能或存储优化考虑，业务模型的调整则出于业务规则变化），耦合在一起会导致任何一方的修改都需要谨慎评估对另一方的影响，这正是分层架构试图避免的问题。

### 3.6 限界上下文之间的集成模式

限界上下文划定之后，还需要设计上下文之间如何协作。DDD 中常见的上下文映射（Context Mapping）模式包括：

- **共享内核（Shared Kernel）**：两个上下文共享一部分模型代码（如公共的值对象库），适用于关系紧密、由同一团队或紧密协作团队维护的上下文，共享部分的任何修改都需要双方协商。
- **客户方-供应方（Customer-Supplier）**：下游上下文（客户方）依赖上游上下文（供应方）提供的能力，供应方在设计接口时需要考虑客户方的诉求。
- **遵奉者（Conformist）**：下游完全接受上游的模型设计，不做任何适配转换，适用于上游是强势方（如行业标准接口）且下游没有议价能力的场景。
- **防腐层（Anti-Corruption Layer, ACL）**：当下游需要对接一个模型设计与自身领域概念不一致的上游（尤其是遗留系统或外部第三方系统）时，在边界处引入一个转换层，将上游的模型转换为下游领域内部认可的模型，避免外部糟糕的模型设计"腐蚀"自身清晰的领域模型。

```java
// 防腐层示例：对接遗留的老订单系统，将其混乱的数据结构转换为当前领域清晰的模型
public class LegacyOrderAntiCorruptionLayer {

    private final LegacyOrderSystemClient legacyClient; // 遗留系统的原始接口，字段命名和结构非常混乱

    public Order translateToOrder(String legacyOrderId) {
        LegacyOrderDTO legacyDTO = legacyClient.queryOrder(legacyOrderId);
        // 在这一层完成"翻译"工作：字段映射、状态码转换、数据清洗，隔离遗留系统的糟糕设计
        Order order = new Order();
        order.setId(new OrderId(Long.parseLong(legacyDTO.getOrderNoStr())));
        order.setStatus(translateLegacyStatus(legacyDTO.getStatusFlag()));
        order.setTotalAmount(Money.ofYuan(legacyDTO.getAmtStr())); // 遗留系统金额是字符串，需要清洗转换
        return order;
    }

    private OrderStatus translateLegacyStatus(int legacyFlag) {
        // 遗留系统的状态码定义混乱（0/1/2/9等无规律编码），在防腐层集中完成语义翻译
        switch (legacyFlag) {
            case 0: return OrderStatus.CREATED;
            case 1: return OrderStatus.CONFIRMED;
            case 9: return OrderStatus.CANCELLED;
            default: throw new IllegalStateException("unknown legacy status: " + legacyFlag);
        }
    }
}
```

防腐层的价值在于：即使上游系统的模型设计再糟糕、字段语义再混乱，这种"污染"也只会局限在防腐层内部，不会扩散到下游清晰的领域模型中，为未来彻底替换遗留系统预留了清晰的边界。

### 3.7 事件风暴（Event Storming）建模实践

在正式编码之前，团队通常通过事件风暴工作坊（业务专家、产品、架构师、开发共同参与的协作建模会议）梳理领域边界，其基本步骤为：

1. **梳理领域事件**：参与者用橙色便利贴写下业务流程中所有值得关注的领域事件（用被动语态动词描述，如"订单已创建""库存已扣减""支付已完成"），按时间顺序在一条时间线上排列。
2. **识别触发命令**：为每个领域事件补充触发它的命令（蓝色便利贴，如"创建订单""扣减库存"），以及触发命令的角色（黄色便利贴，代表用户或外部系统）。
3. **识别聚合**：观察哪些命令和事件是围绕同一个业务实体展开的，将它们归类到同一个聚合，聚合内部保证强一致性。
4. **划定限界上下文**：观察聚合之间的自然聚集和断裂点，通常在"事件密度骤降"或"团队职责边界"处画出限界上下文的边界线。
5. **识别外部系统与集成点**：标记出与外部系统（第三方支付、物流公司接口）交互的边界，为后续的防腐层设计做准备。

这种协作式建模方法的核心价值在于，它强迫技术人员和业务专家使用同一套语言、在同一个空间中共同梳理业务全貌，避免了传统"业务写需求文档、技术自行理解拆分模块"模式下容易出现的理解偏差，是发现隐藏业务规则和边界的高效手段。

### 3.8 微服务间通信方式的选择

限界上下文之间划定后，还需要在同步调用与异步消息之间做出恰当选择：

| 通信方式 | 适用场景 | 优势 | 劣势 |
|---|---|---|---|
| 同步 RPC/HTTP 调用 | 调用方需要立即获得结果才能继续后续业务逻辑（如下单时需要实时校验库存是否充足） | 逻辑直观，时序确定，易于调试 | 引入服务间的强依赖，下游故障会直接影响上游可用性，调用链变长会累积延迟 |
| 异步消息/事件驱动 | 下游处理结果不影响当前主流程的成功与否（如下单后异步累积积分、异步发送通知） | 服务解耦，下游故障不影响上游主流程，可通过消息队列削峰填谷 | 数据一致性从"实时强一致"变为"最终一致"，需要额外处理消息丢失、重复、乱序等问题 |

一个成熟的微服务系统中，两种通信方式通常并存：核心交易链路上，对结果强依赖的调用采用同步方式并配合完善的容错设计；非核心链路、副作用性质的处理采用事件驱动的异步方式，这也是 DDD 中"核心域投入更多精力做同步强一致设计、支撑域/通用域可以适当放宽一致性要求"这一理念在通信层面的具体体现。

---

## 四、异常处理与容错机制

### 4.1 领域层的业务异常设计

领域层应该定义自己的业务异常体系，明确表达业务规则被违反的具体原因，而不是笼统抛出通用异常：

```java
// 领域层异常基类，与技术异常（如SQLException）完全隔离
public abstract class DomainException extends RuntimeException {
    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}

public class IllegalOrderStateException extends DomainException {
    public IllegalOrderStateException(String message) {
        super("ORDER_ILLEGAL_STATE", message);
    }
}

public class InsufficientStockException extends DomainException {
    public InsufficientStockException(String skuId, int required, int available) {
        super("INSUFFICIENT_STOCK",
                String.format("sku=%s, required=%d, available=%d", skuId, required, available));
    }
}
```

应用层统一捕获领域异常并转换为对外的标准错误响应，接口层不应直接暴露领域异常的内部细节：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException e) {
        log.warn("domain exception occurred: {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }
}
```

### 4.2 跨服务调用的容错

微服务拆分后，原本在单体内的方法调用变成了网络调用，必须考虑网络分区、服务不可用等新增的失败模式：

- **超时与重试**：跨服务调用必须设置合理超时时间，配合有限次数的重试（幂等操作才可重试），避免无限期等待拖垮调用方线程池。
- **熔断降级**：当下游服务持续失败时，通过熔断器（Circuit Breaker）快速失败，避免故障扩散和调用方资源耗尽，并提供合理的降级逻辑（如返回缓存数据或默认值）。
- **补偿事务**：跨聚合、跨服务的操作无法使用本地数据库事务保证一致性，需要通过 Saga 模式（每个步骤都定义对应的补偿操作）或事务性发件箱+事件驱动的最终一致性方案来处理。

```java
// Saga模式示例：跨服务的下单流程，每一步失败都触发前序步骤的补偿操作
public class CreateOrderSaga {

    public void execute(CreateOrderCommand command) {
        String reservationId = null;
        try {
            reservationId = inventoryServiceClient.reserve(command.getSkuId(), command.getQuantity());
            String paymentId = paymentServiceClient.charge(command.getUserId(), command.getAmount());
            orderRepository.save(buildOrder(command, reservationId, paymentId));
        } catch (PaymentFailedException e) {
            // 支付失败，补偿：释放已预留的库存
            if (reservationId != null) {
                inventoryServiceClient.releaseReservation(reservationId);
            }
            throw new OrderCreationFailedException("payment failed, order rolled back", e);
        }
    }
}
```

### 4.3 领域事件的幂等与顺序保证

- **幂等性**：事件消费方必须基于事件的唯一标识（`eventId`）做幂等判断，因为消息队列的"至少一次投递"语义下，同一事件可能被重复消费。
- **顺序性**：如果业务对同一聚合的事件处理顺序有强依赖（如"订单创建"必须先于"订单支付"被处理），需要保证同一聚合 ID 的事件路由到消息队列的同一分区，由消费者按分区内顺序处理。

### 4.4 数据一致性的分级策略

不是所有跨聚合的数据同步都需要追求强一致性，应该根据业务重要程度分级处理：

| 一致性要求 | 适用场景 | 实现方式 |
|---|---|---|
| 强一致性 | 同一聚合内部的状态变更（如订单状态与订单明细） | 本地数据库事务 |
| 实时最终一致性 | 跨聚合但业务上要求秒级同步的场景（如下单后立即扣库存） | 同步RPC调用 + 失败补偿 |
| 延迟最终一致性 | 对实时性要求不高的下游影响（如下单后累积积分、更新统计报表） | 领域事件异步驱动 |

---

## 五、性能优化

### 5.1 聚合设计对性能的影响

- **聚合边界过大**：如果把过多的实体塞进同一个聚合（如把订单的所有历史操作日志都作为聚合内部实体），会导致每次加载聚合根都要加载大量不必要的关联数据，造成性能浪费。聚合应该只包含维护业务不变式所必需的最小数据集合，非核心的关联信息可以通过聚合 ID 引用而非直接嵌入对象引用。
- **聚合边界过小**：如果拆分过细，一个业务操作需要跨多个聚合甚至多次数据库往返才能完成，会增加不必要的分布式协调复杂度。合理的聚合设计原则是"一次业务操作理想情况下只修改一个聚合"，跨聚合的影响通过领域事件异步传播。

### 5.2 读写分离与 CQRS

对于查询场景复杂、涉及多表关联、报表统计等场景，严格遵循领域模型往往会导致查询性能低下（领域模型是为了业务规则表达和一致性保证而设计的，并非为查询效率设计）。CQRS（Command Query Responsibility Segregation）模式将写操作（走领域模型，保证业务规则）和读操作（走专门优化的查询模型，可以是宽表、物化视图甚至独立的搜索引擎）分离：

```java
// 命令侧：严格走领域模型，保证业务规则和一致性
@Service
public class OrderCommandService {
    public void createOrder(CreateOrderCommand command) {
        Order order = orderFactory.create(command);
        order.confirm();
        orderRepository.save(order);
    }
}

// 查询侧：直接查询专门为展示优化的宽表，不经过领域模型，性能更优
@Service
public class OrderQueryService {
    private final OrderQueryMapper orderQueryMapper; // 直接查询预先关联好的宽表视图

    public OrderDetailView getOrderDetail(String orderId) {
        return orderQueryMapper.selectOrderDetailView(orderId); // 一次SQL查询返回所有展示所需字段，无需领域对象组装
    }
}
```

写侧的领域事件（如订单创建、订单状态变更）异步更新查询侧的宽表，两侧数据保持最终一致，读写各自独立优化，互不牵制。

### 5.3 服务粒度与调用链深度

微服务拆分越细，一次业务请求需要经过的网络调用跳数越多，每一跳都会引入额外的网络延迟和失败概率。应该避免"为了拆分而拆分"导致调用链路过长，可以通过以下手段优化：

- **合并高频协作的细粒度服务**：如果两个服务几乎每次业务请求都需要同步互相调用，且没有独立扩容和独立发布的实际需求，考虑合并降低调用链深度。
- **数据冗余换取查询性能**：适当在服务内部冗余一份来自其他服务的必要数据（通过领域事件同步更新），避免查询时的同步跨服务调用。
- **批量接口设计**：避免 N+1 调用问题，对外提供批量查询接口，减少网络往返次数。

---

## 六、最佳实践与总结

### 6.1 设计原则总结

1. **统一语言贯穿始终**：业务专家和技术人员使用同一套术语描述领域概念，代码中的类名、方法名应直接体现业务语言，而不是技术人员自造的抽象词汇。
2. **聚合是一致性边界，而非简单的数据分组**：设计聚合时要问"这些数据是否需要在同一个事务中保持强一致"，而不是简单按照表关系或对象引用关系分组。
3. **领域层保持技术无关性**：领域层的代码不应该出现任何具体框架的痕迹，这是保证业务逻辑可测试、可移植的核心手段。
4. **拆分要基于业务边界而非技术层次**：按照"用户""订单""商品"这样的业务能力拆分，而不是按照"Controller 层服务""Service 层服务"这种技术层次拆分。
5. **演进优于一次性重写**：绞杀者模式等渐进式演进手段能大幅降低架构改造的风险，"大爆炸式"重写在实践中失败率很高。

### 6.2 常见陷阱

- **贫血模型的 DDD**：只是把 Service 层的方法搬到了叫作"领域服务"的类里，实体依然只有 getter/setter 没有任何业务方法，这不是真正的 DDD，只是换了个包名的传统三层架构。
- **过度设计的聚合关系**：为了"看起来符合 DDD"而生硬引入聚合、值对象等概念，即使业务场景本身非常简单（如一个纯粹的字典配置管理），过度建模反而增加了不必要的复杂度。
- **分布式单体**：拆分了服务，但服务之间大量同步调用、共享数据库、发布互相依赖，没有获得微服务的任何实际收益，却背上了分布式系统的全部复杂度。
- **忽视领域事件的可靠性**：直接在业务代码中同步调用消息队列 SDK 发送事件而不做事务性保证，一旦消息发送失败会导致状态不一致且难以排查。
- **仓储接口设计泄漏了持久化细节**：如果仓储接口中出现了 SQL 相关的参数或返回类型（如返回 `ResultSet` 或者暴露分页查询的具体 SQL 方言细节），说明基础层的实现细节已经泄漏到了领域层，破坏了依赖倒置的初衷。

### 6.3 演进方向

- **事件驱动架构的进一步深化**：随着业务复杂度提升，越来越多的跨限界上下文协作会转向事件驱动而非同步调用，配合事件溯源（Event Sourcing）可以获得完整的业务操作审计轨迹。
- **领域建模工具与协作方法的成熟**：事件风暴（Event Storming）等协作建模工作坊已经成为团队梳理复杂业务领域、对齐统一语言的标准实践，未来会有更多轻量化工具支撑这类协作过程。
- **微服务粒度的持续调整**：微服务拆分不是一次性决策，应该随着业务发展和团队规模变化持续评估和调整边界，必要时进行服务的合并或进一步拆分（这也是为什么强调依赖倒置和清晰的接口契约——边界调整时改动能被约束在可控范围内）。
- **平台化能力下沉**：随着微服务数量增长，服务治理、可观测性、配置管理等横切关注点会逐步下沉到独立的平台层，业务团队更专注于领域建模本身而非重复造基础设施的轮子。

DDD 与微服务架构的结合，本质上是在解决同一个根本问题：**如何让系统的技术结构与业务复杂度相匹配，并保持这种匹配关系能够随着业务演进而持续调整**。DDD 提供了梳理业务边界和建模业务规则的方法论，微服务提供了在工程和组织层面落地这些边界的技术手段，两者相辅相成，缺一不可——没有 DDD 指导的微服务拆分容易沦为技术驱动的盲目拆分，没有微服务承载的 DDD 建模成果也难以在组织协作层面发挥应有的价值。

---

## 七、全链路实战案例

前文已经从理论和局部代码片段的角度讲解了 DDD 与微服务的核心概念、拆分策略与容错机制。本章选取三个高频出现的实战场景，给出**从头到尾完整贯通**的代码链路（含异常处理、日志埋点、幂等控制），便于直接对照落地到实际工程中。

### 7.1 案例一：DDD 领域事件驱动的微服务通信全链路

**业务场景**：交易上下文中的"订单服务"在订单创建成功后，需要通知库存上下文完成扣减库存、通知积分上下文完成积分发放。两个下游动作都不应该阻塞下单主流程，也不能因为下游服务抖动而导致下单失败，因此采用领域事件 + 消息队列的异步解耦方案。完整链路为：**领域事件产生 → 应用层事务性落库 → 事件转发器投递 MQ → 消费端反序列化 → 幂等校验 → 事件处理**。

#### 7.1.1 领域事件定义（领域层）

```java
package com.example.order.domain.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 领域事件顶层接口，所有领域事件必须携带唯一事件ID用于下游幂等判断
 */
public interface DomainEvent extends Serializable {
    String getEventId();
    String getAggregateId();
    LocalDateTime getOccurredAt();
    /** 事件类型，供事件表存储和MQ路由使用，避免直接依赖Class做序列化 */
    String getEventType();
}

/**
 * 订单已创建事件：不可变值对象，只携带下游处理所必需的最小信息集合，
 * 避免把整个聚合根直接序列化传输（防止上下文之间通过事件产生模型耦合）
 */
public final class OrderCreatedEvent implements DomainEvent {

    public static final String EVENT_TYPE = "ORDER_CREATED";

    private final String eventId;
    private final String orderId;
    private final String userId;
    private final long totalAmountCents;
    private final java.util.List<OrderItemSnapshot> items;
    private final LocalDateTime occurredAt;

    public OrderCreatedEvent(String orderId, String userId, long totalAmountCents,
                              java.util.List<OrderItemSnapshot> items) {
        this.eventId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.userId = userId;
        this.totalAmountCents = totalAmountCents;
        this.items = items;
        this.occurredAt = LocalDateTime.now();
    }

    @Override
    public String getEventId() { return eventId; }
    @Override
    public String getAggregateId() { return orderId; }
    @Override
    public LocalDateTime getOccurredAt() { return occurredAt; }
    @Override
    public String getEventType() { return EVENT_TYPE; }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public long getTotalAmountCents() { return totalAmountCents; }
    public java.util.List<OrderItemSnapshot> getItems() { return items; }

    /** 事件内嵌的明细快照，是值对象，只携带库存扣减所必需的字段 */
    public static final class OrderItemSnapshot implements Serializable {
        private final String skuId;
        private final int quantity;

        public OrderItemSnapshot(String skuId, int quantity) {
            this.skuId = skuId;
            this.quantity = quantity;
        }
        public String getSkuId() { return skuId; }
        public int getQuantity() { return quantity; }
    }
}
```

#### 7.1.2 聚合根收集事件、应用层事务性落库（Outbox 模式）

```java
package com.example.order.domain.model;

import com.example.order.domain.event.DomainEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 聚合根基类：只负责收集事件，绝不在领域层内部直接发送MQ消息 */
public abstract class AggregateRoot {
    private final transient List<DomainEvent> pendingEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        this.pendingEvents.add(event);
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        return Collections.unmodifiableList(events);
    }
}
```

```java
package com.example.order.infrastructure.outbox;

import java.time.LocalDateTime;

/** 事件表持久化对象：与业务数据同库同事务，保证"业务落库"与"事件落库"的原子性 */
public class EventOutboxPO {
    private Long id;
    private String eventId;
    private String eventType;
    private String aggregateId;
    private String payload;       // 事件序列化后的JSON
    private Integer status;       // 0-待发布 1-已发布 2-发布失败超限
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime publishTime;
    // getters/setters省略
}
```

```java
package com.example.order.infrastructure.outbox;

import com.example.order.domain.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class EventOutboxStore {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxStore.class);

    private final EventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;

    public EventOutboxStore(EventOutboxMapper eventOutboxMapper, ObjectMapper objectMapper) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.objectMapper = objectMapper;
    }

    /** 必须运行在调用方已经开启的同一个事务中，与业务数据的insert/update共享事务边界 */
    public void saveAll(List<DomainEvent> events) {
        for (DomainEvent event : events) {
            try {
                EventOutboxPO po = new EventOutboxPO();
                po.setEventId(event.getEventId());
                po.setEventType(event.getEventType());
                po.setAggregateId(event.getAggregateId());
                po.setPayload(objectMapper.writeValueAsString(event));
                po.setStatus(0);
                po.setRetryCount(0);
                po.setCreateTime(LocalDateTime.now());
                eventOutboxMapper.insert(po);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // 序列化失败属于代码/数据缺陷而非瞬时故障，直接抛出使外层事务整体回滚，
                // 避免出现"业务数据落库成功但事件永久丢失"的情况
                log.error("failed to serialize domain event, eventId={}, eventType={}",
                        event.getEventId(), event.getEventType(), e);
                throw new EventPersistException("serialize domain event failed: " + event.getEventId(), e);
            }
        }
    }
}
```

```java
package com.example.order.infrastructure.outbox;

/** 事件持久化异常：属于基础层技术异常，与领域业务异常区分开 */
public class EventPersistException extends RuntimeException {
    public EventPersistException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.example.order.application;

import com.example.order.domain.event.DomainEvent;
import com.example.order.domain.event.OrderCreatedEvent;
import com.example.order.domain.model.Order;
import com.example.order.domain.repository.OrderRepository;
import com.example.order.infrastructure.outbox.EventOutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private final OrderRepository orderRepository;
    private final OrderFactory orderFactory;
    private final EventOutboxStore eventOutboxStore;

    public OrderApplicationService(OrderRepository orderRepository, OrderFactory orderFactory,
                                    EventOutboxStore eventOutboxStore) {
        this.orderRepository = orderRepository;
        this.orderFactory = orderFactory;
        this.eventOutboxStore = eventOutboxStore;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderResult createOrder(CreateOrderCommand command) {
        log.info("start creating order, userId={}, skuId={}, quantity={}",
                command.getUserId(), command.getSkuId(), command.getQuantity());
        try {
            Order order = orderFactory.create(command);
            order.confirm(); // 业务规则校验+状态流转+registerEvent(OrderCreatedEvent)在聚合根内部完成

            orderRepository.save(order);

            // 事件与业务数据在同一个本地事务中原子落库，事务提交前MQ完全不感知这次下单
            List<DomainEvent> events = order.pullEvents();
            eventOutboxStore.saveAll(events);

            log.info("order created successfully, orderId={}, pendingEventCount={}",
                    order.getId().getValue(), events.size());
            return OrderResult.from(order);
        } catch (com.example.order.domain.exception.DomainException e) {
            // 业务异常：记录warn级别日志（属于预期内的业务拒绝，非系统故障），事务自动回滚
            log.warn("order creation rejected by domain rule, userId={}, reason={}",
                    command.getUserId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // 未预期的技术异常：记录error级别日志并保留堆栈，便于排查
            log.error("unexpected error while creating order, userId={}", command.getUserId(), e);
            throw new OrderCreationFailedException("create order failed unexpectedly", e);
        }
    }
}
```

#### 7.1.3 事件转发器：轮询 Outbox 表并投递到 MQ

```java
package com.example.order.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class EventOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventOutboxDispatcher.class);
    private static final int MAX_RETRY_COUNT = 5;
    private static final long SEND_TIMEOUT_SECONDS = 3L;

    private final EventOutboxMapper eventOutboxMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public EventOutboxDispatcher(EventOutboxMapper eventOutboxMapper,
                                  KafkaTemplate<String, String> kafkaTemplate) {
        this.eventOutboxMapper = eventOutboxMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 500)
    public void dispatchPendingEvents() {
        List<EventOutboxPO> pendingEvents = eventOutboxMapper.selectPendingBatch(100);
        if (pendingEvents.isEmpty()) {
            return;
        }
        log.debug("dispatching {} pending domain events", pendingEvents.size());

        for (EventOutboxPO event : pendingEvents) {
            dispatchOne(event);
        }
    }

    private void dispatchOne(EventOutboxPO event) {
        String topic = resolveTopic(event.getEventType());
        try {
            // 使用聚合ID作为分区键，保证同一订单产生的多个事件在MQ内按顺序落盘、按顺序被消费
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
            record.headers().add("eventId", event.getEventId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            record.headers().add("eventType", event.getEventType().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            eventOutboxMapper.markPublished(event.getEventId());
            log.info("domain event dispatched, eventId={}, eventType={}, topic={}",
                    event.getEventId(), event.getEventType(), topic);
        } catch (TimeoutException e) {
            log.warn("dispatch domain event timeout, eventId={}, will retry next round", event.getEventId(), e);
            incrementRetryOrMarkFailed(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("dispatch domain event interrupted, eventId={}", event.getEventId(), e);
        } catch (ExecutionException e) {
            log.error("dispatch domain event failed, eventId={}, eventType={}",
                    event.getEventId(), event.getEventType(), e);
            incrementRetryOrMarkFailed(event);
        } catch (Exception e) {
            log.error("unexpected exception while dispatching event, eventId={}", event.getEventId(), e);
            incrementRetryOrMarkFailed(event);
        }
    }

    private void incrementRetryOrMarkFailed(EventOutboxPO event) {
        int nextRetryCount = event.getRetryCount() + 1;
        if (nextRetryCount >= MAX_RETRY_COUNT) {
            // 超过重试上限后标记为发布失败并告警，避免无限重试掩盖真实故障（如topic配置错误）
            eventOutboxMapper.markFailed(event.getEventId());
            log.error("domain event dispatch exceeded max retry, eventId={}, retryCount={}, alerting on-call",
                    event.getEventId(), nextRetryCount);
            AlertNotifier.notifyEventDispatchFailure(event.getEventId(), event.getEventType());
        } else {
            eventOutboxMapper.incrementRetryCount(event.getEventId(), nextRetryCount);
        }
    }

    private String resolveTopic(String eventType) {
        switch (eventType) {
            case "ORDER_CREATED":
                return "order-created-topic";
            default:
                throw new IllegalStateException("unknown event type without topic mapping: " + eventType);
        }
    }
}
```

#### 7.1.4 消费端：反序列化 + 幂等控制 + 事件处理（库存上下文）

```java
package com.example.inventory.interfaces.event;

import com.example.inventory.application.InventoryApplicationService;
import com.example.inventory.domain.exception.InsufficientStockException;
import com.example.inventory.infrastructure.idempotent.ProcessedEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class OrderCreatedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final InventoryApplicationService inventoryApplicationService;
    private final ProcessedEventRepository processedEventRepository;

    public OrderCreatedEventConsumer(ObjectMapper objectMapper,
                                      InventoryApplicationService inventoryApplicationService,
                                      ProcessedEventRepository processedEventRepository) {
        this.objectMapper = objectMapper;
        this.inventoryApplicationService = inventoryApplicationService;
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(topics = "order-created-topic", groupId = "inventory-service",
            containerFactory = "manualAckKafkaListenerContainerFactory")
    public void onOrderCreated(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = extractHeader(record, "eventId");
        String orderId = record.key();

        // MDC埋点，保证同一条消息在整条处理链路上的日志都能通过eventId/orderId串联排查
        org.slf4j.MDC.put("eventId", eventId);
        org.slf4j.MDC.put("orderId", orderId);
        try {
            log.info("received order-created event, offset={}, partition={}", record.offset(), record.partition());

            // 第一步：反序列化，反序列化失败属于消息本身损坏（脏数据），不重试，直接进入死信处理
            OrderCreatedEventPayload payload;
            try {
                payload = objectMapper.readValue(record.value(), OrderCreatedEventPayload.class);
            } catch (Exception e) {
                log.error("failed to deserialize order-created event, rawPayload={}", record.value(), e);
                deadLetterPublisher.publish(record.topic(), record.value(), e.getMessage());
                ack.acknowledge(); // 反序列化失败无法通过重试解决，确认消费避免消息堆积阻塞分区
                return;
            }

            // 第二步：幂等判断，基于eventId在处理前先检查，避免同一事件因MQ重投被重复处理
            if (processedEventRepository.exists(payload.getEventId())) {
                log.info("event already processed, skip duplicate consumption, eventId={}", payload.getEventId());
                ack.acknowledge();
                return;
            }

            // 第三步：真正的领域处理逻辑，交给应用层编排（内部会调用领域服务完成扣减）
            inventoryApplicationService.handleOrderCreated(payload.getOrderId(), payload.getItems());

            // 第四步：处理成功后落库幂等标记，与业务扣减操作应在同一个本地事务中保证原子性
            processedEventRepository.markProcessed(payload.getEventId());

            ack.acknowledge();
            log.info("order-created event processed successfully, orderId={}", payload.getOrderId());

        } catch (InsufficientStockException e) {
            // 业务异常：库存不足属于预期内的业务状态，不应无限重试（重试也不会成功），
            // 记录日志并转入补偿流程（如触发订单取消事件），然后确认消费避免阻塞分区
            log.warn("insufficient stock while handling order-created event, eventId={}, reason={}",
                    eventId, e.getMessage());
            compensationEventPublisher.publishStockInsufficient(orderId, e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            // 未预期的技术异常（如DB连接失败）：不确认消费，依赖Kafka的重试机制重新投递，
            // 配合幂等判断保证重试不会导致重复扣减
            log.error("unexpected error while handling order-created event, eventId={}", eventId, e);
            // 不调用ack.acknowledge()，触发消息重新投递
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private String extractHeader(ConsumerRecord<String, String> record, String key) {
        return Optional.ofNullable(record.headers().lastHeader(key))
                .map(h -> new String(h.value(), StandardCharsets.UTF_8))
                .orElse("unknown");
    }

    // 以下两个协作方在真实工程中会是独立注入的Bean，此处为保持示例聚焦省略其定义细节
    private final DeadLetterPublisher deadLetterPublisher = new DeadLetterPublisher();
    private final CompensationEventPublisher compensationEventPublisher = new CompensationEventPublisher();
}
```

```java
package com.example.inventory.infrastructure.idempotent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录仓储：基于数据库唯一索引（event_id唯一约束）实现幂等，
 * 比"先查询再插入"更可靠，能应对高并发下的竞态条件
 */
@Repository
public class ProcessedEventRepository {

    private final ProcessedEventMapper processedEventMapper;

    public ProcessedEventRepository(ProcessedEventMapper processedEventMapper) {
        this.processedEventMapper = processedEventMapper;
    }

    public boolean exists(String eventId) {
        return processedEventMapper.countByEventId(eventId) > 0;
    }

    public void markProcessed(String eventId) {
        try {
            processedEventMapper.insert(eventId, java.time.LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            // 并发场景下两个线程同时判断"未处理"后同时插入，数据库唯一索引兜底拦截重复标记，
            // 此时视为正常的并发幂等命中，无需向上抛出异常
        }
    }
}
```

```java
package com.example.inventory.application;

import com.example.inventory.domain.exception.InsufficientStockException;
import com.example.inventory.domain.model.Inventory;
import com.example.inventory.domain.repository.InventoryRepository;
import com.example.order.domain.event.OrderCreatedEvent.OrderItemSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InventoryApplicationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryApplicationService.class);

    private final InventoryRepository inventoryRepository;

    public InventoryApplicationService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCreated(String orderId, List<OrderItemSnapshot> items) {
        for (OrderItemSnapshot item : items) {
            Inventory inventory = inventoryRepository.findBySkuId(item.getSkuId())
                    .orElseThrow(() -> new IllegalStateException("inventory not found for sku=" + item.getSkuId()));

            if (!inventory.hasEnoughStock(item.getQuantity())) {
                throw new InsufficientStockException(item.getSkuId(), item.getQuantity(), inventory.getAvailable());
            }
            inventory.deduct(item.getQuantity()); // 业务规则封装在聚合根内部
            inventoryRepository.save(inventory);
            log.info("stock deducted for order fulfillment, orderId={}, skuId={}, quantity={}",
                    orderId, item.getSkuId(), item.getQuantity());
        }
    }
}
```

**该链路的关键设计点回顾**：

1. **事件产生与业务数据落库的原子性**：通过 Outbox 表 + 本地事务解决"业务成功但事件丢失"的问题（7.1.2）。
2. **投递可靠性**：转发器对发送异常做有限次数重试，超限后转告警而非无限重试（7.1.3）。
3. **消费端幂等**：基于数据库唯一索引而非简单的查询判断实现幂等，能正确处理并发竞态（7.1.4）。
4. **消费端顺序性**：以聚合 ID（订单 ID）作为 MQ 分区键，保证同一订单的事件在消费端有序处理。
5. **异常分级处理**：反序列化失败（脏数据）→ 死信队列；业务异常（库存不足）→ 记录日志 + 触发补偿；技术异常（DB 故障）→ 不确认消费，依赖 MQ 重试。

### 7.2 案例二：微服务拆分后的聚合查询全链路

**业务场景**：商品详情页需要聚合展示"商品基本信息""实时库存""用户可用优惠券""近期评价摘要"四类数据，分别来自商品服务、库存服务、营销服务、评价服务四个独立微服务。为了保证首屏响应时间，网关层需要**并行调用**四个下游服务，任意一个非核心服务超时或失败时应**降级返回默认值**而不是让整个页面报错。

#### 7.2.1 网关侧聚合接口定义

```java
package com.example.gateway.interfaces;

import com.example.gateway.application.ProductDetailAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductDetailAggregationController {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailAggregationController.class);

    private final ProductDetailAggregationService aggregationService;

    public ProductDetailAggregationController(ProductDetailAggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @GetMapping("/aggregation/products/{productId}")
    public ProductDetailAggregatedResponse getProductDetail(@PathVariable String productId,
                                                              @RequestParam String userId) {
        long start = System.currentTimeMillis();
        try {
            ProductDetailAggregatedResponse response = aggregationService.aggregate(productId, userId);
            log.info("product detail aggregation succeeded, productId={}, userId={}, costMs={}",
                    productId, userId, System.currentTimeMillis() - start);
            return response;
        } catch (Exception e) {
            // 聚合接口的兜底：即使编排逻辑本身出现未预期异常，也返回一个明确的降级响应，
            // 而不是让整个页面因为一个聚合接口的500错误而白屏
            log.error("product detail aggregation failed unexpectedly, productId={}, userId={}, costMs={}",
                    productId, userId, System.currentTimeMillis() - start, e);
            return ProductDetailAggregatedResponse.fallback(productId);
        }
    }
}
```

#### 7.2.2 并行调用编排（应用层）

```java
package com.example.gateway.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.*;

@Service
public class ProductDetailAggregationService {

    private static final Logger log = LoggerFactory.getLogger(ProductDetailAggregationService.class);

    // 核心线程数与下游依赖数量匹配，避免线程池过大导致资源浪费或过小导致排队延迟
    private final ExecutorService aggregationExecutor = new ThreadPoolExecutor(
            8, 16, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            new ThreadFactory() {
                private final AtomicIntegerWrapper counter = new AtomicIntegerWrapper();
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "product-detail-aggregation-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列打满时由调用线程直接执行，避免任务丢失，同时天然形成反压
    );

    private final ProductServiceClient productServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final PromotionServiceClient promotionServiceClient;
    private final ReviewServiceClient reviewServiceClient;

    private static final long PER_CALL_TIMEOUT_MS = 800L;   // 单个下游服务超时阈值
    private static final long OVERALL_TIMEOUT_MS = 1200L;   // 整体聚合超时阈值（略大于单调用超时，容纳调度开销）

    public ProductDetailAggregationService(ProductServiceClient productServiceClient,
                                            InventoryServiceClient inventoryServiceClient,
                                            PromotionServiceClient promotionServiceClient,
                                            ReviewServiceClient reviewServiceClient) {
        this.productServiceClient = productServiceClient;
        this.inventoryServiceClient = inventoryServiceClient;
        this.promotionServiceClient = promotionServiceClient;
        this.reviewServiceClient = reviewServiceClient;
    }

    public ProductDetailAggregatedResponse aggregate(String productId, String userId) {
        // 商品基本信息是核心数据，缺失则整个聚合请求应视为失败；
        // 库存/优惠券/评价属于增强信息，缺失时可降级为默认值，不影响主流程返回
        CompletableFuture<ProductInfo> productFuture = supplyWithTimeout(
                () -> productServiceClient.getProductInfo(productId), "product-info", productId);

        CompletableFuture<StockInfo> stockFuture = supplyWithTimeout(
                () -> inventoryServiceClient.getStock(productId), "stock", productId)
                .exceptionally(ex -> {
                    logDegradation("stock", productId, ex);
                    return StockInfo.unknown(); // 降级：库存展示"库存状态未知"，不阻塞主流程
                });

        CompletableFuture<List<CouponInfo>> couponFuture = supplyWithTimeout(
                () -> promotionServiceClient.getAvailableCoupons(productId, userId), "coupon", productId)
                .exceptionally(ex -> {
                    logDegradation("coupon", productId, ex);
                    return Collections.emptyList(); // 降级：不展示优惠券区块
                });

        CompletableFuture<ReviewSummary> reviewFuture = supplyWithTimeout(
                () -> reviewServiceClient.getReviewSummary(productId), "review", productId)
                .exceptionally(ex -> {
                    logDegradation("review", productId, ex);
                    return ReviewSummary.empty(); // 降级：评价区块展示"暂无评价"
                });

        try {
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    productFuture, stockFuture, couponFuture, reviewFuture);
            allOf.get(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            ProductInfo productInfo = productFuture.join(); // 核心数据，允许其异常向上抛出

            return ProductDetailAggregatedResponse.builder()
                    .productId(productId)
                    .productInfo(productInfo)
                    .stockInfo(stockFuture.join())
                    .coupons(couponFuture.join())
                    .reviewSummary(reviewFuture.join())
                    .build();

        } catch (TimeoutException e) {
            // 整体超时：核心数据商品信息也可能仍未返回，此时整个聚合请求判定为失败，抛出业务异常
            log.warn("product detail aggregation overall timeout, productId={}, timeoutMs={}",
                    productId, OVERALL_TIMEOUT_MS);
            throw new AggregationTimeoutException("product detail aggregation timeout, productId=" + productId);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ProductNotFoundException) {
                log.warn("product not found during aggregation, productId={}", productId);
                throw (ProductNotFoundException) cause;
            }
            log.error("product detail aggregation execution failed, productId={}", productId, cause);
            throw new AggregationFailedException("aggregate product detail failed, productId=" + productId, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("product detail aggregation interrupted, productId={}", productId, e);
            throw new AggregationFailedException("aggregation interrupted, productId=" + productId, e);
        }
    }

    /** 统一封装单个下游调用：异步提交 + 单独超时控制，任意一路慢不会拖累其他并行调用 */
    private <T> CompletableFuture<T> supplyWithTimeout(Supplier<T> supplier, String callName, String productId) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                T result = supplier.get();
                log.info("downstream call succeeded, call={}, productId={}, costMs={}",
                        callName, productId, System.currentTimeMillis() - start);
                return result;
            } catch (Exception e) {
                log.warn("downstream call failed, call={}, productId={}, costMs={}, reason={}",
                        callName, productId, System.currentTimeMillis() - start, e.getMessage());
                throw new CompletionException(e);
            }
        }, aggregationExecutor);

        // 单路超时：基于orTimeout实现，超时后该Future以TimeoutException异常完成，
        // 不影响已经提交给线程池执行的其他下游调用继续运行
        return future.orTimeout(PER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void logDegradation(String callName, String productId, Throwable ex) {
        Throwable root = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
        if (root instanceof TimeoutException) {
            log.warn("downstream call degraded due to timeout, call={}, productId={}", callName, productId);
        } else {
            log.warn("downstream call degraded due to exception, call={}, productId={}, reason={}",
                    callName, productId, root.getMessage());
        }
    }
}
```

#### 7.2.3 单个下游客户端：熔断 + 超时 + 幂等重试（以库存服务客户端为例）

```java
package com.example.gateway.application;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.function.Supplier;

@Component
public class InventoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceClient.class);

    private final RestTemplate restTemplate;
    private final CircuitBreaker circuitBreaker;

    public InventoryServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                       // 失败率超过50%触发熔断
                .waitDurationInOpenState(Duration.ofSeconds(10)) // 熔断后10秒进入半开状态尝试恢复
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("inventory-service");
    }

    public StockInfo getStock(String productId) {
        // 查询是幂等的天然幂等操作（GET语义），可以安全地做有限次数重试；
        // 熔断器包裹在重试外层，避免熔断打开时仍然发起重试请求进一步拖垮下游
        Supplier<StockInfo> decorated = Decorators.ofSupplier(() -> doGetStockWithRetry(productId))
                .withCircuitBreaker(circuitBreaker)
                .decorate();
        try {
            return decorated.get();
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("inventory service circuit breaker is open, fallback to unknown stock, productId={}", productId);
            return StockInfo.unknown();
        }
    }

    private StockInfo doGetStockWithRetry(String productId) {
        final int maxAttempts = 2; // 查询场景重试次数不宜过多，避免放大下游压力
        RestClientException lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restTemplate.getForObject("/inventory/stock/" + productId, StockInfo.class);
            } catch (RestClientException e) {
                lastException = e;
                log.warn("get stock failed, productId={}, attempt={}/{}, reason={}",
                        productId, attempt, maxAttempts, e.getMessage());
            }
        }
        throw lastException;
    }
}
```

**该链路的关键设计点回顾**：

1. **并行而非串行调用**：四个下游服务通过独立的 `CompletableFuture` 并行发起，总耗时约等于最慢的一路而非四路之和。
2. **核心数据与增强数据的差异化容错**：商品基本信息（核心）失败则整体失败；库存、优惠券、评价（增强）失败则降级为默认值，用 `exceptionally` 分别兜底。
3. **单路超时 + 整体超时双重控制**：`orTimeout` 控制单个下游调用不拖垮整体，`allOf().get(timeout)` 控制整体聚合的最大等待时间。
4. **熔断与幂等重试结合**：只对天然幂等的 GET 查询做有限重试，且熔断器包裹在重试外层，避免"重试风暴"进一步压垮已经故障的下游服务。
5. **线程池与拒绝策略**：使用独立的聚合专用线程池并采用 `CallerRunsPolicy` 形成天然反压，避免因下游抖动导致聚合线程池被瞬时占满。

### 7.3 案例三：微服务边界划分实战全链路

**业务场景**：将电商单体中的"售后退款"能力从订单模块中拆分为独立的"售后服务"，需要完整走完限界上下文分析、上下文映射、防腐层设计、接口契约定义、集成测试这五个环节，避免拆分后变成又一个"分布式单体"。

#### 7.3.1 限界上下文分析（事件风暴产出物结构化）

```java
package com.example.aftersale.domain.eventstorming;

/**
 * 该类不是运行时代码，而是把事件风暴工作坊的产出物以代码注释形式沉淀在仓库中，
 * 作为团队后续架构决策的可追溯依据（很多团队会把这类文档写成ADR，此处用注释形式演示要点）
 *
 * 【领域事件时间线】
 * 用户申请售后 -> 售后单已创建 -> 客服已审核 -> 退款已发起 -> 退款已到账 -> 售后单已完成
 *                                            \-> 审核已拒绝 -> 售后单已关闭
 *
 * 【触发命令与角色】
 * 命令：申请售后(买家) / 审核售后单(客服) / 发起退款(系统自动或财务) / 确认到账(支付网关回调)
 *
 * 【识别出的聚合】
 * AfterSaleOrder聚合根：包含售后单主体、售后原因、退款金额、状态机
 *   —— 与Order（订单）聚合是完全不同的聚合，售后单只引用OrderId而不包含Order的完整信息
 *
 * 【限界上下文边界判定依据】
 * 1. 事件密度骤降点：从"退款已到账"到"下一次用户浏览商品"之间，业务流程和涉及角色发生明显切换
 * 2. 团队职责边界：售后团队独立负责规则设计（如超时自动退款策略），与交易团队的下单规则演进节奏不同
 * 3. 一致性要求差异：订单状态变更要求强一致（本地事务内完成），售后退款到账普遍是异步的（依赖第三方支付网关回调），
 *    两者对一致性和响应时延的要求本质不同，是划分为独立限界上下文的关键信号
 *
 * 结论：售后上下文（Supporting Subdomain，支撑域）与交易上下文（Core Domain，核心域）应划分为两个独立限界上下文，
 * 通过"客户方-供应方"模式协作：售后上下文是客户方，依赖交易上下文提供的订单查询能力；
 * 对接第三方支付网关退款接口时，因其模型设计与自身领域不一致，需要引入防腐层。
 */
public final class AfterSaleContextAnalysisNote {
    private AfterSaleContextAnalysisNote() {}
}
```

#### 7.3.2 上下文映射：售后上下文对订单上下文的防腐层设计

```java
package com.example.aftersale.infrastructure.acl.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订单上下文防腐层：售后上下文不直接依赖订单服务的DTO结构，
 * 而是通过这一层转换为售后领域自身认可的模型（OrderSnapshot），
 * 即使订单服务未来重构了对外DTO字段，只需要修改这一层的转换逻辑，售后领域模型完全不受影响
 */
@Component
public class OrderContextAntiCorruptionLayer {

    private static final Logger log = LoggerFactory.getLogger(OrderContextAntiCorruptionLayer.class);

    private final OrderServiceFeignClient orderServiceFeignClient;

    public OrderContextAntiCorruptionLayer(OrderServiceFeignClient orderServiceFeignClient) {
        this.orderServiceFeignClient = orderServiceFeignClient;
    }

    /**
     * 售后领域内部认可的订单快照模型，只包含审核售后单所必需的字段，
     * 与订单服务真实的OrderRpcDTO（可能有几十个字段、多层嵌套）完全解耦
     */
    public OrderSnapshot getOrderSnapshot(String orderId) {
        try {
            OrderRpcDTO rpcDTO = orderServiceFeignClient.getOrder(orderId);
            if (rpcDTO == null) {
                log.warn("order not found when building snapshot for after-sale, orderId={}", orderId);
                throw new OrderNotFoundInAfterSaleException(orderId);
            }
            return translate(rpcDTO);
        } catch (feign.FeignException.NotFound e) {
            log.warn("order not found (404) when calling order service, orderId={}", orderId);
            throw new OrderNotFoundInAfterSaleException(orderId);
        } catch (feign.FeignException e) {
            // 订单服务调用失败（超时、5xx等）：转换为售后上下文自己的技术异常，
            // 不让Feign的异常类型泄漏到售后领域层，保持防腐层内外的异常语义隔离
            log.error("failed to call order service for snapshot, orderId={}, status={}",
                    orderId, e.status(), e);
            throw new OrderContextUnavailableException("order service call failed, orderId=" + orderId, e);
        }
    }

    /** 核心翻译逻辑：字段裁剪、语义转换，隔离订单上下文内部模型演进对售后上下文的影响 */
    private OrderSnapshot translate(OrderRpcDTO rpcDTO) {
        return new OrderSnapshot(
                rpcDTO.getOrderId(),
                rpcDTO.getUserId(),
                translateRefundableAmount(rpcDTO),
                translateOrderStatus(rpcDTO.getStatusCode())
        );
    }

    private long translateRefundableAmount(OrderRpcDTO rpcDTO) {
        // 订单服务返回的是"实付金额"和"已退款金额"两个字段，售后领域只关心"可退款余额"这一个业务概念，
        // 这种计算逻辑的封装正是防腐层的价值所在——避免售后领域到处出现"实付-已退"这样的裸计算
        return rpcDTO.getPaidAmountCents() - rpcDTO.getRefundedAmountCents();
    }

    private OrderSnapshotStatus translateOrderStatus(int legacyStatusCode) {
        switch (legacyStatusCode) {
            case 3: return OrderSnapshotStatus.COMPLETED;
            case 4: return OrderSnapshotStatus.SHIPPED;
            default: return OrderSnapshotStatus.OTHER; // 售后场景只关心"已完成/已发货"两种关键状态，其余一律归为OTHER
        }
    }
}
```

```java
package com.example.aftersale.infrastructure.acl.order;

/** 售后上下文自定义的技术异常，用于隔离下游服务调用失败的原始异常类型 */
public class OrderContextUnavailableException extends RuntimeException {
    public OrderContextUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

/** 售后上下文自定义的业务异常：订单不存在 */
public class OrderNotFoundInAfterSaleException extends RuntimeException {
    public OrderNotFoundInAfterSaleException(String orderId) {
        super("order not found for after-sale application, orderId=" + orderId);
    }
}
```

#### 7.3.3 接口契约定义：售后服务对外 API（含幂等）

```java
package com.example.aftersale.api;

import java.io.Serializable;

/** 对外接口契约：所有跨服务字段都是显式的DTO，不直接暴露领域模型，任何字段变更都需要走契约评审 */
public class ApplyAfterSaleRequest implements Serializable {
    private String orderId;
    private String userId;
    private String reasonCode;
    private String reasonDetail;
    private String requestId; // 幂等键，由调用方生成并保证在业务语义上唯一（如同一次点击只生成一个requestId）
    // getters/setters省略
}

public class ApplyAfterSaleResponse implements Serializable {
    private String afterSaleOrderId;
    private String status;
    private String message;
    // getters/setters省略
}
```

```java
package com.example.aftersale.interfaces;

import com.example.aftersale.api.ApplyAfterSaleRequest;
import com.example.aftersale.api.ApplyAfterSaleResponse;
import com.example.aftersale.application.AfterSaleApplicationService;
import com.example.aftersale.domain.exception.AfterSaleDomainException;
import com.example.aftersale.infrastructure.acl.order.OrderContextUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

@RestController
@RequestMapping("/after-sale")
public class AfterSaleController {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleController.class);

    private final AfterSaleApplicationService afterSaleApplicationService;

    public AfterSaleController(AfterSaleApplicationService afterSaleApplicationService) {
        this.afterSaleApplicationService = afterSaleApplicationService;
    }

    @PostMapping("/apply")
    public ResponseEntity<ApplyAfterSaleResponse> apply(@RequestBody @Valid ApplyAfterSaleRequest request) {
        log.info("received after-sale application, orderId={}, userId={}, requestId={}",
                request.getOrderId(), request.getUserId(), request.getRequestId());
        try {
            ApplyAfterSaleResponse response = afterSaleApplicationService.apply(request);
            return ResponseEntity.ok(response);
        } catch (OrderContextUnavailableException e) {
            // 依赖的订单上下文不可用：返回503让调用方（前端/网关）触发重试或提示用户稍后重试，
            // 而不是伪装成一个业务失败结果
            log.error("order context unavailable while applying after-sale, orderId={}", request.getOrderId(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorResponse("ORDER_CONTEXT_UNAVAILABLE", "订单信息暂时无法获取，请稍后重试"));
        } catch (AfterSaleDomainException e) {
            log.warn("after-sale application rejected by domain rule, orderId={}, reason={}",
                    request.getOrderId(), e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse(e.getErrorCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("unexpected error while applying after-sale, orderId={}", request.getOrderId(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse("INTERNAL_ERROR", "系统繁忙，请稍后重试"));
        }
    }

    private ApplyAfterSaleResponse errorResponse(String code, String message) {
        ApplyAfterSaleResponse response = new ApplyAfterSaleResponse();
        response.setStatus(code);
        response.setMessage(message);
        return response;
    }
}
```

```java
package com.example.aftersale.application;

import com.example.aftersale.api.ApplyAfterSaleRequest;
import com.example.aftersale.api.ApplyAfterSaleResponse;
import com.example.aftersale.domain.model.AfterSaleOrder;
import com.example.aftersale.domain.repository.AfterSaleOrderRepository;
import com.example.aftersale.infrastructure.acl.order.OrderContextAntiCorruptionLayer;
import com.example.aftersale.infrastructure.acl.order.OrderSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AfterSaleApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleApplicationService.class);

    private final AfterSaleOrderRepository afterSaleOrderRepository;
    private final OrderContextAntiCorruptionLayer orderContextAcl;

    public AfterSaleApplicationService(AfterSaleOrderRepository afterSaleOrderRepository,
                                        OrderContextAntiCorruptionLayer orderContextAcl) {
        this.afterSaleOrderRepository = afterSaleOrderRepository;
        this.orderContextAcl = orderContextAcl;
    }

    @Transactional(rollbackFor = Exception.class)
    public ApplyAfterSaleResponse apply(ApplyAfterSaleRequest request) {
        // 幂等控制：requestId在数据库中建唯一索引，先查询命中则直接返回已有结果，避免用户重复点击导致重复售后单
        AfterSaleOrder existing = afterSaleOrderRepository.findByRequestId(request.getRequestId());
        if (existing != null) {
            log.info("duplicate after-sale request detected, requestId={}, existingOrderId={}",
                    request.getRequestId(), existing.getId().getValue());
            return toResponse(existing);
        }

        // 通过防腐层获取订单信息，售后领域完全不感知订单服务的原始DTO结构
        OrderSnapshot orderSnapshot = orderContextAcl.getOrderSnapshot(request.getOrderId());

        AfterSaleOrder afterSaleOrder = AfterSaleOrder.apply(
                request.getOrderId(), request.getUserId(), request.getReasonCode(),
                request.getReasonDetail(), orderSnapshot, request.getRequestId());

        try {
            afterSaleOrderRepository.save(afterSaleOrder);
        } catch (DuplicateKeyException e) {
            // 并发场景下的兜底：两个并发请求都通过了前面的查询判断，数据库唯一索引最终拦截了重复插入，
            // 此时重新查询一次已存在的记录返回，保证接口的幂等语义在高并发下依然成立
            log.warn("concurrent duplicate after-sale request caught by unique constraint, requestId={}",
                    request.getRequestId());
            AfterSaleOrder concurrentExisting = afterSaleOrderRepository.findByRequestId(request.getRequestId());
            return toResponse(concurrentExisting);
        }

        log.info("after-sale order created, afterSaleOrderId={}, orderId={}",
                afterSaleOrder.getId().getValue(), request.getOrderId());
        return toResponse(afterSaleOrder);
    }

    private ApplyAfterSaleResponse toResponse(AfterSaleOrder afterSaleOrder) {
        ApplyAfterSaleResponse response = new ApplyAfterSaleResponse();
        response.setAfterSaleOrderId(afterSaleOrder.getId().getValue().toString());
        response.setStatus(afterSaleOrder.getStatus().name());
        response.setMessage("申请已受理");
        return response;
    }
}
```

#### 7.3.4 集成测试：契约测试 + 防腐层的隔离测试

```java
package com.example.aftersale.integration;

import com.example.aftersale.api.ApplyAfterSaleRequest;
import com.example.aftersale.api.ApplyAfterSaleResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试覆盖两类核心场景：
 * 1. 幂等性：同一requestId重复提交应返回同一个售后单，而不是创建多条记录
 * 2. 防腐层的容错：订单服务不可用时，接口应返回明确的503而非普通的500或挂起
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AfterSaleIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void should_return_same_result_when_apply_with_duplicate_request_id() {
        ApplyAfterSaleRequest request = buildRequest("order-1001", "req-idempotent-001");

        ResponseEntity<ApplyAfterSaleResponse> firstResponse =
                restTemplate.postForEntity("/after-sale/apply", request, ApplyAfterSaleResponse.class);
        ResponseEntity<ApplyAfterSaleResponse> secondResponse =
                restTemplate.postForEntity("/after-sale/apply", request, ApplyAfterSaleResponse.class);

        assertThat(firstResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(secondResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(firstResponse.getBody().getAfterSaleOrderId())
                .isEqualTo(secondResponse.getBody().getAfterSaleOrderId()); // 幂等：两次提交返回同一个售后单ID
    }

    @Test
    void should_return_service_unavailable_when_order_context_is_down() {
        // 通过WireMock/Stub将订单服务的依赖打桩为不可用状态，验证防腐层能够正确捕获并转换异常，
        // 而不是让Feign的底层连接异常直接冒泡到接口层导致语义不明确的500错误
        ApplyAfterSaleRequest request = buildRequest("order-unreachable", "req-order-down-001");

        ResponseEntity<ApplyAfterSaleResponse> response =
                restTemplate.postForEntity("/after-sale/apply", request, ApplyAfterSaleResponse.class);

        assertThat(response.getStatusCodeValue()).isEqualTo(503);
        assertThat(response.getBody().getStatus()).isEqualTo("ORDER_CONTEXT_UNAVAILABLE");
    }

    private ApplyAfterSaleRequest buildRequest(String orderId, String requestId) {
        ApplyAfterSaleRequest request = new ApplyAfterSaleRequest();
        request.setOrderId(orderId);
        request.setUserId("user-001");
        request.setReasonCode("QUALITY_ISSUE");
        request.setReasonDetail("商品存在质量问题");
        request.setRequestId(requestId);
        return request;
    }
}
```

**该链路的关键设计点回顾**：

1. **限界上下文划分要有明确依据**：不是拍脑袋决定拆分，而是基于事件密度、团队边界、一致性要求差异三个维度综合判断（7.3.1）。
2. **防腐层隔离外部模型污染**：售后领域只认识 `OrderSnapshot` 这一自定义模型，订单服务真实 DTO 结构的任何变化都被防腐层吸收，不扩散到售后领域内部（7.3.2）。
3. **接口契约显式化**：跨服务 DTO 与内部领域模型严格区分，`requestId` 作为幂等键写入契约本身，是接口设计阶段就应该明确的规范，而不是留给实现阶段临时补救。
4. **幂等的双重保障**：应用层先查询判断 + 数据库唯一索引兜底，正确处理了单线程重复提交和高并发竞态两种场景（7.3.3）。
5. **集成测试覆盖跨上下文的失败场景**：不仅测试正常路径，还必须验证依赖的上游上下文不可用时，防腐层和接口层能否给出正确的降级响应，这是微服务边界测试区别于单体测试的核心差异点（7.3.4）。</new_string>

