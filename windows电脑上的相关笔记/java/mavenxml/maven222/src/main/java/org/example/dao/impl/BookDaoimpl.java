package org.example.dao.impl;

import org.example.dao.BookDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

@Repository("BookDao")
public class BookDaoimpl implements BookDao {
    @Value("${name}")//简单类型注入，value中的值可以用配置文件进行配置。哈哈哈哈哈哈哈哈
    private String dataName;
    @Override
    public void save() {
        System.out.println(System.currentTimeMillis());
        System.out.println("book dao save ..."+dataName);
    }
    public void update(){
         System.out.println("book dao update ...");
    }
//    之前的配置文件自动注入，set方法是不可以去掉的，但是这里 为什么可以，没事了
   /* private String dataName;
    private int age;
    public BookDaoimpl(String dataName, int age){
        this.dataName = dataName;
        this.age = age;
    }
    public void setDataName(String dataName) {
        this.dataName = dataName;
    }
    public void setAge(int age) {
        this.age = age;
    }
    public String getDataName() {
        return dataName;
    }
    public int getAge() {
        return age;
    }*/
}
