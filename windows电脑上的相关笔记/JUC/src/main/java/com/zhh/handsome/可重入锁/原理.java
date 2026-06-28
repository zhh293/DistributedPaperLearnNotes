package com.zhh.handsome.可重入锁;

import java.util.concurrent.locks.ReentrantLock;

public class 原理 {
    public static void main(String[] args) {
        ReentrantLock lock = new ReentrantLock();
//        验证可重入
        lock.lock();
        try {
            kechongru(lock);
        }finally {
            lock.unlock();
        }
        /*new Thread(){
            @Override
            public void run() {
                lock.lock();
                try {
                    System.out.println("进入临界区");
                    new Thread(() -> {
                        lock.lock();
                        try {
                            System.out.println("进入临界区");
                        } finally {
                            lock.unlock();
                        }
                    }).start();
                    System.out.println("离开临界区");
                } finally {
                    lock.unlock();
                }
            }
        }.start();*/
    }

    private static void kechongru(ReentrantLock lock) {
        lock.lock();
        try {
            System.out.println("可重入锁验证成功");
        } finally {
            lock.unlock();
        }

    }
    /*一、ReentrantLock 是什么？
    ReentrantLock 是 Java 并发包（java.util.concurrent.locks）中的可重入独占锁，从 JDK 1.5 开始引入。它的核心功能和 synchronized 类似 —— 保证多线程对共享资源的互斥访问，但提供了更灵活的锁控制能力。
    为什么叫 “可重入”？“可重入” 指一个线程获取锁后，再次请求同一把锁时不会被阻塞，而是直接成功（内部会记录 “重入次数”）。比如：
    java
            运行
    ReentrantLock lock = new ReentrantLock();

lock.lock(); // 第一次获取锁，成功
try {
        lock.lock(); // 再次获取同一把锁，直接成功（重入）
        try {
            // 执行逻辑
        } finally {
            lock.unlock(); // 释放一次，重入次数-1
        }
    } finally {
        lock.unlock(); // 释放第二次，重入次数=0，锁真正释放
    }
    二、ReentrantLock 核心特性
    相比 synchronized，ReentrantLock 的特性更丰富，这也是它的核心价值：
    特性	说明
    可重入性	同一线程可多次获取锁，通过 “重入次数” 记录（释放时需对应次数解锁）
    可中断锁	支持 “中断等待锁的线程”（lockInterruptibly()），避免线程无限等待
    公平 / 非公平锁	可选择 “公平模式”（按等待顺序获取锁）或 “非公平模式”（默认，性能更高）
    条件变量（Condition）	支持多个等待队列（await()/signal()），比 synchronized 的 wait() 更灵活
    手动释放锁	必须通过 unlock() 手动释放（通常在 finally 中），否则会导致死锁
    三、ReentrantLock 底层原理（基于 AQS）
    ReentrantLock 的底层依赖 AQS（AbstractQueuedSynchronizer，抽象队列同步器） 实现。AQS 是 Java 并发工具的 “基础骨架”，本质是一个 “排队管理器”：用一个 同步队列 存储等待锁的线程，用一个 状态变量（state） 控制锁的获取与释放。
            1. AQS 核心结构
    状态变量（state）：int 类型，记录锁的 “重入次数”。state=0 表示锁未被占用；state>0 表示被某线程占用，数值即重入次数。
    同步队列：双向链表，存储所有等待锁的线程（Node 节点），按 “FIFO” 原则排队。
    独占线程（exclusiveOwnerThread）：记录当前持有锁的线程（AQS 的子类 AbstractOwnableSynchronizer 提供）。
            2. ReentrantLock 的加锁过程（以非公平锁为例）
    ReentrantLock 内部有一个抽象静态类 Sync（继承 AQS），并通过两个子类实现公平 / 非公平锁：FairSync（公平）和 NonfairSync（非公平，默认）。
    以默认的 非公平锁 为例，lock() 加锁流程如下：


    第一步：检查 state 是否为 0（锁未被占用）。如果是，直接用 CAS 尝试将 state 改为 1，成功则占有锁。
    第二步：如果 state≠0，检查当前线程是否是 “独占线程”（之前已获取锁）。如果是，state+1（重入计数 + 1），加锁成功。
    第三步：如果以上都不满足，当前线程进入同步队列，进入阻塞状态（LockSupport.park()），等待被唤醒。
            3. ReentrantLock 的解锁过程
    unlock() 解锁流程如下：


    必须由 “独占线程” 调用 unlock()，否则抛 IllegalMonitorStateException。
    每次解锁 state-1，直到 state=0 时，才真正释放锁：清空独占线程，从同步队列唤醒第一个线程（使其有机会获取锁）。
            4. 公平锁 vs 非公平锁的区别
    非公平锁（默认）：线程请求锁时，会先 “插队” 尝试 CAS 获取锁（不管队列中是否有等待线程），失败后再入队。优点是性能高（减少上下文切换），缺点是可能导致线程饥饿（某些线程长期等不到锁）。
    公平锁：线程请求锁时，直接入队排队，按 “先来后到” 顺序获取锁（只有队列中没有等待线程时，才尝试获取）。优点是公平，缺点是性能低（频繁上下文切换）。
    四、ReentrantLock vs synchronized（全面对比）
    两者都是 Java 中实现线程同步的核心工具，但设计和能力差异很大，对比如下：
    维度	ReentrantLock	synchronized
    实现层面	基于 AQS（API 层面，Java 代码实现）	基于 JVM 底层指令（monitorenter/monitorexit）
    锁的释放	必须手动调用 unlock()（通常在 finally 中）	自动释放（代码块执行完或抛异常时）
    可重入性	支持（通过 state 计数）	支持（JVM 自动记录重入次数）
    公平性	可选择（构造函数传 true 为公平锁）	仅非公平锁（无法设置）
    中断响应	支持（lockInterruptibly() 可被中断）	不支持（等待锁的线程无法被中断）
    超时获取	支持（tryLock(long timeout, TimeUnit)）	不支持
    条件变量	支持多个 Condition（多等待队列）	仅一个等待队列（wait()/notify()）
    性能	高并发下更灵活（可避免唤醒全部线程）	JDK 1.6 后优化（偏向锁 / 轻量级锁），性能接近
    使用复杂度	较高（需手动释放，易漏写导致死锁）	简单（自动管理，不易出错）
    关键区别详解：
    锁的释放：
    ReentrantLock 必须手动释放，若忘记 unlock()，会导致锁永远被占用（死锁），因此必须放在 finally 中。
    synchronized 无需手动释放，JVM 会自动处理（即使抛异常也会释放），更安全。
    条件变量：
    ReentrantLock 的 Condition 可以创建多个等待队列。例如：一个锁可以有 “非满”“非空” 两个条件，分别唤醒不同场景的线程（如生产者消费者模型中，生产者等 “非满”，消费者等 “非空”）。
    synchronized 只能用 wait()/notify()，所有等待线程在同一个队列，notify() 会随机唤醒一个，可能唤醒无关线程（效率低）。
    中断和超时：
    ReentrantLock 的 lockInterruptibly() 允许等待锁的线程被中断（比如 thread.interrupt()），避免无限等待。
    synchronized 中，等待锁的线程无法被中断，只能一直等。
    五、如何选择？
    优先用 synchronized：如果场景简单（无需中断、超时、多条件变量），synchronized 更简洁、安全（自动释放），且 JVM 优化成熟。
    选 ReentrantLock：当需要以下功能时：
    公平锁（按顺序获取锁）；
    中断等待锁的线程；
    超时获取锁（避免死等）；
    多个条件变量（精细化唤醒线程）。
    总结
    ReentrantLock 是基于 AQS 实现的灵活锁工具，通过 “状态变量 + 同步队列” 管理锁的竞争，支持可重入、中断、公平性等特性；
    而 synchronized 是 JVM 原生锁，简单易用、自动释放。两者各有优劣，实际开发中需根据场景选择 —— 简单场景用 synchronized，复杂场景用 ReentrantLock。*/
}
