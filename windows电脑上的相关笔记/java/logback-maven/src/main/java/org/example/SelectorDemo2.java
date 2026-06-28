package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class SelectorDemo2 {
    public static void main(String[] args) throws IOException, InterruptedException {
        SocketChannel socketChannel = SocketChannel.open();
        //设置非阻塞
        socketChannel.configureBlocking(false);
        //提供服务器的ip和端口
        if(!socketChannel.connect(new InetSocketAddress("127.0.0.1", 6666))){
            System.out.println("连接需要时间，客户端不会阻塞，可以做其他工作");
            while(!socketChannel.finishConnect()){
                System.out.println("正在连接。。。");
            }
        }
        //如果连接成功，就发送数据
        String str = "hello,我是客户端";
        ByteBuffer  buffer = ByteBuffer.wrap(str.getBytes());
        //发送数据，将buffer的数据写入到channel中
        socketChannel.write(buffer);
        Thread.sleep(2000);
        System.out.println("发送数据成功");
        while (true){
            Thread.sleep(1000);

        }


    }
}
