package com.zhh.handsome.ParkUnpark;

import java.util.concurrent.locks.LockSupport;

public class Test {
    public static void main(String[] args) {
        LockSupport.unpark(Thread.currentThread());
        LockSupport.park();
        System.out.println("end");
    }
}



//理论储备


/*在 Java 并发编程（JUC）中，park和unpark是LockSupport类提供的两个核心静态方法，用于实现线程的阻塞（暂停）和唤醒（恢复）。它们是比synchronized配合wait/notify更灵活的线程控制工具，也是很多并发工具类（如ReentrantLock、Semaphore）的底层实现基础。
一、基本作用
LockSupport.park()：阻塞当前线程，让线程进入 “暂停” 状态，直到被其他线程唤醒，或被中断，或等待超时。
        LockSupport.unpark(Thread t)：唤醒指定的线程t，如果线程t之前因park阻塞，则会立即恢复运行；如果线程t未阻塞，则会提前 “储备” 一个 “唤醒许可”，等线程t后续调用park时会直接跳过阻塞。
二、核心原理：“许可” 机制
park和unpark的工作依赖于一种类似 “许可（permit）” 的机制，每个线程都有一个独立的 “许可” 标识：
许可只有两种状态：有（1）或无（0），且最多只能有 1 个（不可累积）。
unpark(Thread t)的作用是给线程t的许可设为 “有”（如果原本是 “无”）。
park()的作用是尝试消耗许可：如果当前许可为 “有”，则直接消耗（设为 “无”）并继续运行；如果许可为 “无”，则线程进入阻塞状态，等待许可被设置为 “有”。
三、与wait/notify的区别
wait/notify是Object类的方法，依赖synchronized同步块，而park/unpark更灵活，主要区别如下：
对比项	wait/notify	park/unpark
依赖同步块	必须在synchronized块中使用	无需依赖同步块，可直接调用
唤醒顺序	notify必须在wait之后调用，否则信号会丢失	unpark可在park之前调用（提前储备许可）
唤醒目标	notify随机唤醒一个线程，notifyAll唤醒所有	unpark可精确唤醒指定线程
中断响应	被中断会抛出InterruptedException	被中断不会抛异常，仅返回并标记中断状态
四、常用方法
LockSupport中与park相关的方法有多个重载，用于不同场景：
park()：无时限阻塞，直到被unpark或中断。
parkNanos(long nanos)：阻塞指定时长（纳秒），超时后自动唤醒。
parkUntil(long deadline)：阻塞到指定时间戳（毫秒，System.currentTimeMillis()），到达后自动唤醒。
park(Object blocker)：带 “阻塞对象” 的阻塞，用于调试时标识线程阻塞的原因（通过jstack查看时更清晰）。
五、使用示例
示例 1：基本用法（先park后unpark）
java
        运行
public class ParkUnparkDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            System.out.println("线程t1：开始运行");
            System.out.println("线程t1：调用park，进入阻塞");
            LockSupport.park(); // 阻塞当前线程（t1）
            System.out.println("线程t1：被唤醒，继续运行");
        }, "t1");
        t1.start();

        // 主线程休眠1秒，确保t1先执行到park
        Thread.sleep(1000);

        System.out.println("主线程：调用unpark唤醒t1");
        LockSupport.unpark(t1); // 唤醒t1
    }
}
输出：
plaintext
线程t1：开始运行
线程t1：调用park，进入阻塞
主线程：调用unpark唤醒t1
线程t1：被唤醒，继续运行
示例 2：先unpark后park（提前储备许可）
java
        运行
public class ParkUnparkDemo2 {
    public static void main(String[] args) throws InterruptedException {
        Thread t1 = new Thread(() -> {
            System.out.println("线程t1：开始运行");
            // 先休眠2秒，让主线程先执行unpark
            try { Thread.sleep(2000); } catch (InterruptedException e) {}

            System.out.println("线程t1：调用park");
            LockSupport.park(); // 此时已有许可，直接消耗并继续
            System.out.println("线程t1：park结束，继续运行");
        }, "t1");
        t1.start();

        System.out.println("主线程：立即调用unpark给t1许可");
        LockSupport.unpark(t1); // 提前给t1许可
    }
}
输出：
plaintext
主线程：立即调用unpark给t1许可
线程t1：开始运行
线程t1：调用park
线程t1：park结束，继续运行
（说明：unpark提前给了许可，t1后续park时直接消耗许可，不会阻塞）
六、注意事项
中断响应：线程在park阻塞时被中断（调用thread.interrupt()），会立即返回，但不会抛出InterruptedException。需要通过Thread.interrupted()检查中断状态（会清除中断标记）。
java
        运行
Thread t1 = new Thread(() -> {
    LockSupport.park();
    System.out.println("是否被中断：" + Thread.interrupted()); // 输出true
});
t1.start();
t1.interrupt(); // 中断t1
虚假唤醒：park可能在没有被unpark或中断的情况下 “意外唤醒”（概率极低，由操作系统调度导致）。实际使用时，需配合循环检查条件，避免虚假唤醒影响逻辑：
java
        运行
while (条件不满足) { // 循环检查条件
        LockSupport.park();
}
许可不可累积：多次调用unpark(t)不会让许可变成 2，最多还是 1。例如：调用unpark(t)两次，再调用park()一次，许可会变为 0，再次park()会阻塞。
七、应用场景
park和unpark是 JUC 中很多高级工具的底层基础，例如：
AQS（抽象队列同步器）：ReentrantLock、CountDownLatch等类的核心组件，用park阻塞等待锁的线程，用unpark唤醒竞争到锁的线程。
线程池：在任务队列空时，用park阻塞工作线程；有新任务时，用unpark唤醒工作线程。
总结
park和unpark通过 “许可” 机制实现线程的灵活阻塞与唤醒，相比wait/notify更简洁、更精准，是 Java 并发编程中控制线程状态的核心工具。理解它们的原理，有助于深入掌握 JUC 中各种同步工具的实现逻辑。*/



