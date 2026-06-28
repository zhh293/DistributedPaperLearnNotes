package org.example.自定义协议;

public class 处理器的共享问题 {
    public static void main(String[] args) throws Exception {

    }
}
/*
处理器共享的场景与问题根源
Netty 中，ChannelPipeline是处理器的容器，每个Channel（代表一个网络连接）都有自己的ChannelPipeline。当我们需要为多个Channel添加相同逻辑的处理器时，有两种选择：

为每个 Channel 创建独立的处理器实例：每个Channel的ChannelPipeline中是不同的ChannelHandler对象。
多个 Channel 共享同一个处理器实例：所有Channel的ChannelPipeline中引用同一个ChannelHandler对象。
问题的根源：线程安全
Netty 的EventLoop是处理 IO 事件的线程模型，每个EventLoop绑定一个线程，多个Channel可以共享一个EventLoop（但一个Channel只会绑定一个EventLoop）。当多个Channel共享同一个处理器实例时：

若处理器有状态（包含成员变量，且会被修改），多个EventLoop线程（或同一线程的不同Channel）可能同时操作这些成员变量，导致线程安全问题（如数据错乱、并发修改异常）。
若处理器无状态（没有成员变量，或成员变量是常量 / 线程安全的），理论上可以安全共享，但 Netty 默认禁止共享（需要显式声明）。
二、@Sharable 注解的作用
@Sharable是 Netty 提供的一个标记注解（无属性），用于告诉 Netty：该处理器实例可以被多个Channel的ChannelPipeline共享。
核心作用：
允许共享：默认情况下，Netty 禁止将同一个ChannelHandler实例添加到多个ChannelPipeline，若尝试添加会抛出ChannelPipelineException。添加@Sharable注解后，Netty 允许这种共享行为。
开发者承诺：注解本身不保证线程安全，它只是一个 "声明"—— 开发者承诺该处理器在多Channel共享时是线程安全的。
不加 @Sharable 时的错误示例：
java
        运行
public class MyHandler extends ChannelInboundHandlerAdapter {
    // 无@Sharable注解
}

// 创建一个处理器实例
MyHandler handler = new MyHandler();

// 为两个Channel添加同一个处理器实例
channel1.pipeline().addLast(handler);
channel2.pipeline().addLast(handler); // 抛出异常：ChannelPipelineException
三、可共享处理器的设计原则
要让处理器能安全共享（即适合添加@Sharable），必须满足线程安全，通常遵循以下原则：

无状态：处理器内部不包含任何成员变量（或仅包含常量）。
示例：一个简单的日志处理器，仅打印消息，无状态存储。
java
        运行
@Sharable
public class LogHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        System.out.println("收到消息：" + msg); // 无成员变量，安全共享
        ctx.fireChannelRead(msg);
    }
}

成员变量线程安全：若必须有成员变量，需使用线程安全的数据结构（如ConcurrentHashMap），或通过同步机制（synchronized、Lock）保证并发安全。
示例：统计所有连接数的处理器（需线程安全计数）：
java
        运行
@Sharable
public class ConnectionCounterHandler extends ChannelInboundHandlerAdapter {
    // 线程安全的计数器
    private final AtomicInteger connectionCount = new AtomicInteger(0);

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        int count = connectionCount.incrementAndGet();
        System.out.println("当前连接数：" + count);
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        int count = connectionCount.decrementAndGet();
        System.out.println("当前连接数：" + count);
        ctx.fireChannelInactive();
    }
}

避免依赖 Channel 相关状态：共享处理器不应依赖某个特定Channel的状态（如ChannelHandlerContext），因为它会被多个Channel共用。
四、不可共享的典型场景
以下情况的处理器不能共享（即使加@Sharable也会有线程安全问题）：

包含非线程安全的成员变量：如普通HashMap、ArrayList，或自定义的计数器（未加同步）。
java
        运行
// 错误示例：非线程安全的共享处理器
@Sharable // 错误使用！虽然加了注解，但存在线程安全问题
public class BadHandler extends ChannelInboundHandlerAdapter {
    private int count = 0; // 非线程安全的计数器

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        count++; // 多线程并发修改，会导致计数错误
        ctx.fireChannelRead(msg);
    }
}

依赖特定 Channel 的上下文：如处理器中缓存了某个ChannelHandlerContext，用于后续操作（不同Channel的ctx不同，共享会导致混乱）。
有状态的编解码器：如某些自定义协议的编解码器，需要维护半包 / 粘包的临时缓冲区（每个Channel的缓冲区状态不同，共享会导致数据错乱）。*/
