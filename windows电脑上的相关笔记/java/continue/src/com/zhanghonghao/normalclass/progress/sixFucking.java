package com.zhanghonghao.normalclass.progress;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class sixFucking {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        /*progress progress = new progress();
        progress progress1 = new progress();
        progress1.setName("显诚意");
        progress.setName("县城二");*/
        //开启线程
        //progress.start();
        //progress1.start();
        /*progress1 progress2 = new progress1();
        progress1 progress3 = new progress1();
        Thread thread = new Thread(progress2);
        Thread thread2 = new Thread(progress3);
        thread.setName("显诚意");
        thread2.setName("县城二");
        thread.start();
        thread2.start();*/
        /*MyCall myCall = new MyCall();
        FutureTask<String> futureTask = new FutureTask<>(myCall);
        Thread thread = new Thread(futureTask);
        thread.start();
        String s = futureTask.get();
        System.out.println(s);*/
        //继承thread，runnable类，编程比较简单，但无法获取返回的结果
        //继承callable，可以线程获取返回的值，但编程较为复杂
        //常见成员方法
        /*String getName()	返回此线程的名称
void setName(String name)	设置线程的名字（构造方法也可以设置名字）
static Thread currentThread()	获取当前线程的对象
static void sleep(long time)	让线程休眠指定的时间，单位为毫秒
setPriority(int newPriority)	设置线程的优先级
final int getPriority()	获取线程的优先级
final void setDaemon(boolean on)	设置为守护线程
public static void yield()	出让线程 / 礼让线程
public static void join()	插入线程 / 插队线程*/
        /*progress progress = new progress();
        progress1 progress1 = new progress1();
        Thread thread1 = new Thread(progress1);
        thread1.setName("thread1");
        progress.setName("progress1");
        System.out.println(Thread.currentThread().getName());
        thread1.start();
        Thread.sleep(4000);
        progress.start();*/
   //子类是无法继承父类的构造方法的，所以如果想在创建对象时直接命名，需要在子类中自己写有参构造
        /*细节:
1、如果我们没有给线程设置名字，线程也是有默认的名字的
格式:Thread-X(X序号，从0开始的)
2、如果我们要给线程设置名字，可以用set方法进行设置，也可以构造方法设置
*/
        //currentThread，获取当前线程的对象，并且可以调用所含有的方法
        /*细节:
当JVM虚拟机启动之后，会自动的启动多条线程
其中有一条线程就叫做main线程
他的作用就是去调用main方法，并执行里面的代码
在以前，我们写的所有的代码，其实都是运行在main线程当中
*/
        /*progress1 progress1 = new progress1();
        Thread thread1 = new Thread(progress1);
        Thread thread2 = new Thread(progress1);
        System.out.println(thread1.getPriority());
        System.out.println(thread2.getPriority());
        System.out.println(Thread.currentThread().getPriority());*/
        /*progress2 progress = new progress2();
        progress progress1 = new progress();
        progress1.setName("备胎");
        progress.setName("女神");
        progress1.setDaemon(true);
        progress1.start();
        progress.start();*/
        /*final void setDaemon(boolean on)
细节:
设置为守护线程
当其他的非守护线程执行完毕之后，守护线程会陆续结束
通俗易懂:
当女神线程结束了，那么备胎也没有存在的必要了
使用场景：守护线程常用于后台任务，像垃圾回收器、日志记录等。
这些任务通常是为其他线程提供服务的，当所有非守护线程结束时，它们也就没有继续运行的必要了。
*/
        /*progress2 progress2 = new progress2();
        com.zhanghonghao.normalclass.progress.progress2 progress3 = new progress2();
        progress3.setName("progress3");
        progress2.setName("progress2");
        progress3.start();
        progress2.start();//出让方法，ok*/
        //插入线程
        /*progress2 progress2 = new progress2();
        progress2.setName("progress2");
        progress2.start();
        //progress2.join();
        for (int i=0; i<100; i++) {
            System.out.println(Thread.currentThread().getName() + ": " + i);
        }*/
        //线程的生命周期
        //创建线程对象-》start-》就绪(有执行资格，没有执行权),不停的抢CPU->抢到执行权->有执行资格，有执行权->运行代码->run()执行完毕->线程死亡，变成垃圾
        //但执行过程中可能会被其他线程抢走CPU执行权
        //同时，在代码运行过程中，可能会遇到sleep等阻塞方法，这时这个线程就只能干等着，连抢占CPU的资格都没有，等阻塞时间到了之后，又会回到就绪状态
        //在线程类中的变量如果被static修饰，这个变量被所有线程对象共享，但是这种情况仍然不能避免一个问题，那就是当其中一个线程执行类中的方法代码时，并不能执行完全，CPU的操作权就被其他线程抢走了，
        // 从而可能影响共享数据的值，进而影响之前线程后续的运行结果
        //这时，我们就需要一个黑科技了，那就是同步代码块，synchronized(锁对象){操作共享数据的代码}
        /*synchronized1 synchronized1 = new synchronized1();
        synchronized1 synchronized2 = new synchronized1();
        synchronized1 synchronized3 = new synchronized1();
        Thread t1 = new Thread(synchronized1);
        Thread t2 = new Thread(synchronized2);
        Thread t3 = new Thread(synchronized3);
        synchronized1.setName("synchronized1");
        synchronized2.setName("synchronized2");
        synchronized3.setName("synchronized3");
        t1.start();
        t2.start();
        t3.start();*/

        /*Lock1 lock1 = new Lock1();
        Thread t1 = new Thread(lock1);
        Thread t2 = new Thread(lock1);
        t1.setName("t1");
        t2.setName("t2");
        t1.start();
        t2.start();*/
        //死锁，锁发生了嵌套
       /*@Override
public void run(){
//1.循环
while(true){
if("线程A".equals(getName())){
synchronized(objA){
System.out.println("线程A系到了A锁，准备拿B锁");
synchronized(objB){
System.out.println("线程A拿到了B锁，顺利执行完一轮");
}else if("线程B".equals(getName())){
if("线程B".equals(getName())){
synchronized(objB){
System.out.println("线程B拿到了B锁，准备拿A锁");
synchronized(objA){
System.out.println("线程B拿到了A锁，顺利执行完一轮");
*/
        //A想拿B锁的时候，突然发现CPU执行权被拿走了，B抢先一步拿走了B锁，结果两个线程各持一把锁，谁也没办法继续走下去，就卡在这里了
        //以后千万不要把两个锁嵌套起来
        //生产者消费者模式是一个十分经典的多线程协作的模式，也叫做等待唤醒机制
        //生产者 生产数据   消费者  消费数据
        //两个问题(1)消费者等待，比如消费者先抢到执行权，那没有数据处理只能进行等待，这时生产者会抢走CPU执行权开始生产数据
        //(2)生产者等待，比如生产者先抢到执行权，生产完数据后叫醒消费者，想让消费者抢到CPU执行权，结果消费者没抢到，又回到了生产者手里，但这时上一次的数据没有处理完，生产者只能乖乖等待,等消费者抢到执行权之后
        //会把数据处理掉，然后唤醒生产者让他继续做
        //wait当前线程等待，直到被唤醒,notify随即唤醒一个线程,notifyAll唤醒所有沉睡的线程
        Cook cook = new Cook();
        diners diners = new diners();
        cook.setName("初识");
        diners.setName("时刻");
        cook.start();
        diners.start();
    }
}
/*
当一个线程执行含有 sleep 方法的代码时，在它睡眠期间，执行权会被夺走。具体来说：

sleep 方法会使当前线程进入阻塞状态，暂时让出 CPU 执行权，不再参与 CPU 调度。此时，系统会将 CPU 资源分配给其他就绪状态的线程，让其他线程有机会执行。
直到睡眠时长结束，该线程才会重新进入就绪状态，等待再次获取 CPU 执行权以继续运行。

需要注意的是，线程在 sleep 期间 不会释放持有的锁（如同步锁），但这与执行权是否被夺走是两个层面的问题。执行权的让渡仅涉及 CPU 资源的分配，不影响锁的持有状态。例如，若多个线程竞争同一把锁，
一个持有锁的线程调用 sleep，其他线程仍无法获取该锁，但 CPU 会去执行其他可运行的线程（如果有）。*/

