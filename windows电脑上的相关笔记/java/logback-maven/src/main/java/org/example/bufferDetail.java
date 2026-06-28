package org.example;

import java.nio.IntBuffer;

public class bufferDetail {
    public static final int 常用方法=16;
    public static void main(String []args){
        IntBuffer buffer = IntBuffer.allocate(10);
        int capacity = buffer.capacity();
        buffer.put(1);
        buffer.put(2);
        buffer.put(3);
        buffer.put(4);
        buffer.put(5);
        buffer.put(6);
        while(buffer.hasRemaining()){
            System.out.println(buffer.limit());
            System.out.println(buffer.get());
        }

        /*常用方法:
        public final int capacity()//返回此缓冲区的容量

        public final int position( )//返回此缓冲区的位置

        public final Buffer position (int newPosition)//设置此缓冲区的位置
d
        public final int limit()//返回此缓冲区的限制d

        public final Buffer limit (int newLimit)//设置此缓冲区的限制

        public final Buffer clear( )//清除此缓冲区,即将各个标记恢复到初始状态，
        但是数据并没有真正擦除
        public final Buffer flip()//反转此缓冲区
        public final boolean hasRemaining()//告知在当前位置和限制之间是否有元素

        public abstract boolean isReadory( );//告知此缓冲区是否为只读缓冲区
        public abstract boolean hasArray();//告知此缓冲区是否具有可访问的底层实现数组
        public abstract Object array();《返回此缓冲区的底层实现数组

        bytebuffer
         public static ByteBuffer allocateDirect(int capacity)//创建直接缓冲区  
        public static ByteBuffer allocate(int capacity)//设置缓冲区的初始容量
          public static ByteBuffer wrap(byte[] array)//把一个数组放到缓冲区中使用  
        //构造初始化位置offset和上界length的缓冲区
          public static ByteBuffer wrap(bytel] array,int offset, int length)  
        //缓存区存取相关API  
        public abstract byte get();//从当前位置position上get, get之后，position会自动+1  
        public abstract byte get (int index);//从绝对位置get
          public abstract ByteBuffer put (byte b);//从当前位置上添加，put之后，position会自动+1
          public abstract ByteBuffer put (int index, byte b);//从绝对位置上put 

        */





        /*Capacity  容量，即可以容纳的最大数量，在缓冲区被创建时被设定且不能改变
      * limit 表示缓冲区当前的终点，不能对缓冲区超过极限的位置进行读写操作，且极限是可以被修改的
      * position 位置，下一个要被读或者写的元素的索引，每次读写缓冲区数据时都会改变其值，为下次读写做准备
      * mark是一个标记 */
       /* . Buffer（缓冲区）
        作用
        数据的载体，所有数据都必须通过缓冲区处理，通道（Channel）只负责与缓冲区交互。
        类似数组，但提供了更高效的读写管理（通过position、limit、capacity三个指针）。
        核心属性
        capacity：缓冲区的最大容量（固定值）。
        position：当前读写位置（每次读写后自动更新）。
        limit：可读 / 写的边界（flip()方法会调整此值）。
        常用方法
        allocate(int capacity)：创建缓冲区。
        put(data)：写入数据到缓冲区。
        flip()：切换为读模式（将limit设为当前position，position重置为 0）。
        get()：从缓冲区读取数据。
        clear()：清空缓冲区（重置指针，数据未真正清除）。
        compact()：压缩缓冲区（保留未读数据，为下次写入做准备）*/
    }
}
/*
常用Buffer子类一览
1)ByteBuffer，存储字节数据到缓冲区
ShortBuffer，存储字符串数据到缓冲区
可而
CharBuffer，存储字符数据到缓冲区
IntBuffer，存储整数数据到缓冲区
LongBuffer，存储长整型数据到缓冲区
DoubleBuffer，存储小数到缓冲区
FloatBuffer，存储小数到缓冲区*/
