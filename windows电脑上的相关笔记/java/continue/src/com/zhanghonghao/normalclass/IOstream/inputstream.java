package com.zhanghonghao.normalclass.IOstream;

import java.io.*;

public class inputstream {
    public static void main(String[] args) throws IOException {
       // FileInputStream fis = new FileInputStream("C:\\Users\\92819\\c.txt");
        //读取一个数据，返回值为ASCII码
       /* int read = fis.read();
        System.out.println((char) read);
        System.out.println(read);
        char ch='c';
        int num=(int)ch;
        char ch1='d';
        int num1=Integer.parseInt(String.valueOf(ch1));
        System.out.println(num);
        System.out.println(num1);
        //细节，如果文件不存在，则直接报错，程序中最重要的是数据

        //一次读取一个字节，读到末尾了，read方法返回-1
        /*fis.close();
        //循环读取数据
        FileInputStream fis2 = new FileInputStream("C:\\Users\\92819\\c.txt");
        int b;
        while ((b = fis2.read()) != -1) {
            System.out.print((char) b);
        }
        fis2.close();*/
        //文件拷贝
        FileInputStream fis1 = new FileInputStream("C:\\Users\\92819\\c.txt");
        FileOutputStream fos = new FileOutputStream("C:\\Users\\92819\\d.txt");
        //2.开始拷贝
        /*int c;
        while ((c = fis1.read()) != -1) {
            fos.write(c);
        }
        File file = new File("C:\\Users\\92819\\d.txt");
        System.out.println(file.lastModified());
        //先开的流，最后再关闭
        fos.close();
        fis1.close();*/
        //以上只适用于小文件，大文件会出现乱码
        //练习，统计一下拷贝时间，单位毫秒
        //下面来处理拷贝大文件时该怎么处理
        //一次读取一个字节数组的数据，每次读取会尽可能把数组填满
        byte[] buffer = new byte[1024];
        int c;
        //因为每次循环数组都会被覆盖一遍，所以打印时要限制打印的长度，就要用write的三个参数一起来限制
        while ((c = fis1.read(buffer)) != -1) {
            fos.write(buffer,0,c);
        }
        fos.close();
        fis1.close();
        /*1. 处理非文本数据
字符流的设计初衷是处理文本数据，它会以字符为单位进行读写操作，
并且会依据特定的字符编码（如 UTF - 8、GBK 等）对字符进行解码和编码。
然而，在处理非文本数据（像图片、音频、视频文件等）时，字符流就不太适用了。
因为这些数据并不具备字符编码的概念，它们是由二进制字节组成的。
而字节流能够以字节为单位对数据进行读写，可直接处理任意类型的二进制数据，
所以在处理非文本数据时，字节流是更合适的选择。
UTF - 8 编码中，一个英文字母通常用一个字节表示，而一些汉字可能用 2 - 3 个字节表示。
当字符流去读取非文本数据时，它可能会错误地将连续的几个二进制字节按照字符编码规则去解析，这就会导致解析出来的字符是乱码，而且在后续的编码和写入操作中，也会因为这种错误的解析而进一步破坏数据。
字节流本身不存在像 UTF - 8 这种针对字符的解码编码方式
3. 底层操作需求
在进行底层的 I/O 操作时，字节流是必不可少的。
例如，在进行网络编程时，数据在网络中是以字节的形式传输的，
使用字节流可以直接对网络数据进行读写操作，
而不需要进行字符编码和解码的转换。
同样，在与硬件设备进行交互时，也需要使用字节流来处理原始的二进制数据。*/
    }
}
