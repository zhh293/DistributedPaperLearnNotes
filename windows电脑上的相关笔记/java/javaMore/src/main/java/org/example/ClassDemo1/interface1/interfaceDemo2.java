package org.example.ClassDemo1.interface1;

public class interfaceDemo2 {
    public static void main(String[] args)
    {
        PingPongMan1 p1 = new PingPongMan1();
        p1.run();
        p1.rule();


    }
}
/*引入:

类与类是单继承关系:一个类只能继承一个直接父类。

类与接口是多实现关系:一个类可以同时实现多个接口。

接口与接口是多继承关系:一个接口可以同时继承多个接口。*/

//一些特别底层的东西就是这样写的，拼装成一定的组件或者API，再提供给用户即可
interface SportMan extends Law,Go {
    void run();
    void competition();
}
interface Law{
    void rule();
}
interface Go{
    void abroad();
}
class  PingPongMan1 implements SportMan{
    public void run()
    {

    }
    public void competition()
    {

    }
    public void rule()
    {

    }
    public void abroad()
    {

    }
}
//jdk1.8开始之后接口新增的三个方法（了解即可）
/*JDK 1.8开始之后接口新增了三个方法:

        (1)默认方法:其实就是我们之前写的实例方法。

        --必须用default修饰。

默认会加public修饰。

        --只能用接口的实现类的对象来调用

        (2)静态方法:
    --可以直接加static修饰。

     默认会加public修饰。
     接口的静态方法只能用接口的类名称



        (3)私有方法:*/

