package com.zhh.handsome.Demo5;

public class 解决方案 {



    /*我们用一个 “两个线程争夺两个资源” 的场景来模拟活锁：
    场景设定：
    有两个线程（ThreadA和ThreadB），都需要同时获取资源ResourceX和ResourceY才能执行任务。
    ThreadA的逻辑：先拿ResourceX，再拿ResourceY；如果拿不到ResourceY，就释放ResourceX，重试。
    ThreadB的逻辑：先拿ResourceY，再拿ResourceX；如果拿不到ResourceX，就释放ResourceY，重试。
    活锁过程：
    ThreadA拿到ResourceX，ThreadB拿到ResourceY（同时发生）。
    ThreadA想拿ResourceY，发现被ThreadB占用，于是释放ResourceX，准备重试。
    同时，ThreadB想拿ResourceX，发现被ThreadA占用（其实ThreadA刚释放），于是释放ResourceY，准备重试。
    紧接着，ThreadA和ThreadB又同时去拿资源，重复步骤 1-3…… 永远循环，谁也拿不到两个资源。
    代码模拟（Java）：
    java
            运行
import java.util.concurrent.locks.ReentrantLock;

    // 定义两个资源（用锁表示）
    class Resource {
        private final String name;
        public Resource(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public class LiveLockDemo {
        // 两个资源
        private static final Resource X = new Resource("X");
        private static final Resource Y = new Resource("Y");
        // 用可重入锁控制资源访问
        private static final ReentrantLock lockX = new ReentrantLock();
        private static final ReentrantLock lockY = new ReentrantLock();

        public static void main(String[] args) {
            // 线程A：先拿X，再拿Y
            Thread threadA = new Thread(() -> {
                while (true) {
                    // 尝试拿X
                    if (lockX.tryLock()) {
                        try {
                            System.out.println("线程A拿到资源X，尝试拿Y...");
                            // 尝试拿Y（如果拿不到，就释放X）
                            if (lockY.tryLock()) {
                                try {
                                    System.out.println("线程A拿到资源Y，执行任务！");
                                    break; // 成功拿到两个资源，退出循环
                                } finally {
                                    lockY.unlock();
                                }
                            } else {
                                System.out.println("线程A没拿到Y，释放X，重试...");
                            }
                        } finally {
                            lockX.unlock(); // 释放X
                        }
                    }
                    // 重试前短暂等待（注意：这里等待时间固定，导致节奏同步）
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                }
            }, "ThreadA");

            // 线程B：先拿Y，再拿X
            Thread threadB = new Thread(() -> {
                while (true) {
                    // 尝试拿Y
                    if (lockY.tryLock()) {
                        try {
                            System.out.println("线程B拿到资源Y，尝试拿X...");
                            // 尝试拿X（如果拿不到，就释放Y）
                            if (lockX.tryLock()) {
                                try {
                                    System.out.println("线程B拿到资源X，执行任务！");
                                    break; // 成功拿到两个资源，退出循环
                                } finally {
                                    lockX.unlock();
                                }
                            } else {
                                System.out.println("线程B没拿到X，释放Y，重试...");
                            }
                        } finally {
                            lockY.unlock(); // 释放Y
                        }
                    }
                    // 重试前短暂等待（和线程A等待时间相同，导致同步）
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                }
            }, "ThreadB");

            threadA.start();
            threadB.start();
        }
    }
    运行结果（活锁现象）：
    plaintext
    线程A拿到资源X，尝试拿Y...
    线程B拿到资源Y，尝试拿X...
    线程A没拿到Y，释放X，重试...
    线程B没拿到X，释放Y，重试...
    线程A拿到资源X，尝试拿Y...
    线程B拿到资源Y，尝试拿X...
    线程A没拿到Y，释放X，重试...
    线程B没拿到X，释放Y，重试...
            ...（无限循环）
    二、活锁的解决方案
    活锁的核心问题是 “线程调整节奏同步”，解决思路就是打破这种同步，让线程的重试行为错开。常见方案有 3 种：
    方案 1：引入随机等待时间（最常用）
    让线程释放资源后，等待一段随机时间再重试，避免 “同时行动”。修改上面的代码，将固定的Thread.sleep(100)改为随机时间：
    java
            运行
// 线程A的重试等待（线程B同理）
try {
        // 随机等待50-150ms，避免和线程B同步
        Thread.sleep((long) (Math.random() * 100 + 50));
    } catch (InterruptedException e) {}
    原理：随机时间会让两个线程的重试节奏错开，总有一次其中一个线程能先拿到两个资源，打破循环。
    方案 2：固定资源获取顺序（避免冲突）
    规定所有线程必须按相同的顺序获取资源（比如先拿编号小的资源），从根本上避免 “互相等待对方释放资源” 的情况。
    比如在上面的例子中，规定 “必须先拿 X，再拿 Y”：
    线程 A 和线程 B 都先尝试拿 X，再拿 Y。
    此时只会有一个线程能拿到 X，另一个线程会等待，直到 X 被释放后再继续，不会出现 “互相释放” 的情况。
    方案 3：引入优先级（让某个线程 “谦让”）
    给线程设置优先级，低优先级线程主动让高优先级线程先获取资源。
    比如规定 “线程 A 优先级高于线程 B”：
    当线程 B 发现线程 A 在竞争资源时，主动延迟更长时间再重试，让线程 A 先完成。
    注意：优先级需要结合业务场景设计，避免低优先级线程 “饿死”。
    总结
    活锁的本质是 “线程同步调整导致的无效循环”，解决的核心是打破同步节奏。实际开发中，最简单有效的方法是 “随机等待时间”，通过随机性让线程的重试行为错开，从而避免无限循环。
*/








}
