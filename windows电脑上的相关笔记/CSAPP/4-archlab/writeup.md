# 4-archlab

### 1 简要分析

> 实验目的：熟悉Y86汇编指令，在汇编基础上优化程序性能
>
> 工作空间：
>
> 1. part A：misc下的sum.ys，rsum.ys，copy.ys
> 2. part B：seq下的seq-full.hcl
> 3. part C：pipe下的pipe-full.hcl与ncopy.ys
>
> 实验内容：使用汇编指令实现partA中example下的三个程序，构建partB的iaddq指令，然后基于A和B修改partC，优化程序性能

### 2 具体实现

**2.1 sum.ys**

> 先回顾一下Y86指令集和Y86寄存器和函数传递顺序

```
moveq:
irmovq, rrmovq, mrmoveq, rmmoveq

jmp:
jmp, jl, jle, jg, jge, je, jne

cmov:
cmovl, cmovle, cmovg, cmovge, cmove, cmovne

opq:
addq, subq, andq, xorq

call:
call, ret

stack:
pushq, popq

process:
nop, halt
```

```
%rax - %rdx
%rsp, %rbp
%rsi, %rdi
%r8 - %r14
%rip
```

```
函数参数最多六个可放在寄存器上，多余的放在栈上
%rdi, %rsi, %rdx, %rcx, %r8, %r9
```

> 回顾part A，首先看看第一个程序

```
typedef struct ELE {
    long val;
    struct ELE *next;
} *list_ptr;
```

```
/* sum_list - Sum the elements of a linked list */
long sum_list(list_ptr ls)
{
    long val = 0;
    while (ls) {
	val += ls->val;
	ls = ls->next;
    }
    return val;
}

```

> 实现一个列表求和，没有啥好说的，按照csapp的Y86while循环的demo写就可以了，稍微解释一下可能不懂的地方：
>
> .pos：指定程序计数器的值，这个指令告诉汇编器，接下来的代码应该从指定的地址开始，我这里设置为从0开始，那么程序将从内存地址0x0开始存储，下面.pos 0x200表明堆栈从0x200开始向下生长
>
> irmovq  stack, %rsp：将stack标签表示的地址送给rsp，即设置堆栈起始位
>
> 汇编代码中的标签可以代表一个内存地址，如ele1、stack等等标签

```
# sum

        .pos 0
        irmovq  stack, %rsp
        call    main
        halt

# Sample linked list
        .align 8
ele1:
        .quad 0x00a
        .quad ele2
ele2:
        .quad 0x0b0
        .quad ele3
ele3:
        .quad 0xc00
        .quad 0

main:
        irmovq  ele1, %rdi
        call    sum
        ret

sum:
        irmovq  $0, %rax
        jmp     test

loop:
        mrmovq  (%rdi), %rsi
        addq    %rsi, %rax
        mrmovq  8(%rdi), %rdi

test:
        andq    %rdi, %rdi
        jne     loop
        ret


        .pos 0x200
stack:


```

**2.2 rsum.ys**

> 看看源代码

```
/* rsum_list - Recursive version of sum_list */
long rsum_list(list_ptr ls)
{
    if (!ls)
	return 0;
    else {
	long val = ls->val;
	long rest = rsum_list(ls->next);
	return val + rest;
    }
}
```

> 无非就是把循环改为递归，没有什么了不起的，直接上代码
>
> 讲讲可能有疑惑的地方：andq    %rdi, %rdi， je	return。自己和自己addq对数值没有任何作用，但可以设置标志位，如果%rdi为0或null（其实null在C语言中一般为0或(void *)0），那么就设置零标志，je专用于判断标志为是否为零标志，是的话就跳转。

```
# rsum

        .pos 0
        irmovq  stack, %rsp
        call    main
        halt

# Sample linked list
        .align 8
ele1:
        .quad 0x00a
        .quad ele2
ele2:
        .quad 0x0b0
        .quad ele3
ele3:
        .quad 0xc00
        .quad 0

main:
        irmovq  ele1, %rdi
        call    rsum
        ret

rsum:
        andq    %rdi, %rdi
        je      return
        mrmovq  (%rdi), %rbx
        mrmovq  8(%rdi), %rdi
        pushq   %rbx
        call    rsum_list
        popq    %rbx
        addq    %rbx, %rax
        ret

return:
        irmovq  $0, %rax
        ret



        .pos 0x200
stack:


```

