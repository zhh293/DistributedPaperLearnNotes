# 线程栈帧独立性与非 static 共享变量访问原理

## 一、文档引言

在 Java 多线程编程中，常存在两个关键疑问：



1. 每个线程的栈帧为何完全独立？

2. 非 static 修饰的共享变量（如类成员变量）存储在哪里？为何独立栈帧能访问同一变量？

本文结合 JVM 内存模型（Java 虚拟机栈、堆）的特性，从 “栈帧隔离机制”“变量存储规则”“共享访问原理” 三部分拆解，并用代码案例验证逻辑，帮助理解多线程下数据共享的底层本质。

## 二、第一部分：线程栈帧的绝对独立性 —— 源于 “Java 虚拟机栈的线程私有性”

### 2.1 核心结论

**每个线程的栈帧完全独立，包括 main 线程启动的子线程**—— 原因是 JVM 为每个线程分配了 “专属的 Java 虚拟机栈”（线程私有区域），栈帧仅存在于所属线程的虚拟机栈中，其他线程无法访问。

### 2.2 底层原理（结合 JVM 内存模型）

根据 JVM 规范，“Java 虚拟机栈” 是线程私有内存区域（对应《JVM 进程核心构成》文档 2.2.1 章节），具备以下特性：



* **线程启动即分配**：线程创建时，JVM 自动为其分配一块独立的虚拟机栈内存，与其他线程的虚拟机栈物理地址隔离；

* **栈帧随方法调用动态生成**：线程执行方法时，会在自己的虚拟机栈中创建 “栈帧”（包含局部变量表、操作数栈、方法返回地址等），方法执行完毕后栈帧自动销毁；

* **数据不可跨线程访问**：线程 A 的虚拟机栈中存储的栈帧（及局部变量），线程 B 无法读取或修改，因为两者的虚拟机栈属于独立内存空间。

### 2.3 代码案例：栈帧独立性的直观体现



```
public class ThreadStackIsolationDemo {

&#x20;   public static void main(String\[] args) {

&#x20;       // 1. main线程的栈帧：存放在main线程的虚拟机栈中

&#x20;       int mainLocalVar = 10; // 局部变量，仅main线程可见

&#x20;      &#x20;

&#x20;       // 2. 启动子线程，子线程有独立的虚拟机栈

&#x20;       new Thread(() -> {

&#x20;           // 子线程的栈帧：存放在子线程的虚拟机栈中

&#x20;           int threadLocalVar = 20; // 局部变量，仅子线程可见

&#x20;          &#x20;

&#x20;           // 无法访问main线程的局部变量（编译报错：cannot find symbol）

&#x20;           // System.out.println(mainLocalVar);&#x20;

&#x20;          &#x20;

&#x20;           System.out.println("子线程局部变量：" + threadLocalVar); // 输出20

&#x20;       }).start();

&#x20;      &#x20;

&#x20;       // main线程无法访问子线程的局部变量（编译报错：cannot find symbol）

&#x20;       // System.out.println(threadLocalVar);&#x20;

&#x20;      &#x20;

&#x20;       System.out.println("main线程局部变量：" + mainLocalVar); // 输出10

&#x20;   }

}
```

**案例结论**：



* `mainLocalVar` 和 `threadLocalVar` 是线程专属的局部变量，存放在各自线程的栈帧中；

* 跨线程访问局部变量会直接编译报错，证明栈帧的独立性。

## 三、第二部分：非 static 共享变量的存储位置 —— 堆内存（进程共享区域）

### 3.1 关键区分：变量类型决定存储位置

Java 中的变量按 “归属” 可分为两类，存储位置完全不同，这是共享访问的核心前提：



| 变量类型                | 归属主体   | 存储位置           | 生命周期            | 能否跨线程共享 |
| ------------------- | ------ | -------------- | --------------- | ------- |
| 局部变量（方法内定义）         | 方法（栈帧） | 线程私有→Java 虚拟机栈 | 随方法执行（栈帧销毁而销毁）  | 否       |
| 非 static 成员变量（类内定义） | 对象实例   | 进程共享→堆内存       | 随对象实例（GC 回收而销毁） | 是       |
| static 成员变量（类内定义）   | 类（元数据） | 进程共享→方法区（元空间）  | 随类加载（JVM 退出而销毁） | 是       |

