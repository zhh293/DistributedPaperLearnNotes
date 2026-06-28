package com.zhanghonghao.normalclass.internetpachong;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TCPPratice1k {
    public static void main(String[] args) throws IOException {
        //服务器端
        ServerSocket serverSocket=new ServerSocket(8080);
        Socket accept = serverSocket.accept();
        InputStreamReader isr=new InputStreamReader(accept.getInputStream());
        OutputStreamWriter outputStreamWriter=new OutputStreamWriter(new FileOutputStream("E:\\temp.txt"));
        char[] chars=new char[1024];
        int length=0;
        while ((length=isr.read(chars))!=-1){
            outputStreamWriter.write(chars,0,length);
            outputStreamWriter.flush();
        }
        System.out.println("文件接收完成");
        OutputStream outputStream = accept.getOutputStream();
        outputStream.write("completed".getBytes());
        outputStream.flush();
        outputStream.close();
        isr.close();
        outputStreamWriter.close();
        serverSocket.close();

    }
}
