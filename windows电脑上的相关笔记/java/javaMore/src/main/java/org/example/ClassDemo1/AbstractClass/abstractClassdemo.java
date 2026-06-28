package org.example.ClassDemo1.AbstractClass;

public class abstractClassdemo {
    public static void main(String[] args) {
        /*目标:抽象类的概述。

        什么是抽象类?

                父类知道子类一定要完成某个功能，但是每个子类完成的情况是不一样的。

        子类以后也只会用自己重写的功能，那么父类的该功能就可以定义成抽象方法，子类重写调用子类自己

        */
    }
}
abstract class Animal{
    public void eat(){
        System.out.println("吃");
    }
    //子类要自己完成这个功能，但是由自己重写完成
    //抽象方法:没有方法体，只有方法签名，必须用abstract修饰
    //用有抽象方法的类必须是抽象类，抽象类不能实例化
   public abstract void run();
}
class Dog extends Animal{
    @Override
    public void run() {
        eat();
        System.out.println("🐕狗跑");
    }
}
class Cat extends Animal{
    @Override
    public void run() {
        System.out.println("🐱猫跑");
    }
}
