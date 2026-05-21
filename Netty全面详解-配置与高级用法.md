# Netty 全面详解：配置与高级用法

> 本文从基础配置到高级特性，系统性地讲解 Netty 的核心知识。前半部分聚焦"怎么配"，后半部分聚焦"怎么用好"。

---

## 一、整体架构概览

Netty 的核心配置围绕三大块展开：**Bootstrap 启动配置**（包括 Option 参数）、**ChannelPipeline 管道配置**、**ChannelHandler 处理器配置**。理解这三者的关系是掌握 Netty 的关键——Bootstrap 负责"怎么建立连接"，Pipeline 负责"数据怎么流转"，Handler 负责"数据怎么处理"。

```
+-------------------------------------------------------------+
|                    ServerBootstrap                            |
|  +-------------+    +--------------+    +------------+       |
|  | EventLoop   |    | Channel      |    | Option     |       |
|  | Group       |    | Type         |    | 参数        |       |
|  +-------------+    +--------------+    +------------+       |
|                           |                                  |
|                    +------v------+                            |
|                    |  Pipeline   |                            |
|                    | +--------+  |                            |
|                    | |Handler1|  |                            |
|                    | +--------+  |                            |
|                    | |Handler2|  |                            |
|                    | +--------+  |                            |
|                    | |Handler3|  |                            |
|                    | +--------+  |                            |
|                    +-------------+                            |
+-------------------------------------------------------------+
```

---

# 第一部分：基础配置

---

## 二、Bootstrap 启动配置

### 2.1 ServerBootstrap vs Bootstrap

Netty 提供两种引导类：`ServerBootstrap` 用于服务端，`Bootstrap` 用于客户端。服务端有 parent/child 两组配置的概念（分别对应 boss 和 worker），客户端只有一组。

```java
// 服务端典型配置
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)          // 两组 EventLoopGroup
 .channel(NioServerSocketChannel.class)  // Channel 实现类
 .option(ChannelOption.SO_BACKLOG, 1024) // boss channel 的选项
 .childOption(ChannelOption.SO_KEEPALIVE, true) // worker channel 的选项
 .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline().addLast(...);
     }
 });

// 客户端典型配置
Bootstrap b = new Bootstrap();
b.group(workerGroup)
 .channel(NioSocketChannel.class)
 .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
 .handler(new ChannelInitializer<SocketChannel>() {
     @Override
     protected void initChannel(SocketChannel ch) {
         ch.pipeline().addLast(...);
     }
 });
```

### 2.2 EventLoopGroup 配置

EventLoopGroup 是 Netty 的线程模型核心，决定了 I/O 事件的处理方式。

| 配置项 | 说明 | 推荐值 |
|--------|------|--------|
| bossGroup 线程数 | 处理连接接入，通常 1 个线程即可 | 1 |
| workerGroup 线程数 | 处理 I/O 读写，默认 CPU 核心数 x 2 | Runtime.getRuntime().availableProcessors() * 2 |
| 实现类选择 | NioEventLoopGroup（通用）、EpollEventLoopGroup（Linux 高性能） | Linux 生产环境用 Epoll |

**使用经验：**

- bossGroup 设为 1 就够了，因为 ServerSocketChannel 只会绑定到一个 EventLoop 上。设多了也是浪费。
- workerGroup 的线程数不是越多越好。如果 Handler 中有阻塞操作（如数据库查询），应该把阻塞操作放到独立的业务线程池，而不是增加 worker 线程数。
- 在 Linux 环境下，优先使用 `EpollEventLoopGroup` + `EpollServerSocketChannel`，它使用 JNI 直接调用 epoll，比 NIO 的 select/poll 性能更好，且支持 edge-triggered 模式。

```java
// Linux 高性能配置
EventLoopGroup bossGroup = new EpollEventLoopGroup(1);
EventLoopGroup workerGroup = new EpollEventLoopGroup();
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(EpollServerSocketChannel.class);
```

---

## 三、ChannelOption 配置详解

ChannelOption 是 Netty 中最容易让人困惑的部分，因为它混合了 TCP 层参数、Netty 自身参数和操作系统参数。

### 3.1 TCP 连接相关

#### SO_BACKLOG

```java
.option(ChannelOption.SO_BACKLOG, 1024)
```

- **作用**：设置 TCP 全连接队列（accept queue）的大小。当服务端来不及 accept 新连接时，已完成三次握手的连接会在这个队列中等待。
- **默认值**：系统相关，Linux 默认 128。
- **推荐值**：高并发场景设为 1024 或更高。
- **使用经验**：这个值受限于操作系统的 `net.core.somaxconn` 参数，实际生效值是 `min(SO_BACKLOG, somaxconn)`。生产环境记得同时调整系统参数：`sysctl -w net.core.somaxconn=65535`。

#### SO_REUSEADDR

```java
.option(ChannelOption.SO_REUSEADDR, true)
```

- **作用**：允许重用处于 TIME_WAIT 状态的地址。服务重启时不会因为端口被占用而启动失败。
- **推荐值**：服务端通常设为 true。
- **使用经验**：开发和测试环境必开，否则频繁重启服务会遇到 "Address already in use" 错误。生产环境也建议开启，方便快速重启。

#### SO_KEEPALIVE

```java
.childOption(ChannelOption.SO_KEEPALIVE, true)
```

- **作用**：开启 TCP 层的 Keep-Alive 机制。当连接空闲一段时间后，操作系统会发送探测包检测对端是否存活。
- **默认探测时间**：Linux 默认 2 小时（太长了！）。
- **使用经验**：虽然可以开启，但 TCP Keep-Alive 的默认间隔太长，实际项目中更推荐在应用层实现心跳机制（用 `IdleStateHandler`），这样可以精确控制超时时间和重连策略。如果一定要用 TCP Keep-Alive，需要通过系统参数调整探测间隔：
  ```
  net.ipv4.tcp_keepalive_time = 60
  net.ipv4.tcp_keepalive_intvl = 10
  net.ipv4.tcp_keepalive_probes = 3
  ```

#### TCP_NODELAY

```java
.childOption(ChannelOption.TCP_NODELAY, true)
```

- **作用**：禁用 Nagle 算法。Nagle 算法会将小数据包合并后再发送，减少网络中的小包数量，但会引入延迟。
- **推荐值**：对延迟敏感的场景（RPC、游戏、即时通讯）设为 true。
- **使用经验**：绝大多数互联网应用都应该设为 true。Nagle 算法是为了解决早期网络带宽不足的问题，现代网络带宽充裕，延迟才是瓶颈。只有在大量小数据包且对延迟不敏感的场景（如日志传输）才考虑保留 Nagle。

#### SO_LINGER

```java
.childOption(ChannelOption.SO_LINGER, 0)
```

- **作用**：控制 close() 调用时的行为。设为 0 表示立即关闭（发送 RST），设为正数表示等待指定秒数让数据发送完毕。
- **使用经验**：一般不建议设置。默认行为（close 后在后台完成数据发送）对大多数场景已经足够。设为 0 会导致连接被 RST 强制关闭，对端可能丢失未读取的数据。只有在需要快速释放大量连接资源时才考虑。

### 3.2 缓冲区相关

#### SO_RCVBUF / SO_SNDBUF

```java
.childOption(ChannelOption.SO_RCVBUF, 65536)
.childOption(ChannelOption.SO_SNDBUF, 65536)
```

- **作用**：设置 TCP 接收/发送缓冲区大小。
- **使用经验**：现代 Linux 内核有自动调优机制（tcp_rmem/tcp_wmem），通常不需要手动设置。手动设置反而可能禁用自动调优。除非你非常清楚自己的流量模型，否则让操作系统自己管理。

#### WRITE_BUFFER_WATER_MARK

