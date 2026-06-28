package com.zhanghonghao.normalclass.List;

public class taidi extends dog{

    public taidi(String name, int age) {
        super(name, age);
    }

    @Override
    public void eat() {
        System.out.println("一只叫做"+this.getName()+"的"+this.getAge()+"岁的波斯猫，正在吃骨头，边吃边蹭");
    }
}
