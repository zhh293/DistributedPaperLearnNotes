package Netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class handler和pipeline {
    public static void main(String[] args) {
        //通过channel拿到pipeline
        new ServerBootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) throws Exception {
                        //拿到pipeline
                        //netty默认会有一个head和一个tail  handler
                        ch.pipeline().addLast("h1",new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                log.debug("{}",msg);
                                super.channelRead(ctx, msg);
                            }
                        }).addLast("h2",new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                log.debug("{}",msg);
                                super.channelRead(ctx, msg);
                            }
                        }).addLast("h3",new ChannelOutboundHandlerAdapter(){
                            @Override
                            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                                log.debug("{}",msg);
                                super.write(ctx, msg, promise);
                            }
                        });
                    }
                }).bind(8080);

    }
}
/*3.4 Handler & Pipeline

ChannelHandler 用来处理 Channel 上的各种事件，分为入站、出站两种。所有 ChannelHandler 被连成一串

就是 Pipeline

。入站处理器通常是 ChannelinboundHandlerAdapter的子类，主要用来读取客户端数据，写回结果

出站处理器通常是 ChannelOutboundHandlerAdapter 的子类，主要对写回结果进行加工

打个比喻，每个Channel是一个产品的加工车间，Pipeline 是车间中的流水线，ChannelHandler 就是流水线上

的各道工序，而后面要讲的 ByteBuf是原材料，经过很多工序的加工:先经过一道道入站工序，再经过一道道出

        站工序最终变成产品*/

/*1. ChannelPipeline
作用：管理 Channel 的所有处理器，形成一个双向链表结构。
数据流向：
入站（Inbound）：数据从网络到应用（如channelRead()事件）。
出站（Outbound）：数据从应用到网络（如write()操作）。
        2. 处理器分类
类型	继承体系	典型用途
入站处理器	继承自ChannelInboundHandler	解码、协议解析、业务逻辑处理
出站处理器	继承自ChannelOutboundHandler	编码、协议封装、流量控制
二、入站处理器（Inbound Handler）
. 关键 API 详解
方法	触发时机	处理建议
channelRead()	从通道读取数据时	转换数据格式（如 ByteBuf→POJO）
channelReadComplete()	数据读取完成时（可触发 flush）	批量处理数据（如批量写回响应）
exceptionCaught()	发生异常时	关闭通道或重试操作
channelActive()	通道激活（连接建立）	发送握手消息或初始化操作
3. 典型场景
解码：将ByteBuf转换为业务对象（如StringDecoder）。
协议解析：解析 HTTP/2 帧或自定义协议包。
业务逻辑处理：认证、权限校验等。
三、出站处理器（Outbound Handler）
1. 核心接口与实现
接口：ChannelOutboundHandler（通常继承ChannelOutboundHandlerAdapter）。
. 关键 API 详解
方法	触发时机	处理建议
write()	应用程序调用channel.write()时	将业务对象编码为 ByteBuf
flush()	刷新缓冲区数据到网络	控制发送频率（如合并小数据包）
connect()	建立连接时	添加连接超时处理
close()	关闭通道时	释放资源或发送关闭通知
 典型场景
编码：将业务对象转换为ByteBuf（如StringEncoder）。
协议封装：添加 HTTP 头部或自定义协议头。
流量控制：实现writeAndFlush()的优化策略。
四、处理器链的执行流程
1. 入站事件流程
java
// 数据流向：Channel → HeadHandler → InboundHandler1 → ... → TailHandler
ctx.fireChannelRead(msg); // 触发入站事件传播

示例：
java
public class MyInboundHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 处理数据
        System.out.println("Read data: " + msg);
        // 继续传播事件（若不调用，事件链中断）
        ctx.fireChannelRead(msg);
    }
}


2. 出站事件流程
java
// 数据流向：应用 → TailHandler → OutboundHandler1 → ... → HeadHandler → Socket
ctx.write(msg); // 触发出站事件传播


示例：
java
public class MyOutboundHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // 转换数据格式（如POJO → ByteBuf）
        ByteBuf buffer = convertToByteBuf(msg);
        // 继续传播写事件
        ctx.write(buffer, promise);
    }
}*/



/*
channelRead() 方法详解
1. 方法定义
        java
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;


参数：
ctx：ChannelHandlerContext，用于与管道中的其他处理器交互。
msg：读取到的数据对象（类型取决于前一个处理器，通常是 ByteBuf）。
        2. 核心作用
数据处理：将接收到的数据转换为业务对象，或执行具体业务逻辑。
事件传播：通过 ctx.fireChannelRead(msg) 将数据传递给下一个入站处理器。、
        1. 继承关系
自定义处理器通常继承自 ChannelInboundHandlerAdapter。
ChannelInboundHandlerAdapter 的 channelRead() 默认实现：
java
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    ctx.fireChannelRead(msg); // 将事件传递给下一个处理器
}
调用 super.channelRead() 的意义
继续传播事件：如果在自定义处理器中处理完数据后，需要继续传递给后续处理器，必须调用 super.channelRead() 或 ctx.fireChannelRead(msg)。
示例：
java
public class MyHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 处理数据
        System.out.println("Received: " + msg);

        // 方式1：调用父类方法（等价于ctx.fireChannelRead(msg)）
        super.channelRead(ctx, msg);

        // 方式2：手动触发传播
        // ctx.fireChannelRead(msg);
    }
}



数据解码
将 ByteBuf 转换为业务对象，然后传递给后续处理器。
java
public class MessageDecoder extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf buf = (ByteBuf) msg;
        try {
            // 从ByteBuf解析出业务对象
            MyMessage message = parseMessage(buf);
            // 传递给下一个处理器（如业务逻辑处理器）
            ctx.fireChannelRead(message);
        } finally {
            ReferenceCountUtil.release(msg); // 释放ByteBuf
        }
    }
}


2. 业务逻辑处理
处理解码后的业务对象，不继续传播（事件链在此终止）。
java
public class BusinessHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof MyMessage) {
            MyMessage message = (MyMessage) msg;
            // 执行业务逻辑
            processMessage(message);
            // 不调用super.channelRead()，事件链终止
        } else {
            super.channelRead(ctx, msg); // 非预期类型，继续传递
        }
    }
}*/