### 3.2 非 static 成员变量为何存储在堆内存？

非 static 成员变量的核心特性是 “与对象实例绑定”，而堆内存是 JVM 中唯一能承载 “对象实例生命周期” 的区域：



* 若成员变量存储在栈帧：方法执行完毕栈帧销毁，对象实例即使还在（未被 GC 回收），成员变量也会丢失，逻辑矛盾；

* 堆内存是 “进程级共享区域”（所有线程可访问），且对象实例的生命周期由 GC 管理 —— 只要对象未被回收，成员变量就持续存在，满足多线程共享需求。

### 3.3 代码案例：非 static 成员变量的存储验证



```
public class NonStaticVarStorageDemo {

&#x20;   // 非static成员变量：归属User对象实例，存储在堆内存

&#x20;   private String username;

&#x20;   private int age;

&#x20;   // 构造方法：初始化堆中的成员变量

&#x20;   public NonStaticVarStorageDemo(String username, int age) {

&#x20;       this.username = username; // this指向堆中的当前对象

&#x20;       this.age = age;

&#x20;   }

&#x20;   // Getter方法：通过对象引用访问堆中的成员变量

&#x20;   public String getUsername() { return username; }

&#x20;   public int getAge() { return age; }

&#x20;   public void setAge(int age) { this.age = age; }

&#x20;   public static void main(String\[] args) {

&#x20;       // 1. new NonStaticVarStorageDemo()：在堆中创建1个对象实例

&#x20;       NonStaticVarStorageDemo user = new NonStaticVarStorageDemo("张三", 20);

&#x20;      &#x20;

&#x20;       // 2. user是main线程栈帧的局部变量：存储的是堆对象的「引用（内存地址）」

&#x20;       System.out.println("堆中username：" + user.getUsername()); // 输出"张三"

&#x20;       System.out.println("堆中age：" + user.getAge()); // 输出20

&#x20;       // 3. 验证：栈帧中的引用≠堆中的对象

&#x20;       NonStaticVarStorageDemo user2 = user; // user2存储同一个堆对象的引用

&#x20;       user2.setAge(21); // 修改的是堆中对象的age，而非栈帧中的引用

&#x20;      &#x20;

&#x20;       // main线程通过user引用访问堆对象：能看到修改后的值（21）

&#x20;       System.out.println("修改后堆中age：" + user.getAge());&#x20;

&#x20;   }

}
```

**案例结论**：



* 堆内存中仅存在 “1 个对象实例”，`username` 和 `age` 作为成员变量存储于该实例中；

* 栈帧中的 `user` 和 `user2` 是 “引用变量”（存地址），而非对象本身；修改 `user2.age` 本质是通过地址修改堆中对象的成员变量。

## 四、第三部分：独立栈帧访问共享变量的原理 —— 堆共享 + 引用传递

### 4.1 核心逻辑：两步实现跨线程共享

独立的栈帧能访问同一非 static 成员变量，本质是 “堆的共享性” 与 “引用的传递性” 共同作用的结果，流程如下：

#### 步骤 1：堆内存是所有线程的 “公共数据区”

JVM 的堆内存是进程级共享区域（《JVM 进程核心构成》文档 2.2.1 章节明确）：



* 无论哪个线程，只要持有堆中对象的 “引用（内存地址）”，就能通过地址访问对象及成员变量；

* 这与 “Java 虚拟机栈的线程私有” 形成对比：栈帧是线程专属 “私人空间”，堆是线程共用 “公共仓库”。

#### 步骤 2：独立栈帧持有 “同一个对象的引用”

不同线程的栈帧虽然无法互相访问，但可以通过 “引用传递” 持有同一个堆对象的地址：



* 例如 main 线程将对象引用传给子线程（通过方法参数、类成员变量等方式），子线程的栈帧会存储该引用；

* 两个线程通过各自栈帧中的 “引用”，访问堆中同一个对象的成员变量 —— 相当于 “不同人拿着同一把钥匙，打开同一个仓库的门，操作仓库里的物品”。

### 4.2 代码案例：多线程共享非 static 成员变量



