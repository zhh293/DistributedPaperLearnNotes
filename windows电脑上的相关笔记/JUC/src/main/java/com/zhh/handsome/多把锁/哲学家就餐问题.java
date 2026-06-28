package com.zhh.handsome.多把锁;

public class 哲学家就餐问题 {



   /* 一、问题描述
    有 5 位哲学家围坐在圆形餐桌旁，每人面前有一碗意大利面，每两人之间放一根筷子（共 5 根）。哲学家的行为只有两种：思考（不占用资源）和就餐（需要同时使用左右两根筷子）。
    具体流程：
    哲学家思考一段时间后感到饥饿，试图拿起左右两根筷子；
    拿到两根筷子后就餐；
    就餐结束后放下筷子，继续思考。
    二、核心矛盾：死锁与饥饿
1. 死锁问题
    若每位哲学家同时拿起左手边的筷子，此时每人都持有一根筷子，且等待右手边的筷子（被他人持有），形成循环等待，导致所有哲学家永远无法就餐（死锁）。
    死锁的 4 个必要条件在此全部满足：
    互斥：筷子一次只能被一个哲学家使用；
    持有并等待：哲学家持有一根筷子，同时等待另一根；
    不可剥夺：筷子一旦被拿起，不能被强制夺走；
    循环等待：每位哲学家都在等待下一位的筷子。
            2. 饥饿问题
    即使避免了死锁，也可能出现 “饥饿”：某些哲学家因长期无法获取筷子而永远无法就餐（例如，资源分配策略不公平）。
    三、解决方案（Java 实现）
    解决思路是破坏死锁的必要条件（如循环等待、持有并等待），同时避免饥饿。以下是几种经典方案及 Java 实现。
    方案 1：限制同时拿筷子的哲学家数量（破坏 “持有并等待”）
    核心思想：最多允许 4 位哲学家同时尝试拿筷子。由于只有 5 根筷子，若最多 4 人竞争，至少有 1 人能拿到两根筷子（避免所有人都持有一根筷子的情况）。
    Java 实现：用Semaphore（信号量）限制并发数为 4，哲学家拿筷子前需先获取信号量，结束后释放。
    java
            运行
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

    // 筷子用可重入锁表示（支持显式获取/释放）
    class Chopstick extends ReentrantLock {}

    // 哲学家线程
    class Philosopher extends Thread {
        private final int id; // 哲学家编号0-4
        private final Chopstick leftChopstick; // 左筷子
        private final Chopstick rightChopstick; // 右筷子
        private final Semaphore semaphore; // 限制并发的信号量

        public Philosopher(int id, Chopstick left, Chopstick right, Semaphore sem) {
            this.id = id;
            this.leftChopstick = left;
            this.rightChopstick = right;
            this.semaphore = sem;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    think(); // 思考
                    semaphore.acquire(); // 尝试获取信号量（最多4人同时拿筷子）

                    // 拿左筷子
                    leftChopstick.lock();
                    try {
                        // 拿右筷子
                        rightChopstick.lock();
                        try {
                            eat(); // 就餐
                        } finally {
                            rightChopstick.unlock(); // 释放右筷子
                        }
                    } finally {
                        leftChopstick.unlock(); // 释放左筷子
                        semaphore.release(); // 释放信号量
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 模拟思考
        private void think() throws InterruptedException {
            System.out.println("哲学家" + id + "正在思考");
            Thread.sleep((long) (Math.random() * 1000));
        }

        // 模拟就餐
        private void eat() throws InterruptedException {
            System.out.println("哲学家" + id + "正在就餐");
            Thread.sleep((long) (Math.random() * 1000));
        }
    }

    // 主程序
    public class DiningPhilosophers1 {
        public static void main(String[] args) {
            int n = 5; // 5位哲学家
            Chopstick[] chopsticks = new Chopstick[n];
            for (int i = 0; i < n; i++) {
                chopsticks[i] = new Chopstick();
            }
            Semaphore semaphore = new Semaphore(n - 1); // 最多4人同时拿筷子

            // 启动哲学家线程
            for (int i = 0; i < n; i++) {
                Chopstick left = chopsticks[i];
                Chopstick right = chopsticks[(i + 1) % n]; // 右筷子是下一根
                new Philosopher(i, left, right, semaphore).start();
            }
        }
    }
    原理：通过信号量限制并发数，确保不会出现 5 人同时持有一根筷子的情况，破坏 “持有并等待” 条件，避免死锁。
    方案 2：按固定顺序拿筷子（破坏 “循环等待”）
    核心思想：给筷子编号（0-4），规定哲学家必须先拿编号较小的筷子，再拿编号较大的筷子。由于编号递增，不会出现 “循环等待”（例如，哲学家 4 不会等待编号更大的筷子，而是等待更小的 0）。
    Java 实现：通过Math.min和Math.max确定拿筷子的顺序。
    java
            运行
import java.util.concurrent.locks.ReentrantLock;

    class Chopstick extends ReentrantLock {}

    class Philosopher extends Thread {
        private final int id;
        private final Chopstick[] chopsticks; // 所有筷子的数组

        public Philosopher(int id, Chopstick[] chopsticks) {
            this.id = id;
            this.chopsticks = chopsticks;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    think();

                    // 确定左右筷子的编号（左：id，右：(id+1)%5）
                    int left = id;
                    int right = (id + 1) % 5;

                    // 先拿编号小的筷子，再拿大的
                    int first = Math.min(left, right);
                    int second = Math.max(left, right);

                    chopsticks[first].lock();
                    try {
                        chopsticks[second].lock();
                        try {
                            eat();
                        } finally {
                            chopsticks[second].unlock();
                        }
                    } finally {
                        chopsticks[first].unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void think() throws InterruptedException { *//* 同方案1 *//* }
        private void eat() throws InterruptedException { *//* 同方案1 *//* }
    }

    public class DiningPhilosophers2 {
        public static void main(String[] args) {
            int n = 5;
            Chopstick[] chopsticks = new Chopstick[n];
            for (int i = 0; i < n; i++) {
                chopsticks[i] = new Chopstick();
            }

            for (int i = 0; i < n; i++) {
                new Philosopher(i, chopsticks).start();
            }
        }
    }
    原理：通过固定拿筷子的顺序（从小到大），确保资源竞争链是线性的（无循环），破坏 “循环等待” 条件，避免死锁。
    方案 3：尝试获取筷子（拿不到则释放已持有资源，破坏 “持有并等待”）
    核心思想：哲学家尝试拿左筷子，若拿到则尝试拿右筷子；若右筷子拿不到，则主动释放左筷子，等待一段时间后重试。避免 “持有并等待”。
    Java 实现：用tryLock（带超时的尝试获取锁）实现，拿不到则释放已持有的锁。
    java
            运行
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

    class Chopstick extends ReentrantLock {}

    class Philosopher extends Thread {
        private final int id;
        private final Chopstick leftChopstick;
        private final Chopstick rightChopstick;

        public Philosopher(int id, Chopstick left, Chopstick right) {
            this.id = id;
            this.leftChopstick = left;
            this.rightChopstick = right;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    think();

                    boolean leftAcquired = false;
                    boolean rightAcquired = false;

                    try {
                        // 尝试拿左筷子（超时100ms）
                        leftAcquired = leftChopstick.tryLock(100, TimeUnit.MILLISECONDS);
                        if (leftAcquired) {
                            // 拿到左筷子后尝试拿右筷子
                            rightAcquired = rightChopstick.tryLock(100, TimeUnit.MILLISECONDS);
                            if (rightAcquired) {
                                eat(); // 成功拿到两根，就餐
                            }
                        }
                    } finally {
                        // 释放已拿到的筷子
                        if (rightAcquired) rightChopstick.unlock();
                        if (leftAcquired) leftChopstick.unlock();
                    }

                    // 重试前等待一段时间，避免频繁竞争
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void think() throws InterruptedException { *//* 同方案1 *//* }
        private void eat() throws InterruptedException { *//* 同方案1 *//* }
    }

    public class DiningPhilosophers3 {
        public static void main(String[] args) {
            int n = 5;
            Chopstick[] chopsticks = new Chopstick[n];
            for (int i = 0; i < n; i++) {
                chopsticks[i] = new Chopstick();
            }

            for (int i = 0; i < n; i++) {
                Chopstick left = chopsticks[i];
                Chopstick right = chopsticks[(i + 1) % 5];
                new Philosopher(i, left, right).start();
            }
        }
    }
    原理：通过tryLock确保不会长期持有一根筷子等待另一根，若拿不到则释放已持有资源，破坏 “持有并等待” 条件，避免死锁。
    四、方案对比
    方案	核心逻辑	优点	缺点
    限制并发数	最多 4 人同时拿筷子	实现简单，无死锁	需额外信号量，可能有轻微性能损耗
    固定顺序拿筷子	先拿小编号，再拿大编号	无额外开销，无死锁	可能导致某根筷子竞争激烈（如编号 0）
    尝试获取 + 释放资源	拿不到则释放已持有资源	灵活性高，适合动态场景	可能频繁重试，有一定开销
            总结
    哲学家就餐问题的本质是共享资源竞争的同步控制。
    Java 中可通过ReentrantLock（显式锁）、Semaphore（信号量）等机制，通过破坏死锁的必要条件（如循环等待、持有并等待）解决问题。
    实际开发中需根据场景选择方案，优先保证无死锁且避免饥饿。*/

















}
