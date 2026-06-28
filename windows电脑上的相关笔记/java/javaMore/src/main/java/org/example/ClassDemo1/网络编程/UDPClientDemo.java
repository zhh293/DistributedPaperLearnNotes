package org.example.ClassDemo1.网络编程;

import java.net.*;

public class UDPClientDemo {
    public static void main(String[] args) {
        //创建一个DatagramSocket对象，准备发送数据
        //创建客户端

        try {
            DatagramSocket datagramSocket = new DatagramSocket();
            String msg = "hello,UDP";
            byte[] bytes = msg.getBytes();
            InetSocketAddress socketAddress = new InetSocketAddress("127.0.0.1", 8080);
            datagramSocket.send(new DatagramPacket(bytes, bytes.length, socketAddress));
            datagramSocket.close();
            System.out.println("发送完毕");
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
