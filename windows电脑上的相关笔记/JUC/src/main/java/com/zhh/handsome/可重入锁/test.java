package com.zhh.handsome.可重入锁;

import java.util.concurrent.locks.LockSupport;

public class test {
    static final Object lock = new Object();
    static boolean t2 = false;
    public static void main(String[] args) {
        //park,unpark
        /*Thread thread = new Thread() {
            @Override
            public void run() {
                LockSupport.park();
                System.out.println("t1");
            }
        };
        thread.start();

        new Thread(){
            @Override
            public void run() {
                LockSupport.unpark(thread);
                System.out.println("t2");
            }
        }.start();*/






        /*new Thread(){
            @Override
            public void run() {
                synchronized (lock){
                    System.out.println("t1");
                    while (!t2){
                        try {
                            lock.wait();
                        }catch (InterruptedException e){
                            e.printStackTrace();
                        }
                    }
                    System.out.println("t1哈哈哈");
                }
            }
        }.start();

        new Thread(){
            @Override
            public void run() {
                synchronized (lock){
                    System.out.println("t2");
                    t2 = true;
                    lock.notifyAll();
                    System.out.println("t2哈哈哈");
                }
            }
        }.start();*/
    }
}
