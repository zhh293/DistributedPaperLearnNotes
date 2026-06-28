package org.example.ClassDemo1.interface1;

public class interfaceDemo1 {
    public static void main(String[] args){
        /*test show = new test() {
            @Override
            public void show() {
                System.out.println("show");
            }
        };
        int age = show.getAge();
        show.show1();
        show.show();
        System.out.println(age);*/
        /*目标:接口的概述。(认识接口)
        什么是接口?接口是更加彻底的抽象，
        在JDK 1.8之前接口中只能是抽象方法和常量。接口体现的是规范思想，实现接口的子类必须重写完接口的全部抽象方法。

        接口的定义格式:(关注语法)

修饰符 interface 接口名称{

// 在JDK 1.8之前接口中只能是抽象方法和常量

interface定义接口的核心关键字。

接口中成分的研究:

在JDK 1.8之前接口中只能是抽象方法和常量
接口中的抽象方法：抽象方法格式:public abstract 返回值类型 方法名称(参数列表);   前两个修饰符可以省略。默认会加上
但是为什么抽象类中不能省略抽象方法修饰符?
因为抽象类中既可以有抽象方法，也可以有非抽象方法。设计就是这么设计的。


// 接口中的常量:
变量值只有一个，而且在程序运行的过程中不可以改变
public static final 是接口常量的一般修饰符
常量的变量名称建议字母全大写，单词用下划线隔开。
接口中的常量可以省略掉public static final，但是建议加上。
小结一下，接口体现的是一种规范思想。
jdk1.8之后接口中可以有默认方法，格式:
    public default 返回值类型 方法名称(参数列表){
    }
之前只有抽象方法和常量，现在可以有默认方法。
除此之外，接口没有其他成分










*/

    }
}
interface test{
    void show();
    public default void show1(){
        System.out.println("show1");
    }
    public static final int age=18;
    public default int getAge(){
        return age;
    }
}
