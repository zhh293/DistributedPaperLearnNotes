package com.zhanghonghao.normalclass.IOstream;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class read {
    public static void main(String[] args) throws IOException {
        //字符流=字节流+字符集
        //输入流：一次读一个字节，遇到中文时，一次都多个字节，非常适合对纯文本文件进行操作
        //IO流就分为字节流和字符流
        //reader，字符的输入流，writer，字符输出流
        /*
第一步：创建对象
public FileReader(File file)        创建字符输入流关联本地文件
public FileReader(String pathname) 创建字符输入流关联本地文件

第二步：读取数据
public int read()                   读取数据，读到末尾返回-1
public int read(char[] buffer)      读取多个数据，读到末尾返回-1

第三步：释放资源
public void close()                 释放资源/关闭流
*/
        /*FileReader fr=new FileReader("E:\\java\\lianxi\\lianxi1\\lianxi2\\a.txt");

        int ch;
        while ((ch= fr.read())!=-1){
            System.out.print((char)ch);
        }
        fr.close();*/
        /*//read()细节：
//1. read():默认也是一个字节一个字节的读取的,如果遇到中文就会一次读取多个
//2. 在读取之后，方法的底层还会进行解码并转成十进制。
//  最终把这个十进制作为返回值
//  这个十进制的数据也表示在字符集上的数字
//  英文：文件里面二进制数据 01100001
//       read方法进行读取，解码并转成十进制97
//  中文：文件里面的二进制数据 11100110 10100001 10001001
//       read方法进行读取，解码并转成十进制27721，这时可以对这个和数字进行强转就能得到想要的汉字*/
        //java中的char大小为两个字节，跟c语言不一样
//带参的read方法，记住传入的是char类型的数组即可
char[]arr={'2','8'};
System.out.println(arr);
System.out.println(String.valueOf(arr));
System.out.println(new String(arr));
/*FileInputStream fis = new FileInputstream( name:"E:\laaa\\cent0s-7-x86 64-DVD-1810.iso");

File0utputStream fos = new File0utputStream( name:"myio\\copy.iso");

byte[] bytes = new byte[8192];

int len;

while((len=fis.read(bytes))!=-1){

fos.write(bytes, off:0,len);

fos.close();

fis.close();

I

//字节流的基本流:一次读写一个字节数组

public static void method3()throws IoException {

BufferedInputstream bis = new BufferedInputstream(new FileInputstream( name: "E: laaalicent0s-7-x86 64-DVD-1810.iso"));

Bufferedutputstream bos = new Bufferedoutputstream(new File0utputstream( name: "myiol\copy.iso"));

int b;

while((b=bis.read())!=-1){

bos.write(b);

bos.close();

bis.close();

*/

    }
}
