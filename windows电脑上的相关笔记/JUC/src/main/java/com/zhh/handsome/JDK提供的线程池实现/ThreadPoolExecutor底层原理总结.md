# ThreadPoolExecutor 底层原理与参数作用总结

## 一、核心参数的底层细节与作用（关联方法/组件）

- `corePoolSize`
  - 底层细节：`addWorker` 判断是否创建核心线程的阈值（`workerCountOf(ctl) < corePoolSize`）；核心线程默认用 `workQueue.take()` 取任务（无超时），不会被销毁；开启 `allowCoreThreadTimeOut=true` 后核心线程改为 `poll(keepAliveTime)`，可超时销毁。
  - 作用：划定常驻线程上限，决定任务提交时是否优先创建核心线程。
  - 关联：`addWorker(boolean core)`（`core=true`）、`runWorker()`（核心线程取任务逻辑）。

- `maximumPoolSize`
  - 底层细节：`addWorker` 判断是否创建非核心线程的阈值（`workerCountOf(ctl) < maximumPoolSize`）；是线程池总 `Worker` 上限（核心+非核心）。若 `workQueue` 为无界队列（如 `LinkedBlockingQueue`），入队永远成功，不会触发创建非核心线程。
  - 作用：限制总并发数，防止无限创建线程导致 OOM。
  - 关联：`addWorker(boolean core)`（`core=false`）、`ctl`（记录当前线程数）。

- `keepAliveTime` 与 `unit`
  - 底层细节：非核心线程取任务的超时时间，`runWorker` 中通过 `workQueue.poll(keepAliveTime, unit)` 实现；超时后 `poll` 返回 `null`，触发 `processWorkerExit` 销毁线程。时间单位通过 `TimeUnit.toNanos(keepAliveTime)` 等转换供 `poll` 使用。
  - 作用：控制非核心线程的空闲存活时长，减少资源浪费。
  - 关联：`runWorker()`（取任务超时逻辑）、`processWorkerExit()`（销毁逻辑）、`workQueue.poll(long, TimeUnit)`。

- `workQueue`
  - 底层细节：阻塞队列，核心方法 `offer`（入队）、`take`（无超时取）、`poll`（超时取）。`execute` 中优先尝试 `offer` 入队，失败才创建非核心线程。队列的有界/无界特性直接决定 `maximumPoolSize` 是否能生效。阻塞特性保证线程取任务的同步性（无任务阻塞，有任务唤醒）。
  - 作用：存储待执行任务，是“核心→非核心”的中间缓冲，决定任务排队规则。
  - 关联：`execute()`（任务入队判断）、`runWorker()`（线程取任务）。

- `threadFactory`
  - 底层细节：通过 `threadFactory.newThread(Runnable r)` 创建 `Worker` 内部 `Thread`；线程名、优先级、守护与否由其决定；若返回 `null`，`addWorker` 抛出 `NullPointerException`。
  - 作用：统一管理线程创建规则，是线程池与线程的桥梁，便于排查（自定义线程名）。
  - 关联：`Worker` 构造、`addWorker()`（创建 `Worker` 时调用）。

- `handler`（拒绝策略）
  - 底层细节：仅当“线程池非 RUNNING”或“线程数达 `max` 且队列满”时触发；通过 `handler.rejectedExecution(Runnable r, ThreadPoolExecutor e)` 执行拒绝逻辑；所有拒绝策略实现 `RejectedExecutionHandler` 接口。
  - 作用：处理“线程池+队列都满”的极端情况，避免任务无限堆积。
  - 关联：`execute()`（最后一步判断）、`RejectedExecutionHandler.rejectedExecution()`。

- `allowCoreThreadTimeOut`
  - 底层细节：为 `true` 时核心线程也用 `poll(keepAliveTime, unit)` 取任务并允许超时退出。
  - 作用：低负载场景下降低核心线程常驻成本。
  - 关联：`getTask()`、`runWorker()`。                                                             

