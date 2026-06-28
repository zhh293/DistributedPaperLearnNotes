package com.zhanghonghao.normalclass.internetpachong;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class TCP {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket=new ServerSocket(8000);
        Socket socket=new Socket("127.0.0.1",8000);
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write("hello".getBytes());
        outputStream.flush();
        outputStream.close();
        Socket accept = serverSocket.accept();
        InputStream inputStream=accept.getInputStream();
        BufferedReader bufferedReader=new BufferedReader(new InputStreamReader(inputStream));
        String line=bufferedReader.readLine();
        System.out.println(line);
        socket.close();
        serverSocket.close();
    }
}
