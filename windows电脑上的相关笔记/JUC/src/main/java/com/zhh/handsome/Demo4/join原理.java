package com.zhh.handsome.Demo4;

public class join原理 {

    /*要理解 Thread.join() 的原理，我们可以从 保护性暂停机制（Guarded Suspension Pattern） 入手，结合 join 方法的源码实现来分析。
    一、先明确两个核心概念
    保护性暂停机制这是一种多线程通信模式：当线程 A 需要线程 B 的执行结果时，A 会先进入等待状态（暂停），直到 B 完成任务并 “通知” A，A 才会继续执行。核心是 “等待 - 通知” 机制，通过共享对象传递信号，确保 A 在获取结果前不会盲目执行。
    join () 的作用t.join() 表示：当前线程（调用 join 的线程）会等待线程 t 执行完毕后，再继续向下执行。例如：
    java
            运行
    Thread t = new Thread(() -> { *//* 耗时任务 *//* });
t.start();
t.join(); // 当前线程等待t执行完再继续
System.out.println("t执行完毕，我继续执行");
    二、join () 的源码实现（基于 JDK 8）
    Thread 类的 join 方法有多个重载，核心实现是带超时参数的 join(long millis)，源码简化后如下：
    java
            运行
    public final synchronized void join(long millis) throws InterruptedException {
        long base = System.currentTimeMillis();
        long now = 0;

        if (millis == 0) { // 无超时等待（一直等直到线程结束）
            while (isAlive()) { // 循环检查线程是否还存活
                wait(0); // 释放锁，进入等待状态
            }
        } else { // 带超时的等待
            while (isAlive()) {
                long delay = millis - now;
                if (delay <= 0) { // 超时时间到，退出等待
                    break;
                }
                wait(delay); // 等待剩余时间
                now = System.currentTimeMillis() - base;
            }
        }
    }
    关键细节：
    join 是 synchronized 方法，锁对象是 Thread 实例本身（即被等待的线程 t）。
    核心逻辑是 循环检查线程是否存活（isAlive()）：如果存活，就通过 wait() 让当前线程进入等待状态。
    当被等待的线程 t 执行完毕后，当前线程会被唤醒，退出循环，继续执行。
    三、join () 如何体现保护性暂停机制？
    保护性暂停的核心是 “等待条件满足 + 通知唤醒”，join 方法完全遵循这一模式：
    等待方：调用 t.join() 的线程（当前线程）。它的 “等待条件” 是：t.isAlive() == false（即 t 执行完毕）。在条件满足前，它通过 t.wait() 释放锁并进入等待状态（暂停），避免无效占用 CPU。
    结果方：被等待的线程 t。它的 “任务” 是执行自己的 run() 方法。当 t 执行完毕（run() 方法结束）后，JVM 会自动触发一个操作：在 t 线程的内部逻辑中调用 t.notifyAll()，唤醒所有等待在 t 这个锁对象上的线程（即调用 t.join() 的线程）。
    共享对象：被等待的线程 t 本身。它既是锁对象（保证同步），也是 “结果载体”—— 其 isAlive() 状态就是等待方需要的 “结果信号”。
    四、为什么要用循环检查 isAlive()？
    源码中 while (isAlive()) { wait() } 的循环设计，正是为了应对 虚假唤醒（和之前讲的 wait 必须配合循环的原因一致）：
    即使当前线程在没有收到 t.notifyAll() 的情况下被虚假唤醒，循环会再次检查 t.isAlive()。
    如果 t 仍在运行（条件未满足），就继续调用 wait() 等待，避免错误地认为 t 已执行完毕。
    五、总结：join () 的原理流程
    线程 A 调用 t.join()，尝试获取 t 的对象锁。
    获取锁后，检查 t 是否存活（isAlive()）：
    若存活，A 调用 t.wait() 释放锁，进入等待状态。
    若已死亡，直接退出，A 继续执行。
    线程 t 执行完毕后，JVM 自动调用 t.notifyAll()，唤醒所有等待在 t 上的线程（包括 A）。
    线程 A 被唤醒后，重新获取 t 的锁，再次检查 t.isAlive()（此时已为 false），退出循环，继续执行后续逻辑。
    简言之，join 是保护性暂停机制的典型应用：通过 “等待线程存活状态” 和 “线程终止后的自动通知”，实现了线程间的顺序执行控制。*/





//    调用t1.join(),这里isAlive就是判断t1的，join锁对象是t1,wait方法是哪个线程调用就等待哪个线程。最后t1线程结束后就会notifyAll，t1对象的wait线程



}