```java
.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, 
    new WriteBufferWaterMark(32 * 1024, 64 * 1024))
```

- **作用**：设置写缓冲区的低水位和高水位。当写缓冲区超过高水位时，Channel 变为不可写（`isWritable()` 返回 false）；低于低水位时恢复可写。
- **默认值**：低水位 32KB，高水位 64KB。
- **使用经验**：这是防止 OOM 的重要配置！如果生产者速度远大于消费者（网络发送速度），数据会堆积在写缓冲区。务必在写数据前检查 `channel.isWritable()`，并在 `channelWritabilityChanged` 回调中处理背压。

```java
// 正确的写数据方式
if (ctx.channel().isWritable()) {
    ctx.writeAndFlush(msg);
} else {
    // 处理背压：丢弃、缓存到磁盘、或通知上游降速
    log.warn("Channel not writable, dropping message");
    ReferenceCountUtil.release(msg);
}
```

### 3.3 Netty 自身参数

#### CONNECT_TIMEOUT_MILLIS

```java
.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
```

- **作用**：客户端连接超时时间（毫秒）。超过这个时间连接未建立，会抛出 `ConnectTimeoutException`。
- **推荐值**：3000~5000ms。
- **使用经验**：这是客户端配置。不要设太长，否则在目标不可达时会长时间阻塞。也不要设太短，跨机房连接可能需要较长时间。

#### ALLOCATOR

```java
.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
```

- **作用**：指定 ByteBuf 的内存分配器。
- **推荐值**：`PooledByteBufAllocator.DEFAULT`（Netty 4.1+ 已是默认值）。
- **使用经验**：池化分配器通过内存池复用 ByteBuf，大幅减少 GC 压力。生产环境务必使用池化分配器。如果遇到内存泄漏问题，可以临时切换为 `UnpooledByteBufAllocator` 配合 `-Dio.netty.leakDetection.level=PARANOID` 来排查。

#### RCVBUF_ALLOCATOR

```java
.childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator())
```

- **作用**：控制每次读取时分配的 ByteBuf 大小。`AdaptiveRecvByteBufAllocator` 会根据历史读取量动态调整缓冲区大小。
- **使用经验**：默认的自适应分配器在大多数场景下表现良好。如果你的协议消息大小非常固定（如定长协议），可以使用 `FixedRecvByteBufAllocator` 减少内存浪费。

#### AUTO_READ

```java
.childOption(ChannelOption.AUTO_READ, true)
```

- **作用**：是否自动读取数据。设为 true 时，Netty 会自动注册读事件；设为 false 时，需要手动调用 `channel.read()` 才会触发读取。
- **使用经验**：默认 true 即可。设为 false 可以实现精细的流量控制（背压），但编程复杂度大幅增加。只有在需要严格控制读取速率的场景（如代理服务器）才考虑关闭。

### 3.4 option() vs childOption() 的区别

这是新手最容易混淆的点：

| 方法 | 作用对象 | 典型参数 |
|------|----------|----------|
| `option()` | ServerSocketChannel（boss） | SO_BACKLOG, SO_REUSEADDR |
| `childOption()` | SocketChannel（worker/每个连接） | SO_KEEPALIVE, TCP_NODELAY, WRITE_BUFFER_WATER_MARK |

**记忆技巧**：`option()` 配置的是"监听套接字"的参数，`childOption()` 配置的是"每个客户端连接"的参数。

---

## 四、ChannelPipeline 配置详解

### 4.1 Pipeline 的本质

Pipeline 是一个双向链表，由多个 Handler 组成。数据入站（Inbound）从头到尾流经 InboundHandler，数据出站（Outbound）从尾到头流经 OutboundHandler。

```
                          I/O Request
                            via Channel or
                        ChannelHandlerContext
                                  |
  +---------------------------------------------------+
  |                  ChannelPipeline                   |
  |                                                   |
  |    +-----+  +---------+  +---------+  +-----+    |
  |    | Head |->| Handler |->| Handler |->| Tail |   |
  |    +-----+  +---------+  +---------+  +-----+    |
  |   Inbound ===============================>        |
  |   Outbound <==============================        |
  +---------------------------------------------------+
```

### 4.2 Handler 的顺序至关重要

Pipeline 中 Handler 的添加顺序直接决定了数据处理流程，顺序错误是 Netty 开发中最常见的 Bug 来源。

```java
// 典型的服务端 Pipeline 配置
ch.pipeline()
    // 1. 编解码器（最先处理原始字节）
    .addLast("frameDecoder", new LengthFieldBasedFrameDecoder(65535, 0, 4, 0, 4))
    .addLast("frameEncoder", new LengthFieldPrepender(4))
    
    // 2. 序列化/反序列化
    .addLast("decoder", new ProtobufDecoder(MyMessage.getDefaultInstance()))
    .addLast("encoder", new ProtobufEncoder())
    
    // 3. 空闲检测
    .addLast("idleStateHandler", new IdleStateHandler(60, 0, 0))
    
    // 4. 业务处理器（最后处理业务逻辑）
    .addLast("businessHandler", new MyBusinessHandler());
```

**使用经验：**

- 编解码器必须放在最前面，因为它们负责将原始字节流转换为有意义的消息对象。
- Inbound Handler 的执行顺序是从前到后（addLast 的顺序），Outbound Handler 的执行顺序是从后到前。
- 如果一个 Handler 同时实现了 Inbound 和 Outbound（如 `ChannelDuplexHandler`），它在两个方向上的位置是相同的。
- 调试 Pipeline 问题时，可以添加 `LoggingHandler` 来观察数据在各个节点的变化。

### 4.3 ChannelInitializer 的作用

`ChannelInitializer` 是一个特殊的 Handler，它在 Channel 注册到 EventLoop 后被调用一次，用于初始化 Pipeline，然后自动从 Pipeline 中移除自己。

```java
.childHandler(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        // 这里配置每个新连接的 Pipeline
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new MyHttpHandler());
    }
});
```

**使用经验**：不要在 `ChannelInitializer` 外部共享有状态的 Handler 实例（除非标注了 `@Sharable`）。每个连接应该有自己独立的 Handler 实例，否则会出现线程安全问题。

---

## 五、常用 Handler 详解

### 5.1 编解码器（Codec）

#### LengthFieldBasedFrameDecoder —— 最通用的拆包器

```java
new LengthFieldBasedFrameDecoder(
    maxFrameLength,      // 最大帧长度，超过则丢弃
    lengthFieldOffset,   // 长度字段的偏移量
    lengthFieldLength,   // 长度字段本身的字节数
    lengthAdjustment,    // 长度的修正值
    initialBytesToStrip  // 解码后跳过的字节数
)
```

**使用经验**：这是解决 TCP 粘包/拆包问题的首选方案。理解它的 5 个参数是关键：

```
假设协议格式：[2字节魔数][4字节长度][N字节数据]

new LengthFieldBasedFrameDecoder(
    65535,  // 最大帧长度
    2,      // 长度字段从第2个字节开始（跳过魔数）
    4,      // 长度字段占4个字节
    0,      // 长度值就是数据部分的长度，无需修正
    6       // 解码后跳过前6个字节（魔数+长度），只保留数据部分
)
```

#### 其他常用编解码器

| 编解码器 | 适用场景 | 说明 |
|----------|----------|------|
| `LineBasedFrameDecoder` | 文本协议（按行分割） | 以 `\n` 或 `\r\n` 为分隔符 |
| `DelimiterBasedFrameDecoder` | 自定义分隔符协议 | 可指定任意分隔符 |
| `FixedLengthFrameDecoder` | 定长协议 | 每个消息固定长度 |
| `HttpServerCodec` | HTTP 协议 | 组合了请求解码器和响应编码器 |
| `WebSocketServerProtocolHandler` | WebSocket | 处理握手和帧编解码 |
| `SslHandler` | TLS/SSL 加密 | 必须放在 Pipeline 最前面 |

