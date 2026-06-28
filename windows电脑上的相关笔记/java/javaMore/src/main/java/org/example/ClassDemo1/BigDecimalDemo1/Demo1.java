package org.example.ClassDemo1.BigDecimalDemo1;

import java.math.BigDecimal;

public class Demo1 {
    public static void main(String[] args) {
        System.out.println(0.1 + 0.2);
        BigDecimal bigDecimal = BigDecimal.valueOf(0.1);
        BigDecimal bigDecimal2 = BigDecimal.valueOf(0.2);
        BigDecimal result = bigDecimal.add(bigDecimal2);
        BigDecimal bigDecimal1 = result.setScale(2);
        double v1 = bigDecimal1.doubleValue();
        System.out.println(v1);
        double v = result.doubleValue();//一般这种类型才是我们想要的。。。。。
        System.out.println(v);
        System.out.println(result);
    }
}
/*
一、存在意义：为什么需要 BigDecimal？
        1. 浮点数精度问题
float 和 double 类型在进行二进制浮点运算时，会出现精度丢失的情况，这在金融计算中是绝对不能接受的。

java
double a = 0.1;
double b = 0.2;
System.out.println(a + b); // 输出 0.30000000000000004

这是因为二进制无法精确表示像 0.1 这样的十进制小数。
        2. 高精度计算需求
在金融领域，哪怕是极小的精度误差，经过多次计算累积后，都可能导致严重的财务错误，所以必须保证计算结果的精确性。
二、核心细节
1. 不可变性（Immutable）
BigDecimal 对象一旦创建，其值就不能被修改。任何对 BigDecimal 的运算操作，比如加法、乘法等，都会返回一个新的 BigDecimal 对象。

java
BigDecimal a = new BigDecimal("0.1");
BigDecimal b = new BigDecimal("0.2");
BigDecimal c = a.add(b); // a 和 b 保持不变，c 是新的 BigDecimal 对象
2. 精度与舍入模式
在进行除法等运算时，必须明确指定精度和舍入模式，否则当结果是无限小数时，就会抛出 ArithmeticException 异常。

java
BigDecimal a = new BigDecimal("1");
BigDecimal b = new BigDecimal("3");
// 正确写法：指定精度为 2 位小数，舍入模式为四舍五入
BigDecimal result = a.divide(b, 2, RoundingMode.HALF_UP);
System.out.println(result); // 输出 0.33
3. 构造函数的选择
推荐使用 new BigDecimal(String)：这种方式能避免浮点数精度问题。
java
BigDecimal correct = new BigDecimal("0.1"); // 精确表示 0.1

避免使用 new BigDecimal(double)：它会直接保留 double 的二进制表示，从而导致精度问题。
java
BigDecimal incorrect = new BigDecimal(0.1); // 实际存储的是 0.100000000000000055511151231257827021181583404541015625

静态工厂方法 valueOf(double)：它会先将 double 转换为字符串，再创建 BigDecimal，这样可以避免精度问题，是一种较为安全的用法。
java
BigDecimal safe = BigDecimal.valueOf(0.1); // 等价于 new BigDecimal("0.1")

三、常用方法
1. 基本运算
        java
BigDecimal a = new BigDecimal("10.5");
BigDecimal b = new BigDecimal("2.5");

BigDecimal sum = a.add(b);          // 加法：13.0
BigDecimal difference = a.subtract(b); // 减法：8.0
BigDecimal product = a.multiply(b);  // 乘法：26.25
BigDecimal quotient = a.divide(b, RoundingMode.HALF_UP); // 除法：4.2
2. 比较大小
使用 compareTo() 方法，而不是 equals()。equals() 不仅会比较值，还会比较精度，而 compareTo() 只比较值的大小。

java
BigDecimal a = new BigDecimal("1.0");
BigDecimal b = new BigDecimal("1.00");

System.out.println(a.equals(b));      // 输出 false，因为精度不同
        System.out.println(a.compareTo(b) == 0); // 输出 true，因为值相等
        3. 精度控制
        java
BigDecimal num = new BigDecimal("3.14159");
BigDecimal rounded = num.setScale(2, RoundingMode.HALF_UP); // 四舍五入到两位小数：3.14
四、典型应用场景
1. 金融计算
在处理货币金额、利率计算等金融业务时，BigDecimal 是首选。例如：

java
// 计算贷款利息
BigDecimal principal = new BigDecimal("10000"); // 本金
BigDecimal rate = new BigDecimal("0.05");      // 年利率
BigDecimal interest = principal.multiply(rate); // 年利息：500.00

2. 科学计算
在需要高精度的科学研究或工程计算中，BigDecimal 能发挥重要作用。*/
