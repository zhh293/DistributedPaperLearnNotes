package com.zhanghonghao.a01staticdemo1;

public class Student {
    String name;
    int age;
    String school;
    public Student(String name, int age, String school) {
        this.name = name;
        this.age = age;
        this.school = school;
    }
    public Student() {
        //为本类的成员变量设置初始值，一般来说用默认初始值就可以了
        //细节，使用这个结构之后，虚拟机就不会再添加super()
        //this(null, -1, "创制教育");
    }
}