### 5.2 IdleStateHandler —— 空闲检测

```java
new IdleStateHandler(readerIdleTime, writerIdleTime, allIdleTime, TimeUnit.SECONDS)
```

- **readerIdleTime**：读空闲时间，超过这个时间没有收到数据，触发 `READER_IDLE` 事件。
- **writerIdleTime**：写空闲时间，超过这个时间没有发送数据，触发 `WRITER_IDLE` 事件。
- **allIdleTime**：读写空闲时间，超过这个时间既没有读也没有写，触发 `ALL_IDLE` 事件。

**使用经验（心跳机制最佳实践）：**

```java
// 服务端：检测读空闲（客户端是否还活着）
ch.pipeline().addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
ch.pipeline().addLast(new HeartbeatServerHandler());

// 客户端：定时发送心跳（保持连接活跃）
ch.pipeline().addLast(new IdleStateHandler(0, 30, 0, TimeUnit.SECONDS));
ch.pipeline().addLast(new HeartbeatClientHandler());

// 服务端心跳处理
public class HeartbeatServerHandler extends ChannelInboundHandlerAdapter {
    private int lossCount = 0;
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            lossCount++;
            if (lossCount > 3) {
                // 连续3次没收到心跳，关闭连接
                ctx.close();
            }
        }
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        lossCount = 0; // 收到任何消息都重置计数
        super.channelRead(ctx, msg);
    }
}
```

**经验总结**：客户端写空闲时间应该小于服务端读空闲时间的一半，这样即使丢失一次心跳，下一次心跳也能在服务端超时前到达。

### 5.3 LoggingHandler —— 调试利器

```java
ch.pipeline().addLast(new LoggingHandler(LogLevel.DEBUG));
```

**使用经验**：开发阶段放在 Pipeline 的不同位置，可以观察数据在各个 Handler 之间的变化。生产环境记得移除或设为更高日志级别。

### 5.4 流量整形 Handler

```java
// 全局流量整形（所有 Channel 共享限速）
GlobalTrafficShapingHandler globalTraffic = 
    new GlobalTrafficShapingHandler(workerGroup, 
        writeLimit,   // 写速率限制（字节/秒），0 表示不限制
        readLimit);   // 读速率限制（字节/秒），0 表示不限制

// 单 Channel 流量整形
ChannelTrafficShapingHandler channelTraffic = 
    new ChannelTrafficShapingHandler(writeLimit, readLimit);
```

**使用经验**：流量整形在代理服务器、网关等场景非常有用。注意它是通过延迟读写来实现限速的，会增加延迟。

---

## 六、内存管理配置

### 6.1 ByteBuf 分配策略

Netty 的 ByteBuf 有四种组合：

| 类型 | 堆内存（Heap） | 直接内存（Direct） |
|------|----------------|-------------------|
| 池化（Pooled） | PooledHeapByteBuf | PooledDirectByteBuf |
| 非池化（Unpooled） | UnpooledHeapByteBuf | UnpooledDirectByteBuf |

**使用经验：**

- 生产环境使用 **池化 + 直接内存**（默认配置），性能最好。池化减少 GC，直接内存减少一次内存拷贝（零拷贝）。
- 直接内存不受 JVM GC 管理，如果有内存泄漏会更难排查。务必确保每个 ByteBuf 都被正确释放。
- 通过 JVM 参数控制：`-Dio.netty.allocator.type=pooled`（默认）或 `unpooled`。

### 6.2 内存泄漏检测

```java
// JVM 启动参数
-Dio.netty.leakDetection.level=PARANOID  // 开发环境
-Dio.netty.leakDetection.level=SIMPLE    // 生产环境（默认）
```

| 级别 | 说明 | 性能影响 |
|------|------|----------|
| DISABLED | 关闭检测 | 无 |
| SIMPLE | 采样检测（约 1%） | 极小 |
| ADVANCED | 采样检测 + 详细堆栈 | 小 |
| PARANOID | 检测所有 ByteBuf | 大，仅用于开发 |

**使用经验**：开发阶段务必开启 PARANOID 级别。内存泄漏是 Netty 应用最常见的生产事故之一。常见泄漏场景：

- Handler 中读取了消息但没有调用 `ReferenceCountUtil.release(msg)` 或传递给下一个 Handler。
- 异常处理路径中忘记释放 ByteBuf。
- 使用 `ctx.write()` 但没有 `flush()`，且后续连接关闭时缓冲区中的消息未被释放。

```java
// 正确的 Handler 实现模式
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    try {
        // 处理消息
        ByteBuf buf = (ByteBuf) msg;
        // ... 业务逻辑
    } finally {
        ReferenceCountUtil.release(msg); // 确保释放
    }
}
```

---

## 七、线程模型配置

### 7.1 Reactor 线程模型

Netty 支持三种 Reactor 模型：

```java
// 1. 单线程模型（不推荐生产使用）
EventLoopGroup group = new NioEventLoopGroup(1);
b.group(group, group);

// 2. 多线程模型（boss 单线程，worker 多线程）—— 最常用
EventLoopGroup bossGroup = new NioEventLoopGroup(1);
EventLoopGroup workerGroup = new NioEventLoopGroup(); // 默认 CPU*2
b.group(bossGroup, workerGroup);

// 3. 主从多线程模型（boss 多线程，worker 多线程）
EventLoopGroup bossGroup = new NioEventLoopGroup(4);
EventLoopGroup workerGroup = new NioEventLoopGroup();
b.group(bossGroup, workerGroup);
```

**使用经验**：99% 的场景使用模型 2 即可。模型 3 在端口非常多或连接建立速率极高时才有意义。

### 7.2 业务线程池

当 Handler 中有耗时操作（数据库查询、RPC 调用、复杂计算）时，不应该在 EventLoop 线程中执行，否则会阻塞整个 EventLoop 上的所有 Channel。

```java
// 方案一：使用 Netty 提供的 EventExecutorGroup（推荐）
EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(16);
ch.pipeline().addLast(businessGroup, "businessHandler", new MyBusinessHandler());

// 方案二：在 Handler 内部使用自定义线程池
public class MyBusinessHandler extends SimpleChannelInboundHandlerAdapter {
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        executor.submit(() -> {
            // 耗时业务逻辑
            String result = doBusinessLogic(msg);
            ctx.writeAndFlush(Unpooled.copiedBuffer(result, CharsetUtil.UTF_8));
        });
    }
}
```

推荐方案一，因为 Netty 的 EventExecutorGroup 与 Pipeline 的线程模型集成更好，能保证同一 Channel 的事件顺序性。

---

## 八、实战配置模板

### 8.1 高性能 TCP 服务端模板

```java
ServerBootstrap bootstrap = new ServerBootstrap();
bootstrap.group(bossGroup, workerGroup)
    .channel(EpollServerSocketChannel.class)  // Linux 下使用 Epoll
    // --- Server Channel Options ---
    .option(ChannelOption.SO_BACKLOG, 1024)
    .option(ChannelOption.SO_REUSEADDR, true)
    // --- Child Channel Options ---
    .childOption(ChannelOption.SO_KEEPALIVE, true)
    .childOption(ChannelOption.TCP_NODELAY, true)
    .childOption(ChannelOption.SO_SNDBUF, 65536)
    .childOption(ChannelOption.SO_RCVBUF, 65536)
    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
    .childOption(ChannelOption.RCVBUF_ALLOCATOR, new AdaptiveRecvByteBufAllocator())
    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 64 * 1024))
    .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        protected void initChannel(SocketChannel ch) {
            ChannelPipeline p = ch.pipeline();
            // 空闲检测
            p.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
            // 编解码
            p.addLast(new LengthFieldBasedFrameDecoder(1048576, 0, 4, 0, 4));
            p.addLast(new LengthFieldPrepender(4));
            p.addLast(new StringDecoder(CharsetUtil.UTF_8));
            p.addLast(new StringEncoder(CharsetUtil.UTF_8));
            // 业务处理
            p.addLast(businessGroup, new MyBusinessHandler());
        }
    });
```

