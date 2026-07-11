# CSAPP 第10-12章：系统级 I/O、网络编程与并发——补充讲解与深度解析

> 本笔记覆盖 CS:APP 第10章（系统级I/O）、第11章（网络编程）、第12章（并发编程），
> 并关联 Proxy Lab 的实际应用。这三章构成了从"读写文件"到"网络通信"再到"高性能并发服务器"的完整知识链。

---

## 目录

- [第10章 系统级I/O](#第10章-系统级io)
- [第11章 网络编程](#第11章-网络编程)
- [第12章 并发编程](#第12章-并发编程)
- [三章知识与 Proxy Lab 的关联](#三章知识与-proxy-lab-的关联)
- [实际工程应用](#实际工程应用)

---

# 第10章 系统级I/O

## 10.1 Unix I/O 概述

Unix 的核心哲学：**一切皆文件（Everything is a file）**。

所有的 I/O 设备——网络、磁盘、终端——都被抽象为文件。内核提供了一组简单而统一的接口，
称为 Unix I/O，使得所有输入输出都能以统一方式处理。

一个应用程序通过以下步骤执行 I/O：

1. **打开文件**：应用程序请求内核打开文件，内核返回一个非负整数——**文件描述符**
2. **定位**：内核维护文件位置 k，初始为 0，应用可通过 `lseek` 改变
3. **读写文件**：读操作从文件复制字节到内存，写操作从内存复制字节到文件
4. **关闭文件**：应用通知内核关闭文件，内核释放相关资源

## 10.2 文件描述符（File Descriptor）

文件描述符是内核用来标识已打开文件的非负整数。

```
+------------------------------------------+
|        每个进程的文件描述符               |
+------+-----------------------------------+
|  fd  |           指向的对象               |
+------+-----------------------------------+
|  0   |  标准输入  (STDIN_FILENO)          |
|  1   |  标准输出  (STDOUT_FILENO)         |
|  2   |  标准错误  (STDERR_FILENO)         |
|  3   |  用户打开的第一个文件              |
|  4   |  用户打开的第二个文件              |
| ...  |  ...                              |
+------+-----------------------------------+
```

**关键规则**：`open` 总是返回当前未使用的最小描述符编号。这一规则是 I/O 重定向的基础。

## 10.3 打开和关闭文件

### open 函数

```c
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

int open(const char *pathname, int flags, mode_t mode);
// 返回：成功返回新文件描述符（当前最小可用），出错返回 -1
```

**flags 参数**（可用按位或组合）：

| 标志         | 含义                                       |
|:-------------|:-------------------------------------------|
| `O_RDONLY`   | 只读打开                                   |
| `O_WRONLY`   | 只写打开                                   |
| `O_RDWR`     | 读写打开                                   |
| `O_CREAT`    | 文件不存在则创建                           |
| `O_TRUNC`    | 如果文件已存在则截断为空                   |
| `O_APPEND`   | 每次写之前将文件位置设到末尾               |

**mode 参数**（仅当 `O_CREAT` 时有效，指定新文件权限）：

| 掩码       | 含义              |
|:-----------|:------------------|
| `S_IRUSR`  | 所有者可读        |
| `S_IWUSR`  | 所有者可写        |
| `S_IXUSR`  | 所有者可执行      |
| `S_IRGRP`  | 组可读            |
| `S_IWGRP`  | 组可写            |
| `S_IXGRP`  | 组可执行          |
| `S_IROTH`  | 其他人可读        |
| `S_IWOTH`  | 其他人可写        |
| `S_IXOTH`  | 其他人可执行      |

实际权限 = `mode & ~umask`。例如 umask 为 `022`，mode 为 `0666`，则实际权限为 `0644`。

```c
// 典型用法
int fd = open("foo.txt", O_WRONLY | O_CREAT | O_TRUNC, 0644);
if (fd < 0) {
    perror("open");
    exit(1);
}
```

### close 函数

```c
#include <unistd.h>

int close(int fd);
// 返回：成功返回 0，出错返回 -1
```

关闭一个已关闭的描述符会出错。关闭描述符后，该编号可被后续 `open` 复用。

## 10.4 读和写文件

### read 函数

```c
#include <unistd.h>

ssize_t read(int fd, void *buf, size_t n);
// 返回：成功返回读取的字节数，EOF 返回 0，出错返回 -1
```

从描述符 fd 的当前位置复制最多 n 个字节到内存位置 buf，然后更新文件位置。

### write 函数

```c
#include <unistd.h>

ssize_t write(int fd, const void *buf, size_t n);
// 返回：成功返回写入的字节数，出错返回 -1
```

从内存位置 buf 复制最多 n 个字节到描述符 fd 的当前位置。

### 注意 ssize_t 和 size_t 的区别

- `size_t`：无符号长整型（`unsigned long`），表示大小
- `ssize_t`：有符号长整型（`long`），因为需要返回 -1 表示错误

## 10.5 不足值（Short Count）

**这是一个极其重要的概念！**

`read` 和 `write` 传送的字节数有时会比请求的少，这种情况称为**不足值（short count）**。
不足值不代表出错！

**产生不足值的情况**：

| 场景                   | 说明                                                |
|:-----------------------|:----------------------------------------------------|
| 读到 EOF               | 文件剩余字节不足 n，只能返回剩余数量                |
| 从终端读               | 每次最多传送一个文本行                              |
| 从网络 socket 读       | 内核缓冲区可能未收到足够数据，随时可能返回不足值    |
| 向网络 socket 写       | 内核发送缓冲区满，可能只写入部分数据                |
| 被信号中断             | read/write 被信号处理程序中断后返回                  |

**关键结论**：在网络编程中，你**必须**反复调用 read/write 来处理不足值。
这正是 RIO 包存在的意义。

## 10.6 RIO（Robust I/O）包

RIO 包自动处理不足值，提供两类函数：

### 10.6.1 无缓冲的输入输出函数

适用于在网络 socket 上传输二进制数据。

#### rio_readn —— 确保读满 n 字节

```c
/*
 * rio_readn - 从 fd 读取恰好 n 个字节到 usrbuf
 *
 * 核心思想：用循环不断调用 read，直到读满 n 字节或遇到 EOF
 * 返回值：成功返回传送的字节数（EOF 时可能 < n），出错返回 -1
 */
ssize_t rio_readn(int fd, void *usrbuf, size_t n)
{
    size_t nleft = n;       // 还剩多少字节要读
    ssize_t nread;          // 本次 read 实际读取的字节数
    char *bufp = usrbuf;    // 当前写入位置

    while (nleft > 0) {
        if ((nread = read(fd, bufp, nleft)) < 0) {
            if (errno == EINTR)  // 被信号中断，不算错误
                nread = 0;       // 重新调用 read
            else
                return -1;       // 真正的错误
        }
        else if (nread == 0)     // EOF
            break;

        nleft -= nread;          // 更新剩余量
        bufp += nread;           // 移动缓冲区指针
    }
    return (n - nleft);          // 实际读取的总字节数
}
```

#### rio_writen —— 确保写满 n 字节

```c
/*
 * rio_writen - 从 usrbuf 写恰好 n 个字节到 fd
 *
 * 注意：rio_writen 绝不会返回不足值（除非出错）
 * 因为写操作不会遇到 EOF
 */
ssize_t rio_writen(int fd, void *usrbuf, size_t n)
{
    size_t nleft = n;
    ssize_t nwritten;
    char *bufp = usrbuf;

    while (nleft > 0) {
        if ((nwritten = write(fd, bufp, nleft)) <= 0) {
            if (errno == EINTR)   // 被信号中断
                nwritten = 0;
            else
                return -1;
        }
        nleft -= nwritten;
        bufp += nwritten;
    }
    return n;                     // 永远返回 n
}
```

### 10.6.2 带缓冲的输入函数

适用于读取文本行（如 HTTP 头部）。

**为什么需要缓冲？**

如果用 `read` 每次只读一个字节来寻找 `\n`，效率极低（每个字节一次系统调用）。
RIO 的解决方案是维护一个内部缓冲区，一次从内核读取大块数据到缓冲区，
然后从缓冲区中逐字节取出给用户。

#### RIO 内部缓冲区结构

```c
#define RIO_BUFSIZE 8192

typedef struct {
    int rio_fd;                // 关联的文件描述符
    int rio_cnt;               // 缓冲区中未读的字节数
    char *rio_bufptr;          // 指向缓冲区中下一个未读字节
    char rio_buf[RIO_BUFSIZE]; // 内部缓冲区
} rio_t;
```

```
缓冲区状态示意：

rio_buf:
+------+------+------+------+------+------+------+------+
| 已读 | 已读 | 已读 |  H   |  e   |  l   |  l   |  o   |
+------+------+------+------+------+------+------+------+
                      ^                                   ^
                      |                                   |
                  rio_bufptr                          缓冲区末尾
                  (rio_cnt = 5，还有5个字节未读)
```

#### rio_readinitb —— 初始化

```c
void rio_readinitb(rio_t *rp, int fd)
{
    rp->rio_fd = fd;
    rp->rio_cnt = 0;           // 缓冲区为空
    rp->rio_bufptr = rp->rio_buf;
}
```

#### rio_read —— 内部缓冲读取（核心函数）

```c
/*
 * rio_read - RIO 内部函数，带缓冲版本的 read
 *
 * 工作流程：
 * 1. 如果缓冲区为空，调用 read 填充缓冲区
 * 2. 从缓冲区复制 min(n, rio_cnt) 个字节到用户缓冲区
 *
 * 这是 rio_readlineb 和 rio_readnb 的基础
 */
static ssize_t rio_read(rio_t *rp, char *usrbuf, size_t n)
{
    int cnt;

    while (rp->rio_cnt <= 0) {   // 缓冲区为空，需要填充
        rp->rio_cnt = read(rp->rio_fd, rp->rio_buf, sizeof(rp->rio_buf));

        if (rp->rio_cnt < 0) {
            if (errno != EINTR)  // 不是被信号中断
                return -1;
            // 被信号中断，继续循环重试
        }
        else if (rp->rio_cnt == 0)  // EOF
            return 0;
        else
            rp->rio_bufptr = rp->rio_buf; // 重置缓冲区指针
    }

    // 从缓冲区取出数据
    cnt = n;
    if (rp->rio_cnt < n)         // 缓冲区数据不够
        cnt = rp->rio_cnt;
    memcpy(usrbuf, rp->rio_bufptr, cnt);
    rp->rio_bufptr += cnt;       // 移动缓冲区指针
    rp->rio_cnt -= cnt;          // 减少未读计数
    return cnt;
}
```

#### rio_readlineb —— 读取一行

```c
/*
 * rio_readlineb - 从 rp 读取一个文本行（包括换行符），存入 usrbuf
 *
 * 最多读 maxlen-1 个字符（最后一个位置留给 '\0'）
 * 遇到换行符 '\n' 或达到 maxlen-1 时停止
 *
 * 在 HTTP 解析中极为常用：读取请求行、头部行等
 */
ssize_t rio_readlineb(rio_t *rp, void *usrbuf, size_t maxlen)
{
    int n, rc;
    char c, *bufp = usrbuf;

    for (n = 1; n < maxlen; n++) {
        if ((rc = rio_read(rp, &c, 1)) == 1) {  // 每次读一个字符
            *bufp++ = c;
            if (c == '\n') {    // 遇到换行符，停止
                n++;
                break;
            }
        }
        else if (rc == 0) {     // EOF
            if (n == 1)
                return 0;       // 没读到任何数据
            else
                break;          // 读到了一些数据
        }
        else
            return -1;          // 错误
    }
    *bufp = 0;                  // null 终止
    return n - 1;               // 不包含 null 终止符
}
```

> **为什么 rio_readlineb 效率不低？**
> 虽然它每次调用 `rio_read` 只取一个字符，但 `rio_read` 从内部缓冲区取数据，
> 而不是每次调用 `read` 系统调用。真正的系统调用只在缓冲区耗尽时才发生，
> 一次 `read` 可以填充 8192 字节。

## 10.7 文件共享

Linux 内核用三个相关的数据结构来表示打开的文件：

### 三级结构 ASCII 图

```
  进程 A (pid=100)            进程 B (pid=200)
 ┌──────────────┐           ┌──────────────┐
 │ 描述符表     │           │ 描述符表     │
 │ (每进程独有) │           │ (每进程独有) │
 │              │           │              │
 │ fd0 ─────────┼──┐       │ fd0 ─────────┼──────────────┐
 │ fd1 ─────────┼──┼─┐     │ fd1 ─────────┼──┐           │
 │ fd2 ────┐    │  │ │     │ fd3 ─────────┼──┼─┐         │
 │ fd3 ──┐ │    │  │ │     └──────────────┘  │ │         │
 └───────┼─┼────┘  │ │                       │ │         │
         │ │       │ │                       │ │         │
         v v       v v                       v v         v
 ┌───────────────────────────────────────────────────────────┐
 │                    打开文件表                             │
 │                  (所有进程共享)                           │
 │                                                           │
 │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐      │
 │  │ 文件表项 A  │  │ 文件表项 B  │  │ 文件表项 C  │      │
 │  │             │  │             │  │             │      │
 │  │ refcnt = 1  │  │ refcnt = 2  │  │ refcnt = 2  │      │
 │  │ pos = 100   │  │ pos = 200   │  │ pos = 0     │      │
 │  │ flags: R    │  │ flags: RW   │  │ flags: R    │      │
 │  │ v-node ──┐  │  │ v-node ──┐  │  │ v-node ──┐  │      │
 │  └──────────┼──┘  └──────────┼──┘  └──────────┼──┘      │
 │             │               │               │           │
 └─────────────┼───────────────┼───────────────┼───────────┘
               │               │               │
               v               v               v
 ┌───────────────────────────────────────────────────────────┐
 │                     v-node 表                             │
 │                  (所有进程共享)                           │
 │                                                           │
 │  ┌────────────────┐        ┌────────────────┐            │
 │  │ v-node (文件X) │        │ v-node (文件Y) │            │
 │  │                │        │                │            │
 │  │ stat 信息      │        │ stat 信息      │            │
 │  │ - st_mode      │        │ - st_mode      │            │
 │  │ - st_size      │        │ - st_size      │            │
 │  │ - st_blocks    │        │ - st_blocks    │            │
 │  └────────────────┘        └────────────────┘            │
 └───────────────────────────────────────────────────────────┘
```

### 三级结构详解

| 层级         | 所属范围     | 包含内容                          | 关键点                              |
|:-------------|:-------------|:----------------------------------|:------------------------------------|
| 描述符表     | 每进程独有   | fd -> 文件表项的指针              | fork 后子进程复制父进程的描述符表   |
| 打开文件表   | 全局共享     | 文件位置 pos、引用计数、访问模式  | fork 后父子共享同一文件表项         |
| v-node 表    | 全局共享     | 文件的 stat 信息（大小、类型等）  | 同一文件只有一个 v-node             |

### 文件共享的三种典型场景

**场景1：同一进程两次 open 同一文件**
- 创建两个不同的文件表项（各自有独立的文件位置）
- 但指向同一个 v-node
- 各自读写互不影响位置

**场景2：fork 后父子进程共享**
- 子进程复制父进程的描述符表
- 父子的 fd 指向相同的文件表项
- 共享文件位置！一方读写会影响另一方的位置
- 引用计数变为 2

**场景3：dup2 重定向**
- 使两个不同的 fd 指向同一个文件表项

## 10.8 I/O 重定向

### dup2 函数

```c
#include <unistd.h>

int dup2(int oldfd, int newfd);
// 返回：成功返回 newfd，出错返回 -1
```

`dup2(4, 1)` 的效果：让 fd1 指向 fd4 所指向的文件表项。

```
重定向前：                       重定向后 dup2(4, 1)：
fd0 -> 文件表项A (stdin)        fd0 -> 文件表项A (stdin)
fd1 -> 文件表项B (stdout)       fd1 -> 文件表项D (文件X)  ← 重定向了！
fd2 -> 文件表项C (stderr)       fd2 -> 文件表项C (stderr)
fd3 -> ...                     fd3 -> ...
fd4 -> 文件表项D (文件X)        fd4 -> 文件表项D (文件X)
```

**重定向的典型应用**：

```c
// 将标准输出重定向到文件
int fd = open("output.txt", O_WRONLY | O_CREAT | O_TRUNC, 0644);
dup2(fd, STDOUT_FILENO);  // fd1 现在指向 output.txt
printf("This goes to file!\n");  // printf 写到文件而非屏幕
close(fd);  // 关闭多余的描述符，fd1 仍然有效
```

## 10.9 标准 I/O vs Unix I/O 选择指南

```
                    ┌─────────────────┐
                    │   应用程序       │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              v              v              v
     ┌────────────┐  ┌─────────────┐  ┌──────────┐
     │ 标准 I/O   │  │   RIO       │  │ Unix I/O │
     │ fopen      │  │ rio_readn   │  │ open     │
     │ fread      │  │ rio_writen  │  │ read     │
     │ fprintf    │  │ rio_readlineb│ │ write    │
     │ fgets      │  │             │  │ close    │
     │ ...        │  │             │  │ ...      │
     └──────┬─────┘  └──────┬──────┘  └────┬─────┘
            │               │              │
            └───────────────┼──────────────┘
                            v
                 ┌──────────────────┐
                 │  内核 (系统调用)  │
                 └──────────────────┘
```

### 选择建议

| 场景                     | 推荐方案        | 原因                                             |
|:-------------------------|:----------------|:-------------------------------------------------|
| 磁盘文件、普通读写       | 标准 I/O        | 有缓冲，使用方便                                 |
| 网络 socket              | RIO             | 标准 I/O 在 socket 上有限制（不能用 fgets 后 fputs）|
| 信号处理程序中           | Unix I/O        | 标准 I/O 不是异步信号安全的                      |
| 读取元数据（stat）       | Unix I/O        | 标准 I/O 没有对应函数                            |
| 高性能 I/O               | Unix I/O        | 标准 I/O 缓冲可能带来额外开销                    |

### 标准 I/O 在网络 socket 上的问题

**限制**：对同一个流不能在输出函数后紧跟输入函数，除非中间调用了 `fflush`、`fseek`、`fsetpos` 或 `rewind`。
但 `fseek` 和 `fsetpos` 对 socket 不合法！

**错误的做法**：
```c
FILE *fp = fdopen(sockfd, "r+");
fputs("request\r\n", fp);
fgets(buf, MAXLINE, fp);   // 未定义行为！
```

**正确的做法（使用 RIO）**：
```c
rio_t rio;
rio_readinitb(&rio, sockfd);
rio_writen(sockfd, "request\r\n", strlen("request\r\n"));
rio_readlineb(&rio, buf, MAXLINE);  // 安全！
```

---

# 第11章 网络编程

## 11.1 客户端-服务器模型

```
客户端进程                        服务器进程
┌──────────┐                    ┌──────────┐
│          │  1. 发送请求        │          │
│  Client  │ ─────────────────> │  Server  │
│          │                    │          │
│          │  2. 返回响应        │          │
│          │ <───────────────── │          │
│          │                    │          │
└──────────┘                    └──────────┘

每个网络应用都是基于客户端-服务器模型的：
- 一个服务器进程 + 一个或多个客户端进程
- 服务器管理某种资源（文件、数据库、计算能力等）
- 服务器通过操作资源来为客户端提供服务
- 一台主机可以同时运行多个客户端和服务器
```

**基本事务（Transaction）流程**：

1. 客户端向服务器发送请求（request）
2. 服务器收到请求后进行处理
3. 服务器向客户端发送响应（response）
4. 客户端收到响应后进行处理

## 11.2 网络

从程序员的角度，网络就是一种 I/O 设备：

```
                  ┌────────────────────────────┐
                  │          CPU               │
                  └──────────┬─────────────────┘
                             │ 系统总线
                  ┌──────────┴─────────────────┐
                  │      I/O 桥                │
                  └──────────┬─────────────────┘
                             │ I/O 总线
          ┌──────────────────┼──────────────────┐
          │                  │                  │
     ┌────┴────┐       ┌────┴────┐        ┌────┴────┐
     │  磁盘   │       │  显卡   │        │ 网络适配器│
     └─────────┘       └─────────┘        └────┬────┘
                                               │
                                          ┌────┴────┐
                                          │  网络   │
                                          └─────────┘
```

## 11.3 IP 地址

IP 地址是一个 32 位无符号整数（IPv4）。

```c
// IP 地址结构
struct in_addr {
    uint32_t s_addr;  // 网络字节序（大端法）
};
```

### 网络字节序

**关键概念**：网络协议规定使用**大端法（Big-Endian）**，称为网络字节序。

```
数值 0x01020304 在不同字节序中的存储：

大端法（网络字节序）：
地址:  0x100  0x101  0x102  0x103
内容:   01     02     03     04     （高位在低地址）

小端法（x86 主机字节序）：
地址:  0x100  0x101  0x102  0x103
内容:   04     03     02     01     （低位在低地址）
```

**字节序转换函数**：

```c
#include <arpa/inet.h>

// h = host, n = network, l = long(32位), s = short(16位)
uint32_t htonl(uint32_t hostlong);    // 主机 -> 网络 (32位)
uint16_t htons(uint16_t hostshort);   // 主机 -> 网络 (16位)
uint32_t ntohl(uint32_t netlong);     // 网络 -> 主机 (32位)
uint16_t ntohs(uint16_t netshort);    // 网络 -> 主机 (16位)
```

**IP 地址字符串转换**：

```c
#include <arpa/inet.h>

// 点分十进制字符串 -> 网络字节序二进制
int inet_pton(int af, const char *src, void *dst);
// 返回：成功 1，src 无效 0，出错 -1

// 网络字节序二进制 -> 点分十进制字符串
const char *inet_ntop(int af, const void *src, char *dst, socklen_t size);
// 返回：成功返回 dst 指针，出错返回 NULL

// p = presentation (字符串), n = numeric (二进制)
```

## 11.4 域名系统（DNS）

DNS 是一个分布式数据库，提供域名到 IP 地址的映射。

```
DNS 层次结构：

                    ┌───────┐
                    │   .   │  根域
                    └───┬───┘
              ┌─────────┼─────────┐
              v         v         v
          ┌──────┐  ┌──────┐  ┌──────┐
          │ .com │  │ .edu │  │ .org │  顶级域
          └──┬───┘  └──┬───┘  └──────┘
         ┌───┴───┐     │
         v       v     v
    ┌────────┐ ┌────┐ ┌─────┐
    │ google │ │ cmu│ │ mit │         二级域
    └────────┘ └─┬──┘ └─────┘
                 │
            ┌────┴────┐
            v         v
        ┌──────┐  ┌──────┐
        │  cs  │  │  ece │            子域
        └──────┘  └──────┘
```

### DNS 查询过程

```
客户端应用               本地 DNS 服务器         根/TLD/权威 DNS
    │                        │                       │
    │  1. 查询 www.cmu.edu   │                       │
    │ ─────────────────────> │                       │
    │                        │  2. 查询根 DNS         │
    │                        │ ────────────────────> │
    │                        │  3. 返回 .edu DNS     │
    │                        │ <──────────────────── │
    │                        │  4. 查询 .edu DNS     │
    │                        │ ────────────────────> │
    │                        │  5. 返回 cmu.edu DNS  │
    │                        │ <──────────────────── │
    │                        │  6. 查询 cmu.edu DNS  │
    │                        │ ────────────────────> │
    │                        │  7. 返回 IP 地址      │
    │                        │ <──────────────────── │
    │  8. 返回 IP 地址       │                       │
    │ <───────────────────── │                       │
```

## 11.5 套接字接口（Socket Interface）

套接字接口是一组函数，配合 Unix I/O 函数，用来创建网络应用。

### 套接字地址结构

```c
// 通用套接字地址（connect、bind、accept 的参数类型）
struct sockaddr {
    uint16_t sa_family;     // 协议族（AF_INET 等）
    char     sa_data[14];   // 地址数据
};

// Internet 套接字地址（实际使用的结构）
struct sockaddr_in {
    uint16_t        sin_family;   // AF_INET
    uint16_t        sin_port;     // 端口号（网络字节序）
    struct in_addr  sin_addr;     // IP 地址（网络字节序）
    unsigned char   sin_zero[8];  // 填充，使大小与 sockaddr 相同
};
```

### 完整的客户端-服务器通信流程

```
       客户端                                 服务器
    ┌──────────┐                          ┌──────────┐
    │ socket() │                          │ socket() │
    └────┬─────┘                          └────┬─────┘
         │                                     │
         │                                ┌────┴─────┐
         │                                │  bind()  │
         │                                └────┬─────┘
         │                                     │
         │                                ┌────┴─────┐
         │                                │ listen() │
         │                                └────┬─────┘
         │                                     │
         │                                ┌────┴─────┐
         │            连接建立             │ accept() │ ← 阻塞等待
    ┌────┴─────┐  ──────────────────>     │          │
    │ connect()│  <──────────────────     │          │
    └────┬─────┘       三次握手           └────┬─────┘
         │                                     │ 返回 connfd
         │                                     │
    ┌────┴─────┐                          ┌────┴─────┐
    │rio_writen│  ─── 请求数据 ────────>  │rio_readn │
    └────┬─────┘                          └────┬─────┘
         │                                     │
    ┌────┴─────┐                          ┌────┴─────┐
    │rio_readn │  <── 响应数据 ──────────  │rio_writen│
    └────┬─────┘                          └────┬─────┘
         │                                     │
    ┌────┴─────┐                          ┌────┴─────┐
    │  close() │                          │  close() │ ← 关闭 connfd
    └──────────┘                          └──────────┘
```

### socket 函数

```c
#include <sys/types.h>
#include <sys/socket.h>

int socket(int domain, int type, int protocol);
// 返回：成功返回非负描述符，出错返回 -1
```

| 参数     | 常用值           | 含义                    |
|:---------|:-----------------|:------------------------|
| domain   | `AF_INET`        | IPv4 Internet 协议      |
| type     | `SOCK_STREAM`    | 字节流（TCP）           |
| protocol | `0`              | 自动选择协议            |

```c
// 典型调用
int sockfd = socket(AF_INET, SOCK_STREAM, 0);
```

`socket` 返回的描述符还不能直接用于读写，它只是一个**部分打开的**套接字，
需要通过 connect（客户端）或 bind+listen+accept（服务器）来完全打开。

### connect 函数（客户端）

```c
int connect(int clientfd, const struct sockaddr *addr, socklen_t addrlen);
// 返回：成功返回 0，出错返回 -1
```

- 试图与地址 addr 处的服务器建立 TCP 连接
- **阻塞调用**：直到连接成功建立或出错才返回
- 连接成功后，clientfd 就可以用于读写了
- 连接由 `(客户端IP:客户端端口, 服务器IP:服务器端口)` 唯一标识

### bind 函数（服务器）

```c
int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen);
// 返回：成功返回 0，出错返回 -1
```

- 将套接字 sockfd 绑定到指定的地址和端口
- 告诉内核：发往这个地址和端口的数据包应交给这个套接字

### listen 函数（服务器）

```c
int listen(int sockfd, int backlog);
// 返回：成功返回 0，出错返回 -1
```

- 将 sockfd 从**主动套接字**转换为**监听套接字**
- 监听套接字可以接受来自客户端的连接请求
- `backlog` 指定内核在拒绝连接之前，排队等待的最大连接数（通常设为 1024）

**主动套接字 vs 监听套接字**：
- 主动套接字（active socket）：用于发起连接（客户端）
- 监听套接字（listening socket）：用于接受连接（服务器）

### accept 函数（服务器）

```c
int accept(int listenfd, struct sockaddr *addr, socklen_t *addrlen);
// 返回：成功返回已连接描述符（connfd），出错返回 -1
```

- **阻塞调用**：等待客户端连接请求到达监听套接字 listenfd
- 连接建立后，addr 被填入客户端的套接字地址
- 返回一个**已连接描述符（connected descriptor）**，用于与该客户端通信

```
listenfd vs connfd 的区别：

listenfd（监听描述符）：
- 服务器生命周期内只创建一次
- 一直存在，用于接受新连接
- 是所有客户端连接的"入口"

connfd（已连接描述符）：
- 每个客户端连接创建一个
- 只在服务该客户端期间存在
- 通信结束后关闭

                         listenfd (fd3)
                              │
                    ┌─────────┼─────────┐
                    v         v         v
              accept()   accept()   accept()
                 │           │          │
                 v           v          v
            connfd=4    connfd=5   connfd=6
              │             │          │
          客户端A       客户端B     客户端C
```

### 为什么需要区分 listenfd 和 connfd？

这种分离使得服务器可以并发处理多个客户端：
- listenfd 始终在监听新的连接
- 每个 connfd 独立服务一个客户端
- 关闭某个 connfd 不影响 listenfd 和其他 connfd

## 11.6 getaddrinfo 函数

`getaddrinfo` 是现代网络编程中最重要的辅助函数，它将主机名和服务名转换为套接字地址结构。

```c
#include <sys/types.h>
#include <sys/socket.h>
#include <netdb.h>

int getaddrinfo(const char *host,           // 主机名或 IP 字符串
                const char *service,         // 服务名或端口号字符串
                const struct addrinfo *hints,// 过滤条件
                struct addrinfo **result);   // 输出链表
// 返回：成功返回 0，出错返回非零错误码

void freeaddrinfo(struct addrinfo *result);  // 释放链表
const char *gai_strerror(int errcode);       // 错误码转字符串
```

**addrinfo 结构**：

```c
struct addrinfo {
    int              ai_flags;      // 提示标志
    int              ai_family;     // AF_INET, AF_INET6, AF_UNSPEC
    int              ai_socktype;   // SOCK_STREAM, SOCK_DGRAM
    int              ai_protocol;   // 协议
    size_t           ai_addrlen;    // ai_addr 的长度
    struct sockaddr *ai_addr;       // 套接字地址（可直接传给 connect/bind）
    char            *ai_canonname;  // 规范主机名
    struct addrinfo *ai_next;       // 链表中的下一个
};
```

**返回的链表结构**：

```
result
  │
  v
┌──────────┐     ┌──────────┐     ┌──────────┐
│ addrinfo │────>│ addrinfo │────>│ addrinfo │──> NULL
│          │     │          │     │          │
│ ai_addr ─┼──>  │ ai_addr ─┼──>  │ ai_addr ─┼──>
│ sockaddr │     │ sockaddr │     │ sockaddr │
└──────────┘     └──────────┘     └──────────┘
  IP地址1          IP地址2          IP地址3
```

### 使用 getaddrinfo 的封装函数

```c
/*
 * open_clientfd - 客户端：连接到 hostname:port 的服务器
 *
 * 返回已就绪的 socket fd，可以立即用 rio 读写
 */
int open_clientfd(char *hostname, char *port)
{
    int clientfd;
    struct addrinfo hints, *listp, *p;

    memset(&hints, 0, sizeof(struct addrinfo));
    hints.ai_socktype = SOCK_STREAM;   // TCP 连接
    hints.ai_flags = AI_NUMERICSERV;   // port 是数字字符串
    hints.ai_flags |= AI_ADDRCONFIG;  // 推荐标志

    getaddrinfo(hostname, port, &hints, &listp);

    // 遍历结果链表，尝试每一个地址
    for (p = listp; p; p = p->ai_next) {
        if ((clientfd = socket(p->ai_family, p->ai_socktype,
                               p->ai_protocol)) < 0)
            continue;  // socket 失败，试下一个

        if (connect(clientfd, p->ai_addr, p->ai_addrlen) != -1)
            break;     // 连接成功！
        close(clientfd);  // 连接失败，关闭重试
    }

    freeaddrinfo(listp);

    if (!p)  // 所有地址都失败了
        return -1;
    return clientfd;
}

/*
 * open_listenfd - 服务器：在 port 上打开监听套接字
 *
 * 返回可以 accept 的监听 fd
 */
int open_listenfd(char *port)
{
    struct addrinfo hints, *listp, *p;
    int listenfd, optval = 1;

    memset(&hints, 0, sizeof(struct addrinfo));
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE | AI_ADDRCONFIG | AI_NUMERICSERV;
    // AI_PASSIVE: 返回通配地址（INADDR_ANY），表示接受任何 IP 的连接

    getaddrinfo(NULL, port, &hints, &listp);

    for (p = listp; p; p = p->ai_next) {
        if ((listenfd = socket(p->ai_family, p->ai_socktype,
                                p->ai_protocol)) < 0)
            continue;

        // 消除 "Address already in use" 错误
        setsockopt(listenfd, SOL_SOCKET, SO_REUSEADDR,
                   (const void *)&optval, sizeof(int));

        if (bind(listenfd, p->ai_addr, p->ai_addrlen) == 0)
            break;     // 绑定成功
        close(listenfd);
    }

    freeaddrinfo(listp);
    if (!p) return -1;

    // 转换为监听套接字
    if (listen(listenfd, 1024) < 0) {
        close(listenfd);
        return -1;
    }
    return listenfd;
}
```

## 11.7 HTTP 协议

### HTTP 请求格式

```
┌──────────────────────────────────────────────┐
│ 请求行:   方法  URI  版本                     │
│ 例如:     GET /index.html HTTP/1.1\r\n       │
├──────────────────────────────────────────────┤
│ 请求头部:                                     │
│ Host: www.cmu.edu\r\n                        │
│ User-Agent: Mozilla/5.0\r\n                  │
│ Connection: close\r\n                        │
│ \r\n                 ← 空行表示头部结束       │
├──────────────────────────────────────────────┤
│ 请求体（POST 方法时使用）                     │
└──────────────────────────────────────────────┘
```

### HTTP 响应格式

```
┌──────────────────────────────────────────────┐
│ 响应行:   版本  状态码  状态描述               │
│ 例如:     HTTP/1.1 200 OK\r\n                │
├──────────────────────────────────────────────┤
│ 响应头部:                                     │
│ Content-Type: text/html\r\n                  │
│ Content-Length: 1024\r\n                      │
│ Connection: close\r\n                        │
│ \r\n                 ← 空行表示头部结束       │
├──────────────────────────────────────────────┤
│ 响应体:                                       │
│ <html>...</html>                             │
└──────────────────────────────────────────────┘
```

### HTTP 方法

| 方法    | 描述                         | 请求体 | 幂等性 |
|:--------|:-----------------------------|:-------|:-------|
| GET     | 请求指定资源                 | 无     | 是     |
| POST    | 提交数据到服务器             | 有     | 否     |
| PUT     | 上传文件到指定 URI           | 有     | 是     |
| DELETE  | 删除指定资源                 | 无     | 是     |
| HEAD    | 类似 GET 但只返回头部        | 无     | 是     |
| OPTIONS | 查询服务器支持的方法         | 无     | 是     |

### HTTP 状态码

| 状态码 | 类别           | 常见状态码和含义                          |
|:-------|:---------------|:------------------------------------------|
| 1xx    | 信息性         | 100 Continue                              |
| 2xx    | 成功           | 200 OK, 201 Created, 204 No Content       |
| 3xx    | 重定向         | 301 Moved Permanently, 302 Found, 304 Not Modified |
| 4xx    | 客户端错误     | 400 Bad Request, 403 Forbidden, 404 Not Found, 405 Method Not Allowed |
| 5xx    | 服务器错误     | 500 Internal Server Error, 502 Bad Gateway, 503 Service Unavailable |

### 静态内容 vs 动态内容

```
客户端请求:  GET /index.html  →  服务器返回磁盘上的文件    （静态）
客户端请求:  GET /cgi-bin/add?1&2  →  服务器执行程序返回结果  （动态）

CGI（Common Gateway Interface）环境变量：
- QUERY_STRING:  "1&2"  （? 后面的部分）
- SERVER_PORT:   "8080"
- REQUEST_METHOD: "GET"
- 子进程的 stdout 被重定向到 connfd（通过 dup2）
```

## 11.8 Tiny Web 服务器关键代码分析

Tiny 是 CSAPP 中实现的一个简单但完整的 Web 服务器。

### 主循环

```c
int main(int argc, char **argv)
{
    int listenfd, connfd;
    char hostname[MAXLINE], port[MAXLINE];
    socklen_t clientlen;
    struct sockaddr_storage clientaddr;

    listenfd = open_listenfd(argv[1]);

    while (1) {
        clientlen = sizeof(clientaddr);
        connfd = accept(listenfd, (SA *)&clientaddr, &clientlen);

        // 获取客户端信息用于日志
        getnameinfo((SA *)&clientaddr, clientlen,
                    hostname, MAXLINE, port, MAXLINE, 0);
        printf("Accepted connection from (%s, %s)\n", hostname, port);

        doit(connfd);    // 处理请求
        close(connfd);   // 关闭连接
    }
}
```

### 请求处理函数 doit

```c
void doit(int fd)
{
    int is_static;
    struct stat sbuf;
    char buf[MAXLINE], method[MAXLINE], uri[MAXLINE], version[MAXLINE];
    char filename[MAXLINE], cgiargs[MAXLINE];
    rio_t rio;

    // 读取请求行
    rio_readinitb(&rio, fd);
    rio_readlineb(&rio, buf, MAXLINE);
    sscanf(buf, "%s %s %s", method, uri, version);

    // 只支持 GET
    if (strcasecmp(method, "GET")) {
        clienterror(fd, method, "501", "Not Implemented",
                    "Tiny does not implement this method");
        return;
    }

    // 读取并忽略请求头部
    read_requesthdrs(&rio);

    // 解析 URI
    is_static = parse_uri(uri, filename, cgiargs);

    // 检查文件是否存在
    if (stat(filename, &sbuf) < 0) {
        clienterror(fd, filename, "404", "Not found",
                    "Tiny couldn't find this file");
        return;
    }

    if (is_static) {
        // 提供静态内容
        serve_static(fd, filename, sbuf.st_size);
    } else {
        // 提供动态内容（CGI）
        serve_dynamic(fd, filename, cgiargs);
    }
}
```

### 静态内容服务

```c
void serve_static(int fd, char *filename, int filesize)
{
    int srcfd;
    char *srcp, filetype[MAXLINE], buf[MAXBUF];

    // 发送响应头部
    get_filetype(filename, filetype);
    sprintf(buf, "HTTP/1.0 200 OK\r\n");
    sprintf(buf, "%sServer: Tiny Web Server\r\n", buf);
    sprintf(buf, "%sContent-Length: %d\r\n", buf, filesize);
    sprintf(buf, "%sContent-Type: %s\r\n\r\n", buf, filetype);
    rio_writen(fd, buf, strlen(buf));

    // 将文件内容映射到内存并发送
    srcfd = open(filename, O_RDONLY, 0);
    srcp = mmap(0, filesize, PROT_READ, MAP_PRIVATE, srcfd, 0);
    close(srcfd);
    rio_writen(fd, srcp, filesize);
    munmap(srcp, filesize);
}
```

### 动态内容服务（CGI）

```c
void serve_dynamic(int fd, char *filename, char *cgiargs)
{
    char buf[MAXLINE];

    // 发送响应行（响应体由 CGI 程序生成）
    sprintf(buf, "HTTP/1.0 200 OK\r\n");
    rio_writen(fd, buf, strlen(buf));
    sprintf(buf, "Server: Tiny Web Server\r\n");
    rio_writen(fd, buf, strlen(buf));

    if (fork() == 0) {  // 子进程
        // 设置 CGI 环境变量
        setenv("QUERY_STRING", cgiargs, 1);

        // 重定向 stdout 到客户端连接
        dup2(fd, STDOUT_FILENO);

        // 执行 CGI 程序
        execve(filename, NULL, environ);
    }
    wait(NULL);  // 父进程等待子进程
}
```

---

# 第12章 并发编程

## 12.1 为什么需要并发？

上面的 Tiny 服务器是**迭代服务器**——一次只能服务一个客户端。
当一个客户端在慢速传输时，其他所有客户端都被阻塞。

三种构建并发服务器的方法：
1. **基于进程**：fork 子进程处理每个连接
2. **基于 I/O 多路复用**：用 select/poll/epoll 在单进程中处理多个连接
3. **基于线程**：创建线程处理每个连接

## 12.2 基于进程的并发

### 原理

为每个新客户端连接 fork 一个子进程。

```c
void sigchld_handler(int sig)
{
    while (waitpid(-1, 0, WNOHANG) > 0)
        ;  // 回收所有已终止的子进程
}

int main(int argc, char **argv)
{
    int listenfd, connfd;
    socklen_t clientlen;
    struct sockaddr_storage clientaddr;

    signal(SIGCHLD, sigchld_handler);  // 注册信号处理
    listenfd = open_listenfd(argv[1]);

    while (1) {
        clientlen = sizeof(struct sockaddr_storage);
        connfd = accept(listenfd, (SA *)&clientaddr, &clientlen);

        if (fork() == 0) {     // 子进程
            close(listenfd);   // 子进程不需要监听描述符
            doit(connfd);      // 处理请求
            close(connfd);     // 关闭已连接描述符
            exit(0);           // 退出子进程
        }
        close(connfd);         // 父进程不需要已连接描述符（重要！）
    }
}
```

### 描述符引用计数的关键理解

```
fork 前:
父进程: listenfd(refcnt=1)  connfd(refcnt=1)

fork 后:
父进程: listenfd(refcnt=2)  connfd(refcnt=2)  ← 都被复制了
子进程: listenfd(refcnt=2)  connfd(refcnt=2)

各自 close 后:
父进程: listenfd(refcnt=1)  [connfd 已关闭]
子进程: [listenfd 已关闭]    connfd(refcnt=1)
```

**如果父进程不 close(connfd)**：connfd 的引用计数永远不会降为 0，
导致内存泄漏和连接得不到真正关闭！

### 基于进程的优缺点

| 优点                          | 缺点                              |
|:------------------------------|:----------------------------------|
| 进程间地址空间隔离，不会干扰  | 进程创建/切换开销大               |
| 编程模型简单，容易理解        | 进程间通信（IPC）困难且慢         |
| 一个进程崩溃不影响其他进程    | 不适合高并发场景（数千个连接）    |

## 12.3 基于 I/O 多路复用的并发

### 核心思想

使用一个进程同时监视多个描述符，当某个描述符就绪时再进行 I/O 操作。

### select 函数

```c
#include <sys/select.h>

int select(int nfds, fd_set *readfds, fd_set *writefds,
           fd_set *exceptfds, struct timeval *timeout);
// 返回：就绪的描述符数量，超时返回 0，出错返回 -1

// fd_set 操作宏
FD_ZERO(fd_set *fdset);          // 清空集合
FD_SET(int fd, fd_set *fdset);   // 添加 fd 到集合
FD_CLR(int fd, fd_set *fdset);   // 从集合中移除 fd
FD_ISSET(int fd, fd_set *fdset); // 测试 fd 是否在集合中
```

```c
// 使用 select 的事件驱动服务器骨架
void event_loop(int listenfd)
{
    fd_set read_set, ready_set;
    int clientfds[FD_SETSIZE];
    int maxfd = listenfd;
    int nready;

    FD_ZERO(&read_set);
    FD_SET(listenfd, &read_set);

    while (1) {
        ready_set = read_set;  // select 会修改集合，每次须重新设置
        nready = select(maxfd + 1, &ready_set, NULL, NULL, NULL);

        // 检查是否有新连接
        if (FD_ISSET(listenfd, &ready_set)) {
            int connfd = accept(listenfd, ...);
            FD_SET(connfd, &read_set);
            // 更新 maxfd 和 clientfds 数组
        }

        // 检查已有连接是否有数据到达
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (clientfds[i] > 0 && FD_ISSET(clientfds[i], &ready_set)) {
                // 读取并处理数据
                // 如果客户端关闭了连接
                if (read_result == 0) {
                    close(clientfds[i]);
                    FD_CLR(clientfds[i], &read_set);
                }
            }
        }
    }
}
```

### select / poll / epoll 对比

| 特性           | select                    | poll                      | epoll                          |
|:---------------|:--------------------------|:--------------------------|:-------------------------------|
| 数据结构       | fd_set（位图）            | struct pollfd 数组        | 红黑树 + 就绪链表              |
| 最大描述符数   | FD_SETSIZE（通常1024）    | 无硬性限制                | 无硬性限制                     |
| 每次调用开销   | O(n) 线性扫描全部描述符   | O(n) 线性扫描全部描述符   | O(k) 只遍历就绪描述符         |
| 描述符集合传递 | 每次调用都要重新设置      | 每次调用都要重新传入      | 只需添加一次（epoll_ctl）      |
| 触发模式       | 水平触发（LT）            | 水平触发（LT）            | 支持水平触发和边缘触发（ET）   |
| 内核实现       | 每次调用都全量拷贝到内核  | 每次调用都全量拷贝到内核  | 内核维护事件表，无需重复拷贝   |
| 适用场景       | 连接数少、跨平台          | 连接数少、比 select 好一点| 高并发场景（Linux 专用）       |

### epoll 接口详解

```c
#include <sys/epoll.h>

// 1. 创建 epoll 实例
int epoll_create1(int flags);
// 返回：epoll 文件描述符

// 2. 注册/修改/删除监视的描述符
int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event);
// op: EPOLL_CTL_ADD, EPOLL_CTL_MOD, EPOLL_CTL_DEL

// 3. 等待事件
int epoll_wait(int epfd, struct epoll_event *events,
               int maxevents, int timeout);
// 返回：就绪的事件数量

struct epoll_event {
    uint32_t     events;   // EPOLLIN, EPOLLOUT, EPOLLET 等
    epoll_data_t data;     // 用户数据（通常存 fd）
};
```

```c
// 使用 epoll 的事件循环
void epoll_event_loop(int listenfd)
{
    int epfd = epoll_create1(0);
    struct epoll_event ev, events[MAX_EVENTS];

    // 注册监听套接字
    ev.events = EPOLLIN;
    ev.data.fd = listenfd;
    epoll_ctl(epfd, EPOLL_CTL_ADD, listenfd, &ev);

    while (1) {
        int nready = epoll_wait(epfd, events, MAX_EVENTS, -1);

        for (int i = 0; i < nready; i++) {
            if (events[i].data.fd == listenfd) {
                // 新连接
                int connfd = accept(listenfd, ...);
                ev.events = EPOLLIN | EPOLLET;  // 边缘触发
                ev.data.fd = connfd;
                epoll_ctl(epfd, EPOLL_CTL_ADD, connfd, &ev);
            } else {
                // 已有连接上有数据
                handle_client(events[i].data.fd);
            }
        }
    }
}
```

### 水平触发（LT）vs 边缘触发（ET）

```
内核缓冲区中的数据变化：

时刻:     t1     t2     t3     t4     t5
数据量:   0      5KB    3KB    3KB    0
          (到达) (读2KB) (不读) (读完)

水平触发（LT）：只要缓冲区有数据就通知
通知:           是     是     是     否

边缘触发（ET）：只在状态变化时通知
通知:           是     否     否     否
                (0->5)  (5->3不是新到达)  (不变)  (3->0)
```

**ET 模式的要求**：
- 必须使用非阻塞 I/O
- 收到通知后必须一次性读完所有数据（循环 read 直到 EAGAIN）
- 否则会丢失数据（因为不会再次通知）

### I/O 多路复用的优缺点

| 优点                            | 缺点                                |
|:--------------------------------|:------------------------------------|
| 单线程，无需同步                | 编程复杂度高                        |
| 共享地址空间，变量访问容易      | 无法利用多核 CPU                    |
| 无进程/线程创建开销             | 一个请求处理时间长会阻塞所有请求    |
| 适合高并发但每个请求处理简单的场景| 粒度问题：不能让一个逻辑流阻塞      |

## 12.4 基于线程的并发

线程是运行在进程上下文中的逻辑流，由内核自动调度。

### 线程 vs 进程

```
进程模型：                         线程模型：
┌─────────────────┐               ┌─────────────────────────────────┐
│ 进程 A          │               │ 进程                            │
│ ┌─────────────┐ │               │ ┌──────┐ ┌──────┐ ┌──────┐    │
│ │ 代码/数据   │ │               │ │线程1 │ │线程2 │ │线程3 │    │
│ │ 栈          │ │               │ │栈1   │ │栈2   │ │栈3   │    │
│ │ 堆          │ │               │ └──────┘ └──────┘ └──────┘    │
│ └─────────────┘ │               │ ┌─────────────────────────────┐ │
└─────────────────┘               │ │ 共享：代码、数据、堆、      │ │
┌─────────────────┐               │ │       打开的文件、信号处理  │ │
│ 进程 B          │               │ └─────────────────────────────┘ │
│ ┌─────────────┐ │               └─────────────────────────────────┘
│ │ 代码/数据   │ │
│ │ 栈          │ │               每个线程独有：
│ │ 堆          │ │               - 线程 ID (TID)
│ └─────────────┘ │               - 栈
└─────────────────┘               - 栈指针、程序计数器
                                  - 通用寄存器、条件码
完全隔离                          共享地址空间
```

### Posix 线程（Pthreads）接口

```c
#include <pthread.h>

// 创建线程
int pthread_create(pthread_t *tid, pthread_attr_t *attr,
                   void *(*start_routine)(void *), void *arg);
// tid:   输出参数，新线程的 ID
// attr:  线程属性（通常为 NULL，使用默认属性）
// start_routine: 线程执行的函数
// arg:   传递给线程函数的参数

// 获取自身线程 ID
pthread_t pthread_self(void);

// 终止线程
void pthread_exit(void *retval);
// 终止当前线程，返回值通过 retval 传递

// 等待线程结束（回收资源）
int pthread_join(pthread_t tid, void **retval);
// 阻塞等待线程 tid 终止，获取返回值

// 分离线程
int pthread_detach(pthread_t tid);
// 分离后的线程终止时自动回收资源，不需要 join
```

### 基于线程的并发服务器

```c
void *thread_routine(void *vargp)
{
    int connfd = *((int *)vargp);
    pthread_detach(pthread_self());  // 分离，自动回收
    free(vargp);                     // 释放 malloc 的内存
    doit(connfd);                    // 处理请求
    close(connfd);                   // 关闭连接
    return NULL;
}

int main(int argc, char **argv)
{
    int listenfd, *connfdp;
    socklen_t clientlen;
    struct sockaddr_storage clientaddr;
    pthread_t tid;

    listenfd = open_listenfd(argv[1]);

    while (1) {
        clientlen = sizeof(struct sockaddr_storage);
        // 必须 malloc！不能传局部变量的地址（竞争条件）
        connfdp = malloc(sizeof(int));
        *connfdp = accept(listenfd, (SA *)&clientaddr, &clientlen);
        pthread_create(&tid, NULL, thread_routine, connfdp);
    }
}
```

**为什么 connfd 必须用 malloc？**

```c
// 错误写法（竞争条件！）
while (1) {
    int connfd = accept(listenfd, ...);
    pthread_create(&tid, NULL, thread_routine, &connfd);
    // 问题：下一次循环 connfd 被覆盖时，线程可能还没来得及读取！
    // 两个线程可能读到同一个 connfd 值
}

// 正确写法
while (1) {
    int *connfdp = malloc(sizeof(int));
    *connfdp = accept(listenfd, ...);
    pthread_create(&tid, NULL, thread_routine, connfdp);
    // 每个线程有自己独立的 connfd 副本
}
```

## 12.5 共享变量与线程安全

### 变量的共享分析

| 变量类型     | 存储位置   | 是否共享              | 说明                              |
|:-------------|:-----------|:----------------------|:----------------------------------|
| 全局变量     | 数据段     | 是，所有线程共享      | 虚拟内存只有一个实例              |
| 局部自动变量 | 线程栈     | 否，通常不共享        | 每个线程栈上有各自副本            |
| 局部静态变量 | 数据段     | 是，所有线程共享      | 与全局变量相同，只是可见性不同    |

**注意**：局部自动变量也可能被共享——如果一个线程将指向其栈上变量的指针传给另一个线程。但这很危险，因为该线程退出后指针悬空。

## 12.6 信号量（Semaphore）

信号量 s 是一个非负整数值的全局变量，只能由两个特殊操作来处理：

### P 和 V 操作

```
P(s):  （Proberen，荷兰语"测试"）
  if (s > 0)
      s = s - 1;    // 获取资源，继续执行
  else
      挂起线程;      // 资源不可用，阻塞等待

V(s):  （Verhogen，荷兰语"增加"）
  s = s + 1;         // 释放资源
  if (有线程在等待)
      唤醒一个等待的线程;
```

**P 和 V 操作的关键性质**：
- 它们是**原子的**——执行过程中不会被中断
- P 中的测试和减 1 是不可分割的
- V 中的加 1 和可能的唤醒是不可分割的
- 信号量的值永远不会变为负数（不变量）

### Posix 信号量接口

```c
#include <semaphore.h>

int sem_init(sem_t *sem, int pshared, unsigned int value);
// pshared=0 表示线程间共享，value 是初始值

int sem_wait(sem_t *sem);    // P 操作
int sem_post(sem_t *sem);    // V 操作
```

### 用信号量实现互斥锁

```c
sem_t mutex;  // 互斥信号量
sem_init(&mutex, 0, 1);  // 初始值为 1（二元信号量）

// 线程中的临界区保护
sem_wait(&mutex);    // P(mutex): 加锁（0 表示已锁）
// ---- 临界区 ----
cnt++;               // 安全地修改共享变量
// ---- 临界区 ----
sem_post(&mutex);    // V(mutex): 解锁（恢复为 1）
```

互斥锁的 P/V 操作序列保证了同一时刻只有一个线程在临界区中：

```
时间线（两个线程争夺 mutex，初始 s=1）：

线程1              mutex(s)          线程2
  │                  1                  │
  P(mutex) ─────>    0                  │
  │ 进入临界区       │                  │
  │ cnt++            │            P(mutex) ──> 阻塞！(s=0)
  │                  │                  │(等待)
  V(mutex) ─────>    1 ──> 唤醒线程2    │
  │                  0            进入临界区
  │                  │            cnt++
  │                  │            V(mutex) ─────> 1
```

## 12.7 生产者-消费者问题

```
生产者线程                    消费者线程
    │                            │
    │    ┌──────────────────┐    │
    │    │   有界缓冲区     │    │
    ├──> │ [  ] [  ] [  ]   │ ──>├──> 取出项目
    │    │ 大小为 n 的槽    │    │
    │    └──────────────────┘    │
    │                            │
  生产项目                    消费项目
```

需要三个信号量：
- `mutex`：互斥访问缓冲区（初始值 1）
- `slots`：缓冲区中的空闲槽数（初始值 n）
- `items`：缓冲区中的可用项数（初始值 0）

```c
typedef struct {
    int *buf;        // 缓冲区数组
    int n;           // 缓冲区大小
    int front;       // 队头 ((front+1) % n 是第一个项目)
    int rear;        // 队尾 (rear % n 是最后一个项目)
    sem_t mutex;     // 保护缓冲区访问
    sem_t slots;     // 空闲槽计数
    sem_t items;     // 可用项计数
} sbuf_t;

void sbuf_init(sbuf_t *sp, int n)
{
    sp->buf = calloc(n, sizeof(int));
    sp->n = n;
    sp->front = sp->rear = 0;
    sem_init(&sp->mutex, 0, 1);
    sem_init(&sp->slots, 0, n);    // n 个空槽
    sem_init(&sp->items, 0, 0);    // 0 个项目
}

// 生产者：插入项目
void sbuf_insert(sbuf_t *sp, int item)
{
    sem_wait(&sp->slots);          // P(slots): 等待空槽
    sem_wait(&sp->mutex);          // P(mutex): 加锁
    sp->buf[(++sp->rear) % sp->n] = item;  // 插入
    sem_post(&sp->mutex);          // V(mutex): 解锁
    sem_post(&sp->items);          // V(items): 通知消费者
}

// 消费者：取出项目
int sbuf_remove(sbuf_t *sp)
{
    int item;
    sem_wait(&sp->items);          // P(items): 等待项目
    sem_wait(&sp->mutex);          // P(mutex): 加锁
    item = sp->buf[(++sp->front) % sp->n];  // 取出
    sem_post(&sp->mutex);          // V(mutex): 解锁
    sem_post(&sp->slots);          // V(slots): 通知生产者
    return item;
}
```

**信号量顺序非常重要**：必须先 P(slots/items) 再 P(mutex)。
如果先 P(mutex) 再 P(slots)，可能死锁——持有锁的生产者等待空槽，
而消费者无法获取锁来释放槽。

## 12.8 读者-写者问题

```
读者-写者问题：
- 多个读者可以同时读共享对象
- 写者必须独占访问共享对象
- 读者和写者不能同时访问

第一类：读者优先（不会让读者等待，除非写者已获得锁）
第二类：写者优先（一旦写者就绪，尽快执行写操作）
```

### 第一类读者-写者问题（读者优先）

```c
int readcnt = 0;      // 当前读者数量
sem_t mutex;           // 保护 readcnt
sem_t w;               // 控制写者访问

sem_init(&mutex, 0, 1);
sem_init(&w, 0, 1);

// 读者
void reader(void)
{
    while (1) {
        sem_wait(&mutex);        // 加锁保护 readcnt
        readcnt++;
        if (readcnt == 1)        // 第一个读者
            sem_wait(&w);        // 锁住写者
        sem_post(&mutex);

        // ---- 读取共享数据 ----

        sem_wait(&mutex);
        readcnt--;
        if (readcnt == 0)        // 最后一个读者
            sem_post(&w);        // 允许写者进入
        sem_post(&mutex);
    }
}

// 写者
void writer(void)
{
    while (1) {
        sem_wait(&w);            // 独占访问
        // ---- 写入共享数据 ----
        sem_post(&w);
    }
}
```

## 12.9 线程安全

### 四类线程不安全的函数

| 类别   | 描述                                | 例子                      | 解决方案                         |
|:-------|:------------------------------------|:--------------------------|:---------------------------------|
| 第1类  | 不保护共享变量的函数                | 不加锁地修改全局计数器    | 加锁（信号量/互斥锁）           |
| 第2类  | 保持跨越多次调用状态的函数          | `rand()`（内部静态状态）  | 重写为使用调用者传入的状态       |
| 第3类  | 返回指向静态变量指针的函数          | `ctime()`, `gethostbyname()` | 使用 lock-and-copy，或用 `_r` 版本 |
| 第4类  | 调用线程不安全函数的函数            | 调用了第1-3类的函数       | 改为调用线程安全的替代函数       |

### 可重入函数（Reentrant Functions）

```
                    所有函数
                   /        \
          线程安全函数    线程不安全函数
          /          \
   可重入函数    不可重入但线程安全
                 （通过加锁实现）
```

可重入函数不访问任何共享数据，是线程安全函数的一个子集。
可重入函数效率更高，因为不需要任何同步操作。

## 12.10 竞争条件（Race Condition）

当程序的正确性依赖于线程的执行顺序时，就产生了竞争条件。

```c
// 经典竞争条件示例
for (int i = 0; i < N; i++) {
    pthread_create(&tid[i], NULL, thread, &i);  // 错误！
}

void *thread(void *vargp)
{
    int myid = *((int *)vargp);  // 竞争！
    printf("Hello from thread %d\n", myid);
    return NULL;
}
```

问题分析：
```
主线程:  i=0, 创建线程0, i=1, 创建线程1, i=2, 创建线程2...
线程0:                           *vargp=?  可能是0,1,2中的任一个！

期望输出: Hello from thread 0, 1, 2, 3
实际可能: Hello from thread 1, 2, 2, 3  （不确定！）
```

**修复方法**：为每个线程分配独立的 ID 存储。

```c
for (int i = 0; i < N; i++) {
    int *idp = malloc(sizeof(int));
    *idp = i;
    pthread_create(&tid[i], NULL, thread, idp);
}

void *thread(void *vargp)
{
    int myid = *((int *)vargp);
    free(vargp);  // 线程负责释放
    printf("Hello from thread %d\n", myid);
    return NULL;
}
```

## 12.11 死锁（Deadlock）

### 死锁的四个必要条件（Coffman 条件）

| 条件           | 描述                                              |
|:---------------|:--------------------------------------------------|
| 互斥           | 资源不能被多个线程同时持有                        |
| 持有并等待     | 线程在持有资源的同时等待其他资源                  |
| 不可抢占       | 资源只能由持有者主动释放                          |
| 循环等待       | 存在线程间的环形等待链                            |

四个条件必须同时满足才会产生死锁，打破任一条件即可避免。

### 死锁示例

```c
sem_t mutex_a, mutex_b;

// 线程1                        // 线程2
P(mutex_a);  // 获取 A          P(mutex_b);  // 获取 B
P(mutex_b);  // 等待 B ←死锁→  P(mutex_a);  // 等待 A
V(mutex_b);                     V(mutex_a);
V(mutex_a);                     V(mutex_b);
```

```
死锁的进度图：

线程2
  ^
  │     ┌─────────────┐
  │     │ 死锁区域     │
  │     │ (不可能到达) │
  │─────┤  ┌───────┐  │
  │     │  │XXXXXXX│  │
  │     │  │XX 死锁│  │
  │─────┤  └───────┘  │
  │     │             │
  │     └─────────────┘
  │
  └───────────────────────> 线程1
```

### 避免死锁的方法：锁排序（Lock Ordering）

**规则**：给所有锁分配一个全局顺序，每个线程按照相同顺序获取锁。

```c
// 修复：两个线程都先获取 A 再获取 B
// 线程1                        // 线程2
P(mutex_a);                     P(mutex_a);  // 改为先 A
P(mutex_b);                     P(mutex_b);  // 再 B
V(mutex_b);                     V(mutex_b);
V(mutex_a);                     V(mutex_a);
```

---

# 与 Proxy Lab 的关联

## 三个 Part 的知识映射

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Proxy Lab                                  │
├──────────────┬──────────────────┬───────────────────────────────────┤
│  Part I      │  Part II         │  Part III                        │
│  顺序代理    │  并发代理        │  缓存代理                        │
├──────────────┼──────────────────┼───────────────────────────────────┤
│ 第10章       │ 第12章           │ 第12章                           │
│ - RIO 包     │ - pthread_create │ - 读者-写者问题                  │
│ - read/write │ - pthread_detach │ - 互斥锁保护缓存                 │
│              │ - 线程例程       │ - 信号量同步                     │
│ 第11章       │                  │                                   │
│ - socket     │ 第10章           │ 第11章                           │
│ - connect    │ - 文件描述符管理 │ - HTTP 头部解析                  │
│ - bind       │ - close 防泄漏   │ - Host/Connection/               │
│ - listen     │                  │   Proxy-Connection 处理          │
│ - accept     │                  │                                   │
│ - HTTP 解析  │                  │                                   │
│ - 请求转发   │                  │                                   │
└──────────────┴──────────────────┴───────────────────────────────────┘
```

### Part I：顺序代理

核心任务：接收客户端 HTTP 请求，解析后转发给目标服务器，将响应返回客户端。

```
客户端 ──── 请求 ────> 代理 ──── 请求 ────> 目标服务器
           <──── 响应 ────      <──── 响应 ────
```

关键知识点：
- 使用 `open_listenfd` 监听端口
- 使用 `rio_readlineb` 解析 HTTP 请求行和头部
- 使用 `open_clientfd` 连接目标服务器
- 使用 `rio_writen` 转发请求和响应
- 正确处理 HTTP/1.0 和 HTTP/1.1 差异

### Part II：并发代理

将顺序代理改为多线程并发版本。

```c
while (1) {
    int *connfdp = malloc(sizeof(int));
    *connfdp = accept(listenfd, ...);
    pthread_create(&tid, NULL, proxy_thread, connfdp);
}

void *proxy_thread(void *vargp)
{
    int connfd = *((int *)vargp);
    pthread_detach(pthread_self());
    free(vargp);
    handle_request(connfd);  // Part I 的逻辑
    close(connfd);
    return NULL;
}
```

### Part III：带缓存的并发代理

使用读者-写者锁保护共享缓存。

```c
typedef struct {
    char url[MAXLINE];      // 缓存的 URL（作为 key）
    char content[MAX_OBJECT_SIZE];  // 缓存的内容
    int content_length;
    int valid;              // 是否有效
    int timestamp;          // LRU 时间戳
} cache_line_t;

// 读缓存（读者）
int cache_read(char *url, char *buf)
{
    sem_wait(&mutex);       // 保护 readcnt
    readcnt++;
    if (readcnt == 1)
        sem_wait(&w);       // 第一个读者锁写者
    sem_post(&mutex);

    // 在缓存中查找 url ...

    sem_wait(&mutex);
    readcnt--;
    if (readcnt == 0)
        sem_post(&w);       // 最后一个读者放行写者
    sem_post(&mutex);
    return found;
}

// 写缓存（写者）
void cache_write(char *url, char *content, int len)
{
    sem_wait(&w);           // 独占访问
    // 写入缓存，可能需要 LRU 淘汰 ...
    sem_post(&w);
}
```

---

# 实际应用与扩展

## Nginx 事件驱动模型

```
Nginx 架构：

┌──────────────────────────────────────────┐
│                Master 进程               │
│  - 读取配置                              │
│  - 管理 Worker 进程                      │
└────────────────┬─────────────────────────┘
                 │ fork
     ┌───────────┼───────────┐
     v           v           v
┌─────────┐ ┌─────────┐ ┌─────────┐
│ Worker 1│ │ Worker 2│ │ Worker N│   N = CPU 核心数
│         │ │         │ │         │
│ epoll   │ │ epoll   │ │ epoll   │   每个 Worker 用 epoll
│ 事件    │ │ 事件    │ │ 事件    │   处理数千个连接
│ 循环    │ │ 循环    │ │ 循环    │
└─────────┘ └─────────┘ └─────────┘
```

特点：
- 多进程 + I/O 多路复用（epoll）的混合模型
- 每个 Worker 进程是单线程的，避免了锁竞争
- 利用多核 CPU（多个 Worker 进程）
- 单个 Worker 可处理数千并发连接

## Redis 单线程 + epoll

```
Redis 为什么用单线程？

1. 纯内存操作，CPU 不是瓶颈
2. 避免了多线程的锁开销和上下文切换
3. 使用 epoll 实现 I/O 多路复用
4. 单线程保证了命令的原子性

请求处理流程：
客户端A ──┐
客户端B ──┤──> epoll_wait() ──> 逐个处理就绪事件
客户端C ──┘              (单线程串行执行命令)

Redis 6.0+ 的多线程改进：
- 网络 I/O（读取请求/发送响应）使用多线程
- 命令执行仍然是单线程的
- 充分利用多核处理网络 I/O 瓶颈
```

## TCP 三次握手与四次挥手

### 三次握手（建立连接）

```
客户端                                   服务器
  │                                        │
  │  1. SYN (seq=x)                        │
  │ ─────────────────────────────────────> │  LISTEN
  │        "我要建立连接"                   │
  │                                        │
  │  2. SYN+ACK (seq=y, ack=x+1)          │
  │ <───────────────────────────────────── │  SYN_RCVD
  │        "同意，我也要建立连接"           │
  │                                        │
  │  3. ACK (ack=y+1)                      │
  │ ─────────────────────────────────────> │  ESTABLISHED
  │        "确认"                           │
  │                                        │
  │         连接已建立，可以传输数据        │
```

**为什么需要三次？**
- 两次不行：服务器无法确认客户端收到了 SYN+ACK
- 防止已失效的连接请求突然到达服务器（历史 SYN 问题）

### 四次挥手（关闭连接）

```
客户端                                   服务器
  │                                        │
  │  1. FIN (seq=u)                        │
  │ ─────────────────────────────────────> │
  │        "我发完了"                       │  FIN_WAIT_1
  │                                        │
  │  2. ACK (ack=u+1)                      │
  │ <───────────────────────────────────── │  CLOSE_WAIT
  │        "知道了"                         │  FIN_WAIT_2
  │                                        │
  │  (服务器可能还有数据要发送...)          │
  │                                        │
  │  3. FIN (seq=v)                        │
  │ <───────────────────────────────────── │
  │        "我也发完了"                     │  LAST_ACK
  │                                        │
  │  4. ACK (ack=v+1)                      │
  │ ─────────────────────────────────────> │  CLOSED
  │        "确认"                           │
  │  TIME_WAIT (等待 2MSL)                 │
  │                                        │
  │  CLOSED                                │
```

**为什么需要四次？** TCP 是全双工的，每个方向需要独立关闭。

**为什么有 TIME_WAIT？** 确保最后一个 ACK 到达服务器；让网络中残留的数据包自然消亡。

## HTTP 版本对比

| 特性              | HTTP/1.0          | HTTP/1.1               | HTTP/2                | HTTP/3              |
|:------------------|:------------------|:-----------------------|:----------------------|:--------------------|
| 连接管理          | 每个请求一个连接  | 持久连接（keep-alive） | 多路复用              | 基于 QUIC（UDP）    |
| 队头阻塞          | N/A               | 有（流水线模式下）     | 解决了 HTTP 层        | 彻底解决（含传输层）|
| 头部压缩          | 无                | 无                     | HPACK 压缩            | QPACK 压缩          |
| 服务器推送        | 无                | 无                     | 支持                  | 支持                |
| 传输协议          | TCP               | TCP                    | TCP + TLS             | QUIC（UDP + TLS）   |
| 二进制分帧        | 无（文本协议）    | 无（文本协议）         | 有（二进制帧）        | 有                  |

```
HTTP/1.1 队头阻塞问题：

请求1 ──────────> 响应1（慢）────────────────>
请求2 ─────────────────────────> 响应2 ──────> (被阻塞)
请求3 ──────────────────────────────────────> 响应3 (更久)

HTTP/2 多路复用：

请求1 ──> 帧1a │ 帧1b │      │ 帧1c ──> 响应1
请求2 ──>      │      │ 帧2a │      │ 帧2b ──> 响应2
请求3 ──>      │      │      │ 帧3a │      │ 帧3b ──> 响应3
               ← 一个 TCP 连接上交错传输 →
```

## 代理服务器类型

```
1. 正向代理（Forward Proxy）：
   客户端 ──> 正向代理 ──> 目标服务器
   - 客户端知道代理的存在
   - 隐藏客户端身份
   - 用途：翻墙、缓存、访问控制

2. 反向代理（Reverse Proxy）：
   客户端 ──> 反向代理 ──> 后端服务器群
   - 客户端不知道代理的存在
   - 隐藏服务器身份
   - 用途：负载均衡、SSL 终结、缓存

3. 透明代理（Transparent Proxy）：
   客户端 ──> (不知不觉经过) 透明代理 ──> 目标服务器
   - 网络层面拦截，无需客户端配置
   - 用途：企业网络监控、运营商缓存
```

## 线程池设计

```
线程池架构：

                      ┌─────────────────────────────────────┐
                      │            线程池                    │
                      │  ┌──────┐ ┌──────┐ ┌──────┐       │
  任务到达            │  │工作线│ │工作线│ │工作线│       │
     │                │  │程 1  │ │程 2  │ │程 N  │       │
     v                │  └──┬───┘ └──┬───┘ └──┬───┘       │
 ┌────────────────┐   │     │        │        │            │
 │ 任务队列       │   │     v        v        v            │
 │ [T1][T2][T3]...│<──┤── sbuf_remove() 取任务             │
 │ (有界缓冲区)   │   │                                     │
 └────────────────┘   └─────────────────────────────────────┘
   ^  sbuf_insert()
   │  放入任务
```

```c
// 基于 sbuf（生产者-消费者）的线程池
sbuf_t sbuf;  // 共享缓冲区

void *worker_thread(void *vargp)
{
    pthread_detach(pthread_self());
    while (1) {
        int connfd = sbuf_remove(&sbuf);  // 阻塞等待任务
        handle_request(connfd);           // 处理请求
        close(connfd);
    }
}

int main(int argc, char **argv)
{
    int listenfd;
    pthread_t tid;

    listenfd = open_listenfd(argv[1]);
    sbuf_init(&sbuf, SBUFSIZE);

    // 预创建线程池
    for (int i = 0; i < NTHREADS; i++)
        pthread_create(&tid, NULL, worker_thread, NULL);

    // 主线程作为生产者
    while (1) {
        int connfd = accept(listenfd, ...);
        sbuf_insert(&sbuf, connfd);       // 放入任务队列
    }
}
```

线程池的优势：
- 避免了为每个请求创建/销毁线程的开销
- 通过有界缓冲区控制并发度
- 线程数固定，资源可控
- 任务排队机制自动平滑负载波动

---

# 核心概念速查表

| 概念                 | 要点                                                           |
|:---------------------|:---------------------------------------------------------------|
| 文件描述符           | 非负整数，0=stdin，1=stdout，2=stderr                          |
| 不足值               | 网络编程中 read/write 返回值 < 请求值，必须用循环处理          |
| RIO 包               | 自动处理不足值和信号中断，分无缓冲和带缓冲两类                 |
| dup2                 | I/O 重定向的核心，CGI 中用于将 stdout 重定向到 socket          |
| 网络字节序           | 大端法，htonl/htons/ntohl/ntohs 转换                           |
| socket               | 创建套接字端点                                                 |
| bind + listen        | 将套接字变为被动监听套接字                                     |
| accept               | 从监听套接字获取已连接套接字                                   |
| listenfd vs connfd   | 监听描述符只有一个，已连接描述符每客户端一个                   |
| getaddrinfo          | 协议无关的地址解析，替代 gethostbyname                         |
| epoll                | Linux 高性能 I/O 多路复用，O(k) 复杂度                        |
| 边缘触发 vs 水平触发 | ET 只通知状态变化，LT 只要就绪就通知                           |
| 线程 vs 进程         | 线程共享地址空间（轻量），进程隔离（安全）                     |
| 信号量               | P 减 1（可能阻塞），V 加 1（可能唤醒）                        |
| 互斥锁               | 初始值为 1 的二元信号量                                        |
| 竞争条件             | 结果依赖于线程执行顺序，用 malloc 独立传参来避免               |
| 死锁                 | 四个必要条件；用锁排序预防                                     |
| 线程安全             | 四类不安全函数，优先使用可重入函数                             |
| 生产者-消费者        | 三个信号量：mutex + slots + items                              |
| 读者-写者            | readcnt 计数，第一个读者锁写者，最后一个读者放写者             |

---

---

# 附录

---

## 附录A：TCP/IP协议栈深入

### A.1 TCP三次握手ASCII时序图

TCP建立连接时，客户端和服务器通过三次报文交换完成连接建立。以下是详细的ASCII时序图：

```
                        TCP 三次握手（Three-Way Handshake）

    客户端 (Client)                                         服务器 (Server)
    ====================                                    ====================
    状态: CLOSED                                            状态: LISTEN

    1. 客户端发起连接，发送 SYN
    ------------------------------------------------------------------------>
        SYN=1, seq=x (初始序号)
        flags: SYN
        状态: CLOSED → SYN_SENT

                                                            2. 服务器收到 SYN
                                                            状态: LISTEN → SYN_RCVD
                                                            发送 SYN-ACK

    <------------------------------------------------------------------------
        SYN=1, ACK=1, seq=y (服务器初始序号), ack=x+1
        flags: SYN, ACK

    3. 客户端收到 SYN-ACK，发送 ACK
    ------------------------------------------------------------------------>
        ACK=1, seq=x+1, ack=y+1
        flags: ACK
        状态: SYN_SENT → ESTABLISHED

                                                            服务器收到 ACK
                                                            状态: SYN_RCVD → ESTABLISHED

    ======== 双方进入 ESTABLISHED 状态，可以开始数据传输 ========
```

关键要点：
- **seq=x**：客户端选择的初始序号（Initial Sequence Number, ISN），通常是随机的，防止序号预测攻击
- **seq=y**：服务器选择的初始序号
- **ack=x+1**：确认号 = 收到的 SYN 的 seq + 1，表示"我收到了你的 SYN，期望下一次收到 x+1"
- **ack=y+1**：同理，客户端确认服务器的 SYN
- **SYN 标志位**：Synchronize，用于同步序号
- **ACK 标志位**：Acknowledge，确认号字段有效

三次握手为什么是三次而不是两次？
- 两次握手无法防止历史连接（旧 SYN）被误当作新连接
- 第三次 ACK 让服务器确认客户端确实收到了 SYN-ACK，双方都知道对方能收能发

### A.2 TCP四次挥手ASCII时序图

TCP断开连接需要四次报文交换，因为TCP是全双工的，每一方都需要单独关闭。

```
                        TCP 四次挥手（Four-Way Handshake / Connection Termination）

    主动关闭方 (Active Closer)                              被动关闭方 (Passive Closer)
    ========================                                ========================
    状态: ESTABLISHED                                        状态: ESTABLISHED

    1. 主动关闭方发送 FIN
    ------------------------------------------------------------------------>
        FIN=1, seq=u
        flags: FIN, ACK
        状态: ESTABLISHED → FIN_WAIT_1

                                                            2. 被动关闭方收到 FIN
                                                            状态: ESTABLISHED → CLOSE_WAIT
                                                            发送 ACK

    <------------------------------------------------------------------------
        ACK=1, seq=v, ack=u+1
        flags: ACK
        状态: 保持 CLOSE_WAIT

        此时主动关闭方：
        状态: FIN_WAIT_1 → FIN_WAIT_2
        （等待被动关闭方的 FIN）

        ======== 半关闭状态（Half-Close）========
        主动关闭方不再发送数据，但仍能接收
        被动关闭方仍可发送数据

                                                            3. 被动关闭方发送 FIN
                                                            (应用层调用 close())

    <------------------------------------------------------------------------
        FIN=1, ACK=1, seq=w, ack=u+1
        flags: FIN, ACK
        状态: CLOSE_WAIT → LAST_ACK

    4. 主动关闭方收到 FIN，发送 ACK
    ------------------------------------------------------------------------>
        ACK=1, seq=u+1, ack=w+1
        flags: ACK
        状态: FIN_WAIT_2 → TIME_WAIT

                                                            被动关闭方收到 ACK
                                                            状态: LAST_ACK → CLOSED

    ======== TIME_WAIT 状态（持续 2*MSL）========

    主动关闭方在此状态等待 2 倍 MSL（Maximum Segment Lifetime）
    典型值：MSL = 30秒 → TIME_WAIT 持续 60秒

    TIME_WAIT 的目的：
    1. 确保最后的 ACK 能到达被动关闭方
       （如果丢失，被动关闭方会重发 FIN，主动关闭方可以重新发送 ACK）
    2. 等待本连接的所有报文段都从网络中消失
       （防止旧报文段被误当作新连接的数据）

    2*MSL 后：
    状态: TIME_WAIT → CLOSED
```

TIME_WAIT 状态是面试常考点：
- 只出现在主动关闭连接的一方
- 高并发服务器如果大量主动关闭连接，会产生大量 TIME_WAIT，消耗端口资源
- 解决方案：让客户端主动关闭；或设置 `SO_REUSEADDR` 选项

### A.3 TCP状态转换图

以下是完整的 TCP 状态转换图，展示从连接建立到连接断开的所有状态变化：

```
                              TCP 状态转换图

                        +-----------+
                        |  CLOSED   |
                        +-----------+
                              |
                  主动打开      |        被动打开
              (connect/SYN)    |    (listen 等待连接)
                              v              v
                   +-----------+      +-----------+
                   | SYN_SENT  |      |  LISTEN   |
                   +-----------+      +-----------+
                         |                  |
                    收到  |           收到    |
                   SYN-ACK |          SYN     |
                         v           发送     v
                   +-----------+    SYN-ACK  +-----------+
                   |           |<-----------| SYN_RCVD  |
                   |           |            +-----------+
         发送ACK   |           |                  |
         +-------> | ESTABLISHED|<---收到ACK------+ |
         |         |           |                    v
         |         +-----------+              +-----------+
         |              |                    | ESTABLISHED|
         |    主动关闭   |      被动关闭        +-----------+
         |  (close/FIN) |    (收到FIN/ACK)          |
         v              v                          |
   +-----------+  +-----------+                    |
   |FIN_WAIT_1 |  | CLOSE_WAIT|<-----收到FIN-------+
   +-----------+  +-----------+
         |              |
    收到ACK|         应用层|
         v          close  |
   +-----------+        v  |
   |FIN_WAIT_2 |  +-----------+
   +-----------+  | LAST_ACK  |
         |        +-----------+
    收到FIN|              |
    发送ACKv         收到ACK|
   +-----------+          v
   | TIME_WAIT |     +-----------+
   +-----------+     |  CLOSED   |
         |           +-----------+
    等待2*MSL
         v
   +-----------+
   |  CLOSED   |
   +-----------+

  状态说明：
  ─────────────────────────────────────────────────
  CLOSED         初始状态，无连接
  LISTEN         服务器等待连接请求
  SYN_SENT       客户端已发送 SYN，等待 SYN-ACK
  SYN_RCVD       服务器收到 SYN，已发送 SYN-ACK，等待 ACK
  ESTABLISHED    连接已建立，可以传输数据
  FIN_WAIT_1     主动关闭方已发送 FIN
  FIN_WAIT_2     主动关闭方收到对 FIN 的 ACK，等待对方 FIN
  CLOSE_WAIT     被动关闭方收到 FIN，已发送 ACK，等待应用层 close
  LAST_ACK       被动关闭方已发送 FIN，等待最后一个 ACK
  TIME_WAIT      主动关闭方收到 FIN 并发送 ACK 后，等待 2*MSL
  CLOSED         连接完全关闭
```

### A.4 TCP滑动窗口和流量控制原理

TCP 使用滑动窗口机制实现流量控制（Flow Control），确保发送方不会淹没接收方。

```
                    TCP 滑动窗口机制

    发送方窗口（发送缓冲区）：

    已发送已确认    |    已发送未确认    |  可以发送但未发送  |  不能发送
                   |    (在窗口内)      |   (在窗口内)      |
    +--------+-----+-------------------+------------------+----------+
    |        |     |                   |                  |          |
    +--------+-----+-------------------+------------------+----------+
                   ^                   ^                  ^
                   |<- 发送窗口 (Window Size) ->|
                   left edge          right edge

    窗口左边沿：最后已确认字节的下一个字节
    窗口右边沿：left edge + window size
    窗口大小：由接收方通过 ACK 报文中的 "Window Size" 字段通告


    接收方通告窗口：

    接收方缓冲区：
    +-----------+-------------------+------------------+
    | 已读出应用 |  已收到未读出应用  |    空闲空间      |
    +-----------+-------------------+------------------+
    ^           ^                   ^                  ^
    0       next_byte_to_read   next_expected      buffer_end

    Window Size = 空闲空间大小
    接收方在每次 ACK 中携带当前 Window Size

    如果 Window Size = 0，发送方必须停止发送
    （但会发送零窗口探测包，防止死锁）


    滑动过程示例：

    时刻 T1：
    +---+---+---+---+---+---+---+---+---+---+
    | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10|  字节序号
    +---+---+---+---+---+---+---+---+---+---+
            [已确认 1-2]  [发送了 3-5]  [可发 6-8]   窗口=6
            ^                              ^
            left=3                    right=9

    收到 ACK=3（确认了3之前的数据），窗口滑动：

    时刻 T2：
    +---+---+---+---+---+---+---+---+---+---+
    | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10|  字节序号
    +---+---+---+---+---+---+---+---+---+---+
        [已确认 1-3]      [发送了 4-5]  [可发 6-9]   窗口=6
                        ^                        ^
                        left=4              right=10
```

流量控制 vs 拥塞控制：
- **流量控制**：端到端的，接收方通过窗口大小控制发送方速率，防止接收方缓冲区溢出
- **拥塞控制**：网络层面的，发送方根据网络拥塞程度调整发送速率，防止网络过载

### A.5 TCP拥塞控制四个阶段

TCP 拥塞控制通过维护一个拥塞窗口 cwnd 来控制发送速率，与接收方通告的 rwnd 取较小值作为实际发送窗口。

```
                    TCP 拥塞控制四个阶段

    cwnd
    ↑
    |                              /--- 拥塞避免（线性增长）
    |                            /
    |                          /
    |         慢启动           |     快恢复（cwnd 减半后线性增长）
    |         (指数增长)       |    /
    |        /                |  /
    |       /                | /
    |      /    ←ssthresh   |/
    |     /    ---×---     /  ← 新 ssthresh
    |    /      快重传    /
    |   /                /
    |  /                /
    | /                /
    |/________________/___________________→ RTT (时间)

    四个阶段详解：
    ═══════════════════════════════════════════════════

    1. 慢启动（Slow Start）
    ─────────────────────────
    - 初始 cwnd = 1 MSS（最大报文段长度，通常 1460 字节）
    - 每收到一个 ACK，cwnd 加 1（即每个 RTT 翻倍，指数增长）
    - 增长速度：1 → 2 → 4 → 8 → 16 → ...
    - 停止条件：cwnd 达到 ssthresh（慢启动阈值）时切换到拥塞避免
    - 或：检测到丢包（超时或重复 ACK）

    2. 拥塞避免（Congestion Avoidance）
    ───────────────────────────────────
    - 当 cwnd >= ssthresh 时进入
    - 每经过一个 RTT，cwnd 加 1 MSS（线性增长，比慢启动慢得多）
    - 增长速度：cwnd + 1 每个RTT
    - 目的：谨慎探测可用带宽，避免过快增长导致拥塞

    3. 快重传（Fast Retransmit）
    ────────────────────────────
    - 当发送方收到 3 个重复 ACK（Duplicate ACK）时
    - 认为该报文段已丢失，不等超时定时器到期
    - 立即重传丢失的报文段
    - 同时：ssthresh = cwnd / 2
    - 触发快恢复

    为什么是 3 个重复 ACK？
    - 1 个重复 ACK 可能是乱序到达，不一定是丢包
    - 2 个重复 ACK 仍可能是乱序
    - 3 个重复 ACK 基本可以确定是丢包
    - 这是经验值，平衡了误判率和检测延迟

    4. 快恢复（Fast Recovery）
    ──────────────────────────
    - 快重传后进入（不回到慢启动）
    - cwnd = ssthresh（即旧 cwnd 的一半）
    - 继续拥塞避免（线性增长）
    - 注意：收到重复 ACK 说明接收方仍在收数据，网络只是轻度拥塞

    超时丢包 vs 重复 ACK 丢包的区别：
    ┌─────────────────┬──────────────────────┬──────────────────────┐
    │                 │    超时（Timeout）     │  3个重复ACK            │
    ├─────────────────┼──────────────────────┼──────────────────────┤
    │ 拥塞程度         │  严重（报文完全丢失）  │  轻微（部分报文到达）   │
    │ ssthresh        │  cwnd / 2            │  cwnd / 2             │
    │ cwnd 新值        │  1 MSS（回到慢启动）   │  ssthresh（快恢复）    │
    │ 增长方式         │  指数（慢启动）        │  线性（拥塞避免）       │
    └─────────────────┴──────────────────────┴──────────────────────┘
```

---

## 附录B：epoll编程详细教程

### B.1 epoll核心函数详解

epoll 是 Linux 特有的 I/O 多路复用机制，相比 select 和 poll，它在处理大量连接时性能更优。

```c
#include <sys/epoll.h>

/*
 * epoll_create - 创建一个 epoll 实例
 * @size: 告诉内核需要监听的 fd 数量（Linux 2.6.8+ 忽略此参数，但必须 > 0）
 * @return: 成功返回 epoll 实例的文件描述符（epfd），失败返回 -1
 *
 * 内核会创建一个 eventpoll 结构体，包含：
 * - 红黑树（rbtree）：存储所有注册的 fd，支持高效查找/插入/删除
 * - 就绪链表（rdllist）：存储已就绪的 fd
 */
int epoll_create(int size);

// 推荐使用 epoll_create1（支持 flags）
// int epoll_create1(int flags);
// flags = 0 等价于 epoll_create(0)
// flags = EPOLL_CLOEXEC：fd 在 exec 时自动关闭

/*
 * epoll_ctl - 操作 epoll 实例中的 fd
 * @epfd:    epoll_create 返回的文件描述符
 * @op:      操作类型
 * @fd:      要操作的文件描述符
 * @event:   告诉内核需要监听什么事件
 * @return:  成功返回 0，失败返回 -1
 *
 * op 取值：
 *   EPOLL_CTL_ADD  - 注册新的 fd 到 epfd
 *   EPOLL_CTL_MOD  - 修改已注册 fd 的监听事件
 *   EPOLL_CTL_DEL  - 从 epfd 中删除一个 fd
 */
int epoll_ctl(int epfd, int op, int fd, struct epoll_event *event);

/*
 * epoll_wait - 等待事件发生
 * @epfd:      epoll_create 返回的文件描述符
 * @events:    输出参数，用于接收就绪的事件数组（由调用者分配）
 * @maxevents: events 数组的最大大小（必须 > 0）
 * @timeout:   超时时间（毫秒）
 *             -1: 永久阻塞
 *              0: 立即返回（非阻塞）
 *             >0: 等待 timeout 毫秒
 * @return:    就绪 fd 的数量；0 表示超时；-1 表示出错（errno 被设置）
 *
 * 注意：返回的就绪事件是 events 数组的前 n 个元素
 *       每个元素包含 events 字段（发生的事件类型）和 data 字段（用户数据）
 */
int epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout);
```

### B.2 epoll_event 结构体

```c
/*
 * epoll_event 结构体定义
 */
struct epoll_event {
    uint32_t events;       /* 事件类型（位掩码，可组合） */
    epoll_data_t data;     /* 用户数据，用于关联 fd */
} __attribute__((packed));

/*
 * epoll_data_t 联合体（只能使用其中一个字段）
 */
typedef union epoll_data {
    void    *ptr;          /* 自定义指针（最灵活，可指向结构体） */
    int      fd;           /* 文件描述符（最常用） */
    uint32_t u32;
    uint64_t u64;
} epoll_data_t;

/*
 * 常用事件类型（events 字段的位掩码）
 */
#define EPOLLIN       0x001   /* fd 可读（包括对端正常关闭） */
#define EPOLLOUT      0x004   /* fd 可写 */
#define EPOLLERR      0x008   /* 错误（epoll_wait 总是监听此事件） */
#define EPOLLHUP      0x010   /* 挂起（epoll_wait 总是监听此事件） */
#define EPOLLRDHUP    0x2000  /* 对端关闭连接（Linux 2.6.17+） */
#define EPOLLET       0x80000000 /* 边缘触发模式（ET） */

/* 组合使用示例 */
struct epoll_event ev;
ev.events = EPOLLIN | EPOLLET;   /* 可读 + 边缘触发 */
ev.data.fd = connfd;             /* 关联文件描述符 */
epoll_ctl(epfd, EPOLL_CTL_ADD, connfd, &ev);
```

### B.3 水平触发（LT）vs 边缘触发（ET）

```
┌──────────────────┬─────────────────────────────┬─────────────────────────────┐
│                  │     水平触发 (LT - Level     │     边缘触发 (ET - Edge      │
│                  │     Triggered)               │     Triggered)               │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 触发条件          │ 只要 fd 处于可读/可写状态     │ 仅在状态变化时触发一次        │
│                  │ （缓冲区有数据就持续通知）     │ （新数据到达时通知一次）       │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 通知频率          │ 高（重复通知直到数据被读完）   │ 低（每个状态变化只通知一次）   │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 编程复杂度        │ 低（读到 EAGAIN 即可）       │ 高（必须循环读到 EAGAIN）     │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 是否必须非阻塞    │ 否（但推荐）                  │ 是（必须设置 O_NONBLOCK）    │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 读写方式          │ 可以每次只读一部分            │ 必须一次读完缓冲区所有数据     │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ epoll_wait 返回后 │ 可以不处理完，下次还会通知    │ 必须处理完，否则不会再通知     │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 默认模式          │ 是（epoll 默认 LT）           │ 需显式设置 EPOLLET           │
├──────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 适用场景          │ 通用场景，简单易用            │ 高性能服务器，减少 epoll_wait │
│                  │                             │ 调用次数                     │
└──────────────────┴─────────────────────────────┴─────────────────────────────┘

  ET 模式的"惊群"问题：
  在 ET 模式下，如果缓冲区有 10KB 数据但你只读了 4KB，
  剩余 6KB 不会再次触发 epoll_wait，导致数据"丢失"（实际还在缓冲区但永远不会被通知）
  → 必须用 while 循环读到返回 EAGAIN/EWOULDBLOCK 为止
```

**LT 模式示例代码：**

```c
/* 水平触发模式 - 简单，每次读一部分即可 */
void lt_handle(int epfd, int fd) {
    char buf[512];
    /* LT 模式：读一次即可，如果没读完下次 epoll_wait 还会通知 */
    int n = read(fd, buf, sizeof(buf));
    if (n > 0) {
        printf("read %d bytes\n", n);
        /* 处理数据 */
    } else if (n == 0) {
        /* 对端关闭 */
        epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
        close(fd);
    }
}
```

**ET 模式示例代码：**

```c
/* 边缘触发模式 - 必须循环读到 EAGAIN */
void et_handle(int epfd, int fd) {
    char buf[512];
    int n;

    /* ET 模式：必须循环读，直到 EAGAIN */
    while (1) {
        n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            printf("read %d bytes\n", n);
            /* 处理数据 */
        } else if (n == 0) {
            /* 对端关闭 */
            epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
            close(fd);
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* 缓冲区已读完，正常退出循环 */
                break;
            }
            if (errno == EINTR) {
                /* 被信号中断，继续读 */
                continue;
            }
            /* 其他错误 */
            perror("read error");
            close(fd);
            break;
        }
    }
}
```

### B.4 完整的 epoll 回显服务器

以下是一个完整的、带详细注释的 epoll 回显服务器实现（LT 模式）：

```c
/*
 * epoll_echo_server.c - 使用 epoll 实现的回显服务器
 * 编译: gcc -o epoll_echo_server epoll_echo_server.c
 * 运行: ./epoll_echo_server 8080
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/epoll.h>

#define MAX_EVENTS  64      /* epoll_wait 每次最多返回的事件数 */
#define BUF_SIZE    1024    /* 读写缓冲区大小 */
#define BACKLOG     128     /* listen 队列最大长度 */

/*
 * 设置文件描述符为非阻塞模式
 * 在 ET 模式下必须使用非阻塞 fd，否则 read/write 可能阻塞
 */
static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1) {
        perror("fcntl F_GETFL");
        return -1;
    }
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) {
        perror("fcntl F_SETFL");
        return -1;
    }
    return 0;
}

