package org.example.聊天室;

import java.io.IOException;
import java.nio.*;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Set;

public class client {
    private SocketChannel socketChannel;
    private Selector selector;
    private String host;
    private int port;
    public client() throws IOException {
        host = "127.0.0.1";
        port = 80;
        selector=Selector.open();
        socketChannel=SocketChannel.open(new java.net.InetSocketAddress(host,port));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector,java.nio.channels.SelectionKey.OP_READ);
    }
    public static void main(String[] args) throws IOException {
        client client = new client();
        client.connect();
        client.listen();
        client.sendMsg("你好呀，我是你爸爸");
    }
    public void listen() throws IOException {
        while(true){
            if(selector.isOpen()&&selector.select(1000)==0){
                continue;
            }
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            for(SelectionKey key:selectionKeys){
                if(key.isReadable()){
                    try {
                        //这里的事件对象一定是SocketChannel，所以不需要先判断对象类型了
                        SocketChannel channel =(SocketChannel) key.channel();
                        channel.configureBlocking(false);
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        channel.read(buffer);
                        System.out.println("收到服务器消息："+new String(buffer.array()));
                        break;
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                selectionKeys.remove(key);
            }
        }
    }
    public void sendMsg(String msg)throws IOException{
        ByteBuffer flip = ByteBuffer.allocate(1024).put(msg.getBytes("UTF-8")).flip();
        socketChannel.write(flip);
        System.out.println("发送消息成功");
    }
    public void connect() throws IOException {
        boolean connect1 = socketChannel.connect(new InetSocketAddress(host, port));
        if(connect1){
            System.out.println("连接成功");
        }else {
            throw new IOException("连接失败");
        }
    }
}
