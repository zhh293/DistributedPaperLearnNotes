package org.example.MCP;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

public class MCPServer {
    public static void main(String[] args) throws InterruptedException {
        NioEventLoopGroup boss = new NioEventLoopGroup();
        NioEventLoopGroup worker = new NioEventLoopGroup();
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.group(boss, worker);
        serverBootstrap.childHandler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(new StringDecoder());
                ch.pipeline().addLast(new StringEncoder());
                ch.pipeline().addLast(new MCPHandler());
                // 用来判断是不是 读空闲时间过长，或 写空闲时间过长
                // 5s 内如果没有收到 channel 的数据，会触发一个 IdleState#READER_IDLE 事件
                ch.pipeline().addLast(new IdleStateHandler(5, 0, 0));
                // ChannelDuplexHandler 可以同时作为入站和出站处理器
                ch.pipeline().addLast(new ChannelDuplexHandler() {
                    // 用来触发特殊事件
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception{
                        IdleStateEvent event = (IdleStateEvent) evt;
                        // 触发了读空闲事件
                        if (event.state() != IdleState.READER_IDLE) {
//                            log.debug("已经 5s 没有读到数据了");
                            ctx.channel().close();
                        }
                    }
                });
            }
        }).option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        Channel channel = serverBootstrap.bind("localhost",8080).sync().channel();
        channel.closeFuture().sync();
    }
}
