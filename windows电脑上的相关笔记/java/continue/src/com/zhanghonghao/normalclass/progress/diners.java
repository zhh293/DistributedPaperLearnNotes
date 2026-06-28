package com.zhanghonghao.normalclass.progress;

public class diners extends Thread {
    @Override
    public void run() {
       while (true) {
           synchronized(Desk.object){
               if(Desk.count==0){
                   break;
               }else {
                   if(Desk.foodFlag>0){
                       Desk.count--;
                       Desk.foodFlag=0;
                       Desk.object.notifyAll();
                       System.out.println("美味极了");
                   }else {
                       try {
                           Desk.object.wait();
                       } catch (InterruptedException e) {
                           throw new RuntimeException(e);
                       }
                   }
               }

           }
       }
    }
}
/*知识点补充一下
1. this()的作用
this()的功能是调用同一个类里的其他构造方法，它主要用于对构造方法进行复用。下面是一个具体的例子：

java
public class Person {
    private String name;
    private int age;

    // 第一个构造方法：需要同时传入姓名和年龄
    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    // 第二个构造方法：只需要传入姓名，年龄默认为0
    public Person(String name) {
        this(name, 0); // 调用上面那个构造方法
    }
}

在这个例子中，第二个构造方法借助this(name, 0)调用了第一个构造方法，这样就实现了代码的复用。
        2. super()的作用
super()的作用是调用父类的构造方法，以此来初始化从父类继承的成员变量。看下面的示例：

java
public class Student extends Person {
    private String studentId;

    // 子类的构造方法
    public Student(String name, int age, String studentId) {
        super(name, age); // 调用父类的构造方法
        this.studentId = studentId;
    }
}

在这个例子中，super(name, age)调用了父类Person的构造方法，从而对从父类继承的name和age进行初始化。
        3. 使用时的注意要点
必须放在首行：this()和super()都必须是构造方法里的第一条语句，否则会产生编译错误。
不能同时使用：由于它们都要占据构造方法的第一行位置，所以在同一个构造方法中不能同时调用this()和super()。
隐式调用：要是构造方法里没有显式地调用this()或者super()，Java 会自动调用父类的无参构造方法，也就是super()。
总结
this()：用于在同一个类的不同构造方法之间实现代码复用。
        super()：用于调用父类的构造方法，确保父类的状态能够正确初始化*/
