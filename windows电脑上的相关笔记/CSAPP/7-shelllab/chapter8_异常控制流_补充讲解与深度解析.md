# 第8章 异常控制流 — 补充讲解与深度解析

> 本文档是 `chapter8_异常控制流.md` 的**深度补充**，针对主笔记中点到为止、一笔带过或读者可能跳步的知识点，进行系统的、不少于 3000 行的全面展开。
> 目标读者：正在做 Shell Lab（tsh.c）的同学，以及希望把第 8 章所有"为什么"都搞清楚的读者。
>
> 全文分为五大块：
> 1. 前置知识深度讲解
> 2. 跳步内容深度补充（15 个专题）
> 3. 自检清单详解（40+ 问题的详细答案）
> 4. 常见困惑 Q&A（9 个深度问答）
> 5. Shell Lab 实战完整指导

---

## 目录

- [第一大块：前置知识深度讲解](#第一大块前置知识深度讲解)
  - [1. Linux 终端与 Shell 基础](#1-linux-终端与-shell-基础)
  - [2. C 语言基础](#2-c-语言基础)
  - [3. 虚拟内存基础概念](#3-虚拟内存基础概念)
  - [4. 操作系统基本概念](#4-操作系统基本概念)
- [第二大块：跳步内容深度补充](#第二大块跳步内容深度补充)
  - [专题 1：DMA 传输](#专题-1dma-传输)
  - [专题 2：前台进程组与后台进程组](#专题-2前台进程组与后台进程组)
  - [专题 3：setpgid(0,0) 的必要性](#专题-3setpgid00-的必要性)
  - [专题 4：MMU / 页表 / PTE 详解](#专题-4mmu--页表--pte-详解)
  - [专题 5：TLB 与 ASID/PCID](#专题-5tlb-与-asidpcid)
  - [专题 6：ASLR](#专题-6aslr)
  - [专题 7：CFS 完全公平调度](#专题-7cfs-完全公平调度)
  - [专题 8：可重入函数](#专题-8可重入函数)
  - [专题 9：volatile 与 sig_atomic_t](#专题-9volatile-与-sig_atomic_t)
  - [专题 10：atexit 函数](#专题-10atexit-函数)
  - [专题 11：辅助向量 (auxiliary vector)](#专题-11辅助向量-auxiliary-vector)
  - [专题 12：close-on-exec 标志](#专题-12close-on-exec-标志)
  - [专题 13：会话 (session) 与控制终端](#专题-13会话-session-与控制终端)
  - [专题 14：tcsetpgrp](#专题-14tcsetpgrp)
  - [专题 15：信号处理中的竞争条件](#专题-15信号处理中的竞争条件)
- [第三大块：自检清单详解](#第三大块自检清单详解)
  - [段落一：异常与异常处理](#段落一异常与异常处理)
  - [段落二：进程](#段落二进程)
  - [段落三：进程控制](#段落三进程控制)
  - [段落四：信号](#段落四信号)
  - [段落五：非本地跳转与实际应用](#段落五非本地跳转与实际应用)
- [第四大块：常见困惑 Q&A](#第四大块常见困惑-qa)
- [第五大块：Shell Lab 实战完整指导](#第五大块shell-lab-实战完整指导)
  - [eval 函数实现步骤](#eval-函数实现步骤)
  - [builtin_cmd 实现](#builtin_cmd-实现)
  - [sigchld_handler 实现](#sigchld_handler-实现)
  - [waitfg 用 sigsuspend](#waitfg-用-sigsuspend)
  - [6 类常见 Bug 分析与修复](#6-类常见-bug-分析与修复)
  - [trace 文件逐步调试方法](#trace-文件逐步调试方法)

---

# 第一大块：前置知识深度讲解

---

## 1. Linux 终端与 Shell 基础

### 1.1 终端的历史：从电传打字机到伪终端

#### 1.1.1 TTY 的起源

TTY 是 **Teletypewriter**（电传打字机）的缩写。在计算机出现之前，电传打字机是一种用电信号在两地之间传输文字的设备。1960 年代，人们把这种设备接到大型计算机上，作为人机交互的输入/输出设备：

```
   用户                        计算机
 ┌──────────┐               ┌──────────────┐
 │ 电传打字机 │──── 串口线 ────│  UART 串口    │
 │(Teletype) │               │  ↓           │
 │           │               │  终端驱动程序  │
 │  键盘→输入 │               │  ↓           │
 │  纸带←输出 │               │  Shell 进程   │
 └──────────┘               └──────────────┘
```

每台电传打字机对应一个**串口**（serial port），操作系统为每个串口创建一个设备文件 `/dev/ttyS0`, `/dev/ttyS1`, ...。这就是为什么 Unix/Linux 系统中"终端设备"统称为 `tty`。

#### 1.1.2 物理终端 → 虚拟终端 → 终端仿真器

随着技术演进，终端经历了三个阶段：

```
阶段 1：物理终端（1960s-1980s）
  硬件设备，通过串口线直接连接到计算机
  代表：DEC VT100（定义了至今沿用的转义序列标准）
  设备文件：/dev/ttyS*

阶段 2：虚拟终端 / 虚拟控制台（1980s-至今）
  操作系统在内核中模拟多个终端
  Linux 默认有 6 个虚拟控制台（Ctrl+Alt+F1 ~ F6）
  设备文件：/dev/tty1 ~ /dev/tty6

阶段 3：终端仿真器 + 伪终端（1990s-至今）
  图形界面中的终端程序（xterm, GNOME Terminal, iTerm2）
  使用"伪终端"（PTY）机制
  设备文件：/dev/pts/*（slave 端）
```

#### 1.1.3 PTY（伪终端）的工作原理

伪终端是现代 Linux 系统中最常用的终端类型。当你打开一个终端仿真器时，系统会创建一对伪终端设备：

```
     终端仿真器                        内核                          Shell
  (如 iTerm2)                                                    (如 bash)
 ┌─────────────┐          ┌───────────────────────┐         ┌─────────────┐
 │             │          │    PTY 子系统           │         │             │
 │  GUI 程序    │          │                        │         │  /bin/bash  │
 │             │          │  ┌──────┐  ┌──────┐    │         │             │
 │  键盘输入 ───┼───write──▶│ master │──│ slave │────┼──read──▶│  stdin      │
 │             │          │  │  fd  │  │  fd  │    │         │             │
 │  屏幕输出 ◀──┼───read───│  │      │◀─│      │◀───┼──write──│  stdout     │
 │             │          │  └──────┘  └──────┘    │         │             │
 └─────────────┘          │                        │         └─────────────┘
                          │  终端行规程(line disc)    │
                          │  (回显、行编辑、信号)      │
                          └───────────────────────┘
```

**关键组件**：
- **Master 端** (`/dev/ptmx`)：由终端仿真器程序打开和控制
- **Slave 端** (`/dev/pts/N`)：Shell 进程的 stdin/stdout/stderr 连接到这里
- **行规程** (Line Discipline)：内核中的一个中间层，负责：
  - 回显（echo）：你敲的字出现在屏幕上
  - 行编辑：退格、删除行等
  - **信号生成**：Ctrl+C 产生 SIGINT，Ctrl+Z 产生 SIGTSTP，Ctrl+\ 产生 SIGQUIT

用代码来理解 PTY 的创建过程：

```c
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>

int main() {
    // 1. 打开 master 端
    int master_fd = posix_openpt(O_RDWR | O_NOCTTY);
    
    // 2. 授予 slave 端访问权限，解锁
    grantpt(master_fd);
    unlockpt(master_fd);
    
    // 3. 获取 slave 端的设备文件路径
    char *slave_name = ptsname(master_fd);
    // slave_name 类似 "/dev/pts/3"
    
    // 4. 打开 slave 端
    int slave_fd = open(slave_name, O_RDWR);
    
    // 5. fork 子进程
    pid_t pid = fork();
    if (pid == 0) {
        // 子进程：关闭 master，使用 slave 作为终端
        close(master_fd);
        
        // 创建新会话，slave 成为控制终端
        setsid();
        ioctl(slave_fd, TIOCSCTTY, 0);
        
        // 把 stdin/stdout/stderr 重定向到 slave
        dup2(slave_fd, STDIN_FILENO);
        dup2(slave_fd, STDOUT_FILENO);
        dup2(slave_fd, STDERR_FILENO);
        
        if (slave_fd > STDERR_FILENO)
            close(slave_fd);
        
        // 启动 shell
        execl("/bin/bash", "bash", NULL);
    }
    // 父进程：使用 master_fd 与子进程通信
    // 写到 master_fd 的数据 → 子进程的 stdin
    // 从 master_fd 读取的数据 ← 子进程的 stdout
    
    return 0;
}
```

#### 1.1.4 查看你的终端信息

```bash
# 查看当前终端设备
$ tty
/dev/pts/0

# 查看终端的行规程设置
$ stty -a
speed 38400 baud; rows 50; columns 200;
...
intr = ^C; quit = ^\; erase = ^?; kill = ^U; eof = ^D;
...
-echo  -echoe  -echok  ...

# 关键字段解释：
# intr = ^C   → Ctrl+C 产生 SIGINT
# quit = ^\   → Ctrl+\ 产生 SIGQUIT
# susp = ^Z   → Ctrl+Z 产生 SIGTSTP
# eof = ^D    → Ctrl+D 产生 EOF
# erase = ^?  → 退格键删除一个字符
```

### 1.2 Shell 的工作原理

#### 1.2.1 什么是 Shell

Shell 是一个**命令行解释器**（command-line interpreter）。它不是操作系统的一部分，而是一个普通的用户态程序，但它的作用至关重要：作为用户与操作系统内核之间的接口。

```
                ┌─────────────────────────────────────┐
                │              用户                    │
                │  ┌───────────────────────────────┐   │
                │  │      Shell（命令行解释器）       │   │
                │  │   bash / zsh / fish / tsh     │   │
                │  └───────────────┬───────────────┘   │
                │                  │ 系统调用           │
                │  ┌───────────────▼───────────────┐   │
                │  │         操作系统内核             │   │
                │  │  (进程管理/内存管理/文件系统)    │   │
                │  └───────────────────────────────┘   │
                │  ┌───────────────────────────────┐   │
                │  │           硬件                  │   │
                │  └───────────────────────────────┘   │
                └─────────────────────────────────────┘
```

#### 1.2.2 Shell 的核心工作循环

每个 Shell（包括我们要实现的 tsh）都遵循一个基本的 **Read-Evaluate-Print Loop (REPL)**：

```c
// Shell 的伪代码骨架
while (1) {
    // 1. 打印提示符
    printf("tsh> ");
    
    // 2. 读取用户输入的一行命令
    char cmdline[MAXLINE];
    fgets(cmdline, MAXLINE, stdin);
    
    // 3. 如果是 EOF（用户按了 Ctrl+D），退出
    if (feof(stdin))
        exit(0);
    
    // 4. 解析和执行命令
    eval(cmdline);
}
```

#### 1.2.3 Shell 处理命令的完整流程

当你在 Shell 中输入 `ls -la /home` 并按回车时，以下事情依次发生：

```
步骤 1：词法分析（Tokenization / Parsing）
  输入字符串 "ls -la /home\n"
  解析为 argv 数组：
    argv[0] = "ls"
    argv[1] = "-la"
    argv[2] = "/home"
    argv[3] = NULL

步骤 2：判断命令类型
  if 是内置命令 (builtin command)：
    Shell 进程自己直接执行（不 fork 子进程）
    例如：cd, exit, jobs, fg, bg
  else 是外部程序：
    继续步骤 3

步骤 3：Fork 子进程
  pid_t pid = fork();
  // 此时有两个进程在运行

步骤 4（子进程）：执行程序
  execve("/bin/ls", argv, environ);
  // execve 用 ls 的代码和数据替换子进程的地址空间
  // 如果 execve 成功，子进程变成了 ls 程序
  // 这行之后的代码不会执行（除非 execve 失败）

步骤 5（父进程 / Shell）：等待或继续
  if 前台命令（没有 &）：
    waitpid(pid, &status, 0);  // 阻塞等待子进程结束
  else 后台命令（有 &）：
    打印后台作业信息
    立即返回到步骤 1
```

用时序图来展示：

```
   Shell 进程 (pid=1000)              子进程 (pid=2001)
        │                                    
    printf("tsh> ")                          
    fgets() → "ls -la\n"                     
    parseline()                              
    fork() ──────────────────────────▶ 被创建
        │                                │
    waitpid(2001, ...)                execve("/bin/ls", ...)
    （阻塞等待...）                       │
        │                            ls 开始执行
        │                            列出文件...
        │                            ls 执行完毕
        │                            exit(0) → 变成僵尸进程
        │                                │
    waitpid 返回 ◀───────── SIGCHLD ──────┘
    回收子进程                               
    回到循环顶部                             
    printf("tsh> ")                          
```

#### 1.2.4 为什么内置命令不能 fork

有些命令**必须**由 Shell 进程自身执行，而不能在子进程中执行。最典型的例子是 `cd`：

```bash
# 如果 cd 在子进程中执行会发生什么？
Shell (pid=1000, cwd=/home/user)
  │
  ├─ fork() → 子进程 (pid=2001, cwd=/home/user)
  │             │
  │             ├─ chdir("/tmp")    # 子进程的 cwd 变了
  │             │   cwd=/tmp
  │             └─ exit(0)          # 子进程结束
  │
  └─ waitpid(2001, ...)
     # Shell 的 cwd 仍然是 /home/user！
     # cd 没有起任何作用！
```

因为 `fork()` 创建的子进程有独立的地址空间，子进程改变自己的工作目录不会影响父进程。所以 `cd` 必须作为内置命令，由 Shell 进程直接调用 `chdir()` 系统调用。

类似地，Shell Lab 中的 `quit`, `jobs`, `fg`, `bg` 也是内置命令。

### 1.3 前台进程与后台进程

#### 1.3.1 基本概念

在 Shell 中，运行一个命令有两种模式：

```
前台运行（Foreground）：
  $ sleep 30
  # Shell 等待 sleep 结束，期间不接受新命令
  # Ctrl+C 可以终止它
  # Ctrl+Z 可以暂停它

后台运行（Background）：
  $ sleep 30 &
  [1] 12345          # [作业号] PID
  $                  # Shell 立即回到提示符，可以输入新命令
  # sleep 在后台继续运行
```

**关键区别**：

| 特性 | 前台进程 | 后台进程 |
|------|---------|---------|
| Shell 是否等待 | 是，阻塞等待其结束 | 否，立即返回提示符 |
| 是否接收键盘输入 | 是 | 否（尝试读取终端会收到 SIGTTIN 信号并暂停）|
| Ctrl+C (SIGINT) | 收到并终止 | 不受影响 |
| Ctrl+Z (SIGTSTP) | 收到并暂停 | 不受影响 |
| 同时存在的数量 | 最多 1 个 | 可以有多个 |

#### 1.3.2 终端驱动如何区分前台和后台

终端驱动程序通过**前台进程组** (foreground process group) 来决定将键盘信号发送给谁：

```
   键盘输入（Ctrl+C）
       │
       ▼
   终端驱动程序
       │
       │  检查：当前终端的"前台进程组"是哪个？
       │  (通过 tcgetpgrp(fd) 获取)
       │
       ▼
   向前台进程组的所有进程发送 SIGINT
   
   ┌──────────────────────────────────────────┐
   │  会话 (Session)                           │
   │                                           │
   │  ┌──────────────────────┐                │
   │  │ 前台进程组 (pgid=100)  │ ◀── SIGINT   │
   │  │  Shell (pid=100)      │               │
   │  │  cat (pid=101)        │               │
   │  └──────────────────────┘                │
   │                                           │
   │  ┌──────────────────────┐                │
   │  │ 后台进程组 (pgid=200)  │ ← 不受影响    │
   │  │  sleep (pid=200)      │               │
   │  └──────────────────────┘                │
   │                                           │
   │  ┌──────────────────────┐                │
   │  │ 后台进程组 (pgid=300)  │ ← 不受影响    │
   │  │  gcc (pid=300)        │               │
   │  │  as (pid=301)         │               │
   │  └──────────────────────┘                │
   └──────────────────────────────────────────┘
```

### 1.4 作业控制完整教程

#### 1.4.1 什么是作业 (Job)

**作业 (Job)** 是 Shell 层面的概念，一个作业对应 Shell 执行的一条命令行。一个作业可能包含一个或多个进程（例如管道命令 `cat file | grep pattern | wc -l` 是一个作业但包含三个进程）。

```
    Shell
      │
      ├── 作业 1 [前台]：ls -la
      │     └── 进程 pid=2001
      │
      ├── 作业 2 [后台]：sleep 100 &
      │     └── 进程 pid=2002
      │
      ├── 作业 3 [后台]：cat file | sort | uniq &
      │     ├── 进程 pid=2003 (cat)
      │     ├── 进程 pid=2004 (sort)
      │     └── 进程 pid=2005 (uniq)
      │
      └── 作业 4 [已停止]：vim file (被 Ctrl+Z 暂停)
            └── 进程 pid=2006
```

#### 1.4.2 作业状态转换图

```
                      ┌─────────────────────────┐
                      │                         │
               ┌──────▼──────┐           ┌──────┴──────┐
               │   前台运行    │           │   后台运行    │
               │  Foreground  │           │  Background  │
               │  (Running)   │           │  (Running)   │
               └──────┬──────┘           └──────┬──────┘
                      │                         │
          ┌───────────┼───────────┐             │
          │           │           │             │
       Ctrl+C      Ctrl+Z     完成/exit     完成/exit
     (SIGINT)    (SIGTSTP)       │              │
          │           │           │             │
          ▼           ▼           ▼             ▼
     ┌─────────┐ ┌──────────┐  ┌──────────────────┐
     │ 已终止   │ │ 已停止    │  │     已终止         │
     │Terminated│ │ Stopped  │  │   Terminated      │
     └─────────┘ └─────┬────┘  └──────────────────┘
                       │
             ┌─────────┼─────────┐
             │                   │
          fg 命令             bg 命令
             │                   │
             ▼                   ▼
       ┌──────────┐       ┌──────────┐
       │ 前台运行   │       │ 后台运行   │
       │Foreground │       │Background│
       └──────────┘       └──────────┘
```

#### 1.4.3 jobs 命令

`jobs` 命令列出当前 Shell 的所有作业：

```bash
$ sleep 100 &
[1] 12345
$ sleep 200 &
[2] 12346
$ vim file     # 然后按 Ctrl+Z 暂停
[3]+ Stopped    vim file

$ jobs
[1]   Running                 sleep 100 &
[2]-  Running                 sleep 200 &
[3]+  Stopped                 vim file

# 输出格式：[作业号] 状态标记 作业状态 命令行
# + 号表示"当前作业"（默认的 fg/bg 目标）
# - 号表示"前一个作业"
```

作业号与 PID 的关系：

```bash
$ jobs -l       # -l 选项同时显示 PID
[1]   12345 Running                 sleep 100 &
[2]-  12346 Running                 sleep 200 &
[3]+  12347 Stopped                 vim file
```

#### 1.4.4 fg 命令——将作业切换到前台

```bash
# 基本用法
$ fg %1         # 将作业 1 切换到前台
$ fg %2         # 将作业 2 切换到前台
$ fg            # 将"当前作业"（标有 + 的）切换到前台

# 详细过程：
$ sleep 100 &
[1] 12345
$ fg %1          # 执行过程如下：
                 # 1. Shell 查找作业 1 对应的进程组 (pgid=12345)
                 # 2. 调用 tcsetpgrp() 将该进程组设为前台进程组
                 # 3. 如果作业处于 Stopped 状态，发送 SIGCONT 恢复运行
                 # 4. Shell 调用 waitpid() 等待该作业结束
sleep 100        # 现在 sleep 在前台运行，Shell 等待它
                 # 你需要等 100 秒，或者按 Ctrl+C 终止它
```

#### 1.4.5 bg 命令——将已停止的作业在后台恢复

```bash
$ vim file       # 前台运行 vim
# 按 Ctrl+Z
[1]+ Stopped     vim file

$ bg %1          # 执行过程如下：
                 # 1. Shell 查找作业 1 对应的进程组
                 # 2. 发送 SIGCONT 信号恢复进程运行
                 # 3. Shell 不等待，立即返回提示符
[1]+ vim file &

# 注意：vim 在后台运行时，如果它尝试读取终端输入，
# 会收到 SIGTTIN 信号并再次停止
```

#### 1.4.6 kill 命令——向进程发送信号

```bash
# 语法：kill [-信号] PID 或 kill [-信号] %作业号

# 发送 SIGTERM（默认，请求终止）
$ kill 12345
$ kill %1

# 发送 SIGKILL（强制终止，不可捕获）
$ kill -9 12345
$ kill -SIGKILL 12345
$ kill -KILL 12345

# 发送 SIGSTOP（暂停进程）
$ kill -STOP %1

# 发送 SIGCONT（恢复进程）
$ kill -CONT %1

# 向整个进程组发送信号（PID 为负数）
$ kill -SIGTERM -12345    # 向进程组 12345 的所有进程发送 SIGTERM
```

#### 1.4.7 完整实操示例

```bash
# ===== 示例 1：基本的前台/后台切换 =====

$ sleep 300 &                    # 后台启动
[1] 5001
$ sleep 400 &                    # 再启动一个后台作业
[2] 5002
$ jobs                           # 查看所有作业
[1]-  Running                 sleep 300 &
[2]+  Running                 sleep 400 &
$ fg %1                          # 把作业 1 切到前台
sleep 300
^Z                               # 按 Ctrl+Z 暂停
[1]+  Stopped                 sleep 300
$ jobs                           # 再看
[1]+  Stopped                 sleep 300
[2]-  Running                 sleep 400 &
$ bg %1                          # 在后台恢复作业 1
[1]+ sleep 300 &
$ jobs                           # 两个都在后台运行了
[1]-  Running                 sleep 300 &
[2]+  Running                 sleep 400 &
$ kill %1                        # 终止作业 1
[1]-  Terminated              sleep 300
$ kill -9 %2                     # 强制终止作业 2
[2]+  Killed                  sleep 400

# ===== 示例 2：管道命令作为一个作业 =====

$ cat /dev/urandom | base64 | head -100 > output.txt &
[1] 5010                         # 显示的是管道最后一个命令的 PID
$ jobs -l
[1]+ 5008 Running                 cat /dev/urandom |
     5009                         base64 |
     5010                         head -100 > output.txt &

# ===== 示例 3：理解 Ctrl+C 的作用范围 =====

$ sleep 300                      # 前台运行
^C                               # 按 Ctrl+C
                                 # SIGINT 发送给前台进程组
                                 # sleep 被终止
$ sleep 300 &                    # 后台运行
[1] 5020
$ sleep 400                      # 另一个前台运行
^C                               # 按 Ctrl+C
                                 # 只有 sleep 400 被终止
                                 # sleep 300 在后台不受影响
$ jobs
[1]+  Running                 sleep 300 &
```

### 1.5 Shell 的环境变量与搜索路径

```bash
# Shell 如何找到可执行文件？通过 PATH 环境变量
$ echo $PATH
/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin

# 当你输入 "ls" 时，Shell 会依次在以下目录中查找：
# /usr/local/bin/ls → 不存在
# /usr/bin/ls       → 找到了！执行它

# 如果命令包含 /（路径分隔符），Shell 不搜索 PATH
$ ./myspin 10      # 直接执行当前目录下的 myspin
$ /bin/ls          # 直接执行 /bin/ls

# 在 tsh.c 中，parseline() 将命令行解析为 argv 数组
# 然后 eval() 中用 execve(argv[0], argv, environ) 执行
# 注意：execve 不搜索 PATH！所以 trace 文件中都用了 ./myspin 这样的写法
# 如果想让 Shell 搜索 PATH，需要用 execvp() 而不是 execve()
```

---

## 2. C 语言基础

### 2.1 指针数组

Shell Lab 中最常见的数据结构就是 `char **argv`——一个指针数组，每个元素是一个指向字符串的指针。

```c
// parseline() 返回后，argv 的内存布局：

// 假设命令行是 "/bin/ls -la /home"
// parseline 会将其解析为：

char *argv[MAXARGS];
// argv 在栈上分配（128 个指针的数组）

// 解析后：
// argv[0] → "ls"       （指向某个字符数组）
// argv[1] → "-la"      （指向某个字符数组）
// argv[2] → "/home"    （指向某个字符数组）
// argv[3] → NULL       （标记结束）

// 内存布局图：
//
// 栈上的 argv 数组：
// ┌──────────┬──────────┬──────────┬──────────┬─── ...
// │ argv[0]  │ argv[1]  │ argv[2]  │ argv[3]  │
// │ 0x1000   │ 0x1004   │ 0x100A   │ NULL     │
// └────┬─────┴────┬─────┴────┬─────┴──────────┴─── ...
//      │          │          │
//      ▼          ▼          ▼
//   "ls\0"     "-la\0"   "/home\0"
//   0x1000     0x1004    0x100A
```

为什么 `argv` 的类型是 `char **`（指向指针的指针）？

```c
// char *argv[] 和 char **argv 在函数参数中等价

// 一级指针 char *p：指向一个字符（或字符串的首字符）
// 二级指针 char **pp：指向一个 char* 指针
//                     也就是指向字符串的指针的指针

// 当作为函数参数时，数组退化为指针：
// void foo(char *argv[]) 等价于 void foo(char **argv)

// 使用方式：
void eval(char *cmdline) {
    char *argv[MAXARGS];    // 声明为数组
    parseline(cmdline, argv);  // 传递给函数（数组名退化为指针）
    
    // 访问第一个参数（命令名）：
    printf("Command: %s\n", argv[0]);
    
    // 遍历所有参数：
    for (int i = 0; argv[i] != NULL; i++) {
        printf("argv[%d] = %s\n", i, argv[i]);
    }
}
```

### 2.2 函数指针

Shell Lab 中 `Signal()` 函数和 `handler_t` 类型用到了函数指针：

```c
// 在 tsh.c 中的定义：
typedef void handler_t(int);
// handler_t 是一个函数类型：返回 void，接受一个 int 参数

handler_t *Signal(int signum, handler_t *handler);
// Signal 接受一个信号编号和一个函数指针
// 返回之前的处理函数的指针

// 等价的写法（不用 typedef）：
void (*Signal(int signum, void (*handler)(int)))(int);
// 读法：Signal 是一个函数，接受 int 和函数指针参数，
//       返回一个函数指针（指向接受 int 返回 void 的函数）

// 使用示例：
void sigint_handler(int sig) {
    // 处理 SIGINT
}

// 安装信号处理程序：
Signal(SIGINT, sigint_handler);
// 等价于：
signal(SIGINT, sigint_handler);

// 函数名 sigint_handler 在这里自动退化为函数指针
// 类似数组名退化为指向首元素的指针

// 更复杂的示例——函数指针数组：
typedef void (*handler_func)(int);
handler_func handlers[32];  // 32 个函数指针的数组

handlers[SIGINT] = sigint_handler;
handlers[SIGTSTP] = sigtstp_handler;
handlers[SIGCHLD] = sigchld_handler;

// 调用：
handlers[SIGINT](SIGINT);  // 等价于 sigint_handler(SIGINT);
```

### 2.3 位运算

信号集操作和 `waitpid` 的 options 参数中大量使用位运算：

```c
// ===== 位运算基础 =====

// 按位或 |（设置位）
int options = WNOHANG | WUNTRACED;
// WNOHANG   = 0x00000001  (二进制 0001)
// WUNTRACED = 0x00000002  (二进制 0010)
// 结果      = 0x00000003  (二进制 0011)

// 按位与 &（测试位）
if (options & WNOHANG) {
    // WNOHANG 位被设置了
}

// 按位取反 ~（清除位）
options = options & ~WNOHANG;
// ~WNOHANG = 0xFFFFFFFE  (二进制 ...11111110)
// 结果：清除了 WNOHANG 位

// ===== 在信号集操作中的应用 =====

// sigset_t 本质上是一个位向量（bit vector）
// 每一位对应一个信号编号

// 假设信号集用一个 unsigned long 表示（简化理解）：
// 位 0: 未使用
// 位 1: SIGHUP
// 位 2: SIGINT
// 位 3: SIGQUIT
// ...
// 位 9: SIGKILL
// ...

sigset_t mask;
sigemptyset(&mask);        // mask = 0x0000000000000000
sigaddset(&mask, SIGINT);  // mask = 0x0000000000000004 (位 2 设为 1)
sigaddset(&mask, SIGCHLD); // mask |= (1 << SIGCHLD)

// sigismember(&mask, SIGINT) 本质上是：
// return (mask & (1 << SIGINT)) != 0;

// ===== waitpid 状态宏中的位运算 =====

// waitpid 的 status 参数的位布局（Linux x86-64）：
// 正常退出：高 8 位是退出码，低 8 位全 0
// 信号终止：低 7 位是信号编号，第 8 位是 core dump 标志
// 停止：    高 8 位是停止信号编号，低 8 位是 0x7F

// 这就是为什么有宏：
// WIFEXITED(status)    → ((status) & 0x7F) == 0
// WEXITSTATUS(status)  → ((status) >> 8) & 0xFF
// WIFSIGNALED(status)  → ((status) & 0x7F) != 0 && ((status) & 0x7F) != 0x7F
// WTERMSIG(status)     → (status) & 0x7F
// WIFSTOPPED(status)   → ((status) & 0xFF) == 0x7F
// WSTOPSIG(status)     → ((status) >> 8) & 0xFF
```

### 2.4 结构体

Shell Lab 中的作业列表使用结构体数组：

```c
// tsh.c 中的定义：
struct job_t {
    pid_t pid;              // 进程 ID
    int jid;                // 作业 ID [1, 2, ...]
    int state;              // UNDEF, BG, FG, or ST
    char cmdline[MAXLINE];  // 命令行字符串
};

struct job_t jobs[MAXJOBS]; // 作业列表（全局数组，最多 16 个作业）

// 内存布局：
// jobs[0]: │ pid │ jid │ state │ cmdline[1024]      │
// jobs[1]: │ pid │ jid │ state │ cmdline[1024]      │
// ...
// jobs[15]:│ pid │ jid │ state │ cmdline[1024]      │

// 结构体指针访问成员：
struct job_t *job = getjobpid(jobs, some_pid);
if (job != NULL) {
    printf("Job [%d] (%d) %s\n", job->jid, job->pid, job->cmdline);
    // job->jid 等价于 (*job).jid
}

// 在 Shell Lab 中，对作业列表的操作：
// addjob(jobs, pid, state, cmdline)  — 添加作业
// deletejob(jobs, pid)               — 删除作业
// getjobpid(jobs, pid)               — 按 PID 查找
// getjobjid(jobs, jid)               — 按 JID 查找
// fgpid(jobs)                        — 获取前台作业的 PID
// listjobs(jobs)                     — 打印所有作业
```

### 2.5 全局变量与 extern

```c
// ===== tsh.c 中的全局变量 =====

extern char **environ;      // 环境变量数组，在 libc 中定义
                            // extern 声明告诉编译器：这个变量在别处定义
                            // 不要在这里分配存储空间

char prompt[] = "tsh> ";    // Shell 提示符
int verbose = 0;            // 调试模式开关
int nextjid = 1;            // 下一个可用的作业 ID
char sbuf[MAXLINE];         // 临时字符串缓冲区
struct job_t jobs[MAXJOBS]; // 作业列表

// ===== extern 的本质 =====

// 不加 extern 的全局变量声明 → 定义（分配存储空间）
int verbose = 0;  // 定义：分配 4 字节，初始化为 0

// 加 extern 的声明 → 引用（不分配空间，指向别处的定义）
extern char **environ;  // 声明：environ 在 libc.so 中定义

// 为什么 environ 需要 extern？
// 因为 environ 是 C 运行时库（libc）中的一个全局变量，
// 它指向进程的环境变量数组。
// libc 在 exec 系统调用时会初始化这个变量。
// 我们的 tsh.c 需要使用它，但不应该重新定义它。

// ===== 全局变量在信号处理中的注意事项 =====

// 全局变量在主程序和信号处理程序之间共享！
// 这是信号处理编程中许多 Bug 的根源。
// 详见后文"竞争条件"部分。

volatile sig_atomic_t pid;
// volatile：告诉编译器不要优化对这个变量的访问
// sig_atomic_t：保证单次读写是原子的
```

---

## 3. 虚拟内存基础概念

### 3.1 为什么需要虚拟内存

```
问题：如果所有进程直接使用物理内存地址，会发生什么？

进程 A 使用物理地址 0x1000-0x2000
进程 B 也想使用物理地址 0x1000-0x2000
→ 冲突！一个进程可能覆盖另一个进程的数据

解决方案：每个进程有自己独立的"虚拟地址空间"

进程 A 的虚拟地址 0x1000 → 物理地址 0x5000（通过地址翻译）
进程 B 的虚拟地址 0x1000 → 物理地址 0x8000（通过地址翻译）
→ 没有冲突！
```

### 3.2 MMU（Memory Management Unit）

```
       CPU                                    主存储器
   ┌─────────┐                            ┌──────────────┐
   │         │    虚拟地址(VA)              │              │
   │  CPU    │──────────────▶ ┌──────┐    │   物理内存     │
   │  核心   │               │ MMU  │    │              │
   │         │◀──────────────│      │───▶│              │
   │         │   数据         │      │    │              │
   └─────────┘               └──┬───┘    └──────────────┘
                                │  物理地址(PA)
                                │
                           ┌────▼─────┐
                           │  页表     │
                           │(在内存中) │
                           └──────────┘

MMU 是 CPU 内部的硬件模块，它的工作是：
虚拟地址 (VA) → 物理地址 (PA)

这个翻译过程叫做"地址翻译"（address translation）。
翻译的依据是操作系统维护的"页表"（page table）。
```

### 3.3 页表 (Page Table) 与 PTE (Page Table Entry)

```
虚拟地址空间被分割成固定大小的"页"（page），
物理内存也被分割成同样大小的"页帧"（page frame/physical page）。
x86-64 上，页大小通常是 4KB (2^12 = 4096 字节)。

虚拟地址的分解：
┌──────────────────────────────┬─────────────┐
│      虚拟页号 (VPN)           │  页内偏移     │
│      Virtual Page Number      │  Offset      │
│      52 位                    │  12 位        │
└──────────────────────────────┴─────────────┘

页表的结构（简化为一级页表）：
┌───────┬──────────────────────────────────┐
│  VPN  │  PTE (Page Table Entry)           │
│  索引  │                                   │
├───────┼──────────────────────────────────┤
│   0   │  PPN=0x1234 | 有效位=1 | 读写权限  │
├───────┼──────────────────────────────────┤
│   1   │  磁盘地址   | 有效位=0 (不在内存) │  ← 缺页！
├───────┼──────────────────────────────────┤
│   2   │  PPN=0x5678 | 有效位=1 | 只读     │
├───────┼──────────────────────────────────┤
│  ...  │  ...                              │
└───────┴──────────────────────────────────┘

PTE 的结构（x86-64，64位）：
┌──────┬───┬───┬───┬───┬───┬───────────────────┐
│ 保留  │ NX│   │ D │ A │ U/S│  物理页帧号 (PPN)   │
│      │   │   │   │   │ R/W│                    │
│      │   │   │   │   │ P  │                    │
└──────┴───┴───┴───┴───┴───┴───────────────────┘

P (Present) = 1：页在物理内存中
P = 0：页不在内存（在磁盘上，或未分配）→ 访问触发缺页故障
R/W：读写权限（0=只读，1=可读写）
U/S：用户/超级用户权限（0=仅内核可访问）
A (Accessed)：是否被访问过
D (Dirty)：是否被修改过
NX (No Execute)：是否可执行
```

### 3.4 TLB（Translation Lookaside Buffer）

```
每次内存访问都需要查页表？太慢了！（额外一次甚至多次内存访问）

解决方案：TLB — 页表的硬件缓存

┌─────────┐     ┌──────────────────┐     ┌──────────┐
│  CPU    │ VA  │       TLB        │ PA  │          │
│  核心   │────▶│  (通常 64~1024   │────▶│  物理内存 │
│         │     │   条目的快速缓存) │     │          │
│         │     │                  │     │          │
└─────────┘     └────────┬─────────┘     └──────────┘
                         │
                    TLB Miss!
                         │
                    ┌────▼─────┐
                    │ 查页表    │
                    │(内存访问) │
                    └──────────┘

TLB Hit：在 TLB 中找到了 VPN 对应的 PPN → 1 个时钟周期
TLB Miss：未找到 → 需要访问内存中的页表 → 几十到几百个时钟周期
```

---

## 4. 操作系统基本概念

### 4.1 用户态与内核态

```
┌──────────────────────────────────────────────────────┐
│                     内核态 (Ring 0)                    │
│                                                       │
│  ■ 可以执行所有指令（包括特权指令）                       │
│  ■ 可以访问所有内存（包括内核空间）                       │
│  ■ 可以直接操作硬件（I/O 端口、中断控制器等）              │
│  ■ 运行内核代码、设备驱动、中断处理程序                    │
│                                                       │
│  进入方式：异常（中断/陷阱/故障/终止）                    │
│  退出方式：异常返回指令（iret）                          │
│                                                       │
├──────────────────────────────────────────────────────┤
│                     用户态 (Ring 3)                    │
│                                                       │
│  ■ 只能执行非特权指令                                   │
│  ■ 只能访问用户空间的内存                                │
│  ■ 不能直接操作硬件                                     │
│  ■ 运行应用程序代码（Shell、ls、gcc 等）                  │
│                                                       │
│  需要内核服务时：通过系统调用（syscall 指令）陷入内核态     │
│                                                       │
└──────────────────────────────────────────────────────┘

x86-64 的特权级由 CS 段寄存器的低 2 位（CPL，Current Privilege Level）表示：
  CPL = 0 → 内核态（Ring 0）
  CPL = 3 → 用户态（Ring 3）
  Ring 1 和 Ring 2 通常不使用
```

### 4.2 进程

```
进程 = 正在运行的程序的实例

每个进程拥有：
1. 独立的虚拟地址空间（通过页表隔离）
2. 独立的文件描述符表
3. 独立的信号处理设置
4. 独立的 PID、进程组 ID、会话 ID
5. 独立的用户 ID、组 ID

进程在内核中的表示：task_struct（Linux）

struct task_struct {
    pid_t pid;                    // 进程 ID
    pid_t tgid;                   // 线程组 ID
    volatile long state;          // 进程状态
    struct mm_struct *mm;         // 内存描述符（页表等）
    struct files_struct *files;   // 文件描述符表
    struct signal_struct *signal; // 信号相关
    struct sighand_struct *sighand; // 信号处理函数
    // ... 几百个字段
};
```

### 4.3 文件描述符

```
文件描述符（File Descriptor, fd）是一个非负整数，
是进程访问 I/O 资源的"句柄"。

每个进程有一个文件描述符表：

进程的文件描述符表：
┌────┬─────────────────────────────┐
│ fd │  指向的内核对象               │
├────┼─────────────────────────────┤
│  0 │ → stdin  (标准输入，通常是终端) │
│  1 │ → stdout (标准输出，通常是终端) │
│  2 │ → stderr (标准错误，通常是终端) │
│  3 │ → 打开的文件/套接字/管道        │
│  4 │ → ...                        │
│ .. │                              │
└────┴─────────────────────────────┘

tsh.c 中的关键代码：
  dup2(1, 2);  // 把 stderr 重定向到 stdout
               // 这样 Shell 的所有输出（包括错误信息）
               // 都通过 stdout 发送
               // sdriver.pl 测试驱动只连接了 stdout 管道

文件描述符的生命周期：
  open()  / socket() / pipe() → 创建，返回最小可用的 fd
  read()  / write()           → 使用
  close()                     → 关闭，释放 fd 供复用
  dup2(oldfd, newfd)          → 复制，newfd 指向 oldfd 的相同对象
```

### 4.4 进程状态转换

```
Linux 进程的状态：

┌──────────┐   fork()    ┌──────────┐
│ 不存在    │ ──────────▶ │ 就绪/可运行 │
│          │             │ TASK_RUNNING│ ◀──────────────────────┐
└──────────┘             │ (在就绪队列) │                        │
                         └──────┬─────┘                        │
                                │                              │
                           调度器选中                          时间片用完
                           (schedule)                     或被抢占
                                │                              │
                                ▼                              │
                         ┌──────────────┐                      │
                         │ 正在运行       │ ─────────────────────┘
                         │ TASK_RUNNING  │
                         │ (在 CPU 上)   │
                         └──────┬───────┘
                                │
                   ┌────────────┼────────────┐
                   │            │            │
              等待 I/O      收到信号       exit()
              或资源      (如 SIGSTOP)        │
                   │            │            │
                   ▼            ▼            ▼
            ┌────────────┐ ┌──────────┐ ┌──────────┐
            │ 睡眠/等待    │ │ 已停止    │ │ 僵尸      │
            │ TASK_       │ │ TASK_    │ │ EXIT_    │
            │ INTERRUPTIBLE│ │ STOPPED │ │ ZOMBIE   │
            │ 或 TASK_     │ │         │ │          │
            │ UNINTERRUPT. │ │         │ │ 等待父进程 │
            └──────┬─────┘ └────┬─────┘ │ wait()   │
                   │            │        └────┬─────┘
              I/O 完成      SIGCONT           │
              或事件到来     恢复              父进程 wait()
                   │            │              │
                   └────────────┘              ▼
                        │              ┌──────────┐
                        ▼              │ 已终止    │
                   回到就绪队列         │(彻底消失) │
                                       └──────────┘
```

---

# 第二大块：跳步内容深度补充

---

## 专题 1：DMA 传输

### 1.1 概念：什么是 DMA

DMA（Direct Memory Access，直接内存访问）是一种硬件机制，允许 I/O 设备**不经过 CPU** 直接与主存储器之间传输数据。

```
没有 DMA 的 I/O（程序化 I/O，PIO）：

   CPU                主存
    │                  │
    ├─ 读磁盘数据 ──▶  │    ① CPU 从磁盘控制器读取 1 字节/字
    │  写入内存   ──▶  │    ② CPU 将该字节写入内存
    ├─ 读磁盘数据 ──▶  │    ③ 重复... 直到所有数据传完
    │  写入内存   ──▶  │    
    │  ...             │    问题：CPU 全程忙于搬运数据，无法做其他事！
    
有 DMA 的 I/O：

   CPU         DMA 引擎         主存         磁盘
    │             │               │            │
    ├─ 配置 DMA ──▶              │            │
    │  (源地址、目的地址、长度)    │            │
    │                             │            │
    │  CPU 去做别的事了...         │            │
    │                             │            │
    │             ├── 传输数据 ───▶│◀───────────┤
    │             ├── 传输数据 ───▶│◀───────────┤
    │             ├── 传输数据 ───▶│◀───────────┤
    │             │   ...         │            │
    │             │               │            │
    │◀── 中断 ────┤  传输完成！    │            │
    │             │               │            │
    ├─ 处理中断 ──▶              │            │
```

### 1.2 DMA 引擎的工作原理

DMA 引擎（也叫 DMA 控制器）是一个专用的硬件模块，通常集成在芯片组（南桥/PCH）或 SoC 中。

```
DMA 引擎的内部结构（简化）：

┌──────────────────────────────────────────────┐
│              DMA 控制器                        │
│                                               │
│  ┌─────────────┐  ┌─────────────┐            │
│  │  通道 0       │  │  通道 1       │ ...       │
│  │  (Channel 0) │  │  (Channel 1) │            │
│  │              │  │              │            │
│  │ 源地址寄存器  │
│  │ 目的地址寄存器│  │ 目的地址寄存器│            │
│  │ 传输计数器   │  │ 传输计数器   │            │
│  │ 控制/状态    │  │ 控制/状态    │            │
│  └─────────────┘  └─────────────┘            │
│                                               │
│  ┌──────────────────────┐                     │
│  │  总线仲裁逻辑          │                     │
│  │  (Bus Arbiter)        │                     │
│  └──────────────────────┘                     │
└──────────────────────────────────────────────┘
```

**DMA 传输的完整步骤**：

```
1. CPU 配置 DMA 通道：
   - 设置源地址（如磁盘控制器的数据寄存器地址）
   - 设置目的地址（内存中的缓冲区地址）
   - 设置传输长度（要传多少字节）
   - 设置传输方向（设备→内存 或 内存→设备）
   - 启动 DMA 传输

2. DMA 引擎接管总线控制权：
   - DMA 引擎向总线仲裁器请求总线控制权
   - 获得控制权后，DMA 引擎成为"总线主控"（bus master）
   - CPU 暂时释放总线（但 CPU 仍可使用 L1/L2 缓存中的数据）

3. DMA 引擎执行数据传输：
   - 每个总线周期传输一个字（或一个突发块）
   - 从源地址读数据，写到目的地址
   - 每传完一个字，自动递增地址，递减计数器

4. 传输完成：
   - 计数器减到 0
   - DMA 引擎释放总线控制权
   - DMA 引擎向 CPU 发送中断
   - CPU 的中断处理程序被触发
```

### 1.3 DMA 通道

现代系统通常有多个 DMA 通道，每个通道可以独立工作，服务不同的设备：

```
典型的 DMA 通道分配（简化示例）：

通道 0：磁盘控制器
通道 1：网络接口卡 (NIC) 接收
通道 2：网络接口卡 (NIC) 发送
通道 3：声卡
通道 4：级联（连接到第二个 DMA 控制器）
通道 5：SSD/NVMe 控制器
...

多个通道可以同时工作（通过时分复用总线），
实现真正的并行 I/O。
```

### 1.4 Scatter-Gather DMA

传统 DMA 要求源和目的内存区域是连续的，但实际应用中，数据往往分散在多个不连续的内存块中（比如通过 malloc 分配的缓冲区，或者虚拟地址连续但物理地址不连续的页）。

```
传统 DMA（连续内存）：
  源：磁盘
  目的：内存 0x1000 ~ 0x3000（连续的 8KB）
  → 一次 DMA 操作即可

Scatter-Gather DMA（分散/聚集 DMA）：
  源：磁盘
  目的：多个不连续的内存块
    块 1: 0x1000 ~ 0x1FFF (4KB)
    块 2: 0x5000 ~ 0x5FFF (4KB)   ← 不连续！
    块 3: 0x9000 ~ 0x93FF (1KB)

  实现方式：DMA 引擎使用一个"描述符链表"（descriptor list）

  ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
  │ 描述符 1       │────▶│ 描述符 2       │────▶│ 描述符 3       │──▶ NULL
  │ addr=0x1000   │     │ addr=0x5000   │     │ addr=0x9000   │
  │ len =4096     │     │ len =4096     │     │ len =1024     │
  │ next=desc2    │     │ next=desc3    │     │ next=NULL     │
  └──────────────┘     └──────────────┘     └──────────────┘

  DMA 引擎从描述符链表中逐个读取描述符，
  按每个描述符指定的地址和长度执行传输。
  全部完成后才发出中断。
```

### 1.5 DMA 与中断的配合

```
完整的磁盘读操作流程（DMA + 中断）：

   用户程序          内核              DMA 引擎       磁盘
      │                │                │              │
   read(fd,...) ──▶  │                │              │
      │             设置 DMA ─────────▶│              │
      │             参数              │              │
      │                │              │              │
      │             进程 A            │   数据传输    │
      │             进入睡眠           │ ◀────────────┤
      │                │              │              │
      │             切换到进程 B       │ ──▶ 内存      │
      │             (CPU 做别的事)     │              │
      │                │              │              │
      │                │         传输完成！           │
      │                │              │              │
      │            ◀── 中断 ──────────┤              │
      │                │              │              │
      │             中断处理程序       │              │
      │             唤醒进程 A         │              │
      │                │              │              │
      │             切换回进程 A       │              │
      │                │              │              │
   read() 返回 ◀──── │              │              │
      │                │              │              │
```

### 1.6 代码示例：理解 DMA 概念

```c
// 虽然用户态程序不直接操作 DMA，但可以通过以下方式
// 间接感受 DMA 的存在：

#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/time.h>

int main() {
    char buf[1024 * 1024]; // 1MB 缓冲区
    int fd = open("/dev/sda", O_RDONLY); // 打开磁盘设备
    
    struct timeval start, end;
    gettimeofday(&start, NULL);
    
    // 这个 read() 系统调用最终会：
    // 1. 内核设置 DMA 传输（磁盘 → 内核缓冲区）
    // 2. 当前进程被挂起（sleeping）
    // 3. DMA 引擎在后台传输数据
    // 4. 传输完成后，磁盘控制器发送中断
    // 5. 中断处理程序唤醒进程
    // 6. 内核将数据从内核缓冲区复制到用户缓冲区
    // 7. read() 返回
    ssize_t n = read(fd, buf, sizeof(buf));
    
    gettimeofday(&end, NULL);
    printf("Read %zd bytes in %ld microseconds\n", 
           n, (end.tv_sec - start.tv_sec) * 1000000 + 
              (end.tv_usec - start.tv_usec));
    
    close(fd);
    return 0;
}
```

### 1.7 误区

| 误区 | 正确理解 |
|------|----------|
| DMA 传输时 CPU 完全空闲 | CPU 可以执行其他进程的代码，只是不能使用总线（但可以用缓存）|
| DMA 只用于磁盘 | DMA 用于所有高速 I/O：网卡、显卡、声卡、USB 控制器等 |
| DMA 不需要 CPU 参与 | CPU 需要设置 DMA 参数和处理完成中断 |
| DMA 传输是原子的 | DMA 传输需要多个总线周期，可能被中断 |

---

## 专题 2：前台进程组与后台进程组

### 2.1 进程组 (Process Group)

进程组是一个或多个进程的集合，由进程组 ID (PGID) 标识。进程组的主要用途是**信号分发**——可以一次性向整个进程组的所有成员发送信号。

```c
// 获取进程组 ID
pid_t getpgrp(void);          // 返回调用进程的 PGID
pid_t getpgid(pid_t pid);     // 返回指定进程的 PGID

// 设置进程组 ID
int setpgid(pid_t pid, pid_t pgid);
// 将进程 pid 的进程组设为 pgid
// setpgid(0, 0) 等价于 setpgid(getpid(), getpid())
// → 让当前进程成为一个新进程组的组长
```

### 2.2 会话 (Session) 与控制终端的关系

```
┌──────────────────────────────────────────────────────────────┐
│                    会话 (Session, SID=1000)                    │
│                    会话领导进程: login shell (pid=1000)        │
│                    控制终端: /dev/pts/0                        │
│                                                               │
│  ┌──────────────────────────┐                                │
│  │ 前台进程组 (PGID=2000)    │  ◀── 终端驱动将键盘信号发给这个组 │
│  │                           │                                │
│  │  vim file (pid=2000)     │  ← 进程组组长                   │
│  └──────────────────────────┘                                │
│                                                               │
│  ┌──────────────────────────┐                                │
│  │ 后台进程组 1 (PGID=3000)  │                                │
│  │                           │                                │
│  │  make (pid=3000)         │  ← 进程组组长                   │
│  │  gcc (pid=3001)          │                                │
│  │  as (pid=3002)           │                                │
│  └──────────────────────────┘                                │
│                                                               │
│  ┌──────────────────────────┐                                │
│  │ 后台进程组 2 (PGID=4000)  │                                │
│  │                           │                                │
│  │  sleep 1000 (pid=4000)   │                                │
│  └──────────────────────────┘                                │
│                                                               │
│  ┌──────────────────────────┐                                │
│  │ Shell 进程组 (PGID=1000)  │  ← Shell 自己也是一个进程组      │
│  │                           │                                │
│  │  bash (pid=1000)         │  ← 既是会话领导，也是组长        │
│  └──────────────────────────┘                                │
└──────────────────────────────────────────────────────────────┘

关键规则：
1. 一个会话最多有一个控制终端
2. 一个会话中，同一时刻最多有一个前台进程组
3. 键盘信号 (SIGINT/SIGTSTP/SIGQUIT) 发送给前台进程组的所有成员
4. 后台进程组的进程尝试读终端时，收到 SIGTTIN 并暂停
5. 后台进程组的进程尝试写终端时（取决于设置），可能收到 SIGTTOU
```

### 2.3 终端驱动程序的信号管理

```
当用户按 Ctrl+C 时：

键盘 → 中断 → 键盘驱动 → 终端行规程 (line discipline)
                                │
                                │ 识别到 Ctrl+C 是 INTR 字符
                                │
                                ▼
                         获取前台进程组 ID
                         pgid = tcgetpgrp(终端 fd)
                                │
                                ▼
                         kill(-pgid, SIGINT)
                         // 负数 PID：向进程组所有成员发信号
                                │
                                ▼
                         前台进程组的每个进程都收到 SIGINT
```

### 2.4 tcsetpgrp 和 tcgetpgrp

```c
#include <unistd.h>

// 获取与终端关联的前台进程组 ID
pid_t tcgetpgrp(int fd);
// fd: 终端的文件描述符（如 STDIN_FILENO）
// 返回：前台进程组的 PGID

// 设置终端的前台进程组
int tcsetpgrp(int fd, pid_t pgrp);
// fd: 终端的文件描述符
// pgrp: 要设为前台的进程组 ID
// 返回：成功 0，失败 -1

// 使用示例：Shell 将子进程的进程组设为前台
void make_foreground(pid_t child_pgid) {
    // 将 child_pgid 设为终端的前台进程组
    tcsetpgrp(STDIN_FILENO, child_pgid);
}

// Shell 回收前台控制权
void reclaim_terminal() {
    // 将 Shell 自己的进程组设为前台
    tcsetpgrp(STDIN_FILENO, getpgrp());
}
```

### 2.5 误区

| 误区 | 正确理解 |
|------|----------|
| Ctrl+C 只杀一个进程 | Ctrl+C 向前台进程组的所有进程发送 SIGINT |
| 进程组 = 会话 | 一个会话包含多个进程组 |
| Shell 是前台进程组 | 当子进程在前台运行时，Shell 不在前台进程组中（在 tsh 简化实现中 Shell 始终在前台进程组中，因为它没有调用 tcsetpgrp）|

---

## 专题 3：setpgid(0,0) 的必要性

### 3.1 为什么子进程需要自己的进程组

在 Shell Lab 中，子进程调用 `setpgid(0, 0)` 将自己放入一个新的进程组（以自己的 PID 为 PGID）。不这样做会导致严重问题。

```
不调用 setpgid(0,0) 的情况：

   Shell (pid=1000, pgid=1000)
     │
     ├─ fork() → 子进程 (pid=2001, pgid=1000) ← 和 Shell 同组！
     │
     └─ 用户按 Ctrl+C
            │
            ▼
        SIGINT 发送给 pgid=1000 的所有进程
            │
            ├─▶ Shell (pid=1000) 收到 SIGINT → Shell 被杀死！
            └─▶ 子进程 (pid=2001) 收到 SIGINT → 子进程被杀死

   问题：Shell 本身也被杀死了！
   用户按 Ctrl+C 只是想终止正在运行的程序，
   而不是终止 Shell 本身。
```

```
调用 setpgid(0,0) 后：

   Shell (pid=1000, pgid=1000)
     │
     ├─ fork() → 子进程 (pid=2001, pgid=2001) ← 自己的进程组！
     │             setpgid(0, 0) 使 pgid 变为 2001
     │
     └─ 用户按 Ctrl+C
            │
            ▼
        SIGINT 发送给前台进程组（假设是 pgid=2001）
            │
            └─▶ 子进程 (pid=2001) 收到 SIGINT → 子进程被杀死
                Shell (pid=1000) 不受影响 → Shell 继续运行
```

### 3.2 在 Shell Lab 中的应用

```c
void eval(char *cmdline) {
    char *argv[MAXARGS];
    int bg = parseline(cmdline, argv);
    
    if (argv[0] == NULL) return;  // 空行
    
    if (!builtin_cmd(argv)) {
        sigset_t mask, prev_mask;
        sigemptyset(&mask);
        sigaddset(&mask, SIGCHLD);
        
        // 在 fork 前阻塞 SIGCHLD
        sigprocmask(SIG_BLOCK, &mask, &prev_mask);
        
        pid_t pid = fork();
        
        if (pid == 0) {
            // ===== 子进程 =====
            
            // 恢复信号掩码
            sigprocmask(SIG_SETMASK, &prev_mask, NULL);
            
            // 关键！将子进程放入新的进程组
            setpgid(0, 0);
            
            // 执行程序
            if (execve(argv[0], argv, environ) < 0) {
                printf("%s: Command not found\n", argv[0]);
                exit(1);
            }
        }
        
        // ===== 父进程 (Shell) =====
        
        // 父进程也调用 setpgid，防止竞争条件
        setpgid(pid, pid);  // 等价于子进程的 setpgid(0,0)
        
        // 添加作业
        addjob(jobs, pid, bg ? BG : FG, cmdline);
        
        // 恢复信号掩码
        sigprocmask(SIG_SETMASK, &prev_mask, NULL);
        
        if (!bg) {
            waitfg(pid);
        } else {
            printf("[%d] (%d) %s", pid2jid(pid), pid, cmdline);
        }
    }
}
```

### 3.3 竞争条件分析

为什么父进程和子进程都需要调用 `setpgid`？

```
场景：只在子进程中调用 setpgid(0,0)

   Shell (父进程)                   子进程
      │                               │
   fork() ──────────────────────▶  被创建 (pgid=Shell的pgid)
      │                               │
      │                               │ ← 子进程还没来得及执行 setpgid
      │                               │
   sigprocmask(恢复)                   │
   addjob(...)                        │
      │                               │
   如果此时用户按 Ctrl+C，              │
   Shell 发送 kill(-pid, SIGINT)       │
   此时子进程的 pgid 还没改！           │
   → 信号发送失败（pid 对应的进程组      │
     可能不存在或不是我们期望的）        │
                                       │
                                    setpgid(0, 0)  ← 太晚了！

解决方案：父进程也调用 setpgid(pid, pid)
  → 无论谁先执行到这一步，都能确保子进程在新的进程组中
  → 第二次调用是无害的（已经在目标进程组中了）
```

### 3.4 练习

```
思考题：
1. 如果一个 Shell 启动了一个管道命令 "cat file | grep pattern"，
   应该怎样设置进程组？
   答：两个子进程应该在同一个进程组中，
   PGID 通常设为第一个子进程的 PID。
   这样 Ctrl+C 可以同时终止管道中的所有进程。

2. setpgid(0, 0) 中的两个 0 分别代表什么？
   答：第一个 0 = getpid()（指当前进程自己），
       第二个 0 = getpid()（进程组 ID 设为自己的 PID）。
   所以 setpgid(0, 0) = 让自己成为新进程组的组长。
```

---

## 专题 4：MMU / 页表 / PTE 详解

### 4.1 地址翻译的简化流程

```
虚拟地址 (VA) 的构成（x86-64 四级页表）：

 63     48 47     39 38     30 29     21 20     12 11        0
┌────────┬─────────┬─────────┬─────────┬─────────┬──────────┐
│ 符号扩展 │  PML4    │  PDPT    │   PD     │   PT     │  偏移    │
│ (未使用) │  索引    │  索引    │  索引    │  索引    │ (Offset) │
│ 16 位   │  9 位    │  9 位    │  9 位    │  9 位    │  12 位   │
└────────┴─────────┴─────────┴─────────┴─────────┴──────────┘

四级页表的翻译过程：

  CR3 寄存器 ─────▶ PML4 表的物理基地址
                         │
                    PML4[VPN_4] ─────▶ PDPT 表的物理基地址
                                           │
                                      PDPT[VPN_3] ─────▶ PD 表的物理基地址
                                                              │
                                                         PD[VPN_2] ─────▶ PT 表的物理基地址
                                                                              │
                                                                         PT[VPN_1] ─────▶ 物理页帧号 (PPN)
                                                                                              │
                                                                                         PPN + Offset = 物理地址

每一级查表都需要一次内存访问！
4 级页表 → 最多需要 4 次内存访问来翻译一个地址
（这就是为什么 TLB 如此重要）
```

### 4.2 CR3 寄存器

```
CR3（Control Register 3）是 x86-64 的一个控制寄存器，
存储当前进程的顶级页表（PML4）的物理基地址。

每个进程有自己的页表树 → 每个进程有不同的 CR3 值

上下文切换时：
1. 保存当前进程的 CR3 值到 task_struct
2. 加载新进程的 CR3 值
3. TLB 被刷新（因为页表变了）

CR3 的结构（简化）：
┌──────────────────────────────────────────────────┐
│  PML4 物理基地址（对齐到 4KB 边界）   │ PCID │ 标志 │
│  高 52 位                            │ 12位 │      │
└──────────────────────────────────────────────────┘

注意：CR3 是特权寄存器，只能在内核态（Ring 0）访问。
用户态程序不能直接读写 CR3。
```

### 4.3 缺页故障 (Page Fault) 的完整处理流程

```
  CPU 执行 mov (%rax), %rbx    （rax 指向虚拟地址 0x12345000）
       │
       ▼
  MMU 查 TLB → Miss
  MMU 查页表 → PTE 的 Present 位 = 0（页不在内存中）
       │
       ▼
  MMU 触发 Page Fault 异常（异常号 14）
       │
       ▼
  CPU 切换到内核态，跳转到缺页处理程序
       │
       ▼
  内核的缺页处理程序分析故障原因：
  ┌─────────────────────────────────────────────────┐
  │  检查虚拟地址 0x12345000 是否合法                   │
  │  （是否在进程的某个 VMA 范围内）                    │
  │                                                    │
  │  if 地址不合法 → 发送 SIGSEGV（段错误）给进程       │
  │  if 权限不足   → 发送 SIGSEGV 给进程                │
  │  if 地址合法但页不在内存：                           │
  │    1. 在物理内存中分配一个空闲页帧                   │
  │    2. 如果页在交换区 → 从磁盘读入                   │
  │       如果是匿名映射 → 分配零页                     │
  │       如果是文件映射 → 从文件读入                   │
  │    3. 更新 PTE：设置 PPN，设置 Present=1            │
  │    4. 刷新 TLB 中对应的条目                         │
  │    5. 返回用户态，重新执行触发故障的指令              │
  └─────────────────────────────────────────────────┘
```

---

## 专题 5：TLB 与 ASID/PCID

### 5.1 TLB 的基本结构

```
TLB（Translation Lookaside Buffer）是页表的高速缓存。

TLB 条目的结构：
┌──────┬──────┬──────┬──────────────────────┐
│ Valid│ ASID │ VPN  │  PPN + 权限位          │
│  位  │      │      │  (物理页帧号+权限)      │
└──────┴──────┴──────┴──────────────────────┘

TLB 的组织方式（通常是组相联缓存）：

  VPN 的低几位 → 选择组 (set)
  VPN 的高几位 → 标记 (tag)

  示例：4 路组相联 TLB，64 组
  ┌──────┬──────────────────────────────────────────────┐
  │ Set 0│ Way0 │ Way1 │ Way2 │ Way3 │                   │
  │      │ tag|PPN│ tag|PPN│ tag|PPN│ tag|PPN│            │
  ├──────┼──────────────────────────────────────────────┤
  │ Set 1│ ...                                           │
  ├──────┼──────────────────────────────────────────────┤
  │ ...  │ ...                                           │
  ├──────┼──────────────────────────────────────────────┤
  │Set 63│ ...                                           │
  └──────┴──────────────────────────────────────────────┘

  总条目数 = 64 组 x 4 路 = 256 条目
```

### 5.2 上下文切换时的 TLB 管理

```
问题：进程 A 的虚拟地址 0x1000 映射到物理地址 0x5000，
      进程 B 的虚拟地址 0x1000 映射到物理地址 0x8000。
      上下文切换后，TLB 中 A 的映射对 B 来说是错误的！

方案 1：刷新整个 TLB（Flush TLB）
  - 每次上下文切换时，将 TLB 的所有条目标记为无效
  - 简单但代价高：切换后的第一段时间，每次内存访问都是 TLB miss
  - 早期 x86 处理器使用此方案（修改 CR3 自动刷新 TLB）

方案 2：使用 ASID/PCID 标记
  - 每个 TLB 条目附带一个 ASID（Address Space ID）标签
  - ASID 标识该条目属于哪个进程
  - 查找 TLB 时，不仅比较 VPN，还比较 ASID
  - 上下文切换时，只需更改当前 ASID，不用刷新 TLB
  - 不同进程的 TLB 条目可以共存！

  ┌──────┬──────┬────────┬──────────────────┐
  │ Valid│ ASID │  VPN   │  PPN + 权限       │
  ├──────┼──────┼────────┼──────────────────┤
  │  1   │  A   │ 0x001  │  0x005 (进程 A)   │
  │  1   │  B   │ 0x001  │  0x008 (进程 B)   │  ← 同一 VPN，不冲突！
  │  1   │  A   │ 0x002  │  0x010 (进程 A)   │
  │  1   │  B   │ 0x003  │  0x020 (进程 B)   │
  └──────┴──────┴────────┴──────────────────┘
  
  当切换到进程 B 时：
  设置当前 ASID = B
  TLB 中 ASID=A 的条目不会被匹配，无需刷新
```

### 5.3 PCID（Process Context Identifier）

```
PCID 是 x86-64 上 ASID 的具体实现，从 Intel Westmere 开始支持。

- PCID 占 12 位，最多 4096 个不同的值
- 存储在 CR3 寄存器的低 12 位
- 通过 CR4.PCIDE 位启用

启用 PCID 后：
- 修改 CR3 时可以设置"不刷新 TLB"标志
- 内核为每个进程分配一个 PCID 值
- 上下文切换时，只需加载新的 CR3（含新 PCID），TLB 中旧的条目仍然有效

性能提升：
- 减少了上下文切换后的 TLB miss 率
- 对于频繁切换的工作负载（如 Web 服务器），性能提升显著

// Linux 内核中的 PCID 管理（简化）
// 内核为每个 CPU 维护一个 PCID 分配表
// 当 PCID 用完时（超过 4096 个进程），需要回收和刷新
```

---

## 专题 6：ASLR

### 6.1 什么是 ASLR

ASLR（Address Space Layout Randomization，地址空间布局随机化）是一种安全机制，每次运行程序时，将关键内存区域的基地址随机化，使攻击者难以预测代码和数据的位置。

```
没有 ASLR（固定布局）：
  每次运行 /bin/ls，栈总是从 0x7FFFFFFFFFFF 开始
  攻击者知道地址 → 可以构造精确的攻击 payload

有 ASLR（随机布局）：
  第 1 次运行：栈从 0x7FFD12340000 开始
  第 2 次运行：栈从 0x7FFE98760000 开始
  第 3 次运行：栈从 0x7FFC45670000 开始
  攻击者无法预测地址 → 攻击难度大幅增加
```

### 6.2 ASLR 随机化的区域

```
进程地址空间布局（启用 ASLR）：

┌─────────────────────────┐ 0x7FFFFFFFFFFF
│        内核空间           │ ← 用户不可访问
├─────────────────────────┤ 
│          栈 ↓            │ ← 随机化！(每次运行不同)
│   ┌───────────────┐     │
│   │ main() 的栈帧  │     │
│   └───────────────┘     │
│          ...             │
├── ↕ 随机间隙 ────────────┤ ← 随机化！
│                          │
│   共享库 (libc.so 等)    │ ← 随机化！
│                          │
├── ↕ 随机间隙 ────────────┤ ← 随机化！
│          堆 ↑            │ ← 随机化！(brk 基地址)
│   ┌───────────────┐     │
│   │ malloc 分配的   │     │
│   └───────────────┘     │
├── ↕ 随机间隙 ────────────┤
│   BSS 段 / 数据段        │ ← PIE 时随机化
│   代码段 (.text)         │ ← PIE 时随机化
├─────────────────────────┤
│   未映射区域              │
└─────────────────────────┘ 0x000000000000

ASLR 随机化层级：
  Level 0：关闭（不随机化）
  Level 1：栈、共享库、mmap 区域随机化
  Level 2：加上堆的随机化（默认值）
  PIE：加上可执行文件本身的随机化（需要编译时支持）
```

### 6.3 查看和控制 ASLR

```bash
# 查看当前 ASLR 设置
$ cat /proc/sys/kernel/randomize_va_space
2

# 0 = 关闭 ASLR
# 1 = 栈/共享库/mmap 随机化
# 2 = 加上堆随机化（默认）

# 临时关闭 ASLR（需要 root）
$ echo 0 | sudo tee /proc/sys/kernel/randomize_va_space

# 只对当前命令关闭 ASLR
$ setarch $(uname -m) -R ./my_program

# 验证 ASLR 效果
$ for i in $(seq 3); do
    cat /proc/self/maps | grep stack
  done
7ffd1a200000-7ffd1a221000 rw-p 00000000 00:00 0   [stack]
7ffe5b800000-7ffe5b821000 rw-p 00000000 00:00 0   [stack]
7fff23c00000-7fff23c21000 rw-p 00000000 00:00 0   [stack]
# 每次栈地址都不同！
```

### 6.4 代码示例

```c
#include <stdio.h>
#include <stdlib.h>

int global_var = 42;           // 数据段
int bss_var;                   // BSS 段

void func() {
    int local_var = 10;        // 栈
    static int static_var = 0; // 数据段
    int *heap_var = malloc(4); // 堆
    
    printf("代码段 (func):      %p\n", (void*)func);
    printf("数据段 (global_var): %p\n", (void*)&global_var);
    printf("BSS 段 (bss_var):    %p\n", (void*)&bss_var);
    printf("栈 (local_var):      %p\n", (void*)&local_var);
    printf("堆 (heap_var):       %p\n", (void*)heap_var);
    printf("共享库 (printf):     %p\n", (void*)printf);
    
    free(heap_var);
}

int main() {
    func();
    return 0;
}

// 编译为 PIE（Position Independent Executable）：
// gcc -pie -fPIE -o aslr_demo aslr_demo.c

// 多次运行，观察地址变化：
// 第 1 次：
// 代码段 (func):      0x5624a3b00149
// 栈 (local_var):      0x7ffd8e3f1a4c
// 堆 (heap_var):       0x5624a52a02a0
//
// 第 2 次：
// 代码段 (func):      0x559b2c100149  ← 不同！
// 栈 (local_var):      0x7ffe1234abcc  ← 不同！
// 堆 (heap_var):       0x559b2d2002a0  ← 不同！
```

---

## 专题 7：CFS 完全公平调度

### 7.1 CFS 的核心思想

CFS（Completely Fair Scheduler）是 Linux 2.6.23 以来的默认进程调度器。它的核心思想是：**让每个进程获得公平的 CPU 时间份额**。

```
传统调度器：基于时间片（固定长度的时间片轮转）
  问题：不够公平，低优先级进程可能长时间得不到调度

CFS：基于虚拟运行时间 (vruntime)
  每个进程维护一个 vruntime 值
  CFS 总是选择 vruntime 最小的进程来运行
  → 保证所有进程的 vruntime 大致相等
  → 实现"公平"
```

### 7.2 vruntime 的计算

```
vruntime 的增长速度取决于进程的权重（优先级）：

  vruntime 增量 = 实际运行时间 × (NICE_0_WEIGHT / 进程权重)

  NICE_0_WEIGHT = 1024 (nice 值为 0 的标准权重)

  nice 值与权重的对应关系：
  ┌──────┬────────┬──────────────────────────────┐
  │ nice │ 权重    │ 说明                          │
  ├──────┼────────┼──────────────────────────────┤
  │ -20  │ 88761  │ 最高优先级，vruntime 增长最慢   │
  │ -10  │ 9548   │                               │
  │  -5  │ 3121   │                               │
  │   0  │ 1024   │ 默认优先级                     │
  │   5  │  335   │                               │
  │  10  │  110   │                               │
  │  19  │   15   │ 最低优先级，vruntime 增长最快   │
  └──────┴────────┴──────────────────────────────┘

示例：
  进程 A (nice=0, weight=1024)：实际运行 10ms
    vruntime 增量 = 10 × (1024/1024) = 10ms
  
  进程 B (nice=-5, weight=3121)：实际运行 10ms
    vruntime 增量 = 10 × (1024/3121) ≈ 3.28ms
    → 高优先级进程的 vruntime 增长更慢
    → 它会被选中更多次
    → 获得更多 CPU 时间

  进程 C (nice=5, weight=335)：实际运行 10ms
    vruntime 增量 = 10 × (1024/335) ≈ 30.57ms
    → 低优先级进程的 vruntime 增长更快
    → 它被选中的次数更少
```

### 7.3 红黑树

```
CFS 使用红黑树（Red-Black Tree）来管理所有可运行的进程，
以 vruntime 为键值排序。

  红黑树特性：
  - 自平衡二叉搜索树
  - 插入、删除、查找最小值：O(log n)
  - 适合频繁的插入和删除操作

  CFS 的红黑树：
         ┌───────────┐
         │ vrt=100ms │  (黑)
         └─────┬─────┘
         ┌─────┴─────┐
    ┌────┴────┐ ┌────┴────┐
    │vrt=50ms │ │vrt=150ms│
    │  (红)   │ │  (红)   │
    └────┬────┘ └────┬────┘
    ┌────┴────┐      │
    │vrt=30ms │    (NULL)
    │  (黑)   │
    └─────────┘
    ↑
    最左节点 = vruntime 最小的进程
    = 下一个被调度的进程

  调度决策（每次时钟中断时）：
  1. 更新当前进程的 vruntime
  2. 将当前进程重新插入红黑树
  3. 选择最左节点（vruntime 最小的进程）
  4. 如果最左节点不是当前进程，执行上下文切换
```

### 7.4 nice 值的实际使用

```bash
# 查看进程的 nice 值
$ ps -el | head
F S   UID   PID  PPID  C PRI  NI ADDR SZ WCHAN  TTY          TIME CMD
4 S     0     1     0  0  80   0 -  5000 ep_pol ?        00:00:01 systemd
                              ↑  ↑
                             PRI NI (nice 值)

# 以指定 nice 值启动程序
$ nice -n 10 ./my_program    # nice 值 10（较低优先级）
$ nice -n -5 ./my_program    # nice 值 -5（较高优先级，需要 root）

# 修改运行中进程的 nice 值
$ renice 5 -p 12345          # 将 PID 12345 的 nice 值改为 5
```

---

## 专题 8：可重入函数

### 8.1 什么是可重入函数

可重入函数（reentrant function）是指可以被多个执行流（如主程序和信号处理程序）安全地同时调用的函数。

```
不可重入的场景：

  主程序正在执行 printf("Hello ")   （printf 内部正在操作输出缓冲区）
       │
       │ ← 此时信号到达
       │
       ▼
  信号处理程序执行 printf("World")  （printf 内部也要操作同一个输出缓冲区）
       │
       │  输出缓冲区的状态被破坏！
       │  可能输出乱码、崩溃、死锁
       │
       ▼
  信号处理程序返回
       │
       ▼
  主程序的 printf 继续（缓冲区已被破坏）→ 未定义行为！
```

### 8.2 POSIX 异步信号安全 (Async-Signal-Safe) 函数列表

```
POSIX 标准保证以下函数是异步信号安全的（可以在信号处理程序中安全调用）：

_Exit          fexecve        poll            sigqueue
_exit          fork           posix_trace_event sigsuspend
abort          fstat          pselect         sleep
accept         fstatat        raise           sockatmark
access         fsync          read            socket
aio_error      ftruncate      readlink        socketpair
aio_return     futimens       readlinkat      stat
aio_suspend    getegid        recv            symlink
alarm          geteuid        recvfrom        symlinkat
bind           getgid         recvmsg         tcdrain
cfgetispeed    getgroups      rename          tcflow
cfgetospeed    getpeername    renameat        tcflush
cfsetispeed    getpgrp        rmdir           tcgetattr
cfsetospeed    getpid         select          tcgetpgrp
chdir          getppid        sem_post        tcsendbreak
chmod          getsockname    send            tcsetattr
chown          getsockopt     sendmsg         tcsetpgrp
clock_gettime  getuid         sendto          time
close          kill           setgid          timer_getoverrun
connect        link           setpgid         timer_gettime
creat          linkat         setsid          timer_settime
dup            listen         setsockopt      times
dup2           lseek          setuid          umask
execl          lstat          shutdown        uname
execle         mkdir          sigaction       unlink
execv          mkdirat        sigaddset       unlinkat
execve         mkfifo         sigdelset       utime
faccessat      mkfifoat       sigemptyset     utimensat
fchdir         mknod          sigfillset      utimes
fchmod         mknodat        sigismember     wait
fchmodat       open           signal          waitpid
fchown         openat         sigpause        write
fchownat       pause          sigpending
fcntl          pipe           sigprocmask
fdatasync      poll           sigset
```

### 8.3 为什么 malloc 不可重入

```c
// malloc 的简化内部实现（使用空闲链表）：

// 全局空闲链表（所有 malloc/free 共享）
static struct free_block *free_list = NULL;

void *malloc(size_t size) {
    struct free_block *prev = NULL;
    struct free_block *curr = free_list;
    
    // 遍历空闲链表，寻找合适的块
    while (curr != NULL) {
        if (curr->size >= size) {
            // 找到了！从链表中移除这个块
            
            // ★ 如果信号恰好在这里到达... ★
            // prev->next = curr->next;  // 修改链表指针
            // ★ ...信号处理程序也调用 malloc...
            // ★ 信号处理程序也在修改同一个链表！
            // ★ 链表结构被破坏 → 内存泄漏/崩溃
            
            if (prev)
                prev->next = curr->next;
            else
                free_list = curr->next;
            return (void*)(curr + 1);
        }
        prev = curr;
        curr = curr->next;
    }
    // 没有合适的块，调用 sbrk 向内核申请更多内存
    return sbrk_allocate(size);
}

// 问题根因：
// malloc 在操作全局数据结构（空闲链表）时没有保护
// 如果在操作中途被信号中断，信号处理程序也调用 malloc，
// 两个 malloc 调用会同时修改同一个链表 → 数据结构损坏
```

### 8.4 为什么 printf 不可重入

```c
// printf 的简化内部实现：

// stdout 的缓冲区（全局共享）
static char stdout_buf[BUFSIZ];
static int stdout_pos = 0;

int printf(const char *fmt, ...) {
    char temp[1024];
    int len = vsnprintf(temp, sizeof(temp), fmt, ...);
    
    // 将格式化后的字符串复制到 stdout 缓冲区
    for (int i = 0; i < len; i++) {
        stdout_buf[stdout_pos] = temp[i];  // ★ 修改全局缓冲区
        stdout_pos++;                       // ★ 修改全局位置指针
        
        // 如果缓冲区满了或遇到 '\n'，刷新
        if (stdout_pos >= BUFSIZ || temp[i] == '\n') {
            write(STDOUT_FILENO, stdout_buf, stdout_pos);
            stdout_pos = 0;
        }
    }
    return len;
}

// 问题：
// 1. 全局缓冲区和位置指针不是原子操作
// 2. printf 内部还可能调用 malloc（用于临时缓冲区）
// 3. printf 可能持有内部锁（如果是多线程版本），
//    信号处理程序再次调用会导致死锁
```

### 8.5 在信号处理程序中安全输出

```c
// 错误做法：
void sigchld_handler(int sig) {
    printf("Child terminated\n");  // 不安全！
}

// 正确做法：使用 write() 系统调用（异步信号安全）
void sigchld_handler(int sig) {
    // write 是异步信号安全的
    const char msg[] = "Child terminated\n";
    write(STDOUT_FILENO, msg, sizeof(msg) - 1);
}

// 如果需要输出数字（如 PID）：
// 自己实现一个信号安全的整数转字符串函数
void safe_print_int(int n) {
    char buf[20];
    int i = sizeof(buf) - 1;
    buf[i] = '\0';
    
    if (n == 0) {
        buf[--i] = '0';
    } else {
        int neg = (n < 0);
        if (neg) n = -n;
        while (n > 0) {
            buf[--i] = '0' + (n % 10);
            n /= 10;
        }
        if (neg) buf[--i] = '-';
    }
    write(STDOUT_FILENO, &buf[i], sizeof(buf) - 1 - i);
}

// 在 Shell Lab 中，CSAPP 提供了 Sio_puts 和 Sio_putl
// 它们是信号安全的输出函数（使用 write 实现）
```

---

## 专题 9：volatile 与 sig_atomic_t

### 9.1 编译器优化与 volatile

```c
// 考虑以下代码：
int flag = 0;

void handler(int sig) {
    flag = 1;
}

int main() {
    Signal(SIGINT, handler);
    
    while (!flag) {
        // 等待信号
    }
    printf("Signal received!\n");
    return 0;
}

// 编译器可能的优化（-O2）：
// 编译器分析 main() 中的代码，发现：
//   1. flag 在循环中没有被修改
//   2. 循环中没有调用任何可能修改 flag 的函数
//   3. 所以 flag 永远是 0
//   4. 所以 while(!flag) 永远为真
//   5. 优化为无限循环！

// 编译器生成的汇编（伪代码）：
//   mov  flag, %eax      # 只读一次 flag
//   test %eax, %eax
//   jnz  done
// infinite_loop:
//   jmp  infinite_loop    # 永远循环，不再检查 flag！
// done:
//   ...

// 解决方案：使用 volatile
volatile int flag = 0;

// volatile 告诉编译器：
// "不要对这个变量做任何优化假设"
// "每次使用这个变量时，都必须从内存中重新读取"
// "不要将这个变量缓存到寄存器中"

// 使用 volatile 后，编译器生成的汇编：
// loop:
//   mov  flag(%rip), %eax   # 每次都从内存读取
//   test %eax, %eax
//   jz   loop               # 如果为 0，继续循环
// done:
//   ...
```

### 9.2 sig_atomic_t

```c
// sig_atomic_t 是 C 标准定义的类型，
// 保证对它的读写是"原子的"——
// 即一次读或写操作不会被信号中断到一半。

// 在大多数平台上，sig_atomic_t 就是 int。
// 但在某些平台上，int 的读写可能不是原子的
// （例如 8 位 MCU 上的 32 位 int）。

// 最安全的写法：
volatile sig_atomic_t flag = 0;

// volatile：防止编译器优化
// sig_atomic_t：保证原子读写

// 在信号处理程序中：
void handler(int sig) {
    flag = 1;   // 原子写
}

// 在主程序中：
while (!flag) {  // 原子读
    pause();
}
```

### 9.3 volatile 不是线程安全的

```c
// 重要误区：volatile 不等于线程安全！

// volatile 只保证：
//   1. 每次访问都从内存读取（不缓存到寄存器）
//   2. 不重排对 volatile 变量的访问

// volatile 不保证：
//   1. 原子性（除了 sig_atomic_t 大小的读写）
//   2. 内存可见性（在多核 CPU 上）
//   3. 内存屏障

// 对比：
// ┌──────────────────┬──────────┬──────────────────┐
// │                  │ volatile │ atomic / mutex    │
// ├──────────────────┼──────────┼──────────────────┤
// │ 防编译器优化      │    是    │       是          │
// │ 原子操作          │    否*   │       是          │
// │ 内存屏障          │    否    │       是          │
// │ 多线程安全        │    否    │       是          │
// │ 信号处理程序安全  │    是**  │       不适用      │
// └──────────────────┴──────────┴──────────────────┘
// * sig_atomic_t 大小的读写除外
// ** 配合 sig_atomic_t 使用时

// 信号处理场景用 volatile sig_atomic_t
// 多线程场景用 pthread_mutex_t 或 atomic 操作
```

---

## 专题 10：atexit 函数

### 10.1 基本用法

```c
#include <stdlib.h>

int atexit(void (*function)(void));
// 注册一个在程序正常终止时被调用的函数
// 可以注册多个（至少 32 个）
// 调用顺序：后注册的先调用（LIFO，栈序）
// 返回 0 表示成功

void cleanup1(void) {
    printf("Cleanup 1: closing files...\n");
}

void cleanup2(void) {
    printf("Cleanup 2: freeing memory...\n");
}

void cleanup3(void) {
    printf("Cleanup 3: saving state...\n");
}

int main() {
    atexit(cleanup1);  // 注册顺序：1, 2, 3
    atexit(cleanup2);
    atexit(cleanup3);
    
    printf("Main function running...\n");
    
    // 程序结束时（exit() 或 main return）
    // 调用顺序：cleanup3, cleanup2, cleanup1 (LIFO)
    return 0;
}

// 输出：
// Main function running...
// Cleanup 3: saving state...
// Cleanup 2: freeing memory...
// Cleanup 1: closing files...
```

### 10.2 exit() 与 _exit() 的区别

```
┌──────────────┬─────────────────────────┬──────────────────────┐
│              │  exit(status)            │  _exit(status)       │
├──────────────┼─────────────────────────┼──────────────────────┤
│ atexit 函数  │  调用所有注册的清理函数   │  不调用               │
│ stdio 缓冲区 │  刷新并关闭              │  不刷新，直接关闭      │
│ 临时文件     │  删除                    │  不删除               │
│ 系统调用     │  最终调用 _exit          │  直接进入内核          │
│ 使用场景     │  正常程序终止            │  fork 后子进程的错误    │
│              │                         │  退出、信号处理中      │
└──────────────┴─────────────────────────┴──────────────────────┘

执行流程：
  exit(0)
    │
    ├── 调用 atexit 注册的函数（LIFO 顺序）
    ├── 刷新所有 stdio 流的缓冲区
    ├── 关闭所有 stdio 流
    ├── 删除 tmpfile() 创建的临时文件
    └── 调用 _exit(0)
           │
           └── 进入内核
                ├── 关闭所有文件描述符
                ├── 释放进程的内存
                ├── 向父进程发送 SIGCHLD
                └── 进程变为僵尸状态
```

### 10.3 为什么在 Shell Lab 中子进程不应该用 exit()

```c
// 在 eval() 中 fork 的子进程：
if (pid == 0) {
    // 子进程
    if (execve(argv[0], argv, environ) < 0) {
        printf("%s: Command not found\n", argv[0]);
        exit(1);   // 这里用 exit() 还是 _exit()？
    }
}

// 用 exit(1) 的问题：
// 子进程是父进程（Shell）的副本，
// 它继承了父进程通过 atexit() 注册的清理函数。
// 如果用 exit()，这些清理函数会在子进程中被执行，
// 可能导致双重清理（父进程退出时又执行一遍）。
// 此外，exit() 会刷新 stdio 缓冲区，
// 可能导致输出重复。

// 最佳实践：
// 在 fork 后的子进程中，如果 execve 失败，
// 使用 _exit(1) 而不是 exit(1)。
if (pid == 0) {
    if (execve(argv[0], argv, environ) < 0) {
        printf("%s: Command not found\n", argv[0]);
        _exit(1);  // 更安全
    }
}
```

---

## 专题 11：辅助向量 (Auxiliary Vector)

### 11.1 什么是辅助向量

辅助向量是内核在程序启动时传递给用户态程序的一组键值对，包含了程序运行所需的底层环境信息。

```
进程启动时，栈的初始布局：

┌─────────────────────────────┐ 高地址
│  环境变量字符串               │
│  "PATH=/usr/bin:..."        │
│  "HOME=/home/user"          │
│  ...                        │
├─────────────────────────────┤
│  命令行参数字符串             │
│  "./my_program"             │
│  "-v"                       │
│  "input.txt"                │
├─────────────────────────────┤
│  填充 / 对齐                 │
├─────────────────────────────┤
│  辅助向量 (Auxiliary Vector) │ ← 这里！
│  AT_PAGESZ  = 4096          │
│  AT_ENTRY   = 0x400080      │
│  AT_PHDR    = 0x400040      │
│  AT_RANDOM  = 0x7fff1234    │
│  AT_NULL    = 0             │ (终止标记)
├─────────────────────────────┤
│  envp[] 数组                 │
│  envp[0] → "PATH=..."       │
│  envp[1] → "HOME=..."       │
│  NULL                        │
├─────────────────────────────┤
│  argv[] 数组                 │
│  argv[0] → "./my_program"   │
│  argv[1] → "-v"             │
│  argv[2] → "input.txt"      │
│  NULL                        │
├─────────────────────────────┤
│  argc = 3                    │
└─────────────────────────────┘ 低地址 (栈顶, %rsp)
```

### 11.2 常见的 AT_* 键值对

```
┌─────────────────┬────────────────────────────────────────┐
│  键               │  含义                                  │
├─────────────────┼────────────────────────────────────────┤
│ AT_NULL (0)      │ 辅助向量结束标记                        │
│ AT_PHDR (3)      │ 程序头表的地址                          │
│ AT_PHENT (4)     │ 程序头表条目的大小                      │
│ AT_PHNUM (5)     │ 程序头表条目的数量                      │
│ AT_PAGESZ (6)    │ 系统页大小 (通常 4096)                  │
│ AT_BASE (7)      │ 动态链接器的基地址                      │
│ AT_FLAGS (8)     │ 标志                                   │
│ AT_ENTRY (9)     │ 程序入口点地址                          │
│ AT_UID (11)      │ 真实用户 ID                             │
│ AT_EUID (12)     │ 有效用户 ID                             │
│ AT_GID (13)      │ 真实组 ID                               │
│ AT_EGID (14)     │ 有效组 ID                               │
│ AT_PLATFORM (15) │ 平台字符串 (如 "x86_64")               │
│ AT_HWCAP (16)    │ 硬件能力标志 (CPU 支持的指令集)          │
│ AT_CLKTCK (17)   │ 每秒时钟滴答数                          │
│ AT_RANDOM (25)   │ 16 字节随机数据的地址                   │
│ AT_EXECFN (31)   │ 可执行文件名的地址                      │
│ AT_SYSINFO_EHDR  │ vDSO 的地址                            │
│ (33)             │                                        │
└─────────────────┴────────────────────────────────────────┘
```

### 11.3 查看辅助向量

```bash
# 使用 LD_SHOW_AUXV 环境变量
$ LD_SHOW_AUXV=1 /bin/true
AT_SYSINFO_EHDR: 0x7ffd6e5fe000
AT_HWCAP:        bfebfbff
AT_PAGESZ:       4096
AT_CLKTCK:       100
AT_PHDR:         0x5622a7000040
AT_PHENT:        56
AT_PHNUM:        13
AT_BASE:         0x7f3b12000000
AT_FLAGS:        0x0
AT_ENTRY:        0x5622a7001150
AT_UID:          1000
AT_EUID:         1000
AT_GID:          1000
AT_EGID:         1000
AT_RANDOM:       0x7ffd6e5c79e9
AT_EXECFN:       /bin/true
AT_PLATFORM:     x86_64
```

### 11.4 在代码中访问辅助向量

```c
#include <stdio.h>
#include <sys/auxv.h>

int main() {
    // 使用 getauxval() 函数（Linux 特有）
    unsigned long page_size = getauxval(AT_PAGESZ);
    unsigned long entry_point = getauxval(AT_ENTRY);
    const char *platform = (const char*)getauxval(AT_PLATFORM);
    
    printf("Page size: %lu\n", page_size);
    printf("Entry point: 0x%lx\n", entry_point);
    printf("Platform: %s\n", platform);
    
    return 0;
}
```

---

## 专题 12：close-on-exec 标志

### 12.1 问题：文件描述符泄漏

```
当父进程 fork + exec 子进程时，子进程会继承父进程的所有文件描述符。
如果父进程打开了敏感文件（如密码文件、数据库连接），
子进程也能访问这些资源 → 安全隐患！

父进程                               子进程
┌────────────────┐                  ┌────────────────┐
│ fd 0: stdin    │    fork()        │ fd 0: stdin    │
│ fd 1: stdout   │  ──────────▶     │ fd 1: stdout   │
│ fd 2: stderr   │                  │ fd 2: stderr   │
│ fd 3: 密码文件  │                  │ fd 3: 密码文件  │ ← 泄漏！
│ fd 4: 网络套接字│                  │ fd 4: 网络套接字│ ← 泄漏！
└────────────────┘                  └────────────────┘
                                    execve("/bin/ls", ...)
                                    ls 程序现在有 fd 3 和 fd 4！
```

### 12.2 FD_CLOEXEC 标志

```c
#include <fcntl.h>

// 方法 1：打开文件后设置 FD_CLOEXEC
int fd = open("/etc/passwd", O_RDONLY);
fcntl(fd, F_SETFD, FD_CLOEXEC);  // 设置 close-on-exec 标志

// 方法 2：打开文件时直接设置 O_CLOEXEC（更好，避免竞争条件）
int fd = open("/etc/passwd", O_RDONLY | O_CLOEXEC);

// 设置了 FD_CLOEXEC 后：
// fork() → 子进程仍然继承 fd（fd 在 fork 时总是被复制）
// execve() → fd 被自动关闭！
//            子进程的新程序看不到这个 fd

// 检查是否设置了 FD_CLOEXEC：
int flags = fcntl(fd, F_GETFD);
if (flags & FD_CLOEXEC) {
    printf("FD %d will be closed on exec\n", fd);
}
```

### 12.3 为什么 O_CLOEXEC 比 fcntl 更好

```c
// 竞争条件场景（多线程环境）：

// 线程 A：
int fd = open("/etc/secret", O_RDONLY);
// ★ 此时线程 B 可能调用 fork()！
// 子进程继承了 fd，但还没设置 FD_CLOEXEC
fcntl(fd, F_SETFD, FD_CLOEXEC);  // 来不及了！

// 使用 O_CLOEXEC 没有这个问题：
int fd = open("/etc/secret", O_RDONLY | O_CLOEXEC);
// open 原子地设置了 FD_CLOEXEC
// 即使在 open 和 fcntl 之间有 fork，也是安全的
```

### 12.4 最佳实践

```
安全编程准则：
1. 每个 open/socket/pipe/accept 调用都应使用 O_CLOEXEC
2. 只有确实需要子进程继承的 fd（如 stdin/stdout/stderr 的重定向），
   才不设置 FD_CLOEXEC
3. 现代 Linux API 都支持 _CLOEXEC 变体：
   - open()    → O_CLOEXEC
   - socket()  → SOCK_CLOEXEC
   - pipe2()   → O_CLOEXEC
   - accept4() → SOCK_CLOEXEC
   - dup3()    → O_CLOEXEC
   - epoll_create1() → EPOLL_CLOEXEC
   - signalfd() → SFD_CLOEXEC
   - timerfd_create() → TFD_CLOEXEC
   - eventfd() → EFD_CLOEXEC
```

---

## 专题 13：会话 (Session) 与控制终端

### 13.1 会话的概念

```
会话 (Session) 是进程组的集合，用于管理终端访问和作业控制。

登录过程创建一个新会话：
  用户登录 → login 进程调用 setsid() → 创建新会话
  → 启动 Shell（Shell 成为会话领导进程）
  → Shell 启动的所有程序都在这个会话中

会话的层次结构：
  会话 (Session)
    ├── 会话领导进程 (session leader) — 通常是 Shell
    ├── 控制终端 (controlling terminal) — /dev/pts/N
    ├── 前台进程组 — 当前正在使用终端的进程组
    └── 后台进程组 — 在后台运行的进程组（可能多个）
```

### 13.2 setsid() 系统调用

```c
#include <unistd.h>

pid_t setsid(void);
// 创建一个新的会话
// 调用进程成为：
//   1. 新会话的会话领导进程 (session leader)
//   2. 新进程组的组长 (process group leader)
// 调用进程脱离原来的控制终端
// 返回新会话的 SID（等于调用进程的 PID）

// 限制：如果调用进程已经是进程组组长，setsid() 会失败
// 原因：防止组长"偷走"其他组员
// 解决：先 fork()，在子进程中调用 setsid()
```

### 13.3 双重 fork 技巧

双重 fork 是创建守护进程 (daemon) 的经典技巧。目的是创建一个既脱离终端、又不是会话领导进程的进程。

```c
// 为什么需要双重 fork？

// 目标：创建一个守护进程
// 要求：
//   1. 脱离控制终端（不受终端信号影响）
//   2. 不是会话领导进程（避免意外获得控制终端）

// 单次 fork + setsid 的问题：
// fork() → 子进程 → setsid() → 子进程成为会话领导
// 问题：会话领导进程如果 open 一个终端设备，
//       该终端可能成为进程的控制终端（在某些 Unix 系统上）
//       → 前功尽弃！

// 双重 fork 的解决方案：

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/stat.h>

void daemonize() {
    pid_t pid;
    
    // ===== 第一次 fork =====
    pid = fork();
    if (pid < 0) {
        perror("First fork failed");
        exit(1);
    }
    if (pid > 0) {
        // 父进程退出
        // 此时子进程变成孤儿，被 init 收养
        exit(0);
    }
    
    // ===== 第一个子进程 =====
    // 创建新会话
    setsid();
    // 现在：
    //   - 子进程是新会话的会话领导
    //   - 子进程是新进程组的组长
    //   - 没有控制终端
    
    // ===== 第二次 fork =====
    pid = fork();
    if (pid < 0) {
        perror("Second fork failed");
        exit(1);
    }
    if (pid > 0) {
        // 第一个子进程退出
        // 它是会话领导，退出后会话没有领导了
        exit(0);
    }
    
    // ===== 第二个子进程（守护进程） =====
    // 现在：
    //   - 不是会话领导（第一个子进程才是，但它已退出）
    //   - 没有控制终端
    //   - 不可能意外获得控制终端
    //     （因为只有会话领导才能获得控制终端）
    
    // 设置文件创建掩码
    umask(0);
    
    // 改变工作目录到根目录（避免占用挂载点）
    chdir("/");
    
    // 关闭所有文件描述符
    for (int fd = 0; fd < sysconf(_SC_OPEN_MAX); fd++) {
        close(fd);
    }
    
    // 守护进程的主循环
    while (1) {
        // 做守护进程该做的事...
        sleep(60);
    }
}

// 时间线：
//
// 原始进程 (pid=1000, pgid=1000, sid=500)
//    │
//    ├─ fork()
//    │
//    ├─ 原始进程: exit(0)  ← 退出
//    │
//    └─ 第一个子进程 (pid=1001, pgid=1000, sid=500)
//         │
//         ├─ setsid()  → (pid=1001, pgid=1001, sid=1001)
//         │   现在是会话领导
//         │
//         ├─ fork()
//         │
//         ├─ 第一个子进程: exit(0)  ← 退出
//         │
//         └─ 第二个子进程 (pid=1002, pgid=1001, sid=1001)
//              │
//              ├─ 不是会话领导！(会话领导 1001 已退出)
//              ├─ 不可能获得控制终端
//              ├─ 被 init (pid=1) 收养
//              │
//              └─ 这就是守护进程！
```

---

## 专题 14：tcsetpgrp

### 14.1 完整 API

```c
#include <unistd.h>

// 获取终端的前台进程组 ID
pid_t tcgetpgrp(int fd);
// fd: 指向终端的文件描述符
// 返回: 前台进程组的 PGID
// 错误: 返回 -1

// 设置终端的前台进程组
int tcsetpgrp(int fd, pid_t pgrp);
// fd: 指向终端的文件描述符
// pgrp: 要设为前台的进程组 ID
// 要求: pgrp 必须是同一会话中的一个进程组
// 返回: 成功 0，失败 -1

// 限制条件：
// 1. 调用进程必须拥有控制终端
// 2. fd 必须指向调用进程的控制终端
// 3. pgrp 指定的进程组必须在同一会话中
// 4. 如果调用进程在后台调用 tcsetpgrp，
//    可能收到 SIGTTOU 信号
```

### 14.2 Shell 中 fg 命令的完整实现逻辑

```c
// fg 命令的完整实现（概念性的，比 tsh 更完整）
void do_fg(int jid) {
    struct job_t *job = getjobjid(jobs, jid);
    if (job == NULL) {
        printf("fg: no such job\n");
        return;
    }
    
    pid_t pgid = job->pid;  // 在 tsh 中，pgid == pid
    
    // 1. 将该进程组设为终端的前台进程组
    //    （tsh 简化实现中省略了这一步）
    // tcsetpgrp(STDIN_FILENO, pgid);
    
    // 2. 更新作业状态
    job->state = FG;
    
    // 3. 如果作业是 Stopped 状态，发送 SIGCONT 恢复它
    kill(-pgid, SIGCONT);
    
    // 4. 等待前台作业结束或停止
    waitfg(pgid);
    
    // 5. 前台作业结束后，Shell 回收终端控制权
    //    （tsh 简化实现中省略了这一步）
    // tcsetpgrp(STDIN_FILENO, getpgrp());
}
```

### 14.3 为什么 tsh 可以省略 tcsetpgrp

```
tsh 是一个简化的 Shell 实现，它的信号处理方式不同于真实 Shell：

真实 Shell (bash)：
  - Shell 把自己放在自己的进程组中
  - 子进程放在独立的进程组中
  - 运行前台命令时，Shell 调用 tcsetpgrp 将子进程组设为前台
  - 键盘信号直接发给子进程组（Shell 不会收到）
  - 子进程结束后，Shell 调用 tcsetpgrp 夺回前台控制权

tsh 的简化方式：
  - Shell 安装了 SIGINT 和 SIGTSTP 的处理程序
  - 键盘信号发给 Shell 的进程组（因为 Shell 仍是前台进程组）
  - Shell 收到信号后，转发给前台作业的进程组
  - 这种方式更简单，但多了一层转发

  用户按 Ctrl+C
       │
       ▼
  终端驱动发送 SIGINT 给前台进程组
       │
       ▼
  Shell 收到 SIGINT（Shell 在前台进程组中）
       │
       ▼
  sigint_handler:
    pid = fgpid(jobs);  // 获取前台作业的 PID
    if (pid != 0)
        kill(-pid, SIGINT);  // 转发给前台作业的进程组
```

---

## 专题 15：信号处理中的竞争条件

### 15.1 fork + addjob + deletejob 竞争的完整时序分析

这是 Shell Lab 中最核心、最难理解的竞争条件。

```
问题场景（不加保护的代码）：

void eval(char *cmdline) {
    pid_t pid = fork();
    if (pid == 0) {
        execve(argv[0], argv, environ);
    }
    // 父进程
    addjob(jobs, pid, FG, cmdline);  // 添加到作业列表
    waitfg(pid);                      // 等待前台作业结束
}

void sigchld_handler(int sig) {
    pid_t pid;
    while ((pid = waitpid(-1, NULL, WNOHANG)) > 0) {
        deletejob(jobs, pid);  // 从作业列表删除
    }
}
```

```
竞争条件的时序分析：

  Shell (父进程)                  子进程               内核
      │                            │                    │
   fork() ─────────────────▶ 被创建                     │
      │                            │                    │
      │ ← 时间片到/被调度出去       │                    │
      │                            │                    │
      │                         execve("./myspin")      │
      │                            │                    │
      │                         myspin 很快结束          │
      │                         exit(0)                 │
      │                            │                    │
      │                         变成僵尸进程             │
      │                            │                    │
      │                                    ┌────────────┤
      │                                    │ 内核发送     │
      │ ◀── SIGCHLD ──────────────────────┤ SIGCHLD    │
      │                                    └────────────┘
      │
   sigchld_handler 被调用
      │
   waitpid(pid) → 成功回收僵尸
      │
   deletejob(jobs, pid)
      │ ↑
      │ 问题！此时 addjob 还没被调用！
      │ deletejob 在作业列表中找不到这个 pid
      │ → deletejob 什么都没做
      │
   handler 返回
      │
   继续执行 fork 之后的代码
      │
   addjob(jobs, pid, FG, cmdline)
      │ ↑
      │ 现在才添加了作业！
      │ 但子进程已经终止了
      │ 这个作业永远不会被删除 → 僵尸作业！
      │
   waitfg(pid)
      │ ↑
      │ 永远等待！因为前台作业永远不会被回收
      │ Shell 挂起！
```

### 15.2 正确的解决方案

```c
void eval(char *cmdline) {
    char *argv[MAXARGS];
    int bg = parseline(cmdline, argv);
    
    if (argv[0] == NULL) return;
    
    if (!builtin_cmd(argv)) {
        sigset_t mask, prev_mask;
        sigemptyset(&mask);
        sigaddset(&mask, SIGCHLD);
        
        // ★ 关键步骤 1：在 fork 之前阻塞 SIGCHLD ★
        sigprocmask(SIG_BLOCK, &mask, &prev_mask);
        
        pid_t pid = fork();
        
        if (pid == 0) {
            // 子进程：恢复信号掩码（子进程不需要阻塞 SIGCHLD）
            sigprocmask(SIG_SETMASK, &prev_mask, NULL);
            setpgid(0, 0);
            execve(argv[0], argv, environ);
            printf("%s: Command not found\n", argv[0]);
            _exit(1);
        }
        
        // 父进程：
        setpgid(pid, pid);  // 防止竞争
        
        // ★ 关键步骤 2：在 SIGCHLD 被阻塞的状态下添加作业 ★
        addjob(jobs, pid, bg ? BG : FG, cmdline);
        
        // ★ 关键步骤 3：恢复信号掩码（解除对 SIGCHLD 的阻塞）★
        sigprocmask(SIG_SETMASK, &prev_mask, NULL);
        // 如果此前有 SIGCHLD 到达，现在才会被递送
        // 此时 addjob 已经完成，sigchld_handler 中的 deletejob 能找到作业
        
        if (!bg) {
            waitfg(pid);
        } else {
            printf("[%d] (%d) %s", pid2jid(pid), pid, cmdline);
        }
    }
}
```

### 15.3 正确方案的时序分析

```
  Shell (父进程)                  子进程               内核
      │                            │                    │
   sigprocmask(BLOCK SIGCHLD)      │                    │
   // SIGCHLD 被阻塞               │                    │
      │                            │                    │
   fork() ─────────────────▶ 被创建                     │
      │                            │                    │
      │                         execve("./myspin")      │
      │                         myspin 很快结束          │
      │                         exit(0)                 │
      │                            │                    │
      │                                    ┌────────────┤
      │                                    │ 内核发送     │
      │                    SIGCHLD 到达 ◀──┤ SIGCHLD    │
      │                    但 SIGCHLD 被阻塞！            │
      │                    → 信号变为"待处理"(pending)     │
      │                                    └────────────┘
      │
   setpgid(pid, pid)              // 安全
      │
   addjob(jobs, pid, ...)         // 安全！不会被 handler 打断
      │
   sigprocmask(UNBLOCK SIGCHLD)   // 解除阻塞
      │
      │ ← 待处理的 SIGCHLD 现在被递送
      │
   sigchld_handler 被调用
      │
   waitpid(pid) → 成功回收
      │
   deletejob(jobs, pid)           // 成功！作业已经在列表中了
      │
   handler 返回
      │
   waitfg(pid)                    // fgpid 返回 0，立即返回
      │
   回到主循环，一切正常！
```

### 15.4 常见错误

```
错误 1：只在 fork 之后阻塞 SIGCHLD
  sigprocmask(SIG_BLOCK, ...);   // 太晚了！
  pid = fork();
  // 如果 fork 之后、sigprocmask 之前子进程就结束了，
  // SIGCHLD 仍然可能在 addjob 之前到达

错误 2：忘记在子进程中恢复信号掩码
  // 子进程继承了父进程的信号掩码
  // 如果子进程也阻塞了 SIGCHLD，它的子进程结束时
  // 它不会收到 SIGCHLD → 可能产生僵尸进程

错误 3：阻塞的范围太大
  sigprocmask(SIG_BLOCK, ...);   // 在程序开始就阻塞
  // ... 大量代码 ...
  sigprocmask(SIG_UNBLOCK, ...); // 很久才解除
  // 问题：在阻塞期间，所有 SIGCHLD 都被延迟处理
  // 原则：阻塞的时间应该尽可能短
```

---

# 第三大块：自检清单详解

---

## 段落一：异常与异常处理

### Q1：什么是异常？异常和中断的关系是什么？

**答**：异常是控制流中的突变，用来响应处理器状态的某些变化（事件）。异常是一个广义概念，包含四种类型：中断、陷阱、故障、终止。中断是异常的一个子类，特指由外部 I/O 设备触发的异步事件。所有中断都是异常，但不是所有异常都是中断。

### Q2：四类异常（中断、陷阱、故障、终止）的区别和联系是什么？

**答**：
- **中断 (Interrupt)**：异步，由外部设备触发，与当前指令无关。处理完后返回到下一条指令。例：时钟中断、磁盘中断。
- **陷阱 (Trap)**：同步，由当前指令有意触发。处理完后返回到下一条指令。例：syscall 指令。
- **故障 (Fault)**：同步，由当前指令意外触发。如果可恢复，返回到当前指令重新执行；否则终止进程。例：缺页故障、除零异常。
- **终止 (Abort)**：同步，由致命的硬件错误触发。不返回，直接终止进程。例：机器校验异常。

### Q3：异常表 (IDT) 的作用是什么？异常号如何确定？

**答**：异常表（在 x86-64 上叫 IDT，Interrupt Descriptor Table）是一个由操作系统在启动时初始化的跳转表。每个条目存储一个异常处理程序的入口地址。异常号是表的索引，由硬件设计者和操作系统设计者共同定义。x86-64 上：0-31 号由 Intel 架构保留（如 0 号是除零故障，14 号是缺页故障），32-255 号由操作系统定义（如 128 号用于 Linux 系统调用 int 0x80）。

### Q4：系统调用的执行过程是什么？

**答**：
1. 用户程序将系统调用号放入 `%rax`，参数放入 `%rdi, %rsi, %rdx, %r10, %r8, %r9`
2. 执行 `syscall` 指令
3. CPU 切换到内核态（CPL 从 3 变为 0），切换到内核栈
4. 内核检查 `%rax` 中的系统调用号，在 `sys_call_table` 中查找对应的处理函数
5. 执行该处理函数（如 `sys_read`）
6. 将返回值放入 `%rax`
7. 执行 `sysret` 指令返回用户态

### Q5：缺页故障的处理流程是什么？什么情况下会导致段错误 (Segmentation Fault)？

**答**：
缺页故障处理流程：
1. MMU 发现 PTE 的 Present 位为 0，触发异常号 14
2. 内核检查虚拟地址是否合法（是否在某个 VMA 范围内）
3. 如果合法，分配物理页帧，从磁盘/交换区加载数据，更新 PTE
4. 返回用户态，重新执行触发故障的指令

导致段错误的情况：
- 访问未映射的地址（如 NULL 指针解引用）
- 向只读页写入（如修改代码段）
- 用户态访问内核地址空间
- 栈溢出超出栈的最大限制

### Q6：异常处理时 CPU 做了哪些事情（硬件自动完成的部分）？

**答**：硬件自动完成以下操作：
1. 确定异常号 k
2. 从 IDTR 寄存器获取 IDT 基地址
3. 用 k 索引 IDT，获取异常处理程序的入口地址
4. 如果需要特权级切换（用户态→内核态），切换到内核栈（从 TSS 获取内核栈指针）
5. 将 SS、RSP、RFLAGS、CS、RIP 依次压入内核栈
6. 对于某些异常（如缺页），额外压入错误码
7. 将 CS:RIP 设为异常处理程序的入口
8. 设置 CPL=0（进入内核态）

### Q7：为什么说时钟中断是操作系统实现多任务调度的基础？

**答**：没有时钟中断，CPU 就不会被强制从当前进程手中夺走。一个恶意或有 Bug 的程序可以无限占用 CPU（死循环）。时钟中断定期触发（如每 1ms），其处理程序检查当前进程的时间片是否用完，如果用完则调用 schedule() 进行上下文切换。这就是「抢占式调度」的实现基础。

### Q8：异常返回指令 (iret) 和普通的 ret 指令有什么区别？

**答**：
- `ret`：仅从栈上弹出返回地址到 RIP
- `iret`（中断返回）：从栈上弹出 RIP、CS、RFLAGS、RSP、SS，同时可能进行特权级切换（内核态→用户态）。`iret` 是从内核态返回用户态的唯一正规途径。

---

## 段落二：进程

### Q1：什么是进程？进程和程序的关系是什么？

**答**：进程是正在运行的程序的一个实例。程序是磁盘上的一个文件（包含代码和数据），是静态的；进程是程序加载到内存中执行的动态实体，有自己的地址空间、寄存器状态、打开的文件等。一个程序可以同时有多个进程实例（如同时运行两个 vim）。

### Q2：操作系统为进程提供了哪两个关键抽象？

**答**：
1. **逻辑控制流**：每个进程好像独占 CPU。通过上下文切换（轮流使用物理 CPU）实现。
2. **私有地址空间**：每个进程好像独占内存。通过虚拟内存（每个进程有自己的页表）实现。

### Q3：并发 (concurrent) 和并行 (parallel) 的区别是什么？

**答**：
- **并发**：两个逻辑控制流在时间上有重叠（交替执行），可以在单核 CPU 上实现
- **并行**：两个逻辑控制流在同一时刻同时执行，需要多核 CPU
- 并行是并发的特例。所有并行都是并发，但并发不一定是并行。

### Q4：什么是上下文切换？上下文包括哪些内容？

**答**：上下文切换是操作系统将 CPU 从一个进程切换到另一个进程的过程。上下文包括：
- 通用寄存器的值（RAX, RBX, ... R15）
- 程序计数器 (RIP)
- 栈指针 (RSP)
- 状态寄存器 (RFLAGS)
- CR3（页表基地址）
- 浮点/SIMD 寄存器
- 内核栈
- 各种内核数据结构（mm_struct, files_struct, signal_struct 等）

### Q5：什么情况下会触发上下文切换？

**答**：
1. 时钟中断（时间片用完）
2. 系统调用中进程主动放弃 CPU（如 sleep、wait、read 阻塞 I/O）
3. I/O 中断（唤醒等待 I/O 的进程）
4. 进程终止（exit）

### Q6：进程的虚拟地址空间布局是怎样的？

**答**：（从低地址到高地址）
1. 保留区域（0x0 附近，不可访问，用于捕获 NULL 指针解引用）
2. 代码段 (.text)：只读，可执行
3. 数据段 (.data, .bss)：全局变量和静态变量
4. 堆 (heap)：向上增长，由 malloc/free 管理
5. 共享库映射区域：libc.so 等
6. 栈 (stack)：向下增长，局部变量和函数调用
7. 内核空间：用户不可访问（从 0x800000000000 开始，x86-64）

---

## 段落三：进程控制

### Q1：fork() 的返回值有什么特点？为什么这样设计？

**答**：fork() 调用一次，返回两次。在父进程中返回子进程的 PID（正数），在子进程中返回 0。这样设计是因为：父进程可能有多个子进程，需要知道每个子进程的 PID；而子进程总是可以通过 getppid() 获取父进程的 PID，所以不需要通过 fork 的返回值。

### Q2：fork() 之后，父进程和子进程的异同？

**答**：
- **相同**：代码段、数据段的初始内容、打开的文件描述符表、信号处理设置、环境变量、工作目录、用户 ID、组 ID、信号掩码
- **不同**：PID、fork 的返回值、父进程 PID (PPID)、挂起的信号集、文件锁、定时器
- **写时复制 (COW)**：fork 后父子进程共享物理页，直到某一方写入时才复制该页

### Q3：僵尸进程和孤儿进程的区别？

**答**：
- **僵尸进程 (Zombie)**：子进程已终止，但父进程尚未调用 wait/waitpid 回收。内核保留子进程的退出状态信息。大量僵尸进程会浪费 PID 和内核资源。
- **孤儿进程 (Orphan)**：父进程先于子进程终止。孤儿进程会被 init 进程 (PID=1) 收养。init 会自动 wait 回收孤儿进程，所以孤儿进程不会变成僵尸。

### Q4：waitpid 的参数和返回值详解？

**答**：
```c
pid_t waitpid(pid_t pid, int *statusp, int options);
```
- `pid > 0`：等待特定 PID 的子进程
- `pid = -1`：等待任意子进程
- `pid = 0`：等待与调用进程同组的任意子进程
- `pid < -1`：等待进程组 ID 为 |pid| 的任意子进程

- `options = 0`：阻塞等待
- `WNOHANG`：非阻塞，无子进程可回收则立即返回 0
- `WUNTRACED`：也报告已停止的子进程
- `WCONTINUED`：也报告被 SIGCONT 恢复的子进程

返回值：成功回收时返回子进程 PID；WNOHANG 且无可回收子进程返回 0；错误返回 -1。

### Q5：execve 的特点是什么？

**答**：
- execve 调用一次，成功时不返回（因为当前进程的代码已被替换）
- 仅在出错时返回 -1
- execve 替换当前进程的代码段、数据段、堆、栈
- 保留 PID、打开的文件描述符（除了设置了 FD_CLOEXEC 的）、信号掩码、工作目录
- 信号处理程序被重置为默认（因为旧的处理函数代码已被替换）

### Q6：为什么 fork + exec 是分开的两个系统调用？

**答**：分开的设计给了 Shell 极大的灵活性。在 fork 之后、exec 之前，子进程可以：
- 重定向标准输入/输出（dup2）
- 关闭不需要的文件描述符
- 设置进程组（setpgid）
- 修改信号掩码
- 修改环境变量
- 改变工作目录
如果合并为一个 spawn 调用，就需要大量参数来控制这些行为。

### Q7：wait 和 waitpid 的区别？

**答**：`wait(&status)` 等价于 `waitpid(-1, &status, 0)`，即阻塞等待任意一个子进程。waitpid 提供更精细的控制：可以指定等待哪个子进程、是否非阻塞、是否报告停止状态。Shell Lab 中应该使用 waitpid。

### Q8：如何用 waitpid 回收所有僵尸子进程？

**答**：
```c
// 在 sigchld_handler 中：
int olderrno = errno;
pid_t pid;
int status;
while ((pid = waitpid(-1, &status, WNOHANG | WUNTRACED)) > 0) {
    // 处理已终止或已停止的子进程
    if (WIFEXITED(status)) {
        deletejob(jobs, pid);
    } else if (WIFSIGNALED(status)) {
        // 被信号终止
        printf("Job [%d] (%d) terminated by signal %d\n",
               pid2jid(pid), pid, WTERMSIG(status));
        deletejob(jobs, pid);
    } else if (WIFSTOPPED(status)) {
        // 被信号停止
        printf("Job [%d] (%d) stopped by signal %d\n",
               pid2jid(pid), pid, WSTOPSIG(status));
        struct job_t *job = getjobpid(jobs, pid);
        if (job) job->state = ST;
    }
}
errno = olderrno;
```

---

## 段落四：信号

### Q1：什么是信号？信号和异常的关系？

**答**：信号是一种软件层面的异常通知机制。异常是硬件/操作系统层面的机制，信号是建立在异常之上的、用于进程间通知的软件层面机制。可以将信号理解为「软件版的中断」：中断通知 CPU 有外部事件，信号通知进程有外部事件。

### Q2：信号的发送和接收过程？

**答**：
- **发送**：内核在目标进程的 pending 位向量中设置对应的位。发送方式包括：kill 系统调用、键盘快捷键（通过终端驱动）、内核检测到事件（如子进程终止发 SIGCHLD、除零发 SIGFPE）。
- **接收**：当内核将目标进程从内核态切换回用户态时（如系统调用返回、中断返回），检查 `pending & ~blocked`。如果有未被阻塞的待处理信号，选择其中一个递送给进程，执行对应的处理动作（默认动作、忽略、用户自定义的处理程序）。

### Q3：pending 信号的特点（信号不排队）？

**答**：每种类型的信号只有一个 pending 位。如果同一类型的信号在被阻塞期间到达多次，解除阻塞后只会递送一次。这意味着不能用信号来精确计数事件。例如，如果有 5 个子进程同时终止，SIGCHLD 可能只被递送 1-5 次。所以 sigchld_handler 中必须用 while 循环回收所有僵尸进程，而不是只回收一个。

### Q4：信号阻塞和忽略的区别？

**答**：
- **阻塞 (Blocking)**：信号被暂时搁置，不会被递送。信号仍然处于 pending 状态。解除阻塞后会被递送。使用 sigprocmask 控制。
- **忽略 (Ignoring)**：信号被递送但不做任何处理。信号不会保持 pending 状态。使用 signal(sig, SIG_IGN) 设置。

### Q5：sigprocmask 的三种操作？

**答**：
- `SIG_BLOCK`：blocked = blocked | set（将 set 中的信号添加到阻塞集）
- `SIG_UNBLOCK`：blocked = blocked & ~set（从阻塞集中移除 set 中的信号）
- `SIG_SETMASK`：blocked = set（直接设置阻塞集为 set）

### Q6：为什么信号处理程序中需要保存和恢复 errno？

**答**：许多信号安全函数（如 waitpid、write）在出错时会设置 errno。如果主程序正在检查 errno，而信号处理程序修改了 errno，主程序会看到错误的 errno 值。所以信号处理程序的第一行应该保存 errno，最后一行恢复 errno。

### Q7：SIGKILL 和 SIGSTOP 有什么特殊性？

**答**：SIGKILL (9) 和 SIGSTOP (19) 不能被捕获、阻塞或忽略。这是操作系统保留的「最终手段」，确保任何进程都可以被终止或暂停。

### Q8：signal() 和 sigaction() 的区别？为什么应该使用 sigaction()？

**答**：signal() 的行为在不同 Unix 系统上不一致（有些系统在处理信号后自动重置为默认处理程序）。sigaction() 的行为是 POSIX 标准严格定义的，具有可移植性。CSAPP 提供了 Signal() 包装函数，内部使用 sigaction() 实现，设置了 SA_RESTART 标志（自动重启被中断的系统调用）。

### Q9：sigsuspend 和 pause 的区别？为什么 waitfg 应该用 sigsuspend？

**答**：
- `pause()`：挂起进程直到收到任意信号。
- `sigsuspend(&mask)`：原子地将信号掩码设为 mask 并挂起进程，直到收到一个未被 mask 阻塞的信号。返回时恢复原来的信号掩码。

waitfg 应该用 sigsuspend 而不是 `while(fgpid(jobs) == pid) sleep(1)` 或 `while(fgpid(jobs) == pid) pause()`。sleep 的问题是响应太慢（最多延迟 1 秒）。pause 的问题是存在竞争条件：如果在检查 fgpid 和调用 pause 之间 SIGCHLD 到达，pause 会永远阻塞。sigsuspend 将「设置掩码+挂起」合并为原子操作，避免了竞争条件。

### Q10：kill 函数的第一个参数为负数是什么意思？

**答**：`kill(-pid, sig)` 将信号 sig 发送给进程组 ID 为 pid 的所有进程（注意参数是负的 pid）。在 Shell Lab 中，这很重要：当用户按 Ctrl+C 时，Shell 应该向整个前台进程组发信号，而不仅仅是进程组中的某一个进程。所以应该用 `kill(-pid, SIGINT)` 而不是 `kill(pid, SIGINT)`。

---

## 段落五：非本地跳转与实际应用

### Q1：setjmp/longjmp 的工作原理？

**答**：
- `setjmp(env)`：将当前的栈指针、程序计数器、通用寄存器等保存到 env 缓冲区（jmp_buf 类型）。第一次调用返回 0。
- `longjmp(env, val)`：从 env 恢复之前保存的状态，使执行流跳回 setjmp 的位置。setjmp 这次返回 val（非零值）。
- 相当于一个可以跨函数的 goto，可以从深层嵌套的函数调用中直接跳回上层。

### Q2：setjmp/longjmp 有什么限制和注意事项？

**答**：
1. setjmp 必须在特定的上下文中调用（如 if 条件、switch 表达式），不能赋值给变量
2. longjmp 跳过的所有栈帧中的局部变量都不会被析构（C++ 中不会调用析构函数）
3. setjmp 的返回值不能用于判断是否为 0 之后再做其他操作
4. 如果 setjmp 所在的函数已经返回，longjmp 行为未定义（因为栈帧已不存在）
5. longjmp 不能从信号处理程序跳回主程序时恢复信号掩码（应该用 sigsetjmp/siglongjmp）

### Q3：sigsetjmp/siglongjmp 和 setjmp/longjmp 的区别？

**答**：sigsetjmp/siglongjmp 在保存/恢复执行环境时，还会保存/恢复信号掩码。`sigsetjmp(env, savesigs)` 中如果 savesigs 非零，则保存当前信号掩码到 env 中。siglongjmp 会恢复保存的信号掩码。在信号处理程序中使用 longjmp 跳回主程序时，信号掩码可能保持「当前信号被阻塞」的状态（因为进入信号处理程序时内核自动阻塞了该信号），使用 siglongjmp 可以正确恢复。

### Q4：非本地跳转的实际应用场景有哪些？

**答**：
1. **错误恢复**：从深层嵌套的函数调用中跳回顶层错误处理代码（类似于 try-catch）
2. **软重启**：Shell 收到 SIGINT 后跳回主循环，而不是终止
3. **协程实现**：利用 setjmp/longjmp 在多个执行上下文之间切换（早期的用户态线程库）
4. **超时处理**：设置 SIGALRM 处理程序中使用 longjmp 跳回超时检测点

### Q5：为什么不推荐在 C++ 中使用 setjmp/longjmp？

**答**：C++ 有异常机制 (try/catch/throw)，在栈展开时会正确调用局部对象的析构函数。而 longjmp 直接跳过栈帧，不调用析构函数，可能导致资源泄漏（内存、文件描述符、锁等未释放）。C++ 应该使用 throw/catch 代替 longjmp。

### Q6：Shell Lab 中信号处理的完整工作流是什么？

**答**：
```
用户按 Ctrl+C
  → 终端驱动发送 SIGINT 给前台进程组
  → Shell 的 sigint_handler 被调用
  → handler 获取前台作业的 PID
  → handler 用 kill(-pid, SIGINT) 转发给前台作业的进程组
  → 子进程收到 SIGINT 终止
  → 内核发送 SIGCHLD 给 Shell
  → Shell 的 sigchld_handler 被调用
  → handler 用 waitpid 回收僵尸子进程
  → handler 从作业列表中删除该作业
  → waitfg 发现前台没有作业了，返回
  → Shell 回到主循环，打印 tsh> 提示符
```

### Q7：如何理解「信号是不排队的」这句话的实际影响？

**答**：假设 Shell 有 3 个后台子进程同时终止。内核发送 3 次 SIGCHLD，但如果第 1 个 SIGCHLD 正在被处理时第 2 和第 3 个到达，由于 SIGCHLD 被自动阻塞（SA_RESTART 语义），第 2 和第 3 个中只有一个会被标记为 pending（pending 只是一个位，不计数）。所以 handler 最多被调用 2 次（而不是 3 次）。如果 handler 每次只回收一个僵尸子进程，就会遗漏一个。所以必须用 while 循环配合 WNOHANG 回收所有可回收的子进程。

### Q8：在 Shell Lab 中，为什么子进程需要调用 setpgid(0,0)？如果不调用会怎样？

**答**：如果不调用 setpgid(0,0)，子进程和 Shell 在同一个进程组中。当用户按 Ctrl+C 时，SIGINT 会发送给整个前台进程组，Shell 自己也会收到信号。虽然 Shell 有信号处理程序，但如果 Shell 的子进程又 fork 了子进程（孙进程），信号管理会变得混乱。setpgid(0,0) 让子进程自成一个新进程组（组 ID = 子进程的 PID），Shell 可以精确地向这个进程组发送信号，而不影响 Shell 自己或其他作业。

---

# 第四大块：常见困惑 Q&A

---

## Q1：为什么 fork 后父子进程的执行顺序是不确定的？

**答**：fork 创建子进程后，内核调度器可以选择先运行父进程或子进程。这个选择取决于调度策略、CPU 负载、系统配置等因素，是非确定性的。在 Linux 3.x 之前，默认先运行子进程（因为子进程通常会紧接着调用 exec，利用 COW 避免不必要的页复制）；在 Linux 3.x 之后，默认先运行父进程（因为 CPU 缓存中有父进程的数据，继续运行父进程缓存命中率更高）。

**重要推论**：任何依赖于「父进程先运行」或「子进程先运行」假设的代码都是有 Bug 的。正确的做法是使用信号阻塞/同步来保证顺序。

```c
// 错误代码（假设父进程先运行）：
pid = fork();
if (pid == 0) {
    // 子进程
    execve(...);
}
addjob(pid);  // 如果子进程先运行并终止，handler 可能先 deletejob
waitfg(pid);  // → 等待一个永远不会被删除的作业

// 正确代码（不依赖执行顺序）：
sigprocmask(SIG_BLOCK, &mask_chld, &prev);
pid = fork();
if (pid == 0) {
    sigprocmask(SIG_SETMASK, &prev, NULL);
    execve(...);
}
addjob(pid);  // 保证在 deletejob 之前执行
sigprocmask(SIG_SETMASK, &prev, NULL);
waitfg(pid);  // 安全
```

---

## Q2：信号处理程序中可以做什么？不可以做什么？

**答**：

**可以做的**：
- 调用异步信号安全函数（见专题 8 的列表）
- 设置 volatile sig_atomic_t 类型的全局标志
- 调用 waitpid, write, kill, _exit 等
- 读写全局数据结构时先阻塞所有信号（保护临界区）

**不可以做的**：
- 调用 printf, malloc, free, exit 等不可重入函数
- 调用可能导致死锁的函数（如持有锁的函数）
- 执行复杂的逻辑（处理程序应该尽量简短）
- 假设信号的到达顺序

**最佳实践**：在信号处理程序中只做最少的工作（设置标志、回收子进程），把复杂的逻辑留给主程序。

---

## Q3：为什么 Shell 要在 fork 前阻塞 SIGCHLD，而不是在 fork 后？

**答**：如果在 fork 之后才阻塞 SIGCHLD，存在一个极小的时间窗口：fork 返回到父进程后、sigprocmask 执行前，子进程可能已经终止，SIGCHLD 已经到达。此时 sigchld_handler 会在 addjob 之前执行 deletejob，导致作业列表不一致。在 fork 之前阻塞 SIGCHLD，可以保证从 fork 到 addjob 完成这段时间内，SIGCHLD 不会被递送。

---

## Q4：waitfg 中 while 循环的退出条件应该是什么？

**答**：

```c
// 方法 1：检查 fgpid（推荐）
void waitfg(pid_t pid) {
    sigset_t mask;
    sigemptyset(&mask);
    while (fgpid(jobs) == pid) {
        sigsuspend(&mask);  // 等待信号到达
    }
}

// 说明：
// fgpid(jobs) 返回当前前台作业的 PID
// 当 sigchld_handler 处理了前台作业（deletejob 或改为 ST 状态），
// fgpid 就不再返回 pid → 循环退出

// 方法 2：使用全局标志（也可以）
volatile sig_atomic_t fg_stopped_or_reaped = 0;

void waitfg(pid_t pid) {
    while (!fg_stopped_or_reaped) {
        sigsuspend(&mask);
    }
    fg_stopped_or_reaped = 0;
}
```

---

## Q5：为什么用 sigsuspend 而不是 sleep(1) 或 pause()？

**答**：

**sleep(1) 的问题**：
- 响应延迟：子进程可能在 sleep 开始后 0.001 秒就终止了，但要等整整 1 秒 Shell 才能继续
- 浪费 CPU 时间片

**pause() 的问题**：
```c
while (fgpid(jobs) == pid) {
    // ★ 竞争窗口：如果 SIGCHLD 在这里到达... ★
    // fgpid 已经不等于 pid 了，但我们还没进入 pause
    pause();  // → 永远阻塞！因为不会再有 SIGCHLD 了
}
```

**sigsuspend 的解决方案**：
```c
sigset_t mask;
sigemptyset(&mask);
while (fgpid(jobs) == pid) {
    sigsuspend(&mask);
    // sigsuspend 原子地：
    // 1. 将信号掩码设为 mask（允许所有信号）
    // 2. 挂起进程等待信号
    // 3. 信号处理程序执行完毕后，恢复原来的信号掩码
    // 4. 返回
    // 步骤 1 和 2 是原子的，没有竞争窗口
}
```

---

## Q6：Shell Lab 中为什么要在父进程和子进程中都调用 setpgid？

**答**：

```c
pid = fork();
if (pid == 0) {
    setpgid(0, 0);  // 子进程中设置
    execve(...);
}
setpgid(pid, pid);   // 父进程中也设置
```

原因是竞争条件：我们不知道父进程还是子进程先运行。

- 如果只在子进程中调用：父进程可能在子进程调用 setpgid 之前就需要使用子进程的进程组（如发送信号）。此时子进程的 PGID 还没改变。
- 如果只在父进程中调用：子进程可能在父进程调用 setpgid 之前就开始运行（甚至 exec 之后），此时子进程的 PGID 还没改变。
- 在两处都调用可以保证：无论谁先运行，setpgid 都能完成。两处调用中总有一个是「冗余」的（已经设置过了），但这不是错误，冗余调用是无害的。

---

## Q7：kill(-pid, sig) 和 kill(pid, sig) 的区别是什么？在 Shell Lab 中应该用哪个？

**答**：
- `kill(pid, sig)`：发送信号给单个进程 pid
- `kill(-pid, sig)`：发送信号给进程组 ID 为 pid 的所有进程

在 Shell Lab 中应该用 `kill(-pid, sig)`，因为前台命令可能 fork 出子进程（如管道命令 `ls | grep foo`），这些子进程都在同一个进程组中。用户按 Ctrl+C 时，应该终止整个作业（进程组中的所有进程），而不仅仅是组长进程。

---

## Q8：为什么 sigchld_handler 中要用 WNOHANG | WUNTRACED？

**答**：
- `WNOHANG`：非阻塞。如果当前没有可回收的子进程，waitpid 立即返回 0，而不是阻塞等待。这很重要，因为 handler 中阻塞是不可接受的。
- `WUNTRACED`：也报告已停止（如被 SIGTSTP 停止）的子进程。没有 WUNTRACED，waitpid 只报告已终止的子进程，我们就无法知道子进程被停止了。Shell 需要知道停止事件来更新作业状态为 ST。

不加 WNOHANG 会导致 Shell 在 handler 中阻塞，如果有前台作业正在运行且所有后台作业都还活着，Shell 就会挂起。不加 WUNTRACED 会导致 Ctrl+Z 停止前台作业后，Shell 不知道作业已停止，无法正确更新状态。

---

## Q9：虚拟内存和物理内存的关系是什么？进程看到的地址是虚拟地址还是物理地址？

**答**：进程看到的所有地址都是虚拟地址。CPU 在访问内存时，MMU 通过查页表将虚拟地址翻译为物理地址。每个进程有自己的页表，所以同一个虚拟地址在不同进程中对应不同的物理地址。这就是为什么进程有「私有地址空间」的错觉。

虚拟内存的好处：
1. **隔离**：一个进程不能访问另一个进程的物理内存
2. **灵活**：物理内存可以按需分配，不需要连续
3. **高效**：不常用的页可以被换出到磁盘（swap），常用的页留在物理内存
4. **共享**：多个进程可以映射到同一个物理页（如共享库 libc.so）
5. **COW**：fork 后父子进程共享物理页，写时才复制

---

# 第五大块：Shell Lab 实战完整指导

---

## 一、整体架构

```
tsh 的整体工作流程：

main()
  │
  ├── 安装信号处理程序 (Signal)
  │     ├── SIGINT  → sigint_handler
  │     ├── SIGTSTP → sigtstp_handler
  │     ├── SIGCHLD → sigchld_handler
  │     └── SIGQUIT → sigquit_handler
  │
  ├── 初始化作业列表 (initjobs)
  │
  └── 主循环 (Read-Eval Loop)
        │
        ├── 打印提示符 tsh>
        │
        ├── 读取命令行 (fgets)
        │
        └── eval(cmdline)
              │
              ├── parseline(cmdline, argv)
              │     └── 返回 bg (是否后台作业)
              │
              ├── builtin_cmd(argv)
              │     ├── "quit" → exit(0)
              │     ├── "jobs" → listjobs()
              │     ├── "bg %N" / "fg %N" → do_bgfg()
              │     └── 不是内置命令 → 返回 0
              │
              └── 外部命令
                    ├── 阻塞 SIGCHLD
                    ├── fork()
                    │     ├── 子进程：setpgid + execve
                    │     └── 父进程：
                    │           ├── setpgid
                    │           ├── addjob
                    │           ├── 解除阻塞 SIGCHLD
                    │           └── bg ? 打印信息 : waitfg(pid)
                    └── (完成)
```

## 二、eval 函数实现步骤

```c
void eval(char *cmdline) {
    char *argv[MAXARGS];    // 参数列表
    int bg;                 // 前台还是后台
    pid_t pid;              // 子进程 PID
    sigset_t mask_all, mask_chld, prev_mask;
    
    // 第 1 步：解析命令行
    bg = parseline(cmdline, argv);
    
    // 第 2 步：忽略空行
    if (argv[0] == NULL)
        return;
    
    // 第 3 步：检查是否是内置命令
    if (builtin_cmd(argv))
        return;  // 如果是内置命令，builtin_cmd 已经执行了
    
    // 第 4 步：准备信号掩码
    sigfillset(&mask_all);         // 所有信号
    sigemptyset(&mask_chld);       // 空集
    sigaddset(&mask_chld, SIGCHLD); // 只有 SIGCHLD
    
    // 第 5 步：阻塞 SIGCHLD（防止竞争条件）
    sigprocmask(SIG_BLOCK, &mask_chld, &prev_mask);
    
    // 第 6 步：fork 子进程
    if ((pid = fork()) == 0) {
        // === 子进程 ===
        
        // 6a. 恢复子进程的信号掩码
        sigprocmask(SIG_SETMASK, &prev_mask, NULL);
        
        // 6b. 子进程创建新进程组
        setpgid(0, 0);
        
        // 6c. 执行外部程序
        if (execve(argv[0], argv, environ) < 0) {
            printf("%s: Command not found\n", argv[0]);
            _exit(1);  // 注意：用 _exit 而不是 exit
        }
    }
    
    // === 父进程 ===
    
    // 第 7 步：在父进程中也设置子进程的进程组（防止竞争）
    setpgid(pid, pid);
    
    // 第 8 步：添加到作业列表（在阻塞 SIGCHLD 的状态下）
    // 阻塞所有信号以保护共享数据结构
    sigprocmask(SIG_BLOCK, &mask_all, NULL);
    addjob(jobs, pid, bg ? BG : FG, cmdline);
    sigprocmask(SIG_SETMASK, &mask_chld, NULL);  // 恢复只阻塞 SIGCHLD
    
    // 第 9 步：解除 SIGCHLD 阻塞
    sigprocmask(SIG_SETMASK, &prev_mask, NULL);
    
    // 第 10 步：前台作业等待，后台作业打印信息
    if (!bg) {
        waitfg(pid);
    } else {
        printf("[%d] (%d) %s", pid2jid(pid), pid, cmdline);
    }
}
```

## 三、builtin_cmd 实现

```c
int builtin_cmd(char **argv) {
    if (!strcmp(argv[0], "quit")) {
        exit(0);
    }
    if (!strcmp(argv[0], "jobs")) {
        listjobs(jobs);
        return 1;
    }
    if (!strcmp(argv[0], "bg") || !strcmp(argv[0], "fg")) {
        do_bgfg(argv);
        return 1;
    }
    if (!strcmp(argv[0], "&")) {  // 忽略单独的 &
        return 1;
    }
    return 0;  // 不是内置命令
}
```

## 四、do_bgfg 实现

```c
void do_bgfg(char **argv) {
    struct job_t *job;
    int jid;
    pid_t pid;
    
    // 第 1 步：检查参数
    if (argv[1] == NULL) {
        printf("%s command requires PID or %%jobid argument\n", argv[0]);
        return;
    }
    
    // 第 2 步：解析 JID 或 PID
    if (argv[1][0] == '%') {
        // JID
        jid = atoi(&argv[1][1]);
        job = getjobjid(jobs, jid);
        if (job == NULL) {
            printf("%s: No such job\n", argv[1]);
            return;
        }
    } else if (isdigit(argv[1][0])) {
        // PID
        pid = atoi(argv[1]);
        job = getjobpid(jobs, pid);
        if (job == NULL) {
            printf("(%d): No such process\n", pid);
            return;
        }
    } else {
        printf("%s: argument must be a PID or %%jobid\n", argv[0]);
        return;
    }
    
    // 第 3 步：发送 SIGCONT 并更新状态
    kill(-(job->pid), SIGCONT);  // 向进程组发送 SIGCONT
    
    if (!strcmp(argv[0], "fg")) {
        job->state = FG;
        waitfg(job->pid);  // 等待前台作业完成
    } else {
        job->state = BG;
        printf("[%d] (%d) %s", job->jid, job->pid, job->cmdline);
    }
}
```

## 五、waitfg 实现（使用 sigsuspend）

```c
void waitfg(pid_t pid) {
    sigset_t mask;
    sigemptyset(&mask);
    
    // 循环等待，直到 pid 不再是前台进程
    while (fgpid(jobs) == pid) {
        sigsuspend(&mask);  // 原子地解除所有信号阻塞并挂起
    }
    // 不需要在这里做任何回收工作
    // 回收由 sigchld_handler 完成
}
```

## 六、sigchld_handler 实现

```c
void sigchld_handler(int sig) {
    int olderrno = errno;  // 保存 errno
    pid_t pid;
    int status;
    sigset_t mask_all, prev_mask;
    
    sigfillset(&mask_all);
    
    // 循环回收所有已终止/已停止的子进程
    while ((pid = waitpid(-1, &status, WNOHANG | WUNTRACED)) > 0) {
        // 阻塞所有信号，保护共享数据结构
        sigprocmask(SIG_BLOCK, &mask_all, &prev_mask);
        
        if (WIFEXITED(status)) {
            // 正常终止
            deletejob(jobs, pid);
        } else if (WIFSIGNALED(status)) {
            // 被信号终止
            // 注意：这里用 write 而不是 printf（信号安全）
            // 但 Shell Lab 中通常允许使用 printf
            printf("Job [%d] (%d) terminated by signal %d\n",
                   pid2jid(pid), pid, WTERMSIG(status));
            deletejob(jobs, pid);
        } else if (WIFSTOPPED(status)) {
            // 被信号停止
            printf("Job [%d] (%d) stopped by signal %d\n",
                   pid2jid(pid), pid, WSTOPSIG(status));
            struct job_t *job = getjobpid(jobs, pid);
            if (job != NULL)
                job->state = ST;
        }
        
        sigprocmask(SIG_SETMASK, &prev_mask, NULL);
    }
    
    errno = olderrno;  // 恢复 errno
}
```

## 七、sigint_handler 和 sigtstp_handler 实现

```c
void sigint_handler(int sig) {
    int olderrno = errno;
    pid_t pid = fgpid(jobs);  // 获取前台作业的 PID
    
    if (pid != 0) {
        kill(-pid, SIGINT);  // 向前台进程组发送 SIGINT
    }
    
    errno = olderrno;
}

void sigtstp_handler(int sig) {
    int olderrno = errno;
    pid_t pid = fgpid(jobs);  // 获取前台作业的 PID
    
    if (pid != 0) {
        kill(-pid, SIGTSTP);  // 向前台进程组发送 SIGTSTP
    }
    
    errno = olderrno;
}
```

## 八、6 类常见 Bug 的详细分析与修复

### Bug 1：竞争条件 —— addjob/deletejob 顺序

```
症状：Shell 偶尔挂起，或作业列表中出现"幽灵"作业
原因：sigchld_handler 在 addjob 之前执行了 deletejob
修复：在 fork 前阻塞 SIGCHLD，在 addjob 后解除阻塞
详细分析见：专题 15
```

### Bug 2：不回收所有僵尸进程

```c
// 错误代码：
void sigchld_handler(int sig) {
    pid_t pid = waitpid(-1, NULL, 0);  // 只回收一个，且阻塞
    deletejob(jobs, pid);
}

// 问题：
// 1. 每次只回收一个僵尸进程，但可能有多个同时终止
// 2. 使用 0（阻塞）而不是 WNOHANG，可能导致 Shell 挂起
// 3. 没有处理 WIFSTOPPED 情况

// 正确代码：
void sigchld_handler(int sig) {
    int olderrno = errno;
    pid_t pid;
    int status;
    while ((pid = waitpid(-1, &status, WNOHANG | WUNTRACED)) > 0) {
        // 处理每一个终止或停止的子进程
        // ...
    }
    errno = olderrno;
}
```

### Bug 3：忘记 setpgid(0,0)

```
症状：按 Ctrl+C 时 Shell 自己也被终止
      或者按 Ctrl+Z 时 Shell 自己也被暂停
原因：子进程和 Shell 在同一个进程组中，
      终端驱动将信号发送给整个前台进程组（包括 Shell）
修复：在子进程中调用 setpgid(0, 0)
```

### Bug 4：向错误的目标发送信号

```c
// 错误代码：
void sigint_handler(int sig) {
    pid_t pid = fgpid(jobs);
    kill(pid, SIGINT);  // 只发给进程组长
}

// 问题：如果前台作业 fork 了子进程，
// 子进程不会收到 SIGINT，变成孤儿进程

// 正确代码：
void sigint_handler(int sig) {
    pid_t pid = fgpid(jobs);
    if (pid != 0)
        kill(-pid, SIGINT);  // 发给整个进程组（注意负号）
}
```

### Bug 5：waitfg 中使用 sleep 或 busy loop

```c
// 错误代码 1（太慢）：
void waitfg(pid_t pid) {
    while (fgpid(jobs) == pid)
        sleep(1);  // 最多延迟 1 秒
}

// 错误代码 2（浪费 CPU）：
void waitfg(pid_t pid) {
    while (fgpid(jobs) == pid)
        ;  // 忙等待，100% CPU 占用
}

// 错误代码 3（竞争条件）：
void waitfg(pid_t pid) {
    while (fgpid(jobs) == pid)
        pause();  // 可能永远阻塞
}

// 正确代码：
void waitfg(pid_t pid) {
    sigset_t mask;
    sigemptyset(&mask);
    while (fgpid(jobs) == pid)
        sigsuspend(&mask);  // 无竞争、不浪费 CPU、即时响应
}
```

### Bug 6：子进程中没有恢复信号掩码

```c
// 错误代码：
sigprocmask(SIG_BLOCK, &mask_chld, &prev);
pid = fork();
if (pid == 0) {
    // 忘记恢复信号掩码！
    // 子进程继承了父进程的信号掩码，SIGCHLD 被阻塞
    setpgid(0, 0);
    execve(argv[0], argv, environ);
    // 如果子进程执行的程序也 fork 子进程，
    // 它的 SIGCHLD 处理会有问题
}

// 正确代码：
if (pid == 0) {
    sigprocmask(SIG_SETMASK, &prev, NULL);  // 恢复原始信号掩码
    setpgid(0, 0);
    execve(argv[0], argv, environ);
    printf("%s: Command not found\n", argv[0]);
    _exit(1);
}
```

## 九、trace 文件逐步调试方法

### 9.1 trace 文件格式

tsh 的测试驱动程序 `sdriver.pl` 通过 trace 文件向 tsh 发送命令和信号。

```
trace 文件中的指令：

  CLOSE     - 关闭 Shell 的标准输入（模拟 Ctrl+D / EOF）
  WAIT      - 等待 Shell 输出稳定
  SLEEP N   - 等待 N 秒
  INT       - 向 Shell 发送 SIGINT（模拟 Ctrl+C）
  TSTP      - 向 Shell 发送 SIGTSTP（模拟 Ctrl+Z）
  QUIT      - 向 Shell 发送 SIGQUIT（优雅终止）
  /command  - 向 Shell 发送一条命令
  # comment - 注释
```

### 9.2 运行单个 trace 文件

```bash
# 运行你的 Shell (tsh) 处理 trace01
$ make test01
./sdriver.pl -t trace01.txt -s ./tsh -a "-p"

# 运行参考 Shell (tshref) 处理 trace01
$ make rtest01
./sdriver.pl -t trace01.txt -s ./tshref -a "-p"

# 对比输出
$ diff <(make test01 2>&1) <(make rtest01 2>&1)
```

### 9.3 trace 文件对应的功能

```
trace01 - EOF 正确终止 Shell
trace02 - 运行内置命令 quit
trace03 - 运行前台作业（/bin/echo）
trace04 - 运行后台作业
trace05 - jobs 内置命令
trace06 - 前台作业收到 SIGINT (Ctrl+C)
trace07 - 仅前台作业收到 SIGINT
trace08 - 前台作业收到 SIGTSTP (Ctrl+Z)
trace09 - bg 命令恢复已停止的后台作业
trace10 - fg 命令恢复已停止的前台作业
trace11 - 向前台进程组中的每个进程发送 SIGINT
trace12 - 向前台进程组中的每个进程发送 SIGTSTP
trace13 - 重启每个已停止的进程
trace14 - 简单的错误处理
trace15 - 综合测试：bg/fg/jobs/kill
trace16 - 更多测试：多个作业
```

### 9.4 逐步调试策略

```
推荐的调试顺序：

1. trace01-02：最基础，确保 Shell 能启动和退出
   重点：eval 的空命令处理、builtin_cmd 的 quit

2. trace03-04：前台和后台作业
   重点：fork/execve/setpgid 的正确实现、addjob
   常见问题：Command not found（路径问题）、Shell 挂起（waitfg 问题）

3. trace05：jobs 命令
   重点：builtin_cmd 中 jobs 的处理、listjobs 函数

4. trace06-08：信号处理
   重点：sigint_handler、sigtstp_handler、sigchld_handler
   常见问题：信号发给了错误的目标（忘记负号）、
           不处理 WIFSTOPPED、不处理 WIFSIGNALED

5. trace09-10：bg/fg 命令
   重点：do_bgfg 的完整实现
   常见问题：参数解析错误、忘记发 SIGCONT、
           fg 后没有调用 waitfg

6. trace11-16：综合测试
   重点：所有功能的集成、边界条件
   常见问题：竞争条件、信号不排队、多作业管理
```

### 9.5 使用 GDB 调试

```bash
# 编译带调试信息的版本
$ make clean && make CFLAGS="-g -Wall -O0"

# 启动 GDB
$ gdb ./tsh

# 常用 GDB 命令：
(gdb) break eval               # 在 eval 函数设置断点
(gdb) break sigchld_handler     # 在信号处理程序设置断点
(gdb) run -p                    # 以 -p（不打印提示符）模式运行
(gdb) handle SIGCHLD nostop     # 不因 SIGCHLD 暂停（如果不需要调试 handler）
(gdb) handle SIGINT nostop      # 不因 SIGINT 暂停
(gdb) handle SIGTSTP nostop     # 不因 SIGTSTP 暂停
(gdb) print jobs                # 打印作业列表
(gdb) print jobs[0]             # 打印第一个作业
```

### 9.6 常用调试技巧

```c
// 1. 在关键位置添加调试输出
// 使用 write 而不是 printf（信号安全）
void debug_print(const char *msg) {
    if (verbose) {
        write(STDOUT_FILENO, msg, strlen(msg));
    }
}

// 2. 在 eval 中打印解析结果
if (verbose) {
    printf("eval: cmdline='%s', bg=%d\n", cmdline, bg);
    for (int i = 0; argv[i] != NULL; i++)
        printf("  argv[%d]='%s'\n", i, argv[i]);
}

// 3. 使用 -v 选项运行
$ ./tsh -v -p < trace06.txt

// 4. 打印信号掩码（调试信号阻塞问题）
void print_mask() {
    sigset_t mask;
    sigprocmask(SIG_SETMASK, NULL, &mask);
    printf("Blocked signals: ");
    if (sigismember(&mask, SIGCHLD)) printf("SIGCHLD ");
    if (sigismember(&mask, SIGINT))  printf("SIGINT ");
    if (sigismember(&mask, SIGTSTP)) printf("SIGTSTP ");
    printf("\n");
}
```

---

# 附录 A：关键概念速查表

```
┌───────────────────────┬──────────────────────────────────────────────┐
│ 概念                   │ 一句话解释                                    │
├───────────────────────┼──────────────────────────────────────────────┤
│ 异常                   │ 控制流的突变，响应处理器状态的变化               │
│ 中断                   │ 异步异常，来自外部 I/O 设备                     │
│ 陷阱                   │ 同步异常，有意触发（系统调用）                   │
│ 故障                   │ 同步异常，可恢复的错误（如缺页）                 │
│ 终止                   │ 同步异常，不可恢复的致命错误                     │
│ 进程                   │ 运行中程序的实例                               │
│ 上下文切换             │ 从一个进程切换到另一个进程                       │
│ fork                   │ 创建子进程（调用一次，返回两次）                 │
│ execve                 │ 加载并运行新程序（调用一次，成功时不返回）         │
│ waitpid                │ 等待并回收子进程                               │
│ 僵尸进程               │ 已终止但未被父进程回收的进程                     │
│ 信号                   │ 软件层面的异常通知机制                           │
│ 信号阻塞               │ 暂时搁置信号，不递送                            │
│ 信号忽略               │ 递送信号但不做任何处理                           │
│ 可重入函数             │ 可以被信号处理程序安全调用的函数                  │
│ volatile               │ 告诉编译器不要优化，每次从内存读取               │
│ sig_atomic_t           │ 保证原子读写的整数类型                           │
│ setpgid                │ 设置进程组 ID                                  │
│ 进程组                 │ 一组可以一起接收信号的进程                       │
│ 会话                   │ 进程组的集合，关联一个控制终端                   │
│ sigsuspend             │ 原子地设置信号掩码并挂起                        │
│ setjmp/longjmp         │ 非本地跳转，跨函数的 goto                       │
│ COW (写时复制)          │ fork 后共享页面，写入时才复制                    │
│ MMU                    │ 内存管理单元，虚拟地址到物理地址的翻译            │
│ TLB                    │ 页表缓存，加速地址翻译                           │
│ ASLR                   │ 地址空间随机化，安全防护                         │
└───────────────────────┴──────────────────────────────────────────────┘
```

---

# 附录 B：推荐的进一步阅读

```
1. CSAPP 第 8 章原文 — 基础中的基础
2. Advanced Programming in the UNIX Environment (APUE), W. Richard Stevens
   — Unix 系统编程的圣经，信号和进程控制的权威参考
3. Linux Kernel Development, Robert Love
   — 从内核角度理解进程调度、信号递送
4. The Linux Programming Interface, Michael Kerrisk
   — 最全面的 Linux 系统编程参考书
5. man 7 signal — Linux 信号手册
6. man 2 sigaction — sigaction 系统调用详解
7. man 2 waitpid — waitpid 系统调用详解
8. man 2 setpgid — 进程组管理 API
9. man 2 setsid — 会话管理 API
10. man 7 credentials — 进程凭证（UID/GID/PID/PGID/SID）全景
```

---

# 附录 C：Shell Lab 完整检查清单

```
提交前检查清单：

□ eval 函数：
  □ 解析命令行（parseline）
  □ 处理空命令
  □ 检查内置命令（builtin_cmd）
  □ fork 前阻塞 SIGCHLD
  □ 子进程中恢复信号掩码
  □ 子进程中调用 setpgid(0,0)
  □ 子进程中 execve 失败用 _exit
  □ 父进程中调用 setpgid(pid, pid)
  □ 父进程中 addjob
  □ 父进程中解除 SIGCHLD 阻塞
  □ 前台作业调用 waitfg
  □ 后台作业打印 [jid] (pid) cmdline

□ builtin_cmd 函数：
  □ quit → exit(0)
  □ jobs → listjobs
  □ bg/fg → do_bgfg
  □ & → 返回 1（忽略）
  □ 非内置命令 → 返回 0

□ do_bgfg 函数：
  □ 参数为空时打印错误
  □ 支持 %jid 和 pid 两种格式
  □ 无效的 jid/pid 打印错误
  □ 发送 SIGCONT 给进程组
  □ fg 更新状态为 FG 并 waitfg
  □ bg 更新状态为 BG 并打印信息

□ waitfg 函数：
  □ 使用 sigsuspend（不是 sleep/pause/busy-loop）
  □ 循环条件正确

□ sigchld_handler：
  □ 保存/恢复 errno
  □ while 循环 + WNOHANG + WUNTRACED
  □ 处理 WIFEXITED（deletejob）
  □ 处理 WIFSIGNALED（打印信息 + deletejob）
  □ 处理 WIFSTOPPED（打印信息 + 更新状态为 ST）

□ sigint_handler：
  □ 保存/恢复 errno
  □ 获取前台 PID
  □ kill(-pid, SIGINT)（注意负号）
  □ pid 为 0 时不发信号

□ sigtstp_handler：
  □ 保存/恢复 errno
  □ 获取前台 PID
  □ kill(-pid, SIGTSTP)（注意负号）
  □ pid 为 0 时不发信号

□ 通过所有 16 个 trace 文件测试
□ 与 tshref 的输出一致（PID 可以不同）
```

---

> 本文档共覆盖 5 大板块、15 个深度专题、40+ 道自检问题、9 个常见困惑、6 类 Bug 分析，以及完整的 Shell Lab 实现指南。每个知识点按照「概念 → 原理 → 代码示例 → 误区 → 练习」的结构组织，力求全面、深入、可操作。

---

*文档结束*
