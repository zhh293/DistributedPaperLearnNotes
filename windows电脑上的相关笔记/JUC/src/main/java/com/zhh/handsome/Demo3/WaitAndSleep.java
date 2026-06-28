package com.zhh.handsome.Demo3;

public class WaitAndSleep {





    /*要理解wait()、notify()、notifyAll()的底层运行原理，必须先明确一个核心前提：这三个方法是Object类的 native 方法，且必须在synchronized同步块 / 同步方法中调用—— 因为它们的底层依赖于对象关联的「监视器锁（Monitor）」，只有持有锁的线程才能操作这些方法，否则会抛出IllegalMonitorStateException。
    一、底层核心载体：ObjectMonitor（监视器锁）
    在 HotSpot 虚拟机中，每个 Java 对象都会隐式关联一个「ObjectMonitor」结构（可理解为 “锁的实体”），它是实现synchronized和wait/notify的核心。ObjectMonitor包含以下关键字段（简化版），这些字段直接决定了wait/notify的运行逻辑：
    字段名	作用说明
    _owner	指向当前持有锁的线程（初始为null，线程获取锁时设为自身，释放时设为null）。
    _EntryList	等待获取锁的线程队列（线程竞争锁失败后，会进入此队列阻塞，等待_owner释放锁）。
    _WaitSet	调用wait()后进入等待状态的线程队列（线程在此队列中不竞争锁，需等待notify唤醒）。
    _recursions	锁的重入次数（支持synchronized重入，每次重入 + 1，退出同步块 - 1，为 0 时才释放锁）。
    _cxq	临时竞争队列（高并发时，线程先进入_cxq，再按需迁移到_EntryList，优化队列操作）。
    二、wait () 底层流程：从 “持有锁” 到 “等待唤醒”
    当持有锁的线程（记为「线程 A」）调用object.wait()时，底层会执行以下 6 个关键步骤，核心是 “释放锁 → 进入等待队列 → 阻塞线程 → 唤醒后重新竞争锁”：
    步骤 1：校验锁持有状态（前置检查）
    wait()首先会检查：当前线程是否是ObjectMonitor的_owner（即是否持有对象的锁）。
    若不是：直接抛出IllegalMonitorStateException（这就是为什么wait()必须在synchronized中调用）；搜嘎，太他妈清晰了，讲的太好了。。。。
    若是：继续执行后续逻辑。
    步骤 2：保存当前锁状态（应对重入）
    线程 A 可能是重入获取锁（比如嵌套调用synchronized），因此需要先保存ObjectMonitor的_recursions（重入次数）和_owner信息。
    目的：后续线程 A 被唤醒后，能恢复之前的锁重入状态，避免重入计数丢失。
    步骤 3：将线程封装为 ObjectWaiter，加入_WaitSet 队列，这个ObjectWaiter对象中包含重入次数，线程引用等信息
    线程 A 会被封装成一个「ObjectWaiter」节点（包含线程引用、等待状态等信息）；
    将该节点加入ObjectMonitor的_WaitSet队列（此时线程 A 不再竞争锁，仅等待被notify唤醒）；
    标记线程 A 的状态为 “等待中（WAITING）”。
    步骤 4：释放锁，允许其他线程竞争
    为了让其他线程有机会获取锁，wait()会主动释放当前持有的锁：
    将ObjectMonitor的_owner设为null（标记锁已释放）；
    若_EntryList或_cxq中有等待锁的线程（如线程 B），则唤醒其中一个线程（通常是队列头部线程），让它尝试获取锁（通过 CAS 将_owner设为自己）。
    关键：wait()是唯一会主动释放锁的方法（区别于sleep()，sleep()不会释放锁）。
    步骤 5：阻塞当前线程，等待唤醒
    释放锁后，线程 A 会调用操作系统的阻塞原语（如park()，通过Unsafe类的 native 方法实现），将自己从 “运行状态” 转为 “阻塞状态”，放弃 CPU 资源，不再参与调度。
    此时线程 A 的状态在 JVM 层面是WAITING，在操作系统层面是BLOCKED，只能等待两种唤醒方式：
            其他线程调用object.notify()或object.notifyAll()；
            其他线程调用thread.interrupt()（会触发InterruptedException，唤醒后退出等待）。
    步骤 6：被唤醒后，重新竞争锁
    当线程 A 被唤醒（比如其他线程调用notify()），会执行以下操作：
    从_WaitSet队列中移除自己的ObjectWaiter节点；
    重新加入_EntryList或_cxq队列，转为 “等待获取锁” 状态；
    尝试竞争锁：通过自旋或排队，等待_owner释放锁后，再通过 CAS 将_owner设为自己；
    若竞争成功：恢复之前保存的_recursions（重入次数），继续执行wait()之后的代码；
    若竞争失败：留在_EntryList中，继续阻塞等待下一次唤醒。
    三、notify () 底层流程：从 “唤醒等待线程” 到 “锁竞争”
    当持有锁的线程（记为「线程 B」）调用object.notify()时，底层会执行以下 4 个步骤，核心是 “从_WaitSet 唤醒线程 → 让其参与锁竞争”：
    步骤 1：校验锁持有状态（同 wait ()）
    首先检查当前线程是否是ObjectMonitor的_owner（是否持有锁）：
    若不是：抛IllegalMonitorStateException；
    若是：继续执行。
    步骤 2：从_WaitSet 唤醒一个线程
    notify()会从ObjectMonitor的_WaitSet队列中，选择一个线程（通常是队列头部的线程，即 “先进先出” 原则）唤醒：
    标记该线程的ObjectWaiter节点状态为 “唤醒”；
    将该节点从_WaitSet队列中移除。
    步骤 3：将唤醒的线程加入竞争队列
    被唤醒的线程（如之前的线程 A）不会直接获取锁，而是被转移到_EntryList或_cxq队列（等待获取锁的队列），转为 “就绪状态”，参与下一轮锁竞争。
    关键：notify()不会立即释放锁—— 锁仍由当前调用notify()的线程 B 持有，直到线程 B 退出synchronized同步块（执行完代码或抛异常），才会真正释放锁，此时被唤醒的线程 A 才能竞争锁。
    步骤 4：线程 B 继续执行
    唤醒操作完成后，线程 B 会继续执行synchronized块内的剩余代码，直到释放锁。
    四、notifyAll () 底层流程：唤醒所有等待线程
    notifyAll()与notify()的核心逻辑一致，唯一区别是：
    notify()只唤醒_WaitSet中的一个线程；
    notifyAll()会唤醒_WaitSet中的所有线程，将它们全部从_WaitSet转移到_EntryList或_cxq队列，让所有等待线程都有机会参与锁竞争。
    适用场景：
    当多个线程等待同一个条件（如生产者 - 消费者模型中，多个消费者等待 “有数据”），需要通知所有等待线程时，使用notifyAll()；若只需要通知一个线程，使用notify()（效率更高）。*/








}
