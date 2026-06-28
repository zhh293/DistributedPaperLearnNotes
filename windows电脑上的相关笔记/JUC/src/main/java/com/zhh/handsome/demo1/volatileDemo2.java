package com.zhh.handsome.demo1;

public class volatileDemo2 {

    /*最常用的 3 个场景，用例子说清楚：
            1. 状态标记变量（线程间的 “开关”）
    最常见的场景：用一个变量作为 “开关”，控制线程的启动 / 停止。

    比如，我们有一个后台线程一直在循环工作，现在想通过主线程发送一个 “停止信号” 让它退出。这时候这个 “停止信号” 变量就需要用volatile修饰。

    举个例子：

    java
            运行
    // 状态标记变量，用volatile修饰
    volatile boolean isRunning = true;

// 后台工作线程
new Thread(() -> {
        while (isRunning) { // 不断检查“开关”
            System.out.println("线程运行中...");
        }
        System.out.println("线程已停止");
    }).start();

// 主线程：3秒后发送“停止信号”
Thread.sleep(3000);
    isRunning = false; // 修改开关




    为什么需要 volatile？
    如果isRunning不加volatile，
    后台线程可能会一直读取自己工作内存中的 “旧值”（CPU 缓存导致的可见性问题），即使主线程已经把它改成了false，后台线程也看不到，会一直死循环。加了volatile后，主线程修改后，后台线程能立刻看到最新值，顺利退出。*/





    /*2. 单例模式的 “双重检查锁定”
    在单例模式中，为了既保证线程安全又提高效率，会用到 “双重检查锁定”（DCL）。这时候单例对象必须用volatile修饰。

    例子：

    java
            运行
    public class Singleton {
        // 单例对象用volatile修饰
        private static volatile Singleton instance;

        private Singleton() {} // 私有构造，禁止外部创建

        public static Singleton getInstance() {
            if (instance == null) { // 第一次检查：避免每次加锁
                synchronized (Singleton.class) { // 加锁
                    if (instance == null) { // 第二次检查：防止多线程同时通过第一次检查
                        instance = new Singleton(); // 创建对象
                    }
                }
            }
            return instance;
        }
    }*/

    /*为什么需要 volatile？
            new Singleton()这个操作在底层会被拆分成 3 步：

    分配内存空间；
    初始化对象；
    把instance指向分配的内存。

    如果不加volatile，CPU 可能会为了优化而 “重排序”，把步骤 2 和 3 调换顺序（先指向内存，再初始化对象）。这时候如果另一个线程刚好走到第一次检查，会发现instance已经不是null，就会直接返回一个 “未初始化完成” 的对象，导致错误。
    volatile可以禁止这种重排序，保证对象初始化完成后才会被其他线程看到。
            3. 硬件寄存器映射的变量（嵌入式场景）
    在嵌入式开发中，有些变量可能直接对应硬件的寄存器（比如传感器的实时数据、设备的状态码）。这些变量的值可能会被硬件直接修改（不是由软件线程修改），这时候需要用volatile保证每次读取都是 “实时值”。

    比如：

    java
            运行
    // 假设这个变量映射到温度传感器的寄存器
    volatile int temperature;

// 读取温度的线程
new Thread(() -> {
        while (true) {
            // 每次都能读到传感器的最新温度（不会用缓存值）
            System.out.println("当前温度：" + temperature);
        }
    }).start();



    为什么需要 volatile？
    如果不加volatile，CPU 可能会把temperature的值缓存起来，即使硬件已经更新了寄存器的值，线程读到的还是缓存里的旧值。volatile强制每次读取都直接从内存（硬件寄存器）获取最新值。*/

}