### 8.2 HTTP 服务端模板

```java
bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        // SSL（可选）
        // p.addLast(sslCtx.newHandler(ch.alloc()));
        // HTTP 编解码
        p.addLast(new HttpServerCodec());
        // 聚合 HTTP 消息
        p.addLast(new HttpObjectAggregator(65536));
        // 压缩
        p.addLast(new HttpContentCompressor());
        // 大文件传输支持
        p.addLast(new ChunkedWriteHandler());
        // 业务处理
        p.addLast(new MyHttpHandler());
    }
});
```

### 8.3 WebSocket 服务端模板

```java
bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new WebSocketServerCompressionHandler());
        p.addLast(new WebSocketServerProtocolHandler("/ws", null, true));
        p.addLast(new MyWebSocketFrameHandler());
    }
});
```

---

# 第二部分：高级用法

> 以下内容聚焦 Netty 中那些"知道就是降维打击，不知道就踩坑到死"的高级特性。每个特性都从原理、API 使用、实战场景三个维度展开。

---

## 九、背压（Backpressure）

### 9.1 什么是背压

背压是指当数据生产速度超过消费速度时，系统需要一种机制来"反向施压"，让生产者慢下来，避免内存无限增长最终 OOM。

在 Netty 中，典型的背压场景是：上游数据写入速度远大于网络发送速度，数据堆积在 ChannelOutboundBuffer（写缓冲区）中。

### 9.2 Netty 的背压机制

Netty 通过 **WriteBufferWaterMark**（写缓冲区水位线）实现背压：

```java
// 设置高低水位
.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, 
    new WriteBufferWaterMark(32 * 1024, 64 * 1024))  // 低水位 32KB，高水位 64KB
```

当 ChannelOutboundBuffer 中待发送的字节数超过高水位时，`channel.isWritable()` 返回 false；当降到低水位以下时，恢复为 true，并触发 `channelWritabilityChanged` 事件。

### 9.3 完整的背压实现

```java
public class BackpressureHandler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 写之前检查是否可写
        if (ctx.channel().isWritable()) {
            ctx.writeAndFlush(processMessage(msg));
        } else {
            // 方案1：丢弃消息（适合可丢失的场景，如监控数据）
            ReferenceCountUtil.release(msg);
            
            // 方案2：暂停读取，等缓冲区消化后再恢复
            // ctx.channel().config().setAutoRead(false);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // 缓冲区恢复可写，重新开启读取
        if (ctx.channel().isWritable()) {
            ctx.channel().config().setAutoRead(true);
        }
        ctx.fireChannelWritabilityChanged();
    }
}
```

### 9.4 代理场景的背压（最复杂的情况）

在代理服务器中，前端连接和后端连接的速度可能不匹配，需要双向背压：

```java
public class ProxyBackendHandler extends ChannelInboundHandlerAdapter {
    private final Channel frontendChannel;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // 后端读到数据，写给前端
        if (frontendChannel.isWritable()) {
            frontendChannel.writeAndFlush(msg);
        } else {
            // 前端写不动了，暂停从后端读取
            ReferenceCountUtil.release(msg);
            ctx.channel().config().setAutoRead(false);
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // 后端的写缓冲区状态变化 -> 控制前端的读取
        boolean writable = ctx.channel().isWritable();
        frontendChannel.config().setAutoRead(writable);
    }
}
```

### 9.5 使用经验

- 永远不要无脑 `writeAndFlush`，生产环境必须检查 `isWritable()`。
- 低水位和高水位的差值不要太小，否则会频繁触发 writability 变化，造成抖动。
- 如果用了 `setAutoRead(false)` 暂停读取，一定要有恢复机制，否则连接会永远卡住。
- 监控 `ChannelOutboundBuffer` 的大小是排查内存泄漏的重要手段。

---

## 十、时间轮（HashedWheelTimer）

### 10.1 为什么需要时间轮

传统的定时任务方案（如 `ScheduledExecutorService`）使用优先队列管理定时任务，插入和删除的时间复杂度是 O(log n)。当系统中有海量定时任务时（比如每个连接都有超时检测），性能会成为瓶颈。

时间轮将时间划分为固定数量的"格子"（tick），任务按照到期时间散列到对应的格子中，插入和删除的时间复杂度降为 O(1)。

### 10.2 HashedWheelTimer 的结构

```
        +---+
     +--| 0 |--+        每个格子是一个链表
     |  +---+  |        存放该时刻到期的任务
     |         |
  +--+--+   +--+--+
  |  7  |   |  1  |
  +--+--+   +--+--+
     |         |
  +--+--+   +--+--+     指针每 tickDuration 转一格
  |  6  |   |  2  |     转到某格时，执行该格中所有到期任务
  +--+--+   +--+--+
     |         |
  +--+--+   +--+--+
  |  5  |   |  3  |
  +--+--+   +--+--+
     |  +---+  |
     +--| 4 |--+
        +---+
         ^
       当前指针
```

### 10.3 基本使用

```java
// 创建时间轮
HashedWheelTimer timer = new HashedWheelTimer(
    Executors.defaultThreadFactory(),
    100, TimeUnit.MILLISECONDS,  // tickDuration：每格的时间精度
    512                          // ticksPerWheel：格子数量（会向上取2的幂）
);

// 提交延迟任务
Timeout timeout = timer.newTimeout(task -> {
    System.out.println("任务到期执行！");
}, 5, TimeUnit.SECONDS);

// 取消任务
timeout.cancel();

// 关闭时间轮（优雅关闭）
timer.stop();
```

### 10.4 连接超时检测（经典应用）

每个连接都需要超时检测，如果用 ScheduledExecutorService，10 万连接就是 10 万个定时任务在优先队列中。时间轮可以轻松应对：

```java
public class ConnectionTimeoutManager {
    private final HashedWheelTimer timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS, 512);
    private final ConcurrentHashMap<Channel, Timeout> timeoutMap = new ConcurrentHashMap<>();

    // 连接建立时，注册超时检测
    public void onConnect(Channel channel, long timeoutMs) {
        Timeout timeout = timer.newTimeout(t -> {
            if (channel.isActive()) {
                channel.close();
                System.out.println("连接超时关闭: " + channel.remoteAddress());
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        timeoutMap.put(channel, timeout);
    }

    // 收到心跳时，重置超时
    public void onHeartbeat(Channel channel, long timeoutMs) {
        Timeout old = timeoutMap.get(channel);
        if (old != null) {
            old.cancel();
        }
        onConnect(channel, timeoutMs);
    }

    // 连接关闭时，取消超时
    public void onDisconnect(Channel channel) {
        Timeout timeout = timeoutMap.remove(channel);
        if (timeout != null) {
            timeout.cancel();
        }
    }
}
```

### 10.5 延迟重试（另一个经典场景）

```java
public class RetryHandler extends ChannelInboundHandlerAdapter {
    private final HashedWheelTimer timer;
    private final Bootstrap bootstrap;
    private int retryCount = 0;
    private static final int MAX_RETRY = 5;

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (retryCount < MAX_RETRY) {
            long delay = Math.min(1000 * (1L << retryCount), 30000); // 指数退避，最大30秒
            timer.newTimeout(t -> {
                retryCount++;
                bootstrap.connect().addListener(future -> {
                    if (!future.isSuccess()) {
                        // 连接失败，继续重试（会再次触发 channelInactive）
                    }
                });
            }, delay, TimeUnit.MILLISECONDS);
        }
    }
}
```

