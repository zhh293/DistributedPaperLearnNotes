package Netty;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoop;
import io.netty.channel.nio.NioEventLoopGroup;

public class EventLoopDemo1 {

    private EventLoop eventLoop;
    public void test1() {
    }
}
/*
一、核心关系：BOSS 和 WORKER 都是 EventLoopGroup 的 “部门”，每个部门由多个 EventLoop 组成
用 “公司架构” 类比：

NioEventLoopGroup 是公司，有两种部门：
BOSS 部门（主 Reactor 组）：类名为 NioEventLoopGroup，专门处理 “客户接待”（连接请求）。
WORKER 部门（从 Reactor 组）：同样是 NioEventLoopGroup，专门处理 “业务办理”（数据读写）。
每个部门的员工是 EventLoop：
每个 NioEventLoopGroup 包含多个 NioEventLoop（线程），每个 NioEventLoop 负责处理一部分客户端连接的事件。*/
/*
┌─────────────────────────────────────────────────────────┐
        │                        NioEventLoopGroup (公司)           │
        │                                                         │
        │  ┌──────────────┐          ┌────────────────┐            │
        │  │  BOSS部门     │          │  WORKER部门      │            │
        │  │ (主Reactor组) │          │ (从Reactor组)    │            │
        │  └──────┬────────┘          └────────┬─────────┘            │
        │         │                           │                        │
        │         ▼                           ▼                        │
        │  ┌──────────────┐          ┌────────────────┐            │
        │  │ NioEventLoop │          │ NioEventLoop     │            │
        │  │  (线程1)     │          │  (线程A)        │            │
        │  └──────┬────────┘          └────────┬─────────┘            │
        │         │                           │                        │
        │         ▼                           ▼                        │
        │  ┌──────────────┐          ┌────────────────┐            │
        │  │ NioEventLoop │          │ NioEventLoop     │            │
        │  │  (线程2)     │          │  (线程B)        │            │
        │  └──────┘────────┘          └────────┘─────────┘            │
        │                                                         │
        └─────────────────────────────────────────────────────────┘*/
/*
三、BOSS 部门（主 Reactor 组）的运作细节
部门构成：
通常创建时指定线程数为 1（new NioEventLoopGroup(1)），因为 “接待客户”（建连接）是单点任务，多线程可能导致端口绑定冲突。
每个 BOSS 部门的 NioEventLoop 线程负责：
监听服务器端口，接收客户端连接请求。
收到连接后，创建客户端 Channel，并将 Channel 分配给 WORKER 部门的某个 NioEventLoop。
关键代码示例：
java
NioEventLoopGroup bossGroup = new NioEventLoopGroup(1); // BOSS部门只有1个员工（线程）

四、WORKER 部门（从 Reactor 组）的运作细节
部门构成：
通常不指定线程数（new NioEventLoopGroup()），默认线程数为 CPU核心数 × 2，因为 “处理业务”（数据读写）需要并行能力。
每个 WORKER 部门的 NioEventLoop 线程负责：
接收 BOSS 分配的 Channel，用 Selector 监听其读写事件。
读取数据、调用业务处理器（Handler）、发送响应，全程无阻塞。
关键代码示例：
java
NioEventLoopGroup workerGroup = new NioEventLoopGroup(); // WORKER部门默认有 CPU×2 个员工


五、Channel 与 EventLoop 的绑定关系：每个连接由专属 EventLoop 处理
绑定流程：
当 BOSS 部门接受一个客户端连接后，会将该连接的 Channel “注册” 到 WORKER 部门的某个 NioEventLoop 上。
一旦注册，这个 Channel 的所有后续事件（读、写、异常等）都由该 NioEventLoop 线程处理，保证线程安全（无需额外同步锁）。
类比理解：
每个客户端连接就像 “客户”，BOSS 部门接待后，会给客户分配一个专属的 WORKER 员工（NioEventLoop）。
这个员工会全程负责该客户的所有需求（数据交互），避免客户在不同员工间切换导致混乱。*/
/*
在 Netty 中，线程与EventLoop的关系可以概括为：一个线程在其生命周期内始终绑定唯一的EventLoop，而一个EventLoop也只由一个线程驱动。以下是具体逻辑和原理：
        1. EventLoop 与线程的绑定关系
（1）单线程驱动原则
每个EventLoop（如NioEventLoop）由唯一的线程负责执行，该线程在EventLoop的生命周期内不会改变。
示例：
java
EventLoopGroup workerGroup = new NioEventLoopGroup(4); // 4个EventLoop
// 每个EventLoop对应1个线程，共4个线程

（2）线程与 EventLoop 的一一对应
当EventLoop被创建时，会分配一个专属线程，该线程负责处理该EventLoop的所有任务（如 I/O 事件、定时任务）。*/
