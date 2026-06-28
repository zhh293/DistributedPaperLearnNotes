# 6-perflab

**首先，我要吐槽这个实验，在5-cachelab中part B其实把cache优化的很好了，而这个perflab其实主要优化点还是在cache这里，特别是分块，其他新增的无非就是一些代码移动，降低内存引用之类的小优化，其实做起来没有太大感觉，感觉学不到特别多知识，我写的也较粗糙**

### 1 简要分析

> 实验目的：学会优化程序性能
>
> 工作空间：kernels.c
>
> 实验内容：优化C语言程序性能，具体有：
>
> 1. 代码移动
> 2. 减少内存引用
> 3. 循环展开
> 4. 分块

### 2 具体实现

**1 rotate**

> 我做了以下优化：
>
> 1. 移动了一些循环体内的代码
> 2. 进行了一个8×8的分块
>
> 最后分数是15.5左右（他这个分数每次测试的还不一样）

```
void rotate(int dim, pixel *src, pixel *dst) 
{
    for (int i = 0; i < dim; i += 8){
        for (int j = 0; j < dim; j += 8){
            for (int p = i; p < i+8; p++){
                int s = p*dim;
                for (int q = j; q < j+8; q++){
                    dst[RIDX(dim-1-q, p, dim)] = src[s+q];
                }
            }
        }
    }
}
```

**2 smooth**

> 我只做了8×8循环展开，其实还可以循环展开之类的，由于在cachelab里已经做过了，我没继续做。但我试过用多线程或者多进程来运行，但好像有点背离实验了，只能说这个实验有点粗糙
>
> 最后分数是16.5左右

```
void smooth(int dim, pixel *src, pixel *dst) 
{
    for (int i = 0; i < dim; i+=8)
    {
        for (int j = 0; j < dim; j+=8)
        {
            for (int p = i; p < i+8; p++)
            {
                for (int q = j; q < j+8; q++)
                {
                    dst[RIDX(p, q, dim)] = avg(dim, p, q, src);
                }
            }
        }
    }
}
```

