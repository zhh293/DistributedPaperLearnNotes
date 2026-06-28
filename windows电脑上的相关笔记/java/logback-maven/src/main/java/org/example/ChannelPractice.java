package org.example;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;

public class ChannelPractice {
    public static void main(String[] args) throws IOException {
       /* //创建相关流

        FileInputStream fileInputstream = new FileInputStream("d:\\a.jpg");

        FileOutputStream fileoutputstream = new FileOutputStream("d:\\a2.jpg");

//获取各个流对应的filechannel
        FileChannel channel = fileoutputstream.getChannel();
        FileChannel channel1 = fileInputstream.getChannel();
//使用transferForm完成拷贝

       channel.transferFrom(channel1,0,channel1.size());
       channel.close();
       channel1.close();
       fileoutputstream.close();
       fileInputstream.close();*/


        /*ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        byteBuffer.putInt(1);
        byteBuffer.putChar('a');
        byteBuffer.putFloat(1.0f);
        byteBuffer.flip();
        System.out.println(byteBuffer.getInt());
        System.out.println(byteBuffer.getInt());
        System.out.println(byteBuffer.getChar());*/
        /*3. 避免创建额外的缓冲区类型
        如果每种数据类型都需要单独的 Buffer（如 IntBuffer、CharBuffer 等），会导致 API 变得复杂且冗余。
        ByteBuffer 的 putInt() 等方法允许在同一个缓冲区中灵活处理不同类型的数据，无需频繁切换缓冲区类型。
        4. 直接操作底层字节数据
        对于网络通信或文件操作，数据通常以字节流形式传输。ByteBuffer 的 putInt() 等方法可以方便地将高级数据类型转换为字节流，例如：
        java
// 将 int 和 char 写入 ByteBuffer 后发送到网络
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        buffer.putInt(123); // 写入 int
        buffer.putChar('A'); // 写入 char
        channel.write(buffer); // 通过 Channel 发送


        5. 与其他 Buffer 的关系
        IntBuffer、CharBuffer 等是 ByteBuffer 的视图缓冲区，它们通过 ByteBuffer.asIntBuffer() 等方法创建，共享同一块内存区域。
        这些视图缓冲区提供了更便捷的类型化操作，但本质上仍依赖于 ByteBuffer 的底层字节操作。*/

    }

 /*   关于Buffer和 Channel的注意事项和细节

1)ByteBuffer 支持类型化的put 和 get,put 放入的是什么数据类型，get就应该使用

    相应的数据类型来取出，否则可能有 BufferUnderflowException 异常。[举例说明]

            2)可以将一个普通Buffer 转成只读Buffer[举例说明]

            3)NIO 还提供了 MappedByteBuffer，可以让文件直接在内存(堆外的内存)中进

    行修改，而如何同步到文件由NIO 来完成.[举例说明]

    前面我们讲的读写操作，都是通过一个Buffer 完成的，NIO 还支持 通过多个

    Buffer (即 Buffer 数组) 完成读写操作，即 Scattering 和 Gatering 【举例说明】*/
/*    那这种手动输入实用性不太小了吗，直接全部用put方法不可以吗
    你的疑问很有道理，直接使用 put() 方法逐个写入字节确实可行，但手动处理多字节数据类型（如 int、double）时，putInt() 等方法的优势就体现在效率、安全性和易用性上。以下是具体对比：
            1. 手动使用 put() 的问题
    假设要写入一个 int 值（如 123456789），手动实现需要：

    java
    // 手动将int拆分为4个字节（大端序）
    int value = 123456789;
buffer.put((byte) (value >> 24)); // 最高字节
buffer.put((byte) (value >> 16)); // 次高字节
buffer.put((byte) (value >> 8));  // 次低字节
buffer.put((byte) (value));       // 最低字节

    问题：

    繁琐：每个 int 都需要 4 行代码，long 需要 8 行。
    易错：容易写错位移量或字节顺序。
    字节序难维护：手动切换大端 / 小端序需要重写逻辑。
            2. 使用 putInt() 的优势
    java
// 使用putInt()自动处理字节序
buffer.order(ByteOrder.BIG_ENDIAN); // 设置一次字节序
buffer.putInt(123456789); // 一行代码搞定


    优势：

    简洁：一行代码替代多行手动位移。
    安全：自动处理字节序，减少人为错误。
    高效：底层实现经过优化，性能更优。*/

}
