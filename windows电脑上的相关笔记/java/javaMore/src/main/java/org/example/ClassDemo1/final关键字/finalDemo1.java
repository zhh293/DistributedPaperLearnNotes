package org.example.ClassDemo1.final关键字;

import org.example.ClassDemo1.反射.Student;

public class finalDemo1 {

    //final是最终的含义
    //final可以用修饰类，方法，变量
    //final修饰类 类不能被继承
    //final修饰方法 方法不能被重写
    //final修饰变量，变量有且仅能被赋值一次
    //abstract和final是互斥关系，不能同时出现修饰成员！！！！！
    //变量有几种
    //成员变量：静态成员变量和实例成员变量
    //局部变量：只能在 方法中·，构造器中·代码块中，用完作用范围就消失了
    //final修饰局部变量
    //让值被固定或者说被保护起来，防止被修改
    //final修饰静态成员变量，变量变成了常量,它可以在定义的那一刻被赋值，也可以在静态代码块中被赋值
    //main启动类方法不只是只执行一次，
    public static final int ID_CARD = 10;
    public static void main(String[] args)
    {
        /*final Student student = new Student();
        Student student1 = new Student();
        student=student1;//错误，final修饰的变量不能被重新赋值*/
        final int a = 10;
        System.out.println(a);
        final String b = "hello world";
        final String c;
        c="hahahahaha";
        System.out.println(c);
        System.out.println(b);

    }
}
