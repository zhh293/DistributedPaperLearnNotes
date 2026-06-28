package com.zhh.handsome.mongodb;

import org.bson.types.ObjectId;

public class User {
    private ObjectId id;
    private String name;
    private int age;
    private String email;
    private String[] hobbies;
    private boolean isStudent;

    // 构造方法
    public User(String name, int age, String email, String[] hobbies, boolean isStudent) {
        this.name = name;
        this.age = age;
        this.email = email;
        this.hobbies = hobbies;
        this.isStudent = isStudent;
    }

    // getter和setter方法
    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String[] getHobbies() {
        return hobbies;
    }

    public void setHobbies(String[] hobbies) {
        this.hobbies = hobbies;
    }

    public boolean isStudent() {
        return isStudent;
    }

    public void setStudent(boolean student) {
        isStudent = student;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", age=" + age +
                ", email='" + email + '\'' +
                ", hobbies=" + String.join(",", hobbies) +
                ", isStudent=" + isStudent +
                '}';
    }
}

