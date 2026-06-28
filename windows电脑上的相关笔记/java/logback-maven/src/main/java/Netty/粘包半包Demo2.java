package Netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

public class 粘包半包Demo2 {
    public static void main(String[] args) {
        // 模拟粘包
        /*EmbeddedChannel embeddedChannel = new EmbeddedChannel(new LengthFieldBasedFrameDecoder(100,0,4,0,4)
                , new LoggingHandler(LogLevel.DEBUG));
        ByteBuf buffer = Unpooled.buffer(100);
        send(buffer,"hello,world");
        send(buffer,"hello,wor");
        embeddedChannel.writeInbound(buffer);*/
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(new LengthFieldBasedFrameDecoder(100,0,4,1,4)
                , new LoggingHandler(LogLevel.DEBUG));
        ByteBuf buffer = Unpooled.buffer(100);
        send1(buffer,"hello,world");
        send1(buffer,"hello,wor");
        embeddedChannel.writeInbound(buffer);
    }

    private static void send(ByteBuf buffer,String s) {
        byte[] bytes = s.getBytes();
        int length=bytes.length;
        buffer.writeInt(length);
        buffer.writeBytes(bytes);
    }
    private static void send1(ByteBuf buffer,String s) {
        byte[] bytes = s.getBytes();
        int length=bytes.length;
        buffer.writeInt(length);
        buffer.writeByte(1);
        buffer.writeBytes(bytes);
    }
}


/*
maxFrameLength（1024）
含义：允许接收的最大帧长度（字节）。
作用：防止恶意或错误的数据包导致内存溢出。如果接收到的帧超过此长度，会触发 TooLongFrameException。
场景：根据你的协议确定，例如你的应用最大数据包是 1024 字节。
        2. lengthFieldOffset（0）
含义：长度字段在数据包中的起始偏移量（从 0 开始）。
作用：定位长度字段的位置。
示例：
如果数据包格式是 [长度字段][数据]，则偏移量为 0（长度字段在第 0 位）。
如果格式是 [头部信息(2字节)][长度字段][数据]，则偏移量为 2。
        3. lengthFieldLength（4）
含义：长度字段本身占用的字节数。
作用：确定长度字段的大小，常见值为 1、2、4 字节。
示例：
如果长度字段是 int 类型（4 字节），则值为 4。
如果是 short 类型（2 字节），则值为 2。
        4. lengthAdjustment（0）
含义：长度字段的值需要调整的大小。
作用：修正长度计算，因为长度字段可能表示的是 “数据长度” 或 “总长度”。
计算公式：帧总长度 = 长度字段的值 + lengthAdjustment + lengthFieldOffset + lengthFieldLength。
示例：
如果长度字段表示的是 “数据部分的长度”，则 lengthAdjustment = 0（因为总长度 = 长度字段值 + 长度字段本身的偏移量和长度）。
如果长度字段表示的是 “包括长度字段在内的总长度”，则 lengthAdjustment = -lengthFieldLength（减去长度字段本身的长度）。
        5. initialBytesToStrip（4）
含义：解码后需要跳过的字节数。
作用：通常用于跳过长度字段或头部信息。
示例：
如果只需要数据部分，而长度字段在前面，设置为 4（跳过 4 字节的长度字段）。
如果数据包格式是 [长度字段(4字节)][头部(2字节)][数据]，且你需要头部和数据，则设置为 4（跳过长度字段）。*/



