package org.example.Http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class TestHttp {
    public static void main(String[] args) throws InterruptedException {
        ChannelFuture channelFuture = new ServerBootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline().addLast(new LoggingHandler(LogLevel.DEBUG));
                        socketChannel.pipeline().addLast(new HttpServerCodec());
                        /*socketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

                                if(msg instanceof HttpRequest){

                                }else if(msg instanceof HttpContent){

                                }
                            }
                        });*/
                        socketChannel.pipeline().addLast(new SimpleChannelInboundHandler<HttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext channelHandlerContext, HttpRequest httpRequest) throws Exception {
                                System.out.println(httpRequest.uri());
                                System.out.println(httpRequest.headers());
                                //返回响应
                                DefaultFullHttpResponse response = new DefaultFullHttpResponse(httpRequest.protocolVersion(), HttpResponseStatus.OK);
                                response.content().writeBytes("hello world".getBytes());
                                httpRequest.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                                channelHandlerContext.writeAndFlush(response);
                                SocketChannel channel = (SocketChannel)channelHandlerContext.channel();
                                System.out.println(channel.remoteAddress());
                            }
                        });


                    }
                }).bind("127.0.0.1",8080).sync();
        channelFuture.channel().closeFuture().sync();
        System.out.println("启动成功");
    }
}
