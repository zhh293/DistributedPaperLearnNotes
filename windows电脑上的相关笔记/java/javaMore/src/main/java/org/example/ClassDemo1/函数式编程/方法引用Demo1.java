package org.example.ClassDemo1.函数式编程;

import org.example.ClassDemo1.反射.Student;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class 方法引用Demo1 {
    public static void main(String[] args) throws Exception {
       /* Stream.of("zhh","zhh1","zhh2").forEach(new Consumer<String>() {
            @Override
            public void accept(String s) {
                System.out.println(s);
            }
        });*/
        Stream.of("zhh","zhh1","zhh2").forEach(System.out::println);
        Stream.of("zhh","zhh1","zhh2").filter(new Predicate<String>() {
            @Override
            public boolean test(String s) {
                return s.startsWith("zhh");
            }
        }).forEach(System.out::println);


        Consumer<String> show = (S) -> System.out.println(S);
        show.accept("zhh");
        //方法引用就是狗屎，我就喜欢lambda，哈哈哈哈哈


    }
    static void show(String name)
    {
        System.out.println(name);
    }
}
//类名::方法名
