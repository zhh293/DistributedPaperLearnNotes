package org.example.ClassDemo1.函数式编程;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class Stream {
    public static void main(String[] args) {
        //过滤操作，六六六。。。
        java.util.stream.Stream.of(1,2,3,4,5,6,7,8,9)
                .filter(
                    integer->
                         integer>=5
                ).forEach(System.out::println);
        //map映射
        List<Integer> collect = java.util.stream.Stream.of(1, 2, 3, 4, 5, 6, 7, 8, 9)
                .map(new Function<Integer, Integer>() {
                    @Override
                    public Integer apply(Integer integer) {
                        return integer;
                    }
                }).collect(Collectors.toList());
        System.out.println(collect);

        //降维，二维数组改为一维数组
        java.util.stream.Stream.of(List.of(1,2,3,4,5,6,7,8,9),List.of(11,12,13,14,15))
                .flatMap(new Function<List<Integer>, java.util.stream.Stream<?>>() {
                    @Override
                    public java.util.stream.Stream<?> apply(List<Integer> integers) {
                        return integers.stream();
                    }
                }).forEach(System.out::println);



        Integer[][]arr={
                {1,2,3,4,5,6,7,8,9},
                {11,12,13,14,15},
                {1,2,3,4,5,6,7,8,9},
        };
        Arrays.stream(arr).flatMap(new Function<Integer[], java.util.stream.Stream<?>>() {
            @Override
            public java.util.stream.Stream<?> apply(Integer[] integers) {
                return Arrays.stream(integers);
            }
        }).forEach(System.out::println);

    }
}