- `ctl`（线程池状态与工作线程计数复合变量）
  - 底层细节：高位记录运行状态（`RUNNING/SHUTDOWN/STOP/TIDYING/TERMINATED`），低位记录 `workerCount`；所有增减用 CAS 保证并发安全。
  - 作用：统一管理状态与计数，是所有判定的基石。
  - 关联：`addWorker()`、`processWorkerExit()`、`shutdown()`/`shutdownNow()`、`runStateOf(ctl)`、`workerCountOf(ctl)`。

> 关键坑点：
> - `corePoolSize`：线程池初始化为懒加载，只有提交第一个任务才创建核心线程。
> - `maximumPoolSize`：无界队列时 `offer` 永远成功，最多只到 `corePoolSize`。
> - `keepAliveTime`：超时计时点为“上一个任务执行完后”，不是线程创建时。

## 二、完整底层流程（7 阶段）

### 阶段 1：初始化
- 行为：构造 `ThreadPoolExecutor` 时赋值所有参数；初始化 `ctl`（`RUNNING` + `workerCount=0`）；初始化 `workQueue`（有界队列会初始化底层数组）。
- 特性：不创建任何线程（懒加载）。

### 阶段 2：任务提交（`execute()` 判断）

```java
public void execute(Runnable command) {
  if (command == null) throw new NullPointerException();
  int wc = workerCountOf(ctl);
  if (wc < corePoolSize) {
    if (addWorker(command, true)) return; // 尝试创建核心线程
    wc = workerCountOf(ctl);
  }
  //所以说只有在入队失败的时候才会创建非核心线程
  if (isRunning(ctl) && workQueue.offer(command)) {
    if (workerCountOf(ctl) == 0) addWorker(null, false); // 保底补一个线程
    return; // 入队成功
  }
  if (!addWorker(command, false)) reject(command); // 创建非核心失败则拒绝
}
```

- 参数作用：
  - `corePoolSize`：步骤一的核心阈值；
  - `workQueue`：入队 `offer`；
  - `maximumPoolSize`：创建非核心线程的阈值；
  - `handler`：`reject` 调用；
  - `ctl`：状态与计数判定。

### 阶段 3：线程创建（`addWorker`）
- 逻辑：
  - 循环 CAS 修改 `ctl`，`workerCount+1`；失败（达阈值或状态不符）返回 `false`。
  - 成功后用 `threadFactory.newThread(Worker)` 创建线程；将 `Worker` 加入 `workers` 集合（加锁）；`thread.start()` 触发 `Worker.run()`。
- 参数：`core`（决定阈值取 `corePoolSize` 或 `maximumPoolSize`）、`threadFactory`、`firstTask`（绑定首个任务）。

#### `addWorker` 简化近似源码（参考 OpenJDK）

```java
private boolean addWorker(Runnable firstTask, boolean core) {
  retry: for (;;) {
    int c = ctl; int rs = runStateOf(c);
    if (rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
      return false;
    for (;;) {
      int wc = workerCountOf(c);
      int cap = core ? corePoolSize : maximumPoolSize;
      if (wc >= CAPACITY || wc >= cap) return false;
      if (compareAndIncrementWorkerCount(c)) break retry;
      c = ctl; if (runStateOf(c) != rs) continue retry;
    }
  }
  Worker w = new Worker(firstTask);
  Thread t = w.thread;
  if (t == null) { decrementWorkerCount(); return false; }
  final ReentrantLock mainLock = this.mainLock;
  mainLock.lock();
  try {
    int rs = runStateOf(ctl);
    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
      workers.add(w);
      if (workers.size() > largestPoolSize) largestPoolSize = workers.size();
    } else { mainLock.unlock(); return false; }
  } finally { mainLock.unlock(); }
  t.start();
  return true;
}
```

#### `Worker` 结构要点（简化）

```java
private final class Worker extends AbstractQueuedSynchronizer implements Runnable {
  final Thread thread;
  Runnable firstTask;
  Worker(Runnable firstTask) {
    setState(-1);
    this.firstTask = firstTask;
    this.thread = threadFactory.newThread(this);
  }
  public void run() { runWorker(this); }
}
```

