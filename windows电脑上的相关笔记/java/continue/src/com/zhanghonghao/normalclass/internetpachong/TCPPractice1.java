package com.zhanghonghao.normalclass.internetpachong;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;

public class TCPPractice1 {
    public static void main(String[] args) throws IOException {
        //客户端
        Socket socket=new Socket("127.0.0.1",8080);
        InputStreamReader readFile=new InputStreamReader(new FileInputStream("E:\\temp1.txt"), Charset.forName("UTF-8"));
        BufferedReader bufferedReader=new BufferedReader(readFile);
        StringBuilder stringBuilder=new StringBuilder();
        String line=null;
        while((line=bufferedReader.readLine())!=null){
            stringBuilder.append(line);
        }
        String string = stringBuilder.toString();
        socket.getOutputStream().write(string.getBytes(Charset.forName("UTF-8")));
        System.out.println("你好，我到这里了");
//        socket.getOutputStream().close();
        socket.shutdownOutput();
        while(true){
            InputStream inputStream = socket.getInputStream();
            if(inputStream!=null){
                byte[] buffer = new byte[1024];
                int len = 0;
                while((len=inputStream.read(buffer))!=-1){
                    System.out.println(new String(buffer,0,len));
                }
                break;
            }
        }
        socket.close();
    }
//    关键是为什么，明明我的客户端文件已经读取完了，没有内容了之后，服务器端读取不到内容之后，read方法不就会返回-1吗
//    细节大放送
    /*核心原因：网络流与文件流的结束标记机制不同
            文件流的结束标记
    当你读取本地文件时，FileInputStream.read()在文件末尾会返回 - 1，因为文件内容长度是固定的，读完就结束了。
    网络流的结束标记
    网络流（Socket.getInputStream()）没有 "文件末尾" 的概念。服务器端的read()方法只会在以下情况返回 - 1：
    客户端显式关闭整个 Socket 连接（socket.close()）
    客户端半关闭输出流（socket.shutdownOutput()）
    客户端程序崩溃或网络连接断开*/
}
