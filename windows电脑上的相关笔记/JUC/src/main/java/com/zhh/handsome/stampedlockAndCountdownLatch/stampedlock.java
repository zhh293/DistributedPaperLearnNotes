// . 先看生活例子：为什么需要 StampedLock？
// 场景：图书馆的书架
// 大部分时间：很多读者（线程）要「读」书架上的书（查询操作），读操作不修改数据，互相不干扰；
// 少数时间：管理员（线程）要「写」书架（整理书籍、新增书籍，修改数据），写操作需要独占，不能和其他读 / 写同时进行。
// 问题：用传统的 ReentrantReadWriteLock（读写锁）有什么问题？
// ReentrantReadWriteLock 是「悲观读」：只要有一个读者在读，写者就必须排队；如果读者特别多，写者会一直等待（“写饥饿”）；
// 比如图书馆里 100 个读者在看书，管理员想整理书架，必须等所有读者都看完才能动手 —— 效率太低。
// StampedLock 的解决方案：乐观读
// 管理员先 “乐观地” 看看有没有读者在修改书架（写操作）：如果没有，直接快速整理（读操作）；
// 如果刚好有读者在修改（写操作），再 “升级” 为悲观读（排队等待写操作完成）；
// 本质：读操作默认不排队，只有真的遇到写操作时才排队，大幅提升高并发读的效率。
// 2. 核心定义
// StampedLock（翻译：“带戳记的锁”）：
// 是 ReentrantReadWriteLock 的增强版，支持「三种锁模式」，核心优化是「乐观读」；
// 所有锁操作都会返回一个「戳记（stamp）」—— 一个 long 类型的数字，类似 “锁的凭证”；
// 释放锁、转换锁模式时，必须传入对应的 stamp，否则会报错（确保锁的正确性）；
// 核心目标：解决「高并发读、低并发写」场景下的 “写饥饿” 问题，提升整体吞吐量。
// 关键特性：戳记（stamp）是锁的核心凭证—— 不同锁模式的 stamp 含义不同，无效的 stamp（如已释放、已过期）无法操作锁。
// 3. 三种锁模式（核心！必须搞懂）
// StampedLock 有三种锁模式，覆盖 “读 - 写” 的所有场景，重点是「乐观读」：
// 锁模式	作用	特点（对比 ReentrantReadWriteLock）	适用场景
// 写锁（Write Lock）	排他锁：同一时间只能有一个线程持有，禁止其他读 / 写	和 ReentrantReadWriteLock 的写锁一致	数据修改操作（如新增、删除、更新）
// 悲观读锁（Read Lock）	共享锁：同一时间多个线程可以持有，禁止写线程	和 ReentrantReadWriteLock 的读锁一致	读操作，但需要确保读期间数据不被修改（如金融计算）
// 乐观读（Optimistic Read）	无锁：不阻塞写线程，读线程先 “乐观” 读取，再校验是否有写操作干扰	ReentrantReadWriteLock 没有此模式	高并发读、写操作极少的场景（如查询接口）
// 三种模式的切换逻辑（核心流程）：
// plaintext
// 读操作优先走「乐观读」：
// 1. 尝试乐观读，获取stamp；
// 2. 读取数据；
// 3. 校验stamp是否有效（期间是否有写操作）；
//    - 有效：直接使用读取到的数据（高效，无阻塞）；
//    - 无效：升级为「悲观读锁」，排队等待写操作完成后，再读数据；

