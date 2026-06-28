package org.example.ClassDemo1.interface1;

public class interfaceRealize {
    public static void main(String[] args)
    {
        Player p1 = new PingPongMan("蜘蛛侠");
        p1.play();
        Player p2 = new PingPongWoman();
        p2.play();
    }

}
class PingPongMan implements Player{
    private String name;
    public PingPongMan(){}
    public PingPongMan(String name){
        this.name = name;
    }

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }

    public void play()
    {
        System.out.println(this.name+"PingPongMan is playing");
    }
}
class PingPongWoman implements Player{
    public void play(){
        System.out.println("PingPongWoman is playing");
    }
}
interface Player{
    void play();
}

/*
类与类是继承关系。

类与接口是实现关系。接口是被类实现的。

实现接口的类称为:实现类。

类实现接口的的格式:

修饰符 class 实现类名称 implements 接口1,接口2,接口3,接口4,...{}

        implements:类实现接口的关键字。

        实现类必须重写所有的抽象方法，当然，抽象类中的实现类可以不重写抽象方法。
        好的，差不多语法讲完了
*/



/*默认方法（Default Methods）
允许在接口中提供方法的默认实现，使用default关键字修饰。实现类可以选择重写或直接使用默认实现。
java
public interface MyInterface {
    default void defaultMethod() {
        System.out.println("默认方法的实现");
    }
}

静态方法（Static Methods）
接口中可以定义静态方法，使用static关键字修饰，必须包含方法体。静态方法属于接口本身，不能通过实现类实例调用。
java
public interface MyInterface {
    static void staticMethod() {
        System.out.println("接口中的静态方法");
    }
}

私有方法（Private Methods）
JDK 9 进一步允许在接口中定义私有方法，使用private关键字修饰，只能在接口内部被其他默认方法或私有方法调用。
java
public interface MyInterface {
    default void defaultMethod() {
        privateMethod(); // 调用私有方法
    }

    private void privateMethod() {
        System.out.println("接口中的私有方法");
    }
}



这些新增特性使接口更接近抽象类，同时保持了接口的多实现特性，增强了 Java 的函数式编程能力和代码复用性*/

