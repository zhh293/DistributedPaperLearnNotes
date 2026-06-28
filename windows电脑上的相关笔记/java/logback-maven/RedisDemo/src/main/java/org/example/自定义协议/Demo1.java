package org.example.自定义协议;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.springframework.messaging.Message;

import java.io.*;
import java.util.List;

//自定义协议也需要注意黏包和半包，解码的时候出现了半包的问题，那么就会抛出异常，因为肯定会读取超出索引范围的地方
//注意细节，细节决定成败。。。。。 hhhhhhhhh
public class Demo1 extends ByteToMessageCodec<Message> {
    @Override
    public void encode(ChannelHandlerContext channelHandlerContext, Message message, ByteBuf byteBuf) throws IOException {
        //4字节的魔数
        byteBuf.writeBytes(new byte[]{1,2,3,4});
        //字节的版本
        byteBuf.writeByte(1);
        //字节的序列化方式
        byteBuf.writeByte(0);
        //字节的指令类型
//        byteBuf.writeBytes(message.get)
        //请求序号
        byteBuf.writeInt(1);
        //正文长度
        //消息正文，获取内容的字节数组
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos= new ObjectOutputStream(baos);
        oos.writeObject(message);
        FileOutputStream fos = new FileOutputStream("E:\\temp.txt");
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        byte[] bytes = baos.toByteArray();

        bos.write(bytes);
        //长度
        byteBuf.writeInt(bytes.length);
        //写入内容
        byteBuf.writeBytes(bytes);
    }

    /*

    ByteArrayOutputStream 的工作原理：
    ByteArrayOutputStream 是一个输出流，内部维护一个字节数组缓冲区。
    当你向 ByteArrayOutputStream 写入数据时，数据会被追加到这个内部缓冲区中。
    对象序列化过程：
    ObjectOutputStream 包装了 ByteArrayOutputStream，用于将对象序列化成字节流。
    当调用 oos.writeObject(message) 时，ObjectOutputStream 会将 message 对象序列化，并将序列化后的字节数据写入到 ByteArrayOutputStream 的内部缓冲区中。
    获取字节数组：
            baos.toByteArray() 方法返回的是 ByteArrayOutputStream 内部缓冲区的副本，即之前通过 oos.writeObject(message) 写入的所有字节数据。
    因此，baos.toByteArray() 能够获取到字节数组是因为在 oos.writeObject(message) 执行过程中，数据已经被写入到了 ByteArrayOutputStream 的内部缓冲区中

    */


    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        int magicNum = byteBuf.readInt();
        byte version = byteBuf.readByte();
        byte serializerType = byteBuf.readByte();
        byte messageType = byteBuf.readByte();
        int sequenceId = byteBuf.readInt();
        byteBuf.readByte();
        int length = byteBuf.readInt();
        byte[] bytes = new byte[length];
        byteBuf.readBytes(bytes);
        if(version != 1){
            throw new RuntimeException("不支持的版本");
        }
        if(serializerType != 0){
            throw new RuntimeException("不支持的序列化方式");
        }
        if(messageType != 0){
            throw new RuntimeException("不支持的指令类型");
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        ObjectInputStream ois = new ObjectInputStream(bais);
        Message message = (Message) ois.readObject();
        list.add(message);
        System.out.println(message);
        System.out.println(message.getClass());
    }
}
