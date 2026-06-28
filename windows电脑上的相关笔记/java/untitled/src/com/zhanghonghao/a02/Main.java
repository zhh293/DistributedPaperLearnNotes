package com.zhanghonghao.a02;

import java.util.ArrayList;

public class Main {
    public static void main(String[] args) {
   /*person person = new student("zhhh",13);
   person person1 = new teacher("zzw",11);
   person person3=new administractor("wzx",16);
   show(person);
   show(person1);
   show(person3);
   ArrayList <String>list = new ArrayList<>();
   list.add("6666");
   System.out.println(list);*/
   person person = new student();
   person.getName();
   person.show();
   //person.test();
   student student=(student)person;
   student.test();
   /*teacher teacher=(teacher)person;
   teacher.getName();*/
   if(person instanceof student){
       student student1=(student)person;
       student1.test();
   }
   else if(person instanceof teacher){
       teacher teacher=(teacher)person;
       teacher.show();
   }
   else{
       System.out.println("滚吧");
   }
   //简化版本
   if(person instanceof student student1){
       student1.test();
   }
   else if(person instanceof teacher teacher1){
       teacher1.show();
   }
   else{
       System.out.println("滚吧");
   }
//多态的弊端，不能使用子类特有的功能，如果想引用，需要进行类型转化(自动类型转化，强制类型转化)
teacher teacher=new teacher("zhahna",15);
        System.out.println(teacher.name);







    }
    public static void show(person person){
        person.show();
    }



}
