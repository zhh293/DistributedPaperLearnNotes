package com.zhh.handsome.demo1;

import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;

public class Demo1 {
    public static void main(String[] args) throws InterruptedException {

        System.out.println("start");
        TimeUnit.SECONDS.sleep(2);//底层也是sleep方法
        System.out.println("end");

        /*Thread thread = new Thread("t1") {
            @Override
            public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                // System.out.println("hello world");
            }
        };
        thread.start();


        System.out.println(thread.getState());

        Thread.sleep(500);


        System.out.println(thread.getState());*/







        /*Thread thread = new Thread("t1") {
            @Override
            public void run() {
                System.out.println("hello world");
            }
        };
        thread.start();//这是新开了一个线程，区别于主线程
       // thread.run();//这是主线程，会直接执行,串行执行的
        System.out.println(thread.getState());*/
    }
}


/*
JVM 中的Java 虚拟机栈（简称 "JVM 栈"）是线程私有内存区域，与线程的生命周期完全绑定：每个线程启动时，JVM 会为其创建一个独立的虚拟机栈，线程结束时栈也随之销毁。
核心作用：
虚拟机栈的核心功能是存储方法调用的状态，即通过 "栈帧" 的入栈和出栈，记录线程的方法执行流程。例如：线程执行main()方法时，会创建main栈帧并压入栈；main()调用A()方法时，创建A栈帧压入栈顶；A()执行完毕后，A栈帧出栈，继续执行main()剩余逻辑。
二、栈帧：方法执行的 "状态容器"
栈帧（Stack Frame）是虚拟机栈中的基本元素，每个方法从调用到执行结束的全过程，对应一个栈帧在虚拟机栈中的 "入栈→出栈" 过程。栈帧是方法执行时的 "状态快照"，包含了方法执行所需的所有信息。
栈帧的结构（4 个核心部分）：
局部变量表（Local Variables）
存储方法的参数和局部变量的区域，容量以 "变量槽（Slot）" 为单位（1 个 Slot 可存放 boolean、byte、char、short、int、float、reference 或 returnAddress 类型，long 和 double 需 2 个 Slot）。
例如：void method(int a, String b) { int c = 0; }中，a、b、c都会存储在局部变量表中。
局部变量表的大小在编译期就已确定（写死在字节码中），方法执行时不会动态扩容。
操作数栈（Operand Stack）
方法执行过程中用于字节码指令运算的 "临时数据栈"，类似 CPU 的寄存器。字节码指令通过 "入栈"（push）和 "出栈"（pop）操作数栈完成计算。
例如：执行int result = a + b时，会先将a、b依次压入操作数栈，再执行iadd指令弹出两数相加，结果压回栈顶，最后存入局部变量表的result中。
操作数栈的深度同样在编译期确定。
动态链接（Dynamic Linking）
每个栈帧都持有指向当前方法在运行时常量池（Method Area 中的数据结构）中符号引用的指针。这一指针用于将方法的符号引用（如invokevirtual #5）转换为直接引用（内存地址），即 "动态链接"。
作用：支持 Java 的多态（如子类重写父类方法时，确保调用的是实际类型的方法）。
方法返回地址（Return Address）
记录方法执行完毕后，需要返回的调用者位置（即调用该方法的字节码指令的下一条指令地址）。
例如：main()调用A()，A栈帧的返回地址就是main()中调用A()之后的那条指令地址。
若方法通过return正常结束，会将返回值（若有）压入调用者的操作数栈，并跳转到返回地址；若因异常结束，返回地址由异常表（Exception Table）决定。
栈帧的生命周期：
入栈：方法被调用时，JVM 根据方法的元数据（局部变量表大小、操作数栈深度等）创建栈帧，压入当前线程的虚拟机栈顶。
执行：方法执行过程中，所有操作（如变量访问、运算、调用其他方法）都通过操作当前栈帧完成。
出栈：方法执行完毕（正常返回或异常终止），栈帧从虚拟机栈中弹出，线程继续执行调用者的后续逻辑。
三、多线程与栈：线程隔离的核心
多线程场景中，每个线程的虚拟机栈是完全隔离的：线程 A 的栈帧仅存在于 A 的虚拟机栈中，线程 B 无法访问 A 的栈帧。这一隔离性是多线程安全的基础之一 —— 局部变量（存储在栈帧的局部变量表中）之所以线程安全，正是因为它们被封装在各自线程的栈帧中，不会被其他线程修改。

例如：两个线程同时执行count()方法，每个线程的栈中都会有独立的count栈帧，局部变量i在各自栈帧中，互不干扰：

java
        运行
void count() {
    int i = 0; // 每个线程的栈帧中都有独立的i
    i++;
}
四、上下文切换：多线程切换的 "状态保存与恢复"
多线程的并发执行本质是CPU 时间片的轮询分配：CPU 快速在多个线程间切换，每个线程执行一小段时间（时间片）后让出 CPU。当 CPU 从线程 A 切换到线程 B 时，需要保存 A 的当前状态并恢复 B 的上次状态，这个过程就是上下文切换（Context Switch）。
上下文切换的核心：保存与恢复 "线程状态"
线程的 "状态" 包含两部分关键信息：

CPU 层面：程序计数器（PC Register）的值（记录当前执行的字节码指令地址）、CPU 寄存器（如累加器、通用寄存器等）。
JVM 层面：线程的虚拟机栈状态（所有栈帧的局部变量表、操作数栈、返回地址等）。
切换过程（简化）：
当线程 A 的时间片用完，操作系统触发中断，CPU 暂停执行 A。
操作系统将 A 的状态（PC 值、寄存器、虚拟机栈等）保存到内存的 "线程控制块（TCB）" 中。
从内存中读取线程 B 的 TCB，恢复 B 的 PC 值、寄存器和虚拟机栈状态。
CPU 开始执行线程 B，从上次暂停的位置继续运行。
上下文切换与 JVM 栈 / 栈帧的关联：
虚拟机栈和栈帧是线程状态的核心载体，因此上下文切换的开销很大程度上来自栈帧的保存与恢复：

线程的虚拟机栈越深（方法调用链越长），栈帧越多，保存 / 恢复的信息量越大，切换开销越高。
频繁的上下文切换会导致 CPU 大量时间浪费在 "保存 - 恢复" 操作上，而非执行实际业务逻辑（这也是多线程并非越多越好的原因）。
五、总结：对多线程的关键意义
线程隔离性：每个线程的虚拟机栈独立，确保局部变量线程安全，是理解 "线程封闭" 等并发安全策略的基础。
方法执行追踪：栈帧记录了方法调用的完整状态，是调试（如查看调用栈）和分析线程行为（如死锁时的栈信息）的关键依据。
性能影响：上下文切换的开销直接与栈帧数量相关，合理控制方法调用深度（避免过深递归）和线程数量，可减少切换开销，提升多线程程序性能。

理解这些概念后，就能更深入地分析多线程问题（如线程安全、性能瓶颈），例如：为什么局部变量不需要同步？为什么过多线程会导致性能下降？这些问题的答案都与 JVM 栈、栈帧和上下文切换的机制密切相关。
*/


