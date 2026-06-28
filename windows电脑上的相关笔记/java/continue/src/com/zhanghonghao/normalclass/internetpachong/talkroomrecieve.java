package com.zhanghonghao.normalclass.internetpachong;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class talkroomrecieve {
    public static void main(String[] args) throws IOException {
        DatagramSocket socket=new DatagramSocket(8888);
        byte[] buf=new byte[1024];
        DatagramPacket packet=new DatagramPacket(buf,0,buf.length);
        socket.receive(packet);
        byte[] data = packet.getData();
        String str=new String(data,0,packet.getLength());
        System.out.println(str);
        if(str.equals("886")){
            socket.close();
        }
        while (true) {
            if(str.equals("886")){
                socket.close();
                break;
            }
            socket.receive(packet);
            str=new String(packet.getData(),0,packet.getLength());
            System.out.println(str);
        }

    }
}
