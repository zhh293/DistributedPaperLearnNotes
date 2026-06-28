package com.zhanghonghao.ABSTRACT;

public interface swim {
    //定义一个接口,不能创造对象，接口与类是实现关系，通过implement关键字表示
    //public class 类名 implements 接口名{}
    //接口的子类(实现类),要么重写接口中所有抽象方法，要么是抽象类
    //接口可以多实现  public class 类名 implements 接口名1,接口名2{}
    //实现类还可以在继承一个类的同时实现多个接口
    // public class 类名 extends 父类 implements 接口名{}
    public abstract void swim();
    public default void show(){}









}
