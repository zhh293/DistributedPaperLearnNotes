package com.zhanghonghao.normalclass.progressreview;

public class manyThread {
    public static void main(String[] args) {
        /*说起进程，就不得不说下程序。程序是指令和数据的有序集合，其本身没有任何运行的含义，是一个静态的概念。
        而进程则是执行程序的一次执行过程，它是一个动态的概念，是系统资源分配的单位。
        通常在一个进程中可以包含若干个线程，当然一个进程中至少有一个线程，不然没有存在的意义。线程是 CPU 调度和执行的单位。

        注意：很多多线程是模拟出来的，真正的多线程是指有多个 CPU（即多核，如服务器）。如果是模拟出来的多线程，在一个 CPU 的情况下，
        同一时间点 CPU 只能执行一个代码，因为切换得很快，所以会有 “同时执行” 的错觉。*/
        /*垃圾回收线程GC线程
        *
        *
        *  线程开启不一定立即执行，要看CPU的调度*/
        /*TestThread1 t1 = new TestThread1();
        t1.start();
        for (int i = 0; i < 1000; i++) {
            System.out.println(Thread.currentThread().getName() + ":" + i);
        }*/
       /* Thread t1=new Thread(new TestThread2());
        t1.start();
        for (int i = 0; i < 10; i++) {
            System.out.println(Thread.currentThread().getName()+":"+i);
        }
        System.out.println(t1.getState());
*/
        /*继承 Thread 类
        子类继承 Thread 类具备多线程能力
        启动线程：子类对象.start ()
        不建议使用：避免 OOP 单继承局限性*/
        /*实现 Runnable 接口
        实现接口 Runnable 具有多线程能力
        启动线程：传入目标对象 + Thread 对象.start ()
        推荐使用：避免单继承局限性，灵活方便，方便同一个对象被多个线程使用*/
    }
}
/*//下载器
class WebDownloader {
    //下载方法
    public void downloader(String url, String name) {
        try {
            FileUtils.copyURLToFile(new URL(url), new File(name));
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("IO异常,downloader方法出现问题");
        }
    }
}*/

//练习Thread，实现多线程同步下载图片
/*public class TestThread2 extends Thread {
    private String url; //网络图片地址
    private String name; //保存的文件名

    public TestThread2(String url, String name) {
        this.url = url;
        this.name = name;
    }

    @Override
    public void run() {
        WebDownloader webDownloader = new WebDownloader();
        webDownloader.downloader(url, name);
        System.out.println("下载了文件名为:" + name);
    }

    public static void main(String[] args) {
        TestThread2 t1 = new TestThread2("https://blog.kuangstudy.com/usr/themes/handsome/usr/img/sj/1.jpg", "1.jpg");
        TestThread2 t2 = new TestThread2("https://blog.kuangstudy.com/usr/themes/handsome/usr/img/sj/2.jpg", "2.jpg");
        TestThread2 t3 = new TestThread2("https://blog.kuangstudy.com/usr/themes/handsome/usr/img/sj/3.jpg", "3.jpg");

        t1.start();
        t2.start();
        t3.start();
    }
}*/
