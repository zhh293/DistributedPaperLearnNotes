# 7-shelllab

### 1 简要分析

> 实验目的：熟悉信号和进程管理
>
> 工作空间：tsh.c
>
> 实验内容：实现一个微小的unix版本的shell，主要实现
>
> 1. eval函数：解析和翻译输入的命令行
> 2. builtin_cmd函数：识别和翻译内置命令quit，fg，bg，jobs
> 3. do_bgfg函数：实现fg，bg内置命令
> 4. waitfg函数：等待前台命令完成
> 5. sigchld_handler函数：捕获SIGCHILD信号（当一个进程结束或停止时，该进程的父进程会收到 SIGCHLD 信号）
> 6. sigint_handler函数：捕获SIGINT（ctrl-c）信号
> 7. sigtstp_handler函数：捕获SIGTSTP（ctrl-z）信号
>
> 实验方法：熟悉unix的信号和shell，实现上述七个函数即可

### 2 具体实现

**1. 回顾几个比较重要的函数**

> 1. waitpid：用于使父进程暂停，等待子进程结束
>
>    - pid>0：则等待该pid的子进程
>    - pid=-1：等待任意子进程
>    - pid<-1：等待pid为绝对值的进程组
>2. sigprocmask：用于屏蔽信号，使这些信号被接收时不被处理，有如下参数
> 
>   - SIG_BLOCK：添加set中的信号到当前屏蔽字中
>    - SIG_UNBLOCK：从当前屏蔽字中移出set中的信号
>    - SIG_SETMASK：设置当前信号屏蔽字为set
> 3. sigfillset：将所有信号都装入set中
>4. sigemptyset：初始化一个空的set
> 5. sigaddset：将某个信号加入set
>    

**2. 解析作者给出的几个函数**

> 1. 每个作业的结构，包括
>    - pid：进程id
>    - jid：作业id
>    
>    **注意：用户可以启动一个或多个进程为一个作业服务，比如我用多进程跑一个count计数函数，此实验应该是一个进程对应一个作业**
>    
>    - state：作业状态，UNDEF表示作业还没被创建或者已经结束，BG表示后台运行，FG表示前台运行，ST表示作业被停止了，如收到了SIGSTOP 或 SIGTSTP信号
>    - cmdline：对映的命令行语句
>
> jobs一共包含了16个job空闲位

```
struct job_t {              /* The job struct */
    pid_t pid;              /* job PID */
    int jid;                /* job ID [1, 2, ...] */
    int state;              /* UNDEF, BG, FG, or ST */
    char cmdline[MAXLINE];  /* command line */
};
struct job_t jobs[MAXJOBS]; /* The job list */
```

> 2. **parseline**：解析命令行参数，如python echo.py hello &，将命令行拆分为python，echo.py，hello三个段存储到argv数组中，然后检查是否有后台运行标志&，有的话返回1，否则返回0，表示是前台命令
> 3. **initjobs**（clearjobs）：初始化作业组
> 4. **maxjid**：返回作业组中最大的作业id
> 5. **addjob**：向作业组中添加一个作业
> 6. **deletejob**：删除一个作业
> 7. **fgpid**：返回当前前台作业的pid，没有则返回0
> 8. **getjobpid**：通过作业的pid查找作业
> 9. **getjobjid**：通过作业的jid查找作业
> 10. **pid2jid**：通过作业的pid映射到作业的jid
> 11. **listjobs**：打印一下所有的作业
> 12. **usage**：打印一下使用规则
>
> 现有的函数就这些，还剩下几个都是error处理，可以不用

**2. eval函数**

> 功能：解析命令是内置命令还是其他命令，是前台作业还是后台作业
>
> 思路：
>
> 1. 首先通过parseline函数将命令拆分到argv中，并获取是前台命令还是后台命令
> 2. 然后通过后面实现的buildin_cmd函数判断是内置命令还是其他命令
> 3. 如果是内置，则buildin_cmd可以直接实现
> 4. 如果不是，则首先要屏蔽SIGCHLD信号：因为如果在 execve执行前，fork的子进程已经结束，但父进程还没有来得及处理 SIGCHLD 信号，新程序就可能在一个 "已经结束的" 子进程中开始运行，这会导致状态混乱
> 5. 屏蔽后就fork一个子进程用execve执行该命令
> 6. 然后父进程还要判断是否是前台命令，如果是的话需要用waitpid等待，否则输出后台信息
> 7. 最后注意在调用addjob函数时需要阻塞所有信号，防止在运行addjob时子进程运行完成了