### 10.6 使用经验

- **tickDuration 的选择**：决定了时间精度。设为 100ms 意味着任务的实际执行时间可能有 +/-100ms 的误差。对精度要求高的场景（如金融交易）不适合用时间轮。
- **ticksPerWheel 的选择**：格子越多，哈希冲突越少，但内存占用越大。通常 512 或 1024 即可。
- **全局共享**：HashedWheelTimer 内部只有一个线程，不要为每个连接创建一个 Timer。应该全局共享一个或少数几个实例。
- **任务不要阻塞**：时间轮的工作线程是单线程的，如果某个任务执行时间过长，会阻塞后续所有任务的执行。耗时操作应该提交到独立线程池。
- **Netty 内部的 IdleStateHandler 就是基于时间轮实现的**，所以你不需要自己用时间轮来做空闲检测。

### 10.7 时间轮 vs ScheduledExecutorService

| 维度 | HashedWheelTimer | ScheduledExecutorService |
|------|-----------------|--------------------------|
| 插入/删除复杂度 | O(1) | O(log n) |
| 时间精度 | 受 tickDuration 限制 | 精确 |
| 适用场景 | 海量粗精度定时任务 | 少量精确定时任务 |
| 线程模型 | 单线程 | 可配置线程池 |
| 内存占用 | 固定（格子数 x 链表） | 随任务数增长 |

---

## 十一、零拷贝（Zero-Copy）

### 11.1 传统 I/O 的拷贝问题

传统文件传输需要 4 次数据拷贝和 4 次上下文切换：

```
磁盘 -> 内核缓冲区 -> 用户空间 -> Socket缓冲区 -> 网卡
       (DMA拷贝)    (CPU拷贝)   (CPU拷贝)    (DMA拷贝)
```

零拷贝的目标是减少中间的 CPU 拷贝。

### 11.2 Netty 的零拷贝体系

Netty 的零拷贝分为两个层面：**操作系统层面**和**应用层面**。

#### 操作系统层面：FileRegion（sendfile 系统调用）

```java
// 文件直接传输，不经过用户空间
// 底层使用 sendfile() 系统调用（Linux）或 transferTo()（Java NIO）
public class FileServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        File file = new File("/path/to/large-file.dat");
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        
        // 零拷贝传输：数据直接从文件描述符传到 Socket，不经过用户空间
        FileRegion region = new DefaultFileRegion(raf.getChannel(), 0, raf.length());
        ctx.writeAndFlush(region).addListener(future -> {
            raf.close();
        });
    }
}
```

**注意**：FileRegion 不能和 SSL/TLS 一起使用（因为加密需要在用户空间处理数据）。如果需要加密传输大文件，使用 `ChunkedWriteHandler` + `ChunkedNioFile`。

#### 应用层面：CompositeByteBuf（逻辑合并）

传统方式合并两个 Buffer 需要创建新 Buffer 并拷贝数据：

```java
// 传统方式：需要内存拷贝
ByteBuf header = ...;
ByteBuf body = ...;
ByteBuf all = Unpooled.buffer(header.readableBytes() + body.readableBytes());
all.writeBytes(header);
all.writeBytes(body);  // 两次拷贝！
```

CompositeByteBuf 通过维护一个 Buffer 列表，实现逻辑上的合并而无需物理拷贝：

```java
// 零拷贝方式：无内存拷贝
CompositeByteBuf composite = Unpooled.compositeBuffer();
composite.addComponents(true, header, body);  // 只是添加引用，不拷贝数据
// 对外表现为一个连续的 ByteBuf
```

#### 应用层面：slice / duplicate（共享底层数据）

```java
ByteBuf original = Unpooled.buffer(100);
original.writeBytes("Hello, World!".getBytes());

// slice：创建一个子区域视图，共享底层数组，不拷贝
ByteBuf slice = original.slice(0, 5);  // "Hello"
// slice 和 original 共享同一块内存

// duplicate：创建整个 Buffer 的视图
ByteBuf dup = original.duplicate();
// dup 和 original 共享内存，但有独立的 readerIndex/writerIndex

// retainedSlice / retainedDuplicate：同上，但会增加引用计数（更安全）
ByteBuf retained = original.retainedSlice(0, 5);
// 用完后需要 release
retained.release();
```

#### 应用层面：Unpooled.wrappedBuffer（包装已有数组）

```java
byte[] bytes = "Hello".getBytes();
// 直接包装，不拷贝。ByteBuf 直接引用原始数组
ByteBuf wrapped = Unpooled.wrappedBuffer(bytes);

// 包装多个数组，底层使用 CompositeByteBuf
ByteBuf composite = Unpooled.wrappedBuffer(bytes1, bytes2, bytes3);
```

### 11.3 直接内存（Direct Memory）减少一次拷贝

使用堆内存（HeapByteBuf）发送数据时，JVM 需要先将数据拷贝到直接内存，再由操作系统发送。使用直接内存（DirectByteBuf）可以省去这一步：

```
HeapByteBuf:   堆内存 -> 直接内存 -> Socket缓冲区 -> 网卡
DirectByteBuf: 直接内存 -> Socket缓冲区 -> 网卡（少一次拷贝）
```

Netty 默认使用 PooledDirectByteBufAllocator，已经是最优选择。

### 11.4 使用经验

- 大文件传输优先用 `FileRegion`，小消息合并优先用 `CompositeByteBuf`。
- `slice()` 返回的 ByteBuf 与原始 ByteBuf 共享引用计数，释放原始 ByteBuf 后 slice 也不可用。如果需要独立生命周期，用 `retainedSlice()` 并记得单独 release。
- `CompositeByteBuf` 的读取性能略低于连续内存的 ByteBuf（因为需要跨组件定位），如果后续需要频繁随机访问，可以考虑 `copy()` 为连续内存。
- 零拷贝不是银弹，只有在数据量较大时收益才明显。几十字节的小消息，拷贝的开销可以忽略。

---

## 十二、ChannelFuture 与 Promise（异步编程模型）

### 12.1 Netty 的异步哲学

Netty 中几乎所有 I/O 操作都是异步的。`write()`、`connect()`、`bind()` 都不会立即完成，而是返回一个 `ChannelFuture`，你可以通过它来获取操作结果。

### 12.2 ChannelFuture 的正确使用

```java
// 错误用法：同步等待（阻塞 EventLoop 线程！）
ChannelFuture future = ctx.writeAndFlush(msg);
future.sync();  // 千万不要在 Handler 中这样做

// 正确用法：注册监听器
ctx.writeAndFlush(msg).addListener((ChannelFutureListener) future -> {
    if (future.isSuccess()) {
        System.out.println("发送成功");
    } else {
        System.err.println("发送失败: " + future.cause());
        future.channel().close();
    }
});

// 使用预定义的监听器
ctx.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE);          // 写完就关闭
ctx.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE); // 失败才关闭
```

### 12.3 Promise —— 可写的 Future

`ChannelPromise` 是 `ChannelFuture` 的可写版本，你可以主动设置它的结果：

```java
public class MyOutboundHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        // 自定义写逻辑
        try {
            Object processed = transform(msg);
            ctx.write(processed, promise);  // 传递 promise 给下一个 handler
        } catch (Exception e) {
            promise.setFailure(e);  // 主动标记失败
        }
    }
}

// 创建自定义 Promise
ChannelPromise promise = ctx.newPromise();
promise.addListener(future -> {
    // 当 promise 被 setSuccess/setFailure 时触发
});
ctx.writeAndFlush(msg, promise);
```

