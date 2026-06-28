package org.example.ClassDemo1.knowledge1;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class thisDemo {
    public static void main(String[] args) {




        Animal animal = new Animal();
        animal.setName("金毛");
        //这里的this：谁调用这个方法，this就代表谁
        System.out.println(animal.getName());
//        this可以区分成员变量和局部变量
         Animal animal2 = new Animal("金茂",12,'男');


       /* /女次

        目标:this关键字知识回顾。

        this关键字的作用:

        this代表了当前对象的引用。

        this关键字可以用在实例方法和构造器中。

        this用在方法中，谁调用这个方法，this就代表谁。

        this用在构造器，代表了构造器正在初始化的那个对象的引用。*/


    }
}
@Data
@AllArgsConstructor
@NoArgsConstructor
class Animal{
    private String name;
    private int age;
    private char gender;
}
class Dog{
    private String name;
    private int age;
    private char gender;
    public Dog(){
        this("zhag",12,'男');
        //细节初始化，哈哈哈哈哈哈哈哈哈哈哈哈
    }
    public Dog(String name, int age, char gender) {
        this.name = name;
        this.age = age;
        this.gender = gender;
    }
}