package Netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import lombok.extern.slf4j.Slf4j;

import javax.swing.text.html.Option;
@Slf4j
public class 黏包和半包 {
    public static void main(String[] args) throws InterruptedException {
        NioEventLoopGroup eventExecutors = new NioEventLoopGroup(16);
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.option(ChannelOption.SO_RCVBUF,10);
        serverBootstrap.group(eventExecutors);
        ChannelFuture sync = serverBootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel socketChannel) throws Exception {
                socketChannel.pipeline().addLast(new FixedLengthFrameDecoder(10));
                socketChannel.pipeline().addLast(new LoggingHandler(LogLevel.DEBUG));
            }
        }).bind(8080).sync();
        ChannelFuture sync1 = sync.channel().closeFuture().sync();
        sync1.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                eventExecutors.shutdownGracefully();
                log.info("服务端已关闭");
            }
        });

    }
}
//channelActive，当链接建立时，会调用此方法，

/*

一、粘包与半包（TCP 协议特有问题）
TCP 是面向字节流的协议，没有 “消息边界”，仅负责按序传输字节流。这导致发送方的 “消息” 在接收方可能被合并或拆分，即粘包（Sticky Packet）和半包（Half Packet）。
        1. 粘包（多个消息被合并）
接收方收到的数据包是发送方多个独立消息的合并。
核心原因：TCP 为提高效率，会对小数据或短间隔消息进行合并。

发送方角度：Nagle 算法（默认启用）会合并短时间内的小数据（< MSS，最大报文段大小），积累到一定量或超时后再发送，减少网络小包数量。
接收方角度：接收缓冲区的数据未被应用层及时读取，后续消息到达后，缓冲区数据被合并，应用层一次读取多个消息。
场景示例：发送方连续发送 3 条消息（“A”“B”“C”），接收方可能一次性收到 “ABC”。
        2. 半包（单个消息被拆分）
发送方的一个完整消息被拆分成多个部分，接收方分多次收到。
核心原因：数据大小超过传输层限制，或接收方处理不及时。

MTU 限制：以太网 MTU（最大传输单元）通常为 1500 字节，TCP 段（含头部）若超过 MTU，会被 IP 层分片。若消息过大（如 2000 字节），会被拆分为两个 TCP 段，接收方可能先收到前 1500 字节，再收到剩余 500 字节。
接收缓冲区满：接收方处理速度慢于数据到达速度，缓冲区已满，后续数据需等待缓冲区释放，导致一个消息被拆分接收。
场景示例：发送方发送 “HelloWorld123456...”（2000 字节），接收方可能先收到前 1500 字节，再收到剩余 500 字节。
        3. 影响与解决方法
影响：应用层无法区分消息边界，导致解析错误（如协议字段错位、消息内容不完整）。

解决核心：在应用层定义消息边界，让接收方能够正确拆分消息。常见方案：

方法	原理	优点	缺点	适用场景
固定长度	每个消息固定大小（如 1024 字节）	实现简单	空间浪费（短消息需填充），不灵活	消息大小固定的场景（如指令包）
分隔符	用特殊符号（如\n、`	`）标记消息结束	实现较简单	分隔符可能出现在消息体中（需转义）	文本协议（如 HTTP 的\r\n）
长度字段（推荐）	消息 = 头部（固定字节数，存消息体长度

、ChannelOption（Netty 对 Option 的封装）
ChannelOption 是 Netty 框架对底层 Socket Option 的封装，专门用于配置 Netty 中的Channel（通信通道）。Netty 通过ChannelOption统一管理不同类型 Channel（如NioSocketChannel、NioServerSocketChannel）的配置，简化底层选项的设置。
常用 ChannelOption 及适用场景
Netty 的ChannelOption与底层 Socket Option 一一对应，以下是开发中高频使用的选项：

ChannelOption	对应底层 Option	作用说明	适用场景
SO_BACKLOG	TCP 监听队列大小	服务端用于设置 “未完成三次握手” 和 “已完成三次握手” 的连接队列总大小（默认 50）。值越大，能同时处理的并发连接请求越多。	高并发服务端（如 Web 服务器）
TCP_NODELAY	TCP_NODELAY	禁用 Nagle 算法，数据立即发送（低延迟）。默认false（启用 Nagle）。	实时通信（游戏、直播）
SO_KEEPALIVE	SO_KEEPALIVE	启用 TCP 保活：连接空闲超时后（默认 2 小时）发送探测包，检测对方是否在线。	长连接场景（如物联网设备）
SO_RCVBUF	SO_RCVBUF	接收缓冲区大小（字节）。需根据业务调整（如大文件传输需更大缓冲区）。	大流量数据传输（如文件服务器）
SO_SNDBUF	SO_SNDBUF	发送缓冲区大小。避免过小导致发送频繁，过大浪费内存。	批量数据发送（如日志同步）
SO_REUSEADDR	SO_REUSEADDR	允许端口重用（如服务端重启时快速绑定端口，避免 “地址已被使用” 错误）。	服务端频繁重启场景
ChannelOption 的设置方式（Netty 中）
Netty 通过Bootstrap（客户端）或ServerBootstrap（服务端）设置ChannelOption，需区分 “父 Channel” 和 “子 Channel”：

服务端（ServerBootstrap）：
option()：配置父 Channel（如NioServerSocketChannel，负责监听端口），仅适用于服务端。
childOption()：配置子 Channel（客户端连接对应的NioSocketChannel），即每个客户端连接的选项。
java
ServerBootstrap serverBootstrap = new ServerBootstrap();
serverBootstrap.group(bossGroup, workerGroup)
  .channel(NioServerSocketChannel.class)
  // 父Channel选项：设置监听队列大小
  .option(ChannelOption.SO_BACKLOG, 1024)
  // 子Channel选项：禁用Nagle（低延迟）+ 启用保活
  .childOption(ChannelOption.TCP_NODELAY, true)
  .childOption(ChannelOption.SO_KEEPALIVE, true);

客户端（Bootstrap）：
仅需option()配置客户端 Channel（如NioSocketChannel）：
java
Bootstrap clientBootstrap = new Bootstrap();
clientBootstrap.group(workerGroup)
  .channel(NioSocketChannel.class)
  // 客户端选项：设置接收缓冲区 + 禁用Nagle
  .option(ChannelOption.SO_RCVBUF, 1024 * 1024)
  .option(ChannelOption.TCP_NODELAY, true);






在TCP网络编程中，粘包和半包是常见的问题。以下是几种常用的解决方法：
1. 固定长度数据帧（Fixed Length Frame）
每个数据包都使用固定的长度
如果数据不足固定长度，用特定字符填充
接收方按固定长度读取数据
// 发送方：固定长度为100字节的数据帧
byte[] data = "Hello World".getBytes();
byte[] frame = new byte[100];
System.arraycopy(data, 0, frame, 0, data.length);
// 剩余字节填充0或其他特定字符

2. 分隔符协议（Delimiter Based）
在每个数据包末尾添加特殊分隔符
常见的分隔符有\n、\r\n等
接收方根据分隔符拆分数据包
// 示例：使用换行符作为分隔符
String message = "Hello World\n";
socket.getOutputStream().write(message.getBytes());

4. 使用专门的网络应用框架
Netty框架解决方案：
FixedLengthFrameDecoder：固定长度解码器
DelimiterBasedFrameDecoder：基于分隔符解码器
LengthFieldBasedFrameDecoder：基于长度字段解码器






在Netty中，针对TCP粘包和半包问题，提供了三种常用的解码器来处理数据帧的拆分。下面详细讲解这三个解码器的底层原理：
1. FixedLengthFrameDecoder（固定长度解码器）
原理
按照预设的固定字节数来分割数据帧
每次从入站数据中读取固定长度的字节作为一条完整消息
如果可读字节数不足预设长度，则等待更多数据到达
底层实现机制
// 在你的代码中使用方式：
socketChannel.pipeline().addLast(new FixedLengthFrameDecoder(10));
当数据到达时，解码器检查当前 ByteBuf 中可读字节是否达到指定长度（如10字节）
如果达到长度，则从缓冲区读取指定长度的字节并传递给下一个 ChannelHandler
如果不足指定长度，则不触发任何操作，等待更多数据到达
适用场景
消息长度固定不变的协议
简单的指令传输场景
2. DelimiterBasedFrameDecoder（分隔符解码器）
原理
通过指定的分隔符来确定消息边界
从数据流中查找分隔符，将两个分隔符之间的内容作为一条完整消息
支持多个分隔符和转义处理
底层实现机制
// 典型使用方式（未在你的代码中体现）：
// socketChannel.pipeline().addLast(new DelimiterBasedFrameDecoder(1024, Delimiters.lineDelimiter()));
内部维护一个累积缓冲区，持续累积接收到的数据
每次新数据到达时，在累积缓冲区中查找分隔符
找到分隔符后，将分隔符之前的内容作为完整消息传递下去
支持最大帧长度限制，防止缓冲区无限增长
关键特性
支持多种分隔符（如\n、\r\n等）
可配置是否去除分隔符
有最大长度限制防止内存溢出
3. LengthFieldBasedFrameDecoder（长度字段解码器）
原理
基于消息头部的长度字段来确定消息体的长度
消息格式：[长度字段][消息体] 或 [其他头部][长度字段][消息体]
是最灵活和常用的解码方式
底层实现机制
// 典型使用方式（未在你的代码中体现）：
/*
socketChannel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
    1024,    // maxFrameLength: 最大帧长度
    0,       // lengthFieldOffset: 长度字段偏移量
    4,       // lengthFieldLength: 长度字段长度（字节）
    0,       // lengthAdjustment: 长度调整值
    4        // initialBytesToStrip: 跳过的字节数
));
maxFrameLength：最大帧长度，防止过大帧导致内存问题
lengthFieldOffset：长度字段在消息中的偏移量
lengthFieldLength：长度字段本身的字节数（1, 2, 3, 4, 8）
lengthAdjustment：长度调整值，用于处理长度字段与实际消息体的关系
initialBytesToStrip：解析后需要跳过的字节数（通常用于去除长度字段）
工作流程
等待足够的字节以读取长度字段
读取并解析长度字段，得到消息体长度
等待足够的字节以读取完整的消息体
将完整的消息体（或包含头部的消息）传递给下一个处理器
总结
这三种解码器在Netty中都继承自 ByteToMessageDecoder，它们的核心思想是：
维护内部累积缓冲区来处理半包问题
根据特定规则识别完整的消息帧
将完整的消息传递给下一个处理器
保留未完成解析的数据等待后续数据到达
在你的代码中使用了 FixedLengthFrameDecoder(10)，这意味着每条消息都被认为是固定10字节长度
，Netty会自动将接收到的数据按每10字节切分成消息帧。
 */

