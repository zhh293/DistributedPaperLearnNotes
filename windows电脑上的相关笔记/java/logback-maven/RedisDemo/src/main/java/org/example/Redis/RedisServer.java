package org.example.Redis;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.redis.RedisArrayAggregator;
import io.netty.handler.codec.redis.RedisBulkStringAggregator;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.logging.LoggingHandler;

import java.util.Arrays;
import java.util.List;

public class RedisServer {
    public static void main(String[] args) {
        ChannelFuture bind = new ServerBootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel serverSocketChannel) throws Exception {
                        // 使用Netty Redis解码器的示例
                        ChannelPipeline pipeline = serverSocketChannel.pipeline();
                        /*pipeline.addLast(new RedisDecoder());      // 解析RESP协议
                        pipeline.addLast(new RedisBulkStringAggregator()); // 聚合批量字符串*/
                        //pipeline.addLast(new RedisArrayAggregator());      // 聚合数组
                      //  pipeline.addLast(new YourRedisCommandHandler());   // 处理解析后的命令
                        pipeline.addLast(new RespFrameDecoder());
                        serverSocketChannel.pipeline().addLast(new LoggingHandler());
                        serverSocketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                //将msg转换成redis命令语句字符串并且打印一下
                                //msg是一个集合，现在我要打印出来他的元素
                               if(msg instanceof List){
                                   List list = (List) msg;
                                   for(Object o:list){
                                       System.out.println(o);
                                   }
                               }
                            }
                        });
                    }
                }).bind("127.0.0.1", 6379);
        bind.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (channelFuture.isSuccess()) {
                    System.out.println("绑定成功");
                }
            }
        });
    }
}