/*
 * 处理读取到的连接
 * 回显逻辑：读取客户端数据，原样写回
 */
static void handle_client(int epfd, int fd) {
    char buf[BUF_SIZE];
    ssize_t n;

    /* 读取数据 */
    n = read(fd, buf, sizeof(buf) - 1);
    if (n > 0) {
        buf[n] = '\0';
        printf("Received from fd=%d: %s", fd, buf);

        /* 回显：原样写回 */
        /* 注意：实际生产环境应该处理 write 的不足值 */
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(fd, buf + written, n - written);
            if (w <= 0) {
                if (errno == EINTR) continue;
                perror("write");
                break;
            }
            written += w;
        }
    } else if (n == 0) {
        /* 客户端关闭连接 */
        printf("Client fd=%d disconnected\n", fd);
        epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
        close(fd);
    } else {
        /* n < 0: 出错 */
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            perror("read");
            epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
            close(fd);
        }
        /* EAGAIN/EWOULDBLOCK: 非阻塞模式下无数据可读，正常情况 */
    }
}

/*
 * 接受新连接
 */
static void handle_accept(int epfd, int listenfd) {
    struct sockaddr_in client_addr;
    socklen_t client_len = sizeof(client_addr);
    int connfd;

    /* 循环 accept，处理可能的多个待连接（LT 模式下 accept 一次也行） */
    while (1) {
        connfd = accept(listenfd, (struct sockaddr *)&client_addr, &client_len);
        if (connfd == -1) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* 没有更多待连接了 */
                break;
            }
            perror("accept");
            break;
        }

        /* 设置非阻塞 */
        set_nonblocking(connfd);

        /* 打印客户端信息 */
        char ip[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, ip, sizeof(ip));
        printf("New connection from %s:%d, fd=%d\n",
               ip, ntohs(client_addr.sin_port), connfd);

        /* 将新连接加入 epoll 监听 */
        struct epoll_event ev;
        ev.events = EPOLLIN;        /* 监听可读事件 */
        ev.data.fd = connfd;
        if (epoll_ctl(epfd, EPOLL_CTL_ADD, connfd, &ev) == -1) {
            perror("epoll_ctl ADD");
            close(connfd);
        }
    }
}

