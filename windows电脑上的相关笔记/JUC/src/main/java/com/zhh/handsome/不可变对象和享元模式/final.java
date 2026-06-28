// 二、final 的三大使用场景（含用法 + 原理）
// 场景 1：修饰类 → 禁止类被继承
// 用法：类声明时加 final，子类无法继承该类
// java
// 运行
// // final 类：无法被继承
// final class FinalClass {
//     public void sayHello() {
//         System.out.println("我是 final 类");
//     }
// }

// // 尝试继承 final 类：编译报错！
// // class SubClass extends FinalClass {} // 错误：Cannot extend final class 'FinalClass'
// 底层原理：字节码标识 + 编译器校验
// 字节码层面：final 类的字节码中，会被标记 ACC_FINAL 访问标识（可通过 javap -v 类名.class 查看）；
// plaintext
// // FinalClass 的字节码片段
// public final class com.example.FinalClass
//   minor version: 0
//   major version: 52
//   flags: ACC_PUBLIC, ACC_FINAL, ACC_SUPER // 这里的 ACC_FINAL 就是 final 类的标识
// 编译器约束：当编译器遇到「子类继承 final 类」的代码时，直接触发语法错误，拒绝编译；
// JVM 层面：即使通过字节码篡改工具移除 ACC_FINAL 标识，JVM 在加载类时也会检查父类是否为 final，若为 final 仍会抛出 IllegalAccessError，彻底阻止继承。
// 典型应用：Java 内置的 final 类
// String、Integer（包装类）、LocalDate 等：这些类的逻辑稳定，不允许子类修改（比如 String 的不可变性依赖 final 类的特性，若允许继承，子类可能破坏不可变性）；
// 工具类（如 Math）：核心方法逻辑固定，无需继承扩展。
// 场景 2：修饰方法 → 禁止方法被重写
// 用法：方法声明时加 final，子类无法重写该方法（但可以重载）
// java
// 运行
// class Parent {
//     // final 方法：禁止重写
//     public final void finalMethod() {
//         System.out.println("父类 final 方法");
//     }

//     // 普通方法：允许重写
//     public void normalMethod() {
//         System.out.println("父类普通方法");
//     }
// }

// class Child extends Parent {
//     // 尝试重写 final 方法：编译报错！
//     // @Override
//     // public void finalMethod() {} // 错误：Cannot override the final method from Parent

//     // 重载 final 方法：允许（重载≠重写）
//     public final void finalMethod(String arg) {
//         System.out.println("子类重载 final 方法：" + arg);
//     }

//     // 重写普通方法：允许
//     @Override
//     public void normalMethod() {
//         System.out.println("子类重写普通方法");
//     }
// }
// 关键区分：重写 vs 重载
// 重写（Override）：子类方法与父类方法「方法名、参数列表、返回值」完全一致，覆盖父类逻辑；
// 重载（Overload）：子类 / 本类方法与原方法「方法名相同，参数列表不同」，是新方法；
// final 只禁止「重写」，不禁止「重载」。
// 底层原理：字节码标识 + 编译器校验 + JVM 优化
// 字节码层面：final 方法的字节码中，会被标记 ACC_FINAL 标识；
// plaintext
// // Parent 类中 finalMethod 的字节码片段
// public final void finalMethod()
//   descriptor: ()V
//   flags: ACC_PUBLIC, ACC_FINAL // 方法的 ACC_FINAL 标识
//   Code:
//     stack=2, locals=1, args_size=1
//       0: getstatic     #2                  // Field java/lang/System.out:Ljava/io/PrintStream;
//       3: ldc           #3                  // String 父类 final 方法
//       5: invokevirtual #4                  // Method java/io/PrintStream.println:(Ljava/lang/String;)V
//       8: return
// 编译器约束：子类重写 final 方法时，编译器直接报错；
// JVM 优化（重要）：由于 final 方法不能被重写，JVM 可以确定「方法调用的目标」（不会有子类替换逻辑），因此会进行「方法内联优化」—— 直接将方法体代码嵌入调用处，减少方法调用的开销（类似 C++ 的 inline 函数）。
// 典型应用：
// 父类中逻辑稳定、不允许子类修改的方法（比如 String.equals()，逻辑严格，子类若重写会破坏 String 的特性）；
// 性能敏感的核心方法：通过 final 修饰触发 JVM 内联优化，提升执行效率。
// 场景 3：修饰变量 → 禁止变量被重新赋值
// 这是 final 最常用也最容易混淆的场景，需分「基本类型变量」和「引用类型变量」分开讲解，核心规则：final 变量只能赋值一次，赋值后不能重新赋值。
// 3.1 修饰基本类型变量 → 值不可变
// java
// 运行
// public class FinalVariableTest {
//     // 1. 声明时直接初始化（推荐）
//     final int a = 10;

