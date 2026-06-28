package com.zhanghonghao.abstractlianxi1;

public class pingpongcoach extends coach implements teachball,english{
    public pingpongcoach(String name, int age) {
        super(age, name);
    }

    @Override
    public void say() {
        System.out.println("说英语");
    }

    @Override
    public void teach() {
        System.out.println("教打乒乓球");
    }
}
