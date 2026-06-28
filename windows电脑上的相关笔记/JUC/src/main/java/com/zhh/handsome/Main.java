package com.zhh.handsome;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    public static final BlockingQueue<String> blockingQueue = new LinkedBlockingQueue<>(5);
    public static final BlockingQueue<String> blockingQueueA = new LinkedBlockingQueue<>(10);
    public static final BlockingQueue<String> blockingQueueB = new LinkedBlockingQueue<>(10);

    public static void main(String[] args) {

        // A线程生成货件
        new Thread() {
            @Override
            public void run() {
                while (true) {
                    boolean offer = blockingQueueA.offer("A");
                    if (!offer) {
                        try {
                            Thread.sleep(100);
                            continue;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("生产了一个A");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

            }
        }.start();

        new Thread() {
            @Override
            public void run() {
                while (true) {
                    boolean offer = blockingQueueB.offer("B");
                    if (!offer) {
                        try {
                            Thread.sleep(100);
                            continue;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.println("生产了一个B");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }

                }
            }
        }.start();

        new Thread() {
            @Override
            public void run() {
                String resultA = null;
                String resultB = null;
                try {
                    resultA = blockingQueueA.take();
                } catch (InterruptedException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
                try {
                    resultB = blockingQueueB.take();
                } catch (InterruptedException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
                System.out.println("获取到了两个数据");
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }

                System.out.println("resultA=" + resultA);
                System.out.println("resultB=" + resultB);
            }
        }.start();

        // 理发师睡觉问题，so easy

        for (int i = 0; i < 3; i++) {

            // 理发师开始理发
            new Thread() {
                @Override
                public void run() {
                    while (true) {
                        String take = null;
                        try {
                            take = blockingQueue.take();
                            System.out.println("开始理发");
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        System.out.println("理发完毕，对象是" + take);
                    }
                }
            }.start();

        }

        new Thread() {
            @Override
            public void run() {
                while (true) {
                    boolean offer = blockingQueue.offer("1");
                    if (!offer) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        continue;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    System.out.println("进来一个理发的");
                }
            }
        }.start();

        AtomicInteger atomicInteger = new AtomicInteger(0);
        atomicInteger.incrementAndGet();
        /*
         * 要理解进程和线程的区别与联系，我们可以从「定义」「联系」「核心区别」三个维度逐步展开，结合生活类比会更易理解。
         * 一、基本定义
         * 1. 进程（Process）
         * 进程是操作系统进行资源分配的基本单位，可以理解为一个「正在运行的程序」。
         * 比如你打开的浏览器、微信、代码编辑器，每个都是一个独立的进程。
         * 每个进程都有自己独立的资源：包括内存空间（代码、数据、堆栈）、文件描述符（打开的文件、网络连接等）、系统状态（如优先级、运行状态）等。
         * 2. 线程（Thread）
         * 线程是操作系统进行调度（CPU 分配时间）的基本单位，也被称为「轻量级进程」。
         * 线程存在于进程内部，一个进程可以包含多个线程（比如浏览器进程中，可能有「渲染页面的线程」「处理用户输入的线程」「下载文件的线程」等）。
         * 线程本身不拥有独立资源，而是共享所属进程的全部资源（如内存、文件描述符），但每个线程有自己的「执行栈」（存储函数调用和局部变量）和「程序计数器」（
         * 记录当前执行的指令位置）。
         * 二、两者的联系
         * 线程依赖进程存在：线程不能独立运行，必须属于某个进程。一个进程至少有一个线程（称为「主线程」），没有进程就没有线程。
         * 资源共享关系：同一进程内的所有线程共享该进程的资源（内存、文件、网络连接等），这是线程高效通信的基础。
         * 协同完成任务：进程的任务通常由其内部的多个线程协同完成。例如，视频播放器进程中，一个线程负责读取文件，一个线程负责解码，一个线程负责渲染画面，
         * 三者配合实现播放功能。
         * 
         * 并发相当于1个人吃3个馒头,而并行相当于三个人吃3个馒头
         * 
         * 比如你同时开着 “浏览器（刷网页）、微信（收消息）、音乐（放歌）” 这 3 个任务：
         * 
         * 如果你的电脑是「单核心 CPU」：CPU 会像 “1 个人交替吃 3 个馒头” 一样，快速在 3 个任务间切换（给浏览器分配 10ms，再给微信
         * 10ms，再给音乐 10ms…）—— 因为切换速度极快（毫秒级），你感觉不到卡顿，这就是并发。
         * 如果你的电脑是「4 核心 CPU」：其中 3 个核心可以分别 “负责浏览器、微信、音乐”，3 个任务同时执行 —— 这就是并行。
         * 
         * 
         * 
         * 
         * 2) 结论
         * 
         * 单核 cpu 下，多线程不能实际提高程序运行效率，只是为了能够在不同的任务之间切换，不同线程轮流使用 cpu，不至于一个线程总占用
         * cpu，别的线程没法干活
         * 多核 cpu 可以并行跑多个线程，但能否提高程序运行效率还是要分情况的
         * 有些任务，经过精心设计，将任务拆分，并行执行，当然可以提高程序的运行效率。但不是所有计算任务都能拆分（参考后文的【阿姆达尔定律】）
         * 也不是所有任务都需要拆分，任务的目的如果不同，谈拆分和效率没啥意义
         * IO 操作不占用 cpu，只是我们一般拷贝文件使用的是【阻塞 IO】，这时相当于线程虽然不用 cpu，但需要一直等待 IO
         * 结束，没能充分利用线程。所以才有后面的【非阻塞 IO】和【异步 IO】优化
         * 
         * 
         * 
         * 
         */

    }
}