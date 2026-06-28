# 1-datalab

需要实验资料的，可以上作者的GitHub仓库[mysakuratree]([mysakuratree/csapp (github.com)](https://github.com/mysakuratree/csapp))下载。

### 1 简要分析

> 实验目的：熟悉位级操作
>
> 工作空间：只需要修改bits.c文件
>
> 实验内容：在bits.c文件中，有13个问题，我们需要使用! ˜ & ˆ | + << >>这些位级操作来实现这13个难题
>
> 构建工具：
>
> 1. make
> 2. ./btest检验正确性
> 3. ./dlc bits.c检验是否符合限制
> 4. ./driver.pl检验得分
>
> 限制：
>
> 1. 限制步数
> 2. 不可使用大于8位的常数

### 2 具体实现

**2.1 bitXor**

> 问题：实现异或x^y
>
> 思路：略

```
int bitXor(int x, int y) {
  return ~(x & y) & ~(~x & ~y);
}
```

**2.2 tmin**

> 问题：返回最小有符号整数
>
> 思路：把1算数左移31位

```
int tmin(void) {
  return (0x1 << 31); // 0x80000000
}
```

**2.3 isTmax**

> 问题：判断是否是最大有符号整数0x70000000
>
> 思路：只有两个特殊的值，最大整数0x70000000和-1，在+1后符号位会改变，也只有这两个数在+1后按位取反会得到自己，但不能用==操作，于是可以使用一个特性：自己与自己异或可以得到0

```
int isTmax(int x) {
  int mask = !!(x+1); // not -1
  return !((~(x+1))^x)&mask;
}
```

**2.4 addOldBits**

> 问题：判断是否所有偶数位都为1
>
> 思路：首先用0xAAAAAAAA和&操作取出所有偶数位，然后异或0xAAAAAAAA，如果等于0就符合

```
int allOddBits(int x) {
  int mask = 0xAA + ((0xAA) << 8); //0x0000AAAA
  mask = mask + (mask << 16); //0xAAAAAAAA
  return !((x & mask) ^ mask);
}
```

**2.5 negate**

> 问题：取负
>
> 思路：略

```
int negate(int x) {
  return ~x + 1;
}
```

**2.6 isAsciiDigit**

> 问题：判断是不是ASCII码中的数字，即0x30 <= x <= 0x39，这是0到9在ASCII码中的表示
>
> 思路：0x30：0011 0000，0x39：0011 1001。因此前28位必须为0x00000030，然后满足最后4位在0到9之间即可，即判断9+最后4位数字后是否>0即可

```
int isAsciiDigit(int x) {
  return (!((x >> 4) ^ 0x3))&(!((0x9 + (~(x & 0xF) + 1)) >> 31));
}
```

**2.7 conditional**

> 问题：实现三元运算x ? y : z
>
> 思路：首先根据x是否为真构建mask，在x为真时mask=0x11111111，在x为假时mask=0，然后用mask屏蔽y或z即可

```
int conditional(int x, int y, int z) {
  int flag = ~(!!x) + 1; //true: mask=0x11111111, false: mask=0
  return (flag & y) + (~flag & z);
}
```

**2.8 isLessOrEqual**

> 问题：x<=y则返回1，否则返回0，即造一个<=运算符
>
> 思路：首先判断是否相等，然后判断符号位是否相同，不同的话可以根据符号位判断，相同的话可以根据相减，然后判断符号位来判断

```
int isLessOrEqual(int x, int y) {
  int flag_x = (x >> 31) & 1;
  int flag_y = (y >> 31) & 1;
  int equal = !(x ^ y);
  int flag = flag_x ^ flag_y; // flag is or not equal
  int less = ((x + (~y + 1)) >> 31) & 1; // flag equal, then judge by flag after -
  return (flag & (flag_x & (!flag_y))) | (!flag & less) | equal;
}
```

**2.9 logicalNeg**

> 问题：实现逻辑非
>
> 思路：只有0 | -0 = 0，其符号位为0，其他所有的数x，-x | x的符号位都等于1

```
int logicalNeg(int x) {
  return (((~x + 1) | x) >> 31) + 1; // only 0 | -0 = 0
}
```

**2.10 howManyBits**

> 问题：返回最小需要多少位才能表示x
>
> 思路：正数：从右向左找到最左边的1；负数：从右向左最左边的0。最后在加一位符号位即可。负数的找法可以将负数按位取反，然后找法就和正数一样了，找最高位1。因此只考虑正数找法：x在[0,1]需要2位，x在[2,3]需要3位，x在[4,7]需要4位...以此类推。

```
int howManyBits(int x) {
  int b16,b8,b4,b2,b1,b0;
  int flag = x >> 31;
  x = (flag & ~x)|(~flag & x); // x >=0: not change; x < 0: x = ~x
  b16 = !!(x >> 16) << 4; // x's upper 16 bit = 0 or != 0
  x >>= b16; // b16 = 16 or b16 = 0
  b8 = !!(x >> 8) << 3;
  x >>= b8;
  b4 = !!(x >> 4) << 2;
  x >>= b4;
  b2 = !!(x >> 2) << 1;
  x >>= b2;
  b1 = !!(x >> 1);
  x >>= b1;
  b0 = x;
  return b0+b1+b2+b4+b8+b16+1;
}
```

**接下来时float区，解封了unsigned int类型、if else和while等操作，这三道题需要对浮点数有较好的掌握和理解**

**2.11 floatScale2**

> 问题：浮点数乘2
>
> 思路：首先取出sign，exp和frac，然后需要判断exp是否等于0或者是否等于255，如果等于0则表示非规格化数，那么操作frac；如果等于255，则在乘2操作后不论frac是什么，结果都是NaN，直接返回，如果不等于0或255，则exp+1即可

```
unsigned floatScale2(unsigned uf) {
  unsigned sign = uf >> 31 & 0x1;
  unsigned exp = (uf & 0x7F800000) >> 23;
  unsigned frac = uf & 0x7FFFFF;

  if (exp == 0xFF)
  {
    return uf;
  }
  else if (exp == 0)
  {
    frac <<= 1;
  }
  else
  {
    exp++;
  }
  return (sign << 31) | (exp << 23) | frac;
}
```

**2.12 floatFloat2Int**

> 问题：将浮点数强转为整数
>
> 思路：首先判断指数E（exp - bisa），E<0则说明是个纯小数，返回0；E>=31则说明是个超出整数表达范围的数，那么按照题目要求，返回0x80000000u.如果在整数表示范围，则简单移位并舍去小数部分即可

```
int floatFloat2Int(unsigned uf) {
  unsigned sign = uf >> 31 & 0x1;
  unsigned exp = (uf & 0x7F800000) >> 23;
  unsigned frac = uf & 0x7FFFFF;
  int E = exp-127;

  if (E < 0)
  {
    return 0;
  }
  else if (E >= 31)
  {
    return 0x80000000u;
  }
  else
  {
    frac = frac | (1 << 23); // add the omitted 1
    if (E < 23) // exist decimal, omit decimal
    {
        frac >>= (23 - E);
    }
    else // not exist decimal
    {
        frac <<= (E - 23);
    }
  }
  if (sign) // neg
  {
    frac = -frac;
  }
  return frac;
}
```

**2.13 floatPower2**

> 问题：返回2^x，即2的x次方
>
> 思路：符号位和尾数全为0，只需要考虑阶码，阶码范围为-126到127，判断一下即可

```
unsigned floatPower2(int x) {
  int exp = x + 127;
  if (exp < 0)
  {
    return 0;
  }
  else if(exp > 255)
  {
    return 0x7F800000; 
  }
  return exp << 23; 
}
```

> 闲话：csapp的9个实验都很精彩，但也折磨人，其实作者是写完了九个实验再来写writeup的，作者写writeup是煞费苦心，作者这么辛苦了，所以后面的实验慢慢更。



