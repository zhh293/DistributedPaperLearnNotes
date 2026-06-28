package org.example;

import javax.swing.*;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class bufferonlyread {
    public static void main(String[] args) throws IOException {
       /* ByteBuffer buffer = ByteBuffer.allocate(1024);
        for(int i=0;i<64;i++){
            buffer.put((byte)i);
        }
        buffer.flip();
        ByteBuffer readOnlyBuffer = buffer.asReadOnlyBuffer();
        // readOnlyBuffer.put((byte)0);
        while(readOnlyBuffer.hasRemaining()){
            System.out.print(readOnlyBuffer.get());
        }*/
         /*ByteBuffer buffer = ByteBuffer.allocate(1024);
        for(int i=0;i<64;i++){
            buffer.putInt(i);
        }
        buffer.flip();
        ByteBuffer readOnlyBuffer = buffer.asReadOnlyBuffer();
        // readOnlyBuffer.put((byte)0);
        while(readOnlyBuffer.hasRemaining()){
            System.out.print(readOnlyBuffer.getInt());
        }*/
        //mappedbytebuffer，可让文件直接在内存(堆外内存)修改，操作系统不需要拷贝一次,效率高

       /* RandomAccessFile randomAccessFile=new RandomAccessFile("E:\\temp.txt","rw");
        FileChannel fileChannel=randomAccessFile.getChannel();
        MappedByteBuffer map = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
        //map.put(0,(byte)'我');
        while (map.hasRemaining()) {
            System.out.println(map.get());
        }
        fileChannel.close();
        randomAccessFile.close();*/



    }


    /*RandomAccessFile randomAccessFile = new RandomAccessFile("1.txt","rw"),

/获取对应的通道

    Filechannel channel=randomAccessFile.getchannel();

/米来

    参数1:Filechannel.MapMode.READ WRITE 使用的读写模式

    参数2:   0:可以直接修改的起始位置

* 参效3:

            5:是映射到内存的大小，即将 1.txt 的多少个字节映射到内存，可以直接操作的字节数目,记住不是索引下标哦

channel.map(Filechannel.MapMode.READ_WRITE,0,5);*/


}