int main(int argc, char *argv[]) {
    if (argc != 2) {
        fprintf(stderr, "Usage: %s <port>\n", argv[0]);
        exit(EXIT_FAILURE);
    }

    int port = atoi(argv[1]);

    /* ========== 1. 创建监听 socket ========== */
    int listenfd = socket(AF_INET, SOCK_STREAM, 0);
    if (listenfd == -1) {
        perror("socket");
        exit(EXIT_FAILURE);
    }

    /* 设置 SO_REUSEADDR，允许重用处于 TIME_WAIT 的地址 */
    int opt = 1;
    setsockopt(listenfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    /* 设置监听 socket 为非阻塞 */
    set_nonblocking(listenfd);

    /* ========== 2. bind ========== */
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;   /* 监听所有网卡 */
    server_addr.sin_port = htons(port);

    if (bind(listenfd, (struct sockaddr *)&server_addr, sizeof(server_addr)) == -1) {
        perror("bind");
        close(listenfd);
        exit(EXIT_FAILURE);
    }

    /* ========== 3. listen ========== */
    if (listen(listenfd, BACKLOG) == -1) {
        perror("listen");
        close(listenfd);
        exit(EXIT_FAILURE);
    }

    printf("Epoll echo server listening on port %d\n", port);

    /* ========== 4. 创建 epoll 实例 ========== */
    int epfd = epoll_create1(0);
    if (epfd == -1) {
        perror("epoll_create1");
        close(listenfd);
        exit(EXIT_FAILURE);
    }

    /* 将监听 socket 加入 epoll */
    struct epoll_event ev;
    ev.events = EPOLLIN;        /* 监听可读事件（新连接到来） */
    ev.data.fd = listenfd;
    if (epoll_ctl(epfd, EPOLL_CTL_ADD, listenfd, &ev) == -1) {
        perror("epoll_ctl ADD listenfd");
        close(epfd);
        close(listenfd);
        exit(EXIT_FAILURE);
    }

    /* ========== 5. 事件循环 ========== */
    struct epoll_event events[MAX_EVENTS];

    printf("Entering event loop...\n");

    while (1) {
        /* 等待事件，-1 表示永久阻塞 */
        int nready = epoll_wait(epfd, events, MAX_EVENTS, -1);
        if (nready == -1) {
            if (errno == EINTR) {
                /* 被信号中断，正常情况，继续 */
                continue;
            }
            perror("epoll_wait");
            break;
        }

        /* 遍历所有就绪事件 */
        for (int i = 0; i < nready; i++) {
            int fd = events[i].data.fd;

            if (fd == listenfd) {
                /* 监听 socket 就绪 → 有新连接 */
                handle_accept(epfd, listenfd);
            } else {
                /* 客户端 socket 就绪 → 有数据可读或连接关闭 */
                if (events[i].events & EPOLLIN) {
                    handle_client(epfd, fd);
                }
                if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                    printf("Error on fd=%d, closing\n", fd);
                    epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
                    close(fd);
                }
            }
        }
    }

    /* ========== 6. 清理 ========== */
    close(epfd);
    close(listenfd);
    return 0;
}
```

---

## 附录C：线程池完整实现

### C.1 线程池结构设计

线程池预先创建一组工作线程，通过任务队列分发任务，避免频繁创建/销毁线程的开销。

```
                    线程池结构设计

    +-------------------------------------------------------------------+
    |                         threadpool_t                              |
    |                                                                   |
    |  +-------------------+        +----------------------------+      |
    |  | 任务队列           |        | 工作线程数组                |      |
    |  | (task queue)      |        | (threads[])                 |      |
    |  |                   |        |                             |      |
    |  | [task]→[task]→... |        | thread[0] thread[1] ...    |      |
    |  |  head     tail    |        |   ↑          ↑              |      |
    |  +-------------------+        +---|----------|--------------+      |
    |         ↑                         |          |                     |
    |         |                   取任务 |     取任务|                     |
    |    threadpool_add_task            |          |                     |
    |    (生产者)                        v          v                     |
    |                              +----------------+                    |
    |  mutex    ────────────────→  | worker_thread  |                    |
    |  cond     ────────────────→  | (消费者)       |                    |
    |  shutdown ────────────────→  +----------------+                    |
    |                                                                   |
    +-------------------------------------------------------------------+

    同步机制：
    - mutex:  保护任务队列的互斥访问
    - cond_not_empty: 任务队列非空时通知等待的工作线程
    - cond_not_full:  任务队列非满时通知等待的生产者（如果队列有上限）
    - shutdown: 标志线程池是否正在关闭