/*在程序里，死锁是指 多个线程互相等待对方释放资源，导致所有线程都卡住不动。比如：

线程 A 占用了资源 1，想获取资源 2；
线程 B 占用了资源 2，想获取资源 1；
双方都不释放自己的资源，也得不到对方的资源，最终一起 “卡死”。
        2. 死锁发生的四个条件（必须同时满足）
互斥条件：资源一次只能被一个线程占用（比如筷子只能被一个人拿）。
请求与保持条件：线程拿到资源后，不释放已有的资源，继续请求新资源（比如你拿着左筷子，还想拿右筷子）。
不可剥夺条件：不能强行拿走别人的资源（不能抢朋友的筷子）。
循环等待条件：多个线程形成一个环，互相等待对方的资源（你等朋友的右筷子，朋友等你的左筷子，形成环）。
        3. 死锁的解决方案
核心思路：破坏死锁的四个条件之一。
方法 1：避免 “循环等待”—— 按顺序获取资源
比如规定：所有人必须先拿左筷子，再拿右筷子。

如果你和朋友都先拿左筷子，拿到左筷子的人（比如你）可以继续拿右筷子；
没拿到左筷子的人（朋友）会等待，不会出现 “你拿左、他拿右” 的交叉情况，死锁就不会发生。

程序中：给资源编号（如资源 1、资源 2），要求所有线程必须按编号从小到大获取资源，不允许逆向获取。
方法 2：破坏 “请求与保持”—— 一次性拿完所有资源
比如吃饭前，必须同时拿到左右两根筷子才能开始吃，否则就一根都不拿，等着。

如果你拿不到右筷子，就主动放下左筷子，避免 “占着左筷子不放”。

程序中：线程获取资源时，要么一次性拿到所有需要的资源，要么一个都不拿（释放已有的资源，重新等待）。
方法 3：设置 “超时时间”—— 不无限等待
比如等朋友放筷子超过 10 秒，就主动放下自己的筷子，下次再尝试。
程序中：使用 tryLock() 等带超时的方法获取锁，超时后主动释放已有的资源，避免无限等待。

方法 4：检测并 “强行打破” 死锁（最后手段）
系统定期检查是否有死锁（比如哪些线程在互相等待），如果有：

强制剥夺某个线程的资源（比如 “抢走” 一个线程的锁）；
终止某个线程（比如 “让其中一个线程放弃，重新开始”）。*/
