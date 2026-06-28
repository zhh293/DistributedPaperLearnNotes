package com.zhanghonghao.ABSTRACT;

public abstract class person {
    public abstract void work();
    //抽象类中的抽象方法必须要被重写
    //有抽象方法的类一定是抽象类，抽象类中不一定有抽象方法，可以有构造方法
    //抽象类的子类：1：要么重写抽象类中的所有抽象方法2.要么也是抽象类
    //抽象类不能创建对象
    //抽象类的目的就是由于不同子类的相同方法可能要发挥不同的作用，而在父类中不便于定义时可以得到解决，同时也可以防止有人漏掉方法的重写，起到提醒和监督的作用
     private String name;
     private int age;
     //作用：当创建子类对象时，给属性进行赋值的
     public person(String name, int age) {
         this.name = name;
         this.age = age;
     }
     public person() {}
    public String getName() {
         return name;

    }
    public void setName(String name) {
         this.name = name;
    }
    public int getAge() {
         return age;
    }
    public void setAge(int age) {
         this.age = age;
    }
    public void sleep(){
         System.out.println("睡觉");
    }
}
