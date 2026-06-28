package com.zhanghonghao;

import java.util.Scanner;

public class mathquestion {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        //判断质数的效率更高的方法
        //取平方根，看一半就行了
        Long start1=System.currentTimeMillis();
        int number = sc.nextInt();
        for(int i=2;i<=Math.sqrt(number);i++){
            if(number%i==0){
                System.out.println(i);
            }
        }
        Long end1=System.currentTimeMillis();
        System.out.println(end1-start1);
        //自幂数，一个n位自然数等于自身各个数位上数字的n次幂之和
        Long start = System.currentTimeMillis();
        int num1 = sc.nextInt();
        int count=0,replace=num1,num2=num1;
        while(replace!=0){
            count++;
            replace/=10;
        }
        int result=0;
        while(num1!=0){
            result+= (int) Math.pow(num1%10,count);
            num1=num1/10;
        }
        if(result==num2){
            System.out.println(result);
        }
        Long end = System.currentTimeMillis();
        System.out.println(end-start);
    }
}
