package org.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class SelectorDemo1 {
    public static void main(String[] args) throws IOException {
        //创建serversocketchannel
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        //得到一个selector对象
        Selector selector = Selector.open();
        //绑定一个端口6666，在服务器端监听
        serverSocketChannel.socket().bind(new InetSocketAddress(6666));
       //设置为非阻塞
        serverSocketChannel.configureBlocking(false);
        //把serversocketchannel注册到selector关心事件为op_ACCEPT
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        //循环等待客户端连接
        while (true){
            //这里我们等待一秒，如果没有事件发生
            if(selector.select(1000)==0){//没有事件发生
                System.out.println("等待了1秒，无连接");
                continue;
            }
            //如果返回的大于零,就获取到相关的selectionkey集合

            //通过这个集合可以反向获取通道
            Set<SelectionKey> selectionKeys = selector.selectedKeys();//返回关注事件的集合，有事件发生的selectionkey
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                if(key.isAcceptable()){ //如果是op_ACCEPT,有新的客户端连接
                    //给该客户端生成一个socketchannel
                    SocketChannel channel = serverSocketChannel.accept();
                    channel.configureBlocking(false);
                    //将socketChannel注册到selector，关注事件为OP_READ,同时给socketchannel关联一个buffer
                    channel.register(selector,SelectionKey.OP_READ,  ByteBuffer.allocate(1024));
                    System.out.println("客户端连接成功");
                }
                if(key.isReadable()){//发生OP_READ
                    //通过key，反向获取到对应的channel
                    SocketChannel channel =(SocketChannel) key.channel();
                    //把它设置为非阻塞
                    //获取到该channel关联的buffer
                    ByteBuffer attachment = (ByteBuffer)key.attachment();
                    channel.read(attachment);
                    System.out.println("from客户端" + new String(attachment.array()));
                }
                //手动从集合中移除当前的selectionkey，防止重复操作
                iterator.remove();
            }

        }
       /* public abstract class Selector implements Closeable {

            public static Selector open();//得到一个选择器对象

            publicint(selectgng timeout);/7监控所有注册的通道，当其

            中有10 操作可以进行时，将

            对应的 selectionKey加入到内部集合中并返回，参数用来

                    设置超时时间

            public Set<SelectionKey>selectedKeys();//从内部集合中得

            到所有的 SelectionKey*/




        }
}
