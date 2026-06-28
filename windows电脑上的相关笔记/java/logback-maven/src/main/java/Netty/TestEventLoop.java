package Netty;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.TimeUnit;

public class TestEventLoop {
    public static void main(String[] args) {
        EventLoopGroup  group = new NioEventLoopGroup(2);//开两个线程
        //获取下一个线程，在这两个线程中会轮询的，不断查看线程池中的任务
        /*System.out.println(group.next());
        System.out.println(group.next());
        System.out.println(group.next());
        System.out.println(group.next());*/
        //让线程池中的线程执行任务，执行普通任务

        /*group.next().submit(()->{
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println("hahah");
        });*/
        //执行定时任务
        group.next().scheduleAtFixedRate(()->{
            System.out.println("定时任务");
        },  0, 1, TimeUnit.SECONDS);




       /* new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel nioSocketChannel) throws Exception {
                        nioSocketChannel.pipeline().addLast(new NettyClientHandler());
                    }
                });


                四大要素，服务端channel注册，客户端channel初始化，监听器和传输器group(跟selector好像啊)，处理器handler(像业务层中的service层)*/
    }
}
/*
一、用 “公司部门” 类比 NioEventLoopGroup 的核心作用
假设你开了一家 “网络通信公司”，专门处理客户端的请求：

NioEventLoopGroup 就是公司的部门，负责统筹处理网络连接和数据交互。
这个部门有两种关键角色：
BOSS 部门（主 Reactor 组）：专门负责 “接待客户”（接收客户端连接）。
WORKER 部门（从 Reactor 组）：专门负责 “处理客户需求”（处理连接后的读写事件）。
二、NioEventLoopGroup 的底层核心结构：线程与事件循环的组合
每个部门的组成：多个 “员工”（NioEventLoop）
每个 NioEventLoopGroup 包含多个 NioEventLoop（默认数量为 CPU 核心数 × 2）。
每个 NioEventLoop 对应一个独立线程，且绑定一个 Selector（事件监控器）。
Selector（事件监控器）的作用
类比：像 “前台接待员”，负责 “监听” 客户端的动作（连接、发送数据、接收数据等）。
技术原理：Selector 是 Java NIO 的核心组件，通过 select() 方法阻塞等待网络事件，避免线程空转浪费资源。
NioEventLoop 的 “无限工作循环”
每个 NioEventLoop 线程会执行一个无限循环（EventLoop），流程如下：
轮询事件：用 Selector 检查是否有网络事件（连接、读写）。
处理事件：
若是连接事件（BOSS 部门）：接受连接并分配给 WORKER 部门。
若是读写事件（WORKER 部门）：读取数据、处理业务逻辑、发送响应。
处理任务队列：执行开发者提交的异步任务（如定时任务、回调函数）。
三、BOSS 与 WORKER 部门的分工：主从 Reactor 模式的核心
BOSS 部门（主 Reactor 组）的工作流程
职责：只负责 “建立连接”，不处理具体数据。
类比：像公司前台，只负责 “登记客户信息”，不处理具体业务。
技术细节：
绑定服务器端口，监听客户端连接请求。
收到连接后，创建客户端 Channel，并将 Channel 注册到 WORKER 部门的某个 NioEventLoop 上。
WORKER 部门（从 Reactor 组）的工作流程
职责：处理连接后的所有 “数据交互”。
类比：像公司的业务部门，负责客户的具体需求（如订单处理、数据查询）。
技术细节：
接收 BOSS 分配的 Channel，用 Selector 监听其读写事件。
读取数据后，交给业务处理器（Handler）处理，处理完后发送响应。
四、为什么需要这种分工？解决传统网络编程的痛点
        传统同步阻塞模型的问题
一个线程只能处理一个连接，若客户端多，需要创建大量线程（如 BIO 模型），导致内存和 CPU 浪费。
NioEventLoopGroup 的优势
线程复用：少量线程处理大量连接（一个线程可管理 thousands 的 Channel）。
无阻塞等待：通过 Selector 监听事件，线程不会因等待数据而阻塞，可处理其他事件。
分工明确：BOSS 专注建连接，WORKER 专注处理数据，避免单线程瓶颈。*/



/*

为什么java不可以单独new一个NioEventLoop
在 Java 里，是不可以单独创建一个NioEventLoop实例的，下面为你详细分析原因。
        1. NioEventLoop的设计用途
NioEventLoop是 Netty 框架中的一个关键组件，它的主要功能是处理 I/O 操作，像网络连接、数据读写等。该类属于 Netty 内部实现的一部分，并非是供用户直接使用的公共 API。Netty 框架对NioEventLoop的生命周期有着严格的管理，其中涉及线程的创建与销毁、资源的分配与释放等操作。要是用户单独创建NioEventLoop，就很可能会破坏这种管理机制，进而引发资源泄漏或者线程安全方面的问题。
        2. 构造函数的限制
NioEventLoop的构造函数并非是公开的，这就意味着用户无法直接通过new关键字来创建它的实例。例如：

java
// 下面这种写法是错误的，无法编译通过
NioEventLoop loop = new NioEventLoop(); // 编译错误：构造函数不可访问

要想创建NioEventLoop，必须借助NioEventLoopGroup，它是一个事件循环组，能够管理多个NioEventLoop实例。示例如下：

java
EventLoopGroup group = new NioEventLoopGroup(1); // 创建包含1个事件循环的组
NioEventLoop loop = (NioEventLoop) group.next(); // 从组中获取事件循环
3. 资源管理方面的问题
NioEventLoop在运行过程中会占用像 Selector、线程等系统资源。Netty 框架对这些资源的初始化和释放过程进行了封装，例如在关闭EventLoopGroup时，会自动释放所有相关资源：

java
group.shutdownGracefully(); // 优雅地关闭事件循环组，释放资源


如果用户单独创建NioEventLoop，就需要手动管理这些资源，然而NioEventLoop并没有提供公开的资源释放接口，这样就很容易造成资源泄漏。
        4. 线程模型的完整性
Netty 的线程模型要求所有的NioEventLoop都由同一个EventExecutorGroup进行管理，以此来保证线程安全和负载均衡。要是单独创建NioEventLoop，就会破坏这种线程模型，使得事件无法在多个EventLoop之间正确地分发。*/


//简单来说就是作为线程来用不如直接使用线程池，想要用它的selector选择器不如直接new一个Selector对象，完全没有单独使用的价值