package Netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Scanner;
@Slf4j
public class client {
    public static void main(String[] args) throws InterruptedException {
        log.debug("客户端启动");
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup();
        //创建启动器类
        ChannelFuture channelFuture = new Bootstrap()
                //添加EventLoop
                .group(eventExecutors)
                //选择客户端channel的实现
                .channel(NioSocketChannel.class)
                //添加处理器
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel channelHandlerContext) throws Exception {
                        channelHandlerContext.pipeline().addLast(new StringDecoder());
                    }
                })
                //连接服务器
                //异步非阻塞，main发起了调用，真正执行connect是NioEventLoopGroup的线程，返回的ChannelFuture表示一个还没有完成连接的Future
                .connect(new InetSocketAddress("localhost", 8010));
                /*.sync()
                .channel()
                .writeAndFlush("hello world");*/
        /*channelFuture.addListener(f->{
            if(f.isSuccess()){
                System.out.println("连接成功");
            }else{
                f.cause().printStackTrace();
            }
        });*/
        /*channelFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                channelFuture.channel().writeAndFlush("hello world");
            }
        });*/
        /*Channel channel = channelFuture.sync().channel();
        new Thread(()->{
            Scanner scanner = new Scanner(System.in);
            while(true){
                String msg = scanner.next();
                if(msg.equals("exit")){
                    //channel.close();//close异步操作，可能执行完这段代码一秒后才能得到结果，所以不能保证下面的代码一定在channel关闭之后才执行。。。。
                    try {
                        channel.closeFuture().sync();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    log.debug("连接已关闭");
                    break;
                }
                channel.writeAndFlush(msg);

            }
        }).start();*/
        Channel channel = channelFuture.sync().channel();
        ChannelFuture channelFuture1 = channel.closeFuture();
        channelFuture1.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                log.debug("连接已关闭");
                eventExecutors.shutdownGracefully();
            }
        });
    }
}
/*
1. Netty 的异步核心
Netty 是基于异步非阻塞的框架，核心操作（如连接、读写、关闭）都会返回ChannelFuture对象，代表一个未完成的异步操作。例如：

java
ChannelFuture future = bootstrap.connect(host, port); // 异步连接，立即返回Future

此时连接尚未完成，future处于未完成状态。
        2. sync()的作用：阻塞等待操作完成
ChannelFuture sync()：阻塞当前线程，直到异步操作完成（成功 / 失败），并抛出异常（若操作失败）。
ChannelFuture syncUninterruptibly()：类似sync()，但不响应中断。
与异步回调的区别：
java
// 异步回调方式（推荐）
future.addListener(f -> {
        if (f.isSuccess()) {
        System.out.println("连接成功");
    } else {
            f.cause().printStackTrace();
    }
            });

// 同步等待方式
            try {
            future.sync(); // 阻塞直到连接完成
    System.out.println("连接成功");
} catch (InterruptedException e) {
        e.printStackTrace();
}*/
