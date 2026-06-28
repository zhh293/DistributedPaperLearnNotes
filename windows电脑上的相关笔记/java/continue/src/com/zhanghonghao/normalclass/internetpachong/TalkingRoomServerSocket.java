package com.zhanghonghao.normalclass.internetpachong;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

public class TalkingRoomServerSocket {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(8080);
        Socket accept = serverSocket.accept();
        Socket socket=new Socket("127.0.0.1",8888);
        while(true){
            if(!accept.isConnected()){
                break;
            }
            InputStream inputStream = accept.getInputStream();
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String sb="";
            System.out.println("哈哈哈哈");
            //这一步他妈一直阻塞，王朝烈马
            while ((sb = bufferedReader.readLine()) != null) {
                System.out.println(sb);
                socket.getOutputStream().write((sb+"\n").getBytes());
            }
        }
        serverSocket.close();
        socket.close();
    }

}
//现在你急需搞明白一点，就是这些关于流的API跟日常使用的时候为什么会出现这种阻塞现象，踏马居然不是读取到最后就结束，服了，底层原理还是的了解一下。。。。。。。
//网络编程好特喵的神奇，哎哎哎啊。。。。。。
//我这里实现的是服务器自动回复，不必多言。

//下面我来写一下两个客户端之间的交流代码，都是主动输入内容然后点击发送才会推送内容。

//这个实现起来反而更简单
/*多线程模型（推荐）
为每个客户端分配两个线程：

读取线程：专门负责接收消息。
发送线程：专门负责发送消息。

示例代码（Java）：

java
// 客户端代码
public class ChatClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public ChatClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        out = new PrintWriter(socket.getOutputStream(), true);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        // 启动读取线程
        new Thread(this::receiveMessages).start();
        // 启动发送线程（从控制台读取输入）
        new Thread(this::sendMessages).start();
    }

    private void receiveMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                System.out.println("收到消息: " + message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendMessages() {
        try {
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            String userInput;
            while ((userInput = console.readLine()) != null) {
                out.println(userInput);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}*/
















