package org.example.ClassDemo1.设计模式;

import lombok.Getter;

public class SingleInstance {
    //什么是单例
    //单例指的是一个类只能创建一个对象，不能创建多个对象
    //为什么要用单例
    //开发中很多类创建对象，但是对象只能有一个，比如数据库连接，线程池，日志类，只需要一个对象就可以实现业务，单例可以节约内存，提高i性能

    //如何实现单例
    //饿汉式
    //饿汉式，在类加载的时候创建对象，饿汉式线程安全，但是效率低，通过类获取单例对象时，对象已经提前写好了
    //懒汉式
    //懒汉式，在需要的时候创建对象，懒汉式线程不安全，但是效率高，确实，可能当一个线程刚准备new一个对象，执行权就被夺走了，那么就会创建多个对象，不再是单例模式
    public static void main(String[] args) {
        SingleInstanceDemo1 singleInstanceDemo1=SingleInstanceDemo1.getInstance();
        SingleInstanceDemo1 singleInstanceDemo2=SingleInstanceDemo1.getInstance();
        System.out.println(singleInstanceDemo1==singleInstanceDemo2);
    }

}
@Getter
class SingleInstanceDemo1{
    //饿汉式
    //把类的构造器私有,构造器只能在被u类中访问
    //定义一个静态变量用于存储一个对象(饿汉单例在返回对象的时候，对想要已经做好了，所以这里)
    private static final SingleInstanceDemo1 instance = new SingleInstanceDemo1();
    private SingleInstanceDemo1(){}
    public static SingleInstanceDemo1 getInstance(){
        return instance;
    }
}


class SingleInstanceDemo2{
    //懒汉式
    //把类的构造器私有,构造器只能在被u类中访问
    //定义一个静态变量用于存储一个对象(懒汉单例在返回对象之前，对想要对象进行了创建，所以这里需要的时候，即这个对象是空的时候才创建)
    private static SingleInstanceDemo2 instance;
    private SingleInstanceDemo2(){}
    //通过方法返回一个对象，不存在对象才创建一个返回，存在对象就返回
    public static SingleInstanceDemo2 getInstance(){
        if(instance==null){
            instance=new SingleInstanceDemo2();
        }
        return instance;
    }
}

//懒汉式和饿汉式对比