```
public class ThreadShareNonStaticVarDemo {

&#x20;   // 非static成员变量：存储在堆中，可被多线程共享

&#x20;   private int sharedCount = 0;

&#x20;   // 线程安全的自增方法（避免并发问题，后续补充）

&#x20;   public synchronized void increment() {

&#x20;       sharedCount++;

&#x20;   }

&#x20;   public int getSharedCount() {

&#x20;       return sharedCount;

&#x20;   }

&#x20;   public static void main(String\[] args) throws InterruptedException {

&#x20;       // 1. 在堆中创建1个ThreadShareNonStaticVarDemo对象

&#x20;       ThreadShareNonStaticVarDemo demo = new ThreadShareNonStaticVarDemo();

&#x20;       // 2. 启动2个子线程，共享同一个堆对象的引用（demo）

&#x20;       Thread thread1 = new Thread(() -> {

&#x20;           for (int i = 0; i < 1000; i++) {

&#x20;               demo.increment(); // 通过引用访问堆中的sharedCount

&#x20;           }

&#x20;       });

&#x20;       Thread thread2 = new Thread(() -> {

&#x20;           for (int i = 0; i < 1000; i++) {

&#x20;               demo.increment(); // 同样通过引用访问堆中的同一个sharedCount

&#x20;           }

&#x20;       });

&#x20;       // 3. 启动线程并等待执行完成

&#x20;       thread1.start();

&#x20;       thread2.start();

&#x20;       thread1.join();

&#x20;       thread2.join();

&#x20;       // 4. main线程访问堆中的sharedCount：结果为2000（两线程共享修改）

&#x20;       System.out.println("共享变量最终值：" + demo.getSharedCount());&#x20;

&#x20;   }

}
```

**案例结论**：



* 两个子线程的栈帧中均存储了 `demo` 引用（指向堆中同一个对象）；

* 线程 1 和线程 2 对 `sharedCount` 的自增操作，均作用于堆中同一个变量，最终结果为 2000，证明共享访问生效。

### 4.3 关键注意：共享变量的线程安全问题

虽然非 static 成员变量可被多线程共享，但直接修改可能导致 “并发安全问题”（如上述案例若去掉`synchronized`，最终结果可能小于 2000）：



* 原因：多线程对堆中同一变量的修改可能存在 “指令交错”（如读、改、写三步操作被打断）；

* 解决方案：通过`synchronized`、`volatile`、`AtomicInteger`等同步机制，保证共享变量的原子性、可见性和有序性。

## 五、文档总结：核心逻辑闭环



1. **栈帧独立的本质**：每个线程有专属的 Java 虚拟机栈（线程私有），栈帧仅存于所属线程的虚拟机栈中，跨线程不可访问；

2. **非 static 共享变量的存储**：归属对象实例，存储在进程共享的堆内存中，生命周期与对象实例绑定；

3. **共享访问的原理**：不同线程的栈帧通过 “持有同一个堆对象的引用”，访问堆中同一个成员变量 —— 堆的共享性提供 “公共访问空间”，引用传递提供 “地址指向”；

4. **关键提醒**：共享变量需注意线程安全，需通过同步机制避免并发问题。

**通俗类比**：



* 线程栈帧 = 每个人的 “私人房间”，房间里的局部变量 = 私人用品，别人无法使用；

* 堆内存 = 社区 “公共仓库”，对象实例 = 仓库里的箱子，非 static 成员变量 = 箱子里的物品；

* 引用 = 箱子的钥匙，不同人（线程）持有同一把钥匙（引用），就能共同操作箱子里的物品（共享变量）。

## 六、补充：与 static 成员变量的对比（避免混淆）

非 static 成员变量与 static 成员变量虽均能跨线程共享，但存储位置和归属不同，需注意区分：



| 对比维度 | 非 static 成员变量       | static 成员变量        |
| ---- | ------------------- | ------------------ |
| 归属主体 | 对象实例                | 类（元数据）             |
| 存储位置 | 堆内存                 | 方法区（元空间）           |
| 访问方式 | 需通过对象引用（如`obj.var`） | 直接通过类名（如`Cls.var`） |
| 生命周期 | 随对象实例（GC 回收）        | 随类加载（JVM 退出）       |
| 共享粒度 | 同一对象的所有线程共享         | 所有线程共享（类级共享）       |

> （注：文档部分内容可能由 AI 生成）