/*
那如果值为1的话，效果又是什么
如果 lengthAdjustment 值为 1，意味着在计算帧总长度时，需要在 “长度字段的值” 基础上额外加上 1 字节。我用一个具体例子说明：
场景示例：带可选字段的协议
假设协议格式如下：

plaintext
[固定头部(2字节)][长度字段(4字节)][数据(N字节)][可选尾部(1字节)]

长度字段：记录的是 数据长度（不包含头部、长度字段和尾部）。
可选尾部：固定 1 字节，例如用于校验或标志位。
计算逻辑：
长度字段的值 = N（数据部分长度）。
实际帧总长度 = 2（头部） + 4（长度字段） + N（数据） + 1（尾部） = N + 7。
配置参数：
lengthFieldOffset = 2（长度字段在第 2 字节开始）。
lengthFieldLength = 4（长度字段占 4 字节）。
lengthAdjustment = 1（因为长度字段只算数据，而实际帧还多了 1 字节的尾部）。如果你写了这个，之后读取到的数据中就会包含一个字节的尾部。可以避免错误。比如需要的数据部分被这些无用的数据替代并且影响下面数据的读取。
OK，我悟了

公式验证：
plaintext
        实际帧总长度 = 长度字段的值 + lengthAdjustment + lengthFieldOffset + lengthFieldLength
        = N + 1 + 2 + 4
        = N + 7
lengthAdjustment = 1 的效果：
Netty 会认为：整个帧的长度 = 长度字段的值 + 1。
适用场景：当协议中存在长度字段未包含的 “额外数据”（如固定尾部、校验和等），且这些额外数据总大小为 1 字节时，使用 lengthAdjustment = 1。
对比其他值：
lengthAdjustment	效果说明
0	长度字段的值就是数据部分的长度，帧总长度 = 长度字段值 + 长度字段本身大小 + 偏移量。
        1	帧总长度 = 长度字段值 + 1 + 长度字段本身大小 + 偏移量（适用于有 1 字节额外数据的场景）。
负数（如 -4）	长度字段的值包含了自身，需要扣除长度字段的大小（例如长度字段写的是 “总长度”）。
常见误区：
不要混淆长度字段的含义：先确定长度字段记录的是 “数据长度” 还是 “总长度”，再调整 lengthAdjustment。
调试技巧：
打印接收到的原始数据和长度字段的值。
计算预期的帧总长度。
通过调整 lengthAdjustment 让公式计算结果匹配预期。

希望这次解释更清晰！如果有具体协议格式，可以一起分析参数配置。



那netty怎么知道多余的数据在长度字段的头还是尾
Netty 本身并不知道 “多余的数据” 在长度字段的前面还是后面，它只关心 “帧的总长度”。具体数据结构的解析需要你通过 initialBytesToStrip 和后续的处理器来处理。我来详细解释：
Netty 的工作流程：
计算帧总长度：
plaintext
        帧总长度 = 长度字段的值 + lengthAdjustment + lengthFieldOffset + lengthFieldLength

Netty 根据这个公式确定一个完整帧的边界。
剥离头部字节：
initialBytesToStrip 参数指定解码后跳过多少字节，通常用于跳过长度字段或固定头部。
传递剩余数据：
剥离后剩余的数据会作为一个完整的消息传递给下一个处理器。
示例分析：
场景 1：多余数据在尾部
        plaintext
[固定头部(2字节)][长度字段(4字节)=100][数据(100字节)][可选尾部(1字节)]

参数配置：
java
        运行
new LengthFieldBasedFrameDecoder(
    1024,      // 最大帧长度
            2,         // lengthFieldOffset: 长度字段在第2字节
            4,         // lengthFieldLength: 长度字段占4字节
            1,         // lengthAdjustment: 加上尾部的1字节
            6          // initialBytesToStrip: 跳过头部(2)和长度字段(4)
);



Netty 处理结果：
计算总长度 = 100（数据） + 1（尾部） + 2（头部） + 4（长度字段） = 107 字节。
剥离前 6 字节（头部 + 长度字段），传递给下一个处理器的是：[数据(100字节)][尾部(1字节)]。
场景 2：多余数据在头部
        plaintext
[额外头部(1字节)][固定头部(2字节)][长度字段(4字节)=100][数据(100字节)]



参数配置：
java
        运行
new LengthFieldBasedFrameDecoder(
    1024,      // 最大帧长度
            3,         // lengthFieldOffset: 跳过额外头部(1)和固定头部(2)
            4,         // lengthFieldLength: 长度字段占4字节
            0,         // lengthAdjustment: 无需调整（长度字段只包含数据）
            7          // initialBytesToStrip: 跳过额外头部(1)+固定头部(2)+长度字段(4)
);

Netty 处理结果：
计算总长度 = 100（数据） + 0 + 3（偏移量） + 4（长度字段） = 107 字节。
剥离前 7 字节，传递给下一个处理器的是：[数据(100字节)]。



关键结论：

Netty 不关心数据结构：它只根据公式计算帧边界，不区分多余数据在头还是尾。
initialBytesToStrip 控制数据剥离：
如果你需要保留头部数据（如协议版本），可以设置较小的值（如 4，只跳过长度字段）。
如果你只需要核心数据，可以设置较大的值（如 6，跳过头部 + 长度字段）。
后续处理器负责解析：
Netty 解码后，你需要在后续的 ChannelHandler 中手动解析数据结构（如分离数据和尾部校验）。
搜嘎，自己分离出数据并进行进一步的处理。。。。。。。。。。。very good

*/
