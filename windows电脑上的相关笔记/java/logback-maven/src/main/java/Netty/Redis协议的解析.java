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
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.logging.LoggingHandler;

import java.nio.charset.Charset;

public class Redis协议的解析 {
    public static void main(String[] args) throws InterruptedException {
        ChannelFuture future = new Bootstrap()
                .group(new NioEventLoopGroup())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline().addLast(new LoggingHandler());
                        socketChannel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                ByteBuf buffer = ctx.alloc().buffer(1024);
                                buffer.writeBytes(new byte[]{'*', '3', '\r', '\n', '$', '3', '\r', '\n', 'G', 'E', 'T', '\r', '\n', '$', '1', '\r', '\n', 'A', '\r', '\n', '$', '1', '\r', '\n', 'B', '\r', '\n'});
                                ctx.writeAndFlush(buffer);
                            }
                        });
                    }
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg){
                        ByteBuf buf = (ByteBuf) msg;
                        ctx.pipeline().addLast(new StringDecoder(Charset.forName("UTF-8")));
                        System.out.println(buf.toString(io.netty.util.CharsetUtil.UTF_8));

                    }
                }).connect("127.0.0.1", 6379);
        future.sync().addListener(future1 -> {
            if (future1.isSuccess()) {
                System.out.println("连接成功");
            }
        });
    }
    //可以看出来，只要遵守协议，就可以解析出命令和参数
}
