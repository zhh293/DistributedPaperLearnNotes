package org.example.Redis;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/*
*3\r\n         // 数组包含3个元素（SET、name、redis）
$3\r\n         // 第一个元素是长度为3的字符串
SET\r\n        // 第一个元素内容：SET
$4\r\n         // 第二个元素是长度为4的字符串
name\r\n       // 第二个元素内容：name
$5\r\n         // 第三个元素是长度为5的字符串
redis\r\n      // 第三个元素内容：redis
* */

public class RespFrameDecoder extends ByteToMessageDecoder {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(20);
    //这个主要是为了分割出完整的命令消息，防止黏包和半包
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        //这个缓冲区里面的数据现在是固定长度的，所以这里直接用ByteBuf.readableBytes()
        int length=0;
        int ArrayNumber=0;
        System.out.println("进入解码方法");
        /*executorService.submit(()->{

        });*/
        try{
            while(true){
                if(in.capacity()<=0||in.readerIndex()>=in.writerIndex()){
                    System.out.println("没有数据可读");
                    System.out.println(in.toString(Charset.forName("UTF-8")));
                    return;
                }
                ByteBuf byteBuf1 = in.markReaderIndex();
                System.out.println("标记过的索引为"+byteBuf1.readerIndex());
                //打印当前索引的字符
                System.out.println("标记处的索引字符为"+(char)in.getByte(byteBuf1.readerIndex()));
                if(in.readByte()=='*'){
                    //  length++;
                    ArrayNumber=in.readByte()-'0';
                    System.out.println("数组中的元素个数为"+ArrayNumber);
                    String string = in.toString(Charset.forName("UTF-8"));
                    String[] split = string.split("\r\n");
                    Arrays.stream(split).forEach(System.out::println);
                    //  length++;//这个没有考虑多位数的情况，之后记得优化
                    System.out.println("当前数组索引为"+in.readerIndex()+"数组的长度为"+split.length);
                    if(split.length<2*ArrayNumber+1){
                        System.out.println("数据不完整，数组长度为"+split.length);
                        in.resetReaderIndex();
                        break;
                    } else if (split.length > 2 * ArrayNumber+1) {

                        for(int i=1;i<2*ArrayNumber+1;i=i+2){
                            if(split[i].charAt(0)!='$'){
                                System.out.println("这个命令不满足协议");
                            }else{
                                length++;
                                int pre=1;
                                StringBuilder sb=new StringBuilder();
                                while(pre<split[i].length()){
                                    sb.append(split[i].charAt(pre));
                                    length++;
                                    pre++;
                                }
                                length+=Integer.parseInt(sb.toString());
                                out.add(split[i+1]);
                            }
                        }

                        System.out.println("length长度为"+ length);
                        System.out.println("写索引为"+in.writerIndex());
                        if(in.writerIndex()<=in.readerIndex() + length + (2 * ArrayNumber + 1) * 2 ){
                            in.readerIndex(in.readerIndex() + length + (2 * ArrayNumber + 1) * 2 );
                        }else{
                            ByteBuf byteBuf = in.readerIndex(in.readerIndex() + length + (2 * ArrayNumber + 1) * 2);
                            int i = byteBuf.readerIndex();
                            System.out.println("当前缓冲区的索引为"+i);
                        }
                        length=0;
                    }else {
                        for(int i=1;i<2*ArrayNumber+1;i=i+2){
                            if(split[i].charAt(0)!='$'){
                                System.out.println("这个命令不满足协议");
                            }else{
                                // System.out.println(11111);
                                length++;
                                int pre=1;
                                StringBuilder sb=new StringBuilder();
                                while(pre<split[i].length()){
                                    sb.append(split[i].charAt(pre));
                                    length++;
                                    pre++;
                                }
                                length+=Integer.parseInt(sb.toString());
                                out.add(split[i+1]);
                            }
                        }
                        System.out.println("length长度为"+ length);
                        if(in.writerIndex()<in.readerIndex() + length + (2 * ArrayNumber + 1) * 2 ){
                            in.readerIndex(in.readerIndex() + length + (2 * ArrayNumber + 1) * 2 );
                        }else{
                            ByteBuf byteBuf = in.readerIndex(in.readerIndex() + length + (2 * ArrayNumber + 1) * 2 );
                            int i = byteBuf.readerIndex();
                            System.out.println("当前缓冲区的索引为"+i);
                        }
                        length=0;
                    }

                }
                // ctx.fireChannelRead(out);
            }
            System.out.println("命令解析完毕"+out.toString());
            }
            catch (Exception e){
            throw new RuntimeException(e);
        }
    }
}



