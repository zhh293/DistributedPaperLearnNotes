package com.zhanghonghao.normalclass.IOstream;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class IO {
    public static void main(String[] args) throws IOException {
        //IO流是存储和处理数据的解决方案
        //程序
        //IO流分为输入流和输出流，也可以分为字节流和字符流，字节流可以操作所有文件，字符流只能操作纯文本文件
        //纯文本文件就是用windows自带的记事本打开能读懂的
        //字节流  inputstream和outputstream
        //字符流  reader writer
        //1.创建对象，写出输出流
        //FileOutputStream fos = new FileOutputStream("C:\\Users\\92819\\c.txt");
        FileOutputStream fos = new FileOutputStream(new File("C:\\Users\\92819\\c.txt"));
        //写出数据
        fos.write(97);
        //关闭数据，解除了java对文件的占用
        fos.close();
        //细节1：参数是字符串表示的路径或者File对象都是可以的
        //细节2：如果文件不存在会创建一个新文件，但要保证父级路径是存在的
        //细节三：如果文件已经存在，则会清空文件
        //write方法的参数虽然是整数，但实际上是按照ASCII码转化之后打印到文件中的
        //写数据的三种方式
        //1.
        //FileOutputStream fos2 = new FileOutputStream(new File("C:\\Users\\92819\\c.txt"));
        //fos2.write(97);
       // fos2.close();
        //2.
        //byte[]buf={'z','g',3,6,7};
        //fos2.write(buf);
        //fos2.close();
        //3.off代表的是起始索引，len表示读取的个数
        //fos2.write(buf,3,buf.length-3);
        //fos2.close();
        //写数据的小问题，换行写和续写
        //换行写
        FileOutputStream fos3 = new FileOutputStream(new File("C:\\Users\\92819\\c.txt"),true);
        String str="sdfgasdfghghdfas\n666\\n";
        byte[] b=str.getBytes();
        //续写
        //将创建对象的第二个参数改成true即可打开续写开关
        String str1="666";
        byte[] b1=str1.getBytes();
        fos3.write(b1);
        fos3.close();
    }
}