**2.3 copy.ys**

> 同样看看源代码

```
/* copy_block - Copy src to dest and return xor checksum of src */
long copy_block(long *src, long *dest, long len)
{
    long result = 0;
    while (len > 0) {
        long val = *src++;
        *dest++ = val;
        result ^= val;
        len--;
    }
    return result;
}
```

> 无非就是把一个长整型数组复制到另一个长整型数组，也没啥好说的，直接上代码
>
> 只需要注意一点，src和dst都是指向一个内存地址，Y86中没有mmmovq这种指令，所以需要先将内存中的值取到寄存器中，再放入内存中。addq和subq也必须是两个寄存器相减，所以需要将8和1放入寄存器中使用

```
#copy

        .pos 0
        irmovq  stack, %rsp
        call    main
        halt

# Sample
        .align 8
# Source block
src:
        .quad 0x00a
        .quad 0x0b0
        .quad 0xc00

# Destination block
dst:
        .quad 0x111
        .quad 0x222
        .quad 0x333

main:
        irmovq  src, %rdi
        irmovq  dst, %rsi
        irmovq  $3, %rdx
        call    copy
        ret

copy:
        irmovq  $8, %r8
        irmovq  $1, %r9
        irmovq  $0, %rax
        andq    %rdx, %rdx
        jmp     test

loop:
        mrmovq  (%rdi), %r10
        addq    %r8, %rdi
        rmmovq  %r10, (%rsi)
        addq    %r8, %rsi
        xorq    %r10, %rax
        subq    %r9, %rdx

test:
        jne     loop
        ret



        .pos 0x200
stack:


```

**2.4 seq-full.hcl**

> part B需要我们实现一个iaddq指令，即实现立即数与寄存器直接做加法运算。首先回顾一下一条机器指令的执行步骤
>
> 1. 取指：根据PC的值从内存中取出具体指令
> 2. 译码：识别该条指令，确定操作数
> 3. 执行：执行该条指令
> 4. 访存：有些操作数需要从内存中取出
> 5. 写回：将最多两个结果写回到寄存器
> 6. 更新PC

> 依次看看seq-full.hcl需要更改的地方：
>
> 1. fetch stage：
>
>    - bool instr_valid：判断指令是否有效，添上IIANDQ
>
>    - bool need_regids：判断是否需要寄存器指示字节，这个指令需要用到寄存器，因此需要寄存器指示字节指定是哪个寄存器，添上
>    - bool need_valC：判断是否需要常数，添上
>
> 2. decode stage：
>
>    - word srcA：是否需要源寄存器rA，不需要
>    - word srcB：是否需要源寄存器，我们需要一个源寄存器rB，这个rB提供一个操作数，添上
>    - word dstE：是否需要一个目标寄存器，我们需要一个目的寄存器存计算后的值，还是选rB存，添上
>    - word dstM：是否需要存入内存，不需要
>
> 3. execute stage：
>
>    - 首先要知道，ALU执行阶段是aluB OP aluA，由于我们设置的dstE是rB，因此结果存入aluB。aluA与aluB操作数的来源是valA，valB，valC，其中valC存常数。因此aluA中valC添加，aluB中valB添加
>    - bool set_cc：设置标志位，需要设置，添上
>
> 4. memory stage：不需要访存
>
> 5. 更改PC：不涉及转移指令，不需要修改
>
> 整个代码：

