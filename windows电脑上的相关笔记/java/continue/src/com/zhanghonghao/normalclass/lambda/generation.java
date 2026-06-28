package com.zhanghonghao.normalclass.lambda;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;

public class generation {
    public static void main(String[] args) {
        //迭代器遍历
        //迭代器在java中的类是iterator
        Collection collection =new ArrayList();
        collection.add(1);
        collection.add(2);
        collection.add(3);
        Iterator iterator = collection.iterator();
        while (iterator.hasNext()) {
        System.out.println(iterator.next());
        iterator.remove();
        }
        //迭代器遍历完毕，指针不会复位，迭代器遍历时，不能用集合的方法进行增加和删除
        //循环中只能使用一次next方法(获取元素，移动指针)，如果元素个数为偶数还好，奇数的话就轧钢了
        //增强for遍历，只有单列集合和数组才能用此方法遍历
        Collection collection1 =new ArrayList();
        collection1.add(1);
        collection1.add(2);
        collection1.add(3);
        for(Object s:collection1){
            System.out.println(s);
        }
        //lambda表达式进行遍历
        //foreach
        //底层原理：也是自己遍历集合，依次得到每一个元素，把得到的每一个元素传递给下面的accept方法
        collection1.forEach(new Consumer<Integer>() {
            public void accept(Integer integer) {
                System.out.println(integer);
            }
        });
        collection1.forEach((Integer)->{
            System.out.println(Integer);
        });


    }
}
