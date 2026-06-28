package com.zhanghonghao.normalclass.progress;

public class Cook extends Thread {
    @Override
    public void run() {
        /*
        * 循环
        * 同步代码块
        * 判断共享数据是否到了末尾
        * 有
        * 没有，执行核心逻辑*/
        while (true) {
            synchronized (Desk.object) {
                if(Desk.foodFlag==0){
                    if(Desk.count==0){
                        break;
                    }
                    else{
                        Desk.foodFlag=1;
                        Desk.object.notifyAll();
                        System.out.println("还有"+Desk.count+"碗等着我去做");
                    }

                }else {
                    try {
                        Desk.object.wait();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                }

            }
        }
    }
}
/*等待唤醒机制的核心：让暂时无法执行的线程 “停下来等通知”，而不是无意义地循环检查。
二、核心方法：wait () 和 notify ()（以 Java 为例）
Java 中，每个对象都有一个 “监视器（锁）”，配合 wait()、notify()、notifyAll() 方法实现线程间通信，必须在 synchronized 代码块中使用（因为需要获取对象锁）。
        1. 等待（wait ()）
当线程 A 发现条件不满足（比如牛排没做好），会：

释放对象锁（重要！否则其他线程无法获取锁修改条件）。
进入该对象的 等待队列，暂时停止执行。

java
synchronized (lock) { // 获得锁
        while (条件不满足) { // 用while避免“虚假唤醒”
        lock.wait(); // 释放锁，进入等待队列
    }
            // 条件满足，继续执行
            }
            2. 唤醒（notify () /notifyAll ()）
当线程 B 改变了条件（比如牛排做好了），会：

调用 notify()：从等待队列中随机唤醒一个线程（如果有多个等待线程）。
调用 notifyAll()：唤醒等待队列中的所有线程（通常用于多个线程等待同一个条件的场景）。
唤醒后，被唤醒的线程不会立刻执行，而是重新竞争对象锁，拿到锁后再检查条件是否满足（因为可能被其他线程先抢到锁并修改了条件）。

java
synchronized (lock) { // 获得锁
        // 修改条件（比如设置“牛排已做好”）
        lock.notify(); // 唤醒一个等待线程（或notifyAll()唤醒所有）
}

三、核心逻辑：用 “条件变量” 串联线程
所有等待唤醒的场景，核心都是围绕一个 “共享条件” 展开，比如：

生产者 - 消费者模型：消费者等待生产者生产数据，生产者生产后通知消费者。
线程 A 等待线程 B 计算完结果：线程 B 计算完后通知线程 A 继续处理。

多个线程共享一个 “条件变量”（比如一个布尔值 isReady）。
等待方（消费者）：
检查条件是否满足，不满足则调用 wait() 等待。
被唤醒后，再次检查条件（防止虚假唤醒，必须用 while 循环）。
通知方（生产者）：
改变条件（比如设置 isReady=true）。
调用 notify() 或 notifyAll() 唤醒等待线程。
四、关键细节：为什么必须配合锁？
wait() 和 notify() 必须在 synchronized 代码块中调用，因为：
等待线程需要通过对象锁判断条件（条件变量通常是对象的成员变量，修改时需要锁保证原子性）。
调用 wait() 时，线程必须持有对象锁，否则会抛出 IllegalMonitorStateException。
释放锁后，其他线程才能获取锁并修改条件，否则通知方无法修改条件，等待方永远等不到通知。*/
