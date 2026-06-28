package org.example.ClassDemo1.包装类;

import java.math.BigDecimal;

public class Demo1 {
    public static void main(String[] args) {
        BigDecimal bigDecimal = new BigDecimal("0.1");
        BigDecimal bigDecimal1 = new BigDecimal("0.2");
        BigDecimal add = bigDecimal1.add(bigDecimal);
        double v = add.doubleValue();
        System.out.println(v);
    }
}
/*
Java 为 8 种基本数据类型都提供了对应的包装类：

基本数据类型	包装类
byte	Byte
short	Short
int	Integer
long	Long
float	Float
double	Double
char	Character
boolean	Boolean
2. 包装类的作用
对象化操作：可以将基本数据类型当作对象来处理，比如添加到集合中。
泛型支持：在泛型中只能使用对象类型，像 List<Integer> 这样的泛型集合。
空值处理：包装类可以赋值为 null，这在需要表示缺失值的场景中很有用。
类型转换：提供了将字符串转换为基本数据类型的方法，例如 Integer.parseInt()。*/
/*
二、自动装箱（Autoboxing）
        1. 定义
自动装箱是指 Java 编译器在基本数据类型和对应的包装类对象之间进行的自动转换。例如，把 int 转换为 Integer，把 double 转换为 Double 等。

java
int num = 10;
Integer obj = num; // 自动装箱：等价于 Integer obj = Integer.valueOf(num);
2. 实现机制
自动装箱实际上是通过调用包装类的 valueOf() 方法来实现的：

        Integer.valueOf(int)
Double.valueOf(double)
Boolean.valueOf(boolean)
3. 示例
        java
List<Integer> list = new ArrayList<>();
list.add(10); // 自动装箱：int -> Integer
list.add(20); // 自动装箱：int -> Integer

// 内部实现等价于：
list.add(Integer.valueOf(10));
        list.add(Integer.valueOf(20));*/

/*
三、自动拆箱（Unboxing）
        1. 定义
自动拆箱则是指将包装类对象自动转换为基本数据类型。例如，把 Integer 转换为 int，把 Double 转换为 double 等。

java
Integer obj = 10;
int num = obj; // 自动拆箱：等价于 int num = obj.intValue();
2. 实现机制
自动拆箱是通过调用包装类的 xxxValue() 方法来实现的：

intValue()
doubleValue()
booleanValue()
3. 示例
        java
List<Integer> list = Arrays.asList(1, 2, 3);
int sum = 0;
for (Integer num : list) {
sum += num; // 自动拆箱：Integer -> int
}
// 内部实现等价于：
sum += num.intValue();
自动装箱和拆箱是 Java 提供的语法糖，它让基本数据类型和包装类之间的转换更加便捷。不过，在使用过程中要注意缓存机制、空指针异常以及性能开销等问题*/