/*

        capacity()
        返回 ByteBuf 的当前容量。

        java
                运行
        int capacity = byteBuf.capacity(); // 返回缓冲区的总容量
        maxCapacity()
        返回 ByteBuf 允许的最大容量。

        java
                运行
        int maxCapacity = byteBuf.maxCapacity(); // 返回最大可扩展容量
        readableBytes() 和 writableBytes()
        readableBytes()：返回可读取的字节数（readerIndex 到 writerIndex 之间的距离）。
        writableBytes()：返回可写入的字节数（writerIndex 到 capacity 之间的距离）。

        java
                运行
        int readable = byteBuf.readableBytes(); // 可读字节数
        int writable = byteBuf.writableBytes(); // 可写字节数
2. 读写索引控制
readerIndex() 和 writerIndex()
        readerIndex()：返回当前读取位置。
        writerIndex()：返回当前写入位置。

        java
                运行
        int readerIndex = byteBuf.readerIndex(); // 当前读取索引
        int writerIndex = byteBuf.writerIndex(); // 当前写入索引
        markReaderIndex()、resetReaderIndex()
        标记和重置读取位置。

        java
                运行
byteBuf.markReaderIndex(); // 标记当前读取位置
// 读取数据...
byteBuf.resetReaderIndex(); // 恢复到标记的读取位置
        discardReadBytes()
        丢弃已读取的数据，释放空间。

        java
                运行
byteBuf.discardReadBytes(); // 移动readerIndex到0，压缩缓冲区
        clear()
        重置读写索引（readerIndex = writerIndex = 0），但不清除内容。

        java
                运行
byteBuf.clear(); // 重置索引，不擦除数据
3. 数据写入方法
writeByte(int value)
        写入一个字节。

        java
                运行
byteBuf.writeByte(65); // 写入'A'的ASCII码
        writeInt(int value)
        写入一个 32 位整数（大端序，高位在前）。

        java
                运行
byteBuf.writeInt(123456); // 写入整数
        writeBytes(byte[] src)
        写入字节数组。

        java
                运行
        byte[] data = "Hello".getBytes();
byteBuf.writeBytes(data); // 写入字节数组
        setByte(int index, int value)
        在指定位置写入字节（不改变写入索引）。

        java
                运行
byteBuf.setByte(0, 65); // 在索引0处写入'A'
4. 数据读取方法
readByte()
        读取一个字节并递增读取索引。

        java
                运行
        byte b = byteBuf.readByte(); // 读取一个字节
        readInt()
        读取一个 32 位整数并递增读取索引。

        java
                运行
        int i = byteBuf.readInt(); // 读取一个整数

        readBytes(byte[] dst)
        读取数据到字节数组。

        java
                运行
        byte[] dst = new byte[10];
byteBuf.readBytes(dst); // 读取10个字节到dst

        getByte(int index)
        获取指定位置的字节（不改变读取索引）。

        java
                运行
        byte b = byteBuf.getByte(0); // 获取索引0处的字节


        isReadable() 和 isWritable()
isReadable()：检查是否有数据可读（readerIndex < writerIndex）。
isWritable()：检查是否有空间可写（writerIndex < capacity）。

java
运行
if (byteBuf.isReadable()) {
    // 有数据可读
}
toString(Charset charset)
将可读字节转换为字符串。

java
运行
String str = byteBuf.toString(StandardCharsets.UTF_8);*/
