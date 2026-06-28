package Netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;

public class NettyDemo2 {
    public static void main(String[] args) {
        //启动器，负责组装Netty组件，启动服务器
        ChannelFuture bind = new ServerBootstrap()
                //BossEventLoopGroup：处理客户端连接请求，请求之后，会生成一个NioEventLoop.WorkerEventLoopGroup：处理客户端业务逻辑
                .group(new NioEventLoopGroup())
                //选择服务端的实现
                .channel(NioServerSocketChannel.class)//OIO BIO
                //boss负责监听端口，worker负责处理客户端连接，一旦boss接受连接，将连接转给worker这一行决定了worker能执行哪些操作
                .childHandler(
                        //代表和客户端进行数据读写的通道初始化，负责添加读写操作的handler
                        new ChannelInitializer<NioSocketChannel>() {
                            @Override
                            protected void initChannel(NioSocketChannel channelHandlerContext) throws Exception {
                                //添加具体的handler
                                channelHandlerContext.pipeline().addLast(new StringDecoder());//将bytebuf转为字符串
                                channelHandlerContext.pipeline().addLast(new ChannelInboundHandlerAdapter() {//自定义handler
                                    @Override//读事件
                                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                        //打印上一步读到的数据
                                        System.out.println(msg);
                                    }
                                });
                            }
                        })
                .bind(8010);

    }
}
/*服务器启动后，BossGroup 监听 8010 端口等待连接
当有新连接到达时，BossGroup 将连接注册到 WorkerGroup
WorkerGroup 为每个连接创建一个 ChannelPipeline
数据到达时，依次经过 Pipeline 中的处理器处理
StringDecoder 将二进制数据转换为字符串
自定义处理器的 channelRead 方法打印接收到的字符串

这段代码实现了一个简单的 Echo 服务器的雏形，只负责接收和打印客户端发送的数据。在实际应用中，你可能需要添加更多处理器来实现业务逻辑，以及适当的错误处理和资源释放机制。*/

/*ServerBootstrap：Netty 提供的一个启动辅助类，用于简化服务器的启动过程。
group(new NioEventLoopGroup())：
NioEventLoopGroup 是一个线程池，负责处理 I/O 操作
在服务器模式下，通常需要两个 EventLoopGroup：
BossGroup：负责接受客户端连接（代码中省略了第二个参数，默认 BossGroup 和 WorkerGroup 使用同一个实例）
WorkerGroup：负责处理连接上的读写操作
channel(NioServerSocketChannel.class)：
指定使用 NIO 传输层的服务器通道实现
其他可选实现包括 OIO（阻塞 I/O）、Epoll（Linux 特定的高性能实现）等
childHandler()：
为每个新连接创建一个 ChannelPipeline
ChannelPipeline 是一个处理器链，负责处理入站和出站数据
ChannelInitializer：
一个特殊的处理器，用于初始化新创建的 Channel 的 Pipeline
当 Channel 注册到 EventLoop 后，initChannel 方法会被调用
StringDecoder：
一个内置的解码器，将接收到的 ByteBuf 转换为字符串
属于 ChannelInboundHandler，处理入站数据
ChannelInboundHandlerAdapter：
自定义处理器的基类
我们重写了 channelRead 方法来处理接收到的数据
channelRead()：
当从客户端接收到数据时被调用
参数 ctx 是 ChannelHandlerContext，用于与 ChannelPipeline 交互
参数 msg 是接收到的数据，经过 StringDecoder 处理后已经是 String 类型
bind(8010)：
绑定到指定端口并启动服务器
返回一个 ChannelFuture，可用于异步等待服务器启动完成*/




