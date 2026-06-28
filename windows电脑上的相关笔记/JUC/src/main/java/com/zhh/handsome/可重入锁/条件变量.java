package com.zhh.handsome.可重入锁;

public class 条件变量 {
    /*条件变量（Condition）是 ReentrantLock 中非常强大的特性，核心价值是让线程能根据不同的 “条件” 等待或被唤醒，解决了 synchronized 中 “唤醒线程盲目性” 的问题。下面通过 “生产者 - 消费者模型” 这个经典场景，对比着讲清楚两者的区别，再用代码直观展示。
    一、先理解核心问题：为什么需要多个等待队列？
    在生产者 - 消费者模型中，有两个核心条件：
    生产者要等 “缓冲区非满”（才能往里面放数据）；
    消费者要等 “缓冲区非空”（才能从里面拿数据）。
    如果用 synchronized 的 wait()/notify()，所有等待的线程（不管是生产者还是消费者）都挤在同一个等待队列里。当缓冲区有空间时，notify() 可能随机唤醒一个消费者（此时消费者发现缓冲区还是空的，白唤醒了）；当缓冲区有数据时，notify() 可能随机唤醒一个生产者（此时生产者发现缓冲区还是满的，也白唤醒了）。这种 “无效唤醒” 会导致效率低下。
    而 ReentrantLock 的 Condition 可以创建多个等待队列：
    生产者专门在 “非满” 条件队列里等；
    消费者专门在 “非空” 条件队列里等。
    当缓冲区有空间时，只唤醒 “非满” 队列里的生产者；当缓冲区有数据时，只唤醒 “非空” 队列里的消费者。精准唤醒，没有无效操作。
    二、代码对比：synchronized vs Condition
    用一个 “固定大小的缓冲区” 为例，分别实现生产者 - 消费者模型，直观感受差异。
    场景设定：
    缓冲区是一个列表，最大容量为 2（满了之后生产者不能放，空了之后消费者不能拿）。
    生产者线程：往缓冲区加数据，若满了则等待。
    消费者线程：从缓冲区拿数据，若空了则等待。
            1. synchronized 实现（问题明显）
    java
            运行
import java.util.ArrayList;
import java.util.List;

    public class SyncDemo {
        private static final List<Integer> buffer = new ArrayList<>(); // 缓冲区
        private static final int MAX_SIZE = 2; // 缓冲区最大容量

        public static void main(String[] args) {
            // 1个生产者，2个消费者
            new Thread(new Producer(), "生产者").start();
            new Thread(new Consumer(), "消费者1").start();
            new Thread(new Consumer(), "消费者2").start();
        }

        // 生产者：往缓冲区加数据
        static class Producer implements Runnable {
            private int i = 0;
            @Override
            public void run() {
                while (true) {
                    synchronized (buffer) {
                        // 若缓冲区满了，等待（等“非满”条件）
                        while (buffer.size() == MAX_SIZE) {
                            try {
                                System.out.println("缓冲区满了，生产者等待...");
                                buffer.wait(); // 进入等待队列
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        // 生产数据
                        buffer.add(i);
                        System.out.println(Thread.currentThread().getName() + "生产了：" + i++);
                        buffer.notify(); // 随机唤醒一个等待线程（可能是生产者或消费者）
                    }
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                }
            }
        }

        // 消费者：从缓冲区拿数据
        static class Consumer implements Runnable {
            @Override
            public void run() {
                while (true) {
                    synchronized (buffer) {
                        // 若缓冲区空了，等待（等“非空”条件）
                        while (buffer.isEmpty()) {
                            try {
                                System.out.println(Thread.currentThread().getName() + "发现缓冲区空了，等待...");
                                buffer.wait(); // 进入等待队列
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        // 消费数据
                        int data = buffer.remove(0);
                        System.out.println(Thread.currentThread().getName() + "消费了：" + data);
                        buffer.notify(); // 随机唤醒一个等待线程
                    }
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                }
            }
        }
    }
            2. ReentrantLock + Condition 实现（精准唤醒）
    java
            运行
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

    public class ConditionDemo {
        private static final List<Integer> buffer = new ArrayList<>();
        private static final int MAX_SIZE = 2;
        private static final ReentrantLock lock = new ReentrantLock();
        // 创建两个条件变量：
        private static final Condition notFull = lock.newCondition(); // 生产者等“非满”
        private static final Condition notEmpty = lock.newCondition(); // 消费者等“非空”

        public static void main(String[] args) {
            new Thread(new Producer(), "生产者").start();
            new Thread(new Consumer(), "消费者1").start();
            new Thread(new Consumer(), "消费者2").start();
        }

        static class Producer implements Runnable {
            private int i = 0;
            @Override
            public void run() {
                while (true) {
                    lock.lock(); // 获取锁
                    try {
                        // 若缓冲区满了，在 notFull 条件队列等待
                        while (buffer.size() == MAX_SIZE) {
                            System.out.println("缓冲区满了，生产者等待...");
                            notFull.await(); // 释放锁，进入 notFull 队列
                        }
                        // 生产数据
                        buffer.add(i);
                        System.out.println(Thread.currentThread().getName() + "生产了：" + i++);
                        // 生产后，缓冲区非空了，唤醒 notEmpty 队列里的消费者
                        notEmpty.signal();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock(); // 释放锁
                    }
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                }
            }
        }

        static class Consumer implements Runnable {
            @Override
            public void run() {
                while (true) {
                    lock.lock();
                    try {
                        // 若缓冲区空了，在 notEmpty 条件队列等待
                        while (buffer.isEmpty()) {
                            System.out.println(Thread.currentThread().getName() + "发现缓冲区空了，等待...");
                            notEmpty.await(); // 释放锁，进入 notEmpty 队列
                        }
                        // 消费数据
                        int data = buffer.remove(0);
                        System.out.println(Thread.currentThread().getName() + "消费了：" + data);
                        // 消费后，缓冲区非满了，唤醒 notFull 队列里的生产者
                        notFull.signal();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                    try { Thread.sleep(500); } catch (InterruptedException e) {}
                }
            }
        }
    }
// 完全没有无效唤醒，每次唤醒的都是需要的线程
    三、Condition 核心原理与关键点
1. Condition 和锁的关系
    一个 ReentrantLock 可以创建多个 Condition（通过 lock.newCondition()），每个 Condition 对应一个独立的等待队列。
    调用 condition.await() 时，线程会释放持有的锁，进入该 Condition 的等待队列；调用 condition.signal() 时，会从该队列中唤醒一个线程（signalAll() 唤醒所有）。
            2. 和 synchronized 的 wait/notify 对比
    对比项	synchronized（wait/notify）	ReentrantLock + Condition
    等待队列数量	1 个（所有线程混在一起）	多个（每个 Condition 一个队列）
    唤醒目标	随机唤醒一个（可能唤醒无关线程）	精准唤醒特定条件队列里的线程
    唤醒方式	notify()（随机）/ notifyAll()（全部）	signal()（单个）/ signalAll()（全部）
    与锁的绑定	锁对象本身就是等待队列的载体	锁和 Condition 分离，一个锁可绑定多个 Condition
3. 必须注意的细节
    await () 必须在锁保护下：和 wait() 一样，await() 必须在 lock() 之后调用，否则会抛 IllegalMonitorStateException（没拿到锁就想等，不合理）。
    用 while 循环检查条件：await() 可能被 “虚假唤醒”（比如 JVM 内部原因唤醒，但条件并未满足），所以必须用 while 而不是 if 检查条件（比如 while (buffer.isEmpty()) { ... }）。
    释放锁：await() 会自动释放锁，被唤醒后会重新竞争锁，获取到锁后才会继续执行 await() 之后的代码。
    总结
    Condition 的核心价值是将等待队列按 “条件” 拆分，实现精准唤醒，避免 synchronized 中 “无效唤醒” 的问题，尤其适合多条件控制的场景（如生产者 - 消费者、线程池任务调度等）。
    简单说：synchronized 是 “大锅饭”（所有线程等一个队列，唤醒靠运气），Condition 是 “分餐制”（按条件排队，唤醒谁我说了算）。*/
}
