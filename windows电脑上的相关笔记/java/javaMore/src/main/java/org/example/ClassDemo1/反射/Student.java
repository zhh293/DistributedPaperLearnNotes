package org.example.ClassDemo1.反射;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Student {
    private String name;
    private int age;
    public void show()
    {
        System.out.println("show()");
    }
    public void show(String name)
    {
        System.out.println("show(String name)");
    }
    public void show(String name,int age)
    {
        System.out.println("show(String name,int age)");
    }
    private void show(int age,String name)
    {
        System.out.println("show(int age,String name)");
    }
   // private Student(){}
    public Student(){}
    public Student(String name)
    {
        this.name = name;
    }
}
