package org.example.聊天室;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Set;

public class Server {
    private ServerSocketChannel server;
    private Selector selector;
    private String host;
    private int port;
    public Server() throws IOException {
        this.host= "localhost";
        this.port=80;
        this.selector=Selector.open();
        this.server=ServerSocketChannel.open();
        this.server.bind(new InetSocketAddress(host,port));
        this.server.configureBlocking(false);
        this.server.register(selector, SelectionKey.OP_ACCEPT);
    }
    public static void main(String[] args) throws Exception {
        new Server().listen();
    }
    public void listen() throws Exception{
        while(true){
            if(selector.select(1000)==0){
                continue;
            }
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            for (SelectionKey selectionKey : selectionKeys) {
                if(selectionKey.isAcceptable()){
                    //处理连接事件
                    ServerSocketChannel channel = (ServerSocketChannel)selectionKey.channel();
                    SocketChannel accept = channel.accept();
                    SocketAddress remoteAddress = accept.getRemoteAddress();
                    System.out.println("客户端连接成功，客户端地址："+remoteAddress);
                    accept.configureBlocking(false);
                    accept.register(selector,SelectionKey.OP_READ);
                }else if(selectionKey.isReadable()){
                    //处理读事件
                    SocketChannel channel = (SocketChannel)selectionKey.channel();
                    ByteBuffer buffer = ByteBuffer.allocate(1024);
                    channel.read(buffer);
                    String msg = new String(buffer.array());
                    System.out.println("收到客户端消息："+msg);
                    //向其他客户端转发消息
                    broadcast(channel,msg);
                }
            }
        }
    }
    public void broadcast(SocketChannel channel,String msg){
        Set<SelectionKey> keys = selector.keys();
        for (SelectionKey key : keys) {
            Channel targetChannel = key.channel();
            if(targetChannel instanceof SocketChannel && targetChannel!=channel){
                SocketChannel socketChannel = (SocketChannel)targetChannel;
                try {
                    socketChannel.write(ByteBuffer.wrap(msg.getBytes()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