//     // 2. 声明时不初始化，后续在「构造器」中初始化（仅允许一次）
//     final int b;
//     public FinalVariableTest() {
//         b = 20; // 合法：构造器中首次赋值
//         // b = 30; // 错误：已赋值，不能重新赋值
//     }

//     // 3. 声明时不初始化，后续在「静态代码块」中初始化（静态 final 变量）
//     static final int c;
//     static {
//         c = 30; // 合法：静态代码块中首次赋值
//     }

//     public void test() {
//         // a = 20; // 错误：基本类型 final 变量，值不能改
//         System.out.println(a); // 只能读取
//     }
// }
// 3.2 修饰引用类型变量 → 引用不可变，对象内部可改
// java
// 运行
// class Person {
//     private String name;
//     public Person(String name) { this.name = name; }
//     // setter 允许修改对象内部状态
//     public void setName(String name) { this.name = name; }
//     public String getName() { return name; }
// }

// public class FinalReferenceTest {
//     public static void main(String[] args) {
//         // final 修饰引用类型变量：p 的引用（指向的对象地址）不能改
//         final Person p = new Person("张三");

//         // 1. 尝试修改引用：编译报错！
//         // p = new Person("李四"); // 错误：Cannot assign a value to final variable 'p'

//         // 2. 修改对象内部状态：合法！
//         p.setName("李四");
//         System.out.println(p.getName()); // 输出「李四」（对象内部变了，但引用没变）
//     }
// }
// 关键误区：final 引用 ≠ 不可变对象
// final 引用：仅限制「引用不能指向其他对象」，但对象内部的成员变量可通过 setter 等方法修改；
// 不可变对象：要求「对象内部状态完全不可改」（需满足 final 类 + private final 成员变量 + 无 setter + 深拷贝，之前讲过）；
// 关系：不可变对象的成员变量通常用 final 修饰，但 final 引用的对象不一定是不可变对象。
// 3.3 final 变量的初始化规则（必须遵守）
// final 变量必须在「首次使用前完成初始化」，否则编译报错，允许的初始化方式：
// 变量类型	允许的初始化方式	不允许的方式
// 局部 final 变量	声明时初始化 / 声明后、使用前初始化	使用后初始化 / 多次初始化
// 成员 final 变量	声明时初始化 / 构造器中初始化 / 代码块中初始化	构造器外的普通方法中初始化
// 静态 final 变量	声明时初始化 / 静态代码块中初始化	构造器中初始化 / 普通方法中初始化
// 示例（局部 final 变量）：
// java
// 运行
// public void testLocalFinal() {
//     // 局部 final 变量：声明后初始化（合法）
//     final int d;
//     d = 40; // 首次赋值，合法
//     // d = 50; // 错误：多次赋值

//     // 局部 final 变量：未初始化就使用（编译报错）
//     // final int e;
//     // System.out.println(e); // 错误：Variable 'e' might not have been initialized
// }
// 3.4 底层原理：字节码标识 + 常量折叠 + JMM 约束
// （1）字节码层面：标记 ACC_FINAL
// final 变量的字节码中，会被标记 ACC_FINAL 标识，编译器通过该标识禁止重新赋值：
// plaintext
// // FinalVariableTest 类中 a 变量的字节码片段
// final int a;
// descriptor: I
// flags: ACC_FINAL, ACC_PRIVATE // 变量的 ACC_FINAL 标识
// ConstantValue: int 10 // 编译期常量的初始值（仅静态 final 或声明时初始化的 final 变量有）
// （2）编译期优化：常量折叠（Compile-Time Constant Folding）
// 对于「编译期常量」（静态 final + 基本类型 / 字符串 + 声明时初始化且值为字面量），编译器会直接将变量名替换为具体值，减少运行时开销：
// java
// 运行
// public class ConstantFoldingTest {
//     // 编译期常量：static final + 基本类型 + 字面量初始化
//     public static final int MAX_AGE = 100;

