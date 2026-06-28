package com.zhanghonghao.normalclass.lambda;

import java.util.ArrayList;
import java.util.Collection;

public class practice {
    public static void main(String[] args) {
        //接口多态，前半部分代表实现了该接口的对象，后面代表所创建的对象的种类
        //接口多态就是有好几个类同时实现了一个接口，在后面的main函数中创建实现该接口的对象时，可以有更多的选择，new不同的类。
        Collection collection = new ArrayList();
        collection.add(1);
        collection.add(2);
        System.out.println(collection);
        collection.clear();
        System.out.println(collection);
        collection.add(3);
        collection.add(4);
        boolean s1=collection.remove(2);
        boolean s2=collection.remove(3);
        System.out.println(s1);
        System.out.println(s2);
        System.out.println(collection);
        boolean s3=collection.contains(1);
        System.out.println(s3);
        System.out.println(collection);
        //contains方法在底层依赖equals方法判断对象是否一致，即通过地址值来判断是否一致
        //如果存的是自定义对象，没有重写equals方法，那默认用的是object类中的
        //所以如果你想通过属性值判断是否相等，则需要重写equals方法
        //而其它类型的数据已经自动帮你写好了，不需要自己重写了
        collection.remove(4);
        System.out.println(collection.isEmpty());

    }
}
