package Netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class ByteBufDemo1 {
    public static void main(String[] args) {
        ByteBuf buffer = ByteBufAllocator.DEFAULT.buffer();
        System.out.println(buffer.capacity());
        System.out.println(buffer.maxCapacity());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("a");
        }
        buffer.writeBytes(sb.toString().getBytes());
        System.out.println(buffer.capacity());
    }
}

/*
扩容规则是

如何写入后数据大小未超过 512，则选择下一个 16 的整数倍，例如写入后大小为 12，则扩容后 capacity 是

16

如果写入后数据大小超过 512，则选择下一个 2^n，例如写入后大小为 513，则扩容后 capacity 是

2^10=1024(2^9=512 已经不够了)  扩容不能超过 max capacity 会报错

*/

/*
1. 双指针设计
ByteBuf 采用读写分离的指针：

readerIndex：读取数据的起始位置。
writerIndex：写入数据的起始位置。
capacity：缓冲区的容量。

它们之间存在这样的关系：0 ≤ readerIndex ≤ writerIndex ≤ capacity。
        2. 内存分类
堆内存（HeapBuffer）：数据存于 JVM 堆中，创建和销毁速度快，适合处理纯内存操作。
直接内存（DirectBuffer）：数据存于操作系统物理内存，避免了内存复制，提升了 I/O 效率，不过分配和释放的成本较高。
可以使用下面的代码来创建池化基于堆的

ByteBuf buffer = ByteBufA1locator.DEFAULT.heapBuffer(10);

也可以使用下面的代码来创建池化基于直接内存的 ByteBuf

ByteBuf buffer = ByteBufA1locator .DEFAULT.directBuffer(10);


复合缓冲区（CompositeByteBuf）：能够将多个 ByteBuf 组合成一个逻辑上的缓冲区，避免了内存复制。


        3. 内存管理
Netty 运用引用计数（Reference Counting）机制来管理 ByteBuf 的生命周期：

retain()：增加引用计数。
release()：减少引用计数，当计数为 0 时释放内存。
二、主要 API
1. 创建 ByteBuf
java
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
ctx.alloc().buffer(10);//这样也可以创建ByteBuf。非常nice
// 创建堆内存缓冲区
ByteBuf heapBuf = Unpooled.buffer(1024);

// 创建直接内存缓冲区
ByteBuf directBuf = Unpooled.directBuffer(1024);

// 包装现有字节数组
ByteBuf wrappedBuf = Unpooled.wrappedBuffer("Hello".getBytes());

// 创建复合缓冲区
CompositeByteBuf compositeBuf = Unpooled.compositeBuffer();
2. 写入数据
        java
ByteBuf buf = Unpooled.buffer(10);

// 写入基本类型
buf.writeByte(1);
buf.writeInt(100);
buf.writeBytes("Hello".getBytes());
int writeCharSequence(CharSequence sequerice, Charset charset)  写入字符串
// 写入其他ByteBuf
        buf.writeBytes(anotherBuf);

// 检查可写空间
if (buf.writableBytes() >= 4) {
        buf.writeInt(42);
}
        3. 读取数据
        java
// 读取前检查可读字节数
if (buf.readableBytes() >= 4) {
int value = buf.readInt();
}

// 标记和重置读取位置
        buf.markReaderIndex();
int firstValue = buf.readInt();
buf.resetReaderIndex(); // 回到标记位置
int sameValue = buf.readInt();

// 按索引读取（不改变readerIndex）
byte b = buf.getByte(0);
4. 释放内存
        java
// 使用完后释放ByteBuf
buf.release();

// 安全释放（避免重复释放）
ReferenceCountUtil.release(buf);
5. 转换操作
        java
// 转换为字节数组
byte[] bytes = new byte[buf.readableBytes()];
buf.readBytes(bytes);

// 转换为字符串
String str = buf.toString(StandardCharsets.UTF_8);

// 转换为NIO ByteBuffer
ByteBuffer nioBuffer = buf.nioBuffer();
6. 其他实用方法
        java
// 清空缓冲区（重置指针）
buf.clear();

// 丢弃已读字节（压缩缓冲区）
buf.discardReadBytes();

// 复制缓冲区（浅拷贝，共享内存）
ByteBuf slice = buf.slice();

// 复制缓冲区（深拷贝）
ByteBuf copy = buf.copy();
三、内存管理最佳实践
1. 引用计数规则
谁最后使用 ByteBuf，谁就负责释放它。
在 ChannelHandler 的channelRead()方法中，处理完消息后要调用ctx.fireChannelRead(msg)或者ReferenceCountUtil.release(msg)。
        2. try-with-resources 模式
java
try (ByteBuf buf = Unpooled.buffer(1024)) {
        buf.writeBytes("Hello".getBytes());
        // 使用buf
        } // 自动释放
        3. 避免内存泄漏
使用内存检测器：

java
ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);


借助 IDE 插件（如 Netty Leak Detector）来检测未释放的 ByteBuf。
四、与 ByteBuffer 对比
特性	ByteBuf	ByteBuffer
读写指针	分离的 readerIndex 和 writerIndex	通过 flip () 切换读写模式
内存分配	支持堆内存和直接内存	仅支持直接内存（DirectByteBuffer）
扩容机制	自动扩容	需要手动创建新缓冲区并复制数据
引用计数	有	无
API 易用性	更丰富、更直观	功能有限*/



