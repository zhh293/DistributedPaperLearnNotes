package com.zhanghonghao.normalclass.progressreview;

public class TestThread1 extends Thread{
    @Override
    public void run() {
        for (int i = 0; i < 1000; i++) {
            System.out.println(i);
        }
    }
}
