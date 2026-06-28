package com.zhanghonghao.normalclass.progress;

public class MyRunnable implements Runnable {
    int ticket=0;

    public void run() {
        while(true){
            synchronized (MyRunnable.class){
                if(ticket<100){
                    try{
                        Thread.sleep(10);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                    ticket++;
                    System.out.println(Thread.currentThread().getName()+"第"+ticket+"张票");
                }else{
                    break;
                }
            }
        }

    }
}