### 12.4 异步编排：多个 Future 的组合

```java
// 等待多个操作全部完成
ChannelFuture f1 = channel1.writeAndFlush(msg1);
ChannelFuture f2 = channel2.writeAndFlush(msg2);

// 方案：使用 PromiseCombiner
PromiseCombiner combiner = new PromiseCombiner(ctx.executor());
combiner.add(f1);
combiner.add(f2);
ChannelPromise aggregatePromise = ctx.newPromise();
combiner.finish(aggregatePromise);
aggregatePromise.addListener(future -> {
    if (future.isSuccess()) {
        System.out.println("所有消息发送完成");
    }
});
```

### 12.5 使用经验

- 永远不要在 EventLoop 线程中调用 `sync()` 或 `await()`，会导致死锁。
- `addListener` 的回调默认在 EventLoop 线程中执行，不要在里面做耗时操作。
- 如果需要在非 EventLoop 线程等待结果，可以用 `sync()` 或 `await()`，但要注意超时。
- `writeAndFlush` 返回的 Future 表示数据已经写入 Socket 缓冲区，不代表对端已经收到。

---

## 十三、内存池（PooledByteBufAllocator）深入

### 13.1 为什么需要内存池

每次 I/O 操作都需要分配 ByteBuf，如果每次都 malloc/free，会产生大量内存碎片和 GC 压力。Netty 的内存池借鉴了 jemalloc 的设计思想，通过预分配和复用来解决这个问题。

### 13.2 内存池的层次结构

```
PooledByteBufAllocator
+-- PoolThreadCache（线程本地缓存，无锁分配）
|   +-- tinySubPagesCaches    (< 512B)
|   +-- smallSubPagesCaches   (512B ~ 8KB)
|   +-- normalCaches          (8KB ~ 16MB)
+-- PoolArena（竞技场，多线程共享）
|   +-- PoolChunk（16MB 大块内存）
|   |   +-- PoolSubpage（细分为更小的块）
|   +-- ...
+-- ...
```

### 13.3 关键配置参数

```java
// 通过系统属性配置
-Dio.netty.allocator.numHeapArenas=16        // 堆内存 Arena 数量（默认 CPU*2）
-Dio.netty.allocator.numDirectArenas=16      // 直接内存 Arena 数量（默认 CPU*2）
-Dio.netty.allocator.pageSize=8192           // 页大小（默认 8KB）
-Dio.netty.allocator.maxOrder=11             // 最大阶数（Chunk = pageSize << maxOrder = 16MB）
-Dio.netty.allocator.tinyCacheSize=512       // 线程缓存中 tiny 类型的缓存数量
-Dio.netty.allocator.smallCacheSize=256      // 线程缓存中 small 类型的缓存数量
-Dio.netty.allocator.normalCacheSize=64      // 线程缓存中 normal 类型的缓存数量
```

### 13.4 内存池的监控

```java
PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;

// 获取内存池指标
PooledByteBufAllocatorMetric metric = allocator.metric();
System.out.println("已使用堆内存: " + metric.usedHeapMemory());
System.out.println("已使用直接内存: " + metric.usedDirectMemory());
System.out.println("堆内存 Arena 数: " + metric.numHeapArenas());
System.out.println("直接内存 Arena 数: " + metric.numDirectArenas());

// 每个 Arena 的详细信息
for (PoolArenaMetric arena : metric.directArenas()) {
    System.out.println("活跃分配数: " + arena.numActiveAllocations());
    System.out.println("活跃 Chunk 数: " + arena.numChunkLists());
}
```

### 13.5 使用经验

- 池化分配器的 ByteBuf 必须手动释放（`release()`），否则内存永远不会归还到池中。
- 线程本地缓存（PoolThreadCache）会在线程退出时自动清理，但如果线程池中的线程长期不退出，缓存可能占用大量内存。可以通过 `-Dio.netty.allocator.cacheTrimIntervalMillis` 设置定期清理间隔。
- 直接内存不受 `-Xmx` 限制，受 `-XX:MaxDirectMemorySize` 限制。生产环境务必设置这个参数，否则可能耗尽系统内存。

---

## 十四、EventLoop 的任务调度

### 14.1 提交普通任务

```java
// 在 EventLoop 线程中执行（线程安全）
channel.eventLoop().execute(() -> {
    // 这里的代码一定在该 Channel 所属的 EventLoop 线程中执行
    channel.writeAndFlush(msg);
});
```

### 14.2 提交定时任务

```java
// 延迟执行
channel.eventLoop().schedule(() -> {
    System.out.println("5秒后执行");
}, 5, TimeUnit.SECONDS);

// 周期执行
channel.eventLoop().scheduleAtFixedRate(() -> {
    System.out.println("每10秒执行一次");
}, 0, 10, TimeUnit.SECONDS);
```

### 14.3 判断当前线程

```java
// 判断当前是否在 EventLoop 线程中
if (channel.eventLoop().inEventLoop()) {
    // 直接执行，无需切换线程
    doSomething();
} else {
    // 不在 EventLoop 线程，提交任务
    channel.eventLoop().execute(() -> doSomething());
}
```

### 14.4 使用经验

- Netty 保证同一个 Channel 的所有事件都在同一个 EventLoop 线程中处理，所以 Handler 中不需要加锁。
- 但如果你在 Handler 中引用了共享资源（如全局 Map），仍然需要考虑线程安全。
- `execute()` 提交的任务会排队执行，如果前面的任务耗时过长，后面的任务会被延迟。这就是为什么不能在 EventLoop 中做阻塞操作。

---

## 十五、优雅关闭（Graceful Shutdown）

### 15.1 为什么需要优雅关闭

直接关闭可能导致：正在处理的请求被中断、写缓冲区中的数据丢失、客户端收到连接重置错误。

### 15.2 完整的优雅关闭流程

```java
public class GracefulShutdown {
    private final ServerBootstrap bootstrap;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private Channel serverChannel;

    public void start() throws InterruptedException {
        ChannelFuture f = bootstrap.bind(8080).sync();
        serverChannel = f.channel();
        
        // 注册 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public void shutdown() {
        // 1. 停止接受新连接
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }

        // 2. 优雅关闭 EventLoopGroup
        //    - 等待已提交的任务执行完毕
        //    - quietPeriod 内没有新任务则关闭
        //    - 最多等待 timeout 时间
        Future<?> bossFuture = bossGroup.shutdownGracefully(2, 15, TimeUnit.SECONDS);
        Future<?> workerFuture = workerGroup.shutdownGracefully(2, 15, TimeUnit.SECONDS);

        // 3. 等待关闭完成
        bossFuture.syncUninterruptibly();
        workerFuture.syncUninterruptibly();
    }
}
```

### 15.3 shutdownGracefully 的参数含义

```java
shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit)
```

- **quietPeriod**：静默期。关闭过程中如果在这段时间内没有新任务提交，则认为可以安全关闭。如果有新任务，则重新计时。
- **timeout**：最大等待时间。无论是否还有任务，超过这个时间强制关闭。

### 15.4 使用经验

- 永远不要用 `shutdownNow()`，它会丢弃未执行的任务。
- quietPeriod 设为 2 秒，timeout 设为 15 秒是比较合理的默认值。
- 在 Kubernetes 环境中，需要配合 preStop hook 和 terminationGracePeriodSeconds 使用。

---

## 十六、ChannelGroup（连接管理）

### 16.1 批量管理连接