```
void eval(char *cmdline) 
{
    char *argv[MAXARGS];
    char buf[MAXLINE];
    int bg;
    int state;
    pid_t pid;
    sigset_t mask_all, mask_one, prev_one;

    strcpy(buf, cmdline);
    bg = parseline(buf, argv);
    state = bg? BG : FG;

    if (argv[0] == NULL)
    {
        return;
    }

    if (!builtin_cmd(argv))
    {
        sigfillset(&mask_all);
        sigemptyset(&mask_one);
        sigaddset(&mask_one, SIGCHLD);
        sigprocmask(SIG_BLOCK, &mask_one, &prev_one);
        
        if ((pid = fork()) == 0)
        {
            sigprocmask(SIG_SETMASK, &prev_one, NULL);
            setpgid(0, 0);
            execve(argv[0], argv, environ);
            exit(0);
        }
        if (state == FG)
        {
            sigprocmask(SIG_BLOCK, &mask_all, NULL);
            addjob(jobs, pid, state, cmdline);
            sigprocmask(SIG_SETMASK, &mask_one, NULL);
            waitfg(pid);
        }
        else
        {
            sigprocmask(SIG_BLOCK, &mask_all, NULL);
            addjob(jobs, pid, state, cmdline);
            sigprocmask(SIG_SETMASK, &mask_one, NULL);
            printf("[%d] (%d) %s", pid2jid(pid), pid, cmdline);
        }
        sigprocmask(SIG_SETMASK, &prev_one, NULL);
    }

    return;
}
```

**4. buildin_cmd函数**

> 功能：判断是否为内置命令，是的话立即执行并返回1，不是的话返回0
>
> 思路：判断argv[0]即可

```
int builtin_cmd(char **argv) 
{
    if (!strcmp(argv[0], "quit"))
    {
        exit(0);
    }
    else if (!strcmp(argv[0], "jobs"))
    {
        listjobs(jobs);
        return 1;
    }
    else if (!strcmp(argv[0], "bg") || !strcmp(argv[0], "fg"))
    {
        do_bgfg(argv);
        return 1;
    }
    else if (!strcmp(argv[0], "&"))
    {
        return 1;
    }
    return 0;     /* not a builtin command */
}
```

**5. do_fgbg函数**

> 功能：实现fg和bg函数
>
> 思路：
>
> 1. 区分是fg函数还是bg函数
> 2. 判断是使用的jid还是pid，并解析出id
> 3. 使用getjob函数找到该作业
> 4. 向该作业发送一个SIGCONT信号，用于让被停止（例如，因为接收到了 SIGSTOP、SIGTSTP、SIGTTIN` 或 `SIGTTOU 信号）的进程或进程组继续运行
> 5. 然后判断是fg命令否，是的话就用将要实现的waitfg等待该进程执行完，是bg的话输出信息

```
void do_bgfg(char **argv) 
{
    struct job_t *job = NULL;
    int state;
    int id;

    if (!strcmp(argv[0], "bg"))
    {
        state = BG;
    }
    else
    {
        state = FG;
    }

    if (argv[1] == NULL)
    {
        printf("%s command requires PID or %%jobid argument\n", argv[0]);
        return;
    }

    if (argv[1][0] == '%')
    {
        if (sscanf(&argv[1][1], "%d", &id) > 0)
        {
            job = getjobjid(jobs, id);
            if (job == NULL)
            {
                printf("%%%d: No such job\n", id);
                return;
            }
        }
    }
    else if (!isdigit(argv[1][0]))
    {
        printf("%s: argument must be a PID or %%jobid\n", argv[0]);
        return;
    }
    else
    {
        id = atoi(argv[1]);
        job = getjobpid(jobs, id);
        if (job == NULL)
        {
            printf("(%d): No such process\n", id);
            return;
        }
    }
    kill(-(job->pid), SIGCONT);
    job->state = state;
    if (state == BG)
    {
        printf("[%d] (%d) %s",job->jid, job->pid, job->cmdline);
    }
    else
    {
        waitfg(job->pid);
    }

    return;
}
```