//     public static void main(String[] args) {
//         int age = MAX_AGE; // 编译时直接替换为 int age = 100;
//         System.out.println(age);
//     }
// }
// 反编译后代码（javap -c 查看）：
// plaintext
// public static void main(java.lang.String[]);
//   Code:
//     0: bipush        100 // 直接使用 100，而非 MAX_AGE 变量
//     2: istore_1
//     3: getstatic     #2                  // Field java/lang/System.out:Ljava/io/PrintStream;
//     6: iload_1
//     7: invokevirtual #3                  // Method java/io/PrintStream.println:(I)V
//    10: return
// 注意：若 final 变量是「运行期常量」（如 final int num = new Random().nextInt()），则不会进行常量折叠，因为值在运行时才能确定。
// （3）JMM 约束：保证内存可见性和初始化安全性
// 在多线程环境中，final 变量有两个关键特性（由 Java 内存模型 JMM 保证）：
// 内存可见性：final 变量初始化完成后，其他线程无需加锁就能看到其最新值（类似 volatile，但 volatile 是双向可见，final 是单向 —— 仅保证初始化后可见）；
// 初始化安全性：即使没有同步机制，其他线程也能看到 final 变量的「完全初始化状态」（比如 final 引用的对象，其他线程看到的是对象构造完成后的状态，不会出现 “半初始化”）。
// 原理：JMM 对 final 变量的写操作和读操作做了「禁止指令重排」约束：
// 构造器中对 final 变量的赋值，不能被重排到构造器之外（确保 final 变量在对象构造完成前已初始化）；
// 其他线程读取 final 变量时，不能重排到读取对象引用之前（确保先看到对象引用，再看到 final 变量的初始化后的值）。
// 三、final 与其他关键字的对比
// 1. final vs static
// 关键字	核心作用	修饰变量时的区别	修饰方法时的区别
// final	不可变 / 不可重写 / 不可继承	变量只能赋值一次，实例变量每个对象一份	方法不能被重写，可触发 JVM 内联优化
// static	静态（属于类，不属于实例）	变量属于类，所有对象共享，可多次赋值（非 final 时）	方法属于类，不能被重写（只能隐藏），无内联优化
// 示例（static final 变量）：
// java
// 运行
// // static final 变量：类级别的常量，所有对象共享，值不可改
// public static final String DEFAULT_NAME = "未知";
// 特点：编译期常量，常量折叠，内存中只存一份（类加载时初始化）。
// 2. final vs volatile
// 关键字	核心作用	修饰对象	线程安全保障
// final	不可变（赋值后不能改）	类、方法、变量	保证初始化安全性和可见性，不保证原子性
// volatile	可见性、禁止指令重排	只能修饰变量（基本类型 / 引用）	保证可见性和禁止重排，不保证原子性（不能替代锁）
// 注意：volatile 和 final 不能同时修饰一个变量（矛盾：volatile 允许变量被修改，final 禁止修改）。
// 四、常见误区澄清
// 误区 1：final 修饰的对象是不可变对象？
// 错！final 修饰引用变量时，仅限制「引用不能指向其他对象」，但对象内部的成员变量可修改（如 final Person p = new Person ("张三"); p.setName ("李四"); 是合法的）。不可变对象需要额外满足：final 类 + private final 成员变量 + 无 setter + 深拷贝。
// 误区 2：final 变量必须在声明时初始化？
// 错！final 变量可在「声明时、构造器、代码块（静态代码块 for 静态变量）」中初始化，只要在「首次使用前」完成一次赋值即可。
// 误区 3：final 方法不能被重载？
// 错！final 禁止的是「重写」，不是「重载」。重载是方法名相同、参数列表不同，属于新方法，完全允许。
// 误区 4：final 变量的常量折叠是万能的？
// 错！只有「静态 final + 基本类型 / 字符串 + 字面量初始化」的变量才会被常量折叠。若 final 变量是运行期赋值（如 final int num = new Random().nextInt()），则不会常量折叠。
// 误区 5：final 能保证线程安全？
// 不完全对！final 仅保证「自身的初始化安全性和可见性」，但不保证对变量的操作是原子性的。比如：
// java
// 运行
// // final 修饰引用变量，对象内部成员变量的修改仍需线程安全保障
// final Person p = new Person("张三");
// // 多线程同时调用 p.setName()，仍会出现线程安全问题（需加锁或用线程安全的成员变量）
// 五、final 的实战应用场景
// 1. 定义常量（static final）
// 用于存储程序中固定不变的值（如配置项、枚举值），编译期常量折叠，性能更优：
// java
// 运行
// public class Config {
//     // 数据库连接端口（常量）
//     public static final int DB_PORT = 3306;
//     // 默认编码（常量）
//     public static final String DEFAULT_CHARSET = "UTF-8";
// }
// 2. 实现不可变对象（final 类 + final 成员变量）
// 如 String、Integer 等，通过 final 类禁止继承，final 成员变量禁止修改，确保对象不可变：
// java
// 运行
// final class ImmutableUser {
//     private final String id;
//     private final String name;

