package com.zhh.handsome.happenBefore;

public class Demo1 {
    //导言
    /*要理解 happens-before 规则，核心是先搞懂它的「本质目的」—— 它是 Java 给程序员的「多线程内存可见性 / 有序性保障协议」：只要两个操作满足 happens-before 关系，就能保证：前一个操作的结果，后一个操作一定能看到；且前一个操作的执行顺序（逻辑上）在後一个之前。
    它解决的是「底层优化导致的多线程混乱」—— 比如 JVM 会做「指令重排」（为了效率打乱代码执行顺序）、CPU 会有「缓存」（数据不立即刷到主内存），这些优化在单线程没问题，但多线程会导致「线程 A 改了数据，线程 B 看不到」「代码执行顺序和写的不一样」。
    happens-before 规则的价值在于：你不用关心底层的指令重排、缓存刷新，只要根据规则判断两个操作的关系，就能确定是否线程安全。*/





    /*一、先搞懂 3 个基础概念（不懂这些，规则就白学）
    在讲规则前，必须先明确 3 个底层背景，否则会越听越懵：
    指令重排：JVM 会在不改变单线程执行结果的前提下，打乱代码的实际执行顺序。比如你写的 a=1; b=2，实际可能执行 b=2; a=1（单线程没问题，但多线程可能出问题）。
    CPU 缓存：线程读写数据时，不会直接操作主内存，而是先读入自己的 CPU 缓存（类似「个人笔记本」），修改后再刷回主内存（类似「公共白板」）。如果线程 B 没从主内存刷新数据，就看不到线程 A 改的结果。
    可见性 vs 有序性：
    可见性：线程 A 改了变量 x，线程 B 之后读 x，能不能看到 A 改的值？
    有序性：代码的实际执行顺序，和你写的顺序是否一致？
    happens-before 同时保障这两点。*/




    /*如果操作 A happens-before 操作 B，那么：
    从「可见性」来看：A 操作对共享变量的所有修改，B 操作一定能看到（A 的修改会被强制刷到主内存，B 会从主内存读最新值）；
    从「有序性」来看：A 操作的执行顺序（逻辑上）在 B 之前（JVM 不会把 B 操作重排到 A 之前）。
            ⚠️ 关键误区：happens-before 不是「A 必须在 B 之前执行」，而是「如果 A 先执行了，B 必须看到 A 的结果；且 JVM 不能把 B 重排到 A 前面」。
    单线程下所有操作天然满足 happens-before（所以单线程不会有可见性 / 有序性问题）。*/



    /*规则 1：程序顺序规则（单线程天然有序可见）
    通俗定义：同一个线程内，按代码书写顺序，前面的操作 happens-before 后面的操作。
    例子：
    java
            运行
    public void singleThread() {
        int a = 1; // 操作 A
        int b = a + 1; // 操作 B
        System.out.println(b); // 输出 2，绝不会是其他值
    }
    解决问题：单线程内的可见性和有序性。JVM 不会把「操作 B」重排到「操作 A」前面，且 B 一定能看到 A 给 a 赋的值。
    因为a，b要么都从cpu缓存中读取，要么都从主内存中读取。单个线程中不管怎么修改怎么读，结果都一样。
    */



    /*规则 2：监视器锁规则（synchronized 的核心保障）
    通俗定义：对同一个锁的「解锁操作」happens-before 后续对同一个锁的「加锁操作」。
    关键：必须是「同一个锁」！不同锁无效。
    例子（呼应你之前的同步块疑问）：
    java
            运行
    private final Object lock = new Object();
    private int x = 0;

    // 线程 A 执行
    public void write() {
        synchronized (lock) { // 加锁
            x = 1; // 操作 A（解锁前的写操作）
        } // 操作 B（解锁）
    }

    // 线程 B 执行
    public void read() {
        synchronized (lock) { // 操作 C（加锁，happens-before 于 B）
            System.out.println(x); // 操作 D，一定输出 1
        }
    }


    执行流程与保障：
线程 A 加锁 → 改 x=1 → 解锁（操作 B）；
线程 B 申请同一个锁 → 加锁成功（操作 C）；
因为 B happens-before C，所以 A 对 x 的修改（操作 A），线程 B 的操作 D 一定能看到；
解决问题：synchronized 的可见性和互斥性（互斥性是锁本身的特性，可见性是 happens-before 保障的）。
这就是为什么你之前问的「全量锁同步块」不会有可见性问题 —— 解锁操作 happens-before 后续加锁操作，后面的线程一定能看到前面的修改。
    */