```java
public class ConnectionManager {
    // ChannelGroup 是线程安全的
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ChannelGroup vipChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    // 新连接加入
    public void onConnect(Channel channel, boolean isVip) {
        allChannels.add(channel);
        if (isVip) {
            vipChannels.add(channel);
        }
        // Channel 关闭时会自动从 ChannelGroup 中移除，无需手动管理
    }

    // 广播消息给所有连接
    public void broadcast(Object msg) {
        allChannels.writeAndFlush(msg);
    }

    // 广播给 VIP 用户
    public void broadcastToVip(Object msg) {
        vipChannels.writeAndFlush(msg);
    }

    // 优雅关闭所有连接
    public ChannelGroupFuture closeAll() {
        return allChannels.close();
    }

    // 获取在线连接数
    public int getOnlineCount() {
        return allChannels.size();
    }
}
```

### 16.2 使用经验

- ChannelGroup 会自动清理已关闭的 Channel，不会内存泄漏。
- `writeAndFlush` 是异步的，返回 `ChannelGroupFuture` 可以监听所有 Channel 的写入结果。
- 适合实现聊天室广播、推送通知、连接统计等场景。

---

## 十七、ByteBuf 的高级操作

### 17.1 引用计数（Reference Counting）

Netty 的 ByteBuf 使用引用计数管理生命周期：

```java
ByteBuf buf = ctx.alloc().buffer();
System.out.println(buf.refCnt());  // 1

buf.retain();  // 引用计数 +1
System.out.println(buf.refCnt());  // 2

buf.release(); // 引用计数 -1
System.out.println(buf.refCnt());  // 1

buf.release(); // 引用计数归零，内存被回收
// 此后再访问 buf 会抛出 IllegalReferenceCountException
```

**谁最后使用，谁负责释放**的原则：

```java
// 如果 Handler 消费了消息（不传递给下一个 Handler），必须释放
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ByteBuf buf = (ByteBuf) msg;
    try {
        // 处理数据...
    } finally {
        buf.release();  // 我是最后一个使用者，我来释放
    }
}

// 如果传递给下一个 Handler，不要释放
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) {
    // 做一些处理...
    ctx.fireChannelRead(msg);  // 传递给下一个 Handler，由它负责释放
}
```

### 17.2 ByteBuf 的读写指针

```java
ByteBuf buf = Unpooled.buffer(256);

// ByteBuf 有两个独立的指针
// +-------------------+------------------+------------------+
// | discardable bytes |  readable bytes  |  writable bytes  |
// +-------------------+------------------+------------------+
// |                   |                  |                  |
// 0      <=      readerIndex   <=   writerIndex    <=    capacity

buf.writeInt(42);           // writerIndex += 4
buf.writeLong(123456L);     // writerIndex += 8
int value = buf.readInt();  // readerIndex += 4

// 标记和重置
buf.markReaderIndex();      // 记住当前读位置
buf.readLong();             // 读取数据
buf.resetReaderIndex();     // 回到标记位置，可以重新读取

// 丢弃已读字节，回收空间
buf.discardReadBytes();     // 将 readerIndex 之前的空间回收
```

### 17.3 ByteBuf 的派生操作对比

| 操作 | 是否拷贝 | 是否共享引用计数 | 独立读写指针 |
|------|----------|-----------------|-------------|
| `slice()` | 否 | 是 | 是 |
| `duplicate()` | 否 | 是 | 是 |
| `retainedSlice()` | 否 | 是（+1） | 是 |
| `copy()` | 是 | 否（独立） | 是 |
| `readSlice(len)` | 否 | 是 | 是 |

---

## 十八、自定义协议设计

### 18.1 协议设计模板

一个健壮的自定义协议通常包含以下字段：

```
+--------+--------+--------+--------+--------+--------+
| 魔数   | 版本号 | 序列化 | 命令   | 数据长度| 数据   |
| 4字节  | 1字节  | 1字节  | 1字节  | 4字节  | N字节  |
+--------+--------+--------+--------+--------+--------+
```

### 18.2 编码器实现

```java
public class MyProtocolEncoder extends MessageToByteEncoder<MyMessage> {
    private static final int MAGIC = 0xCAFEBABE;

    @Override
    protected void encode(ChannelHandlerContext ctx, MyMessage msg, ByteBuf out) {
        out.writeInt(MAGIC);                    // 魔数
        out.writeByte(msg.getVersion());        // 版本号
        out.writeByte(msg.getSerializeType());  // 序列化方式
        out.writeByte(msg.getCommand());        // 命令类型
        byte[] data = serialize(msg);
        out.writeInt(data.length);              // 数据长度
        out.writeBytes(data);                   // 数据
    }
}
```

### 18.3 解码器实现

```java
public class MyProtocolDecoder extends ByteToMessageDecoder {
    private static final int MAGIC = 0xCAFEBABE;
    private static final int HEADER_LENGTH = 11; // 4+1+1+1+4

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 可读字节不够一个头部，等待更多数据
        if (in.readableBytes() < HEADER_LENGTH) {
            return;
        }

        // 标记当前读位置，如果数据不完整可以回退
        in.markReaderIndex();

        // 校验魔数
        int magic = in.readInt();
        if (magic != MAGIC) {
            // 魔数不对，可能是非法连接
            ctx.close();
            return;
        }

        byte version = in.readByte();
        byte serializeType = in.readByte();
        byte command = in.readByte();
        int dataLength = in.readInt();

        // 数据部分还没到齐，回退等待
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }

        // 读取数据
        byte[] data = new byte[dataLength];
        in.readBytes(data);

        // 反序列化并输出
        MyMessage message = deserialize(data, serializeType);
        message.setVersion(version);
        message.setCommand(command);
        out.add(message);
    }
}
```

### 18.4 使用经验

- 魔数用于快速识别非法连接，收到错误魔数直接关闭连接。
- 版本号用于协议升级时的兼容性处理。
- `ByteToMessageDecoder` 内部有累积缓冲区，会自动处理粘包/拆包，你只需要判断数据是否足够即可。
- 解码器中一定要有长度校验，防止恶意客户端发送超大 dataLength 导致 OOM。

---

## 十九、SSL/TLS 加密

### 19.1 服务端 SSL 配置

```java
// 使用 Netty 的 SslContext 构建器
SslContext sslCtx = SslContextBuilder.forServer(
        new File("server.crt"),   // 证书
        new File("server.key"))   // 私钥
    .protocols("TLSv1.2", "TLSv1.3")
    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
    .build();

// 在 Pipeline 最前面添加 SslHandler
ch.pipeline().addFirst("ssl", sslCtx.newHandler(ch.alloc()));
```

### 19.2 客户端 SSL 配置

```java
SslContext sslCtx = SslContextBuilder.forClient()
    .trustManager(InsecureTrustManagerFactory.INSTANCE)  // 开发环境：信任所有证书
    // .trustManager(new File("ca.crt"))                 // 生产环境：指定 CA 证书
    .build();

ch.pipeline().addFirst("ssl", sslCtx.newHandler(ch.alloc(), host, port));
```

### 19.3 使用经验

- SslHandler 必须是 Pipeline 中的第一个 Handler（在所有其他 Handler 之前）。
- 使用 OpenSSL 引擎（`SslProvider.OPENSSL`）比 JDK 自带的 SSL 引擎性能高 2-3 倍。需要添加 `netty-tcnative` 依赖。
- SSL 握手是异步的，可以通过 `sslHandler.handshakeFuture()` 监听握手完成事件。

---

## 二十、Native Transport（原生传输）

### 20.1 为什么用原生传输

Java NIO 底层使用 `select/poll/epoll`，但经过了 JDK 的抽象层，有额外开销。Netty 的原生传输通过 JNI 直接调用系统调用，性能更好。

### 20.2 Linux Epoll

```xml
<!-- Maven 依赖 -->
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-epoll</artifactId>
    <classifier>linux-x86_64</classifier>
</dependency>
```