// 写操作走「写锁」：
// 1. 尝试获取写锁，获取成功则拿到stamp，独占锁；
// 2. 修改数据；
// 3. 释放写锁（传入stamp）；
// 4. 写操作期间，乐观读的校验会失败，悲观读会排队。
// 4. 核心方法（按锁模式分类，重点记乐观读流程）
// （1）写锁相关方法
// 方法名	作用	返回值（stamp）含义
// long writeLock()	阻塞获取写锁（直到拿到锁）	成功：非 0 的 stamp；失败：无（一直阻塞）
// long tryWriteLock()	非阻塞获取写锁（立即返回）	成功：非 0 的 stamp；失败：0
// long tryWriteLock(long timeout, TimeUnit unit)	超时获取写锁	成功：非 0 的 stamp；失败：0
// void unlockWrite(long stamp)	释放写锁（必须传入对应的 stamp）	无返回值；stamp 无效抛 IllegalMonitorStateException
// （2）悲观读锁相关方法
// 方法名	作用	返回值（stamp）含义
// long readLock()	阻塞获取悲观读锁	成功：非 0 的 stamp；失败：无（一直阻塞）
// long tryReadLock()	非阻塞获取悲观读锁	成功：非 0 的 stamp；失败：0
// long tryReadLock(long timeout, TimeUnit unit)	超时获取悲观读锁	成功：非 0 的 stamp；失败：0
// void unlockRead(long stamp)	释放悲观读锁	无返回值；stamp 无效抛异常
// （3）乐观读相关方法（核心！）
// 方法名	作用	返回值含义
// long tryOptimisticRead()	获取乐观读的 stamp（非阻塞，立即返回）	成功：非 0 的 stamp（表示当前无写锁）；失败：0（当前有写锁）
// boolean validate(long stamp)	校验 stamp 是否有效（期间是否有写操作）	有效（无写操作）：true；无效（有写操作）：false
// （4）锁模式转换方法（可选，进阶用）
// 方法名	作用	适用场景
// long tryConvertToReadLock(long stamp)	将其他模式的 stamp 转换为悲观读锁的 stamp	乐观读校验失败后，升级为悲观读
// long tryConvertToWriteLock(long stamp)	将其他模式的 stamp 转换为写锁的 stamp	读锁持有期间，需要修改数据时转换
// 5. 底层原理（通俗讲，不用深究源码）
// StampedLock 底层没有用 AQS（和 ReentrantReadWriteLock 不同），而是自己实现了一个「CLH 队列锁」+「状态位管理」，核心逻辑：
// 用一个「64 位的 stamp」存储锁的状态：
// 高 32 位：锁的版本号；
// 低 32 位：锁的模式（无锁、乐观读、悲观读、写锁）；
// 乐观读 tryOptimisticRead()：本质是 “读锁的版本号”，不修改锁状态，所以不阻塞写线程；
// 校验 validate(stamp)：对比当前锁的版本号和之前获取的 stamp 的版本号 —— 若一致，说明期间无写操作；若不一致，说明有写操作（版本号递增）；
// 写锁 writeLock()：获取锁时会递增版本号，释放时也会递增版本号 —— 确保乐观读能检测到写操作；
// 悲观读锁 readLock()：获取锁时会标记 “有读线程”，写线程会阻塞直到所有读线程释放。
// 关键优化：乐观读不需要修改锁状态，不需要排队，所以效率极高 —— 这是 StampedLock 比 ReentrantReadWriteLock 快的核心原因。
// 6. 实战案例：高并发读 + 低并发写的缓存场景
// 需求：实现一个缓存工具类，支持「查询缓存（读）」和「更新缓存（写）」：
// 读操作：高并发（100 个线程同时查）；
// 写操作：低并发（1 个线程定期更新）；
// 要求：读操作效率高，写操作不被饿死。
// 步骤 1：实现缓存类（用 StampedLock 保护）
// java
// 运行
// import java.util.HashMap;
// import java.util.Map;
// import java.util.concurrent.locks.StampedLock;

// // 缓存工具类：高并发读，低并发写
// class Cache {
//     // 缓存数据（线程不安全，需要锁保护）
//     private final Map<String, String> data = new HashMap<>();
//     // StampedLock 实例
//     private final StampedLock lock = new StampedLock();

//     // 1. 查缓存（读操作：优先乐观读，失败升级为悲观读）
//     public String get(String key) {
//         // 步骤1：尝试乐观读，获取stamp（非阻塞）
//         long stamp = lock.tryOptimisticRead();
//         String value = null;
//         try {
//             // 步骤2：乐观读数据（无锁，直接读）
//             value = data.get(key);
//             System.out.println(Thread.currentThread().getName() + " 乐观读缓存，key=" + key + "，value=" + value);

