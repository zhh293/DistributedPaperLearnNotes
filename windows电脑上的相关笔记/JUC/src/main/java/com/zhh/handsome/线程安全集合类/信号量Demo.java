package com.zhh.handsome.线程安全集合类;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

public class 信号量Demo {
    public static void main(String[] args) {
        Semaphore semaphore=new Semaphore(5);
        CountDownLatch countDownLatch=new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            new Thread(()->{
                try {
                    semaphore.acquire();
                    System.out.println(Thread.currentThread().getName()+"正在使用");
                    Thread.sleep(2000);
                    System.out.println(Thread.currentThread().getName()+"正在使用完成");
                    semaphore.release();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }finally {
                    countDownLatch.countDown();
                }
            }).start();
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
