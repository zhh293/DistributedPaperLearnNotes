package com.zhanghonghao.normalclass.progress;

public class progress1 implements Runnable {
    public void run() {
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            System.out.println(Thread.currentThread().getName());
        }
    }
}
/*MyRunnable mr = new MyRunnable();

Thread t1 = new Thread(mr);

Thread t2 = new Thread(mr);

Thread t3 = new Thread(mr);

t1.setName("窗囗1");

t2.setName("窗囗2”);

t3.setName("窗ㄇ3");

t1.start();

t.start();

t1.start();

*/
/*所以t1，t2，t3操作的都是同一个内存地址，相当于共享ticket数据
然后我们把对象mr传入到每个Thread的构造方法MyRunnable对象在创建好后，
内存中开辟一个位置存放ticket
这时每个线程都可以根据ticket的内存地址修改其数据:
这时锁对象就可以使用this对象了，因为this对象是唯一的*/
/*单线程使用stringbuilder，多线程，需要考虑数据安全，就用stringbuffer*/
