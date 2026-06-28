package com.zhanghonghao.abstractlianxi1;

public class Main {
    public static void main(String[] args) {
        basketballathlete p=new basketballathlete("张哲闻",18);
        p.play();
        p.setAge(19);
        p.setName("张鸿昊");
        pingpongathlete p1=new pingpongathlete();
        p1.setAge(19);
        p1.setName("张哲闻");
        p1.play();
        p1.say();
    }
}
