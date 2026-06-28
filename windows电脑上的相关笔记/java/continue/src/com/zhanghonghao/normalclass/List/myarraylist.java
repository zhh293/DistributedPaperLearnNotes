package com.zhanghonghao.normalclass.List;

import java.util.Arrays;

public class myarraylist <E>{
    Object[] myarray=new Object[10];
    int size=0;
    //E就是一个不确定的类型
    public boolean add(E e){
        myarray[size]=e;
        size++;
        return true;
    }
    public E get(int index){
        return (E)myarray[index];
    }
    public String toString(){
        return Arrays.toString(myarray);
    }
    //如果你加上E的话，在主类中创建对象的时候必须传入类型

}
