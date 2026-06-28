package com.zhanghonghao.normalclass.progress;

public class progress2 extends Thread {
    @Override
    public void run() {
        for (int i = 0; i < 150; i++) {
            System.out.println(this.getName()+"哈哈哈");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //Thread.yield();
        }
    }
}
