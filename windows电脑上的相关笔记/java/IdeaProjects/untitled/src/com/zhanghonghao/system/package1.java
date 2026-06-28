package com.zhanghonghao.system;

import java.lang.System;
import java.util.Scanner;

public class package1 {
    public static void main(String[] args) {
        //包装类，用一个对象，把基本数据类型囊括在里面
        //自动装箱和自动拆箱
        //自动装箱把基本数据类型会自动变成对应的包装类
        //自动拆箱：把包装类自动地变成对应基本数据类型
        //jdk5之后可以把int和integer当作一个东西，因为可以自动装箱和拆箱
        //integer成员方法
        //自带的进制转化方法
        String str1=Integer.toBinaryString(100);
        System.out.println(str1);
        String str2=Integer.toBinaryString(-100);
        System.out.println(str2);
        String str3=Integer.toOctalString(100);
        System.out.println(str3);
        String str4=Integer.toHexString(100);
        System.out.println(str4);
        String str5="1234567";
        int number=Integer.parseInt(str5,10);
        //细节一，括号中的参数只能是数字而不能是其他，否则报错
        //细节二，八种包装类中，除了character都有对应的parsexxx的方法，进行类型转化
        System.out.println(number);
        //键盘录入弊端，当我们使用next，nextInt，nextDouble在接受数据时，遇到回车，空格，制表符的时候就终止了
        //如果我想一次性把数据录入在同一行，使用nextLine方法，遇到回车才停止
        Scanner sc=new Scanner(System.in);
        String str6=sc.nextLine();
        System.out.println(str6);
        //约定：以后统一使用nextLine输入，输入完毕之后进行类型转化即可
    }
}
