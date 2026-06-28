package ThreadLocal;

public class  ThreadLocalDemo1<T> {
    /*private  final ThreadLocal<T> t = new ThreadLocal<>();
    public void setT(T t1)
    {
        t.set(t1);
    }
    public T getT(){
        return t.get();
    }
    public void clear()
    {
        t.remove();
    }*/
}
/*
ThreadLocal 如何实现共享变量的线程隔离？
要理解 ThreadLocal 如何解决共享变量的线程安全问题，需要先明确它的核心设计思想：为每个线程提供独立的变量副本，而不是通过 "创建独立对象" 的方式实现隔离。
        1. ThreadLocal 的核心原理
ThreadLocal 不是一种 "线程安全的变量"，而是一种线程本地存储机制，它的工作原理可以概括为：

每个线程内部都有一个 ThreadLocalMap 类型的成员变量
ThreadLocal 通过当前线程作为键，将变量值存储在该线程的 ThreadLocalMap 中
不同线程访问同一个 ThreadLocal 时，获取的是各自 ThreadLocalMap 中的值，彼此隔离*/


/*
ThreadLocal 与 "独立对象" 的本质区别
方案	实现方式	核心优势	适用场景
独立对象实例	每个线程创建独立的对象	实现简单，无额外内存开销	对象状态简单，创建成本低的场景
ThreadLocal	共享对象，但变量存储在各线程中	无需频繁创建对象，适合跨方法传递数据	共享对象需要维护线程隔离状态的场景
4. ThreadLocal 的典型应用场景
数据库连接 / 事务管理
每个线程需要独立的数据库连接，避免连接混用：
java
private static final ThreadLocal<Connection> CONNECTION_THREAD_LOCAL = new ThreadLocal<>();


用户会话信息传递
在分布式系统中，将用户会话信息（如登录令牌）存储在 ThreadLocal 中，方便跨方法访问：
java
public class UserContext {
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    // 提供静态方法操作 ThreadLocal
    public static void setUserId(String userId) { USER_ID.set(userId); }
    public static String getUserId() { return USER_ID.get(); }
}*/


/*
存泄漏风险
ThreadLocalMap 使用弱引用存储键（ThreadLocal 对象），但值（Object）是强引用。如果线程被长期持有（如线程池），需要手动调用 remove() 清除数据，避免旧数据占用内存。
线程池场景的特殊处理
在线程池场景中，线程会被重复使用，必须在任务结束后调用 remove()，否则上一个任务的 ThreadLocal 数据会残留到下一个任务：
java
executor.submit(() -> {
        try {
        processor.processRequest(request);
    } finally {
            processor.userId.remove(); // 重要！
    }
            });
*/


/*

为什么 ThreadLocal 能解决共享变量的线程安全问题？
传统共享变量的问题
多个线程直接访问共享变量（如类的成员变量、静态变量）时，需要通过锁（synchronized、Lock）保证原子性，否则会出现数据竞争（如前文提到的userId残留问题）。
ThreadLocal 的 “空间换时间” 策略
不使用锁来 “互斥访问” 共享变量，而是让每个线程拥有独立的变量副本，从根本上避免多个线程同时操作同一变量：
读操作：仅访问当前线程的副本，无需加锁
写操作：仅修改当前线程的副本，不影响其他线程
典型场景：避免线程复用时的状态残留（如 Spring 的用户上下文、事务连接）

*/



//重点，为什么有时候需要放入成员变量

/*一般没必要把成员变量放进去吧，毕竟这些成员变量的访问需要new对象，每个用户new出来的对象都是独立的，跟静态变量差得远了
一、Spring Boot 的请求处理线程模型
        每个请求分配独立线程
Spring Boot 默认使用 Tomcat 作为 Web 容器，Tomcat 通过线程池为每个 HTTP 请求分配一个独立的线程（来自线程池）。例如，当请求 A 到达时，线程池中的线程 T1 处理它；请求 B 到达时，可能由线程 T2 处理，线程 T1 处理完请求 A 后会回到线程池等待下一个请求。
组件的作用域
Spring Boot 中，@Service、@Controller等组件默认是单例作用域（singleton），即整个应用中只有一个实例。这意味着：
若单例组件中包含成员变量，多个请求线程会共享该变量，可能引发线程安全问题。
二、成员变量的线程安全问题
1. 单例组件中的成员变量（危险场景）
若单例组件包含成员变量，例如：

java
@Service
public class UserService {
    private String userId; // 成员变量，被所有请求线程共享

    public void processRequest(HttpServletRequest request) {
        this.userId = request.getRemoteUser();
        // 处理逻辑...
    }
}


当线程 T1 处理请求 A 时，设置userId为 A 的 ID；处理完请求 A 后，线程 T1 回到线程池。若线程 T1 再次处理请求 B，此时userId仍为 A 的 ID（未清理），会导致请求 B 使用错误的用户数据。
原因：单例组件的成员变量被多个线程共享，线程复用时数据残留。*/








/*
2. 避免参数传递的复杂性
当多个方法需要使用同一个对象时，要是不借助ThreadLocal，就需要通过方法参数层层传递该对象，这会让代码变得复杂。而ThreadLocal可以让数据在同一个线程内的任何地方都能被访问到，无需显式传递参数。

对比示例：

不使用 ThreadLocal：
java
void processRequest(HttpServletRequest req) {
    String userId = req.getRemoteUser();
    step1(userId);
}

void step1(String userId) {
    step2(userId);
}

void step2(String userId) {
    // 使用userId...
}

使用 ThreadLocal：
java
void processRequest(HttpServletRequest req) {
    RequestContext.getCurrent().setUserId(req.getRemoteUser());
    step1(); // 无需传递userId
}

void step1() {
    step2();
}

void step2() {
    String userId = RequestContext.getCurrent().getUserId();
    // 使用userId...
}

对于对各拦截器之间需要共同变量做多次校验的场景，使用 ThreadLocal可以避免参数传递的复杂性。噢噢噢噢哦哦哦，牛逼六六六
*/
