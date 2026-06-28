package com.zhanghonghao.Stream;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class stream1 {
    public static void main(String[] args) {
        ArrayList<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.add("d");
        list.stream().filter(name->name.equals("a")).filter(name->name.length()==1).forEach(System.out::println);
        ArrayList<String> list1=new ArrayList<>();
        for(String s:list){
            if(s.startsWith("a")){
                list1.add(s);
            }
        }
        ArrayList<String> list2=new ArrayList<>();
        for(String s:list1){
            if(s.length()==1){
                list2.add(s);
            }
        }
        for(String s:list2){
            System.out.println(s);
        }
        //stream流的思想，先得到一条stream流，并把数据放上去，再利用stream流API进行各种操作，中间方法，终结方法
        ArrayList<String> list3=new ArrayList<>();
        Collections.addAll(list3,"a","b","c","d");
        Stream<String> stream3=list3.stream();
        stream3.forEach(new Consumer<String>() {
            public void accept(String s) {
                System.out.println(s);
            }
        });
        /*stream3.forEach(s->{
            System.out.println("s");
        });*/
        HashMap<String,Integer> map=new HashMap<>();
        map.put("a",1);
        map.put("b",2);
        map.put("c",3);
        map.put("d",4);
        map.keySet().stream().forEach(new Consumer<String>() {
            public void accept(String s) {
                System.out.println(s);
            }
        });
        int []arr={1,2,3,4,5};
        //Arrays.stream(arr).forEach(System.out::println);
        Stream.of(1,2,34,54).forEach(System.out::println);
        Stream.of("a","b","c","d").forEach(System.out::println);
        //注意，streamof方法的形参是一个可变参数，可以传递一堆零散数据，也可传递数组
        //数组必须是引用数据类型的，如果传递基本数据类型，是会把整个数组当作一个元素
        Stream.of(arr).forEach(System.out::println);
        //1.单列集合使用collection中的默认方法2.双列集合无法直接使用stream流3.数组则使用Arrays工具类的静态方法4.一堆零散数据则使用stream接口中的静态方法
        //filter
        /*list3.stream().filter(new Predicate<String>() {
            public boolean test(String s) {
                System.out.println(s);
                return true;
            }
        }).forEach(System.out::println);*/
        list3.stream().filter((s)->{
            s.startsWith("a");
             return true;});
         //中间方法使用后会返回一条新的stream流，原来的只能使用一次，建议使用链式编程
        //修改stream流的数据，不会影响原来集合或者数组中的数据



    }
}
