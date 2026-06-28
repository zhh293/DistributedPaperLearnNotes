/*package com.zhh.handsome.可重入锁;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class 吸烟室例子 {
    private ReentrantLock lock = new ReentrantLock();
    private Condition smokeCondition = lock.newCondition();
    private Condition sendCondition = lock.newCondition();

    private boolean isSmoke = false;
    private boolean isSend = false;
    public void main() {
        new Thread(){
            @Override
            public void run() {
                System.out.println("开始吸烟");
                lock.lock();
                try {
                    while (!isSmoke) {
                        System.out.println("没有烟，等待送烟");
                        smokeCondition.await();
                    }
                    System.out.println("有烟了，开始吸烟");
                    sendCondition.signal();
                    smokeCondition.signalAll();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    lock.unlock();
                }
            }
        }.start();
        new Thread(){
            @Override
            public void run() {
                System.out.println("开始送烟");
                lock.lock();
                try {
                    while (!isSend) {
                        System.out.println("没有烟，等待送烟");
                        sendCondition.await();
                    }
                    System.out.println("有烟了，开始送烟");
                    smokeCondition.signal();
                    smokeCondition.signalAll();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }finally {
                    lock.unlock();
                }
            }
        }.start();
    }
}*/