```

### C.2 线程池完整C代码

```c
/*
 * threadpool.h - 线程池实现头文件
 */

#ifndef THREADPOOL_H
#define THREADPOOL_H

#include <pthread.h>

/* 任务函数类型 */
typedef void (*task_func_t)(void *arg);

/* 任务结构体 */
typedef struct task {
    task_func_t func;           /* 任务函数指针 */
    void       *arg;            /* 任务参数 */
    struct task *next;          /* 下一个任务（链表） */
} task_t;

/* 线程池结构体 */
typedef struct threadpool {
    pthread_mutex_t lock;           /* 互斥锁，保护整个线程池 */
    pthread_cond_t  cond_not_empty; /* 条件变量：任务队列非空 */
    pthread_cond_t  cond_not_full;  /* 条件变量：任务队列非满 */

    pthread_t      *threads;        /* 工作线程数组 */
    task_t         *task_queue_head;/* 任务队列头指针 */
    task_t         *task_queue_tail;/* 任务队列尾指针 */

    int queue_size;                 /* 当前任务队列长度 */
    int queue_capacity;             /* 任务队列最大容量（0=无限） */

    int thread_count;               /* 工作线程数量 */
    int shutdown;                   /* 是否正在关闭：0=运行中，1=关闭中 */
} threadpool_t;

/* API */
threadpool_t *threadpool_create(int thread_count, int queue_capacity);
int threadpool_add_task(threadpool_t *pool, task_func_t func, void *arg);
int threadpool_destroy(threadpool_t *pool);

#endif /* THREADPOOL_H */
```

```c
/*
 * threadpool.c - 线程池实现
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include "threadpool.h"

/*
 * 工作线程函数 - 每个工作线程都执行这个函数
 * 不断从任务队列取任务并执行
 */
static void *worker_thread(void *arg) {
    threadpool_t *pool = (threadpool_t *)arg;

    while (1) {
        /* ========== 加锁，访问任务队列 ========== */
        pthread_mutex_lock(&(pool->lock));

        /* 如果任务队列为空且线程池未关闭，等待任务到来 */
        while (pool->queue_size == 0 && !pool->shutdown) {
            /* 阻塞等待 cond_not_empty 信号 */
            pthread_cond_wait(&(pool->cond_not_empty), &(pool->lock));
        }

        /* 如果线程池正在关闭，退出线程 */
        if (pool->shutdown) {
            pthread_mutex_unlock(&(pool->lock));
            printf("Worker thread %lu: exiting (shutdown)\n",
                   (unsigned long)pthread_self());
            pthread_exit(NULL);
        }

        /* 从任务队列头部取出一个任务 */
        task_t *task = pool->task_queue_head;
        pool->task_queue_head = task->next;

        /* 如果取走后队列为空，更新 tail 指针 */
        if (pool->task_queue_head == NULL) {
            pool->task_queue_tail = NULL;
        }

        pool->queue_size--;

        /* 通知生产者：队列不满了（如果有容量限制） */
        pthread_cond_signal(&(pool->cond_not_full));

        /* ========== 解锁 ========== */
        pthread_mutex_unlock(&(pool->lock));

        /* 执行任务（在锁外执行，不阻塞其他线程） */
        task->func(task->arg);

        /* 释放任务结构体 */
        free(task);
    }

    pthread_exit(NULL);
    return NULL;
}

/*
 * 创建线程池
 * @thread_count: 工作线程数量
 * @queue_capacity: 任务队列最大容量（0表示无限制）
 * @return: 线程池指针，失败返回NULL
 */
threadpool_t *threadpool_create(int thread_count, int queue_capacity) {
    threadpool_t *pool = NULL;

    /* 参数检查 */
    if (thread_count <= 0) {
        thread_count = 4;  /* 默认4个线程 */
    }

    /* 分配线程池结构体 */
    pool = (threadpool_t *)malloc(sizeof(threadpool_t));
    if (pool == NULL) {
        perror("malloc threadpool");
        return NULL;
    }
    memset(pool, 0, sizeof(threadpool_t));

    /* 初始化字段 */
    pool->thread_count = thread_count;
    pool->queue_capacity = queue_capacity;
    pool->queue_size = 0;
    pool->task_queue_head = NULL;
    pool->task_queue_tail = NULL;
    pool->shutdown = 0;

    /* 分配工作线程数组 */
    pool->threads = (pthread_t *)malloc(sizeof(pthread_t) * thread_count);
    if (pool->threads == NULL) {
        perror("malloc threads");
        free(pool);
        return NULL;
    }

    /* 初始化互斥锁和条件变量 */
    if (pthread_mutex_init(&(pool->lock), NULL) != 0) {
        perror("pthread_mutex_init");
        free(pool->threads);
        free(pool);
        return NULL;
    }
    if (pthread_cond_init(&(pool->cond_not_empty), NULL) != 0) {
        perror("pthread_cond_init not_empty");
        pthread_mutex_destroy(&(pool->lock));
        free(pool->threads);
        free(pool);
        return NULL;
    }
    if (pthread_cond_init(&(pool->cond_not_full), NULL) != 0) {
        perror("pthread_cond_init not_full");
        pthread_mutex_destroy(&(pool->lock));
        pthread_cond_destroy(&(pool->cond_not_empty));
        free(pool->threads);
        free(pool);
        return NULL;
    }

    /* 创建工作线程 */
    for (int i = 0; i < thread_count; i++) {
        if (pthread_create(&(pool->threads[i]), NULL,
                          worker_thread, (void *)pool) != 0) {
            perror("pthread_create");
            /* 创建失败，销毁已创建的资源 */
            pool->shutdown = 1;
            pthread_cond_broadcast(&(pool->cond_not_empty));
            for (int j = 0; j < i; j++) {
                pthread_join(pool->threads[j], NULL);
            }
            free(pool->threads);
            pthread_mutex_destroy(&(pool->lock));
            pthread_cond_destroy(&(pool->cond_not_empty));
            pthread_cond_destroy(&(pool->cond_not_full));
            free(pool);
            return NULL;
        }
    }

    printf("Thread pool created: %d threads, queue capacity = %d\n",
           thread_count, queue_capacity);

    return pool;
}

/*
 * 向线程池添加任务
 * @pool: 线程池
 * @func: 任务函数
 * @arg:  任务参数
 * @return: 0成功，-1失败
 */
int threadpool_add_task(threadpool_t *pool, task_func_t func, void *arg) {
    if (pool == NULL || func == NULL) {
        return -1;
    }

    /* 创建新任务节点 */
    task_t *new_task = (task_t *)malloc(sizeof(task_t));
    if (new_task == NULL) {
        perror("malloc task");
        return -1;
    }
    new_task->func = func;
    new_task->arg = arg;
    new_task->next = NULL;

    pthread_mutex_lock(&(pool->lock));

    /* 如果队列有容量限制且已满，等待 */
    while (pool->queue_capacity > 0 &&
           pool->queue_size >= pool->queue_capacity &&
           !pool->shutdown) {
        pthread_cond_wait(&(pool->cond_not_full), &(pool->lock));
    }

    /* 如果线程池已关闭，不再接受新任务 */
    if (pool->shutdown) {
        pthread_mutex_unlock(&(pool->lock));
        free(new_task);
        return -1;
    }

    /* 将任务加入队列尾部 */
    if (pool->task_queue_tail == NULL) {
        /* 队列为空 */
        pool->task_queue_head = new_task;
        pool->task_queue_tail = new_task;
    } else {
        pool->task_queue_tail->next = new_task;
        pool->task_queue_tail = new_task;
    }

    pool->queue_size++;

    /* 通知等待的工作线程：有新任务了 */
    pthread_cond_signal(&(pool->cond_not_empty));

    pthread_mutex_unlock(&(pool->lock));

    return 0;
}

/*
 * 销毁线程池
 * 等待所有任务完成，然后关闭所有线程
 * @return: 0成功，-1失败
 */
