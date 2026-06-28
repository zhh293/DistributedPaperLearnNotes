package com.zhanghonghao.Stream;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class method {
    public static void main(String[] args) {
        //limit
        ArrayList<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.stream().limit(2).forEach(new Consumer<String>() {
            public void accept(String s) {
                System.out.println(s);
            }
        });
        //skip
       // list.stream().skip(1).forEach(System.out::println);
        //distinct    元素去重   concat  合并a和b两个流为一个流
        //map  转化流中的数据类型
        ArrayList<String> list1 = new ArrayList<>();
        Collections.addAll(list1, "张无忌-15", "周芷若-14", "赵敏-13", "张强-20", "张三丰-100", "张翠山-40", "张良-35", "王二麻子-37");
        /*list1.stream().map(new Function<String, Integer>() {
            @Override
            public Integer apply(String s) {
                String[] arr = s.split("-");
                String ageString = arr[1];
                int age = Integer.parseInt(ageString);
                return age;
            }
        }).forEach(s -> System.out.println(s));*/
        /*Stream<String> stream = list.stream();
        stream.distinct();*/
        //distinct应该是一个静态方法，ok
        //forEach，遍历，count 统计，toArray   收集流中的数据并且放在数组中
        //long count= list1.stream().count();
        //System.out.println(count);
       String[]arr= list1.stream().toArray(new IntFunction<String[]>() {
            @Override
            public  String[] apply(int value) {
                return new String[value];
            }
        });
       System.out.println(Arrays.toString(arr));
       //collect  收集流中的数据，放在集合当中
        ArrayList<String> list2 = new ArrayList<>();
        List<String> list3 = new ArrayList<>();
        Collections.addAll(list2,"张无忌-男-15","周芷若-女-14","张良-男-35");
          /*list3=list2.stream().filter(new Predicate<String>() {
            public boolean test(String s) {
                String [] arr = s.split("-");
                String s1=arr[1];
                if(s1.equals("男")){
                    return true;
                }
                return false;
            }
        }).collect(Collectors.toList());
          System.out.println(list3);*/
        /*Set set = new HashSet();
        set=list2.stream().filter(new Predicate<String>() {
            public boolean test(String s) {
                String [] arr = s.split("-");
                String s1=arr[1];
                if(s1.equals("男")){
                    return true;
                }
                return false;
            }
        }).collect(Collectors.toSet());
        System.out.println(set);*/
        //收集到set集合当中可以起到去重的作用，用list则不会有这样的作用
        Map<String, Integer> map = new HashMap<>();
        map=list2.stream().filter(new Predicate<String>() {
            public boolean test(String s) {
                String [] arr = s.split("-");
                String s1=arr[1];
                if(s1.equals("男")){
                    return true;
                }
                return false;
            }
        }).collect(Collectors.toMap(new Function<String, String>() {
            public String apply(String s) {
                String[] arr = s.split("-");
                String s1 = arr[0];
                return s1;
            }
        },
                new Function<String, Integer>() {
             public Integer apply(String s) {
                 String[] arr = s.split("-");
                 String s1 = arr[2];
                 int age=Integer.parseInt(s1);
                 return age;
             }
                } ));
        System.out.println(map);
//tomap这个方法有两个参数，参数一表示键的生成规则，参数二表示值得生成规则，这两个参数本质上都是对象
        //第一个参数的function这个接口中有两个泛型，第一个表示流中每一个数据的类型，第二个表示map集合中键的数据类型
//第二个function也有两个泛型，但是第二个为返回值的类型
        //map中的键是不可以重复的，小细节，别忘了








    }
}
