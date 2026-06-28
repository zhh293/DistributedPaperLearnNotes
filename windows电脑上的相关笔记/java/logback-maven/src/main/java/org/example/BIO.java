package org.example;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BIO {

    public static void main(String[] args) throws IOException {
        //线程池机制
        //思路，
        //创建一个线程池
        ExecutorService executor = Executors.newCachedThreadPool();
        //如果有客户端连接，就创建一个线程，与之通讯(单独写一个方法)
        ServerSocket serverSocket = new ServerSocket(6666);
        System.out.println("Listening on port 6666");
        while (true) {
            //监听,等待客户端链接
            System.out.println("Waiting for connection");
           final Socket accept = serverSocket.accept();
           System.out.println("Accepted connection from " + accept);
           executor.submit(new Runnable() {
               public void run() {
                   handler(accept);
               }
           });
        }
    }
    //编写一个handler方法，和客户端通讯
    public static void handler(Socket socket) {
        try {
            byte[] buffer = new byte[1024];
            //通过socket获取输入流
            InputStream inputStream = socket.getInputStream();
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String inputLine;
            while ((inputLine = bufferedReader.readLine()) != null) {
                System.out.println(inputLine);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.out.println("Closing connection");

            try {
                socket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
   /* Java Bl0 问题分析

    每个请求都需要创建独立的线程，与对应的客户端进行数据 Write
      2)
    当并发数较大时，需要创建大量线程来处理连接，
    系统资源占用较犬。
    3)连接建立后，如果当前线程暂时没有数据可读，则线程就阻塞
    在 Read 操作上，造成线程资源浪费*/







   /* 方法二：使用 Telnet（需先启用）
    Windows 默认没有启用 Telnet 客户端，你需要手动开启：

    powershell
    dism.exe /online /enable-feature /featurename:TelnetClient /norestart

    模拟客户端发送信息的步骤：
    启动 Telnet 客户端并连接服务器：
    powershell
    telnet 127.0.0.1 6666
    如果屏幕没有反应，先按Enter键。之后输入测试消息：
    plaintext
    Hello from Telnet!
    按Enter键发送消息。若要退出 Telnet，先按Ctrl + ]，再输入quit。*/
}