    /*规则 3：volatile 变量规则（volatile 的核心保障）
    通俗定义：对一个 volatile 变量的「写操作」happens-before 后续对同一个 volatile 变量的「读操作」。
    额外福利：volatile 还会「禁止指令重排」—— 写操作之前的代码不能重排到写操作之后，读操作之后的代码不能重排到读操作之前。
    例子（呼应 ConcurrentHashMap 的 table 数组）：
    java
            运行
    // 类似 ConcurrentHashMap 的 table 数组
    private volatile int flag = 0;
    private int x = 0;

    // 线程 A 执行（写 volatile 变量）
    public void write() {
        x = 1; // 操作 A（普通变量写）
        flag = 1; // 操作 B（volatile 变量写）
    }

    // 线程 B 执行（读 volatile 变量）
    public void read() {
        if (flag == 1) { // 操作 C（volatile 变量读，happens-before 于 B）
            System.out.println(x); // 操作 D，一定输出 1
        }
    }
    核心保障：
    操作 B（写 flag=1）happens-before 操作 C（读 flag=1）；
    由「程序顺序规则」，操作 A happens-before 操作 B，操作 C happens-before 操作 D；
    由「传递性规则」（后面会讲），操作 A happens-before 操作 D → 线程 B 一定能看到 x=1；
    解决问题：volatile 变量的可见性 + 有序性。
    这就是为什么 ConcurrentHashMap 的 table 数组要加 volatile—— 写数组（扩容、插入节点）happens-before 读数组（获取节点），保证其他线程能看到最新的数组状态。
*/









   /* 规则 4：线程启动规则（Thread.start ()）
    通俗定义：主线程调用 thread.start() 操作 happens-before 子线程内的所有操作。
    例子：
    java
            运行
    public class StartDemo {
        private int x = 0;

        public static void main(String[] args) {
            StartDemo demo = new StartDemo();
            demo.x = 1; // 操作 A（主线程写）
            Thread thread = new Thread(() -> {
                System.out.println(demo.x); // 操作 B（子线程读，一定输出 1）
            });
            thread.start(); // 操作 C（启动子线程）
        }
    }
    保障：操作 C happens-before 操作 B，且操作 A happens-before 操作 C（程序顺序规则）→ 操作 A happens-before 操作 B → 子线程一定能看到主线程启动前的修改。
    反例：如果子线程在 start() 前就启动（比如直接调用 run() 方法，不是 start()），则不满足这个规则，子线程可能看不到 x=1。*/



    /*规则 5：线程终止规则（Thread.join ()）
    通俗定义：子线程内的所有操作 happens-before 主线程调用 thread.join() 成功返回后的所有操作。
    例子：
    java
            运行
    public class JoinDemo {
        private int x = 0;

        public static void main(String[] args) throws InterruptedException {
            JoinDemo demo = new JoinDemo();
            Thread thread = new Thread(() -> {
                demo.x = 1; // 操作 A（子线程写）
            });
            thread.start();
            thread.join(); // 操作 B（主线程等待子线程终止）
            System.out.println(demo.x); // 操作 C（主线程读，一定输出 1）
        }
    }
    保障：操作 A happens-before 操作 B，操作 B happens-before 操作 C → 主线程一定能看到子线程终止前的所有修改。
    反例：如果主线程不调用 join()，直接读 x，可能输出 0（子线程还没执行完，或修改没被看到）。*/







    /*规则 6：传递性规则（核心推导规则）
    通俗定义：如果 A happens-before B，且 B happens-before C，那么 A happens-before C。
    这是最常用的「推导规则」—— 很多复杂场景的可见性，都是通过传递性推导出来的。
    例子（结合规则 2 和规则 6）：
    java
            运行
    private final Object lock = new Object();
    private int x = 0;
    private int y = 0;

    // 线程 A 执行
    public void writeX() {
        synchronized (lock) {
            x = 1; // 操作 A
        } // 操作 B（解锁）
    }

    // 线程 B 执行
    public void readXWriteY() {
        synchronized (lock) { // 操作 C（加锁，B happens-before C）
            y = x; // 操作 D（读 x=1，写 y=1）
        } // 操作 E（解锁）
    }

    // 线程 C 执行
    public void readY() {
        synchronized (lock) { // 操作 F（加锁，E happens-before F）
            System.out.println(y); // 操作 G，一定输出 1
        }
    }
    推导过程：
    规则 2：A happens-before B，B happens-before C → 传递性 → A happens-before C；
    程序顺序规则：C happens-before D，D happens-before E → 传递性 → A happens-before E；
    规则 2：E happens-before F，F happens-before G → 传递性 → A happens-before G；
    结论：线程 C 一定能看到线程 A 写的 x=1，以及线程 B 写的 y=1。*/

}






















