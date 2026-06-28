package com.zhh.handsome.ParkUnpark;

public class 区别与联系 {

    /*一、synchronized的wait/notify：依赖 “对象的 monitor”
    synchronized的等待 / 唤醒逻辑完全绑定在某个具体对象上（即synchronized(obj)中的obj），这个对象背后隐藏着一个叫做monitor（管程）的结构，它是wait/notify的 “核心载体”。
    可以把monitor想象成一个 “带门卫的会议室”：
    会议室（对象）：被synchronized保护的资源，只有拿到 “入场券（锁）” 的线程才能进入操作。
    门卫（monitor）：负责管理线程的进入、等待和唤醒，内部有两个关键 “队列”：
    EntryList（入口队列）：想进入会议室但没拿到锁的线程，都在这里排队等待。
    WaitSet（等待队列）：拿到锁后调用wait()的线程，会释放锁并进入这里等待 “唤醒信号”。
    当调用wait()时：
    线程会释放手中的 “入场券（锁）”，从会议室里出来，进入WaitSet排队。
    此时其他线程可以从EntryList竞争锁，进入会议室。
    当调用notify()时：
    门卫会从WaitSet里随机选一个线程，把它移到EntryList（重新排队竞争锁）。
    被选中的线程只有重新竞争到锁，才能继续执行（所以notify后线程不会立即运行，还要等锁）。
    核心特点：
    必须绑定某个对象（synchronized(obj)），wait/notify必须在synchronized块里调用（否则抛异常）。
    唤醒是 “随机” 的（notify）或 “批量” 的（notifyAll），无法精准唤醒某个线程。
    信号（唤醒通知）是 “一次性” 的：如果notify在wait之前调用，信号会直接丢失（线程永远等不到）。
    二、park/unpark：依赖 “线程自身的控制块”
    park/unpark不依赖任何对象，它的核心载体是线程自身的 “控制块”（JVM 为每个线程维护的内部数据结构，类似线程的 “个人档案”）。这个控制块里有一个permit（许可）变量，专门用来控制线程的暂停和唤醒。
    可以把这个控制块想象成线程的 “个人口袋”：
    口袋里的卡片（permit）：要么有（1），要么没有（0），最多 1 张。
    park()：线程摸口袋，有卡就扔掉继续走；没卡就站在原地不动（阻塞）。
    unpark(t)：给线程t的口袋塞一张卡（如果没有的话）；如果t正在原地不动，就喊它 “有卡了，快走”。
    核心特点：
    不依赖任何对象，无需在同步块中调用，灵活度高。
    唤醒是 “精准” 的：unpark(t)可以直接指定唤醒某个线程。
    信号（卡片）是 “可储备” 的：unpark在park之前调用，卡片会存在口袋里，等park时直接用（不会丢失）。*/

}
