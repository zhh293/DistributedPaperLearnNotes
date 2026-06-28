package Netty;

public class ByteBufDemo3 {
    //slice
    //
}
/*
ByteBuf 零拷贝概述
Netty 的 ByteBuf 提供了多种零拷贝 (Zero-Copy) 机制，这些机制可以在不进行数据复制的情况下对数据进行操作和传输，从而显著提高性能。零拷贝的核心思想是避免在内存中重复复制数据，而是通过共享内存区域或引用计数来实现高效操作。
零拷贝相关 API 和方法
1. CompositeByteBuf - 组合多个 ByteBuf
CompositeByteBuf 允许将多个 ByteBuf 组合成一个逻辑上的 ByteBuf，而不需要复制它们的内容。这在需要合并多个缓冲区的场景下非常有用，比如 HTTP 分块传输。

java
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;

public class CompositeByteBufExample {
    public static void main(String[] args) {
        // 创建两个ByteBuf
        ByteBuf header = Unpooled.buffer(10);
        header.writeBytes("Header: ".getBytes());

        ByteBuf body = Unpooled.buffer(20);
        body.writeBytes("Hello, World!".getBytes());

        // 创建CompositeByteBuf并添加两个ByteBuf
        CompositeByteBuf composite = Unpooled.compositeBuffer();
        composite.addComponents(true, header, body); // true表示自动递增阅读器索引

        // 直接操作CompositeByteBuf
        System.out.println(composite.toString(io.netty.util.CharsetUtil.UTF_8));

        // 释放资源
        composite.release();
    }
}
2. slice () - 创建共享内存的子缓冲区
slice () 方法创建一个新的 ByteBuf，它和原始 ByteBuf 共享底层内存，但有独立的读写索引。这在需要处理部分数据时非常高效。

java
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class SliceExample {
    public static void main(String[] args) {
        ByteBuf buffer = Unpooled.buffer(10);
        buffer.writeBytes("abcdefghij".getBytes());

        // 创建从索引2开始，长度为4的子缓冲区
        ByteBuf slice = buffer.slice(2, 4);

        // 修改子缓冲区会影响原始缓冲区
        slice.setByte(0, 'X');

        System.out.println(buffer.toString(io.netty.util.CharsetUtil.UTF_8)); // 输出：abXdefghij
        System.out.println(slice.toString(io.netty.util.CharsetUtil.UTF_8)); // 输出：Xdef

        // 释放资源
        buffer.release();
        // 注意：slice不需要单独释放，因为它和原始缓冲区共享内存
    }
}
3. duplicate () - 创建共享内存的完整缓冲区副本
duplicate () 方法创建一个新的 ByteBuf，它和原始 ByteBuf 共享底层内存和索引，但有独立的读写标记。

java
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class DuplicateExample {
    public static void main(String[] args) {
        ByteBuf buffer = Unpooled.buffer(10);
        buffer.writeBytes("abcdefghij".getBytes());

        // 创建完整副本
        ByteBuf duplicate = buffer.duplicate();

        // 修改副本会影响原始缓冲区
        duplicate.setByte(0, 'X');

        System.out.println(buffer.toString(io.netty.util.CharsetUtil.UTF_8)); // 输出：Xbcdefghij
        System.out.println(duplicate.toString(io.netty.util.CharsetUtil.UTF_8)); // 输出：Xbcdefghij

        // 释放资源
        buffer.release();
        // 注意：duplicate不需要单独释放，因为它和原始缓冲区共享内存
    }
}
4. wrappedBuffer () - 包装现有数据创建零拷贝缓冲区
wrappedBuffer () 方法可以包装 Java 数组、字节数组或其他 ByteBuf，创建一个零拷贝的 ByteBuf。

java
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class WrappedBufferExample {
    public static void main(String[] args) {
        // 包装字节数组
        byte[] array = "Hello, Netty!".getBytes();
        ByteBuf wrapped = Unpooled.wrappedBuffer(array);

        // 修改ByteBuf会影响原始数组
        wrapped.setByte(0, 'J');

        System.out.println(new String(array)); // 输出：Jello, Netty!

        // 包装多个ByteBuf
        ByteBuf buf1 = Unpooled.buffer(5);
        buf1.writeBytes("Hello".getBytes());

        ByteBuf buf2 = Unpooled.buffer(6);
        buf2.writeBytes(" World".getBytes());

        ByteBuf combined = Unpooled.wrappedBuffer(buf1, buf2);
        System.out.println(combined.toString(io.netty.util.CharsetUtil.UTF_8)); // 输出：Hello World

        // 释放资源
        combined.release();
        // buf1和buf2不需要单独释放，因为它们被包含在combined中
    }
}


读和写的误解

我最初在认识上有这样的误区，认为只有在 netty，nio 这样的多路复用 10 模型时，读写才不会相互阻塞，才可以

实现高效的双向通信，但实际上，Java Socket 是全双工的:在任意时刻，线路上存在A 到 B和 B到 A 的双向

信号传输。即使是阻塞 10，读和写是可以同时进行的，只要分别采用读线程和写线程即可，读不会阻塞写、写也

不会阻塞读



*/
