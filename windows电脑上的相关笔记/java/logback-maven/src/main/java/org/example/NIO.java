package org.example;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public class NIO {
    public static void main(String[] args) {
        //举例说明buffer的使用
        IntBuffer intBuffer = IntBuffer.allocate(10);
        int capacity = intBuffer.capacity();
        //向buffer中存放数据
        intBuffer.put(10);
        intBuffer.put(11);
        intBuffer.put(12);
        intBuffer.put(13);
        intBuffer.put(14);
        //从buffer中读取数据
        //将buffer转换，读写转换
        intBuffer.flip();
        while (intBuffer.hasRemaining()) {
            System.out.println(intBuffer.get());
        }
        //每个channel对应一个buffer，selector对应一个线程，一个线程对应多个channel
        //程序切换到哪个channel是由事件决定的，event就是一个很重要的概念，selector会根据不同的事件，在各个通道上切换
        //buffer就是一个内存块，底层有一个数组
        //数据的读取写入是通过buffer，这个和BIO，BIO中要么是输入流或者是输出流，不会双向流动
        //但是NIO的buffer是可以读也可以写，需要flip切换
        //channel是双向的，可以返回底层操作系统的情况，比如linux，底层的操作系统通道就是双向的
        /*”
BIO 以流的方式处理数据,而 NIO 以块的方式处理数据,块 IO 的效率比流 I0 高很多

2)BIO 是阻塞的，NIO 则是非阻塞的

BI0 基于字节流和字符流进行操作，而 NI0 基于 Channel(通道)和 Bufer(缓神区)进行操作，数据总是从通道
3)

读取到缓冲区中，或者从缓冲区写入到通道中。Selector(选择器)用于监听多个通道的事件(比如:连接请求

数据到达等)，

因此使用单个线程就可以监听多个客户端通道

*/






        /*Java Nl0 基本介绍
        1)Java NlO 全称 java non-blocking lO,是指 JDK提供的新
        API。从 JDK1.4开始，Java 提供了-
                系列改进的输入/输出
        的新特性，被统称为NO(即(Npw IO)，
                是同步非阻塞的
                2)NIO 相关类都被放在 java.nio 包及子包下，并且对原 java.io
        包中的很多类进行改写。【基本案例】
        NioBasic zip
        3)NIO 有三大核心部分:
        Channel(通道)，
        Buffer(缓冲区)
        Selector(选择器)
        或者面向 块 编程的。数据读取到一个
        4)NIO是 面向缓冲区，
        它稍后处理的缓冲区，需要时可在缓冲区中前后移动，这就
        增加了处理过程中的灵活性，使用它可以提供非阻塞式的高
                伸缩性网络*/
 /*       NIO 的非阻塞原理
        NIO 通过以下机制实现非阻塞：
（1）通道（Channel）
        双向传输：通道类似传统的流，但支持双向读写，且可以异步操作。
        示例：文件通道、套接字通道（SocketChannel、ServerSocketChannel）。
（2）缓冲区（Buffer）
        数据载体：所有数据都通过缓冲区处理，通道直接读写缓冲区。
        示例：ByteBuffer、CharBuffer等。
（3）选择器（Selector）
        核心机制：一个选择器可以监听多个通道的事件（如连接就绪、读就绪、写就绪）。
        单线程管理多连接：通过选择器，一个线程可以处理多个通道，避免创建大量线程。
        NIO 的非阻塞本质是：通过选择器轮询多个通道的就绪状态，让线程在数据未就绪时无需等待，
        继续处理其他通道，从而实现单线程管理多连接


        Java NI0的非阻塞模式，使一个线程从某通道发送请求或者读取数据，但是它仅能得

到目前可用的数据，如果目前没有数据可用时，就什么都不会获取，而不是保持线

程阻塞，所以直至数据变的可以读取之前，该线程可以继续做其他的事情。非阻塞

写也是如此，一个线程请求写入一些数据到某通道，但不需要等待它完全写入，这

个线程同时可以去做别的事情。【后面有案例说明】

通俗理解:NIO是可以做到用一个线程来处理多个操作的。假设有10000个请求过来

根据实际情况，可以分配50或者100个线程来处理。不像之前的阻塞10那样，非得分

配10000个。

HTTP2.0使用了多路复用的技术，做到同一个连接并发处理多个请求，而且并发请求

的数量比HTTP1.1大了好几个数量级。

*/
    }
}
