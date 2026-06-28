package org.example.springbootwebquick;

public class user {
   private String name;
   private int age;
   private ADDRESS address;
    public user(){}
    public user(String name, int age, ADDRESS address) {
        this.name = name;
        this.age = age;
        this.address = address;
    }
    public void setName(String name) {
        this.name = name;
    }
    public void setAge(int age) {
        this.age = age;
    }
    public String getName() {
        return name;

    }
    public int getAge() {
        return age;
    }

    public void setAddress(ADDRESS address) {
        this.address = address;
    }
    public ADDRESS getAddress() {
        return address;
    }
}
