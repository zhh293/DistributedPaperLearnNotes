package Netty;

public class NettyDemo1 {
}
/*
3. 手动创建 Worker 线程池的示例
下面是一个手动创建 Worker 线程池的示例代码：

java
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class NettyServer {
    private final int port;

    public NettyServer(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        // 手动创建Boss线程池和Worker线程池
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); // 主Reactor
        EventLoopGroup workerGroup = new NioEventLoopGroup(16); // 从Reactor，16个线程

        try {
            ServerBootstrap b = new ServerBootstrap(); // (2)
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class) // (3)
                    .childHandler(new ChannelInitializer<SocketChannel>() { // (4)
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new MyServerHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)          // (5)
                    .childOption(ChannelOption.SO_KEEPALIVE, true); // (6)

            // 绑定端口，开始接收进来的连接
            ChannelFuture f = b.bind(port).sync(); // (7)

            // 等待服务器  socket 关闭 。
            // 在这个例子中，这不会发生，但你可以优雅地关闭你的服务器。
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    public static void main(String[] args) throws Exception {
        new NettyServer(8080).run();
    }
}
4. 总结
默认线程池：在大多数情况下，使用 Netty 提供的默认线程池就能满足需求，无需手动创建 Worker 线程池。
手动创建的必要性：只有在你有特殊需求，如自定义线程数量、线程属性，或者需要实现复杂的线程隔离策略时，才需要手动创建 Worker 线程池*/


/*1. 绑定到不同 EventLoop 的执行逻辑
        执行流程
线程切换机制：
当 Handler 绑定到不同的 EventLoop 时，Netty 会通过EventExecutorGroup自动切换执行线程。例如：
Handler A 在 EventLoop A 中执行。
当数据传递到绑定 EventLoop B 的 Handler B 时，Netty 会将任务提交到 EventLoop B 的任务队列。
Handler B 在 EventLoop B 的线程中执行，与 EventLoop A 完全隔离。
异步非阻塞特性：
线程切换是通过任务队列实现的，不会阻塞当前线程。例如：
java
ctx.channel().pipeline().addLast(
        eventLoopA, new HandlerA(),    // 在EventLoop A执行
eventLoopB, new HandlerB()     // 在EventLoop B执行
);

当 Handler A 调用ctx.fireChannelRead(msg)时，消息会被封装为任务提交到 EventLoop B 的队列。
EventLoop B 的线程在处理该任务时，会执行 Handler B 的channelRead()方法。
线程安全影响
不同 EventLoop 间：由于每个 EventLoop 由独立线程执行，Handler 间无需额外同步（如synchronized），但需注意：
共享状态（如静态变量）需要手动同步。
避免直接在不同 Handler 中修改同一对象。
适用场景
耗时操作隔离：将 I/O 密集型和 CPU 密集型 Handler 分离。
多业务隔离：如 HTTP 请求和 WebSocket 消息处理使用不同线程池。
        2. 绑定到相同 EventLoop 的执行逻辑
        执行流程
顺序执行保证：
若所有 Handler 绑定到同一个 EventLoop，它们会在同一个线程中按顺序执行：
java
ctx.channel().pipeline().addLast(
        eventLoopA, new HandlerA(),    // 在EventLoop A执行
eventLoopA, new HandlerB()     // 仍在EventLoop A执行
);


Handler A 执行完成后，直接调用 Handler B 的channelRead()方法，无需线程切换。
阻塞风险：
若某个 Handler 执行耗时操作（如数据库查询），会阻塞整个 EventLoop 线程，导致其他 Channel 的 I/O 操作也被阻塞*/