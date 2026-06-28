package org.example.ClassDemo1.AbstractClass;

import lombok.Data;

public class 设计模式 {
    //抽象类设计模板设计模式

//    拓展:1.抽象类体现的是模板思想，部分实现，部分抽象。可以设计模板设计模式.
    //什么是设计模式
    /*
    设计模式:
    1.设计模式是一套被反复使用的、经过检验的解决方案。
    2.设计模式可以解决某一类问题，也可以解决某一类问题下的某一种问题。
    3.设计模式可以解决某一类问题下的某一种问题，也可以解决某一类问题下的某一种问题下的某一种问题。
    4.设计模式可以解决某一类问题下的某一种问题下的某一种问题下的某一种问题下的某一种问题。
    5.设计模式可以解决某一类问题下的某一种问题下的某一种问题下的某一种问题下的某一种问题下的某一种问题。


    设计模式是前人或者技术大牛或者软件行业在生产实战中发现的优秀软件设计架构和思想。
    来者可以直接用这些架构或者思想就可以设计出优秀，提高效率，提高软件可扩展性和可维护性的软件!

    模板设计模式的作用:优化代码架构，提高代码的复用性，相同功能的重复代码无需反复书写!
    可以做到部分实现，部分抽象，抽象的东西交给使用模板的人重写实现!
    就比如说我一个springboot工程中，有许多配置类直接用别人写好的模板就可以了，不需要自己去写了，只有具体的业务逻辑才需要自己实现，这就是部分实现，部分抽象的含义


    哈哈哈哈哈哈哈哈啊哈哈哈*/
    public static void main(String[] args) {
        Student s = new Student();
        s.write();
    }
}
//这就是模板设计模式的体现
//部分实现，不用重复造轮子，部分抽象，抽象的东西交给使用模板的人重写实现，所谓抽象，无非就是一段代码不同人因为需求不同，所以代码不固定而已。
//变相地降低代码的耦合度
class Student extends Template{
    @Override
    public String writeOne() {
        return "我的爸爸真棒,芜湖，哈哈哈哈哈哈哈哈哈哈哈啊哈哈啊哈哈哈哈哈啊哈哈";
    }

}
abstract class Template{
    private String title="我的区长父亲";
    private String one="请介绍一下你的爸爸";
    private String last="我的爸爸真棒";
    public  void write(){
        System.out.println(title);
        System.out.println(one);
        System.out.println(writeOne());
        System.out.println(last);
    }
    public abstract String writeOne();
}