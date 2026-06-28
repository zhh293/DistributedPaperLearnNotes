package org.example.ClassDemo1.网络编程;

import java.io.*;
import java.net.Socket;

public class TCPClientDemo {
    public static void main(String[] args) throws IOException {
        Socket socket=new Socket("127.0.0.1",8080);
        System.out.println("客户端启动");
        OutputStream outputStream = socket.getOutputStream();
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(outputStream));
        bufferedWriter.write("hello world");
        bufferedWriter.flush();
        bufferedWriter.close();
        InputStream inputStream = socket.getInputStream();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        String line="";
        while ((line=bufferedReader.readLine())!=null){
            System.out.println(line);
        }
        bufferedReader.close();
        inputStream.close();
        outputStream.close();
        socket.close();
        System.out.println("发送完毕");
    }
}
