  package NIO群聊系统;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class serverDuan {
    private Selector  selector;
    private int port=6667;
    private ServerSocketChannel serverSocketChannel;
    public serverDuan() throws Exception{
        //初始化工作
        //得到选择器
        selector=Selector.open();
        serverSocketChannel=ServerSocketChannel.open();
        //绑定端口
        serverSocketChannel.socket().bind(new InetSocketAddress(port));
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }
    //读取数据的代码逻辑
    public void readData(SelectionKey key) throws Exception{
        SocketChannel channel = (SocketChannel)key.channel();
        ByteBuffer  buffer = ByteBuffer.allocate(1024);
        int read = channel.read(buffer);
        if(read>0){
            System.out.println("从客户端读取数据："+new String(buffer.array()));
            //向其他客户端转发消息，专门写一个方法处理
            sendInfoToOtherClient(channel,new String(buffer.array()));
        }


    }


    /*1. ClassCastException 错误原因
    在 sendInfoToOtherClient 方法中，你遍历了 selector.keys() 集合，
    这个集合包含了所有注册到选择器的键，包括 ServerSocketChannel 对应的键。
    当尝试将 ServerSocketChannel 强制转换为 SocketChannel 时就会抛出异常：*/
    private void sendInfoToOtherClient(SocketChannel exceptionSelf, String s) throws IOException {
        System.out.println("服务器转发消息");
        for (SelectionKey key:selector.keys()) {
            Channel channel1 = key.channel();
            //排除自己
            if (channel1 instanceof SocketChannel && channel1 != exceptionSelf) {
                SocketChannel channel = (SocketChannel) channel1;
                System.out.println("转发数据给" + channel.getRemoteAddress());
                channel.write(ByteBuffer.wrap(s.getBytes()));
            }

        }
    }

    //完成监听的代码
    public void listen() throws Exception{
        try {
            while (true){
                if(selector.select(1000)==0){
                    System.out.println("服务器等待1秒，无连接");
                }
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectionKeys.iterator();
                while (iterator.hasNext()){
                    SelectionKey key =  iterator.next();
                    if(key.isAcceptable()){
                        System.out.println("有新的客户端连接");
                        SocketChannel channel = serverSocketChannel.accept();
                        channel.configureBlocking(false);
                        ByteBuffer attachment = ByteBuffer.allocate(1024);
                        channel.register(selector,SelectionKey.OP_READ,attachment);
                        System.out.println("客户端连接成功"+channel.getRemoteAddress());
                    }
                    if(key.isReadable()){
                        try {
                            //处理读取的方法
                            readData(key);
                        }catch (Exception e){
                            System.out.println("客户端断开连接");
                            throw new RuntimeException(e);
                        }
                    }
                    iterator.remove();
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            // 确保资源被释放
            if (selector != null) {
                selector.close();
            }
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        serverDuan serverDuan = new serverDuan();
        serverDuan.listen();
    }
}
