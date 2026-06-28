package org.example.ClassDemo1.匿名内部类;

public class InternnalClassDemo1 {
    public static void main(String[] args) {
        Animal animal2 = new Animal() {
            @Override
            public void eat() {
                System.out.println(132);
            }
            public int get(){
                return 132;
            }
        };
        //上面相当于是创建了一个继承于animal的子类，也就是匿名类，这个子类中重写了父类的eat方法，并且多了一个独属于自己的get方法，但是由于是匿名类，所以等号左边并不知道类名和对应的类
        //所以只能通过多态思想，用父类实例来接受，所以使用不了get方法，但是可以使用被重写的eat方法，还有默认实现的方法show。。。。。。。。


        animal2.show();

        Animal animal = new cat();
        animal.eat();
        animal.show();

        Animal animal1 = new Animal(){
            @Override
            public void eat() {
                System.out.println("吃吃吃");
            }
        };
        animal1.eat();
        animal1.show();


        //相当于new了一个实现了Swim接口的匿名内部类，这个匿名内部类最大的好处，就是不用频繁的在创建类，直接在一段代码中就可以创建一个一次性的类对象，非常的简单方便
        Swim swim=new Swim() {
            @Override
            public void swim() {
                System.out.println("大张伟游游游");
            }
        };
        go(swim);
        swim.swim();


    }

    public static void go(Swim  s){
        System.out.println("开始游");
        s.swim();
        System.out.println("游结束");
    }
}

class cat extends Animal{
    @Override
    public void eat() {
        System.out.println("吃吃吃");
    }
}
abstract class Animal{
    public abstract void eat();
    public void show(){
        System.out.println("show()");
    }
}
/*目标:匿名内部类的概述、

什么是匿名内部类?

就是一个没有名字的局部内部类。

匿名内部类目的是为了:简化代码，也是开发中常用的形式

匿名内部类的格式:

        new 类名|抽象类|接口(形参){

          方法重写。
    }

    匿名内部类的特点:

    1.匿名内部类是一个没有名字的内部类。

    2.匿名内部类一旦写出来，就会立即创建一个匿名内部类的对象返回。

    3.匿名内部类的对象的类型相当于是当前new的那个的类型的子类类型。*/

//匿名内部类的使用形式（关注语法）
interface Swim{
    void swim();
}