package com.zhanghonghao.methods;

import java.util.Arrays;
import java.util.Comparator;

public class methods0 {
    public static void main(String[] args) {
        //方法引用
        //把已经有的方法拿来用，当作函数式接口中抽象方法的方法体
        //被引用方法必须存在，被引用方法的形参和返回值需要跟抽象方法保持一致，被引用方法功能必须满足功能
        Integer []arr={1,2,3,4,5};
       /* Arrays.sort(arr,new Comparator<Integer>(){
            public int compare(Integer o1, Integer o2) {
                return o2-o1;
            }
        });*/
        /*Arrays.sort(arr,(o1,o2)->{
            return o1-o2;
        });*/
        //极简的方法引用
        Arrays.sort(arr,methods0::sort);
        System.out.println(Arrays.toString(arr));
        int num=sort(1,2);
        System.out.println(num);
        //方法引用的深入！！！！！！！






    }
    public static int sort(int num1,int num2){
        return num1-num2;
    }
}
