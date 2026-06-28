package com.zhanghonghao.normalclass.lambda;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Consumer;

public class list {
    public static void main(String[] args) {
        //list特有方法
        List<String> list = new ArrayList<String>();
        list.add("a");
        list.add("b");
        list.add("c");
        //add可以在指定位置插入元素，细节：原来索引及之后的元素都会往后移动
        System.out.println(list);
        String s=list.remove(0);
        list.remove("b");
        System.out.println(list);
        //List集合删除元素的两种方法
        //1.直接删除元素2.根据索引删除元素
        //直接往remove方法里面传入整型会默认传入的是索引
        //因为在调用方法的时候，如果方法出现了重载的现象会优先调用实参跟形参类型一致的方法
        List<Integer>list2=new ArrayList<>();
        list2.add(1);
        list2.add(2);
        list2.add(3);
        list2.remove(1);
        Integer i=Integer.valueOf(1);
        list2.remove(i);
        System.out.println(list2);
        //即remove方法不会自动装箱的
        //传入的参数如果是一个对象，就删除对象
        //set方法，修改指定索引处的元素，返回被修改的元素
        int s1=list2.set(0,100000);
        System.out.println(s1);
        //get方法，返回指定索引处的元素
        //遍历方式
        //1.迭代器
        List<String>list3=new ArrayList<>();
        list3.add("a");
        list3.add("b");
        list3.add("c");
        list3.add("d");
        Iterator<String> iterator=list3.iterator();
        while(iterator.hasNext()){
            System.out.println(iterator.next());
        }
        //2.增强for
        for(String s12:list3){
            System.out.println(s12);
        }
        //lambda表达式
        list3.forEach(new Consumer<String>() {
            public void accept(String s) {
                System.out.println(s);
            }
        });
        list3.forEach(hrt->{
            System.out.println(hrt);
        });
        //列表迭代器,在遍历的过程中可以添加元素，牛逼66666
        //list3调用的那个方法返回值是一个实现了listiterator接口的对象,soga肆内，芜湖
        ListIterator<String> listIterator=list3.listIterator();
        while(listIterator.hasNext()){
            System.out.println(listIterator.next());
        }

    }
}
