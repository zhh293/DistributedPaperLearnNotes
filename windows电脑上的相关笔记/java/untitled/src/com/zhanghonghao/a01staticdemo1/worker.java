package com.zhanghonghao.a01staticdemo1;

public class worker {
    String name;
    int salary;
    String  ID;
    public worker() {}

    public worker(String name, String ID, int salary) {
        this.salary = salary;
        this.name = name;
        this.ID = ID;
    }
    public void setName(String name) {
        this.name = name;
    }
    public void setSalary(int salary) {
        this.salary = salary;
    }
    public void setID(String ID) {
        this.ID = ID;
    }
    public String getName() {
        return name;
    }
    public int getSalary() {
        return salary;
    }
    public String getID() {
        return ID;
    }
    public void work(){
        System.out.println("工作");
    }
    public void eat(){
        System.out.println("吃饭");
    }
}
