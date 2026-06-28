package com.zhanghonghao.normalclass.lambda;

import java.util.*;

public class lambda implements Collection {
    public static void main(String[] args) {
        //单列集合collection
        //集合体系及结构，一次只能添加一个数据，map双列集合一次可以添加两个数据
        //collection分为list和set,list添加到的元素是有序，可重复，有索引的，有序指的是存和取的顺序是一样的
        //set添加的元素是无序，不重复，无索引
        //而collection是祖宗接口，它的功能是全部单列集合都可以继承使用的
        //collection的方法
        ArrayList list = new ArrayList();
        list.add("hello");
        list.add("world");
        System.out.println(list);
        Set set = new HashSet();
        set.add("hello");
        set.add("world");
        System.out.println(set);
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator iterator() {
        return null;
    }

    @Override
    public Object[] toArray() {
        return new Object[0];
    }

    @Override
    public boolean add(Object o) {
        return false;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean addAll(Collection c) {
        return false;
    }

    @Override
    public void clear() {

    }

    @Override
    public boolean retainAll(Collection c) {
        return false;
    }

    @Override
    public boolean removeAll(Collection c) {
        return false;
    }

    @Override
    public boolean containsAll(Collection c) {
        return false;
    }

    @Override
    public Object[] toArray(Object[] a) {
        return new Object[0];
    }
}
