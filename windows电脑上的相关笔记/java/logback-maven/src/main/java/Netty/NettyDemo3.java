package Netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import lombok.extern.slf4j.Slf4j;
/*netty为什么需要多线程和那么多个worker呢，只使用一个带有selecctor的工人不行吗
Netty 作为高性能网络编程框架，之所以设计为多线程和多 Worker 模型，主要是基于以下几个关键原因：
        1. 充分利用多核 CPU 资源
现代服务器通常配备多核 CPU，如果只使用单线程，会导致其他 CPU 核心处于闲置状态，无法发挥硬件的最大性能。Netty 的多线程模型可以将 I/O 操作和业务处理分配到多个线程上并行执行，显著提升系统吞吐量。
        2. 避免 I/O 阻塞导致的性能瓶颈
虽然 NIO 的 Selector 允许单线程处理多个连接，但如果某个连接上的 I/O 操作（如读取大量数据）耗时过长，会阻塞整个线程，影响其他连接的处理。通过多 Worker 线程，可以将连接分配到不同线程上，一个连接的阻塞不会影响其他连接。*/
@Slf4j
public class NettyDemo3 {
    public static void main(String [] args){
        EventLoopGroup group = new DefaultEventLoop();
        new ServerBootstrap()
                //boss和worker，boss负责处理连接，worker负责处理读写，boss只负责ServerSocketChannel上面的accept事件，worker只负责SocketChannel
                //创建一个独立的eventgroup，这个可以在代码处理时间较长的处理器中使用，避免线程阻塞
                .group(new NioEventLoopGroup())//第一个参数是boss，第二个参数是worker，第二个参数是默认的，不必要配置，第一个参数需要传入线程组
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>(){
                    @Override
                    protected void  initChannel(NioSocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new StringDecoder());
                        ch.pipeline().addLast("handler1",new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                ByteBuf buf = (ByteBuf) msg;
                                ctx.fireChannelRead(msg
                                );//把消息转发给下一个handler
                                System.out.println(buf);
                            }
                        }).addLast(group,"handler2",new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                ByteBuf buf = (ByteBuf) msg;
                                log.debug(buf.toString());
                                System.out.println(buf);
                            }
                        });
                    }
                })
                .bind(8010);
    }













    /*组件 / 概念	作用与细节	类比理解
    Channel（通道）	- 数据传输的 “通道”，代表网络连接（如NioSocketChannel）。
            - 封装了 TCP 连接的读写、绑定端口等操作。	快递运输的 “管道”
    EventLoop（事件循环）	- 基于线程池的 “事件处理器”，分为：
            - BossEventLoopGroup：负责接收客户端连接（类似 “门卫”）。
            - WorkerEventLoopGroup：负责处理连接的读写业务（类似 “工人”）。
            - 每个 EventLoop 绑定一个线程，循环处理通道的 I/O 事件。	工厂里的 “工人 + 任务队列”
    Handler（处理器）	- 处理网络事件（如 “读数据、写数据、连接建立”）的逻辑单元。
            - 分为：
            - InboundHandler（入站）：处理接收数据（如解码、业务逻辑）。
            - OutboundHandler（出站）：处理发送数据（如编码、协议封装）。	工厂里的 “工序工人”
    Pipeline（管道）	- 串联多个 Handler 的执行链，数据会按顺序流经所有处理器。
            - 每个 Channel 对应一个 Pipeline，事件在链中传递。	工厂的 “流水线”*/
/*比如·
    sequenceDiagram
    客户端->>服务端: 发送 ByteBuf 数据
    服务端->>BossEventLoop: 接收连接请求
    BossEventLoop->>WorkerEventLoop: 转交连接给 Worker
    Worker->>Pipeline: 触发“读事件”(channelRead)
    Pipeline->>StringDecoder: 解码（ByteBuf→字符串）
    Pipeline->>自定义 Handler: 业务处理（如打印数据）
    数据变形：原始数据是 ByteBuf（Netty 自定义字节容器），经过 StringDecoder 会转为字符串，再交给后续 Handler。
事件驱动：所有操作（读、写、连接建立）都通过重写 Handler 的方法实现（如 channelRead 处理读事件）*/
    /*关键概念深化
    Pipeline 执行顺序：
    数据从 “入站” 到 “出站” 会严格按 addLast 的顺序流经处理器。例如：
    java
pipeline.addLast(handlerA); // 先执行 A
pipeline.addLast(handlerB); // 再执行 B


（注：入站和出站处理器的执行顺序需区分，入站是 “从前到后”，出站是 “从后到前” ）
    ChannelHandlerContext：
    每个 Handler 关联一个 ChannelHandlerContext，用于：
    传递事件（如 ctx.fireChannelRead(msg) 触发下一个 Handler 的读事件）。
    直接操作通道（如 ctx.writeAndFlush(...) 发送数据）。
    事件循环的 “绑定” 与 “复用”：
    一个 WorkerEventLoop 可处理多个 Channel（连接）的 I/O 操作，基于 “事件循环” 复用线程。
    一旦 Channel 绑定到某个 EventLoop，后续操作（读写）固定由该线程处理，避免多线程竞争。*/









/*// 1. 初始化服务器引导类（组装 Netty 组件的“启动器”）
new ServerBootstrap()
    // 2. 配置事件循环组（Boss 负责接连接，Worker 负责处理读写）
    .group(new NioEventLoopGroup())
            // 3. 指定服务器通道类型（NIO 模式，也可选 BIO/OIO 等）
            .channel(NioServerSocketChannel.class)
    // 4. 配置子处理器（Worker 处理连接时，用 Pipeline 初始化逻辑）
    .childHandler(new ChannelInitializer<NioSocketChannel>() {
        @Override
        protected void initChannel(NioSocketChannel ch) throws Exception {
            // 5. 向 Pipeline 添加处理器（流水线工序）
            // 5.1 解码器：ByteBuf → 字符串（Inbound 入站处理）
            ch.pipeline().addLast(new StringDecoder());
            // 5.2 自定义业务处理器（重写 channelRead 处理读事件）
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    System.out.println(msg); // 打印解码后的字符串
                }
            });
        }
    })
            // 6. 绑定端口（启动服务器，监听 8010 端口）
            .bind(8010);*/
}