### 阶段 4：线程复用（`runWorker`/`getTask`）

```java
final void runWorker(Worker w) {
  Thread wt = Thread.currentThread();
  Runnable task = w.firstTask; w.firstTask = null;
  while (task != null || (task = getTask()) != null) {
    try { task.run(); } finally { task = null; }
  }
  processWorkerExit(w, false);
}

private Runnable getTask() {
  boolean timedOut = false;
  for (;;) {
    boolean timed = allowCoreThreadTimeOut || workerCountOf(ctl) > corePoolSize;
    Runnable r = timed ? workQueue.poll(keepAliveTime, unit) : workQueue.take();
    if (r != null) return r;
    if (timedOut) return null; // 超时两次未取到任务
    timedOut = true;
  }
}
```

- 参数作用：`keepAliveTime`/`unit`（`poll` 超时）、`corePoolSize`（是否超时取任务）、`allowCoreThreadTimeOut`、`workQueue`（任务来源）。

### 阶段 5：线程销毁（`processWorkerExit`）
- 行为：
  - 从 `workers` 移除当前 `Worker`（加锁）；CAS 将 `workerCount-1`；
  - 根据状态判断是否关闭或补充线程；在 `RUNNING` 且队列有任务时补核心线程，保证不低于 `corePoolSize`。
- 关联参数：`corePoolSize` 保证核心线程数；`ctl` 计数修改与状态检查。

### 阶段 6：线程池关闭
- `shutdown()`（平缓关闭）：状态改为 `SHUTDOWN`；中断空闲线程（阻塞在 `take/poll`）；等待队列任务执行完成后转 `TERMINATED`。
- `shutdownNow()`（强制关闭）：状态改为 `STOP`；中断所有线程（含执行中的）；返回队列未执行任务；最终 `TERMINATED`。
- 注：`keepAliveTime` 不影响关闭流程，关闭时会强制中断。

### 阶段 7：线程复用与扩缩容交互
- 复用：`runWorker` 循环取任务形成复用；
- 扩容：入队失败时尝试创建非核心线程；
- 缩容：非核心线程在 `poll` 超时后退出；若启用核心超时，核心也可退出。

## 三、队列与并发策略影响
- 无界队列（`LinkedBlockingQueue`）：
  - 入队几乎不失败，`maximumPoolSize` 基本失效；线程数通常维持在 `corePoolSize`。
- 有界队列（`ArrayBlockingQueue`）：
  - 当队列满时触发非核心线程创建与拒绝策略；更可控的背压行为。
- 同步与可见性：
  - 取任务的阻塞/唤醒由队列实现；状态与计数通过 `ctl` 的 CAS 与高/低位分离保证原子性。

## 四、调优与实践建议
- 任务模型优先：CPU 密集型偏小 `corePoolSize`+有界队列；IO 密集型可适度增大核心与最大并发。
- 队列选型：生产优先有界队列，配合合理的拒绝策略实现背压。
- 核心超时：低负载服务可开启 `allowCoreThreadTimeOut` 降低常驻开销。
- 监控：队列长度、活跃线程数、拒绝次数、平均等待时长；结合报警与自适应调整。

## 五、其他框架的拒绝策略优化实践
- Spring（`ThreadPoolTaskExecutor`）
  - 沿用 JUC 的 `RejectedExecutionHandler` 体系，可直接配置为 `Abort/CallerRuns/Discard/DiscardOldest`；常见实践选择 `CallerRunsPolicy` 将压力回推到调用方以形成自然限流。
  - 运行期可动态调整核心/最大线程数、队列容量（在部分实现中需重建队列），结合 `AsyncUncaughtExceptionHandler` 统一记录被拒绝或执行异常，便于排查。
  - 与 `@Async`、`TaskExecutor` 集成，拒绝时抛出 `TaskRejectedException`，方便在业务层统一降级处理。

