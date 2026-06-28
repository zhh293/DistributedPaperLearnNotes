package Netty;

public class connect方法 {
/*    Netty 客户端的connect()方法设计为异步非阻塞主要出于以下几个关键原因，这些设计选择实际上是为了提升系统的整体性能和资源利用率：
            1. 避免线程阻塞，提高资源利用率
    在传统同步编程模型中，调用connect()方法会阻塞当前线程，直到连接建立或超时。这意味着：

    线程资源浪费：在等待连接的过程中，线程无法执行其他任务，尤其在高并发场景下，大量线程会被阻塞，导致系统资源浪费。
    吞吐量下降：线程池中的线程被阻塞后，无法处理其他请求，导致系统整体吞吐量下降。

    异步非阻塞的优势：

    调用connect()后，线程可以立即返回并执行其他任务（如处理其他连接或业务逻辑）。
    连接结果通过ChannelFuture异步通知，线程无需等待。
            2. 更好的并发处理能力
    在高并发场景下，客户端可能需要同时建立大量连接（如负载均衡、服务发现等场景）。如果connect()是同步阻塞的，线程池很快会被耗尽。而异步非阻塞模型可以：

    用少量线程管理大量连接请求。
    通过事件驱动机制，在连接建立成功或失败时触发回调。
    便于集成其他异步组件
在微服务架构中，客户端可能需要与多个服务建立连接。异步连接便于与其他异步操作（如异步 HTTP 请求、异步数据库访问）组合使用，形成完整的异步调用链。
6. 实际连接建立本身是耗时操作
TCP 连接的建立需要经过三次握手，这个过程在网络状况不佳时可能耗时较长。将这个过程异步化，可以让应用在等待期间继续处理其他任务。






方式 1：同步等待（阻塞当前线程）
java
ChannelFuture future = bootstrap.connect(host, port);
// 阻塞当前线程，直到连接完成
future.sync();
if (future.isSuccess()) {
    // 连接成功
    Channel channel = future.channel();
} else {
    // 连接失败
    Throwable cause = future.cause();
}

方式 2：异步回调（非阻塞）
java
ChannelFuture future = bootstrap.connect(host, port);
future.addListener(f -> {
    if (f.isSuccess()) {
        // 连接成功，在回调中处理
        Channel channel = ((ChannelFuture) f).channel();
    } else {
        // 连接失败
        Throwable cause = f.cause();
    }
});
// 代码继续执行，不会阻塞*/
}