int threadpool_destroy(threadpool_t *pool) {
    if (pool == NULL) {
        return -1;
    }

    pthread_mutex_lock(&(pool->lock));

    /* 如果已经在关闭，直接返回 */
    if (pool->shutdown) {
        pthread_mutex_unlock(&(pool->lock));
        return -1;
    }

    /* 设置关闭标志 */
    pool->shutdown = 1;

    /* 唤醒所有等待的工作线程 */
    pthread_cond_broadcast(&(pool->cond_not_empty));
    pthread_cond_broadcast(&(pool->cond_not_full));

    pthread_mutex_unlock(&(pool->lock));

    /* 等待所有工作线程结束 */
    for (int i = 0; i < pool->thread_count; i++) {
        pthread_join(pool->threads[i], NULL);
        printf("Thread %d joined\n", i);
    }

    /* 清理剩余任务 */
    task_t *task = pool->task_queue_head;
    while (task != NULL) {
        task_t *next = task->next;
        free(task);
        task = next;
    }

    /* 销毁同步原语 */
    pthread_mutex_destroy(&(pool->lock));
    pthread_cond_destroy(&(pool->cond_not_empty));
    pthread_cond_destroy(&(pool->cond_not_full));

    /* 释放内存 */
    free(pool->threads);
    free(pool);

    printf("Thread pool destroyed\n");

    return 0;
}
```

### C.3 在代理服务器中使用线程池的示例

```c
/*
 * proxy_with_threadpool.c - 使用线程池的代理服务器框架
 */

#include "threadpool.h"
#include <sys/socket.h>
#include <netinet/in.h>

/* 处理一个客户端连接的任务函数 */
void handle_request(void *arg) {
    /* arg 是通过 malloc 分配的，包含 connfd 等信息 */
    int connfd = *(int *)arg;
    free(arg);  /* 释放参数内存，避免内存泄漏 */

    /* 1. 读取 HTTP 请求 */
    /* 2. 解析请求行和头部 */
    /* 3. 连接目标服务器 */
    /* 4. 转发请求 */
    /* 5. 接收响应 */
    /* 6. 转发响应给客户端 */
    /* 7. 查询/更新缓存 */

    printf("Handling connection on fd=%d\n", connfd);

    /* ... 实际代理逻辑 ... */

    close(connfd);
}

int main(int argc, char *argv[]) {
    int port = atoi(argv[1]);

    /* 创建线程池：8个工作线程，队列容量128 */
    threadpool_t *pool = threadpool_create(8, 128);

    /* 创建监听 socket */
    int listenfd = open_listenfd(port);  /* CSAPP 辅助函数 */

    printf("Proxy server with thread pool listening on port %d\n", port);

    while (1) {
        /* 接受新连接 */
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int connfd = accept(listenfd,
                           (struct sockaddr *)&client_addr, &client_len);

        if (connfd < 0) {
            perror("accept");
            continue;
        }

        /* 关键：必须用 malloc 分配，因为多线程不能共享栈变量 */
        int *arg = (int *)malloc(sizeof(int));
        *arg = connfd;

        /* 将任务提交给线程池 */
        if (threadpool_add_task(pool, handle_request, arg) != 0) {
            fprintf(stderr, "Failed to add task, closing connection\n");
            close(connfd);
            free(arg);
        }
    }

    /* 关闭线程池（实际不会执行到这里，服务器是死循环） */
    threadpool_destroy(pool);
    close(listenfd);
    return 0;
}

/*
 * 与 pthread_create 方式的对比：
 *
 * pthread_create 方式（Proxy Lab 默认）：
 * - 每个连接创建一个新线程
 * - 优点：简单
 * - 缺点：高并发时线程创建/销毁开销大；线程数量不可控可能耗尽资源
 *
 * 线程池方式：
 * - 预创建固定数量的线程，复用执行多个任务
 * - 优点：避免创建/销毁开销；控制最大并发数
 * - 缺点：实现复杂；如果任务执行时间长，队列可能积压
 */
```

---

## 附录D：Proxy Lab完整实现思路

### D.1 顺序代理的 doit 函数框架

顺序代理（Sequential Proxy）一次只处理一个客户端请求，处理完才能接受下一个。

```c
/*
 * 顺序代理核心函数 doit
 * 处理一个完整的 HTTP 代理请求
 */
void doit(int connfd) {
    char buf[MAXLINE], method[MAXLINE], uri[MAXLINE], version[MAXLINE];
    char hostname[MAXLINE], pathname[MAXLINE];
    int port;
    rio_t rio_client;   /* 客户端连接的 RIO 缓冲 */

    /* 1. 读取客户端的 HTTP 请求行 */
    Rio_readinitb(&rio_client, connfd);
    if (!Rio_readlineb(&rio_client, buf, MAXLINE)) {
        return;     /* 客户端什么都没发就关闭了 */
    }
    printf("Request line: %s", buf);

    /* 2. 解析请求行：GET http://www.cmu.edu:8080/hub/index.html HTTP/1.0 */
    sscanf(buf, "%s %s %s", method, uri, version);

    /* 只支持 GET 方法 */
    if (strcasecmp(method, "GET") != 0) {
        clienterror(connfd, method, "501", "Not Implemented",
                   "Proxy does not implement this method");
        return;
    }

    /* 3. 解析 URI，提取 hostname, port, pathname */
    parse_uri(uri, hostname, pathname, &port);

    /* 4. 读取并转发 HTTP 请求头部 */
    /* 先读取剩余的请求头 */
    char request_buf[MAXLINE];
    int request_len = 0;
    request_len += snprintf(request_buf + request_len,
                           MAXLINE - request_len,
                           "GET /%s HTTP/1.0\r\n", pathname);
    /* Host 头 */
    request_len += snprintf(request_buf + request_len,
                           MAXLINE - request_len,
                           "Host: %s\r\n", hostname);
    /* 其他头... */
    while (Rio_readlineb(&rio_client, buf, MAXLINE) > 0) {
        if (strcmp(buf, "\r\n") == 0) break;  /* 空行表示头部结束 */
        /* 可以选择性转发或修改头部 */
        request_len += snprintf(request_buf + request_len,
                               MAXLINE - request_len, "%s", buf);
    }
    request_len += snprintf(request_buf + request_len,
                           MAXLINE - request_len, "\r\n");

    /* 5. 连接目标服务器 */
    int serverfd = open_clientfd(hostname, port);
    if (serverfd < 0) {
        clienterror(connfd, hostname, "502", "Bad Gateway",
                   "Proxy couldn't connect to the server");
        return;
    }

    /* 6. 向目标服务器发送请求 */
    Rio_writen(serverfd, request_buf, request_len);

    /* 7. 接收服务器响应并转发给客户端 */
    rio_t rio_server;
    Rio_readinitb(&rio_server, serverfd);
    size_t n;
    while ((n = Rio_readnb(&rio_server, buf, MAXLINE)) > 0) {
        Rio_writen(connfd, buf, n);
    }

    /* 8. 关闭连接 */
    close(serverfd);
    close(connfd);
}
```

### D.2 多线程代理

使用 pthread_create 为每个客户端连接创建一个独立线程，实现并发处理。

```c
/*
 * 多线程代理核心
 * 每个连接分配一个线程，使用 pthread_detach 分离线程
 */

/* 线程参数结构体 - 必须用 malloc 分配 */
typedef struct {
    int connfd;
    /* 可以添加缓存指针等其他参数 */
} thread_arg_t;

/* 线程入口函数 */
void *thread_main(void *arg) {
    thread_arg_t *targ = (thread_arg_t *)arg;
    int connfd = targ->connfd;

    /* 分离自己：结束时自动回收资源，不需要 join */
    pthread_detach(pthread_self());

    /* 释放参数结构体 */
    free(targ);

    /* 调用 doit 处理请求 */
    doit(connfd);

    /* 关闭连接 */
    close(connfd);

    return NULL;
}

int main(int argc, char *argv[]) {
    int port = atoi(argv[1]);
    int listenfd = open_listenfd(port);
    struct sockaddr_in client_addr;
    socklen_t client_len;

    /* 忽略 SIGPIPE（写已关闭的 socket 会触发） */
    Signal(SIGPIPE, SIG_IGN);

    while (1) {
        client_len = sizeof(client_addr);
        int connfd = accept(listenfd,
                           (struct sockaddr *)&client_addr, &client_len);

        if (connfd < 0) continue;

        /* 关键：用 malloc 分配参数，避免竞争条件 */
        thread_arg_t *targ = (thread_arg_t *)malloc(sizeof(thread_arg_t));
        targ->connfd = connfd;

        /* 创建线程 */
        pthread_t tid;
        if (pthread_create(&tid, NULL, thread_main, targ) != 0) {
            perror("pthread_create");
            close(connfd);
            free(targ);
        }
        /* 不需要 join，线程会自动分离 */
    }
}

/*
 * 常见陷阱：
 * 1. 直接传 &connfd 给线程 → 竞争条件！
 *    主线程可能在子线程读取前修改 connfd
 *    解决：用 malloc 分配独立的空间
 *
 * 2. 忘记 pthread_detach → 线程结束后资源不回收，内存泄漏
 *
 * 3. 忘略 SIGPIPE → 写已关闭的连接会导致进程崩溃
 *    解决：Signal(SIGPIPE, SIG_IGN)
 *
 * 4. 线程中忘记 close(connfd) → 文件描述符泄漏
 *    每个线程必须负责关闭自己处理的 connfd
 */
```

### D.3 缓存实现（读者写者锁 + LRU链表）

```c
/*
 * cache.c - Proxy Lab 缓存实现
 * 使用读者写者锁保护缓存访问
 * 使用 LRU（Least Recently Used）策略淘汰旧缓存
 */

#include "cache.h"
#include <string.h>
#include <pthread.h>

#define MAX_CACHE_SIZE  1049000   /* 最大缓存大小：约 1MB */
#define MAX_OBJECT_SIZE 102400    /* 单个缓存对象最大：100KB */

/* 缓存对象节点 */
typedef struct cache_node {
    char *uri;                      /* URI 作为 key */
    char *object;                   /* 响应内容 */
    size_t size;                    /* 对象大小 */
    struct cache_node *prev;        /* LRU 双向链表前驱 */
    struct cache_node *next;        /* LRU 双向链表后继 */
} cache_node_t;

/* 缓存结构体 */
typedef struct cache {
    cache_node_t *head;             /* LRU 链表头（最近使用） */
    cache_node_t *tail;             /* LRU 链表尾（最久未使用） */
    size_t total_size;              /* 当前缓存总大小 */
    rwlock_t lock;                  /* 读者写者锁 */
} cache_t;

/* 全局缓存实例 */
static cache_t cache;

/*
 * 初始化缓存
 */
void cache_init() {
    cache.head = NULL;
    cache.tail = NULL;
    cache.total_size = 0;
    rwlock_init(&cache.lock);
}

/*
 * 从缓存中查找对象
 * 找到后将其移到链表头部（标记为最近使用）
 * @return: 0=找到，-1=未找到
 */
int cache_get(char *uri, char *object_buf, size_t *size) {
    /* 获取读锁 */
    rwlock_read_lock(&cache.lock);

    cache_node_t *node = cache.head;
    while (node != NULL) {
        if (strcmp(node->uri, uri) == 0) {
            /* 找到缓存项，复制数据 */
            *size = node->size;
            memcpy(object_buf, node->object, node->size);
            break;
        }
        node = node->next;
    }

    rwlock_read_unlock(&cache.lock);

    if (node == NULL) {
        return -1;  /* 未找到 */
    }

    /* 获取写锁来更新 LRU 顺序 */
    rwlock_write_lock(&cache.lock);
    /* 再次查找（可能已被其他线程修改） */
    cache_node_t *n = cache.head;
    while (n != NULL) {
        if (strcmp(n->uri, uri) == 0) {
            /* 移到链表头部 */
            cache_move_to_head(&cache, n);
            break;
        }
        n = n->next;
    }
    rwlock_write_unlock(&cache.lock);

    return 0;
}

/*
 * 向缓存添加对象
 * 如果缓存已满，从尾部（最久未使用）开始淘汰
 */
void cache_put(char *uri, char *object, size_t size) {
    /* 如果对象太大，不缓存 */
    if (size > MAX_OBJECT_SIZE) {
        return;
    }

    rwlock_write_lock(&cache.lock);

    /* 淘汰直到有足够空间 */
    while (cache.total_size + size > MAX_CACHE_SIZE) {
        cache_evict_tail(&cache);
    }

    /* 创建新节点 */
    cache_node_t *node = (cache_node_t *)malloc(sizeof(cache_node_t));
    node->uri = strdup(uri);
    node->object = (char *)malloc(size);
    memcpy(node->object, object, size);
    node->size = size;

    /* 插入链表头部 */
    node->prev = NULL;
    node->next = cache.head;
    if (cache.head != NULL) {
        cache.head->prev = node;
    }
    cache.head = node;
    if (cache.tail == NULL) {
        cache.tail = node;
    }

    cache.total_size += size;

    rwlock_write_unlock(&cache.lock);
}

/*
 * 将节点移到链表头部（标记为最近使用）
 */
void cache_move_to_head(cache_t *c, cache_node_t *node) {
    if (node == c->head) return;  /* 已经在头部 */

    /* 从当前位置摘除 */
    if (node->prev) node->prev->next = node->next;
    if (node->next) node->next->prev = node->prev;
    if (node == c->tail) c->tail = node->prev;

    /* 插入头部 */
    node->prev = NULL;
    node->next = c->head;
    if (c->head) c->head->prev = node;
    c->head = node;
    if (c->tail == NULL) c->tail = node;
}

/*
 * 淘汰链表尾部（最久未使用）的节点
 */
void cache_evict_tail(cache_t *c) {
    if (c->tail == NULL) return;

    cache_node_t *victim = c->tail;

    /* 从链表摘除 */
    c->tail = victim->prev;
    if (c->tail) {
        c->tail->next = NULL;
    } else {
        c->head = NULL;  /* 链表空了 */
    }

    /* 释放内存 */
    c->total_size -= victim->size;
    free(victim->uri);
    free(victim->object);
    free(victim);
}
```

### D.4 读者写者锁完整实现

```c
/*
 * rwlock.h - 读者写者锁实现
 * 使用两个信号量和一个互斥锁实现
 * 读者优先策略
 */

#ifndef RWLOCK_H
#define RWLOCK_H

#include <semaphore.h>

typedef struct {
    sem_t mutex_readcount;   /* 保护 readcount 的互斥信号量 */
    sem_t mutex_writecount;  /* 保护 writecount 的互斥信号量 */
    sem_t resource;          /* 保护共享资源的信号量（写者用） */
    sem_t read_try;          /* 防止读者饿死写者的信号量 */
    int readcount;           /* 当前活跃读者数量 */
    int writecount;          /* 当前等待/活跃写者数量 */
} rwlock_t;

void rwlock_init(rwlock_t *rw);
void rwlock_read_lock(rwlock_t *rw);
void rwlock_read_unlock(rwlock_t *rw);
void rwlock_write_lock(rwlock_t *rw);
void rwlock_write_unlock(rwlock_t *rw);

#endif
```

```c
/*
 * rwlock.c - 读者写者锁实现
 * 读者优先策略：只要有读者在读，新读者可以直接进入
 */

#include "rwlock.h"

/*
 * 初始化读者写者锁
 */
void rwlock_init(rwlock_t *rw) {
    sem_init(&rw->mutex_readcount, 0, 1);
    sem_init(&rw->mutex_writecount, 0, 1);
    sem_init(&rw->resource, 0, 1);       /* 初始资源可用 */
    sem_init(&rw->read_try, 0, 1);       /* 初始允许读 */
    rw->readcount = 0;
    rw->writecount = 0;
}

/*
 * 获取读锁
 * 第一个读者获取写锁（resource），最后一个读者释放
 * 后续读者可以直接进入
 */
void rwlock_read_lock(rwlock_t *rw) {
    /* 请求进入读模式（防止写者饥饿） */
    sem_wait(&rw->read_try);

    /* 更新读者计数 */
    sem_wait(&rw->mutex_readcount);
    rw->readcount++;
    if (rw->readcount == 1) {
        /* 第一个读者：锁定资源，阻止写者 */
        sem_wait(&rw->resource);
    }
    sem_post(&rw->mutex_readcount);

    /* 释放 read_try，允许其他读者进入 */
    sem_post(&rw->read_try);
}

/*
 * 释放读锁
 */
void rwlock_read_unlock(rwlock_t *rw) {
    sem_wait(&rw->mutex_readcount);
    rw->readcount--;
    if (rw->readcount == 0) {
        /* 最后一个读者：释放资源，允许写者 */
        sem_post(&rw->resource);
    }
    sem_post(&rw->mutex_readcount);
}

/*
 * 获取写锁
 */
void rwlock_write_lock(rwlock_t *rw) {
    /* 更新写者计数 */
    sem_wait(&rw->mutex_writecount);
    rw->writecount++;
    if (rw->writecount == 1) {
        /* 第一个写者：锁定 read_try，阻止新读者 */
        sem_wait(&rw->read_try);
    }
    sem_post(&rw->mutex_writecount);

    /* 获取资源锁（与其他写者和第一个读者互斥） */
    sem_wait(&rw->resource);
}

/*
 * 释放写锁
 */
void rwlock_write_unlock(rwlock_t *rw) {
    /* 释放资源锁 */
    sem_post(&rw->resource);

    /* 更新写者计数 */
    sem_wait(&rw->mutex_writecount);
    rw->writecount--;
    if (rw->writecount == 0) {
        /* 最后一个写者：释放 read_try，允许读者 */
        sem_post(&rw->read_try);
    }
    sem_post(&rw->mutex_writecount);
}

/*
 * 读者写者锁的使用场景：
 *
 * 1. 缓存系统（如 Proxy Lab）：
 *    - 查询缓存 = 读操作（多个线程可以同时读）
 *    - 更新缓存 = 写操作（独占访问）
 *    - 读远多于写，用读者优先策略
 *
 * 2. 数据库系统：
 *    - 查询 = 读
 *    - 更新/插入/删除 = 写
 *
 * 3. 配置管理：
 *    - 读取配置 = 读
 *    - 修改配置 = 写
 *
 * 注意：读者优先策略可能导致写者饥饿
 *      如果需要公平性，可以使用写者优先或公平策略
 */
```

---

## 附录E：RIO包完整实现

### E.1 RIO包概述

RIO（Robust I/O）是 CSAPP 提供的健壮 I/O 包，自动处理以下问题：
- **不足值（Short count）**：read/write 返回的字节数少于请求值
- **信号中断**：read/write 被信号中断返回 -1，errno=EINTR

RIO 包分为两类：
1. **无缓冲函数**：rio_readn / rio_writen — 直接对 fd 读写
2. **带缓冲函数**：rio_readnb / rio_readlineb — 通过内部缓冲区减少系统调用

### E.2 rio_t 结构体

```c
/*
 * RIO 缓冲区结构体
 */
#define RIO_BUFSIZE 8192    /* 内部缓冲区大小：8KB */

typedef struct {
    int rio_fd;             /* 关联的文件描述符 */
    int rio_cnt;            /* 缓冲区中未读的字节数 */
    char *rio_bufptr;       /* 指向缓冲区中下一个未读字节 */
    char rio_buf[RIO_BUFSIZE]; /* 内部缓冲区 */
} rio_t;

/*
 * 结构体布局示意图：
 *
 *  +--------------------------------------------------+
 *  | rio_t                                            |
 *  |                                                  |
 *  |  rio_fd = 3 (文件描述符)                          |
 *  |  rio_cnt = 5 (缓冲区中还有5字节未读)               |
 *  |  rio_bufptr ---+                                 |
 *  |                |                                 |
 *  |  rio_buf[8192]:|                                 |
 *  |  [已读][已读][已读][未读][未读][未读][未读][未读][空]...|
 *  |                ^                                 |
 *  |                rio_bufptr 指向这里                  |
 *  +--------------------------------------------------+
 *
 *  当 rio_cnt == 0 时，缓冲区已读完
 *  下次读取会从 fd 重新填充缓冲区
 */
```

### E.3 rio_readinitb — 初始化

```c
/*
 * rio_readinitb - 初始化 RIO 缓冲区
 * @rp:    RIO 结构体指针
 * @fd:    文件描述符
 *
 * 将 fd 关联到 RIO 结构体，并初始化缓冲区为空
 */
void rio_readinitb(rio_t *rp, int fd) {
    rp->rio_fd = fd;
    rp->rio_cnt = 0;
    rp->rio_bufptr = rp->rio_buf;
}
```

### E.4 rio_read — 核心内部缓冲读函数

```c
/*
 * rio_read - RIO 包的核心内部函数
 *
 * 这是带缓冲读取的基础。工作原理：
 * 1. 如果缓冲区有未读数据（rio_cnt > 0），直接从缓冲区复制
 * 2. 如果缓冲区为空（rio_cnt == 0），从 fd 读取数据填充缓冲区
 * 3. 自动处理 EINTR（信号中断）
 *
 * 注意：这个函数是 static 的，外部不直接调用
 *       rio_readnb 和 rio_readlineb 都基于它实现
 *
 * @rp:     RIO 结构体
 * @usrbuf: 用户缓冲区（目标）
 * @n:      最多读取的字节数
 * @return: 实际读取的字节数；0 表示EOF；-1 表示出错
 */
static ssize_t rio_read(rio_t *rp, char *usrbuf, size_t n) {
    int cnt;

    /*
     * 如果内部缓冲区为空，从 fd 重新填充
     */
    while (rp->rio_cnt <= 0) {
        /* 从文件描述符读取数据到内部缓冲区 */
        rp->rio_cnt = read(rp->rio_fd, rp->rio_buf,
                          sizeof(rp->rio_buf));

        if (rp->rio_cnt < 0) {
            /* 出错 */
            if (errno != EINTR) {
                /* 非信号中断的错误，返回 -1 */
                return -1;
            }
            /* EINTR: 被信号中断，重试 */
        } else if (rp->rio_cnt == 0) {
            /* EOF：对端关闭连接（网络）或文件结束 */
            return 0;
        } else {
            /* 成功读取，重置缓冲区指针到开头 */
            rp->rio_bufptr = rp->rio_buf;
        }
    }

    /*
     * 从内部缓冲区复制数据到用户缓冲区
     * 最多复制 min(n, rio_cnt) 个字节
     */
    cnt = n;
    if (rp->rio_cnt < (int)n) {
        cnt = rp->rio_cnt;  /* 缓冲区数据不够，只复制有的 */
    }
    memcpy(usrbuf, rp->rio_bufptr, cnt);
    rp->rio_bufptr += cnt;   /* 移动缓冲区指针 */
    rp->rio_cnt -= cnt;      /* 减少未读计数 */

    return cnt;
}

/*
 * rio_read 工作流程图：
 *
 *  用户调用 rio_read(rp, buf, 100)
 *
 *  缓冲区有数据 (rio_cnt >= 100)?
 *     ├── 是 → 从缓冲区复制100字节到 buf，返回100
 *     │       rio_cnt -= 100, bufptr += 100
 *     │
 *     └── 否 → 缓冲区有多少复制多少
 *              然后下次调用时缓冲区已空，从 fd 重新读取
 *
 *  缓冲区为空 (rio_cnt == 0)?
 *     └── 从 fd 读取最多 8192 字节到 rio_buf
 *         ├── 成功 → rio_cnt = 读取字节数, bufptr = rio_buf
 *         ├── EINTR → 重试
 *         ├── 返回 0 → EOF, 返回 0
 *         └── 其他错误 → 返回 -1
 */
```

### E.5 rio_readnb — 带缓冲的批量读

```c
/*
 * rio_readnb - 从 RIO 缓冲区读取 n 个字节
 * 带缓冲版本，自动处理不足值
 *
 * @rp:     RIO 结构体
 * @usrbuf: 用户缓冲区
 * @n:      要读取的字节数
 * @return: 实际读取的字节数；0=EOF; -1=出错
 *
 * 与 rio_readn 的区别：使用内部缓冲区，减少 read 系统调用次数
 */
ssize_t rio_readnb(rio_t *rp, void *usrbuf, size_t n) {
    char *bufp = usrbuf;
    size_t nleft = n;
    ssize_t nread;

    /* 循环读取，直到读完 n 个字节或遇到 EOF/错误 */
    while (nleft > 0) {
        if ((nread = rio_read(rp, bufp, nleft)) < 0) {
            /* 出错 */
            if (errno == EINTR) {
                nread = 0;  /* 信号中断，继续 */
            } else {
                return -1;  /* 其他错误 */
            }
        } else if (nread == 0) {
            break;  /* EOF */
        }

        nleft -= nread;
        bufp += nread;
    }

    return (n - nleft);  /* 返回实际读取的字节数 */
}
```

### E.6 rio_readlineb — 带缓冲的逐行读

```c
/*
 * rio_readlineb - 从 RIO 缓冲区读取一行
 * 读取直到遇到 '\n' 或读取了 maxlen-1 个字节
 *
 * @rp:     RIO 结构体
 * @usrbuf: 用户缓冲区
 * @maxlen: 缓冲区最大长度（包括 '\0'）
 * @return: 读取的行长度（不包括 '\0'）; 0=EOF; -1=出错
 *
 * 典型用途：读取 HTTP 请求/响应头部（每行以 \r\n 结尾）
 */
ssize_t rio_readlineb(rio_t *rp, void *usrbuf, size_t maxlen) {
    int n;
    char c;
    char *bufp = usrbuf;

    /* 逐字节读取，直到遇到换行符或达到最大长度 */
    for (n = 1; n < maxlen; n++) {
        int rc;
        if ((rc = rio_read(rp, &c, 1)) == 1) {
            *bufp++ = c;
            if (c == '\n') {
                n++;        /* 包含换行符 */
                break;
            }
        } else if (rc == 0) {
            if (n == 1) {
                return 0;   /* EOF，没有读到任何数据 */
            } else {
                break;       /* EOF，但已经读了一些数据 */
            }
        } else {
            return -1;       /* 出错 */
        }
    }

    *bufp = 0;   /* 以 null 结尾 */
    return (n - 1);
}
```

### E.7 rio_readn — 无缓冲批量读

```c
/*
 * rio_readn - 无缓冲读取 n 个字节
 * 直接调用 read，自动处理不足值和信号中断
 *
 * @fd:     文件描述符
 * @usrbuf: 用户缓冲区
 * @n:      要读取的字节数
 * @return: 实际读取的字节数；0=EOF; -1=出错
 *
 * 注意：rio_readn 和 rio_writen 不能与带缓冲函数混用
 *       一旦对某个 fd 使用了 rio_readinitb，就只能用带缓冲函数
 */
ssize_t rio_readn(int fd, void *usrbuf, size_t n) {
    char *bufp = usrbuf;
    size_t nleft = n;
    ssize_t nread;

    while (nleft > 0) {
        if ((nread = read(fd, bufp, nleft)) < 0) {
            if (errno == EINTR) {
                nread = 0;      /* 信号中断，重试 */
            } else {
                return -1;       /* 其他错误 */
            }
        } else if (nread == 0) {
            break;                /* EOF */
        }

        nleft -= nread;
        bufp += nread;
    }

    return (n - nleft);  /* 返回实际读取的字节数 */
}
```

### E.8 rio_writen — 无缓冲批量写

```c
/*
 * rio_writen - 无缓冲写入 n 个字节
 * 直接调用 write，自动处理不足值和信号中断
 *
 * @fd:     文件描述符
 * @usrbuf: 用户缓冲区（数据源）
 * @n:      要写入的字节数
 * @return: 实际写入的字节数（成功时=n）; -1=出错
 *
 * 注意：rio_writen 没有带缓冲版本
 *       写操作不需要缓冲，因为写入的数据不需要重复读取
 */
