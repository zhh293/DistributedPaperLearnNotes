package org.example.ClassDemo1.函数式编程;

public class 闭包和柯里化 {
    // 闭包
    //什么是闭包
    //闭包就是一个函数，这个函数使用了外部的变量
    //闭包的限制
    //闭包不能有返回值
    //闭包不能有参数
    //闭包的作用
    //闭包可以用来创建函数，函数可以引用外部的变量
}
/*
闭包是一种编程概念，它让函数能够记住并使用其外部作用域里的变量，即便该函数已经脱离了创建它的那个作用域。下面就以 Java 为例，来详细且通俗易懂地讲解闭包。
        1. 变量的作用域
在 Java 里，局部变量仅在定义它的方法内部有效。一旦方法执行完毕，这些局部变量就会随之消失。

java
public class Main {
    public static void main(String[] args) {
        int x = 10; // 局部变量 x
        // 在这里可以使用 x
    } // x 在这里消失
}
2. 闭包的核心作用
闭包的关键在于，它能够让内部函数（或者 lambda 表达式）捕获并使用外部函数的变量，哪怕外部函数已经执行结束。
        3. Java 中闭包的实现方式
在 Java 里，闭包主要通过匿名内部类和lambda 表达式来实现，不过有一个前提条件，那就是被捕获的变量必须是最终变量（final）或者实际上的最终变量（也就是在初始化之后就不会再被修改）。
示例 1：使用匿名内部类
        java
public class ClosureExample {
    public static void main(String[] args) {
        int x = 10; // 实际上的最终变量

        // 创建一个匿名内部类实例
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                System.out.println(x); // 捕获外部变量 x
            }
        };

        x = 20; // 错误！如果取消注释，x 就不再是实际上的最终变量

        runnable.run(); // 输出 10
    }
}
示例 2：使用 lambda 表达式（Java 8+）
java
public class ClosureExample {
    public static void main(String[] args) {
        int x = 10; // 实际上的最终变量

        // Lambda 表达式捕获 x
        Runnable runnable = () -> {
            System.out.println(x); // 捕获外部变量 x
        };

        runnable.run(); // 输出 10
    }
}

4. 闭包的特点
捕获变量的值：闭包捕获的是变量的值，而不是变量本身。就像上面的例子，即便后续尝试修改 x，编译器也会报错。
延长变量的生命周期：闭包会把捕获的变量保存在自己的实例中，这样一来，这些变量的生命周期就得到了延长，不会随着外部函数的结束而消失。


缺点
闭包会持有外部变量的引用，这可能会导致内存泄漏。
如果使用不当，闭包可能会让代码的逻辑变得复杂，降低代码的可读性。
*/
