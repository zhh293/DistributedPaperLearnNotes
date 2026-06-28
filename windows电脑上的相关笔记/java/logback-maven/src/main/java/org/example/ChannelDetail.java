package org.example;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class ChannelDetail {
    public static void main(final String[] args) throws IOException {
        /*Channel 是什么？
        你可以把 Channel 想象成现实生活中的 “管道” 或者 “传送带”。它的作用是在 Java 程序和外部资源（像文件、网络连接）之间搭建起数据传输的通道。和传统的 Stream（流）每次只能单向传输数据不同，Channel 支持双向数据传输，而且能够实现非阻塞操作，这使得它的传输效率大大提高。
        Channel 的特点
        1. 双向传输
        传统的 InputStream/OutputStream 只能进行单向的数据传输，就像单行道一样。而 Channel 就像是双向车道，既可以用来读取数据，也可以用来写入数据（不过有些 Channel，比如 FileChannel，需要通过配置来实现读写切换）。
        2. 非阻塞模式
        Channel 能够以非阻塞的方式运行。这就好比你在网上购物，不必一直守在门口等快递，而是可以先去做其他事情，等快递到了再去处理。在程序中，线程在等待数据传输完成的这段时间里，可以去执行其他任务，这样就提高了程序的运行效率。
        3. 与 Buffer 配合使用
        Channel 在传输数据时，需要和 Buffer（缓冲区）一起工作。你可以把 Buffer 想象成一个 “数据集装箱”，数据会先被存放在这个集装箱里，然后再通过 Channel 进行传输。


        FileChannel：用于对文件进行读写操作，就像是连接程序和文件的管道。
SocketChannel：用于 TCP 网络通信，类似于网络通信的 “数据专线”。
ServerSocketChannel：作为服务器端的监听通道，好比是服务器的 “门卫”，负责接收客户端的连接请求。
DatagramChannel：用于 UDP 通信，就像是 UDP 协议的 “快递员”，负责数据的发送和接收。*/
        /*String str="尚硅谷";
        //创建一个输出流
        FileOutputStream fileOutputStream = new FileOutputStream("E:\\temp.txt");
        //通过输出流获取对应的文件channel
        FileChannel channel = fileOutputStream.getChannel();
        //创建一个缓冲区
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        byteBuffer.put(str.getBytes());
        byteBuffer.flip();
        channel.write(byteBuffer);
        fileOutputStream.close();*/
        /*FileInputStream fis = new FileInputStream("E:\\temp.txt");
        FileChannel channel = fis.getChannel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int bytesRead = channel.read(buffer);
        System.out.println(new String(buffer.array(), 0, bytesRead));
        fis.close();
        channel.close();*/
        FileInputStream fileInputStream = new FileInputStream("E:\\temp.txt");
        FileChannel fileChannel = fileInputStream.getChannel();
        FileOutputStream fileOutputStream = new FileOutputStream("E:\\temp1.txt");
        FileChannel fileChannel1 = fileOutputStream.getChannel();
        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        while (fileChannel.read(byteBuffer) != -1) {
            byteBuffer.flip();
            while (byteBuffer.hasRemaining()) {
                fileChannel1.write(byteBuffer);
            }
            byteBuffer.clear();
        }
       /* while (fileChannel.read(byteBuffer) != -1) {
        }
        byteBuffer.flip();
        while (byteBuffer.hasRemaining()) {
            int write = fileChannel1.write(byteBuffer);
        }*/
        fileChannel.close();
        fileInputStream.close();
        fileOutputStream.close();



        /*)public int read(ByteBuffer dst)，从通道读取数据并放到缓冲区中
        public int write(ByteBuffer src)，把缓冲区的数据写到通道中
        public long transferFrom(ReadableByteChannel src,long position, long count)，从目标通道  中复制数据到当前通道 
        public long transferTo(long position, long count, WritableBytechannel target)，把数据从当  前通道复制给目标通道 */
    }
}
