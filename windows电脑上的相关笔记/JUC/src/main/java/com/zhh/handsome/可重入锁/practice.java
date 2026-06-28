package com.zhh.handsome.可重入锁;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class practice {

    //写法二
    private int flag ;
    private int loopTimes;
    public practice(int flag,int loopTimes){
        this.flag = flag;
        this.loopTimes = loopTimes;
    }
    public void print(String str,int flag,int nextFlag){
        for (int i = 0; i < loopTimes; i++){
            synchronized (this){
                while (flag != this.flag){
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println(str);
                this.flag = nextFlag;
                this.notifyAll();
            }
        }
    }

    public static void main(String[] args) {
        practice p = new practice(1,10);
        new Thread(()->p.print("A",1,2)).start();
        new Thread(()->p.print("B",2,3)).start();
        new Thread(()->p.print("C",3,1)).start();
    }



    /*private static final ReentrantLock lock = new ReentrantLock();
    // 三个条件变量，分别对应A、B、C的等待队列
    private static final Condition aCond = lock.newCondition();
    private static final Condition bCond = lock.newCondition();
    private static final Condition cCond = lock.newCondition();

    private static int count = 0; // 控制打印次数（0-29，共30次，A、B、C各10次）

    public static void main(String[] args) {
        // 线程A：负责打印A（count%3==0时）
        new Thread(() -> print("A", 0, aCond, bCond)).start();
        // 线程B：负责打印B（count%3==1时）
        new Thread(() -> print("B", 1, bCond, cCond)).start();
        // 线程C：负责打印C（count%3==2时）
        new Thread(() -> print("C", 2, cCond, aCond)).start();
    }

    *//**
     * @param name      打印的字符（A/B/C）
     * @param target    轮到自己的条件（count%3==target）
     * @param selfCond  自己的等待队列（没轮到时等待）
     * @param nextCond  下一个线程的等待队列（打印后唤醒下一个）
     *//*
    private static void print(String name, int target, Condition selfCond, Condition nextCond) {
        for (int i = 0; i < 10; i++) { // 打印10次后退出
            lock.lock();
            try {
                // 没轮到自己时，进入等待队列（释放锁，等待被唤醒）
                while (count % 3 != target) {
                    selfCond.await();
                }
                // 轮到自己，打印
                System.out.println(name);
                count++; // 切换到下一个
                nextCond.signal(); // 唤醒下一个线程
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                lock.unlock();
            }
        }
    }*/
}
