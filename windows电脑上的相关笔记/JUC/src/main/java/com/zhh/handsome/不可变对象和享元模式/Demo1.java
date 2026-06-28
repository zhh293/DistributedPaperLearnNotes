// 一、先搞懂：什么是不可变对象？
//不可变是指引用不可变，内部的值也不可变，哪里都不能变。
//牢牢记住了。。。。

// 核心定义：对象被创建后，其内部状态（成员变量的值）无法被修改，只能读取，不能更新。
// 举个生活例子：
// 不可变对象 = 一张打印好的纸（内容固定，不能修改，要改只能重新打印一张）；
// 可变对象 = 一个记事本（可以随时擦除、修改内容）。
// 关键区分：不可变 ≠ 只读
// 很多人会把「只读」和「不可变」搞混，这里用代码明确区别：
// java
// 运行
// // 1. 只读对象（不是不可变）
// class ReadOnlyObj {
//     private List<String> list = new ArrayList<>();
//     // 只提供「读取」方法，不提供「修改」方法
//     public List<String> getList() { return list; }
// }

// // 测试：看似只读，实则能被外部修改内部状态
// public class Test {
//     public static void main(String[] args) {
//         ReadOnlyObj obj = new ReadOnlyObj();
//         List<String> list = obj.getList();
//         list.add("外部修改的内容"); // 能成功修改！
//         System.out.println(obj.getList()); // 输出 [外部修改的内容]
//     }
// }
// 原因：getList() 返回的是 List 的引用，外部通过引用仍能修改 List 内部数据。而不可变对象要求：即使拿到内部成员的引用，也无法修改对象的状态。
// 二、为什么需要不可变对象？（核心价值）
// 不可变对象的设计是为了解决 Java 中的核心痛点，主要有 4 大优势：
// 1. 天生线程安全（最核心优势）
// 多线程环境下，可变对象的状态修改会导致「竞态条件」（比如两个线程同时修改一个变量），需要加锁（synchronized）才能保证安全。而不可变对象的状态永远不变，多个线程同时读取时，不会出现任何并发问题，无需加锁，天然线程安全。
// 2. 简化编程，减少 Bug
// 不用考虑「对象被谁修改了」「什么时候修改的」—— 状态一旦创建就固定，调试时不用追踪修改链路，代码更易维护。
// 3. 可安全作为缓存 Key
// HashMap、HashSet 等集合的 Key 要求「哈希值稳定」（如果 Key 的哈希值变了，会导致无法找到对应的 Value）。不可变对象的哈希值在创建时就确定（基于内部状态计算），永远不变，是完美的缓存 Key（比如 String 常作为 HashMap 的 Key）。
// 4. 支持对象池 / 常量池复用
// 不可变对象的状态固定，相同状态的对象可以复用，减少内存占用。比如 String 常量池（"abc" 只创建一次，多次使用时复用同一个对象）、Integer 的缓存池（Integer.valueOf(123) 会复用 -128~127 之间的对象）。
// 三、如何手动创建不可变对象？（5 条黄金规则）
// Java 没有内置的 immutable 关键字，要创建不可变对象，必须严格遵循以下 5 条规则（缺一不可），我们用代码示例一步步实现：
// 规则 1：类声明为 final，禁止被继承
// 如果类不是 final，子类可能重写方法修改父类的状态，破坏不可变性。
// 规则 2：所有成员变量声明为 private final
// private：禁止外部直接访问成员变量；
// final：禁止成员变量被重新赋值（引用类型的 final 表示「引用不可变」，但引用的对象可能可变，后续要处理）。
// 规则 3：不提供任何「修改状态」的方法（无 setter）
// 禁止定义 setXxx()、add()、remove() 等可能修改成员变量的方法。
// 规则 4：对「引用类型成员变量」做「深拷贝」
// 如果成员变量是可变对象（比如 Date、List、自定义可变类），仅用 final 不够（引用不可变，但对象内部可改），必须通过「深拷贝」保证外部无法修改内部状态：
// 构造方法中：对传入的可变对象做深拷贝，存储拷贝后的对象；
// getter 方法中：返回拷贝后的对象（或不可变视图），禁止返回原始引用。
// 规则 5：构造方法中「防御性拷贝」
// 防止外部通过传入的可变对象引用，在构造方法执行完后修改对象内部状态。
// 实战：实现一个真正的不可变类
// 假设我们要创建一个 ImmutablePerson 类，包含「姓名（String，不可变）」和「生日（Date，可变）」，严格遵循以上规则：
// java
// 运行
// import java.util.Date;

// // 规则 1：类声明为 final，禁止继承
// public final class ImmutablePerson {
//     // 规则 2：成员变量 private final
//     private final String name;
//     private final Date birthday;

//     // 规则 5：构造方法防御性拷贝（对可变参数深拷贝）
//     public ImmutablePerson(String name, Date birthday) {
//         this.name = name; // String 本身是不可变的，直接赋值即可
//         // 对 Date（可变对象）做深拷贝：避免外部修改传入的 birthday 影响内部
//         this.birthday = new Date(birthday.getTime());
//     }

//     // 规则 3：只提供 getter，无 setter
//     public String getName() {
//         return name; // String 不可变，直接返回引用没问题
//     }

