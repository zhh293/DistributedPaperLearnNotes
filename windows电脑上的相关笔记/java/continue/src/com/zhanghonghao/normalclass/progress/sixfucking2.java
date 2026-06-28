package com.zhanghonghao.normalclass.progress;

public class sixfucking2 {
    public static void main(String[] args) {
        synchronized1 s1=new synchronized1();
        synchronized1 s2=new synchronized1();
        synchronized1 s3=new synchronized1();
        s1.start();
        s2.start();
        s3.start();
        MyRunnable myRunnable=new MyRunnable();
        Thread thread=new Thread(myRunnable);
        Thread thread2=new Thread(myRunnable);
        Thread thread3=new Thread(myRunnable);
        thread.setName("窗口一");
        thread2.setName("窗口二");
        thread3.setName("窗口三");
        thread.start();
        thread2.start();
        thread3.start();
    }
}
