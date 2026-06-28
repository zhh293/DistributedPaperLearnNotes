package com.zhanghonghao.doublearraylist;

import java.util.*;
import java.util.Map;
import java.util.function.BiConsumer;

public class hahaha1 {
    public static void main(String[] args) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        //遍历方法
        //1.键找值
        map.put("a", 1);
        map.put("b", 2);
        map.put("c", 3);
        //创建一个只含有key对象的集合
        Set<String> set = map.keySet();
        //set.forEach(System.out::println);
        /*for (String key : set) {
            System.out.println(key);
            Integer value = map.get(key);
            System.out.println(value);
        }*/
        //set.iterator().forEachRemaining(System.out::println);
        //2.、entry方法,通过一个方法获取所有的键值对对象，返回一个set集合
        Set<Map.Entry<String, Integer>> entries = map.entrySet();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            String key = entry.getKey();
            Integer value = entry.getValue();
            System.out.println(key);
            System.out.println(value);
        }
        Iterator<Map.Entry<String, Integer>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            String key = entry.getKey();
            Integer value = entry.getValue();
            System.out.println(key);
            System.out.println(value);
        }
        entries.forEach(System.out::println);
        //lambda表达式遍历，foreach方法
        map.forEach(new BiConsumer<String, Integer>() {
            public void accept(String key, Integer value) {
                System.out.println(key + ":" + value);
            }
        });
        map.forEach((k, v) -> System.out.println(k + ":" + v));
        //hashmap底层原理
    }
}
