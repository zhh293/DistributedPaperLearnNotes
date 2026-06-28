package com.zhanghonghao.normalclass.internetpachong;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

public class TalkingRoomSocket {
    public static void main(String[] args) throws IOException {
        sendThread sendThread=new sendThread();
        new Thread(sendThread).start();
        receiveThread receiveThread=new receiveThread();
        new Thread(receiveThread).start();

    }
    public static class sendThread implements Runnable{
        @Override
        public void run() {
            Socket socket= null;
            try {
                socket = new Socket("127.0.0.1",8080);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            Scanner scanner=new Scanner(System.in);
            String next = scanner.next();
            while(true){
                if(next.equals("end")){
                    break;
                }else{
                    //多线程解决聊天室
                    try {
                        socket.getOutputStream().write((next+"\n").getBytes());
                        socket.getOutputStream().flush();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println("请输入内容");
                    next = scanner.next();
                }
            }
            System.out.println(socket.getRemoteSocketAddress()+"拜拜了");
            try {
                socket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
    static class receiveThread implements Runnable{
        @Override
        public void run() {
            try {
                ServerSocket serverSocket = new ServerSocket(8888);
                Socket accept = serverSocket.accept();
                while (accept.isConnected()) {
                    InputStream inputStream = accept.getInputStream();
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                    String line="";
                    while ((line = bufferedReader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
                serverSocket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }


        }
    }
}
