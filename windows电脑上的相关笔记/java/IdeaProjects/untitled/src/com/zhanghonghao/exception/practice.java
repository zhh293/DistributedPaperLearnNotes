package com.zhanghonghao.exception;

import java.util.Scanner;

public class practice {
    public static void main(String[] args) {
        String name;
        int age;
        Scanner sc = new Scanner(System.in);
        while(true){
            try{
                name=sc.next();
                age=sc.nextInt();
              nameage(name,age);
              break;
            }catch(Exception e){
                e.printStackTrace();
                continue;
            }
        }
        System.out.println("录入成功");
    }
    public static void nameage(String name,int age){
        if(age<18||age>40){
            throw new AgeoutException(age+"有问题");
        }
        if(name.length()<3||name.length()>10){
            throw new NameFormatException(name+"有问题");
        }
    }
}