ssize_t rio_writen(int fd, void *usrbuf, size_t n) {
    char *bufp = usrbuf;
    size_t nleft = n;
    ssize_t nwritten;

    while (nleft > 0) {
        if ((nwritten = write(fd, bufp, nleft)) <= 0) {
            if (errno == EINTR) {
                nwritten = 0;      /* 信号中断，重试 */
            } else {
                return -1;          /* 其他错误 */
            }
        }

        nleft -= nwritten;
        bufp += nwritten;
    }

    return n;  /* 成功写入 n 个字节 */
}
```

### E.9 RIO函数使用总结

```
┌─────────────────┬──────────────────┬─────────────────────────────────────┐
│ 函数             │ 是否带缓冲        │ 典型用途                             │
├─────────────────┼──────────────────┼─────────────────────────────────────┤
│ rio_readn       │ 否               │ 读取已知长度的二进制数据             │
│ rio_writen      │ 否               │ 写入数据（没有带缓冲版本）            │
│ rio_readinitb   │ N/A（初始化）     │ 初始化 rio_t 结构体                  │
│ rio_readnb      │ 是               │ 读取已知长度的数据（减少系统调用）    │
│ rio_readlineb   │ 是               │ 逐行读取文本（如 HTTP 头部）         │
└─────────────────┴──────────────────┴─────────────────────────────────────┘

  使用模式：

  /* 模式1：无缓冲读写（简单场景） */
  rio_readn(fd, buf, n);
  rio_writen(fd, buf, n);

  /* 模式2：带缓冲读 + 无缓冲写（Proxy Lab 常用） */
  rio_t rio;
  rio_readinitb(&rio, fd);
  rio_readlineb(&rio, line, MAXLINE);   /* 逐行读 */
  rio_readnb(&rio, buf, n);             /* 批量读 */
  rio_writen(fd, buf, n);               /* 写仍用无缓冲 */

  ⚠️ 重要：不要对同一个 fd 混用带缓冲和无缓冲读函数！
     一旦用 rio_readinitb 初始化后，就只能用 rio_readnb/rio_readlineb
```

---

## 附录F：10个练习题

### 练习题1：文件描述符引用计数

**题目**：在一个进程中，`open()` 返回 fd=3，然后执行 `fork()`。此时子进程是否也能通过 fd=3 访问该文件？两个进程各自 `close(fd)` 后，文件才真正被关闭吗？为什么？

<details>
<summary>查看答案</summary>

是的，子进程也能通过 fd=3 访问该文件。`fork()` 会复制父进程的文件描述符表，子进程获得相同的 fd 编号。

文件描述符表中的每个条目指向内核中的打开文件描述（open file description），该描述维护一个引用计数。`fork()` 会使引用计数加 1。只有当引用计数降为 0 时（即父进程和子进程都 `close(fd)`），内核才会真正关闭文件，释放相关资源。

因此，两个进程各自 `close(fd)` 后，文件才会被真正关闭。

</details>

### 练习题2：dup2重定向

**题目**：以下代码的输出是什么？解释 dup2 的工作原理。

```c
int fd = open("output.txt", O_WRONLY | O_CREAT, 0644);
dup2(fd, STDOUT_FILENO);
printf("Hello, World!\n");
close(fd);
```

<details>
<summary>查看答案</summary>

"Hello, World!" 会被写入 output.txt 文件，而不是终端。

`dup2(fd, STDOUT_FILENO)` 的工作原理：
1. 关闭 STDOUT_FILENO（fd=1）的当前指向（通常是终端）
2. 将 fd=1 指向与 fd 相同的打开文件描述
3. 此后所有写到 stdout 的数据（如 printf）都会写入 output.txt

注意：printf 有缓冲区，如果在 `close(fd)` 之前没有 `fflush(stdout)`，数据可能还在缓冲区中。在实际使用中应该先 `fflush(stdout)` 或在 close 之前确保缓冲区已刷新。

</details>

### 练习题3：HTTP请求解析

**题目**：给定以下 HTTP 请求，写出解析 URI 并提取 hostname、port、path 的伪代码。

```
GET http://www.example.com:8080/path/to/page?query=1 HTTP/1.0
Host: www.example.com:8080
User-Agent: Mozilla/5.0
```

<details>
<summary>查看答案</summary>

```c
/*
 * 解析代理请求的 URI
 * 输入: "http://www.example.com:8080/path/to/page?query=1"
 * 输出: hostname="www.example.com", port=8080, path="/path/to/page?query=1"
 */
void parse_uri(char *uri, char *hostname, char *path, int *port) {
    *port = 80;  /* 默认端口 */

    /* 跳过 "http://" 前缀 */
    char *host_start = strstr(uri, "://");
    if (host_start != NULL) {
        host_start += 3;  /* 跳过 "://" */
    } else {
        host_start = uri;
    }

    /* 查找主机名结束位置（第一个 / 或 : 或字符串结尾） */
    char *port_start = strchr(host_start, ':');
    char *path_start = strchr(host_start, '/');

    if (port_start != NULL) {
        /* 有端口号 */
        int host_len = port_start - host_start;
        strncpy(hostname, host_start, host_len);
        hostname[host_len] = '\0';
        *port = atoi(port_start + 1);
    } else if (path_start != NULL) {
        /* 无端口号，有路径 */
        int host_len = path_start - host_start;
        strncpy(hostname, host_start, host_len);
        hostname[host_len] = '\0';
    } else {
        /* 只有主机名 */
        strcpy(hostname, host_start);
    }

    /* 提取路径 */
    if (path_start != NULL) {
        strcpy(path, path_start);
    } else {
        strcpy(path, "/");  /* 默认根路径 */
    }
}
```

</details>

### 练习题4：select vs epoll

**题目**：解释 select 和 epoll 的核心区别。为什么在高并发场景下 epoll 性能远优于 select？

<details>
<summary>查看答案</summary>

核心区别：

1. **数据结构**：
   - select 使用位图（bitmap）记录关注的 fd，最大限制 FD_SETSIZE（通常1024）
   - epoll 使用红黑树存储关注的 fd，没有数量限制

2. **时间复杂度**：
   - select：O(n)，每次返回后需要遍历所有关注的 fd 检查是否就绪
   - epoll：O(1) 就绪事件返回，O(就绪fd数量) 处理，不需要遍历所有 fd

3. **fd 传递方式**：
   - select：每次调用都需要将所有 fd 从用户空间拷贝到内核空间
   - epoll：通过 epoll_ctl 一次性注册，epoll_wait 只拷贝就绪的 fd

4. **就绪通知方式**：
   - select：水平触发，需要遍历全部 fd 检查状态
   - epoll：支持水平触发和边缘触发，只返回就绪的 fd

5. **fd 数量限制**：
   - select：FD_SETSIZE 限制（通常1024）
   - epoll：无硬性限制（取决于系统资源）

高并发下 epoll 优势的原因：
- 10000 个连接中只有 100 个活跃 → select 遍历 10000 个，epoll 只处理 100 个
- select 每次拷贝 10000 个 fd，epoll 只拷贝 100 个就绪的
- epoll 用红黑树管理 fd，增删改 O(log n)；select 用数组每次重新设置

</details>

### 练习题5：信号量实现互斥

**题目**：用 POSIX 信号量实现一个简单的互斥锁（mutex），给出 init、lock、unlock 的代码。

<details>
<summary>查看答案</summary>

```c
#include <semaphore.h>

typedef struct {
    sem_t sem;
} my_mutex_t;

/* 初始化：信号量初始值为1（二元信号量=互斥锁） */
void my_mutex_init(my_mutex_t *m) {
    sem_init(&m->sem, 0, 1);  /* value=1，未锁定状态 */
}

/* 加锁：P操作（wait），将信号量减1 */
void my_mutex_lock(my_mutex_t *m) {
    sem_wait(&m->sem);  /* 如果值为0，阻塞等待 */
}

/* 解锁：V操作（post），将信号量加1 */
void my_mutex_unlock(my_mutex_t *m) {
    sem_post(&m->sem);  /* 唤醒一个等待的线程 */
}

/*
 * 互斥锁本质上是初始值为1的二元信号量：
 * - lock: P(1→0)，如果已经是0则阻塞
 * - unlock: V(0→1)，唤醒等待者
 *
 * 信号量比互斥锁更通用：
 * - 互斥锁只能保护一个资源（值=1）
 * - 信号量可以控制多个资源（值=N，如生产者-消费者）
 */
```

</details>

### 练习题6：死锁分析

**题目**：以下代码会产生死锁吗？如果会，说明原因并给出修复方案。

```c
/* 线程1 */
void transfer_A_to_B() {
    pthread_mutex_lock(&mutex_A);
    pthread_mutex_lock(&mutex_B);
    /* 转账操作 */
    pthread_mutex_unlock(&mutex_B);
    pthread_mutex_unlock(&mutex_A);
}

/* 线程2 */
void transfer_B_to_A() {
    pthread_mutex_lock(&mutex_B);
    pthread_mutex_lock(&mutex_A);
    /* 转账操作 */
    pthread_mutex_unlock(&mutex_A);
    pthread_mutex_unlock(&mutex_B);
}
```

<details>
<summary>查看答案</summary>

**会产生死锁**。

原因：线程1先锁 A 再锁 B，线程2先锁 B 再锁 A。如果两个线程同时执行，可能出现：
- 线程1持有 A，等待 B
- 线程2持有 B，等待 A
- 两者互相等待，形成死锁

这满足死锁的四个必要条件：
1. 互斥：锁一次只能被一个线程持有
2. 持有并等待：持有 A 的同时请求 B
3. 不可剥夺：不能强制夺取锁
4. 循环等待：线程1等线程2的资源，线程2等线程1的资源

**修复方案1：锁排序（推荐）**

所有线程都按相同顺序获取锁（如总是先 A 后 B）：

```c
void transfer_B_to_A_fixed() {
    pthread_mutex_lock(&mutex_A);  /* 先锁 A */
    pthread_mutex_lock(&mutex_B);  /* 再锁 B */
    /* 转账操作 */
    pthread_mutex_unlock(&mutex_B);
    pthread_mutex_unlock(&mutex_A);
}
```

**修复方案2：使用 trylock + 超时**

```c
void transfer_B_to_A_trylock() {
    pthread_mutex_lock(&mutex_B);
    while (pthread_mutex_trylock(&mutex_A) != 0) {
        pthread_mutex_unlock(&mutex_B);
        usleep(1000);  /* 等待一会儿重试 */
        pthread_mutex_lock(&mutex_B);
    }
    /* 转账操作 */
    pthread_mutex_unlock(&mutex_A);
    pthread_mutex_unlock(&mutex_B);
}
```

</details>

### 练习题7：信号处理与并发安全

**题目**：在代理服务器中，为什么要使用 `Signal(SIGPIPE, SIG_IGN)` 忽略 SIGPIPE 信号？如果不忽略会发生什么？

<details>
<summary>查看答案</summary>

**原因**：当代理服务器向一个已被客户端关闭的 socket 写入数据时，内核会向进程发送 SIGPIPE 信号。默认情况下，SIGPIPE 的处理动作是终止进程。

如果不忽略 SIGPIPE：
- 客户端在服务器写响应之前关闭了连接
- 服务器 `write()` 到已关闭的 socket
- 内核发送 SIGPIPE
- **整个代理服务器进程被杀死**，所有正在处理的连接都会中断

忽略 SIGPIPE 后：
- `write()` 到已关闭的 socket 会返回 -1，errno 设为 EPIPE
- 代理服务器可以正常处理这个错误（关闭该连接），不影响其他连接

在 CSAPP 中，使用 `Signal()`（包裹函数）而不是 `signal()`，因为 `Signal()` 的语义更可移植，能保证信号处理函数不会被重复调用打断。

</details>

### 练习题8：线程安全 vs 可重入

**题目**：以下函数是线程安全的吗？是可重入的吗？如何修改使其既线程安全又可重入？

```c
char *asctime_local(const struct tm *tm) {
    static char buf[26];
    strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", tm);
    return buf;
}
```

<details>
<summary>查看答案</summary>

**不是线程安全的**，也不是可重入的。

原因：使用 `static char buf[26]`，多个线程同时调用会互相覆盖 buf 的内容。

**修改为线程安全（但不可重入）**：

```c
char *asctime_local_r(const struct tm *tm, char *buf) {
    strftime(buf, 26, "%Y-%m-%d %H:%M:%S", tm);
    return buf;
}
/* 调用者提供缓冲区，线程安全 */
```

**修改为既线程安全又可重入**：

```c
/* 可重入版本：不使用任何静态/全局数据，不调用非可重入函数 */
int asctime_local_reentrant(const struct tm *tm, char *buf, size_t bufsize) {
    if (buf == NULL || bufsize < 20) return -1;
    /* strftime 本身是可重入的（使用调用者提供的缓冲区） */
    strftime(buf, bufsize, "%Y-%m-%d %H:%M:%S", tm);
    return 0;
}
```

区别：
- **线程安全**：多线程同时调用结果正确（可能用锁实现）
- **可重入**：函数可以在执行过程中被中断（如信号处理函数中），再次调用不会影响第一次调用的结果
- 可重入一定是线程安全的，线程安全不一定是可重入的

</details>

### 练习题9：代理缓存策略

**题目**：在 Proxy Lab 中，缓存使用 LRU 淘汰策略。请解释 LRU 的原理，并分析在多线程环境下实现 LRU 需要注意什么？

<details>
<summary>查看答案</summary>

**LRU（Least Recently Used）原理**：
- 每次访问缓存项时，将其移到链表头部（标记为最近使用）
- 当缓存满时，淘汰链表尾部的项（最久未使用）
- 使用双向链表 + 哈希表可实现 O(1) 的访问和更新

**多线程环境下的注意事项**：

1. **读操作的 LRU 更新**：
   - 读取缓存时需要更新 LRU 顺序（将节点移到头部）
   - 这涉及链表修改，需要写锁而非读锁
   - 如果用纯读锁，多个读者同时移动节点会导致链表损坏
   - 解决方案：先读锁读取数据，再写锁更新 LRU 顺序

2. **锁粒度**：
   - 粗粒度：一把锁保护整个缓存 → 简单但并发度低
   - 细粒度：每个缓存项一把锁 → 高并发但复杂
   - Proxy Lab 推荐使用读者写者锁，读多写少场景效果好

3. **ABA 问题**：
   - 线程A读取缓存项，释放读锁
   - 线程B获取写锁，淘汰该缓存项
   - 线程A再获取写锁更新 LRU，但节点已被释放
   - 解决：在写锁中重新验证节点是否存在

4. **缓存击穿**：
   - 大量请求同时未命中缓存，都去请求后端
   - 解决：使用"单飞"模式（singleflight），只允许一个请求去后端获取

</details>

### 练习题10：不足值处理

**题目**：为什么网络编程中 `read()` 和 `write()` 会返回不足值（返回值小于请求值）？RIO 包是如何处理的？

<details>
<summary>查看答案</summary>

**不足值产生的原因**：

1. **read 不足值**：
   - 网络缓冲区中数据尚未全部到达，只有部分字节可用
   - 网络延迟导致数据分多个 TCP 段到达
   - 读取管道/FIFO 时，写入端尚未写完全部数据
   - 读取磁盘文件时一般不会出现（磁盘 I/O 通常返回请求的完整量）

2. **write 不足值**：
   - 内核发送缓冲区已满，只能写入部分数据
   - 网络拥塞导致发送速率降低
   - 对端接收窗口太小

**RIO 包的处理方式**：

```c
/* rio_readn / rio_writen 核心逻辑：循环直到完成 */
ssize_t rio_readn(int fd, void *buf, size_t n) {
    size_t nleft = n;
    while (nleft > 0) {
        ssize_t nread = read(fd, bufp, nleft);
        if (nread < 0) {
            if (errno == EINTR) nread = 0;  /* 信号中断，重试 */
            else return -1;                  /* 真正的错误 */
        } else if (nread == 0) break;        /* EOF */

        nleft -= nread;
        bufp += nread;
    }
    return n - nleft;  /* 返回实际读到的字节数 */
}
```

RIO 包通过 `while` 循环自动处理不足值：
- 每次调用 `read`/`write` 只处理部分数据
- 循环直到所有请求的数据都处理完，或遇到 EOF/错误
- 同时处理 EINTR（信号中断），自动重试

这就是为什么在 Proxy Lab 中必须使用 RIO 包而不是直接使用 `read`/`write` — 如果不用循环处理不足值，HTTP 请求/响应可能只被部分读取或写入，导致数据不完整。

</details>

---

> 以上附录内容补充了 CSAPP 第10-12章的进阶知识，为 Proxy Lab 的实现提供了完整的理论基础和代码参考。

---

# 📖 补充说明与学习指引

> 本章不改动上文任何内容，仅针对上文在讲解节奏上的"跳步"、隐含的前置知识以及信息密度过高的段落做集中补充。阅读顺序建议：先看"一、前置知识要求"确认自己是否具备阅读基础，再在读到对应章节遇到卡壳时回到"二、跳步内容补充"查漏补缺，最后用"三、阶段性自检清单"检验掌握程度。

## 一、前置知识要求

本笔记从 10.1 开头就默认读者具备一批 C 语言与操作系统基础。若下面任何一项不熟悉，建议先补齐再读正文，否则很多"一句话带过"的地方会读不懂。

1. **C 语言指针与内存模型**
   - 指针算术（`bufp += nread` 这类操作贯穿整个 RIO 包）。
   - `void *` 泛型指针与强制类型转换（`int connfd = *((int *)vargp)`）。
   - 栈 / 堆 / 数据段（`.data`）/ 代码段（`.text`）的区别——12.5 节"共享变量分析"直接用"数据段""线程栈"来判断变量是否共享，如果不清楚这几个段的含义就无法理解结论。
   - `malloc`/`free` 的语义，以及"栈上局部变量在函数返回后失效"这一点（这是 12.4 节为什么必须 malloc connfd 的根本原因）。

2. **进程与系统调用基础（对应 CSAPP 第 8 章）**
   - 什么是进程、什么是内核态 / 用户态、什么是系统调用。
   - `fork()` 的语义：复制地址空间、父子进程从 fork 返回处继续执行、返回值区分父子。
   - `execve()`、`wait()`/`waitpid()`、`exit()` 的作用。
   - 信号（signal）的基本概念：`SIGCHLD`、`SIGPIPE`、信号处理函数、`signal()`。
   - 上面这些在第 12 章"基于进程的并发"里被大量直接使用，本笔记并未重新解释。

3. **`errno` 错误处理机制**
   - `errno` 是什么、为什么它是线程局部的、`EINTR`/`EAGAIN`/`EWOULDBLOCK`/`EPIPE` 各代表什么。
   - RIO 的每个函数都在判断 `errno == EINTR`，若不理解 errno 就只能死记代码。

4. **计算机组成中的字节序与整数表示（对应 CSAPP 第 2 章）**
   - 大端 / 小端、有符号 / 无符号整数、`unsigned long` 与 `long` 的宽度差异（10.4 节 `size_t` vs `ssize_t` 直接引用）。

5. **基础网络常识**
   - IP、端口、TCP/UDP 的最基本概念（知道"TCP 是可靠字节流"即可）。
   - 客户端 / 服务器的直观印象。

6. **命令行与编译**
   - 会用 `gcc` 编译、会读 man 手册中的函数原型格式。

> 一句话总结：**本笔记是 CSAPP 第 10-12 章的读书笔记，隐含地要求你已经学过第 2、6、8 章（数据表示、存储器层次、异常控制流）。** 尤其是第 8 章的进程与信号，是第 12 章的直接地基。

## 二、"跳步"内容补充

下面逐条列出正文中"直接用了但没先解释"的地方，按正文出现顺序排列。

### 2.1 关于"内核""系统调用"本身

- **原文在 10.1 就说**"应用程序请求内核打开文件""内核维护文件位置 k"，**但没有解释**什么是内核、应用程序如何"请求"内核（即系统调用机制）。
  - 补充：`open`/`read`/`write`/`close` 都是**系统调用**，即用户程序通过一条特殊指令（如 x86 的 `syscall`）陷入内核态，由内核代为完成操作后再返回用户态。系统调用比普通函数调用昂贵得多（涉及特权级切换），这正是 10.6 节引入缓冲区（一次系统调用搬 8192 字节）的性能动机。

### 2.2 `errno` 与 `EINTR`（10.6 RIO 包）

- **原文在 rio_readn 里直接写** `if (errno == EINTR) nread = 0;`，**但没有解释** errno 是什么、EINTR 为什么会出现。
  - 补充：`errno` 是一个（线程局部的）全局错误码变量，系统调用失败返回 -1 时会设置它。`EINTR` 表示"系统调用在完成前被一个信号打断了"。这不是真正的错误，正确做法是重新发起调用。所以 RIO 把 `nread` 置 0 让 `while` 循环再跑一次。**理解这一点需要第 8 章的信号知识。**

### 2.3 `memcpy` 与"用户缓冲区/内部缓冲区"（10.6.2 带缓冲输入）

- **原文说** rio_read"从缓冲区复制 min(n, rio_cnt) 个字节到用户缓冲区"，**但没有强调** "用户缓冲区 usrbuf"和"RIO 内部缓冲区 rio_buf"是两块不同的内存。
  - 补充：数据流向是 `内核 --read()--> rio_buf（内部）--memcpy--> usrbuf（用户）`。带缓冲的意义在于：昂贵的 `read()` 系统调用只在 rio_buf 空时发生一次（搬 8KB），之后 `rio_readlineb` 逐字节取数据都是廉价的内存拷贝。这就是"为什么 rio_readlineb 效率不低"那段引用块背后的真正机制。

### 2.4 `mmap` / `munmap`（11.8 serve_static）

- **原文在 serve_static 中直接调用** `mmap(0, filesize, PROT_READ, MAP_PRIVATE, srcfd, 0)`，**但全篇没有解释** mmap 是什么。
  - 补充：`mmap` 把一个文件"映射"到进程的虚拟地址空间，之后可以像访问内存一样访问文件内容（`srcp` 指向文件数据），无需显式 `read` 到缓冲区。这属于 CSAPP 第 9 章（虚拟内存）的内容。`munmap` 解除映射。注意代码里 `close(srcfd)` 在 mmap 之后立即执行是安全的——映射建立后不再依赖该 fd。

### 2.5 `fork` 后的"写时复制"与描述符复制（12.2 基于进程的并发）

- **原文说** "子进程复制父进程的描述符表"、fork 后引用计数变成 2，**但没有解释** fork 到底复制了什么、为什么描述符表是复制而文件表项是共享。
  - 补充：`fork` 让子进程获得父进程地址空间的一份**逻辑副本**（现代内核用写时复制/COW 实现）。**描述符表**属于进程私有数据，会被复制，所以父子各有一份 fd→文件表项的指针；但这些指针指向的**打开文件表项是全局共享的**，因此引用计数 +1，且父子共享同一个文件位置。这正好呼应 10.7 节的三级结构图——建议把 10.7 和 12.2 对照着看。

### 2.6 `waitpid` 与 `WNOHANG`、僵尸进程（12.2）

- **原文的 sigchld_handler 直接写** `while (waitpid(-1, 0, WNOHANG) > 0);`，**但没有解释** 为什么要回收子进程、`WNOHANG` 是什么、为什么用 `while` 循环。
  - 补充：子进程终止后若父进程不"回收"（wait），它会变成**僵尸进程**占用内核资源。`SIGCHLD` 在子进程终止时被发送。`WNOHANG` 表示"没有已终止的子进程时立即返回而不阻塞"。用 `while` 是因为**信号不排队**——多个子进程几乎同时终止可能只触发一次 `SIGCHLD`，必须循环把所有僵尸一次回收干净。这整段依赖第 8 章的异常控制流知识。

### 2.7 `select` 会修改传入的 fd_set（12.3）

- **原文注释写** "select 会修改集合，每次须重新设置" `ready_set = read_set;`，**但没有解释** 为什么。
  - 补充：`select` 返回时会把 fd_set 原地改写成"只保留就绪的 fd"。所以必须保存一份"关注集合"（read_set），每次循环拷贝出一份"工作集合"（ready_set）传给 select。这是 select 的一个著名易错点。

### 2.8 阻塞 vs 非阻塞 I/O、`O_NONBLOCK`、EAGAIN（12.3 ET 模式、附录 B）

- **原文在讲 ET 模式时说** "必须使用非阻塞 I/O""循环 read 直到 EAGAIN"，**但没有先解释** 什么是阻塞/非阻塞、`O_NONBLOCK` 怎么设置、EAGAIN 何时出现。
  - 补充：默认的 socket 是**阻塞**的——没数据时 `read` 会一直等。**非阻塞**模式（用 `fcntl` 设置 `O_NONBLOCK`，见附录 B 的 `set_nonblocking`）下，没数据时 `read` 立即返回 -1 且 `errno==EAGAIN/EWOULDBLOCK`。ET 只在"新数据到达"时通知一次，所以必须一直读到 EAGAIN 才能保证缓冲区被读空，否则剩余数据永远不会再被通知（附录 B.3 的"惊群/数据丢失"说的就是这个）。**要先懂阻塞语义，才能理解 ET 为什么强制非阻塞。**

### 2.9 `pthread_detach` 与线程资源回收（12.4）

- **原文在线程例程里直接调用** `pthread_detach(pthread_self())`，**但没有充分解释** 不 detach 会怎样。
  - 补充：线程默认是**joinable** 的——终止后其资源（栈、TID 等）要等别的线程 `pthread_join` 才释放，否则泄漏（类似进程的僵尸）。服务器的主线程忙于 accept，不可能去 join 每个工作线程，因此让线程**自己 detach**，终止时由系统自动回收。这与 12.2 节进程模型里"父进程 waitpid 回收子进程"是同一类问题的两种解法，建议对比记忆。

### 2.10 `pthread_cond_t` 条件变量与"为什么用 while 而不是 if"（附录 C 线程池）

- **原文正文（12.6-12.8）只讲了信号量**，到了**附录 C 的线程池却突然改用** `pthread_cond_wait` / `pthread_cond_signal` / `pthread_cond_broadcast`，**中间没有过渡解释** 条件变量是什么、它和信号量的区别、为什么 `while (pool->queue_size == 0 ...)` 要用 while。
  - 补充：**条件变量**是与互斥锁配合使用的同步原语，用于"等待某个条件成立"。`pthread_cond_wait(&cond, &lock)` 会**原子地**释放锁并睡眠，被唤醒时重新拿回锁。
  - 用 `while` 而不是 `if` 的原因有二：① **虚假唤醒**（spurious wakeup），线程可能在没有 signal 的情况下被唤醒；② **唤醒后条件可能又被别的线程改变**。所以醒来后必须重新检查条件。
  - 与信号量对比：信号量自带计数，`sem_wait` 会阻塞直到计数 > 0；条件变量本身不计数，必须搭配一个"共享状态 + 互斥锁"来判断。生产者-消费者既可以用三信号量（正文 12.7）实现，也可以用"互斥锁 + 两个条件变量"（附录 C）实现——**这两种写法解决的是同一个问题，正文没有点明它们的等价性，容易让人误以为是两套无关的知识。**

### 2.11 读者-写者锁的两个版本差异（12.8 vs 附录 D.4）

- **原文 12.8 给的读者优先版本用了 2 个信号量（mutex + w）**，而**附录 D.4 的 rwlock 却用了 4 个信号量（含 read_try、mutex_writecount）**，**没有解释** 为什么复杂化。
  - 补充：12.8 的简版是**纯读者优先**，缺点是"只要有读者源源不断进来，写者会饥饿"。附录 D.4 引入 `read_try`（写者到来时先占住它以阻止新读者排队）和写者计数，是为了**缓解写者饥饿**、更接近公平。两者是"入门版"和"改进版"的关系，读的时候不要被信号量数量吓到。

### 2.12 `SIGPIPE` / `SIG_IGN`（附录 D.2、练习题 7）

- **原文在多线程代理里直接写** `Signal(SIGPIPE, SIG_IGN)`，虽然练习题 7 有解答，**但正文出现处没有就地解释**。
  - 补充：向"对端已关闭的连接"写数据，内核会发 `SIGPIPE`，其默认行为是**杀死整个进程**。`SIG_IGN` 表示忽略该信号，于是 `write` 改为返回 -1 且 `errno==EPIPE`，程序可以优雅处理而不是整个崩掉。代理服务器几乎必配这一行。

### 2.13 CSAPP 的"包裹函数"约定（大写首字母，如 `Rio_readlineb`、`Signal`）

- **原文正文用小写** `rio_readlineb`，**附录 D 却改用大写** `Rio_readlineb`、`Rio_writen`、`Signal`，**没有说明** 二者关系。
  - 补充：这是 CSAPP 全书的约定——**首字母大写的版本是"包裹函数"（wrapper）**，内部调用同名小写函数并帮你做错误检查（出错就打印信息并 `exit`）。功能等价，大写版只是省去手写错误处理。看到大小写不要以为是两个不同函数。

### 2.14 `getnameinfo` / `sockaddr_storage`（11.8 主循环）

- **原文主循环用了** `struct sockaddr_storage clientaddr` 和 `getnameinfo(...)`，**但没解释** 为什么不用 `sockaddr_in`、getnameinfo 是干嘛的。
  - 补充：`sockaddr_storage` 是一个"足够大且对齐"的通用地址结构，能容纳 IPv4 或 IPv6 地址，用它写出的代码与协议版本无关（呼应 11.6 getaddrinfo 的"协议无关"设计哲学）。`getnameinfo` 是 `getaddrinfo` 的逆操作，把二进制 socket 地址转回主机名/端口字符串，这里仅用于打印日志。

### 2.15 `snprintf` 拼接请求与缓冲区溢出风险（附录 D.1）

- **原文 doit 用** `request_len += snprintf(request_buf + request_len, MAXLINE - request_len, ...)` 拼 HTTP 请求，**没有点明** 这个"边写边算剩余空间"模式的意义。
  - 补充：`snprintf` 返回"本应写入的字符数"，配合 `MAXLINE - request_len` 作为剩余容量，可以安全地在同一缓冲区里追加多段而不溢出。这是 C 里拼装文本协议的常用安全写法，值得记住。

## 三、阶段性自检清单

把正文按逻辑切成 6 个段落，每段给出若干自检题。**能不看笔记答出来，才算真掌握。**

### 段落 A：Unix I/O 基础（10.1 - 10.4）
1. 文件描述符 0/1/2 分别是什么？`open` 返回的 fd 有什么规律？
2. `read` 返回 0、返回 -1、返回小于 n 的正数，各代表什么？
3. `size_t` 和 `ssize_t` 为什么要区分？为什么 read 的返回类型必须是有符号的？

### 段落 B：不足值与 RIO 包（10.5 - 10.6）
4. 列举至少 3 种会产生"不足值"的场景。为什么磁盘文件很少出现，网络 socket 却经常出现？
5. `rio_readn` 中 `if (errno == EINTR) nread = 0;` 这一行的作用是什么？去掉会怎样？
6. 为什么 `rio_writen` "永远返回 n"，而 `rio_readn` 可能返回小于 n？
7. 带缓冲的 `rio_readlineb` 逐字节取数据，为什么效率并不低？系统调用真正发生在什么时候？

### 段落 C：文件共享与重定向（10.7 - 10.9）
8. 画出"描述符表 / 打开文件表 / v-node 表"三级结构，并说明哪一层是进程私有、哪些是全局共享。
9. `fork` 后父子进程读写同一个 fd 会互相影响文件位置吗？为什么？
10. `dup2(fd, 1)` 之后，`printf` 的输出去了哪里？为什么之后可以 `close(fd)`？
11. 为什么网络 socket 不能像磁盘文件那样用标准 I/O（fgets/fputs 交替）？

### 段落 D：网络编程与套接字（11.1 - 11.8）
12. 网络字节序是大端还是小端？`htons`/`ntohl` 各在什么时候用？
13. 服务器端 `socket→bind→listen→accept` 四步各自的作用是什么？客户端只需要哪几步？
14. `listenfd` 和 `connfd` 有什么区别？为什么要分成两个描述符？
15. `getaddrinfo` 相比老式 `gethostbyname` 好在哪里？为什么要遍历返回的链表逐个尝试？
16. 一个完整 HTTP 请求/响应报文由哪几部分组成？"头部结束"用什么标志？
17. Tiny 服务器区分静态/动态内容的依据是什么？CGI 程序的输出是怎么送回客户端的？

### 段落 E：并发三模型（12.1 - 12.4）
18. 构建并发服务器的三种方式各是什么？各自的优缺点？
19. 基于进程的并发里，为什么父进程必须 `close(connfd)`、子进程必须 `close(listenfd)`？
20. select、poll、epoll 三者在数据结构和时间复杂度上有何本质区别？
21. 水平触发（LT）和边缘触发（ET）的区别？为什么 ET 必须搭配非阻塞 I/O 且循环读到 EAGAIN？
22. 基于线程的并发里，为什么传给 `pthread_create` 的 connfd 必须 `malloc` 而不能直接传 `&connfd`？
23. 线程和进程在"共享什么、独有什么"上有何区别？

### 段落 F：同步与线程安全（12.5 - 12.11 及附录 C/D）
24. 全局变量、局部自动变量、局部静态变量在多线程下哪些共享、哪些不共享？
25. 信号量的 P/V 操作分别做什么？为什么必须是原子的？
26. 生产者-消费者为什么需要 mutex + slots + items 三个信号量？为什么"先 P(slots) 再 P(mutex)"顺序不能反？
27. 条件变量 `pthread_cond_wait` 为什么要放在 `while` 循环里而不是 `if`？（附录 C）
28. 死锁的四个必要条件是什么？"锁排序"是通过打破哪个条件来避免死锁的？
29. 线程安全和可重入是什么关系？四类线程不安全函数分别怎么修复？
30. 读者优先的读者-写者锁为什么可能让写者饥饿？附录 D.4 用什么手段缓解？

## 四、推荐学习路径

**如果你是初学者（没学过 CSAPP 第 8 章）：**
1. 先补第 8 章的进程、`fork`/`exec`/`wait`、信号，再回来读第 12 章，否则会处处卡壳。
2. 顺序读 10.1 → 10.6，重点吃透"不足值"和 RIO 包（这是后面所有网络代码的地基）。
3. 读 10.7 三级结构图，务必和 12.2 的 fork 引用计数对照理解。
4. 读第 11 章，动手把 11.6 的 `open_clientfd`/`open_listenfd` 抄一遍、编译跑通。
5. 读第 12 章"三种并发模型"，先只看"基于进程"和"基于线程"，暂时跳过 I/O 多路复用。
6. 掌握信号量 → 互斥锁 → 生产者消费者 → 读者写者，循序渐进。
7. 最后回头攻 I/O 多路复用（select/epoll）和附录 B（epoll 实战）。

**如果你目标是做 Proxy Lab：**
1. Part I（顺序代理）：只需第 10 章 RIO + 第 11 章 socket/HTTP，配合附录 D.1 的 doit 框架。
2. Part II（并发代理）：加上 12.4 基于线程的并发 + 附录 D.2，注意 malloc connfd、detach、SIG_IGN 三个坑。
3. Part III（缓存代理）：加上 12.8 读者-写者 + 附录 D.3/D.4 的 LRU 缓存与 rwlock。
4. 全程用大写包裹函数（`Rio_*`、`Signal`）省去错误处理。

**如果你只想复习/应付面试：**
1. 直接看"核心概念速查表"和本章"三、自检清单"。
2. 重点背：不足值、listenfd vs connfd、select/epoll 区别、LT/ET、竞争条件、死锁四条件、生产者消费者、读者写者。
3. 附录 A（TCP 三次握手/四次挥手/滑动窗口/拥塞控制）是网络面试高频，单独强化。

## 五、常见困惑与解答

**Q1：为什么 read 读到的字节数会比我要求的少？是不是我程序写错了？**
A：不是错误。见 10.5 节，这叫"不足值"，在网络 socket 上是常态（数据分多个 TCP 段陆续到达）。正确做法是用循环反复读，这正是 RIO 包 `rio_readn` 做的事。

**Q2：rio_readn / rio_writen 和 rio_readnb / rio_readlineb 到底该用哪个？能混用吗？**
A：无缓冲的 `rio_readn`/`rio_writen` 直接操作 fd；带缓冲的 `rio_readnb`/`rio_readlineb` 走内部缓冲区。**同一个 fd 一旦用 `rio_readinitb` 初始化并开始用带缓冲读函数，就不能再对它用 `rio_readn`**，否则数据会被内部缓冲区"截胡"导致丢失。写永远用 `rio_writen`（写没有带缓冲版本）。见附录 E.9。

**Q3：为什么服务器要区分 listenfd 和 connfd？一个不行吗？**
A：listenfd 是"接线员"，一直守在门口接新连接；每来一个客户端，accept 就生成一个专属的 connfd 去服务它。分开后 listenfd 可以持续接客，多个 connfd 可以并发服务、各自独立关闭，互不影响。见 11.5 节末尾。

**Q4：为什么多线程时 connfd 一定要 malloc？直接传地址不行吗？**
A：直接传 `&connfd`，主循环下一次 accept 会覆盖同一个栈变量，子线程还没来得及读就被改了，两个线程可能拿到同一个 fd（竞争条件）。malloc 给每个线程一份独立副本，由该线程负责 free。见 12.4 节和练习题的对比代码。

**Q5：select 每次循环为什么要重新 `ready_set = read_set`？**
A：因为 select 返回时会原地修改传入的 fd_set，只留下就绪的 fd。必须保留一份"关注集合"，每次拷贝出"工作集合"给它改。见 2.7 补充。

**Q6：ET 模式下我 read 了一次就返回，为什么后面的数据收不到了？**
A：ET 只在"状态发生变化（新数据到达）"时通知一次。你没把缓冲区读空，剩余数据不会再触发通知。必须在非阻塞 fd 上用 `while` 循环读到 `EAGAIN` 为止。见 12.3 节和附录 B.3。

**Q7：信号量和互斥锁、条件变量有什么关系？为什么正文用信号量，附录却用条件变量？**
A：互斥锁就是初始值为 1 的二元信号量（练习题 5）。条件变量是另一套原语，用来"等待某个条件"，必须配合互斥锁使用。生产者-消费者用两种都能实现——正文 12.7 用三信号量，附录 C 用"互斥锁 + 两个条件变量"，本质等价。见 2.10 补充。

**Q8：读者-写者锁正文用 2 个信号量，附录却用 4 个，哪个对？**
A：都对，是入门版与改进版的关系。2 信号量版是纯读者优先、可能饿死写者；4 信号量版引入 read_try 缓解写者饥饿。见 2.11 补充。

**Q9：为什么代理服务器几乎都要写 `Signal(SIGPIPE, SIG_IGN)`？**
A：向已被对端关闭的连接写数据会收到 SIGPIPE，默认行为是杀死整个进程。忽略它后，write 改为返回 -1 且 errno=EPIPE，程序能优雅地只关掉这一个连接而不整体崩溃。见 2.12 补充和练习题 7。

**Q10：笔记里一会儿 `rio_readlineb` 一会儿 `Rio_readlineb`，是两个函数吗？**
A：不是。首字母大写的是 CSAPP 的"包裹函数"，内部调用小写版并自动做错误检查，功能一样。见 2.13 补充。

**Q11：进程并发和线程并发，回收"僵尸"的方式为什么不一样？**
A：本质是同一类问题。进程：子进程终止后父进程要 `waitpid` 回收，否则成僵尸进程；线程：线程终止后要被 `pthread_join`，否则资源泄漏，所以服务器让线程 `pthread_detach` 自己回收。见 2.6 与 2.9 补充，建议对照记忆。

**Q12：mmap 是什么？为什么 serve_static 不用 read 把文件读到缓冲区再发？**
A：mmap 把文件映射进虚拟地址空间，可以像访问内存一样访问文件内容，省去一次"读到用户缓冲区"的拷贝，适合发送大文件。属于第 9 章虚拟内存的内容。见 2.4 补充。

> 学习建议：遇到看不懂的代码行，先回到本章"二、跳步内容补充"对号入座；如果补充里也依赖你没学过的章节（如第 8 章信号、第 9 章虚拟内存），说明这就是你真正该先去补的前置知识。
> 建议结合 CS:APP 官方教材和 lab 手册一起阅读。

---

# 第七大块：前置知识逐层深挖与自检答案

> 前文已经给出第 10～12 章主体、15 个跳步补充、Proxy Lab、`epoll`、线程池与 TCP 专题。
> 本块进一步把最容易成为理解瓶颈的前置知识展开，并给出前文 30 道自检题的参考答案。
> 每节固定使用“概念 → 原理 → 代码示例 → 误区 → 练习”的顺序。

## 7.1 C 语言指针与进程内存模型

### 7.1.1 概念

指针是保存地址的有类型变量。

`int *p` 不只是“一个地址”，其类型还告诉编译器：

1. 解引用 `*p` 时读取 `sizeof(int)` 个字节；
2. `p + 1` 应跨过一个 `int`；
3. 编译器应按 `int` 的对齐和别名规则解释目标对象。

典型进程虚拟地址空间可以抽象为：

```text
高地址
+------------------------------+
| 内核虚拟地址区               | 用户态不可直接访问
+------------------------------+
| 用户栈 stack                 | 局部自动变量、返回地址；向低地址增长（常见实现）
+------------------------------+
| mmap 映射区                  | 共享库、文件映射、匿名映射
+------------------------------+
| 堆 heap                      | malloc 管理；通常向高地址增长
+------------------------------+
| .bss                         | 未显式初始化或零初始化的全局/静态变量
+------------------------------+
| .data                        | 已初始化的可写全局/静态变量
+------------------------------+
| .rodata                      | 字符串字面量、只读常量
+------------------------------+
| .text                        | 机器指令，通常只读且可执行
+------------------------------+
低地址
```

这是一种逻辑布局，不保证所有平台地址方向完全相同。

线程共享 `.text`、全局数据、堆和进程描述符表，但每个线程有自己的栈、寄存器上下文和线程局部存储。

### 7.1.2 原理：指针算术为何按元素移动

设 `p` 的类型是 `T *`，则抽象语义为：

```text
p + k 对应地址 = address(p) + k * sizeof(T)
```

所以 RIO 将泛型缓冲区转成 `char *`：

```c
char *bufp = usrbuf;
bufp += nread;
```

C 标准保证 `sizeof(char) == 1`。

因此 `char *` 每次按字节移动，正好适合处理任意二进制数据。

两个指针只有在指向同一数组对象或尾后位置时，做减法和有序比较才有良好定义。

指针越过数组尾后再解引用属于 undefined behavior。

### 7.1.3 原理：`void *` 泛型指针

`void *` 能保存任意对象指针，是 C 接口实现泛型的常用方式。

`malloc` 返回 `void *`，`pthread_create` 的参数和返回值也使用 `void *`。

标准 C 中，`void *` 与对象指针可隐式互转；C++ 通常要求显式转换。

标准 C 不定义 `void *` 的指针算术，因为 `void` 没有对象大小。

GCC 把 `void * + 1` 当扩展支持，但可移植代码应先转为 `char *`。

### 7.1.4 代码示例：线程参数所有权

```c
typedef struct {
    int connfd;
    unsigned long request_id;
} worker_arg_t;

