package org.example.ClassDemo1.网络编程;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

public class TCPServerDemo {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket=new ServerSocket(8080);
        Socket accept = serverSocket.accept();
        InetAddress inetAddress = accept.getInetAddress();
        System.out.println(inetAddress.getHostAddress());
        InputStream inputStream = accept.getInputStream();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        String line="";
        while ((line=bufferedReader.readLine())!=null){
            System.out.println(line);
        }
        bufferedReader.close();
        inputStream.close();
        System.out.println("接收完毕");
        OutputStream outputStream = accept.getOutputStream();
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream));
        bufferedWriter.write("hello world11111111");
        bufferedWriter.flush();
        bufferedWriter.close();
        accept.close();
        outputStream.close();
        bufferedWriter.close();
        serverSocket.close();
        System.out.println("服务端结束");
    }
}
