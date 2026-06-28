package org.example.ClassDemo1.extend;

public class 成员变量的访问特点 {
    public static void main(String[] args) {
        //继承之后成员变量如何访问呢
        //你就记住一点，就是就近原则，谁离它近就访问谁



    }
}
class Animals{
    public String name="小动物";
    public int age;
    public void eat(){
        System.out.println("Animal eat");
    }
}
class Dogs extends Animals{
    public void eat(){
        System.out.println("Dog eat");
        System.out.println(name);//父类的名称
    }
}
class Cats extends Animals{
    public String name="小猫";
    public void eat(){
        name="hskkfs";
        System.out.println("Cat eat");
        System.out.println(name);//局部名称
        System.out.println(this.name);//子类的名称
        System.out.println(super.name);//父类的名称
        //如果没有局部名称，就会往上找，直到找到子类名称，但如果子类名称都没有。就会自动找父类名称，如果父类名称都没有，就会报错
        //上面的陈述是针对没有this，super关键字的变量的访问特点，如果你带上这些关键字，那么就明确了应该去找谁，就近原则也就没有必要考虑了。。。。。。
//        this代表了当前对象的引用，可以用于访问当前子类对象的成员变量  super代表了父类对象的引用，可以用于访问父类中的成员变量。
    }
}