package org.example.ClassDemo1.函数式编程;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.ClassDemo1.HomeWorkDemo.Student;

import java.util.ArrayList;
import java.util.List;

public class Demo1 {
    public static void main(String[] args) {
        // 函数式接口
        // 函数式接口：只有一个抽象方法
        // 函数式接口的实例：Lambda表达式
        // 函数式接口的实例：方法引用


        //函数的对象
        //参数->函数体(逻辑部分)
       /* new Thread(() -> {
            System.out.println("线程启动了");
        }).start();*/
        List<Student> list = new ArrayList<>();
        list.add(new Student("张三",18,"男"));
        list.add(new Student("张三",19,"男"));
        list.add(new Student("张三",20,"男"));
        //所以可以这么来写，这样一个函数就可以实现不同的过滤操作，函数式接口，直接写自己想要的核心逻辑就可以了，不用再重新写一些逻辑
        filter(list, new Lambda() {
            @Override
            public boolean test(Student student) {
                return student.getAge() > 18;
            }
        });
        //当然，上面的代码可以进行简化，这不就很爽了吗，哈哈哈
        List<Student> filter = filter(list, student -> student.getAge() > 18);
        System.out.println(filter);


    }
    static List<Student> filter(List<Student> list, Lambda lambda){
        List<Student> result = new ArrayList<>();
        for (Student student : list) {
            if (lambda.test(student)){
                result.add(student);
            }
        }
        return result;
    }
    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    static class Student{
        private String name;
        private int age;
        private String sex;

    }
}
interface Lambda{
    boolean test(Demo1.Student  student);
}
//相当于定一一个接口方法，然后实现方法
