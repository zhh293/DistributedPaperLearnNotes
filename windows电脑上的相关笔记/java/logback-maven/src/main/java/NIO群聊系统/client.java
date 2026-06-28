package NIO群聊系统;

import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Scanner;

public class client {
    private final  String host = "127.0.0.1";//服务器的ip
    private final  int port = 6667;//服务器的端口号
    private final Selector selector;
    private final SocketChannel  socketChannel;
    private final String userName;
    public client() throws Exception{
        selector = Selector.open();
        socketChannel = SocketChannel.open(new java.net.InetSocketAddress(host,port));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector,java.nio.channels.SelectionKey.OP_READ);
        userName = socketChannel.getLocalAddress().toString().substring(1);
        System.out.println(userName+"上线了");
    }

    //可以向服务器发送消息
    public void sendMsg(String msg) throws Exception{
        msg  = userName+"说："+msg;
        socketChannel.write(java.nio.ByteBuffer.wrap(msg.getBytes()));
        System.out.println("发送消息成功");
    }
    public void listen() throws Exception{
        while(true){
            if(selector.select(1000)==0){
                continue;
            }
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                if(key.isReadable()){
                    try {
                        //这里的事件对象一定是SocketChannel，所以不需要先判断对象类型了
                        SocketChannel channel =(SocketChannel) key.channel();
                        channel.configureBlocking(false);
                        ByteBuffer buffer = ByteBuffer.allocate(1024);
                        channel.read(buffer);
                        System.out.println("收到服务器消息："+new String(buffer.array()));
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }
                iterator.remove();
            }
        }


    }
    public static void main(String[] args) throws Exception{
        client client = new client();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    client.listen();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
        while(true){
            Scanner scanner = new Scanner(System.in);
            String msg = scanner.next();
            if(msg.equals("exit")){
                break;
            }
            client.sendMsg(msg);
        }
    }
}