//     public Date getBirthday() {
//         // 规则 4：返回拷贝后的对象（禁止返回原始引用）
//         return new Date(birthday.getTime());
//     }

//     // 可选：重写 toString 方便打印
//     @Override
//     public String toString() {
//         return "ImmutablePerson{name='" + name + "', birthday=" + birthday + "}";
//     }
// }
// 测试：验证不可变性
// java
// 运行
// public class TestImmutable {
//     public static void main(String[] args) {
//         Date birthday = new Date();
//         ImmutablePerson person = new ImmutablePerson("张三", birthday);

//         // 尝试 1：修改外部传入的 birthday（构造方法已深拷贝，内部不受影响）
//         birthday.setTime(0); // 把外部的 birthday 改成 1970-01-01
//         System.out.println(person.getBirthday()); // 输出原始生日（不受影响）

//         // 尝试 2：通过 getter 获取 birthday 后修改（返回的是拷贝，内部不受影响）
//         Date innerBirthday = person.getBirthday();
//         innerBirthday.setTime(0);
//         System.out.println(person.getBirthday()); // 输出原始生日（仍不受影响）

//         // 尝试 3：没有 setter 方法，无法直接修改 name 或 birthday
//         // person.setName("李四"); // 编译报错：无此方法
//     }
// }
// 结果：无论外部怎么尝试，ImmutablePerson 的内部状态都无法修改，真正实现了不可变性。
// 四、Java 中常见的不可变类
// Java 内置了很多不可变类，核心都是遵循上面的设计规则：
// 1. String 类（最典型）
// String 是 final 类，不能被继承；
// 内部存储字符的数组 private final char[] value（JDK 9 后改为 byte[]），是 private final；
// 所有修改字符串的方法（concat()、replace()、substring()）都会返回新的 String 对象，不会修改原对象。
// 示例：
// java
// 运行
// String s = "abc";
// s.concat("def"); // 返回新对象 "abcdef"，原对象 s 仍为 "abc"
// System.out.println(s); // 输出 "abc"
// 2. 包装类（Integer、Long、Boolean 等）
// 类是 final，成员变量 value 是 private final（比如 Integer 的 private final int value）；
// 无 setter 方法，修改时会返回新对象（比如 Integer.valueOf(1).byteValue() 不会修改原对象）。
// 注意：包装类的「自动装箱」会复用缓存（-128~127 之间的 Integer 对象），也是基于不可变性的优化。
// 3. BigInteger、BigDecimal
// 类是 final，内部存储数值的数组是 private final；
// 所有算术操作（add()、multiply()）都会返回新对象，原对象不变。
// 4. 其他
// LocalDate、LocalTime、LocalDateTime（Java 8 时间类）：都是不可变的，修改时返回新对象；
// URI、URL（部分不可变，核心状态无法修改）。
// 五、不可变对象的缺点与规避方案
// 不可变对象不是万能的，有两个明显缺点，需要针对性处理：
// 缺点 1：创建对象频繁，内存开销大
// 修改不可变对象时，必须创建新对象（比如 String 拼接 s1 + s2 + s3 会产生多个中间对象），如果频繁修改，会增加 GC 压力。
// 规避方案：
// 字符串拼接用 StringBuilder（非线程安全）或 StringBuffer（线程安全），避免产生中间对象；
// 对于频繁修改的场景，优先用可变对象（比如 ArrayList 代替不可变的 List），仅在需要共享 / 并发时用不可变对象。
// 缺点 2：深拷贝成本高
// 如果不可变对象包含多层可变成员（比如 ImmutablePerson 里有 List<Date>），深拷贝会消耗更多资源。
// 规避方案：
// 尽量使用不可变的成员变量（比如用 String 代替 Date，用 ImmutableList 代替 ArrayList）；
// 对高频复用的不可变对象，使用缓存池（比如 Integer.valueOf() 的缓存）。
// 六、常见误区澄清
// 「final 修饰的对象就是不可变对象」？错！final 只保证「对象引用不可变」（不能重新赋值），但对象内部的成员变量仍可修改：
// java
// 运行
// final Person person = new Person("张三");
// person.setName("李四"); // 如果 Person 有 setter，仍能修改内部状态
// // person = new Person("王五"); // 编译报错：final 引用不能重新赋值
// 「不可变对象的成员变量必须是基本类型」？错！成员变量可以是引用类型，但必须保证引用的对象也是不可变的（或通过深拷贝隔离外部修改），比如 ImmutablePerson 中的 Date 通过深拷贝保证不可变。
// 「不可变对象不能有任何修改方法」？错！可以有修改方法，但方法必须返回新的不可变对象，而不是修改原对象（比如 String.concat()、BigInteger.add()）。
// 七、总结
// 不可变对象的核心是「创建后状态不可修改」，设计时遵循 5 条规则：final 类 + private final 成员变量 + 无 setter + 深拷贝（引用类型） + 防御性拷贝（构造方法）。
// 适用场景：多线程共享、缓存 Key、常量池复用（比如 String、包装类）；
// 不适用场景：频繁修改的对象（比如循环拼接字符串、频繁增减的列表）；
// 核心优势：天生线程安全、简化编程、哈希值稳定、可复用。