- Apache Tomcat（`org.apache.tomcat.util.threads.ThreadPoolExecutor`）
  - 通过自定义 `TaskQueue.offer` 行为“优先扩容再排队”：当线程数尚未达到 `maximumPoolSize` 时对 `offer` 返回 `false`，驱动 `addWorker` 扩容，降低排队与拒绝概率、改善尾延迟。
  - 真正达到最大线程且队列满时，再触发标准拒绝策略（默认通常为 `AbortPolicy`），同时配合连接器层面的 `maxConnections`/`acceptCount` 形成多层背压。

- Dubbo（分布式 RPC 线程池）
  - 增强型拒绝策略 `AbortPolicyWithReport`：在拒绝时采集线程栈、池指标与系统负载，落盘并告警，显著提升可观测性与问题定位效率。
  - 提供多种策略可选：`CallerRuns`（把任务在调用线程执行）、`Discard`/`DiscardOldest`（丢弃当前/最旧任务），按服务的延迟敏感度选择。
  - 结合线程池隔离与队列容量配置按服务维度限流，拒绝后由上层做快速失败或降级。

- Hystrix（命令隔离线程池，已停止维护但思路常用）
  - 当线程池/队列饱和时拒绝并立即触发 `fallback`，实现“快速失败+降级”，避免级联阻塞。
  - 每命令（或命令组）独立线程池与指标，拒绝/熔断与隔离联动，形成可控的保护边界。

- Netty（事件执行器 `SingleThreadEventExecutor`）
  - 默认在关闭或不可接收任务时直接抛出 `RejectedExecutionException`（`RejectedExecutionHandlers.reject()`），将背压更早地传到上层，从而依赖通道的可写性/水位线控制进行限流。
  - 设计强调“少排队、多协调”，将拒绝作为健康信号促使上层减少提交或拆分任务。

> 实践小结：除标准的四种策略外，框架优化主要聚焦于三点——
> - 优先扩容再排队（Tomcat 的队列改造）以降低拒绝与尾延迟；
> - 拒绝即告警/留证（Dubbo 的带报告策略）提升排障效率；
> - 拒绝联动降级（Hystrix 的 fallback）保证用户侧体验与系统稳定。

## 五、术语速览
- `Worker`：线程池中的工作单元，持有 `Thread` 与首个任务。
- `ctl`：高位池状态、低位工作线程计数的复合整型；所有修改需 CAS。
- 外显/内隐：任务为外显状态，线程池参数与共享结构为内隐状态。

## 六、线程工厂方法与默认实现
- 接口定义
  - `ThreadFactory`：唯一方法 `newThread(Runnable r)`，用于创建线程并设置名称、守护与优先级等属性。
- JDK 默认实现
  - `Executors.defaultThreadFactory()`：创建非守护、`NORM_PRIORITY` 线程，命名规则 `pool-N-thread-M`，归属当前或安全管理器指定的 `ThreadGroup`。
  - `Executors.privilegedThreadFactory()`：在受限安全环境下创建线程，继承调用方的 `AccessControlContext` 与 `contextClassLoader`，用于需要权限隔离的场景。
- ThreadPoolExecutor 中的使用
  - 若通过 `Executors.newFixedThreadPool/newCachedThreadPool` 创建，默认使用 `defaultThreadFactory`；自定义创建时可显式传入自定义 `ThreadFactory`。
- 自定义工厂实践要点
  - 统一命名：`{service}-{pool}-thread-{index}` 便于定位问题。
  - 明确属性：设置为非守护线程、`NORM_PRIORITY`，必要时设置 `UncaughtExceptionHandler`。
  - 观测增强：在 `newThread` 中记录创建计数、线程组、是否拒绝创建等信息，配合监控。

---

> 目录：`src/main/java/com/zhh/handsome/JDK提供的线程池实现`


























#### `addWorker` 简化近似源码（参考 OpenJDK）

