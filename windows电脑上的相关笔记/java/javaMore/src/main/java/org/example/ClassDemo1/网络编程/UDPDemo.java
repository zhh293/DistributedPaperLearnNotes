package org.example.ClassDemo1.网络编程;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.spi.InetAddressResolver;

public class UDPDemo {
    public static void main(String[] args) throws IOException {
        DatagramSocket socket=new DatagramSocket(8080);
        /*String msg="hello world!酷狗";
        byte[] bytes=msg.getBytes();*/
        DatagramPacket packet=new DatagramPacket(new byte[1024], 1024);
        socket.receive(packet);
        System.out.println(new String(packet.getData()));
        socket.close();
        System.out.println("接收完毕");

    }
}