//     public ImmutableUser(String id, String name) {
//         this.id = id;
//         this.name = name;
//     }

//     // 只提供 getter，无 setter
//     public String getId() { return id; }
//     public String getName() { return name; }
// }
// 3. 禁止方法重写，避免逻辑混乱
// 父类中核心逻辑（如工具类方法、业务规则方法）用 final 修饰，防止子类重写后破坏原有逻辑：
// java
// 运行
// class OrderService {
//     // 订单支付核心逻辑：禁止重写
//     public final void pay(Order order) {
//         // 校验订单状态 → 扣减库存 → 发起支付 → 记录日志
//         // 逻辑固定，子类不能修改
//     }

//     // 扩展点：允许子类重写（模板方法模式）
//     protected void afterPay(Order order) {
//         // 支付后回调，子类可自定义
//     }
// }
// 4. 锁定对象，避免线程安全问题
// 多线程环境中，用 final 修饰锁对象，防止锁对象被替换导致线程安全问题：
// java
// 运行
// public class LockTest {
//     // final 修饰锁对象：确保锁对象不会被替换
//     private final Object lock = new Object();

//     public void doTask() {
//         synchronized (lock) {
//             // 线程安全的业务逻辑
//         }
//     }
// }
// 若 lock 不是 final，可能被其他代码修改为 lock = new Object()，导致不同线程持有不同锁，线程安全失效。
// 5. 提升性能（JVM 内联优化）
// final 方法不能被重写，JVM 可确定方法调用目标，进行「方法内联」（将方法体嵌入调用处），减少方法调用的栈帧切换开销：
// java
// 运行
// class MathUtil {
//     // 高频调用的工具方法：用 final 修饰触发内联优化
//     public final static int add(int a, int b) {
//         return a + b;
//     }
// }
// 六、总结
// final 的核心是「不可变约束」，不同修饰对象的作用和原理可总结为：
// 修饰对象	核心作用	底层原理
// 类	禁止继承	字节码 ACC_FINAL + 编译器 / JVM 双重校验
// 方法	禁止重写 + 内联优化	字节码 ACC_FINAL + JVM 内联优化
// 变量	禁止重新赋值	字节码 ACC_FINAL + 常量折叠 + JMM 禁止重排
// 关键要点：
// final 修饰引用 ≠ 不可变对象，仅限制引用不能改，对象内部可改；
// final 的核心价值：代码安全（禁止意外修改）、稳定性（逻辑固定）、性能优化（常量折叠 + 内联）；
// 多线程场景中，final 保证初始化安全性和可见性，但不保证原子性，复杂线程安全仍需锁或并发工具。