```java
private boolean addWorker(Runnable firstTask, boolean core) {
  retry: for (;;) {
    int c = ctl; int rs = runStateOf(c);
    if (rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
      return false;
    for (;;) {
      int wc = workerCountOf(c);
      int cap = core ? corePoolSize : maximumPoolSize;
      if (wc >= CAPACITY || wc >= cap) return false;
      if (compareAndIncrementWorkerCount(c)) break retry;
      c = ctl; if (runStateOf(c) != rs) continue retry;
    }
  }
  Worker w = new Worker(firstTask);
  Thread t = w.thread;
  if (t == null) { decrementWorkerCount(); return false; }
  final ReentrantLock mainLock = this.mainLock;
  mainLock.lock();
  try {
    int rs = runStateOf(ctl);
    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
      workers.add(w);
      if (workers.size() > largestPoolSize) largestPoolSize = workers.size();
    } else { mainLock.unlock(); return false; }
  } finally { mainLock.unlock(); }
  t.start();
  return true;
}

讲解以下这段代码的逻辑
你希望我详细拆解这段 addWorker 简化源码的逻辑，讲清楚每一部分代码的作用、循环的目的、核心变量的含义，以及整个方法的执行流程，让你理解线程池创建 Worker（工作线程）的底层核心逻辑。
首先先明确：addWorker 是 ThreadPoolExecutor 中创建并启动工作线程（Worker）的核心方法，参数 firstTask 是线程创建后要执行的第一个任务，core 是布尔值（true 表示按 corePoolSize 作为阈值，false 表示按 maximumPoolSize），返回值表示是否成功创建并启动线程。
下面逐段拆解代码逻辑，结合注释和底层设计思路讲解：
一、整体逻辑框架
这段代码分为 4 个核心阶段：
状态 + 线程数校验（双层循环 + CAS）：无锁阶段，快速判断是否能创建线程，并用 CAS 安全增加线程数；
Worker 对象创建：封装任务和线程，是线程复用的核心载体；
加锁保护 workers 集合：修改线程池的 Worker 集合时保证线程安全；
启动线程并返回结果：完成线程创建和启动。
二、逐行 / 逐块拆解代码
1. 外层循环（retry 标签）：线程池状态校验
java
运行
retry: for (;;) {
  int c = ctl; int rs = runStateOf(c);
  if (rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
    return false;
  // ... 内层循环
}
核心变量：
c = ctl：获取线程池的「状态 + 线程数」原子变量（ctl 高 3 位是状态，低 29 位是线程数）；
rs = runStateOf(c)：提取 ctl 的高 3 位，得到线程池状态（RUNNING/SHUTDOWN/STOP 等）。
关键条件判断：
java
运行
rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty())
翻译成人话：
当线程池状态不是 RUNNING（rs >= SHUTDOWN）时，只有一种例外情况允许创建线程：
线程池是 SHUTDOWN 状态 + 没有初始任务（firstTask=null） + 任务队列非空（workQueue.isEmpty ()=false）—— 这种情况是为了创建线程处理队列中剩余的任务；
其他所有非 RUNNING 状态的情况，直接返回 false（拒绝创建线程）。
循环意义：for (;;) 是无限循环，配合 retry 标签，用于状态变化时重新校验（后面内层循环会触发重试）。
2. 内层循环：CAS 安全增加线程数
java
运行
for (;;) {
  int wc = workerCountOf(c);
  int cap = core ? corePoolSize : maximumPoolSize;
  if (wc >= CAPACITY || wc >= cap) return false;
  if (compareAndIncrementWorkerCount(c)) break retry;
  c = ctl; if (runStateOf(c) != rs) continue retry;
}
这是并发安全的核心，目的是「在无锁情况下，用 CAS 增加线程数」：
wc = workerCountOf(c)：提取 ctl 的低 29 位，得到当前线程池的工作线程数；
cap = core ? corePoolSize : maximumPoolSize：根据 core 参数选择阈值（创建核心线程用 corePoolSize，非核心线程用 maximumPoolSize）；
wc >= CAPACITY || wc >= cap：
CAPACITY 是 ctl 低 29 位的最大值（2^29-1），防止线程数溢出；
若当前线程数超过阈值（corePoolSize/maximumPoolSize）或容量上限，返回 false；
compareAndIncrementWorkerCount(c)：
核心 CAS 操作：尝试将线程数 + 1（仅当当前 ctl 仍为 c 时才成功）；
CAS 成功：跳出外层 retry 循环，进入后续创建 Worker 阶段；
CAS 失败：说明有其他线程同时修改了线程数，需要重试：
重新获取 ctl（c = ctl）；
若线程池状态变了（runStateOf(c) != rs），回到外层循环重新校验状态；
否则继续内层循环，再次尝试 CAS。
3. 创建 Worker 对象并检查线程
java
运行
Worker w = new Worker(firstTask);
Thread t = w.thread;
if (t == null) { decrementWorkerCount(); return false; }
Worker w = new Worker(firstTask)：创建 Worker 对象（线程池的核心复用载体）：
Worker 实现了 Runnable，内部会通过 threadFactory 创建一个 Thread 对象；
firstTask 是这个线程启动后要执行的第一个任务；
t = w.thread：获取 Worker 封装的真实线程对象；
若 t == null：说明线程工厂（threadFactory）创建线程失败，需要「回滚」—— 调用 decrementWorkerCount() 将之前 CAS 增加的线程数减回去，然后返回 false。
4. 加锁修改 workers 集合（主锁保护）
java
运行
final ReentrantLock mainLock = this.mainLock;
mainLock.lock();
try {
  int rs = runStateOf(ctl);
  if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
    workers.add(w);
    if (workers.size() > largestPoolSize) largestPoolSize = workers.size();
  } else { mainLock.unlock(); return false; }
} finally { mainLock.unlock(); }
这一步是线程安全的关键，因为 workers 是存储所有 Worker 的 HashSet（非线程安全），必须加锁修改：
mainLock：线程池的主锁，用于保护 workers、largestPoolSize 等核心变量的修改；
lock() + finally unlock()：保证锁一定会释放，避免死锁；
二次状态校验：
重新获取线程池状态 rs，再次判断：
rs < SHUTDOWN：线程池是 RUNNING 状态（允许创建线程）；
或 rs == SHUTDOWN && firstTask == null：SHUTDOWN 状态且无初始任务（用于处理队列剩余任务）；
满足以上条件，才将 Worker 加入 workers 集合，并更新 largestPoolSize（记录线程池历史最大线程数）；
若状态不满足：解锁并返回 false（创建失败）。
5. 启动线程并返回结果
java
运行
t.start();
return true;
t.start()：启动 Worker 对应的线程，触发 Worker.run() 方法（进而执行 runWorker 逻辑，开始循环取任务执行，实现线程复用）；
返回 true：表示 Worker 成功创建并启动。
三、核心设计思路 & 关键细节
双层循环的意义：
外层循环：处理「线程池状态变化」的重试（比如 CAS 失败后状态变了，需要重新校验）；
内层循环：专注于「CAS 增加线程数」的重试，无锁操作提升并发效率。
两次状态校验：
第一次：无锁阶段快速校验，避免不必要的锁开销；
第二次：加锁后再次校验，防止无锁阶段状态变化导致错误（比如校验后线程池被关闭）。
CAS + 锁的分层设计：
线程数的修改（轻量操作）：用 CAS 无锁实现，提升并发；
workers 集合的修改（重量操作）：用 ReentrantLock 保护，保证安全。
失败回滚：
若线程工厂创建线程失败，会调用 decrementWorkerCount() 回滚线程数，保证 ctl 记录的线程数和实际一致。
总结（核心关键点）
addWorker 先通过「双层循环 + CAS」完成线程池状态校验和线程数安全增加，这一步无锁，效率高；
创建 Worker 后，通过主锁（mainLock） 保护 workers 集合的修改，同时二次校验状态，避免并发问题；
最终启动线程，完成 Worker 的创建，核心逻辑是「无锁 CAS 改计数，加锁改集合，双重状态校验」，既保证并发安全，又兼顾性能。