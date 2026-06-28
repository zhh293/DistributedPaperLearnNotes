package org.example.Redis;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RedisClient {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);
    public static void main(String[] args) throws InterruptedException {
        try{
            send("set name zhangsan");
            send("get name");
        }
        catch (Exception e){
            e.printStackTrace();
        }finally {
            executorService.shutdown();
        }
       /* send("get name");
        send("del name");*/
    }

    private static void send(String delName) throws InterruptedException {

            ChannelFuture sync = null;
            try {
                sync = new Bootstrap()
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
                                String[] s = delName.split(" ");
                                buffer.writeBytes(("*" + s.length + "\r\n").getBytes());
                                for (String s1 : s) {
                                    buffer.writeBytes(("$" + s1.getBytes().length + "\r\n"+s1+"\r\n").getBytes());
                                }
                                ctx.writeAndFlush(buffer);
                                //buffer.release();
                            }
                        });
                    }
                }).connect("127.0.0.1", 6379)
                .sync();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            sync.addListener(future -> {
            if (future.isSuccess()) {
                System.out.println("连接成功");
            }
        });
            ChannelFuture future = null;
            try {
                future = sync.channel().closeFuture().sync();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            future
                .addListener(future1 -> {
                    if (future1.isSuccess()) {
                        System.out.println("关闭成功");
                    }
                });
        };


}




/*必须使用 RESP 数组格式（因为 Redis 命令通常包含多个参数，如SET key value包含 3 个元素）。RESP 协议通过特定前缀符号区分不同数据类型，核心格式如下：
        1. 数组（客户端发送命令的格式）
命令以数组形式发送，格式为：
        *<元素数量>\r\n<元素1>\r\n<元素2>\r\n...<元素N>\r\n

* 是数组的前缀符号；
<元素数量> 是数组中包含的元素个数（整数）；
每个元素需用 批量字符串（Bulk String） 格式表示（见下文）；
每行结束必须用 \r\n（CRLF，回车 + 换行），不能单独用 \n。
        2. 批量字符串（数组元素的格式）
数组中的每个元素（命令名、参数）需用批量字符串表示，格式为：
$<字符串长度>\r\n<字符串内容>\r\n

$ 是批量字符串的前缀符号；
<字符串长度> 是字符串的字节数（整数，不包含\r\n）；
<字符串内容> 是具体的字符串（二进制安全，可包含任意字符）。
示例：发送 SET name redis 命令
按照 RESP 协议，该命令的格式如下（拆分行便于理解）：

plaintext
*3\r\n         // 数组包含3个元素（SET、name、redis）
$3\r\n         // 第一个元素是长度为3的字符串
SET\r\n        // 第一个元素内容：SET
$4\r\n         // 第二个元素是长度为4的字符串
name\r\n       // 第二个元素内容：name
$5\r\n         // 第三个元素是长度为5的字符串
redis\r\n      // 第三个元素内容：redis


合并后完整的字节流为：
        *3\r\n$3\r\nSET\r\n$4\r\nname\r\n$5\r\nredis\r\n*/
