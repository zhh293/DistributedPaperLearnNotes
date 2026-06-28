package com.zhh.handsome.demo1;

import java.util.Hashtable;
import java.util.Vector;

public class 局部变量线程安全 {
    static int count = 0;
    public static void main(String[] args) {
        /*Hashtable<String, String> map = new Hashtable<>();
        new Thread(() -> {
            map.put("key", "value");
            System.out.println(map.get("key"));
        });
        new Thread(() -> {
            map.put("key", "value");
            System.out.println(map.get("key"));
        });*/
        /*Vector<String> vector = new Vector<>();
        vector.add("1");
        String s = vector.get(0);
        System.out.println(s);*/

        new Thread(() -> {
            test();
        }, "线程1").start();
       new Thread(() -> {
            test();
        }, "线程2").start();
    }
    /*public static void test(){
        int count = 0;
        for(int i=1;i<100;i++){
            count++;
            System.out.println(Thread.currentThread().getName()+"  "+count);
        }
    }*/
    public static void test(){
        for(int i=1;i<10000;i++){
            count++;
            System.out.println(Thread.currentThread().getName()+"  "+count);
        }
    }
  /*  一、局部变量的线程安全基础：栈私有特性
    局部变量（方法内定义的变量）的线程安全性源于其存储位置和作用域特性：

    存储位置：局部变量存储在当前线程的虚拟机栈帧中（栈内存属于线程私有）。每个线程在调用方法时，JVM 会为该方法创建独立的栈帧，局部变量仅在当前栈帧中可见。
    作用域限制：局部变量的生命周期与方法调用一致，仅在当前方法执行期间有效，方法执行结束后栈帧出栈，变量随之销毁。

    由于不同线程的栈帧完全隔离，局部变量不会被多个线程共享，因此基本类型的局部变量天然是线程安全的。

    示例（安全情况）：

    java
            运行
    public void calculate() {
        int count = 0; // 基本类型局部变量，线程安全
        count++; // 仅当前线程可见，无竞争
        System.out.println(count);
    }

    无论多少线程调用calculate()，每个线程都会在自己的栈帧中创建独立的count，修改操作互不干扰。
    二、引用类型局部变量的线程安全陷阱
    当局部变量是引用类型（如对象、数组）时，情况会变得复杂。此时需要区分两个概念：

    局部变量本身：引用（内存地址）存储在栈帧中，线程私有。
    引用指向的对象：实际对象存储在堆内存中（堆是线程共享的）。

    因此，引用类型局部变量的线程安全性取决于：该变量指向的对象是否被多个线程访问。
            1. 安全场景：对象仅被当前线程访问（无逸出）
    如果引用类型的局部变量指向的对象仅在当前方法内使用，且未被传递到方法外部（即对象未 “逸出” 当前线程），则即使对象本身是可变的，也不会产生线程安全问题。

    示例（安全情况）：

    java
            运行
    public void process() {
        List<String> list = new ArrayList<>(); // 局部变量（引用）
        list.add("a"); // 修改堆中的ArrayList对象
        list.add("b");
        // 仅当前线程访问list指向的对象，无安全问题
    }

    虽然ArrayList本身不是线程安全的，但此处list指向的对象仅被当前线程操作，因此安全。
            2. 危险场景：对象逸出到多个线程
    如果局部变量指向的对象被传递到方法外部（如返回给调用方、存储到静态变量、传递给其他线程），则该对象可能被多个线程访问，此时即使变量是局部的，也可能引发线程安全问题。

    示例（不安全情况）：

    java
            运行
    private static List<String> sharedList; // 静态变量（多线程共享）

    public void unsafeMethod() {
        List<String> localList = new ArrayList<>(); // 局部变量
        localList.add("data");

        // 将局部变量指向的对象赋值给共享变量（对象逸出）
        sharedList = localList;
    }

    此时localList指向的ArrayList对象被赋值给静态变量sharedList，可能被其他线程访问。若多个线程同时修改该对象，会导致ArrayList的线程安全问题（如ConcurrentModificationException）。
            3. 更隐蔽的陷阱：方法参数的传递
    方法参数本质上也是局部变量（存储在栈帧的局部变量表中）。如果参数是引用类型，且指向的对象被多个线程共享，则通过参数修改对象状态时，同样会引发线程安全问题。

    示例（不安全情况）：

    java
            运行
    // 共享的可变对象
    private List<String> globalList = new ArrayList<>();

    public void modifyList(List<String> paramList) { // paramList是局部变量（引用）
        paramList.add("new data"); // 修改paramList指向的对象
    }

// 线程1调用
new Thread(() -> modifyList(globalList)).start();
// 线程2调用
new Thread(() -> modifyList(globalList)).start();

    此处paramList是局部变量，但它指向的globalList是共享对象。两个线程同时调用modifyList修改同一对象，会导致线程安全问题。
    三、线程封闭：局部变量线程安全的本质
    局部变量的线程安全性本质上是线程封闭（Thread Confinement）的一种体现：将对象限制在单个线程内访问，避免共享。

    线程封闭的常见形式包括：

    栈封闭：即局部变量的场景，对象仅在当前线程的栈帧中使用。
    ThreadLocal：通过ThreadLocal将对象与线程绑定，每个线程拥有独立副本。

    局部变量的栈封闭是最自然的线程封闭方式，但需确保对象不逸出当前线程。
    四、注意事项总结
    基本类型局部变量一定安全：其值存储在栈帧中，线程私有，无共享可能。
    引用类型局部变量需判断对象是否共享：
    若对象仅在当前方法内使用（无逸出），则安全；
    若对象被传递到方法外部（如返回、存储到共享区域），则需考虑线程安全（如加锁、使用线程安全容器）。
    警惕对象逸出的隐蔽形式：
    直接返回局部变量指向的对象；
    将对象传递给其他线程的任务（如Runnable）；
    把对象存储到静态变量、实例变量等共享区域。
    方法参数的特殊性：参数是局部变量，但如果指向共享对象，修改参数指向的对象会影响所有线程。
    匿名内部类 / Lambda 中的局部变量：
    若局部变量被匿名内部类或 Lambda 捕获，需注意对象是否逸出（例如，Lambda 被传递到其他线程执行时，捕获的对象可能被多线程访问）。
    总结
    局部变量的线程安全性不能一概而论：基本类型局部变量天然安全；引用类型局部变量的安全性取决于其指向的对象是否被多线程共享。核心原则是：确保局部变量指向的对象仅被当前线程访问，避免对象逸出。*/
}