```
####################################################################
#    Control Signal Definitions.                                   #
####################################################################

################ Fetch Stage     ###################################

# Determine instruction code
word icode = [
	imem_error: INOP;
	1: imem_icode;		# Default: get from instruction memory
];

# Determine instruction function
word ifun = [
	imem_error: FNONE;
	1: imem_ifun;		# Default: get from instruction memory
];

bool instr_valid = icode in 
	{ INOP, IHALT, IRRMOVQ, IIRMOVQ, IRMMOVQ, IMRMOVQ,
	       IOPQ, IJXX, ICALL, IRET, IPUSHQ, IPOPQ, IIADDQ };

# Does fetched instruction require a regid byte?
bool need_regids =
	icode in { IRRMOVQ, IOPQ, IPUSHQ, IPOPQ, 
		     IIRMOVQ, IRMMOVQ, IMRMOVQ, IIADDQ };

# Does fetched instruction require a constant word?
bool need_valC =
	icode in { IIRMOVQ, IRMMOVQ, IMRMOVQ, IJXX, ICALL, IIADDQ };

################ Decode Stage    ###################################

## What register should be used as the A source?
word srcA = [
	icode in { IRRMOVQ, IRMMOVQ, IOPQ, IPUSHQ  } : rA;
	icode in { IPOPQ, IRET } : RRSP;
	1 : RNONE; # Don't need register
];

## What register should be used as the B source?
word srcB = [
	icode in { IOPQ, IRMMOVQ, IMRMOVQ, IIADDQ } : rB;
	icode in { IPUSHQ, IPOPQ, ICALL, IRET } : RRSP;
	1 : RNONE;  # Don't need register
];

## What register should be used as the E destination?
word dstE = [
	icode in { IRRMOVQ } && Cnd : rB;
	icode in { IIRMOVQ, IOPQ, IIADDQ } : rB;
	icode in { IPUSHQ, IPOPQ, ICALL, IRET } : RRSP;
	1 : RNONE;  # Don't write any register
];

## What register should be used as the M destination?
word dstM = [
	icode in { IMRMOVQ, IPOPQ } : rA;
	1 : RNONE;  # Don't write any register
];

################ Execute Stage   ###################################

## Select input A to ALU
word aluA = [
	icode in { IRRMOVQ, IOPQ } : valA;
	icode in { IIRMOVQ, IRMMOVQ, IMRMOVQ, IIADDQ } : valC;
	icode in { ICALL, IPUSHQ } : -8;
	icode in { IRET, IPOPQ } : 8;
	# Other instructions don't need ALU
];

## Select input B to ALU
word aluB = [
	icode in { IRMMOVQ, IMRMOVQ, IOPQ, ICALL, 
		      IPUSHQ, IRET, IPOPQ, IIADDQ } : valB;
	icode in { IRRMOVQ, IIRMOVQ } : 0;
	# Other instructions don't need ALU
];

## Set the ALU function
word alufun = [
	icode == IOPQ : ifun;
	1 : ALUADD;
];

## Should the condition codes be updated?
bool set_cc = icode in { IOPQ, IIADDQ };

```

> 注：如果编译遇到了问题，是由于实验文件太老了，有些库更改了，不是你的错，可以上网搜搜解决方案，很简单

**pipe-full.hcl，ncpoy.ys**

> 这一关是要我们优化ncopy.ys这一段汇编代码的性能，这个函数源代码如下：

```
word_t ncopy(word_t *src, word_t *dst, word_t len)
{
    word_t count = 0;
    word_t val;

    while (len > 0) {
    val = *src++;
    *dst++ = val;
    if (val > 0)
        count++;
    len--;
    }
    return count;
}
```

> 无非就是把数组克隆，并计算该数组中大于0的个数
>
> 首先修改pipe-full.hcl，让我们得到iaddq这个指令，然后就考虑如何进行优化
>
> 1. 首先一个优化就是可以使用iaddq一步代替了之前先把立即数移入寄存器，然后使用addq的两步操作
> 2. 然后就是移动一些操作的位置，比如把一些循环里的赋值给拿出来，这个可能对C语言里的循环里的strlen()这样的函数有用，但其实对汇编语言作用不大，因为都是单条指令
> 3. 重头戏是循环展开，就现在不展开来看，每次循环都需要%rsi与%rdi的指针+1移动，还有一些不必要的运算，因此循环展开是解决指针频繁移动的好方法
> 4. 在循环展开中要注意一个很重要的点，就是注意到这两条指令是连在一起的，在上一条指令执行到execute stage时候，下一条指令就执行到了decode stage，这时候上一条指令需要等待memory stage才会把值存入%r10，而下一条指令在decode stage就会读取%r10，这时候就需要等待，造成性能损失，因此需要隔开这两条指令。

```
mrmovq (%rdi), %r10	
rmmovq %r10, (%rsi)
```