void *worker(void *opaque)
{
    worker_arg_t *arg = opaque;
    int connfd = arg->connfd;
    unsigned long id = arg->request_id;

    free(arg);                 /* 工作线程取得所有权后释放参数 */
    handle_request(connfd, id);
    close(connfd);
    return NULL;
}

int dispatch(int connfd, unsigned long id)
{
    pthread_t tid;
    worker_arg_t *arg = malloc(sizeof(*arg));
    if (arg == NULL)
        return -1;

    arg->connfd = connfd;
    arg->request_id = id;

    int rc = pthread_create(&tid, NULL, worker, arg);
    if (rc != 0) {
        free(arg);             /* 创建失败，所有权仍在调用者 */
        return -1;
    }
    pthread_detach(tid);
    return 0;
}
```

这个模式同时解决生命周期、并发覆盖和资源所有权三个问题。

### 7.1.5 常见误区

- 误区：指针就是无符号整数。纠正：地址可表示为整数，但转换可能损失信息，且指针还受类型与对象边界规则约束。
- 误区：`sizeof(p)` 是缓冲区大小。纠正：它只是指针本身大小，64 位进程里通常为 8。
- 误区：`malloc` 的内存会自动清零。纠正：`malloc` 内容未指定；需要清零时用 `calloc` 或 `memset`。
- 误区：函数返回后可以继续使用局部数组地址。纠正：局部自动对象生命周期已经结束。
- 误区：字符串字面量可修改。纠正：写字符串字面量是 undefined behavior，应使用字符数组。
- 误区：多个线程各有一份全局变量。纠正：同一进程的线程共享全局数据。

### 7.1.6 练习

1. 解释 `int a[8]; int *p = a; p += 3;` 后地址移动了多少字节。
2. 解释为什么 `bufp += nread` 中 `bufp` 应是 `char *`。
3. 画出全局缓存、线程局部变量、工作线程参数分别位于哪个内存区域。
4. 修改上例，让主线程通过 `pthread_join` 获取工作线程处理的字节数。

## 7.2 进程、系统调用与信号基础

### 7.2.1 概念

进程是正在执行的程序实例，拥有虚拟地址空间、描述符表和执行上下文。

用户态代码不能直接执行设备访问、页表修改等特权操作。

需要内核服务时，程序通过 system call 进入内核态。

普通函数调用仍在当前特权级执行；系统调用会跨越用户态与内核态边界。

### 7.2.2 system call 的完整进入与返回路径

以 Linux x86-64 的 `read(fd, buf, count)` 为例，可以建立如下心智模型：

```text
用户代码
  → libc read wrapper
  → 按 ABI 放置 syscall number 与参数
  → 执行 syscall 指令
  → CPU 保存用户返回位置并切换特权级
  → 进入内核统一入口
  → 保存寄存器、建立内核执行上下文
  → 检查系统调用号
  → 查询系统调用表 syscall table
  → 分派到 sys_read/ksys_read 一类处理函数
  → 校验 fd、权限和用户地址
  → 经 VFS 与具体文件/socket 实现完成读取
  → 将结果或负错误码放入返回寄存器
  → 内核出口恢复上下文
  → 返回用户态 wrapper
  → wrapper 把负错误转换成 -1 并设置 errno
  → 调用者继续执行
```

系统调用表本质上是“系统调用号 → 内核处理函数”的受控分派结构。

用户程序不能把任意函数地址交给内核执行，只能请求 ABI 定义的系统调用号。

`syscall` 指令本身只负责受控转移，不会自动完成 read 的业务逻辑。

内核还必须防御恶意或错误参数。

用户指针不能像内核指针一样直接信任，内核通常借助 `copy_from_user`、`copy_to_user` 一类机制安全访问。

访问无效页时应向调用者返回 `EFAULT`，而不是让整个内核崩溃。

系统调用开销主要来自：

1. 特权级切换和入口/出口固定成本；
2. 寄存器与执行上下文保存恢复；
3. 参数检查与安全边界验证；
4. Spectre/Meltdown 缓解措施可能带来的额外成本；
5. cache 与 TLB 局部性扰动；
6. 真正 I/O 可能引起的睡眠、调度、设备等待和数据复制。

一次普通函数调用通常远比一次系统调用便宜。

但不要把所有 I/O 性能问题都归因于 `syscall` 指令本身。

磁盘、网络 RTT、锁竞争和 page fault 往往占据更大成本。

缓冲 I/O 的价值是批量搬运、摊薄边界切换成本，并减少小调用数量。

#### 代码示例：观察系统调用而非绕过 libc

```c
char buf[4096];
ssize_t n;

do {
    n = read(fd, buf, sizeof(buf));
} while (n < 0 && errno == EINTR);
```

可以通过 `strace`（Linux）或 `dtruss`（macOS）观察用户 API 最终触发哪些系统调用。

直接使用 `syscall(SYS_read, ...)` 通常没有必要，会降低可移植性，并绕开 libc 的兼容处理。

#### 误区

- 误区：调用 libc 函数一定进入内核。纠正：`strlen` 等纯用户函数不会；部分调用可由 vDSO 优化。
- 误区：进入内核等于切换到另一个进程。纠正：通常仍是同一线程，只是执行在内核态；阻塞后才可能发生调度。
- 误区：内核可以直接信任用户传来的地址。纠正：用户地址必须校验并安全复制。
- 误区：减少 syscall 次数永远提高性能。纠正：批量过大也会增加延迟和内存占用，需要按工作负载权衡。

#### 练习

1. 用跟踪工具比较逐字节 read 与 8 KiB 缓冲 read 的系统调用次数。
2. 解释系统调用号与普通 C 函数地址为何不是一回事。
3. 说明阻塞 read 从进入内核到线程睡眠、再到数据就绪唤醒的大致过程。

### 7.2.3 `fork` 原理与语义

`fork()` 创建一个子进程。

成功时它“返回两次”：

- 在父进程返回子进程 PID；
- 在子进程返回 0；
- 失败时只在父进程返回 -1。

父子从 `fork` 后的下一条语句继续。

地址空间逻辑上复制，物理页通常先以 COW 共享。

任一方写共享页时触发缺页异常，内核才复制该页。

父子描述符表分别存在，但对应项指向同一打开文件表项。

所以它们共享 file offset 和文件状态标志。

### 7.2.3 `execve` 原理与语义

`execve(path, argv, envp)` 不创建新进程。

它用新程序替换当前进程的用户地址空间、代码与栈。

成功后不会返回到旧程序。

PID 通常不变。

未设置 `FD_CLOEXEC` 的描述符会跨 `execve` 保留。

这就是 shell 能先重定向描述符，再执行目标程序的基础。

### 7.2.4 `wait` 与 `waitpid`

子进程退出后，内核仍保留 PID、退出状态和资源统计，等待父进程回收。

这段只剩退出信息的进程叫 zombie。

`waitpid(-1, &status, 0)` 阻塞等待任意子进程。

`waitpid(pid, &status, 0)` 等待指定子进程。

加入 `WNOHANG` 后，没有可回收子进程时立即返回 0。

### 7.2.5 信号的最小正确模型

信号是内核通知进程发生异步事件的一种机制。

信号可处于产生、pending、递送、处理等阶段。

传统同类标准信号通常不排队：pending 集合里更像一个 bit，而不是计数器。

信号处理程序会打断主控制流。

处理程序中只能安全调用 async-signal-safe 函数。

`printf`、`malloc`、大多数 pthread API 都不应在处理程序中调用。

### 7.2.6 代码示例：回收所有已终止子进程

```c
static void sigchld_handler(int sig)
{
    int saved_errno = errno;

    while (waitpid(-1, NULL, WNOHANG) > 0)
        ;

    errno = saved_errno;
}
```

保存并恢复 `errno`，避免异步处理程序污染被打断代码看到的错误码。

循环是因为一次 SIGCHLD 可能对应多个可回收子进程。

### 7.2.7 常见误区

- 误区：`fork` 后父进程一定先运行。纠正：调度顺序未指定。
- 误区：`execve` 是“在当前程序旁边再启动一个程序”。纠正：它替换当前程序映像。
- 误区：子进程退出后所有信息立刻消失。纠正：父进程回收前可能成为 zombie。
- 误区：收到三次同类信号就一定执行三次处理程序。纠正：传统信号可能合并。
- 误区：信号处理程序里什么函数都能调用。纠正：只能依赖 async-signal-safe 集合。

### 7.2.8 练习

1. 写出 `fork` 三种返回情况。
2. 说明 shell 实现 `cmd > out.txt` 时 `open`、`dup2`、`execve` 的顺序。
3. 解释为什么 SIGCHLD 处理程序需要循环 `waitpid`。
4. 思考如果父进程从不 wait，长期服务器会发生什么。

## 7.3 `errno` 与可恢复错误

### 7.3.1 概念

很多 POSIX API 用返回值报告成功或失败，并在失败时设置 `errno`。

现代实现通常把 `errno` 定义为访问 thread-local storage 的宏。

因此不同线程可以同时拥有不同错误码。

只有函数已经通过返回值表示失败时，才应读取 `errno`。

成功调用通常不承诺把旧 `errno` 清零。

### 7.3.2 四个高频错误码

`EINTR`：阻塞系统调用在完成前被已捕获信号打断。

`EAGAIN`：资源暂时不可用；非阻塞 I/O 常用它表示“现在没数据/空间”。

`EWOULDBLOCK`：操作若继续就会阻塞；在 Linux 上通常与 `EAGAIN` 值相同，但可移植代码应同时判断。

`EPIPE`：向已无读端的 pipe 或已关闭连接写数据；通常还会产生 SIGPIPE。

### 7.3.3 正确重试模式

```c
ssize_t retry_read(int fd, void *buf, size_t n)
{
    ssize_t rc;

    do {
        rc = read(fd, buf, n);
    } while (rc < 0 && errno == EINTR);

    return rc;
}
```

不是所有失败都应盲目重试。

`EBADF`、`EFAULT`、`EINVAL` 常表示程序错误或参数错误。

对非阻塞 fd，`EAGAIN` 应返回事件循环等待下一次 readiness，而不是忙等重试。

### 7.3.4 安全写入模式

```c
ssize_t write_all(int fd, const void *buf, size_t n)
{
    const char *p = buf;
    size_t left = n;

    while (left > 0) {
        ssize_t rc = write(fd, p, left);
        if (rc > 0) {
            p += rc;
            left -= (size_t)rc;
            continue;
        }
        if (rc < 0 && errno == EINTR)
            continue;
        return -1;
    }
    return (ssize_t)n;
}
```

若用于可能超过 `SSIZE_MAX` 的超大数据，还应分块处理并检查类型转换。

### 7.3.5 常见误区

- 误区：每次调用前都必须 `errno = 0`。纠正：大多数 API 依据失败返回值判断即可。
- 误区：`EINTR` 总能无条件重试。纠正：要考虑调用是否已经产生部分副作用。
- 误区：`EAGAIN` 是永久故障。纠正：它明确表示暂时不可用。
- 误区：忽略 SIGPIPE 后写就会成功。纠正：写仍失败，只是进程不会被默认终止。
- 误区：可以保存 `char *p = strerror(errno)` 长期跨线程使用。纠正：可移植代码应使用线程安全接口或立即复制文本。

### 7.3.6 练习

1. 给 `accept` 写一个只对 EINTR 重试的包装函数。
2. 说明 ET 模式下读到 EAGAIN 为什么代表“本轮已经排空”。
3. 区分 transient error 与 programming error，并各举两个例子。
4. 为 `write_all` 增加 `EPIPE` 日志处理，但不要终止整个进程。

## 7.4 字节序与整数表示

### 7.4.1 概念

多字节整数在内存中需要约定低地址存高位字节还是低位字节。

Big-endian 把最高有效字节放在最低地址。

Little-endian 把最低有效字节放在最低地址。

网络字节序规定为 big-endian。

主机字节序由 CPU/ABI 决定，常见 x86-64 是 little-endian。

### 7.4.2 原理与转换函数

- `htons`：host to network short，转换 16 位值；
- `ntohs`：network to host short；
- `htonl`：host to network long，接口历史名称中的 long 实际对应 32 位类型；
- `ntohl`：network to host long。

端口是 16 位，因此通常用 `htons`。

IPv4 地址是 32 位，因此传统接口常用 `htonl`。

协议字段应使用 `uint16_t`、`uint32_t` 等明确宽度类型，而不是假定 C 的 `int` 或 `long` 宽度。

### 7.4.3 代码示例：观察内存顺序

```c
#include <arpa/inet.h>
#include <stdint.h>
#include <stdio.h>