//             // 步骤3：校验stamp是否有效（期间是否有写操作）
//             if (!lock.validate(stamp)) {
//                 // 步骤4：校验失败，升级为悲观读锁（排队等待写操作完成）
//                 stamp = lock.readLock(); // 阻塞获取悲观读锁
//                 System.out.println(Thread.currentThread().getName() + " 乐观读失效，升级为悲观读锁");
//                 value = data.get(key); // 再次读数据（此时无写操作干扰）
//             }
//         } finally {
//             // 步骤5：释放锁（乐观读不需要释放，悲观读需要释放）
//             if (StampedLock.isReadLockStamp(stamp)) {
//                 lock.unlockRead(stamp); // 释放悲观读锁
//             }
//             // 乐观读的stamp不需要释放，因为没占用锁
//         }
//         return value;
//     }

//     // 2. 更新缓存（写操作：独占写锁）
//     public void put(String key, String value) {
//         // 步骤1：获取写锁（阻塞，直到拿到锁）
//         long stamp = lock.writeLock();
//         try {
//             System.out.println(Thread.currentThread().getName() + " 获取写锁，开始更新缓存：key=" + key + "，value=" + value);
//             // 步骤2：修改缓存（独占操作，无其他读/写干扰）
//             data.put(key, value);
//             // 模拟更新耗时（1秒）
//             Thread.sleep(1000);
//             System.out.println(Thread.currentThread().getName() + " 缓存更新完成");
//         } catch (InterruptedException e) {
//             Thread.currentThread().interrupt();
//         } finally {
//             // 步骤3：释放写锁（必须传入stamp）
//             lock.unlockWrite(stamp);
//             System.out.println(Thread.currentThread().getName() + " 释放写锁");
//         }
//     }
// }
// 步骤 2：测试高并发读 + 低并发写
// java
// 运行
// public class StampedLockDemo {
//     public static void main(String[] args) {
//         Cache cache = new Cache();

//         // 1. 启动1个写线程：定期更新缓存（key="name"）
//         new Thread(() -> {
//             for (int i = 0; i < 2; i++) {
//                 cache.put("name", "张三-" + i); // 每次更新为不同值
//                 try {
//                     Thread.sleep(2000); // 每2秒更新一次
//                 } catch (InterruptedException e) {
//                     e.printStackTrace();
//                 }
//             }
//         }, "写线程").start();

//         // 2. 启动10个读线程：高并发查询缓存（key="name"）
//         for (int i = 0; i < 10; i++) {
//             new Thread(() -> {
//                 while (true) {
//                     String value = cache.get("name");
//                     try {
//                         Thread.sleep(500); // 每0.5秒查一次
//                     } catch (InterruptedException e) {
//                         e.printStackTrace();
//                     }
//                 }
//             }, "读线程-" + i).start();
//         }
//     }
// }
// 运行结果分析（关键日志）
// plaintext
// // 初始状态：缓存为空，读线程乐观读返回null
// 读线程-0 乐观读缓存，key=name，value=null
// 读线程-1 乐观读缓存，key=name，value=null
// ...

// // 写线程获取写锁，开始更新
// 写线程 获取写锁，开始更新缓存：key=name，value=张三-0
// 写线程 缓存更新完成
// 写线程 释放写锁

// // 写锁释放后，读线程乐观读拿到新值
// 读线程-2 乐观读缓存，key=name，value=张三-0
// 读线程-3 乐观读缓存，key=name，value=张三-0
// ...

