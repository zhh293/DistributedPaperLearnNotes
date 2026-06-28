package com.zhanghonghao.set;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class hashset {
    public static void main(String[] args) {
        /*Collection 是单列集合的祖宗接口，它的功能是全部单列集合都可以继承使用的。
方法名称	说明
public boolean add(E e)	把给定的对象添加到当前集合中
public void clear()	清空集合中所有的元素
public boolean remove(E e)	把给定的对象在当前集合中删除
public boolean contains(Object obj)	判断当前集合中是否包含给定的对象
public boolean isEmpty()	判断当前集合是否为空
public int size()	返回集合中元素的个数 / 集合的长度*/
        /*Collection<String> collection = new HashSet<>();
        collection.add("a");
        collection.add("b");
        collection.add("c");
        collection.add("d");
        collection.add("e");
        for (String string : collection) {
            System.out.println(string);
        }
        //第二种方式
        collection.forEach(x->{
            System.out.println(x);
        });
       //第三种方式，迭代器
        Iterator<String> iterator = collection.iterator();
        while (iterator.hasNext()) {
            System.out.println(iterator.next());
        }*/
        Set<String> set = new HashSet<String>();
        set.add("a");
        set.add("b");
        set.add("c");
        set.add("d");
        //遍历方法与上述一模一样，千万要记住集合不能够有重复元素，ok吧，哈哈哈哈哈哈哈哈哈哈哈哈
        /*Set 系列集合的特点
无序、不重复、无索引
Set 集合的方法上基本上与 Collection 的 API 一致
Set 集合的实现类特点
HashSet: 无序、不重复、无索引
LinkedHashSet: 有序、不重复、无索引
TreeSet: 可排序、不重复、无索引*/
        /*哈希值是对象的整数形式*/





    }
}
