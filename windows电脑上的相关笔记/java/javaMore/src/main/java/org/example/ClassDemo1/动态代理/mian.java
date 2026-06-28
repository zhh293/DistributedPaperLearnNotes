package org.example.ClassDemo1.动态代理;

import org.example.ClassDemo1.反射.Student;

import java.lang.reflect.Proxy;

public class mian {
    public static void main(String[]args){
       star star=new star() {
           @Override
           public String sing() {
               return "谢谢你星星";
           }
       };
        /*Student student=new Student();
        ProxyUtil proxyUtil=new ProxyUtil(student);
        Student proxy = (Student) proxyUtil.getProxy();
        proxy.show("我是你大八");*///这是个类，没有实现接口，所以不能创建代理对象，艹，居然只能创建接口的代理类吗，有点意思。。。。。。。。
       /* ProxyUtil proxyUtil=new ProxyUtil(star);
        star proxy =(star) proxyUtil.getProxy();
        String sing = proxy.sing();
        System.out.println(sing);*/
    }
}