> 5. 优化条件跳转准确率，这是拿高分的重要手段，由于条件跳转很多，而跳转预测失败会损失两个循环，是很大的性能损失，因此要想办法提高一下准确率
> 6. 我自己是展开了六层，注释特别详细，想看可以看看，分数是35左右，不太高，主要是展开的不太细，最后剩下的小于6的情况也没有继续展开，而是直接循环解决了，如果想拿高分，可以看看[csapp-Archlab | Little csd's blog](https://littlecsd.net/2019/01/18/csapp-Archlab/)这篇文章，他是是八层循环展开，其实展开思路差不多，只是他是一次开两层，然后还有当个数小于8时也展开了，相当于这里也做了4、5步的优化，在数组数量小时很管用。我跑了一下，好像可以跑到56分，确实细致，我就懒得改了。下面是我自己的代码，思路都是前5步优化

```
#/* $begin ncopy-ys */
##################################################################
# ncopy.ys - Copy a src block of len words to dst.
# Return the number of positive words (>0) contained in src.
#
# Include your name and ID here.
#
# Describe how and why you modified the baseline code.
#
##################################################################
# Do not modify this portion
# Function prologue.
# %rdi = src, %rsi = dst, %rdx = len
ncopy:

##################################################################
# You can modify this portion
	# Loop header
	xorq	%rax,%rax		# count = 0
	andq	%rdx,%rdx		# len <= 0?
	jle		Done			# if so, goto Done
	jmp 	test

# len > 6
Loop1:
	mrmovq	(%rdi), %r8		# read val from src...
	mrmovq	8(%rdi), %r9	# read val from src...
	mrmovq	16(%rdi), %r10	# read val from src...
	andq	%r8, %r8		# val <= 0?
	rmmovq	%r8, (%rsi)		# ...and store it to dst
	rmmovq	%r9, 8(%rsi)	# ...and store it to dst
	rmmovq	%r10, 16(%rsi)	# ...and store it to dst
	jle 	Loop2			# if so, goto Loop2
	iaddq	$1, %rax		# count++

Loop2:
	andq	%r9, %r9		# val <= 0?
	jle 	Loop3			# if so, goto Loop3
	iaddq	$1, %rax		# count++

Loop3:
	andq	%r10, %r10		# val <= 0?
	jle 	Loop4			# if so, goto Loop4
	iaddq	$1, %rax		# count++

Loop4:
	mrmovq	24(%rdi), %r8	# read val from src...
	mrmovq	32(%rdi), %r9	# read val from src...
	mrmovq	40(%rdi), %r10	# read val from src...
	andq	%r8, %r8		# val <= 0?
	rmmovq	%r8, 24(%rsi)	# ...and store it to dst
	rmmovq	%r9, 32(%rsi)	# ...and store it to dst
	rmmovq	%r10, 40(%rsi)	# ...and store it to dst
	jle 	Loop5			# if so, goto Loop5
	iaddq	$1, %rax		# count++

Loop5:
	andq	%r9, %r9		# val <= 0?
	jle 	Loop6			# if so, goto Loop6
	iaddq	$1, %rax		# count++

Loop6:
	iaddq	$48, %rdi		# src + 6
	iaddq	$48, %rsi		# dst + 6
	andq	%r10, %r10		# val <= 0?
	jle 	test			# if so, goto test
	iaddq	$1, %rax		# count++

test:
	iaddq	$-6, %rdx		# len - 6
	jg		Loop1
	iaddq	$6, %rdx		# len + 6

# len <= 6
Loop:
	mrmovq	(%rdi), %r9		# read val from src...
	rmmovq	%r9, (%rsi)		# ...and store it to dst
	andq	%r9, %r9		# val <= 0?
	jle 	Npos			# if so, goto Npos:
	iaddq	$1, %rax		# count++

Npos:
	iaddq	$8, %rdi		# src++
	iaddq	$8, %rsi		# dst--
	iaddq	$-1, %rdx		# len--
	jg 		Loop			# if so, goto Loop

##################################################################
# Do not modify the following section of code
# Function epilogue.
Done:
	ret
##################################################################
# Keep the following label at the end of your function
End:
#/* $end ncopy-ys */

```





