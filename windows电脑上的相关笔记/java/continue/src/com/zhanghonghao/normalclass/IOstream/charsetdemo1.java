package com.zhanghonghao.normalclass.IOstream;

import java.io.FileInputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

public class charsetdemo1 {
    public static void main(String[] args) throws UnsupportedEncodingException {
        //ascii字符集，存储英文一个字节足以
        //GBK字符集，系统显示的是ANSI
        //UNICODE字符集，万国码
        /*核心 1：GBK 中，一个英文字母一个字节，二进制第一位是 0
核心 2：GBK 中，一个中文汉字两个字节，二进制第一位是 1*/
        /*一个字节由八位数字组成，即八个比特，这些数字不是零就是一*/
        //UTF-8编码规则：用1~4个字节保存，中文使用三个字节保存，英文使用一个字节
        //乱码的原因：读取数据时没有读完整个汉字
        //编码和解码的方式不一样
        //字节流一次只能读一个字节
        //避免方法：不要用字节流读取文本文件，编码和解码使用同一个码表，同一个编码方式
        //java中的解码和编码方法
        /*Java 中编码的方法
String 类中的方法	说明
public byte[] getBytes()	使用默认方式进行编码
public byte[] getBytes(String charsetName)	使用指定方式进行编码
Java 中解码的方法
String 类中的方法	说明
String(byte[] bytes)	使用默认方式进行解码
String(byte[] bytes, String charsetName)	使用指定方式进行解码*/
        String str1="ai你鸭";
        byte[] b1=str1.getBytes();
        System.out.println(Arrays.toString(b1));
        String str2=new String(b1);
        byte[] b2=str2.getBytes("GBK");
        System.out.println(Arrays.toString(b2));
        String str3=new String(b1);
        System.out.println(str3);
        String str4=new String(b2,"GBK");
        System.out.println(str4);
    }
}