/*
一、池化 ByteBuf
1. 核心概念
池化 ByteBuf 使用内存池来复用 ByteBuf 实例，避免了频繁创建和销毁对象带来的开销。Netty 的内存池基于 jemalloc 算法实现，能够高效地管理直接内存和堆内存。
        2. 工作原理
内存池：预先分配一大块内存，将其划分为多个小块（Chunk）。
对象复用：使用完的 ByteBuf 不会被立即释放，而是返回到池中，等待下次使用。
引用计数：通过retain()和release()方法管理对象生命周期


二、非池化 ByteBuf
1. 核心概念
非池化 ByteBuf 在每次使用时都会创建新的内存区域，使用完毕后由垃圾回收器回收内存。
        2. 工作原理
按需分配：每次调用Unpooled.buffer()时，都会分配新的内存。
GC 回收：对象不再被引用时，由 JVM 垃圾回收器回收内存。
        3. 如何创建
        java
// 创建非池化堆内存ByteBuf
ByteBuf heapBuf = Unpooled.buffer(1024);

// 创建非池化直接内存ByteBuf
ByteBuf directBuf = Unpooled.directBuffer(1024);
4. 优点
简单易用：无需担心引用计数和内存释放问题。
适合短期使用：对于生命周期短暂的小缓冲区，开销可以接受。
        5. 缺点
性能开销大：频繁创建和销毁对象会增加 GC 压力。
内存碎片：长期运行可能导致内存碎片问题。
三、对比分析
特性	池化 ByteBuf	非池化 ByteBuf
内存分配方式	从内存池复用	每次创建新对象
性能	高（减少分配和 GC 开销）	低（频繁分配和回收）
内存使用	更高效（减少碎片）	可能产生碎片
复杂度	高（需管理引用计数）	低（自动 GC）
适用场景	高并发、长连接、大流量场景	短连接、小数据量、简单应用*/



/*

1. 什么是字节序？
当数据超过一个字节时（像 int、long 等类型），就需要按照一定顺序来存储各个字节。字节序就是用来确定存储顺序的规则。
        2. 大端（Big Endian）
大端也被称为 “网络字节序”，它的存储规则是：数据的高位字节存放在内存的低地址处，低位字节存放在内存的高地址处。

可以把大端存储方式想象成我们写数字的习惯，比如数字 1234，我们会先写高位的 1，再依次写 2、3、4。
        3. 小端（Little Endian）
小端的存储规则与大端相反：数据的低位字节存放在内存的低地址处，高位字节存放在内存的高地址处。

这类似于我们说中文数字的习惯，比如 “一千二百三十四”，我们会从低位的 “三十四” 开始说，然后再是 “一百”“一千”。
二、直观示例
假设我们要存储一个 16 位整数 0x1234（0x 表示十六进制），这个数由两个字节组成：高位字节是 0x12，低位字节是 0x34。

在内存中的存储情况如下表所示：

内存地址	大端存储（高位在前）	小端存储（低位在前）
        0x1000	0x12（高位字节）	0x34（低位字节）
        0x1001	0x34（低位字节）	0x12（高位字节）
三、现实类比
1. 大端（高位在前）
就像写日期的顺序，例如 2025 年 7 月 12 日，我们会先写年份（高位），再写月份，最后写日期（低位）。
国际标准的日期格式 “YYYY-MM-DD” 就是大端序的体现。
        2. 小端（低位在前）
类似于中文的地址写法，比如 “北京市海淀区中关村”，我们会从最具体的区域（低位）开始写，然后逐级写到更大的区域（高位）。


在 Java 中，可以使用ByteBuffer类来处理字节序：

java
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// 创建一个ByteBuffer
ByteBuffer buffer = ByteBuffer.allocate(4);

// 设置为大端序（网络字节序）
buffer.order(ByteOrder.BIG_ENDIAN);
buffer.putInt(0x12345678);

// 设置为小端序
buffer.order(ByteOrder.LITTLE_ENDIAN);
buffer.putInt(0x12345678);*/
