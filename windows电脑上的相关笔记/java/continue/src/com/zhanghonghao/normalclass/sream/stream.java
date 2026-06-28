package com.zhanghonghao.normalclass.sream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class stream {
    public static void main(String[] args) {
       /* ArrayList<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.stream().filter(name->name.equals("a")).forEach(System.out::println);*/
        /*List<Integer>list= Arrays.asList(1,2,3,4,5,6,7,8,9);
        list.stream().map(x->{
            return x*2;
        }).forEach(System.out::println);*/
        List<String> names = Arrays.asList("alice", "bob", "charlie");
        Stream<String> stringStream = names.stream().map(name -> {
            String upperCase = name.toUpperCase();
            return upperCase;
        });
        stringStream.forEach(Big->{
            System.out.println(Big);
        });

    }
}
/*
2. Stream 的 map 方法是干啥的？—— 流水线加工厂
一句话解释：
map 方法就像工厂里的 “流水线机器”，它会对 Stream 中的每个元素 执行 相同的转换操作，然后返回一个包含新元素的 Stream。

生活例子：
假设你是奶茶店老板，有一批 “原味奶茶”（Stream 中的元素），你想把它们都变成 “珍珠奶茶”。这时就可以用 map 方法：

map 方法：相当于流水线机器。
转换规则：往原味奶茶里加珍珠（用 Lambda 表达式定义）。
输出结果：得到一个新的 Stream，里面全是 “珍珠奶茶”。
        3. map 方法的语法和参数
java
Stream<R> map(Function<? super T, ? extends R> mapper)


参数：
mapper：一个 Function 函数式接口，定义了 “如何把一个元素转换成另一个元素”。
通常用 Lambda 表达式 来实现这个 Function，比如：x -> x * 2。
返回值：
一个新的 Stream，包含转换后的元素。*/
/*为什么要用 map 方法？—— 比循环更优雅！
不用 map 的写法（传统 for 循环）：

java
List<Integer> numbers = Arrays.asList(1, 2, 3, 4);
List<Integer> result = new ArrayList<>();

for (Integer n : numbers) {
        result.add(n * 2);
}

        System.out.println(result); // 输出：[2, 4, 6, 8]

用 map 的写法：

java
List<Integer> numbers = Arrays.asList(1, 2, 3, 4);
List<Integer> result = numbers.stream()
        .map(n -> n * 2)
        .toList();

System.out.println(result); // 输出：[2, 4, 6, 8]



对比优点：

代码更简洁：省掉了循环和集合初始化的样板代码。
更易读：直接表达 “我要对每个元素做什么”，而不是 “我要怎么遍历集合”。
支持链式操作：map 之后可以继续接其他 Stream 方法（如 filter、reduce 等），形成复杂的数据处理流水线。*/
//大彻大悟，原来还有这么优美的方法，噢噢噢噢哦哦哦
