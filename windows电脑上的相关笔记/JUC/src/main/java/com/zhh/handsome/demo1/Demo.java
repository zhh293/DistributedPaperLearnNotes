package com.zhh.handsome.demo1;


import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

//@Slf4j(topic = "cp.topic")
public class Demo {
    public static void main(String[] args) throws ExecutionException, InterruptedException {

        /*Windows 系统
        任务管理器：
        右键点击任务栏 → 打开 “任务管理器”，在详细信息选项卡中，可查看每个进程的名称、PID（进程标识符）、线程数；选中进程后，点击 “结束任务” 可终止进程。
        tasklist 命令：
        在 ** 命令提示符（cmd）** 中执行，列出当前所有运行的进程（含进程名、PID、会话等信息）。若需过滤特定进程，可结合管道符，如 tasklist | findstr "notepad.exe"（查看记事本进程）。
        taskkill 命令：
        用于终止进程，格式示例：
        taskkill /PID 1234（通过 PID 终止进程）；
        taskkill /IM notepad.exe（通过进程名终止进程）；
        加 /F 参数（如 taskkill /F /PID 1234）可强制终止进程。
        Linux 系统
        Linux 中线程可视为 “轻量级进程”，以下命令用于进程 / 线程的查看与管理：

        ps -fe 命令：
        列出系统所有进程的完整信息，包括 UID（用户 ID）、PID、PPID（父进程 ID）、启动时间、执行命令等。
        ps -fT -p <PID> 命令：
        查看指定 PID 进程的所有线程，-T 选项表示显示线程信息（输出中每个线程会对应一行，含线程 ID 等）。
        kill 命令：
        向进程发送信号以终止它：
        默认发送 SIGTERM 信号（进程可捕获并自定义退出逻辑）；
        若需强制终止，使用 kill -9 <PID>（发送 SIGKILL 信号，进程无法捕获，直接终止）。
        top 命令：
        实时监控系统进程 / 线程状态：
        按大写 H，可切换 “是否显示线程”（显示时，每个线程会作为独立条目展示）；
        默认按 CPU 使用率排序，便于发现高负载线程。
        top -H -p <PID> 命令：
        聚焦查看指定 PID 进程的所有线程，-H 表示显示线程，-p 指定进程 PID，可清晰看到该进程内各线程的 CPU、内存占用等。
        Java 应用（跨平台，依赖 JDK 工具）
        Java 程序运行在 JVM 中，JDK 提供专用工具查看 Java 进程和线程：

        jps 命令：
        列出当前系统所有 Java 进程（显示进程 PID 和主类名 / JAR 包名），类似 Linux ps 但仅针对 Java 程序（需确保 JDK 环境变量配置正确）。
        jstack <PID> 命令：
        打印指定 Java 进程（PID）的线程堆栈信息，可用于分析线程状态（如死锁、阻塞、运行中），是排查 Java 线程问题（如卡顿、死锁）的核心工具。
        补充工具：
        jconsole：图形化工具，可查看 Java 进程的线程、内存、MBean 等状态；
        jvisualvm：更强大的可视化监控工具，集成 “线程 Dump”“内存分析”“性能剖析” 等功能，适合深度排查 Java 应用问题。*/


        /*FutureTask<String> stringFutureTask = new FutureTask<>(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return "我是你爸爸";
            }
        })*//*{
            @Override
            public void run() {
                super.run();
                System.out.println("hello world");
            }
        }*//*;//我这个是重写的人家固定的run方法，所以导致阻塞了。。。。。我是傻逼，而且这种在new 某个类(){},括号里面重写完方法之后，左边的返回值其实是这个类的子类对象，体现了多态的写法，所以也可以推知重写的方法必须是父类有的方法。
        Thread thread = new Thread(stringFutureTask);
        thread.setName("t1");
        thread.start();
        stringFutureTask.run();*/
        /*Thread thread = new Thread(stringFutureTask);
        thread.setName("t1");
        thread.start();*/
        //读过源码之后可以发现，执行run方法的时候会调用call方法，然后把结果赋给futureTask的value属性，之后get就可以返回对应的值了，但是如果value没有被赋值的话
        // 那么get方法就会一直阻塞，直到value被赋值。。。。。
      //  System.out.println(stringFutureTask.get());
        /*FutureTask<Integer> integerFutureTask = new FutureTask<>(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return 1;
            }
        }){
            @Override
            public void run() {
                System.out.println("hello world");
            }
        };
        Thread thread = new Thread(integerFutureTask);
        thread.setName("t1");
        thread.start();
        Integer i = integerFutureTask.get();
        System.out.println(i);*/


        Thread thread = new Thread() {
            @Override
            public void run() {
                System.out.println("hello world");
            }
        };
        thread.start();
        System.out.println(thread.getName());




       /* Runnable runnable = () ->
            System.out.println("hello world");

        Runnable runnable1 = new Runnable(){
            @Override
            public void run() {
                System.out.println("hello world");
            }
        };
        new Thread(runnable).start();
        new Thread(runnable1).start();
        Consumer<Object> helloWorld = new Consumer<>() {
            @Override
            public void accept(Object o) {
                System.out.println("hello world"+o.getClass());
            }
        };
        helloWorld.accept(null);*/

        /*Thread thread = new Thread("t1"){
            @Override
            public void run() {
                System.out.println("hello world");
            }
        };
        thread.start();
        System.out.println(thread.getName());*/
        /*Thread thread = new Thread(()->{
            System.out.println("hello world");
        },"t1");
        thread.setName("t1");
        thread.start();*/
    }
}
