package com.zhanghonghao.normalclass.progressreview;

public class TestThread2 implements Runnable {
    public void run() {
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }
    }
}
