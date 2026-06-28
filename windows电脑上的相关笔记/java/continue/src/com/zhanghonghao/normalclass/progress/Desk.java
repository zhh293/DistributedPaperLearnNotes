package com.zhanghonghao.normalclass.progress;

public class Desk {
    //是否有数据  0：没有面条   1：有面条
    public static int foodFlag=0;
    //总个数
    public static int count=10;
    //锁对象
    public static Object object=new Object();
}
/*举个小例子：
public class WaitNotifyDemo {
    private static Object lock = new Object();
    private static boolean foodReady = false;

    // 顾客线程（等待方）
    static class Customer extends Thread {
        public void run() {
            synchronized (lock) {
                System.out.println("顾客：点了牛排，等待通知");
                while (!foodReady) { // 条件不满足，等待
                    try {
                        lock.wait(); // 释放锁，进入等待队列
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println("顾客：收到通知，开始吃饭");
            }
        }
    }

    // 厨师线程（通知方）
    static class Chef extends Thread {
        public void run() {
            synchronized (lock) {
                try {
                    Thread.sleep(2000); // 模拟做饭时间
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                foodReady = true; // 条件改变
                System.out.println("厨师：牛排做好了，通知顾客");
                lock.notify(); // 唤醒等待的顾客线程
            }
        }
    }

    public static void main(String[] args) {
        new Customer().start();
        new Chef().start();
    }
}*/
/*
应用场景
线程间的任务依赖（按顺序执行）
场景描述：
多个线程需要按特定顺序执行（如线程 B 需等待线程 A 完成初始化后再启动），或某个线程需等待其他线程完成某个任务后再继续。
案例：主线程等待子线程完成计算
        java
public class TaskDependency {
    private static final Object lock = new Object();
    private static boolean taskFinished = false;

    public static void main(String[] args) throws InterruptedException {
        // 子线程：模拟耗时任务
        Thread worker = new Thread(() -> {
            synchronized (lock) {
                // 执行任务...
                System.out.println("子线程完成任务");
                taskFinished = true;
                lock.notify(); // 唤醒主线程
            }
        });
        worker.start();

        // 主线程：等待子线程完成
        synchronized (lock) {
            while (!taskFinished) {
                lock.wait(); // 主线程等待子线程通知
            }
            System.out.println("主线程继续执行");
        }
    }
}
*/