int main(void)
{
    uint32_t host = UINT32_C(0x12345678);
    uint32_t net = htonl(host);
    unsigned char *p = (unsigned char *)&host;

    printf("host memory: %02x %02x %02x %02x\n",
           p[0], p[1], p[2], p[3]);
    printf("network value converted back: 0x%08x\n", ntohl(net));
    return 0;
}
```

不要通过这段打印硬编码判断生产机器字节序；协议代码应始终显式转换。

### 7.4.4 有符号与无符号边界

`size_t` 是无符号类型，适合表示对象大小。

`ssize_t` 是有符号类型，允许 I/O API 用 -1 表示错误。

把 `read` 结果直接存入 `size_t` 会把 -1 转换成巨大正数，是严重 bug。

比较 `int` 与 `size_t` 时也可能发生 usual arithmetic conversions，使负数转成无符号大值。

### 7.4.5 常见误区

- 误区：网络传输 C 结构体即可。纠正：结构体有 padding、对齐、字节序和 ABI 差异。
- 误区：`htonl` 在所有机器都交换字节。纠正：大端主机上可能是恒等操作。
- 误区：文本 HTTP 头也需要 `htonl`。纠正：转换函数针对二进制整数表示。
- 误区：`long` 永远 32 位。纠正：LP64 平台通常为 64 位。

### 7.4.6 练习

1. 手工写出 `0x12345678` 的大端和小端内存布局。
2. 解释端口 8080 在 `sockaddr_in.sin_port` 中为什么要 `htons(8080)`。
3. 找出 `size_t n = read(fd, buf, cap); if (n < 0)` 的错误。
4. 设计一个不依赖结构体布局的二进制报文编码函数。

## 7.5 TCP/IP 四层模型与可靠传输

### 7.5.1 概念

常用 TCP/IP 四层模型为：

```text
应用层      HTTP、DNS、SSH                 进程间的应用语义
传输层      TCP、UDP                       端到端进程通信，端口复用
网际层      IP、ICMP                       跨网络寻址与尽力而为转发
网络接口层  Ethernet、Wi-Fi、ARP           同一链路上的帧传输
```

发送端逐层 encapsulation，接收端逐层 decapsulation。

HTTP 消息被视为 TCP 字节流内容。

TCP segment 被封装进 IP packet，再封装进链路帧。

### 7.5.2 TCP 是可靠字节流的含义

“可靠”表示 TCP 力图向应用提供按序、无重复的字节流。

它不保证网络永不丢包，也不保证连接永不失败。

可靠性依靠：

1. sequence number 标识字节位置；
2. ACK 确认已接收数据；
3. checksum 检测传输错误；
4. retransmission 修复丢失；
5. receive window 实现 flow control；
6. congestion window 控制注入网络的数据量；
7. 接收端重排失序 segment 并去重。

### 7.5.3 TCP 没有消息边界

发送端两次 `write(fd, "abc", 3)` 与 `write(fd, "def", 3)`，接收端可能：

- 一次 read 得到 `abcdef`；
- 两次分别得到 `ab` 与 `cdef`；
- 多次得到其他合法切分。

应用层必须自己 framing。

HTTP/1.x 可用行结束符、`Content-Length`、chunked encoding 或连接关闭界定消息。

### 7.5.4 代码示例：长度前缀协议

```c
int send_frame(int fd, const void *data, uint32_t len)
{
    uint32_t netlen = htonl(len);
    if (write_all(fd, &netlen, sizeof(netlen)) < 0)
        return -1;
    if (write_all(fd, data, len) < 0)
        return -1;
    return 0;
}
```

接收端先精确读取 4 字节长度，再校验上限，最后读取 payload。

永远不要在未检查长度上限时直接 `malloc(len)`。

### 7.5.5 常见误区

- 误区：一次 send 对应对端一次 recv。纠正：TCP 没有消息边界。
- 误区：TCP 可靠，所以应用无需处理错误。纠正：超时、reset、进程崩溃仍会使连接失败。
- 误区：flow control 与 congestion control 是同一件事。纠正：前者保护接收端，后者保护网络。
- 误区：监听端口代表只有一个 socket。纠正：listenfd 接受连接，每条连接产生独立 connfd。
- 误区：收到 ACK 就代表对端应用已经处理。纠正：通常只代表对端 TCP 栈确认了字节。

### 7.5.6 练习

1. 解释为什么 HTTP 解析必须处理不足值。
2. 为长度前缀协议补充最大 1 MiB 的长度检查。
3. 分别说明 advertised receive window 与 congestion window 受谁控制。
4. 用 Wireshark 观察一次 HTTP 请求的封装与 ACK。

# 第八大块：30 道阶段性自检题参考答案

## 8.1 Unix I/O 基础

### 答案 1：标准描述符与 `open` 规律

fd 0、1、2 分别是 standard input、standard output、standard error。

`open` 成功返回当前进程最小可用的非负描述符。

“最小可用”是 `close(1); open(...)` 可能得到 1 的原因，也是简单重定向能够工作的基础。

描述符只是进程内索引，不是磁盘 inode，也不是全局唯一编号。

### 答案 2：`read` 三类返回值

返回 0 表示到达 EOF；对 TCP 表示对端完成有序关闭且已读完先前数据。

返回 -1 表示失败，具体原因读取 `errno`。

返回 `0 < rc < n` 是合法 short count，不等于错误。

返回 n 只说明本次恰好取得 n 字节，不说明后面没有数据。

### 答案 3：`size_t` 与 `ssize_t`

`size_t` 无符号，用于对象大小和请求长度。

`ssize_t` 有符号，I/O 函数需要用 -1 表示失败。

必须先用 `ssize_t` 保存 `read` 返回值，确认非负后再转换为 `size_t`。

## 8.2 不足值与 RIO

### 答案 4：不足值场景

包括接近文件 EOF、终端按行输入、socket 当前只到达部分字节、write 目标暂时只能接收部分数据、信号打断等。

普通磁盘文件的阻塞读取常能满足请求，所以不足值较少见，但 POSIX 并不保证永远读满。

网络到达时间、分段和缓冲状态不可预测，因此 short count 是常态。

### 答案 5：RIO 如何处理 `EINTR`

`EINTR` 表明调用在完成前被信号打断。

RIO 令本轮有效进度为 0，并继续循环，让 read 再次执行。

去掉处理会把可恢复中断误报为永久失败，造成请求被提前截断。

若调用已经返回正的字节数，应先累计进度，而不是丢掉它。

### 答案 6：`rio_writen` 与 `rio_readn` 返回差异

`rio_writen` 循环处理 short write，成功语义是写完 n 字节；失败才返回 -1。

`rio_readn` 虽然也循环，但 EOF 可能在累计 n 字节前到来，所以合法返回小于 n。

“writen 永远返回 n”必须加前提：没有发生错误。

### 答案 7：逐字节读取为何不慢

`rio_readlineb` 的逐字节操作主要在用户空间内部缓冲区上完成。

内部缓冲区空时才调用一次 `read`，通常批量拉取 8192 字节。

之后逐字符只是指针移动和内存复制，不会每个字符都陷入内核。

数据流为 `socket receive buffer → rio_buf → usrbuf`。

## 8.3 文件共享与重定向

### 答案 8：三级表结构

进程描述符表记录 fd 到打开文件表项的引用。

打开文件表项记录 file offset、状态标志和引用计数。

v-node/inode 层记录文件类型、权限、大小和存储位置等元数据。

多个 fd 可指向同一打开文件表项，多个打开文件表项也可指向同一 v-node。

### 答案 9：两次 `open` 是否共享 offset

同一路径调用两次 `open` 通常创建两个独立打开文件表项，因此 offset 独立。

`dup` 得到的新 fd 指向同一个打开文件表项，因此共享 offset。

`fork` 后对应描述符也通常共享父进程原有的打开文件表项。

### 答案 10：`dup2(oldfd, newfd)`

若 `oldfd` 有效，`dup2` 使 `newfd` 指向 `oldfd` 的打开文件表项。

如果 `newfd` 原先打开，内核先原子地关闭原引用再完成复制。

`oldfd == newfd` 时成功且不改变映射。

原子性避免“先 close 再 dup”之间被信号或线程抢占造成 fd 复用竞态。

### 答案 11：为什么 fork 后两边都要 close

父子描述符各自贡献一个打开文件表引用。

父进程不关闭 connfd，会让连接引用一直存在，子进程关闭后也可能无法发送最终 FIN。

子进程不关闭 listenfd，会无谓保留监听 socket，并使监听端生命周期难管理。

close 只减少当前进程对应引用，不会直接使另一个进程的 fd 失效。

## 8.4 网络与套接字

### 答案 12：客户端和服务器调用链

服务器典型链路：`getaddrinfo → socket → setsockopt → bind → listen → accept`。

客户端典型链路：`getaddrinfo → socket → connect`。

CSAPP 的 `open_listenfd` 与 `open_clientfd` 封装了地址遍历和错误处理。

`accept` 返回新的 connected socket，原 listenfd 继续接受后续连接。

### 答案 13：listenfd 与 connfd

listenfd 代表被动监听端点，关注新连接到达。

connfd 代表一条具体 TCP 连接，可读写应用数据。

每次成功 accept 产生一个新 connfd。

关闭某个 connfd 不影响 listenfd 和其他连接。

### 答案 14：`getaddrinfo` 的意义

它把主机名、服务名和 hints 转换成地址候选链表。

调用者遍历候选项尝试 socket/connect 或 socket/bind。

它支持 IPv4/IPv6 和多种 socket 类型，避免硬编码 `sockaddr_in`。

使用结束后必须 `freeaddrinfo`。

### 答案 15：网络字节序

网络字节序是 big-endian。

16 位端口用 `htons`/`ntohs`，32 位字段用 `htonl`/`ntohl`。

名称里的 short/long 是历史接口命名，应配合固定宽度整数理解。

### 答案 16：HTTP 代理为何重写头

代理应把 absolute-form URI 解析为目标 host、port、path，再向源站发送合适请求行。

实验通常强制 `HTTP/1.0`，并发送指定 `User-Agent`。

必须重写 `Connection: close` 与 `Proxy-Connection: close`，避免持久连接状态机超出实验实现。

`Host` 头应保留客户端合法值或由 URI 重建，但不能重复发送冲突版本。

### 答案 17：二进制响应为何不能用 `strlen`

图片、压缩数据可以包含 `\0`，`strlen` 会在第一个零字节停止。

从服务器 read 得到的返回值才是本块准确字节数。

转发时必须 `rio_writen(clientfd, buf, n)`，缓存长度也应累计 n。

缓存对象必须以“字节数组 + 显式长度”表示。

## 8.5 并发三模型

### 答案 18：三种服务器并发模型

进程模型隔离强、编程直观，但创建和 IPC 成本较高。

I/O multiplexing 用单线程管理多 fd，资源省，但状态机更复杂，处理不能长时间阻塞。

线程模型共享内存方便、切换通常较轻，但必须正确同步并管理线程资源。

生产系统常混合使用多进程、线程池和事件循环。

### 答案 19：进程模型中的关闭责任

父进程只负责 accept，所以得到 connfd 后应交给子进程并关闭自己的副本。

子进程只负责该连接，所以应关闭继承来的 listenfd。

否则会泄漏引用、延迟 TCP 关闭，甚至使监听端在主进程退出后仍意外存活。

### 答案 20：`select`、`poll`、`epoll`

`select` 使用位图，受 `FD_SETSIZE` 限制，每轮复制并线性扫描，而且会修改集合。

`poll` 使用 `pollfd` 数组，突破位图编号限制，但仍需每轮传递并线性扫描。

`epoll` 在内核维护 interest list，并通过 ready list 返回就绪项，适合大量连接中少量活跃的场景。

不能简单声称 epoll 在所有工作负载都更快；连接数少时差异可能不重要。

### 答案 21：LT、ET 与非阻塞

LT 在 fd 保持就绪时可反复通知，容错较高。

ET 主要在状态边沿变化时通知，需要本轮尽量处理到不能继续。

若 ET 使用阻塞 fd，排空循环最后一次 read 可能永久阻塞事件线程。

因此 ET 通常配 O_NONBLOCK，并读到 `EAGAIN/EWOULDBLOCK`。

### 答案 22：线程参数为何动态分配

主循环的栈变量 connfd 会在下一次 accept 被覆盖。

把 `&connfd` 交给多个线程会形成 data race，工作线程看到的值不确定。

为每个线程分配独立参数对象，创建成功后将所有权转交工作线程，可保证生命周期和独立性。

也可使用有界队列按值传递 fd，线程池就是这种模式。

### 答案 23：线程与进程共享关系

同进程线程共享虚拟地址空间的大部分区域、全局数据、堆和描述符表。

线程独有寄存器、栈、线程 ID、调度状态与 TLS。

不同进程默认地址空间隔离，但 fork 后可通过 COW 暂时共享物理页。

进程间共享数据需要 pipe、socket、shared memory 等 IPC 机制。

## 8.6 同步与线程安全

### 答案 24：三类 C 变量是否共享

全局变量和函数内 `static` 变量位于共享数据区，同进程线程共享。

普通局部自动变量位于各线程自己的栈，通常不共享。

但局部变量的地址若发布给其他线程，所指对象仍可能被并发访问，因此“在栈上”不等于绝对线程私有。

动态分配对象是否共享取决于指针是否被多个线程持有。

### 答案 25：P/V 与原子性

P（wait）等待计数大于零并原子减一；不满足时阻塞。

V（post）原子加一，并可能唤醒等待者。

若“检查计数”和“修改计数”不原子，两个线程可能同时通过检查并消耗同一份资源。

实现依赖内核与硬件原子指令，而不是普通 `value--`。

### 答案 26：生产者—消费者三个信号量

`mutex` 保护队列结构；`slots` 记录空槽；`items` 记录已有任务。

生产者先 P(slots) 再 P(mutex)，写入后 V(mutex)、V(items)。

若先持有 mutex 再等待 slots，队列满时生产者睡眠却占着锁，消费者无法取走 item，形成死锁。

消费者对称地先 P(items) 再 P(mutex)。

### 答案 27：条件变量为什么用 `while`

条件变量可能 spurious wakeup。

即使确实收到 signal，线程重新取得 mutex 前，另一个线程也可能先改变条件。

所以 `pthread_cond_wait` 返回只表示“应该重新检查”，不表示谓词必然成立。

标准模式是 `while (!predicate) pthread_cond_wait(...)`。

### 答案 28：死锁四条件与锁排序

四个必要条件是 mutual exclusion、hold and wait、no preemption、circular wait。

全局锁排序要求所有线程按同一序获取锁，从结构上消除 circular wait。

释放顺序通常反向进行，便于保持不变量和代码清晰。

锁排序必须覆盖程序中所有可能同时持有的锁。

### 答案 29：线程安全、可重入与四类函数

线程安全表示多线程并发调用仍正确；可重入要求函数可在尚未完成时再次进入，通常是更强性质。

第一类不保护共享变量：加锁或消除共享状态。

第二类跨调用保留状态，如 `rand`：改为显式状态版本。

第三类返回静态缓冲区指针：改为 caller-provided buffer 或立即复制。

第四类调用线程不安全函数：替换依赖或在更高层串行化。

### 答案 30：读者优先为何饿死写者

读者优先算法允许新读者在已有读者时不断加入。

只要读者流不间断，`readcnt` 就无法降到 0，写者一直拿不到写锁。

写者优先版本用 gate/read_try 在写者到达后阻止新读者进入。

公平版本通常使用 FIFO queue、ticket 或 fair rwlock，让到达顺序得到更强保证。

# 第九大块：把知识落到 Proxy Lab 的执行清单

## 9.1 Part I 顺序代理

### 概念

顺序代理一次处理一个客户端，重点验证 HTTP 解析、连接源站和二进制透明转发。

### 原理

完整数据路径是：

```text
browser
  → clientfd
  → proxy request parser
  → serverfd
  → origin server
  → serverfd
  → proxy response relay/cache candidate
  → clientfd
  → browser
```

### 实现步骤

1. 校验命令行端口并忽略 SIGPIPE。
2. 调用 `open_listenfd` 创建监听 socket。
3. 循环 accept；失败时区分 EINTR 与永久错误。
4. 为 clientfd 初始化独立 `rio_t`。
5. 读取 request line，并限制 method 为 GET（按实验要求）。
6. 解析 URI 的 scheme、host、可选 port 与 path。
7. 读取完整请求头直到空行。
8. 过滤 Host、User-Agent、Connection、Proxy-Connection 的旧版本。
9. 用有界追加方式构造发往源站的新请求。
10. `open_clientfd(host, port)` 连接源站。
11. 一次性完整发送重建请求。
12. 循环 read 源站响应，每块按实际 n 转发。
13. 关闭 serverfd，再关闭 clientfd。

### 代码骨架

```c
while ((n = Rio_readnb(&server_rio, buf, sizeof(buf))) > 0) {
    if (Rio_writen(clientfd, buf, (size_t)n) < 0)
        break;
}
```

这里不能用 `strlen(buf)`，因为响应可以是 binary object。

### 误区

- 只解析 `http://host/path`，忘记 `:port`。
- 把 URI 直接作为源站 request-target，未转换成 path。
- 依赖大小写敏感比较 HTTP header name。
- 忘记请求头终止的 `\r\n`。
- 源站连接失败后仍继续使用无效 serverfd。

### 练习

用 `curl -v -x localhost:PORT http://example.com/` 检查代理实际发送的头。

再请求图片并比较经代理与直连文件的 SHA-256。

## 9.2 Part II pthread 并发代理

### 概念

thread-per-connection 把每个已连接 socket 交给独立工作线程。

### 原理

工作线程共享进程描述符表，但每个线程只应拥有自己 connfd 的处理责任。

线程例程必须 detached 或由其他线程 join。

### 实现步骤

1. 主线程只做 accept 和 dispatch。
2. 每个连接创建独立 heap 参数。
3. `pthread_create` 成功后，工作线程获得参数所有权。
4. 工作线程尽早 detach 并 free 参数。
5. 调用已经验证的 Part I 处理函数。
6. 无论正常、协议错误还是源站错误，都走统一 cleanup 关闭 fd。
7. 所有共享统计数据使用 atomic 或锁保护。

### 误区

- 传 `&connfd` 引发竞态。
- 在线程中调用会返回静态共享缓冲区的旧式函数。
- 某个工作线程遇到普通客户端错误就调用 `exit`，杀死整个代理。
- 忽略 SIGPIPE，让任意断连客户端终止整个进程。
- 创建线程无限制，最终耗尽地址空间和调度资源。

### 练习

用并发脚本同时发起 100 个请求，并通过 ThreadSanitizer 或 Helgrind 检查 data race。

## 9.3 Part III LRU 缓存代理

### 概念

缓存以规范化 URI 为 key，以响应原始字节和显式长度为 value。

总缓存不超过 `MAX_CACHE_SIZE`，单对象不超过 `MAX_OBJECT_SIZE`。

### 原理

只有完整接收且大小合规的响应才可插入。

边转发边把字节复制到对象暂存区。

如果累计长度超限，停止收集但继续向客户端转发。

LRU 要淘汰最久未被使用的对象，而不是最早插入对象。

### 锁设计

真正纯读的 lookup 可以持 read lock。

但命中后更新 LRU 元数据属于写操作。

可选方案：

1. 在 write lock 下查找、复制并更新元数据，最简单正确；
2. read lock 下查找和复制，随后 write lock 再验证并更新时间，吞吐更高但逻辑复杂；
3. 内容 immutable，命中计数用 atomic，淘汰流程仍持 write lock。

复制命中对象时必须考虑对象是否会在解锁后被淘汰。

最简单做法是在读锁内复制到线程私有缓冲区，解锁后再写 clientfd。

### 误区

- 把 `strlen` 用在缓存对象上。
- 响应尚未完整就插入缓存。
- 在持有全局缓存锁时执行阻塞网络写，导致所有线程停顿。
- 只锁链表指针，不锁 size 和 LRU 元数据，仍有 data race。
- 用递增 `int` 时间戳且不考虑溢出。
- key 未规范化，等价 URI 占多个条目。

### 练习

构造大小恰好为 `MAX_OBJECT_SIZE`、大 1 字节和小 1 字节的响应，验证边界。

并发请求同一未缓存 URI，观察是否出现 cache stampede，并思考 single-flight 优化。

## 9.4 调试与验收清单

- 编译开启 `-Wall -Wextra -Wpedantic -Wconversion -pthread`。
- 调试构建加入 AddressSanitizer 与 UndefinedBehaviorSanitizer。
- 并发问题使用 ThreadSanitizer；不要和 AddressSanitizer 混在同一次运行中。
- 用 `curl -v` 查看请求行、响应码和连接关闭。
- 用 `nc` 手工发送畸形请求，验证代理不会崩溃。
- 用 `strace`/`dtruss` 观察 accept、connect、read、write、close。
- 用 `lsof -p PID` 检查 fd 是否随请求持续增长。
- 用二进制文件校验哈希，防止把响应错误当字符串。
- 检查所有错误路径是否释放 heap 参数并关闭已打开 fd。
- 检查所有 `snprintf` 返回值，而不只是依赖它自动截断。

## 9.5 安全的 `snprintf` 追加助手

### 概念与原理

`snprintf` 最多写入容量减一的正文字符，并追加 NUL（容量大于零时）。

它返回“如果空间无限，本应写入的字符数”。

所以返回值大于等于剩余容量表示发生 truncation。

不能直接无条件执行 `used += snprintf(...)`，否则 used 可能越过容量，下一次 `cap - used` 发生无符号下溢。

### 代码示例

```c
#include <stdarg.h>
#include <stdio.h>

int appendf(char *dst, size_t cap, size_t *used, const char *fmt, ...)
{
    if (*used >= cap)
        return -1;

    va_list ap;
    va_start(ap, fmt);
    int rc = vsnprintf(dst + *used, cap - *used, fmt, ap);
    va_end(ap);

    if (rc < 0)
        return -1;

    size_t need = (size_t)rc;
    if (need >= cap - *used)
        return -1;

    *used += need;
    return 0;
}
```

### 误区与练习

误区是认为 `snprintf` 截断后仍可安全地继续累计其返回值。

练习：用 `appendf` 构造代理请求，并让任意一步失败时返回 `431 Request Header Fields Too Large` 或关闭连接。

---

# 第十大块：`EPOLLONESHOT` 的独占处理语义

## 10.1 概念

`EPOLLONESHOT` 表示一个 fd 触发一次事件后会在该 epoll 实例中被暂时禁用。

它不会自动关闭 fd，也不会把 fd 从 interest list 永久删除。

应用处理完成后，需要用 `epoll_ctl(EPOLL_CTL_MOD, ...)` 重新 arm。

## 10.2 原理

在“一个 epoll 实例 + 多个工作线程”架构中，同一 socket 可能被多个线程并行处理。

这会造成两线程同时读协议状态、同时修改输出缓冲区或同时关闭 fd。

`EPOLLONESHOT` 让一次 readiness 通知只交给一个处理者。

处理者维护完连接状态后再 rearm，使下一次事件可被分发。

它解决的是同一 fd 的并发处理权，不代替应用状态锁，也不保证整个请求一次完成。

常与 `EPOLLET | O_NONBLOCK` 组合：

```c
static int rearm(int epfd, int fd, uint32_t interests)
{
    struct epoll_event ev = {0};
    ev.events = interests | EPOLLET | EPOLLONESHOT;
    ev.data.fd = fd;
    return epoll_ctl(epfd, EPOLL_CTL_MOD, fd, &ev);
}
```

工作线程应循环 read/write 到 `EAGAIN`，更新连接状态，再调用 `rearm`。

若忘记 rearm，该连接即使后来有数据也不会再收到事件，表现为“神秘卡死”。

## 10.3 代码示例：处理与重新注册

```c
void handle_ready(connection_t *c)
{
    for (;;) {
        ssize_t n = read(c->fd, c->inbuf + c->used,
                         sizeof(c->inbuf) - c->used);
        if (n > 0) {
            c->used += (size_t)n;
            consume_complete_messages(c);
            continue;
        }
        if (n == 0) {
            close_connection(c);
            return;
        }
        if (errno == EINTR)
            continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK)
            break;
        close_connection(c);
        return;
    }

    if (rearm(c->epfd, c->fd, EPOLLIN) < 0)
        close_connection(c);
}
```

真实实现还要防止 fd number reuse：旧任务中的数字 fd 可能已关闭并被新连接复用。

可用带 generation 的连接对象、引用计数和明确生命周期协议降低风险。

## 10.4 常见误区

- 误区：`EPOLLONESHOT` 触发后 fd 被关闭。纠正：只是该 epoll 注册被禁用。
- 误区：它与 ET 相同。纠正：ET 控制边沿通知；ONESHOT 控制触发后是否需显式 rearm。
- 误区：rearm 越早越好。纠正：共享状态尚未更新完就 rearm，另一线程可能并发进入。
- 误区：用了 ONESHOT 就不需要非阻塞。纠正：ET 排空循环仍要求非阻塞来避免工作线程卡住。
- 误区：ONESHOT 自动解决 fd reuse。纠正：生命周期和异步任务仍需额外设计。

## 10.5 练习

1. 画出“epoll 线程取事件 → 工作线程处理 → MOD rearm”的时序图。
2. 删除示例中的 rearm，观察连接为何只处理一次。
3. 分别解释 `EPOLLET`、`EPOLLONESHOT`、`O_NONBLOCK` 各自解决的问题。
4. 设计一个连接对象引用计数方案，避免 close 与工作任务并发。

---

# 结语：从 API 记忆走向系统推理

系统级 I/O 的核心不是背函数名，而是追踪“字节在哪里、由谁拥有、何时可见”。

网络编程的核心不是把 socket 当文件这么简单，而是同时处理字节流、协议 framing、错误和生命周期。

并发编程的核心不是增加线程数量，而是明确共享状态、不变量、锁粒度和资源回收责任。

Proxy Lab 把三者压缩进一个项目：

- RIO 训练 short count 与边界；
- socket 训练协议无关地址与 TCP 字节流；
- pthread 训练生命周期与共享状态；
- cache 训练同步、LRU 和 binary-safe 数据表示；
- 调试过程训练从 syscall、协议到竞态的分层定位能力。

完成实验后，应能沿着如下因果链解释任意一行关键代码：

```text
应用层需求
→ 数据表示与协议边界
→ 用户缓冲区
→ libc / CSAPP wrapper
→ system call
→ 内核 socket/file object
→ TCP/IP 或文件系统
→ 并发可见性与资源生命周期
```

能画出这条链、说清每层失败方式，并写出有边界检查和清理路径的代码，才算真正掌握第 10、11、12 章。