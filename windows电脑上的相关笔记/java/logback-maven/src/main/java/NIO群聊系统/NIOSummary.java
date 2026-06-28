package NIO群聊系统;

public class NIOSummary {
   /* 想象一下：你是一个交通调度员（线程），要管理 多条道路（Channel，比如客户端连接） 的通行需求（事件，比如 “有车要上高速”“路口车满了要疏导”）。
    如果每条路都派一个人盯着（传统 BIO 模式，每个连接一个线程），人力成本太高；
    而 选择器就是 “智能指挥中心”，让你坐在中控室，同时监控所有道路的状态，只处理有需求的道路，效率翻倍！*/
   /*Java NIO 中，选择器的核心类是 java.nio.channels.Selector，配合 SelectionKey（事件标识）和 SelectableChannel（可被监控的通道，如 SocketChannel、ServerSocketChannel）工作。以下是最常用的 API：
            1. 创建选择器：Selector.open()
    Selector selector = Selector.open();
    类比：建一个 “交通指挥中心”，准备开始监控道路。*/
   /* 2. 通道注册到选择器：channel.register(selector, 事件)
    通道（如 ServerSocketChannel、SocketChannel）必须先注册到选择器，才能被监控。

    示例（服务器监听新连接）：
    ServerSocketChannel serverChannel = ServerSocketChannel.open();
serverChannel.configureBlocking(false); // 设为非阻塞
serverChannel.bind(new InetSocketAddress(8080)); // 绑定端口

    // 注册到选择器，监听【新连接事件】（OP_ACCEPT）
    SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    事件：选择要监听的事件（4 种核心事件）：
OP_ACCEPT：ServerSocketChannel 等待新连接（类似 “高速入口等车来”）。
OP_READ：通道有数据可读（类似 “路口有车要出发”）。
OP_WRITE：通道可以写入数据（类似 “路口有空位可以进车”）。
OP_CONNECT：客户端连接建立（类似 “车成功上高速”）。
返回 SelectionKey：通道和选择器的 “纽带”，记录事件、通道等信息。*/


    /*等待事件：selector.select()
    java
    int readyChannels = selector.select(); // 阻塞，直到有事件发生


    类比：调度员坐在指挥中心，阻塞等待 “道路报警”，直到至少有一条道路有事件（比如 “新连接来了”“有数据要读”）。
    返回值 readyChannels：有多少条通道发生了事件（比如 3 条通道同时有数据可读）。*/


   /* 处理事件：遍历 selectedKeys()
    当 select() 检测到事件后，通过 selectedKeys() 获取所有 “报警的道路”（发生事件的通道），逐个处理：


    // 判断事件类型，处理不同逻辑
    if (key.isAcceptable()) { // 新连接事件（ServerSocketChannel）
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept(); // 接受新连接
        clientChannel.configureBlocking(false);
        // 把新客户端通道注册到选择器，监听【读事件】
        clientChannel.register(selector, SelectionKey.OP_READ);

    }  else if (key.isReadable()) { // 读事件（SocketChannel 有数据）
        SocketChannel clientChannel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        clientChannel.read(buffer); // 读取客户端数据
    }
    key.channel()：获取对应的通道（需强转为具体类型，如 ServerSocketChannel 或 SocketChannel）。
key.isXxx()：判断事件类型（isAcceptable()、isReadable() 等）。
iterator.remove()：处理完事件后，必须从集合中移除，否则下次会重复处理。
*/


}
