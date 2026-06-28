package Netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class 黏包和半包Client {
    public static void main(String[] args) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(new NioEventLoopGroup(16));
        ChannelFuture channelFuture = bootstrap.channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline().addLast(new LoggingHandler(LogLevel.DEBUG));
                        socketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ByteBuf buffer = ctx.alloc().buffer(1024);
                                /*buffer.retain();
                                new Thread(()->{
                                     for(int i=0;i<100;i++){
                                        buffer.writeBytes(fill10Bytes('a',10));
                                        ctx.writeAndFlush(buffer);
                                    }
                                    buffer.release();
                                });*/
                                for(int i=0;i<10;i++){
                                    buffer.writeBytes(fill10Bytes('a',5));
                                }
                                ctx.writeAndFlush(buffer);
                                buffer.release();
                            }
                        });
                    }
                }).connect("127.0.0.1", 8080);
        channelFuture.addListener(future -> {
            if (future.isSuccess()) {
                System.out.println("连接成功");
            }
        });
    }
    public static byte[] fill10Bytes(char c,int length){
        byte[]bytes=new byte[10];
        if(length<10){
            //先把字节填到对应的长度要求，再用下划线填充空白的
            for(int i=0;i<length;i++){
                bytes[i]=(byte)c;
            }
            for(int i=length;i<10;i++){
                bytes[i]=(byte)'_';
            }
        }else {
            for(int i=0;i<10;i++){
                bytes[i]=(byte)c;
            }
        }
        return bytes;
    }
}



/*
LengthFieldBasedFrameDecoder 在许多基于 Netty 的框架和协议中被广泛使用，用于解决 TCP 粘包 / 拆包问题。以下是几个典型应用场景和开源框架中的实例：
        1. Dubbo（RPC 框架）
Dubbo 是阿里巴巴开源的高性能 RPC 框架，基于 Netty 实现网络通信。其协议格式为：

plaintext
[消息头(16字节)][消息体(N字节)]

消息头结构：
前 12 字节为固定头部信息（如魔数、消息类型）。
第 13~16 字节为长度字段，表示 消息体的长度。
Netty 配置：
java
        运行
// Dubbo 协议解码器配置
pipeline.addLast(new LengthFieldBasedFrameDecoder(
                         8 * 1024 * 1024,  // 最大帧长 8MB
    12,               // lengthFieldOffset: 长度字段在第12字节
                         4,                // lengthFieldLength: 长度字段占4字节
                         0,                // lengthAdjustment: 无需调整（长度字段只包含消息体）
                         16                // initialBytesToStrip: 跳过头部16字节
));
解析逻辑：
Netty 读取第 13~16 字节作为长度值 N。
计算总长度 = N（消息体） + 0（调整） + 12（偏移） + 4（长度字段） = N + 16。
剥离前 16 字节（头部），传递消息体给后续处理器。
        2. Redis 协议（RESP）
Redis 协议使用 长度前缀 来表示 bulk string 的长度。例如：

plaintext
$5\r\nhello\r\n


$5 表示后续字符串长度为 5 字节。
Netty 配置：
虽然 Redis 协议更适合用 LineBasedFrameDecoder，但复杂场景下也可用 LengthFieldBasedFrameDecoder：

java
        运行
pipeline.addLast(new LengthFieldBasedFrameDecoder(
                         1024,           // 最大帧长
    1,              // lengthFieldOffset: 长度字段从第1字节开始（跳过$符号）
                         1,              // lengthFieldLength: 长度字段占1字节（假设长度<=9）
                         2,              // lengthAdjustment: 加上\r\n的2字节
                         3               // initialBytesToStrip: 跳过$5\r\n
));

        4. 自定义私有协议
许多公司内部系统使用自定义协议，例如：

plaintext
[魔数(2字节)][版本号(1字节)][长度字段(4字节)=N][数据(N字节)]
Netty 配置：
java
        运行
pipeline.addLast(new LengthFieldBasedFrameDecoder(
                         1024 * 1024,    // 最大帧长 1MB
    3,              // lengthFieldOffset: 跳过魔数(2)和版本号(1)
                         4,              // lengthFieldLength: 长度字段占4字节
                         0,              // lengthAdjustment: 无需调整
                         7               // initialBytesToStrip: 跳过魔数(2)+版本号(1)+长度字段(4)
));

总结：
LengthFieldBasedFrameDecoder 的核心应用场景是：

所有带长度字段的协议：通过配置五个参数，适配不同协议格式。
防止 TCP 粘包 / 拆包：确保每次传递给业务处理器的都是完整消息。
简化协议解析：将复杂的帧边界识别逻辑交给 Netty，专注业务处理。
*/