// // 写线程再次更新（期间有读线程乐观读失效，升级为悲观读）
// 写线程 获取写锁，开始更新缓存：key=name，value=张三-1
// 读线程-4 乐观读缓存，key=name，value=张三-0
// 读线程-4 乐观读失效，升级为悲观读锁 // 因为写线程持有写锁，校验stamp失败
// 写线程 缓存更新完成
// 写线程 释放写锁
// 读线程-4 乐观读失效，升级为悲观读锁：value=张三-1 // 悲观读拿到新值
// 核心结论：
// 无写操作时，所有读线程都走乐观读，无阻塞，效率极高；
// 有写操作时，部分读线程乐观读失效，升级为悲观读（排队），写操作不会被饿死（因为乐观读不阻塞写）；
// 整体吞吐量比 ReentrantReadWriteLock 高很多（尤其是读并发越高，优势越明显）。
// 7. 优缺点
// 优点：
// 高并发读效率极高：乐观读模式无锁，不阻塞写线程，比 ReentrantReadWriteLock 快；
// 解决写饥饿问题：乐观读不占用锁，写线程不需要等所有读线程完成，只需等当前写线程完成；
// 灵活：支持三种锁模式，可根据场景切换（乐观读→悲观读→写锁）。
// 缺点：
// 不支持重入：同一线程不能重复获取同一把锁（比如线程持有悲观读锁，再调用 readLock () 会阻塞）；
// 不支持条件变量（Condition）：ReentrantReadWriteLock 支持 Condition，StampedLock 不支持；
// 乐观读需要手动校验：代码复杂度比 ReentrantReadWriteLock 高（需要写 try-catch、校验 stamp、释放锁）；
// 异常处理复杂：如果乐观读期间发生异常，不需要释放锁；但悲观读 / 写锁必须在 finally 里释放，否则会导致锁泄漏。
// 8. 常见误区
// 误区 1：乐观读不需要释放锁，所以可以随便用？
// 对！但必须手动调用 validate(stamp) 校验 —— 不校验的话，可能拿到 “脏数据”（写线程修改后的数据）。
// 误区 2：StampedLock 是线程安全的，所以保护的对象也线程安全？
// 错！StampedLock 只保证「锁的正确性」，但它保护的共享对象（如示例中的 HashMap）本身可能线程不安全 —— 必须确保所有对共享对象的访问都通过 StampedLock 保护（读走乐观读 / 悲观读，写走写锁）。
// 误区 3：StampedLock 支持重入？
// 错！StampedLock 不支持重入。比如：
// java
// 运行
// StampedLock lock = new StampedLock();
// long stamp = lock.readLock();
// lock.readLock(); // 阻塞！因为同一线程不能重复获取悲观读锁
// 若需重入：用 ReentrantReadWriteLock，或自己维护线程持有锁的次数。
// 误区 4：释放锁时可以传入任意 stamp？
// 错！释放锁时必须传入「获取锁时返回的 stamp」—— 否则会抛 IllegalMonitorStateException。比如：
// java
// 运行
// long stamp1 = lock.writeLock();
// long stamp2 = lock.tryWriteLock(); // 0（因为stamp1已持有写锁）
// lock.unlockWrite(stamp2); // 抛异常！stamp2是无效的
// 误区 5：StampedLock 比 ReentrantReadWriteLock 好，所有场景都能用？
// 错！适用场景有限：
// 适合「高并发读、低并发写」的场景（如缓存、查询接口）；
// 若写并发高（比如写操作占比 30% 以上），乐观读失效频繁，升级为悲观读的开销会变大，此时 StampedLock 优势不明显，甚至不如 ReentrantReadWriteLock；
// 若需要重入、条件变量，直接用 ReentrantReadWriteLock。
// 三、总结：两个工具类的核心区别与适用场景
// 工具类	核心作用	适用场景	关键特点
// CountDownLatch	等待多线程完成后，当前线程再继续	主线程等待子线程完成（如合并结果、集合发车）	计数器不可重置，一次性使用，基于 AQS 共享锁
// StampedLock	高并发读场景的读写锁优化，支持乐观读	高并发读、低并发写（如缓存、查询接口）	不支持重入，乐观读高效，解决写饥饿
// 一句话记住：
// 要 “等人齐了再干活”→ 用 CountDownLatch；
// 要 “多人大声读，少人安静写”→ 用 StampedLock。