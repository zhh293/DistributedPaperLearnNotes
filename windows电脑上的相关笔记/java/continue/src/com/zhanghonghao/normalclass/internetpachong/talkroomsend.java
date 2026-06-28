package com.zhanghonghao.normalclass.internetpachong;

import java.io.IOException;
import java.net.*;
import java.util.Random;
import java.util.Scanner;

public class talkroomsend {
    public static void main(String[] args) throws IOException {
        Scanner sc = new Scanner(System.in);
        String next = sc.next();
        DatagramSocket datagramSocket=new DatagramSocket();
        int port=8888;
        InetAddress address=InetAddress.getByName("127.0.0.1");
        DatagramPacket datagramPacket=new DatagramPacket(next.getBytes(),next.getBytes().length,address,port);
        datagramSocket.send(datagramPacket);
        if(next.equals("886")){
            datagramSocket.close();
        }
        while (true){
            next = sc.next();
            if(next.equals("886")){
                datagramPacket=new DatagramPacket(next.getBytes(),next.getBytes().length,address,port);
                datagramSocket.send(datagramPacket);
                datagramSocket.close();
                break;
            }
            datagramPacket=new DatagramPacket(next.getBytes(),next.getBytes().length,address,port);
            datagramSocket.send(datagramPacket);
        }
    }
}