```java
// 替换为 Epoll 实现
EventLoopGroup bossGroup = new EpollEventLoopGroup(1);
EventLoopGroup workerGroup = new EpollEventLoopGroup();
bootstrap.channel(EpollServerSocketChannel.class);

// Epoll 特有的选项
bootstrap.childOption(EpollChannelOption.TCP_CORK, true);       // 合并小包
bootstrap.childOption(EpollChannelOption.TCP_QUICKACK, true);   // 快速 ACK
bootstrap.childOption(EpollChannelOption.SO_REUSEPORT, true);   // 端口复用（多线程 accept）
```

### 20.3 macOS KQueue

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-transport-native-kqueue</artifactId>
    <classifier>osx-x86_64</classifier>
</dependency>
```

```java
EventLoopGroup group = new KQueueEventLoopGroup();
bootstrap.channel(KQueueServerSocketChannel.class);
```

### 20.4 跨平台兼容写法

```java
public static EventLoopGroup createEventLoopGroup(int threads) {
    if (Epoll.isAvailable()) {
        return new EpollEventLoopGroup(threads);
    } else if (KQueue.isAvailable()) {
        return new KQueueEventLoopGroup(threads);
    } else {
        return new NioEventLoopGroup(threads);
    }
}

public static Class<? extends ServerSocketChannel> serverChannelClass() {
    if (Epoll.isAvailable()) {
        return EpollServerSocketChannel.class;
    } else if (KQueue.isAvailable()) {
        return KQueueServerSocketChannel.class;
    } else {
        return NioServerSocketChannel.class;
    }
}
```

### 20.5 SO_REUSEPORT 的威力

Linux 3.9+ 支持 SO_REUSEPORT，允许多个 Socket 绑定同一端口，内核自动做负载均衡：

```java
// 多个 EventLoop 各自 bind 同一端口
bootstrap.option(EpollChannelOption.SO_REUSEPORT, true);
// 这样每个 EventLoop 线程都有自己的 accept 队列，避免了惊群效应
```

---

## 二十一、DNS 异步解析

### 21.1 为什么需要异步 DNS

Java 默认的 `InetAddress.getByName()` 是阻塞的，如果在 EventLoop 中调用会阻塞整个线程。Netty 提供了异步 DNS 解析器。

### 21.2 使用方式

```java
// 创建异步 DNS 解析器
DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoop)
    .channelType(NioDatagramChannel.class)
    .queryTimeoutMillis(5000)
    .maxQueriesPerResolve(3);

DnsNameResolver resolver = builder.build();

// 异步解析
Future<InetAddress> future = resolver.resolve("www.example.com");
future.addListener(f -> {
    if (f.isSuccess()) {
        InetAddress address = (InetAddress) f.getNow();
        System.out.println("解析结果: " + address);
    } else {
        System.err.println("解析失败: " + f.cause());
    }
});
```

### 21.3 在 Bootstrap 中使用

```java
Bootstrap b = new Bootstrap();
b.resolver(new DnsAddressResolverGroup(
    NioDatagramChannel.class, 
    DnsServerAddressStreamProviders.platformDefault()));
```

---

## 二十二、HTTP/2 支持

### 22.1 HTTP/2 服务端配置

```java
// HTTP/2 需要 ALPN 协商
SslContext sslCtx = SslContextBuilder.forServer(cert, key)
    .sslProvider(SslProvider.OPENSSL)
    .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
    .applicationProtocolConfig(new ApplicationProtocolConfig(
        Protocol.ALPN,
        SelectorFailureBehavior.NO_ADVERTISE,
        SelectedListenerFailureBehavior.ACCEPT,
        ApplicationProtocolNames.HTTP_2,
        ApplicationProtocolNames.HTTP_1_1))
    .build();

ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
ch.pipeline().addLast(new ApplicationProtocolNegotiationHandler("") {
    @Override
    protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
            ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build());
            ctx.pipeline().addLast(new Http2MultiplexHandler(new MyHttp2Handler()));
        } else {
            ctx.pipeline().addLast(new HttpServerCodec());
            ctx.pipeline().addLast(new MyHttpHandler());
        }
    }
});
```

---

# 第三部分：总结与速查

---

## 二十三、配置参数速查表

| 参数 | 默认值 | 推荐值 | 说明 |
|------|--------|--------|------|
| SO_BACKLOG | 128 | 1024+ | 全连接队列大小 |
| SO_KEEPALIVE | false | true | TCP 保活 |
| TCP_NODELAY | false | true | 禁用 Nagle |
| SO_REUSEADDR | false | true | 地址复用 |
| SO_SNDBUF | 系统默认 | 64KB~256KB | 发送缓冲区 |
| SO_RCVBUF | 系统默认 | 64KB~256KB | 接收缓冲区 |
| CONNECT_TIMEOUT_MILLIS | 30000 | 5000~10000 | 连接超时 |
| WRITE_BUFFER_WATER_MARK | 32KB/64KB | 按业务调整 | 写缓冲水位 |
| ALLOCATOR | Pooled | PooledByteBufAllocator | 内存分配器 |
| AUTO_READ | true | true | 自动读取 |
| SINGLE_EVENTEXECUTOR_PER_GROUP | true | true | 线程亲和性 |

---

## 二十四、性能调优 Checklist

| 类别 | 调优项 | 建议 |
|------|--------|------|
| 系统参数 | 文件描述符 | ulimit -n 1000000 |
| 系统参数 | TCP 全连接队列 | somaxconn=65535 |
| 系统参数 | 直接内存限制 | -XX:MaxDirectMemorySize=2g |
| Netty 参数 | 传输实现 | Linux 用 Epoll，macOS 用 KQueue |
| Netty 参数 | 内存分配器 | PooledByteBufAllocator（默认） |
| Netty 参数 | TCP_NODELAY | true（低延迟场景） |
| Netty 参数 | SO_BACKLOG | 1024+ |
| 编码实践 | 不在 EventLoop 中阻塞 | 耗时操作提交到业务线程池 |
| 编码实践 | ByteBuf 及时释放 | 避免内存泄漏 |
| 编码实践 | 检查 isWritable() | 实现背压，防止 OOM |
| 编码实践 | 合理使用零拷贝 | CompositeByteBuf、FileRegion |
| 监控 | 内存泄漏检测 | 开发用 PARANOID，生产用 SIMPLE |
| 监控 | 连接数监控 | ChannelGroup.size() |
| 监控 | 写缓冲区监控 | ChannelOutboundBuffer 大小 |

---

## 二十五、全文总结

Netty 的知识体系可以从三个层面来理解：

**基础配置层面**——解决"怎么用"的问题：

1. **传输层配置（Option）**：控制底层 Socket 行为，影响连接建立、数据传输的基础特性
2. **处理链配置（Pipeline/Handler）**：定义数据处理流程，通过组合不同 Handler 实现协议编解码、业务逻辑分离
3. **资源配置（EventLoop/Allocator）**：管理线程模型和内存分配策略，决定系统的并发能力和资源利用率

**高级特性层面**——解决"怎么用好"的问题：

1. **异步非阻塞**：ChannelFuture/Promise 体系让所有 I/O 操作都不阻塞线程
2. **零拷贝**：从操作系统层（sendfile）到应用层（CompositeByteBuf），全方位减少数据拷贝
3. **内存池化**：借鉴 jemalloc 的分配策略，大幅降低 GC 压力
4. **时间轮**：O(1) 复杂度管理海量定时任务
5. **背压控制**：通过水位线机制防止生产者压垮消费者
6. **原生传输**：绕过 JDK 抽象层，直接调用系统调用获得极致性能

合理配置基础参数，灵活运用高级特性，结合具体业务场景进行调优，是构建高性能网络应用的关键。建议在开发阶段开启详细的日志和泄漏检测，在生产环境中根据监控数据逐步调整参数。
