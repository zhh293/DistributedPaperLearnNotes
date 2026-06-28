# 5-cachelab

### 1 简要分析

> 实验目的：熟悉cache，学会根据cache优化程序执行速度
>
> 工作空间：csim.c和trans.c
>
> 实验内容：
>
> 1. part A：在csim.c下面编写一个缓存模拟器来模拟cache的行为，并且规定该模拟器用LRU替换策略，即替换某组中最后一次访问时间最久远的一块，还要支持一些输入可选参数
>
>    操作有四种：
>
>    - I：加载指令
>    - L：加载数据
>    - S：存储数据
>    - M：修改数据
>
>    反馈有三种：
>
>    - hit：命中
>    - miss：不命中
>    - eviction：替换
>
> 2. part B：在trans.c中根据cache的原理优化矩阵的转置这一操作

### 2 具体实现

**1 csim.c**

**注：本段代码来自[CSAPP | Lab5-Cache Lab 深入解析 - 知乎 (zhihu.com)](https://zhuanlan.zhihu.com/p/484657229)，写得好，本文思路要更清晰，但代码确实不是我手敲的，哈哈哈，我没有能力敲得这么好**

> 首先要清楚cache的结构：
>
> 1. cache一般分为S组，每组有E块
> 2. 每块结构为一个有效位v，一个标志位tag，一个数据块data
> 3. cache地址分为三部分：标志位tag，组索引s，块偏移b

![csapp5-1](../image/csapp5-1.png)

> 然后我们想，cache的运作方式是怎样的：
>
> 1. 有一个cache地址，这个cache地址提供了标志tag，组索引s和块偏移b
> 2. 首先根据组索引找到组
> 3. 然后遍历该组所有块，如果块有效位v为1，则用tag与该块的tag比较，如果相同，那就hit，完匹配
> 4. 如果遍历完组中所有块，没有hit，那么就miss，这时就需要唤醒替换，把需要的块替换进来
> 5. 这里替换有一种特殊情况需要判断，就是组满了，组中每一块valid都为1，那么我们要根据LRU策略，选一块最近访问时间最久远的块替换掉
>
> 这就是cache的运作机制，实现架构为

![csapp5-2](../image/csapp5-2.png)

> 那开始我们的代码：
>
> 不管其他什么，我们都需要有一个cache结构，开始构建cache模拟器：
>
> 1. 定义一个cache块，这里我们按照题目中提示的时间戳方式来实现LRU策略

```
typedef struct cache_block
{
    int valid;
    int tag;
    int time_tamp;
} Cache_block;
```

> 2. 然后定义整个cache。S，E，B分别代表组数，每组块数，每块数据位的字节数

```
typedef struct cache
{
    int S;
    int E;
    int B;
    Cache_block **block;
} Cache;
```

> 3. 现在初始化一个cache，这个初始化主要就是申请一个S组，每组E块（一般行和块相等，行是cache的概念，块是主存的概念，我比较喜欢两个都叫块），每块数据B字的cache，然后设置每块的有效位0，标志位-1，时间戳0

```
void initCache(int s, int E, int b)
{
    int S = 1 << s;
    int B = 1 << b;
    cache = (Cache *)malloc(sizeof(Cache));
    cache->S = S;
    cache->E = E;
    cache->B = B;
    cache->block = (Cache_block **)malloc(sizeof(Cache_block *) * S);
    for (int i = 0; i < S; i++)
    {
        
        cache->block[i] = (Cache_block *)malloc(sizeof(Cache_block) * E);
        for (int j = 0; j < E; j++)
        {
            cache->block[i][j].valid = 0;
            cache->block[i][j].tag = -1;
            cache->block[i][j].time_tamp = 0;
        }
    }
}
```

> 4. 现在我们有了一个cache，根据上面的cache运作机制，我们应当根据cache地址去查找需要的块是否存在，再进行后续操作。该函数遍历该组中所有快，只有v=1且tag相同，那就hit，返回这个块的索引即可。否则miss，且返回-1

```
int getIndex(int op_set, int op_tag) {
    for (int i = 0; i < cache->E; i++)
    {
        if (cache->block[op_set][i].valid && cache->block[op_set][i].tag == op_tag)
        {
            return i;
        }
    }
    return -1;
}
```

> 5. 如果hit，皆大欢喜，啥都不用管。现在我们miss了，那首先，我们要排除是否全满这一特殊情况。该函数实现，如果没有全满，则选第一个有效位为0的块作为替换块，返回该索引，如果满了，那返回-1。

```
int isFull(int op_set) {
    for (int i = 0; i < cache->E; i++)
    {
        if (cache->block[op_set][i].valid == 0)
        {
            return i;
        }
    }
    return -1;
}
```

> 6. 现在，假设没有满，那我们就设置更新策略，更新返回的索引块即可。这里参数op_set代表第几组，i代表组中第几块。然后LRU的实现策略是更新的这一块的时间戳设置为0，同组中其他有效位位1的块时间戳+1，这样做代表该组中时间戳最大的块为最久没有访问的块，为后续满的情况铺垫

````
void update(int i, int op_set, int op_tag)
{
    cache->block[op_set][i].valid = 1;
    cache->block[op_set][i].tag = op_tag;
    for (int k = 0; k < cache->E; k++)
    {
        if (cache->block[op_set][k].valid == 1)
        {
            cache->block[op_set][k].time_tamp++;
        }
    }
    cache->block[op_set][i].time_tamp = 0;
}
````

> 7. 如果满了，那根据上述测实现策略，可以得到LRU策略函数，查找某组中最久没访问的就是找有效位为1的哪一块时间戳最大，查找到该组的索引返回即可，然后再调用update

```
int findLRU(int op_set)
{
    int max_index = 0;
    int max_stamp = 0;
    for (int i = 0; i < cache->E; i++)
    {
        if (cache->block[op_set][i].time_tamp > max_stamp)
        {
            max_stamp = cache->block[op_set][i].time_tamp;
            max_index = i;
        }
    }
    return max_index;
}
```

> 8. 最后组装一下update，isfull，findurl三个函数为updateinfo函数即可

```
void updateInfo(int op_set, int op_tag) {
    int index = getIndex(op_set, op_tag);
    if (index == -1)
    {
        miss_count++;
        if (verbose)
        {
            printf("miss ");
        }
        int i = isFull(op_set);
        if (i == -1)
        {
            eviction_count++;
            if (verbose)
            {
                printf("eviction\n");
            }
            i = findLRU(op_set);
        }
        update(i, op_set, op_tag);
    }
    else {
        hit_count++;
        if (verbose)
        {
            printf("hit\n");
        }
        update(index, op_set, op_tag);
    }
}
```

> 9. free，申请了动态内存记得free一下。free内存不是指直接调用一个free函数就行了，这样会生成一个野指针，需要把该指针指向NULL，成为一个空指针

```
void freeCache() {
    free(cache);
    cache = NULL;
}
```

> 10. 最后一个函数是根据四种操作分别实现一下

```
void getTrace(int s, int E, int b) {
    FILE *p_file;
    p_file = fopen(t, "r");
    if (p_file == NULL)
    {
        exit(-1);
    }
    char identifier;
    unsigned address;
    int size;

    while (fscanf(p_file, " %c %x,%d", &identifier, &address, &size) > 0)
    {
        int op_tag = address >> (s + b);
        int op_set = (address >> b) & ((unsigned)(-1) >> (8 * sizeof(unsigned) - s));
        switch (identifier)
        {
            case 'M':
                updateInfo(op_set, op_tag);
                updateInfo(op_set, op_tag);
                break;
            case 'L':
                updateInfo(op_set, op_tag);
                break;
            case 'S':
                updateInfo(op_set, op_tag);
                break;
        }
    }
    fclose(p_file);
}
```

> 11. 主函数，需要解析一下所有的输入参数

```
#include "cachelab.h"
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>

typedef struct cache_block
{
    int valid;
    int tag;
    int time_tamp;
} Cache_block;

typedef struct cache
{
    int S;
    int E;
    int B;
    Cache_block **block;
} Cache;

Cache *cache = NULL;
int miss_count = 0;
int hit_count = 0;
int eviction_count = 0;
int verbose = 0;
char t[50];
extern char *optarg;

void initCache(int s, int E, int b);
void update(int i, int op_set, int op_tag);
int findLRU(int op_set);
int getIndex(int op_set, int op_tag);
int isFull(int op_set);
void updateInfo(int op_set, int op_tag);
void getTrace(int s, int E, int b);
void freeCache();

int main(int argc, char **argv)
{
    char opt;
    int s, E, b;

    while (-1 != (opt = getopt(argc, argv, "hvs:E:b:t:")))
    {
        switch (opt)
        {
            case 'h':
                printf("input format wrong");
                exit(0);
            case 'v':
                verbose = 1;
                break;
            case 's':
                s = atoi(optarg);
                break;
            case 'E':
                E = atoi(optarg);
                break;
            case 'b':
                b = atoi(optarg);
                break;
            case 't':
                strcpy(t, optarg);
                break;
            default:
                printf("input format wrong!");
                exit(-1);
        }
    }
    initCache(s, E, b);
    getTrace(s, E, b);
    freeCache();
    printSummary(hit_count, miss_count, eviction_count);
    return 0;
}
```

**2 trans.c**

> 没有安装valgrind的先安装一下

```
sudo apt install valgrind
```

> 优化矩阵转置算法，满分要求如下：
>
> 1. 32×32：miss < 300
> 2. 64×64：miss < 1300
> 3. 61×67：miss < 2000
>
> 我们先暴力转置一下，两个for循环怼上，miss有1183，太多了

```
void transpose_submit(int M, int N, int A[N][M], int B[M][N])
{
    int i, j, tmp;

    for (i = 0; i < N; i++) {
        for (j = 0; j < M; j++) {
            tmp = A[i][j];
            B[j][i] = tmp;
        }
    }
}
```

> 接下来想想优化，注意，我们这里需要优化的不是程序运行的时间，而是cache的命中率，也就是说像常见的把一些代码从循环中移出之类的优化在这里没有意义。
>
> **32×32**：分析一下：给出的信息是(s=5, E=1, b=5)，s=5，也就是说缓存有32个组，E=1，即每组一块，b=5，也就是说每一块数据位存储了32个字节，也就是8个int
>
> 1. 对于A来说，A是顺序访问的，每一块8个int会有1个导致miss，剩下7个hit，不用对A的访问进行操作
> 2. **主要是B。首先我们要清楚，这里缓存用的是一路组相联，组数为32，也就是说组索引最大为32，到33的话就会覆盖第一个组的块。B是存储在内存中的，这里是32×32的int矩阵大小，按照一路组相联，在内存中每一行32个int数，也就是对应了4个缓存块（32/8），由于是一路组相联，也就是对应了4个组。那么在内存B中每8行就会耗空所有的组索引，如果直接竖着写，写到内存B第9行时，组索引用完了，他就会去覆盖第1组的块，这才是组相联的映射规律，当然就会导致miss**
> 3. 那么A在读完一个缓存块的8个int后，会导致B不命中8个缓存块，B会修改这8个缓存块的每一个的第一个int（4字节），如果我们不优化，那这8个缓存块的剩下7个int就没用到，由于组索引耗空，到第9行就覆盖了第一组里面的块，那就gg
> 4. 因此我们展开循环，A每读一个块的8个int后，我们竖着读下一个块，一共竖着读8个块，这样就能让B修改那8个缓存块的后7个int，并且正好能够完全利用契合组索引（其实在这里是巧合，不一定你的组索引数一定能契合你的数据块数，比如下面的64×64）
>
> miss达到了287，好好好

```
void transpose_submit(int M, int N, int A[N][M], int B[M][N])
{
    int i, j, k;
    int tmp_0, tmp_1, tmp_2, tmp_3, tmp_4, tmp_5, tmp_6, tmp_7;

    for (i = 0; i < N; i+=8) {
        for (j = 0; j < M; j+=8) {
            for (k = i; k < i+8; k++) {
                tmp_0 = A[k][j];
                tmp_1 = A[k][j+1];
                tmp_2 = A[k][j+2];
                tmp_3 = A[k][j+3];
                tmp_4 = A[k][j+4];
                tmp_5 = A[k][j+5];
                tmp_6 = A[k][j+6];
                tmp_7 = A[k][j+7];
                B[j][k] = tmp_0;
                B[j+1][k] = tmp_1;
                B[j+2][k] = tmp_2;
                B[j+3][k] = tmp_3;
                B[j+4][k] = tmp_4;
                B[j+5][k] = tmp_5;
                B[j+6][k] = tmp_6;
                B[j+7][k] = tmp_7;
            }
        }
    }
}
```

> **64×64**：根据上面32×32我们得知，当内存每行变为64个int时，也就是每行对应了8个缓存块，那内存B中的4行就会耗尽组索引，所以靠8×8展开是不行的，因为第5行就会导致组索引耗空不命中
>
> 1. 我们先改为4×4展开，来满足组索引，这时miss来到了1699，接近满昏了
> 2. 那继续优化的点在哪里，就是cache块没有完全利用，我们的cache有8个int啊，4×4展开肯定不能完全利用，那我们还是可以用8×8展开，只不过8×8中再4×4，具体是每一个8×8块中，分别移动左上，右上，左下，右下的4×4块，说白了就是套两层，8×8整体移动，只不过8×8中不是直接用，而是分四次4×4的搬运即可，miss来到了1179

```
void transpose_submit(int M, int N, int A[N][M], int B[M][N])
{
    int i, j, k;
    int tmp_1, tmp_2, tmp_3, tmp_4, tmp_5, tmp_6, tmp_7, tmp_8;

    for (i = 0; i < N; i += 8) {
        for (j = 0; j < M; j += 8) {  
            for (k = 0; k < 4; i++, k++) {
                tmp_1 = A[i][j];
                tmp_2 = A[i][j+1];
                tmp_3 = A[i][j+2];
                tmp_4 = A[i][j+3];
                tmp_5 = A[i][j+4];
                tmp_6 = A[i][j+5];
                tmp_7 = A[i][j+6];
                tmp_8 = A[i][j+7];

                B[j][i] = tmp_1;
                B[j+1][i] = tmp_2;
                B[j+2][i] = tmp_3;
                B[j+3][i] = tmp_4;
                B[j][i+4] = tmp_5;
                B[j+1][i+4] = tmp_6;
                B[j+2][i+4] = tmp_7;
                B[j+3][i+4] = tmp_8;
            }
            i -= 4;

            for (k = 0; k < 4; j++, k++) {
                tmp_1 = A[i+4][j];
                tmp_2 = A[i+5][j];
                tmp_3 = A[i+6][j];
                tmp_4 = A[i+7][j];
                tmp_5 = B[j][i+4];
                tmp_6 = B[j][i+5];
                tmp_7 = B[j][i+6];
                tmp_8 = B[j][i+7];

                B[j][i+4] = tmp_1; 
                B[j][i+5] = tmp_2;
                B[j][i+6] = tmp_3;
                B[j][i+7] = tmp_4;
                B[j+4][i] = tmp_5;
                B[j+4][i+1] = tmp_6;
                B[j+4][i+2] = tmp_7;
                B[j+4][i+3] = tmp_8;
            }
            j -= 4;
            j += 4;

            for (i += 4, k = 0; k < 4; i++, k++) {
                tmp_1 = A[i][j];
                tmp_2 = A[i][j+1];
                tmp_3 = A[i][j+2];
                tmp_4 = A[i][j+3];

                B[j][i] = tmp_1;
                B[j+1][i] = tmp_2;
                B[j+2][i] = tmp_3;
                B[j+3][i] = tmp_4;
            }
            i -= 8;
            j -= 4;
        }
    }
}
```

> **61×67**：这个要求很松，只需要2000即可，分块展开即可，测试后好像展开为16×16即可