**6. waitfg函数**

> 功能：等待前台作业完成
>
> 思路：一直循环等待子进程结束，这时候fgpid将会返回0

```
void waitfg(pid_t pid)
{
    sigset_t mask;
    sigemptyset(&mask);
    while (fgpid(jobs) != 0)
    {
        sigsuspend(&mask);
    }
    return;
}
```

**7. sigchld_handler**

> 功能：处理sigchld信号。这个信号是一个由操作系统向父进程发送的信号，用于通知父进程其子进程的状态已经改变。状态改变可能包括子进程终止、停止或者继续运行，这里我们只需要处理暂停和终止
>
> 思路：
>
> 1. 使用waitpid等待所有子进程，如果有任意的子进程状态改变了，那就处理
> 2. 判断是正常退出，还是因为信号而终止了，还是因为信号而暂停了
>    - WNOHANG 选项使得 waitpid() 在没有子进程结束时不会阻塞，而是立即返回 0。WUNTRACED 选项使得 waitpid() 在子进程被停止时也会返回
>    - WIFEXITED：一个宏定义，检查子进程是否正常结束
>    - WIFSIGNALED：一个宏定义，用于检查子进程是否因为接收到一个未被捕获的信号而终止
>    - WIFSTOPPED：一个宏定义，用于检查子进程是否被停止

```
void sigchld_handler(int sig) 
{
    int olderrno = errno;
    int status;
    pid_t pid;
    struct job_t *job;
    sigset_t mask_all, prev;
    sigfillset(&mask_all);

    while ((pid = waitpid(-1, &status, WNOHANG | WUNTRACED)) > 0) 
    {
        sigprocmask(SIG_BLOCK, &mask_all, &prev);
        if (WIFEXITED(status))
        {
            deletejob(jobs, pid);
        }
        else if (WIFSIGNALED(status))
        {
            printf ("Job [%d] (%d) terminated by signal %d\n", pid2jid(pid), pid, WTERMSIG(status));
            deletejob(jobs, pid);
        }
        else if (WIFSTOPPED(status))
        {
            printf ("Job [%d] (%d) stoped by signal %d\n", pid2jid(pid), pid, WSTOPSIG(status));
            job = getjobpid(jobs, pid);
            job->state = ST;
        }
        sigprocmask(SIG_SETMASK, &prev, NULL);          
    }
    errno = olderrno;
    return;
}
```

**8. sigint_handler**

> 功能：处理ctrl-c，即SIGINT信号
>
> 思路：就是判断如果还有前台任务，则用kill向其发送一个SIGINT信号终止该进程即可

```
void sigint_handler(int sig) 
{
    int olderrno = errno;
    pid_t pid;
    sigset_t mask_all, prev;
    sigfillset(&mask_all);
    sigprovmask(SIG_BLOCK, &mask_all, &prev);
    
    if ((pid = fgpid(jobs)) != 0)
    {
        sigprocmask(SIG_SETMASK, &prev, NULL);
        kill(-pid, SIGINT);
    }
    errno = olderrno;
    return;
}
```

**9. sigtstp_handler**

> 功能：处理ctrl-z，即SIGTSTP信号
>
> 思路：与sigint_handler一样

```
void sigtstp_handler(int sig) 
{
    int olderrno = errno;
    pid_t pid;
    sigset_t mask_all, prev;
    sigfillset(&mask_all);
    sigprovmask(SIG_BLOCK, &mask_all, &prev);
    
    if ((pid = fgpid(jobs)) != 0)
    {
        sigprocmask(SIG_SETMASK, &prev, NULL);
        kill(-pid, SIGSTOP);
    }
    errno = olderrno;
    return;
}
```

**注：所有代码来自[CSAPP | Lab7-Shell Lab 深入解析 - 知乎 (zhihu.com)](https://zhuanlan.zhihu.com/p/492645370)，这位确实写得好**



