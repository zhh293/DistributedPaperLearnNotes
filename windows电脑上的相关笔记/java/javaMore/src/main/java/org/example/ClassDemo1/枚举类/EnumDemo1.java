package org.example.ClassDemo1.枚举类;

import java.util.Optional;

//枚举类的概述
//作用：枚举用于信息标志和信息分类
//m格式
/*修饰符 enum 枚举类名{
    枚举值1,枚举值2,枚举值3
}*/
//枚举类第一行罗列的必须是枚举类对象的名称，枚举类对象之间用逗号隔开
public class EnumDemo1 {
    public static void main(String[] args) {
        Sex sb=Sex.FEMALE;
        System.out.println(sb);
        sb.show();
        Sex male = Sex.MALE;
        System.out.println(male);
        //为什么这里不是地址  ordinal方法获取的是索引，获取枚举对象里面的索引
        Sex example = Sex.Example;

        /*Sex[] values = Sex.values();
        for (Sex value : values) {
            System.out.println(value);
        }*/
        System.out.println(example.ordinal());
        System.out.println(male.ordinal());
        System.out.println(sb.ordinal());
    }
}
enum Sex {
    MALE{
        public void show(){
            System.out.println("这是一个枚举对象");
        }
    },FEMALE{
        public void show(){
            System.out.println("这是一个枚举对象");
        }
    },
    Example{
        public void show(){
            System.out.println("这是一个枚举对象");
        }
    };
    public abstract void show();
}

/*
public static final Sex BOY;

public static final sex GIRL;

public static sex[] values();

public static sex valueof(java.lang.string);

static {};

枚举类的特点
枚举类是final的，不能被继承
枚举类默认继承了枚举类型
枚举类的构造器是private的
*/

